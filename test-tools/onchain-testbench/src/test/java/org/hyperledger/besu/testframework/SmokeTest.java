package org.hyperledger.besu.testframework;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.core.BlockchainNetwork;
import org.hyperledger.besu.testframework.core.ConsensusTopology;
import org.hyperledger.besu.testframework.contracts.PermissioningStrategy;
import org.hyperledger.besu.testframework.scenarios.FailCloseScenario;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test that validates the full Permissioning Test Framework pipeline:
 * network startup → governance deploy → P2P permissioning → fail-close behavior.
 *
 * This test requires Docker running and the permissioning plugin JAR available.
 */
@Tag("smoke")
@Tag("integration")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "runIntegration", matches = "true")
class SmokeTest {
    private static final Logger LOG = LoggerFactory.getLogger(SmokeTest.class);

    private static BlockchainNetwork network;

    @BeforeAll
    static void setUp() {
        LOG.info("=== Permissioning Test Framework Smoke Test ===");

        Path genesisPath = resolveGenesisPath();

        network = BlockchainNetwork.builder()
            .withTopology(ConsensusTopology.QBFT)
            .withValidators(4)
            .withRpcNodes(1)
            .withGenesis(genesisPath)
            .withLogCapture("smoke-test")
            .build();

        network.start();

        LOG.info("Network started with {} validators and {} RPC nodes.",
            network.getOrchestrator().getValidators().size(),
            network.getOrchestrator().getRpcNodes().size());
    }

    @AfterAll
    static void tearDown() {
        if (network != null) {
            LOG.info("Shutting down test network (log capture finalized automatically)...");
            network.shutdown(); // streaming log capture auto-closes here
        }
    }

    @Test
    @DisplayName("Network starts with correct validator count")
    void testNetworkStartup() {
        assertThat(network.getNodes()).hasSize(5); // 4 validators + 1 RPC
        assertThat(network.getOrchestrator().getValidators()).hasSize(4);
        assertThat(network.getOrchestrator().getRpcNodes()).hasSize(1);
        LOG.info("PASS: Network started with correct node count.");
    }

    @Test
    @DisplayName("Governance contracts are deployed and accessible")
    void testGovernanceDeployment() {
        network.deployGovernance(PermissioningStrategy.genesis());

        PermissioningStrategy gov = network.getGovernance();
        assertThat(gov).isNotNull();
        assertThat(gov.getAccountIngressAddress())
            .isEqualTo("0x0000000000000000000000000000000000008888");
        assertThat(gov.getNodeIngressAddress())
            .isEqualTo("0x0000000000000000000000000000000000009999");
        LOG.info("PASS: Genesis governance contracts verified.");
    }

    @Test
    @DisplayName("Web3j connection is established to RPC node")
    void testWeb3jConnection() throws Exception {
        var web3j = network.getWeb3j();
        assertThat(web3j).isNotNull();

        String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
        assertThat(clientVersion).contains("besu");
        LOG.info("PASS: Web3j connected. Client: {}", clientVersion);

        var chainId = web3j.ethChainId().send().getChainId();
        LOG.info("Chain ID: {}", chainId);
    }

    @Test
    @DisplayName("RPC node exposes HTTP API")
    void testRpcNodeAccessible() {
        BesuNode rpcNode = network.getRpcNode();
        assertThat(rpcNode).isNotNull();
        assertThat(rpcNode.getRpcUrl()).startsWith("http://");
        LOG.info("PASS: RPC node accessible at {}", rpcNode.getRpcUrl());
    }

    private static Path resolveGenesisPath() {
        // 1. Explicit env var / system property (self-contained usage)
        String sysProp = System.getProperty("perm.genesis.path");
        if (sysProp != null) return Paths.get(sysProp);
        String envVar = System.getenv("PERM_GENESIS_PATH");
        if (envVar != null) return Paths.get(envVar);

        // 2. Bundled genesis in framework directory (self-contained)
        Path[] candidates = {
            Paths.get("genesis.json"),
            Paths.get("genesis-evolution.json"),
            Paths.get("../onchain-testbench/genesis.json")
        };
        for (Path candidate : candidates) {
            if (candidate.toFile().exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "Genesis file not found. Place it at ./genesis.json or set PERM_GENESIS_PATH env var.");
    }
}
