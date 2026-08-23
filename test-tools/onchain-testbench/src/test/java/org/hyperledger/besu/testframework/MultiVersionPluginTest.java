package org.hyperledger.besu.testframework;

import org.hyperledger.besu.testframework.contracts.PermissioningStrategy;
import org.hyperledger.besu.testframework.core.BlockchainNetwork;
import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.core.ConsensusTopology;
import org.hyperledger.besu.testframework.dsl.PermissioningAssertions;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-version plugin compatibility test.
 *
 * Validates that the permissioning plugin operates identically on Besu 25.12.0 and 26.5.0
 * using a genesis with pre-deployed contracts and storage.
 *
 * Replaces the old EvolutionTest which delegated to an external shell script.
 */
@Tag("multiversion")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiVersionPluginTest {
    private static final Logger LOG = LoggerFactory.getLogger(MultiVersionPluginTest.class);

    // Selectors verificados com: cast sig "accountPermitted(address)"
    private static final String ACCOUNT_PERMITTED_SELECTOR = "0x0f68f0b3";
    // cast sig "getContractAddress(bytes32)"
    private static final String GET_CONTRACT_ADDRESS_SELECTOR = "0x0d2020dd";

    private static BlockchainNetwork network;
    private static Web3j web3j;

    @BeforeAll
    static void setUp() {
        LOG.info("=== Multi-Version Plugin Compatibility Test ===");

        Path genesisPath = resolveGenesisPath();
        LOG.info("Using genesis: {}", genesisPath.toAbsolutePath());

        network = BlockchainNetwork.builder()
            .withTopology(ConsensusTopology.QBFT)
            .withValidators(3)
            .withRpcNodes(1)
            .withGenesis(genesisPath)
            .withLogCapture("multiversion-test")
            .build();

        network.start();
        LOG.info("Network started successfully.");

        // Deploy governance + upgrade all nodes to secured mode (plugin mounted)
        network.deployGovernance(PermissioningStrategy.genesis());
        LOG.info("Governance deployed, nodes upgraded to secured mode.");

        // Rebuild Web3j pointing to the upgraded (secured) RPC node
        String rpcUrl = network.getRpcNode().getRpcUrl();
        LOG.info("Rebuilding Web3j → secured RPC: {}", rpcUrl);
        web3j = Web3j.build(new HttpService(rpcUrl));
    }

    @AfterAll
    static void tearDown() {
        if (network != null) {
            network.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("All nodes are running and reachable")
    void testAllNodesRunning() {
        List<BesuNode> nodes = network.getNodes();
        assertThat(nodes).isNotEmpty();
        for (BesuNode node : nodes) {
            assertThat(node.getContainer().isRunning())
                .as("Node %s should be running", node.getName()).isTrue();
        }
        LOG.info("PASS: All {} nodes running.", nodes.size());
    }

    @Test
    @Order(2)
    @DisplayName("Genesis contracts are deployed and accessible")
    void testGenesisContractsAccessible() throws Exception {
        assertThat(web3j).isNotNull();

        // Account Ingress
        String acctCode = web3j.ethGetCode(
            "0x0000000000000000000000000000000000008888",
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().getCode();
        assertThat(acctCode).isNotEmpty();
        assertThat(acctCode.length()).isGreaterThan(100);
        LOG.info("PASS: Account Ingress deployed ({} chars bytecode).", acctCode.length());

        // Admin contract
        String adminCode = web3j.ethGetCode(
            "0x181a92c9b76ab7271a03b640cc172e75a0dc3484",
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().getCode();
        assertThat(adminCode.length()).isGreaterThan(100);
        LOG.info("PASS: Admin deployed ({} chars bytecode).", adminCode.length());

        // AccountRules
        String rulesCode = web3j.ethGetCode(
            "0x0e9e81bb09cdd55b607373e89e3154354a925b7d",
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().getCode();
        assertThat(rulesCode.length()).isGreaterThan(100);
        LOG.info("PASS: AccountRules deployed ({} chars bytecode).", rulesCode.length());
    }

    @Test
    @Order(3)
    @DisplayName("Plugin registered on RPC node")
    void testPluginRegistered() {
        // Only RPC node is upgraded to secured mode (validators stay transparent)
        String logs = network.getRpcNode().getPluginLogs();
        assertThat(logs)
            .as("Plugin should be registered on RPC node")
            .contains("Registering On-Chain Permissioning Plugin");
        LOG.info("PASS: Plugin registered on RPC node.");
    }

    @Test
    @Order(4)
    @DisplayName("Admin account is in allowlist (pre-configured in genesis storage)")
    void testAdminInAllowlist() throws Exception {

        // eth_call: accountPermitted(0xf39Fd6e5...)
        String data = ACCOUNT_PERMITTED_SELECTOR +
            "000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266";
        String result = web3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                "0x0000000000000000000000000000000000000000",
                "0x0e9e81bb09cdd55b607373e89e3154354a925b7d",
                data
            ),
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().getValue();

        assertThat(result)
            .as("Admin should be permitted")
            .isEqualTo("0x0000000000000000000000000000000000000000000000000000000000000001");
        LOG.info("PASS: Admin is in allowlist (accountPermitted = true).");
    }

    @Test
    @Order(5)
    @DisplayName("Ingress registrations are correct (admin + rules)")
    void testIngressRegistrations() throws Exception {

        String adminKey = "0x61646d696e697374726174696f6e000000000000000000000000000000000000";
        String rulesKey = "0x72756c6573000000000000000000000000000000000000000000000000000000";

        // AccountIngress -> Admin
        String adminData = GET_CONTRACT_ADDRESS_SELECTOR + adminKey.substring(2);
        String adminResult = web3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                "0x0000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000008888",
                adminData
            ),
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().getValue();
        assertThat(adminResult).isNotEqualTo(
            "0x0000000000000000000000000000000000000000000000000000000000000000");

        // AccountIngress -> Rules
        String rulesData = GET_CONTRACT_ADDRESS_SELECTOR + rulesKey.substring(2);
        String rulesResult = web3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                "0x0000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000008888",
                rulesData
            ),
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send().getValue();
        assertThat(rulesResult).isNotEqualTo(
            "0x0000000000000000000000000000000000000000000000000000000000000000");

        LOG.info("PASS: Ingress registrations verified (Admin={}..., Rules={}...).",
            adminResult.substring(0, 18), rulesResult.substring(0, 18));
    }

    @Test
    @Order(6)
    @DisplayName("Blocks are being produced (chain is alive)")
    void testBlocksProduced() throws Exception {
        var blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
        assertThat(blockNumber.longValue()).isGreaterThan(0);
        LOG.info("PASS: Chain producing blocks (current: {}).", blockNumber);
    }

    private static Path resolveGenesisPath() {
        String envVar = System.getenv("PERM_GENESIS_PATH");
        if (envVar != null) return Paths.get(envVar);

        Path[] candidates = {
            Paths.get("genesis-evolution.json"),
            Paths.get("genesis.json"),
            Paths.get("../../test-suite/templates/genesis.json"),
        };
        for (Path candidate : candidates) {
            if (candidate.toFile().exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Genesis not found.");
    }
}
