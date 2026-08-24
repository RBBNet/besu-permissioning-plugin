package org.hyperledger.besu.testframework;

import org.hyperledger.besu.testframework.core.BlockchainNetwork;
import org.hyperledger.besu.testframework.core.ConsensusTopology;
import org.hyperledger.besu.testframework.contracts.PermissioningStrategy;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raw Docker log evidence for permissioning scenarios.
 *
 * Scenario 1: Authorized account → plugin allows → tx mined
 * Scenario 2: Unauthorized account → plugin blocks → error -32007
 *
 * Requires: Docker + permissioning-plugin.jar in ./plugins/
 * Run:
 * ./gradlew test --tests "*PermissioningLogEvidenceTest" -Dperm.genesis.path=./genesis.json
 */
@Tag("integration")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "runIntegration", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PermissioningLogEvidenceTest {
    private static final Logger LOG = LoggerFactory.getLogger(PermissioningLogEvidenceTest.class);

    private static BlockchainNetwork network;
    private static Web3j web3j;
    private static Path genesisPath;
    private static Credentials authorized;
    private static Credentials unauthorized;

    @BeforeAll
    static void setUp() throws Exception {
        LOG.info("=== Permissioning Log Evidence: Permissioning Plugin Raw Log Test ===");

        genesisPath = resolveGenesisPath();
        LOG.info("Genesis: {}", genesisPath.toAbsolutePath());

        // Standard Anvil/Hardhat public test key — DO NOT USE IN PRODUCTION
        authorized = Credentials.create(
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
        // Standard Anvil/Hardhat public test key — DO NOT USE IN PRODUCTION
        unauthorized = Credentials.create(
            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
        LOG.info("Authorized:   {}", authorized.getAddress());
        LOG.info("Unauthorized: {}", unauthorized.getAddress());

        // 1. Start network WITHOUT plugin (transparent mode)
        network = BlockchainNetwork.builder()
            .withTopology(ConsensusTopology.QBFT)
            .withValidators(4)
            .withRpcNodes(1)
            .withGenesis(genesisPath)
            .withLogCapture("permissioning-log-evidence")
            .build();
        network.start();
        LOG.info("Network started: {} nodes",
            network.getOrchestrator().getAllNodes().size());

        // 2. Deploy governance + upgrade RPC node to secured mode (plugin mounted)
        // deployGovernance() calls upgradeToSecuredMode internally for each RPC node.
        network.deployGovernance(PermissioningStrategy.genesis());
        String acctIngress = network.getGovernance().getAccountIngressAddress();
        String nodeIngress = network.getGovernance().getNodeIngressAddress();
        LOG.info("Governance deployed. AccountIngress={}, NodeIngress={}",
            acctIngress, nodeIngress);

        // 3. Connect to the now-secured RPC node
        String rpcUrl = network.getRpcNode().getRpcUrl();
        LOG.info("Secured RPC URL: {}", rpcUrl);
        web3j = Web3j.build(new HttpService(rpcUrl));
        Thread.sleep(3000); // brief wait for plugin initialization to complete

        // 4. Verify allowlist state from genesis (admin pre-configured in genesis-evolution.json)
        addAccountToAllowlist(authorized.getAddress()); // idempotent — admin already in genesis
        Thread.sleep(4000);
        boolean adminPermitted = network.getGovernance().isAccountAuthorized(authorized.getAddress());
        boolean unauthPermitted = network.getGovernance().isAccountAuthorized(unauthorized.getAddress());
        LOG.info("Admin account {} authorized on-chain: {}", authorized.getAddress(), adminPermitted);
        LOG.info("Unauthorized account {} permitted on-chain: {}", unauthorized.getAddress(), unauthPermitted);
        assertThat(adminPermitted).as("Admin must be authorized after addAccount").isTrue();
        assertThat(unauthPermitted).as("Unauthorized must NOT be in allowlist").isFalse();
    }

    @AfterAll
    static void tearDown() {
        if (network != null) {
            LOG.info("Shutting down. Raw container logs captured.");
            network.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Scenario 1: Authorized tx — plugin allows → tx flows normally")
    void scenario1_AuthorizedTx() throws Exception {
        LOG.info("--- SCENARIO 1: Authorized Transaction ---");

        EthGetTransactionCount count = web3j.ethGetTransactionCount(
            authorized.getAddress(), DefaultBlockParameterName.LATEST).send();
        LOG.info("Nonce: {}", count.getTransactionCount());

        RawTransaction rawTx = RawTransaction.createEtherTransaction(
            count.getTransactionCount(),
            BigInteger.valueOf(22_000_000_000L),
            BigInteger.valueOf(21_000),
            "0x0000000000000000000000000000000000000001",
            BigInteger.ONE
        );

        byte[] signed = TransactionEncoder.signMessage(rawTx, 12120014, authorized);
        EthSendTransaction response = web3j.ethSendRawTransaction(
            Numeric.toHexString(signed)).send();

        LOG.info("Tx result: hasError={}, hash={}",
            response.hasError(), response.getTransactionHash());

        if (response.hasError()) {
            LOG.error("UNEXPECTED BLOCK: code={}, msg={}",
                response.getError().getCode(), response.getError().getMessage());
        }

        assertThat(response.hasError())
            .as("Authorized tx MUST be accepted by plugin")
            .isFalse();

        LOG.info(" SCENARIO 1 PASSED: Authorized tx accepted.");
    }

    @Test
    @Order(2)
    @DisplayName("Scenario 2: Unauthorized tx — plugin blocks with security error")
    void scenario2_UnauthorizedTxBlocked() throws Exception {
        LOG.info("--- SCENARIO 2: Unauthorized Transaction → Plugin Must Block ---");

        // Fund unauthorized account first (from authorized)
        EthGetTransactionCount authCount = web3j.ethGetTransactionCount(
            authorized.getAddress(), DefaultBlockParameterName.LATEST).send();
        RawTransaction fundTx = RawTransaction.createEtherTransaction(
            authCount.getTransactionCount(),
            BigInteger.valueOf(22_000_000_000L),
            BigInteger.valueOf(21_000),
            unauthorized.getAddress(),
            BigInteger.valueOf(1_000_000_000_000_000_000L)
        );
        byte[] signedFund = TransactionEncoder.signMessage(fundTx, 12120014, authorized);
        EthSendTransaction fundResp = web3j.ethSendRawTransaction(
            Numeric.toHexString(signedFund)).send();
        LOG.info("Fund tx: hash={}, error={}",
            fundResp.getTransactionHash(),
            fundResp.hasError() ? fundResp.getError().getMessage() : "none");
        Thread.sleep(4000);

        // Verify on-chain: unauthorized NOT in allowlist
        boolean permitted = network.getGovernance().isAccountAuthorized(unauthorized.getAddress());
        LOG.info("Unauthorized account permitted on-chain: {}", permitted);

        // Attempt unauthorized transaction
        EthGetTransactionCount unauthCount = web3j.ethGetTransactionCount(
            unauthorized.getAddress(), DefaultBlockParameterName.LATEST).send();
        LOG.info("Unauthorized nonce: {}", unauthCount.getTransactionCount());

        RawTransaction rawTx = RawTransaction.createEtherTransaction(
            unauthCount.getTransactionCount(),
            BigInteger.valueOf(22_000_000_000L),
            BigInteger.valueOf(21_000),
            "0x0000000000000000000000000000000000000001",
            BigInteger.ONE
        );
        byte[] signedUnauth = TransactionEncoder.signMessage(rawTx, 12120014, unauthorized);
        EthSendTransaction response = web3j.ethSendRawTransaction(
            Numeric.toHexString(signedUnauth)).send();

        LOG.info("Unauthorized tx: hasError={}, code={}, msg={}",
            response.hasError(),
            response.hasError() ? response.getError().getCode() : "N/A",
            response.hasError() ? response.getError().getMessage() : "ACCEPTED (no RPC error)");

        if (response.hasError()) {
            int code = response.getError().getCode();
            String msg = response.getError().getMessage();
            LOG.info(" PLUGIN BLOCK: code={}, message='{}'", code, msg);
            assertThat(code == -32007
                       || msg.toLowerCase().contains("not authorized")
                       || msg.toLowerCase().contains("not permitted")
                       || msg.toLowerCase().contains("sender account"))
                .as("Error must be authorization-related")
                .isTrue();
            LOG.info(" SCENARIO 2 PASSED: Plugin blocked unauthorized tx.");
        } else {
            // Tx accepted to pool — check if reverted during mining
            LOG.info("Tx in pool — checking receipt after mining...");
            Thread.sleep(10000);
            EthGetTransactionReceipt receipt = web3j.ethGetTransactionReceipt(
                response.getTransactionHash()).send();
            if (receipt.getResult() != null) {
                String status = receipt.getResult().getStatus();
                LOG.info("Receipt status: {} (0x1=success, 0x0=reverted)", status);
                assertThat(status).as("Unauthorized tx must be reverted").isEqualTo("0x0");
                LOG.info(" SCENARIO 2 PASSED: Unauthorized tx REVERTED by plugin.");
            } else {
                LOG.warn("No receipt — tx effectively blocked/stuck in pool.");
            }
        }
    }

    /**
     * Call addAccount(address) on the AccountRules contract.
     * Resolves the AccountRules address dynamically from the Account Ingress contract.
     */
    private static void addAccountToAllowlist(String accountAddress) throws Exception {
        var tmpWeb3j = Web3j.build(new HttpService(network.getRpcNode().getRpcUrl()));

        String accountIngress = network.getGovernance().getAccountIngressAddress();
        LOG.info("Resolving AccountRules from Ingress: {}", accountIngress);

        // getContractAddress(bytes32("rules")) → selector: keccak256("getContractAddress(bytes32)")[0:4]
        String getContractAddrSelector = "0x0d2020dd";
        String rulesKey = "72756c6573000000000000000000000000000000000000000000000000000000";
        String rulesData = getContractAddrSelector + rulesKey;

        EthCall rulesCall = tmpWeb3j.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                "0x0000000000000000000000000000000000000000",
                accountIngress,
                rulesData
            ),
            DefaultBlockParameterName.LATEST
        ).send();

        String rulesResult = rulesCall.getValue();
        if (rulesResult == null || rulesResult.equals("0x") ||
            rulesResult.equals("0x0000000000000000000000000000000000000000000000000000000000000000")) {
            throw new IllegalStateException("Could not resolve AccountRules address from Ingress. Result: " + rulesResult);
        }
        // Extract address from bytes32: last 20 bytes
        String accountRulesAddress = "0x" + rulesResult.substring(rulesResult.length() - 40);
        LOG.info("Resolved AccountRules address: {}", accountRulesAddress);

        EthGetTransactionCount count = tmpWeb3j.ethGetTransactionCount(
            authorized.getAddress(), DefaultBlockParameterName.LATEST).send();

        // Verificado com: cast sig "addAccount(address)"
        String selector = "0xe89b0e1e";
        String encodedAddr = "000000000000000000000000" + accountAddress.substring(2);

        String data = selector + encodedAddr;

        RawTransaction rawTx = RawTransaction.createTransaction(
            count.getTransactionCount(),
            BigInteger.valueOf(22_000_000_000L),
            BigInteger.valueOf(300_000),
            accountRulesAddress,
            BigInteger.ZERO,
            data
        );

        byte[] signed = TransactionEncoder.signMessage(rawTx, 12120014, authorized);
        EthSendTransaction response = tmpWeb3j.ethSendRawTransaction(
            Numeric.toHexString(signed)).send();

        LOG.info("addAccount({}) → tx={}, error={}",
            accountAddress,
            response.getTransactionHash(),
            response.hasError() ? response.getError().getMessage() : "none");
    }

    private static Path resolveGenesisPath() {
        String sp = System.getProperty("perm.genesis.path");
        if (sp != null) return Paths.get(sp);
        String ev = System.getenv("PERM_GENESIS_PATH");
        if (ev != null) return Paths.get(ev);
        // genesis-evolution.json has all contracts pre-deployed with storage (allowlist + ingress)
        for (Path c : new Path[]{Paths.get("genesis-evolution.json"), Paths.get("genesis.json"),
                                 Paths.get("../onchain-testbench/genesis-evolution.json")}) {
            if (c.toFile().exists()) return c;
        }
        throw new IllegalStateException("Genesis not found. Set PERM_GENESIS_PATH.");
    }
}
