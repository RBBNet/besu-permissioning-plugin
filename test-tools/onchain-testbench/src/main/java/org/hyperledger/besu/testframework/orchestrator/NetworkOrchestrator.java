package org.hyperledger.besu.testframework.orchestrator;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.core.ConsensusTopology;
import org.hyperledger.besu.testframework.metrics.GrafanaContainer;
import org.hyperledger.besu.testframework.metrics.PrometheusContainer;
import org.hyperledger.besu.testframework.reporting.DockerLogCapture;
import com.github.dockerjava.api.model.ContainerNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.web3j.crypto.ECKeyPair;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the lifecycle of a Testcontainers-based Besu network.
 *
 * Peer-discovery strategy:
 * - Enode IDs are pre-computed from deterministic Hardhat private keys.
 * - When start() runs, the bootnode container IP is retrieved from the Docker
 *   daemon after the bootnode starts. The actual IP (not a DNS hostname) is
 *   used in --bootnodes for all other nodes, satisfying Besu's requirement that
 *   the enode host is an IP address.
 * - No curl calls, no admin_addPeer, no named Docker volumes.
 */
public class NetworkOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkOrchestrator.class);

    private final Network dockerNetwork;
    private final List<BesuNode> nodes = new ArrayList<>();
    private final List<BesuNode> validators = new ArrayList<>();
    private final List<BesuNode> rpcNodes = new ArrayList<>();
    private BesuNode bootnode;
    private ConsensusTopology topology;
    private Path genesisPath;
    private boolean metricsEnabled = false;
    private PrometheusContainer prometheus;
    private GrafanaContainer grafana;
    private DockerLogCapture logCapture;

    // Pre-computed enode ID for the bootnode (from its private key via ECKeyPair)
    private String bootnodeEnodeId;
    // Key-index offset: ensures validators and RPC nodes receive non-overlapping keys
    private int nextKeyIndex = 0;
    // Resolved after bootnode starts — contains the bootnode's actual container IP
    private String resolvedBootnodeEnodeUrl;

    private NetworkOrchestrator(Path genesisPath) {
        this.dockerNetwork = Network.newNetwork();
        this.genesisPath = genesisPath;
    }

    public static NetworkOrchestrator withGenesis(Path genesisPath) {
        return new NetworkOrchestrator(genesisPath);
    }

    /**
     * Sets up a QBFT validator set. The first validator is the bootnode.
     * Its enode ID is pre-computed from its private key; the actual TCP endpoint
     * (IP) is resolved in start() after the bootnode container is running.
     */
    public NetworkOrchestrator withValidators(int count) {
        this.topology = ConsensusTopology.QBFT;
        String[] keys = generateKeys(nextKeyIndex, count);
        nextKeyIndex += count;

        // Pre-compute enode ID of bootnode (key #0 of this validator set)
        this.bootnodeEnodeId = computeEnodeId(keys[0]);
        LOG.info("Bootnode enode ID pre-computed (IP resolved in start()): {}", bootnodeEnodeId);

        for (int i = 0; i < count; i++) {
            String name = "validator-" + (i + 1);
            String enodeKey = keys[i];
            BesuNode node;
            if (i == 0) {
                // Bootnode: no --bootnodes flag (it IS the bootnode)
                node = BesuNodeFactory.createBootnode(name, dockerNetwork, genesisPath, enodeKey);
                node.setEnodeId(bootnodeEnodeId);
                this.bootnode = node;
            } else {
                // Non-bootnode validators: --bootnodes is injected in start()
                node = BesuNodeFactory.createValidator(name, dockerNetwork, genesisPath, enodeKey, null);
            }
            nodes.add(node);
            validators.add(node);
            if (logCapture != null) logCapture.attachTo(node);
        }
        return this;
    }

    /**
     * Adds non-validator RPC nodes. Key indices are offset past the validator
     * key range to avoid enode ID collisions. --bootnodes is injected in start().
     */
    public NetworkOrchestrator withRpcNodes(int count) {
        String[] keys = generateKeys(nextKeyIndex, count);
        nextKeyIndex += count;

        for (int i = 0; i < count; i++) {
            String name = "rpc-node-" + (i + 1);
            BesuNode node = BesuNodeFactory.createRpcNode(
                name, dockerNetwork, genesisPath, keys[i], null);
            nodes.add(node);
            rpcNodes.add(node);
            if (logCapture != null) logCapture.attachTo(node);
        }
        return this;
    }

    public NetworkOrchestrator withMetrics() {
        this.metricsEnabled = true;
        return this;
    }

    /**
     * Starts all nodes in the network.
     *
     * Order:
     * 1. Bootnode starts first (no peers needed).
     * 2. Retrieve the bootnode's container IP from the Docker daemon.
     * 3. Inject --bootnodes=enode://<id>@<ip>:30303 into non-bootnode containers
     *    BEFORE starting them. Besu requires an IP address (not a hostname) in
     *    the --bootnodes parameter, so we cannot use DNS aliases here.
     * 4. Start RPC nodes and observability stack.
     */
    public List<BesuNode> start() {
        LOG.info("Starting Besu permissioning network with {} validators and {} RPC nodes",
            validators.size(), rpcNodes.size());

        // 1. Start bootnode
        if (bootnode != null) {
            LOG.info("Starting bootnode: {}", bootnode.getName());
            bootnode.getContainer().start();

            // 2. Resolve the bootnode's actual Docker network IP
            String bootnodeIp = getContainerIp(bootnode.getContainer());
            this.resolvedBootnodeEnodeUrl = "enode://" + bootnodeEnodeId + "@" + bootnodeIp + ":30303";
            LOG.info("Bootnode IP: {}. Enode URL: {}", bootnodeIp, resolvedBootnodeEnodeUrl);
        }

        // 3. Start remaining validators with the real --bootnodes URL
        for (BesuNode validator : validators) {
            if (validator == bootnode) continue;
            LOG.info("Starting validator: {}", validator.getName());
            if (resolvedBootnodeEnodeUrl != null) {
                // Inject --bootnodes with real IP before container starts
                validator.getContainer().withCommand(
                    BesuNodeFactory.buildValidatorCommand(resolvedBootnodeEnodeUrl));
            }
            validator.getContainer().start();
        }

        // 4. Start RPC nodes with the real --bootnodes URL
        for (BesuNode rpc : rpcNodes) {
            LOG.info("Starting RPC node: {}", rpc.getName());
            if (resolvedBootnodeEnodeUrl != null) {
                rpc.getContainer().withCommand(
                    BesuNodeFactory.buildRpcCommand(resolvedBootnodeEnodeUrl));
            }
            rpc.getContainer().start();
        }

        // 5. Observability stack
        if (metricsEnabled) {
            LOG.info("Starting observability stack (Prometheus + Grafana)...");
            try {
                prometheus = new PrometheusContainer(dockerNetwork, nodes);
                if (logCapture != null) {
                    prometheus.getContainer().withLogConsumer(logCapture.createConsumer("prometheus"));
                }
                prometheus.start();
                LOG.info("Prometheus ready at {}", prometheus.getApiUrl());

                grafana = new GrafanaContainer(dockerNetwork, "prometheus");
                if (logCapture != null) {
                    grafana.getContainer().withLogConsumer(logCapture.createConsumer("grafana"));
                }
                grafana.start();
                LOG.info("Grafana ready at {}", grafana.getUrl());
            } catch (Exception e) {
                LOG.warn("Observability stack failed to start: {}. Continuing.", e.getMessage());
                if (prometheus != null && prometheus.isRunning()) prometheus.stop();
                if (grafana != null && grafana.isRunning()) grafana.stop();
                prometheus = null;
                grafana = null;
            }
        }

        LOG.info("Network startup complete. {} nodes running.", nodes.size());
        return Collections.unmodifiableList(nodes);
    }

    public NetworkOrchestrator withLogCapture(String testId) {
        this.logCapture = DockerLogCapture.start(testId);
        return this;
    }

    public DockerLogCapture getLogCapture() {
        return logCapture;
    }

    public Path captureLogs(String testId) {
        return DockerLogCapture.captureAll(this, testId);
    }

    public void shutdown() {
        LOG.info("Shutting down network ({} nodes)...", nodes.size());

        if (logCapture != null) {
            logCapture.close();
            logCapture = null;
        }
        if (grafana != null) {
            try { grafana.stop(); } catch (Exception e) {
                LOG.warn("Error stopping Grafana: {}", e.getMessage());
            }
        }
        if (prometheus != null) {
            try { prometheus.stop(); } catch (Exception e) {
                LOG.warn("Error stopping Prometheus: {}", e.getMessage());
            }
        }
        for (BesuNode node : nodes) {
            try { node.terminate(); } catch (Exception e) {
                LOG.warn("Error stopping node {}: {}", node.getName(), e.getMessage());
            }
        }
        dockerNetwork.close();
        LOG.info("Network shutdown complete.");
    }

    /**
     * Creates an external peer not in the initial validator set.
     * Must be called after start() so resolvedBootnodeEnodeUrl is available.
     */
    public BesuNode createExternalPeer(String name) {
        if (bootnode == null) {
            throw new IllegalStateException("Cannot create external peer without a bootnode.");
        }
        String enodeKey = generateKeys(nextKeyIndex, 1)[0];
        nextKeyIndex++;
        BesuNode rogue = BesuNodeFactory.createRogueNode(
            name, dockerNetwork, genesisPath, enodeKey, resolvedBootnodeEnodeUrl);
        rogue.getContainer().start();
        return rogue;
    }

    public Network getDockerNetwork() { return dockerNetwork; }
    public BesuNode getBootnode() { return bootnode; }
    public List<BesuNode> getValidators() { return Collections.unmodifiableList(validators); }
    public List<BesuNode> getRpcNodes() { return Collections.unmodifiableList(rpcNodes); }

    public BesuNode getRpcNode(int index) {
        if (index >= rpcNodes.size()) {
            throw new IndexOutOfBoundsException("RPC node index " + index + " out of bounds");
        }
        return rpcNodes.get(index);
    }

    public void replaceRpcNode(int index, BesuNode upgraded) {
        BesuNode old = rpcNodes.set(index, upgraded);
        nodes.remove(old);
        nodes.add(upgraded);
    }

    public void replaceValidator(int index, BesuNode upgraded) {
        BesuNode old = validators.set(index, upgraded);
        nodes.remove(old);
        nodes.add(upgraded);
    }

    public List<BesuNode> getAllNodes() { return Collections.unmodifiableList(nodes); }
    public PrometheusContainer getPrometheus() { return prometheus; }
    public GrafanaContainer getGrafana() { return grafana; }
    public boolean isMetricsEnabled() { return metricsEnabled; }

    // -- Internal helpers --

    /**
     * Gets the container's primary IP in its Docker network after start().
     */
    private static String getContainerIp(GenericContainer<?> container) {
        return container.getContainerInfo()
            .getNetworkSettings()
            .getNetworks()
            .values()
            .stream()
            .findFirst()
            .map(ContainerNetwork::getIpAddress)
            .orElseThrow(() -> new IllegalStateException(
                "Cannot determine container IP — container not connected to any network"));
    }

    /**
     * Derives the 128-hex-char enode node ID from a secp256k1 private key.
     * Result matches what Besu computes from the same key file at /opt/besu/data/key.
     */
    private static String computeEnodeId(String hexPrivKey) {
        BigInteger privKeyInt = Numeric.toBigInt(hexPrivKey);
        ECKeyPair kp = ECKeyPair.create(privKeyInt);
        return Numeric.toHexStringNoPrefixZeroPadded(kp.getPublicKey(), 128);
    }

    // Hardhat/Anvil deterministic keys. Account #0 address: 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
    // This matches the validator address in genesis-evolution.json extraData.
    private static final String[] HARDHAT_KEYS = {
        "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", // account #0
        "59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d", // account #1
        "5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a", // account #2
        "7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6", // account #3
        "47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a", // account #4
        "8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba", // account #5
        "92db14e403b83dfe3df233f83dfa3a0d7096f21ca9b0d6d6b8d88b2b4ec1564e", // account #6
        "4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356", // account #7
        "dbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97", // account #8
        "2a871d0798f97d79848a013d4936a73bf4cc922c825d33c1cf7073dff6d409c6", // account #9
    };

    private String[] generateKeys(int startIndex, int count) {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            int idx = startIndex + i;
            keys[i] = (idx < HARDHAT_KEYS.length)
                ? HARDHAT_KEYS[idx]
                : String.format("%064d", idx + 1);
        }
        return keys;
    }
}
