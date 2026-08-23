package org.hyperledger.besu.testframework.orchestrator;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Factory for creating BesuNode instances with proper volume mounts,
 * environment variables, and command-line arguments.
 *
 * Each container uses its own writable layer for data storage — no named Docker
 * volumes are used, so stale volume state from previous test runs never causes
 * genesis-mismatch errors. Genesis-based tests (genesis-evolution.json) get the
 * full pre-deployed world state on every fresh start.
 *
 * Private keys are mounted deterministically from the Hardhat test vector set so
 * that enode IDs can be pre-computed before containers start, enabling static
 * --bootnodes configuration without curl or any runtime RPC call.
 */
public class BesuNodeFactory {
    private static final Logger LOG = LoggerFactory.getLogger(BesuNodeFactory.class);

    private static final String DEFAULT_BESU_IMAGE = "hyperledger/besu:latest";

    private static String resolveBesuImage() {
        String override = System.getenv("BESU_IMAGE_OVERRIDE");
        return (override != null && !override.isEmpty()) ? override : DEFAULT_BESU_IMAGE;
    }
    private static final int DEFAULT_RPC_PORT = 8545;
    private static final int DEFAULT_P2P_PORT = 30303;

    /**
     * Creates a validator node for the QBFT network.
     */
    public static BesuNode createValidator(String name, Network network, Path genesisPath,
                                            String enodeKey, String bootnodeEnode) {
        GenericContainer<?> container = buildBaseContainer(name, network, genesisPath, enodeKey)
            .withEnv("BESU_RPC_HTTP_API", "ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            .withEnv("BESU_RPC_HTTP_ENABLED", "false")
            .withCommand(buildValidatorCommand(bootnodeEnode))
            .waitingFor(Wait.forLogMessage(".*Ethereum main loop is up.*", 1))
            .withStartupTimeout(Duration.ofSeconds(120));

        BesuNode node = new BesuNode(name, BesuNode.Role.VALIDATOR, container,
            DEFAULT_RPC_PORT, DEFAULT_P2P_PORT, enodeKey);
        node.setMetricsEnabled(true);
        return node;
    }

    /**
     * Creates a bootnode (anchor validator) for the network.
     */
    public static BesuNode createBootnode(String name, Network network, Path genesisPath,
                                           String enodeKey) {
        GenericContainer<?> container = buildBaseContainer(name, network, genesisPath, enodeKey)
            .withEnv("BESU_RPC_HTTP_API", "ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            .withEnv("BESU_RPC_HTTP_ENABLED", "true")
            .withEnv("BESU_METRICS_ENABLED", "true")
            .withCommand("--data-storage-format=FOREST --sync-min-peers=1")
            .waitingFor(Wait.forLogMessage(".*Ethereum main loop is up.*", 1))
            .withStartupTimeout(Duration.ofSeconds(120));

        BesuNode node = new BesuNode(name, BesuNode.Role.BOOTNODE, container,
            DEFAULT_RPC_PORT, DEFAULT_P2P_PORT, enodeKey);
        node.setMetricsEnabled(true);
        return node;
    }

    /**
     * Creates an RPC/writer node (non-validator) with HTTP RPC exposed.
     */
    public static BesuNode createRpcNode(String name, Network network, Path genesisPath,
                                          String enodeKey, String bootnodeEnode) {
        GenericContainer<?> container = buildBaseContainer(name, network, genesisPath, enodeKey)
            .withEnv("BESU_RPC_HTTP_API", "ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            .withEnv("BESU_RPC_HTTP_ENABLED", "true")
            .withExposedPorts(DEFAULT_RPC_PORT)
            .withCommand(buildRpcCommand(bootnodeEnode))
            .waitingFor(Wait.forLogMessage(".*Ethereum main loop is up.*", 1))
            .withStartupTimeout(Duration.ofSeconds(120));

        BesuNode node = new BesuNode(name, BesuNode.Role.RPC, container,
            DEFAULT_RPC_PORT, DEFAULT_P2P_PORT, enodeKey);
        node.setMetricsEnabled(true);
        return node;
    }

    /**
     * Creates a rogue/external node (non-permissioned, for testing).
     */
    public static BesuNode createRogueNode(String name, Network network, Path genesisPath,
                                            String enodeKey, String bootnodeEnode) {
        GenericContainer<?> container = buildBaseContainer(name, network, genesisPath, enodeKey)
            .withEnv("BESU_RPC_HTTP_API", "ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            .withEnv("BESU_RPC_HTTP_ENABLED", "false")
            .withCommand(buildValidatorCommand(bootnodeEnode))
            .waitingFor(Wait.forLogMessage(".*Ethereum main loop is up.*", 1))
            .withStartupTimeout(Duration.ofSeconds(120));

        BesuNode node = new BesuNode(name, BesuNode.Role.ROGUE, container,
            DEFAULT_RPC_PORT, DEFAULT_P2P_PORT, enodeKey);
        node.setMetricsEnabled(true);
        return node;
    }

    /**
     * Upgrades a transparent node to secured mode with the permissioning plugin.
     *
     * The new container starts fresh from genesis — no named Docker volume is used.
     * For genesis-based strategies (genesis-evolution.json), all world state is
     * encoded in the genesis alloc so a fresh start is always correct.
     * The same private key is mounted so the node keeps its enode identity.
     */
    public static BesuNode upgradeToSecuredMode(BesuNode transparentNode,
                                                  String accountIngressAddress,
                                                  String nodeIngressAddress,
                                                  Path genesisPath) {
        LOG.info("Upgrading node {} to secured mode (Account Ingress: {}, Node Ingress: {})",
            transparentNode.getName(), accountIngressAddress, nodeIngressAddress);

        GenericContainer<?> oldContainer = transparentNode.getContainer();

        transparentNode.terminate();

        // Fresh container — no named volume, no fixed name.
        // Genesis supplies the full world state; stale Docker volumes are never a problem.
        GenericContainer<?> securedContainer = new GenericContainer<>(resolveBesuImage())
            .withNetwork(oldContainer.getNetwork())
            .withNetworkAliases(transparentNode.getName())
            .withCopyFileToContainer(
                MountableFile.forHostPath(genesisPath.toAbsolutePath()),
                "/opt/besu/genesis.json")
            .withEnv("BESU_DATA_PATH", "/opt/besu/data")
            .withEnv("BESU_GENESIS_FILE", "/opt/besu/genesis.json")
            .withEnv("BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS", accountIngressAddress)
            .withEnv("BESU_PERMISSIONS_NODES_CONTRACT_ADDRESS", nodeIngressAddress)
            .withEnv("BESU_RPC_HTTP_API", "ADMIN,ETH,TXPOOL,NET,QBFT,WEB3,DEBUG,TRACE,PERM")
            .withEnv("BESU_RPC_HTTP_ENABLED",
                transparentNode.getRole() == BesuNode.Role.RPC ? "true" : "false")
            .withEnv("BESU_METRICS_ENABLED", "true")
            .withEnv("BESU_HOST_ALLOWLIST", "*")
            .withExposedPorts(DEFAULT_RPC_PORT, 9545)
            .withCommand(oldContainer.getCommandParts())
            .waitingFor(Wait.forLogMessage(".*Ethereum main loop is up.*", 1))
            .withStartupTimeout(Duration.ofSeconds(120));

        // Maintain the same node private key so the enode ID is stable across the restart
        mountKeyFile(securedContainer, transparentNode.getEnodeKey());

        // Mount plugin JAR for permissioning
        mountPlugin(securedContainer);

        securedContainer.start();

        return new BesuNode(
            transparentNode.getName(), transparentNode.getRole(),
            securedContainer, DEFAULT_RPC_PORT, DEFAULT_P2P_PORT,
            transparentNode.getEnodeKey()
        );
    }

    // -- Private helpers --

    private static GenericContainer<?> buildBaseContainer(String name, Network network,
                                                           Path genesisPath, String enodeKey) {
        GenericContainer<?> container = new GenericContainer<>(resolveBesuImage())
            .withNetwork(network)
            .withNetworkAliases(name)
            .withCreateContainerCmdModifier(cmd -> cmd.withName(name))
            .withEnv("BESU_DATA_PATH", "/opt/besu/data")
            .withEnv("BESU_GENESIS_FILE", "/opt/besu/genesis.json")
            .withEnv("BESU_MIN_GAS_PRICE", "0")
            .withEnv("BESU_RPC_HTTP_CORS_ORIGINS", "*")
            .withEnv("BESU_HOST_ALLOWLIST", "*")
            .withEnv("BESU_METRICS_ENABLED", "true")
            .withEnv("BESU_METRICS_HOST", "0.0.0.0")
            .withEnv("BESU_METRICS_PORT", "9545")
            .withCopyFileToContainer(
                MountableFile.forHostPath(genesisPath.toAbsolutePath()),
                "/opt/besu/genesis.json");

        // Mount deterministic private key so the enode ID is stable and pre-computable
        mountKeyFile(container, enodeKey);

        return container;
    }

    private static void mountKeyFile(GenericContainer<?> container, String hexPrivKey) {
        if (hexPrivKey == null || hexPrivKey.isEmpty()) return;
        try {
            Path keyFile = Files.createTempFile("besu-nodekey-", "");
            Files.writeString(keyFile, hexPrivKey);
            keyFile.toFile().deleteOnExit();
            container.withCopyFileToContainer(
                MountableFile.forHostPath(keyFile.toAbsolutePath()),
                "/opt/besu/data/key");
        } catch (IOException e) {
            LOG.warn("Could not create node key file: {}. Besu will generate a random key.", e.getMessage());
        }
    }

    /**
     * Mounts the permissioning plugin JAR into the container, if available.
     */
    private static void mountPlugin(GenericContainer<?> container) {
        String pluginJarPath = findPluginJar();
        if (pluginJarPath != null) {
            container.withCopyFileToContainer(
                MountableFile.forHostPath(pluginJarPath),
                "/opt/besu/plugins/permissioning-plugin.jar"
            );
        }
    }

    static String buildValidatorCommand(String bootnodeEnode) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("--logging=DEBUG");
        cmd.append(" --data-storage-format=FOREST");
        cmd.append(" --sync-min-peers=1");
        if (bootnodeEnode != null && !bootnodeEnode.isEmpty()) {
            cmd.append(" --bootnodes=").append(bootnodeEnode);
        }
        return cmd.toString();
    }

    static String buildRpcCommand(String bootnodeEnode) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("--logging=DEBUG");
        cmd.append(" --data-storage-format=FOREST");
        cmd.append(" --sync-min-peers=1");
        cmd.append(" --rpc-http-host=0.0.0.0");
        if (bootnodeEnode != null && !bootnodeEnode.isEmpty()) {
            cmd.append(" --bootnodes=").append(bootnodeEnode);
        }
        return cmd.toString();
    }

    /**
     * Locates the permissioning plugin JAR.
     * Search order: env var PERM_PLUGIN_PATH, ./plugins/, ./build/libs/, ../plugin-permissioned-rbb-integra/
     */
    private static String findPluginJar() {
        String envPath = System.getenv("PERM_PLUGIN_PATH");
        if (envPath != null) {
            File f = new File(envPath);
            if (f.exists()) {
                LOG.info("Found plugin JAR via PERM_PLUGIN_PATH: {}", f.getAbsolutePath());
                return f.getAbsolutePath();
            }
        }

        String[] candidates = {
            "plugins/permissioning-plugin.jar",
            "plugins/onchain-permissioning-plugin.jar",
            "build/libs/onchain-permissioning-plugin.jar",
            "../../build/libs/onchain-permissioning-plugin.jar",
            "../../build/libs/permissioning-plugin.jar",
            "../rbb-network/plugins/permissioning-plugin.jar"
        };
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.exists()) {
                LOG.info("Found plugin JAR at: {}", f.getAbsolutePath());
                return f.getAbsolutePath();
            }
        }
        LOG.warn("Permissioning plugin JAR not found. Set PERM_PLUGIN_PATH or place JAR in ./plugins/");
        LOG.warn("Nodes will start without permissioning plugin.");
        return null;
    }
}
