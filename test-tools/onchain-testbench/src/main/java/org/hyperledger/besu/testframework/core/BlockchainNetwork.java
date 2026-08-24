package org.hyperledger.besu.testframework.core;

import org.hyperledger.besu.testframework.contracts.PermissioningStrategy;
import org.hyperledger.besu.testframework.orchestrator.BesuNodeFactory;
import org.hyperledger.besu.testframework.orchestrator.NetworkOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.DefaultGasProvider;

import java.nio.file.Path;
import java.util.List;

/**
 * Top-level DSL entry point for orchestrating a permissioned Besu blockchain network.
 *
 * <pre>{@code
 * BlockchainNetwork network = BlockchainNetwork.builder()
 *     .withTopology(ConsensusTopology.QBFT)
 *     .withValidators(4)
 *     .withRpcNodes(1)
 *     .withGenesis(Paths.get("genesis.json"))
 *     .build();
 *
 * network.start();
 * network.deployGovernance(PermissioningStrategy.gen02());
 *
 * BesuNode unauthorized = network.createExternalPeer("rogue-01");
 * unauthorized.connectTo(network.getValidator(0));
 *
 * PermissioningAssertions.assertThat(unauthorized)
 *     .isNotConnectedTo(network.getValidator(0))
 *     .hasLogMatch("PermissioningPlugin: P2P connection DENIED");
 * }</pre>
 */
public class BlockchainNetwork implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BlockchainNetwork.class);

    private final ConsensusTopology topology;
    private final int numValidators;
    private final int numRpcNodes;
    private final Path genesisPath;
    private final NetworkOrchestrator orchestrator;
    private PermissioningStrategy governance;
    private List<BesuNode> nodes;
    private Web3j web3j;
    private Thread shutdownHook;

    private BlockchainNetwork(Builder builder) {
        this.topology = builder.topology;
        this.numValidators = builder.numValidators;
        this.numRpcNodes = builder.numRpcNodes;
        this.genesisPath = builder.genesisPath;
        this.orchestrator = NetworkOrchestrator.withGenesis(genesisPath);
    }

    /**
     * Creates a new Builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the network: bootnode, then validators, then RPC nodes.
     * The network starts in transparent mode (no permissioning); deploy governance,
     * then upgrade nodes to secured mode.
     */
    public BlockchainNetwork start() {
        LOG.info("========================================");
        LOG.info("Starting Permissioned Blockchain Network");
        LOG.info("  Topology: {}", topology);
        LOG.info("  Validators: {}", numValidators);
        LOG.info("  RPC Nodes: {}", numRpcNodes);
        LOG.info("========================================");

        orchestrator.withValidators(numValidators);
        orchestrator.withRpcNodes(numRpcNodes);
        this.nodes = orchestrator.start();

        // Setup Web3j connection via the first RPC node
        if (!orchestrator.getRpcNodes().isEmpty()) {
            BesuNode rpcNode = orchestrator.getRpcNode(0);
            String rpcUrl = rpcNode.getRpcUrl();
            LOG.info("Connecting Web3j to RPC node: {}", rpcUrl);
            this.web3j = Web3j.build(new HttpService(rpcUrl));
        }

        // Register shutdown hook for cleanup on unexpected JVM exit
        shutdownHook = new Thread(this::shutdown, "besu-network-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        return this;
    }

    /**
     * Deploys the permissioning governance contracts and upgrades validators
     * to secured mode using the Plugin + Ingress pattern.
     */
    public BlockchainNetwork deployGovernance(PermissioningStrategy strategy) {
        LOG.info("Deploying governance strategy: {}", strategy.getClass().getSimpleName());

        if (web3j == null) {
            throw new IllegalStateException("Network must be started before deploying governance. Call start() first.");
        }

        try {
            strategy.executeDeploy(web3j);

            String accountIngress = strategy.getAccountIngressAddress();
            String nodeIngress = strategy.getNodeIngressAddress();

            LOG.info("Governance deployed:");
            LOG.info("  Account Ingress: {}", accountIngress);
            LOG.info("  Node Ingress: {}", nodeIngress);

            this.governance = strategy;

            // Only upgrade RPC nodes to secured mode — upgrading validators
            // breaks QBFT consensus (containers recreated, enode IPs change).
            for (int i = 0; i < orchestrator.getRpcNodes().size(); i++) {
                BesuNode transparent = orchestrator.getRpcNodes().get(i);
                LOG.info("Upgrading RPC node {} to secured mode...", transparent.getName());
                BesuNode secured = BesuNodeFactory.upgradeToSecuredMode(
                    transparent, accountIngress, nodeIngress, genesisPath);
                orchestrator.replaceRpcNode(i, secured);
            }
        } catch (Exception e) {
            LOG.error("Failed to deploy governance: {}", e.getMessage(), e);
            throw new RuntimeException("Governance deployment failed", e);
        }

        return this;
    }

    /**
     * Shuts down the entire network and releases all resources.
     */
    public void shutdown() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
            shutdownHook = null;
        }
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
    }

    /**
     * Enables try-with-resources usage.
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * Creates an external peer that will attempt to join the network.
     * This peer is NOT in the NodeRules allowlist by default — it should be rejected
     * unless explicitly authorized via governance.
     */
    public BesuNode createExternalPeer(String name) {
        return orchestrator.createExternalPeer(name);
    }

    // -- Accessors --

    public ConsensusTopology getTopology() {
        return topology;
    }

    public PermissioningStrategy getGovernance() {
        return governance;
    }

    public List<BesuNode> getNodes() {
        return nodes;
    }

    public BesuNode getValidator(int index) {
        return orchestrator.getValidators().get(index);
    }

    public BesuNode getRpcNode() {
        return orchestrator.getRpcNode(0);
    }

    public Web3j getWeb3j() {
        return web3j;
    }

    public NetworkOrchestrator getOrchestrator() {
        return orchestrator;
    }

    /**
     * Builder for BlockchainNetwork following the standard builder pattern.
     */
    public static class Builder {
        private ConsensusTopology topology = ConsensusTopology.QBFT;
        private int numValidators = 4;
        private int numRpcNodes = 1;
        private Path genesisPath;
        private String logCaptureTestId;

        public Builder withTopology(ConsensusTopology topology) {
            this.topology = topology;
            return this;
        }

        public Builder withValidators(int count) {
            this.numValidators = count;
            return this;
        }

        public Builder withRpcNodes(int count) {
            this.numRpcNodes = count;
            return this;
        }

        public Builder withGenesis(Path genesisPath) {
            this.genesisPath = genesisPath;
            return this;
        }

        /**
         * Enables real-time streaming Docker log capture.
         * Logs are written continuously to {@code docs/reports/docker-logs/<testId>/}
         * from the moment each container starts until shutdown.
         */
        public Builder withLogCapture(String testId) {
            this.logCaptureTestId = testId;
            return this;
        }

        public BlockchainNetwork build() {
            if (genesisPath == null) {
                throw new IllegalStateException("Genesis path is required. Call withGenesis() before build().");
            }
            BlockchainNetwork network = new BlockchainNetwork(this);
            if (logCaptureTestId != null) {
                network.orchestrator.withLogCapture(logCaptureTestId);
            }
            return network;
        }
    }
}
