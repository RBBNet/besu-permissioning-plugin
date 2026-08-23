package org.hyperledger.besu.testframework.contracts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Uint;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Strategy that uses the pre-deployed Ingress contracts at the genesis addresses
 * (0x8888 for AccountIngress, 0x9999 for NodeIngress).
 *
 * This is the default strategy for scenarios that use the standard genesis file
 * where the permissioning contracts are already baked into the world state.
 */
public class GenesisStrategy implements PermissioningStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(GenesisStrategy.class);

    private static final String GENESIS_ACCOUNT_INGRESS = "0x0000000000000000000000000000000000008888";
    private static final String GENESIS_NODE_INGRESS = "0x0000000000000000000000000000000000009999";

    // Function signatures (4-byte selectors)
    private static final String TX_ALLOWED_SIG = "transactionAllowed(address,address,uint256,uint256,uint256,bytes)";
    private static final String GET_CONTRACT_ADDR_SIG = "getContractAddress(bytes32)";

    private Web3j web3j;
    private String accountIngressAddress;
    private String nodeIngressAddress;

    public GenesisStrategy() {
        this(GENESIS_ACCOUNT_INGRESS, GENESIS_NODE_INGRESS);
    }

    public GenesisStrategy(String accountIngressAddress, String nodeIngressAddress) {
        this.accountIngressAddress = accountIngressAddress;
        this.nodeIngressAddress = nodeIngressAddress;
    }

    @Override
    public void executeDeploy(Web3j web3j) throws Exception {
        this.web3j = web3j;
        LOG.info("Using pre-deployed genesis contracts:");
        LOG.info("  Account Ingress: {}", accountIngressAddress);
        LOG.info("  Node Ingress: {}", nodeIngressAddress);

        // Verify contracts are accessible
        String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
        LOG.info("Connected to Besu: {}", clientVersion);

        BigInteger chainId = web3j.ethChainId().send().getChainId();
        LOG.info("Chain ID: {}", chainId);
    }

    @Override
    public String getAccountIngressAddress() {
        return accountIngressAddress;
    }

    @Override
    public String getNodeIngressAddress() {
        return nodeIngressAddress;
    }

    @Override
    public String authorizeNode(String enodeId) throws Exception {
        throw new UnsupportedOperationException(
            "Node authorization via genesis strategy requires the Admin contract. " +
            "Use the admin RPC methods or create a custom strategy.");
    }

    @Override
    public String authorizeAccount(String accountAddress) throws Exception {
        throw new UnsupportedOperationException(
            "Account authorization via genesis strategy requires the Admin contract.");
    }

    @Override
    public String revokeNode(String enodeId) throws Exception {
        throw new UnsupportedOperationException("Not implemented for genesis strategy");
    }

    @Override
    public String revokeAccount(String accountAddress) throws Exception {
        throw new UnsupportedOperationException("Not implemented for genesis strategy");
    }

    @Override
    public boolean isAccountAuthorized(String address) throws Exception {
        if (web3j == null) {
            throw new IllegalStateException("Web3j not initialized. Call executeDeploy() first.");
        }
        return checkAccountAllowed(address, "0x0000000000000000000000000000000000000000");
    }

    @Override
    public boolean isNodeAuthorized(String enodeId) throws Exception {
        // Node authorization check requires the connectionAllowed call
        // which needs enode high/low bytes. Simplified for genesis strategy.
        LOG.warn("isNodeAuthorized is a simplified check. Full validation requires the plugin simulation.");
        return true;
    }

    /**
     * Low-level simulation of a transactionAllowed check through the Ingress contract.
     * This mirrors what the Besu plugin does: it simulates a call to the Ingress,
     * which delegates to the Rules contract.
     */
    private boolean checkAccountAllowed(String sender, String target) {
        try {
            // Build the ABI-encoded payload for transactionAllowed
            String selector = "0x" + keccak256Selector(TX_ALLOWED_SIG);

            // Encode: address sender (32 bytes), address target (32 bytes),
            //         uint256 value (32), uint256 gasPrice (32), uint256 gasLimit (32),
            //         offset for bytes payload (32), then bytes data
            String encodedSender = padTo32Bytes(sender);
            String encodedTarget = padTo32Bytes(target);
            String encodedValue = padTo32Bytes("0x0");
            String encodedGasPrice = padTo32Bytes("0x0");
            String encodedGasLimit = padTo32Bytes("0x0");
            String encodedOffset = padTo32Bytes("0xc0"); // offset = 6 * 32 = 192 = 0xc0
            String encodedBytesLength = padTo32Bytes("0x0"); // empty bytes payload
            String data = selector + encodedSender + encodedTarget + encodedValue
                        + encodedGasPrice + encodedGasLimit + encodedOffset + encodedBytesLength;

            EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    "0x0000000000000000000000000000000000000000",
                    accountIngressAddress,
                    data
                ),
                DefaultBlockParameterName.LATEST
            ).send();

            if (response.hasError()) {
                LOG.error("Error calling transactionAllowed: {}", response.getError().getMessage());
                return false;
            }

            String result = response.getValue();
            // Check if result is non-zero (true)
            return result != null && !result.equals("0x") &&
                   !result.equals("0x0000000000000000000000000000000000000000000000000000000000000000");

        } catch (Exception e) {
            LOG.error("Failed to simulate transactionAllowed: {}", e.getMessage());
            return false;
        }
    }

    // -- ABI encoding helpers --

    private static String keccak256Selector(String signature) {
        byte[] hash = Hash.sha3(signature.getBytes(StandardCharsets.UTF_8));
        return Numeric.toHexString(hash).substring(2, 10); // first 4 bytes
    }

    private static String padTo32Bytes(String hex) {
        String clean = Numeric.cleanHexPrefix(hex);
        StringBuilder sb = new StringBuilder();
        int padLen = 64 - clean.length();
        for (int i = 0; i < padLen; i++) {
            sb.append('0');
        }
        sb.append(clean);
        return sb.toString();
    }

    // -- Contract ABI snippets embedded as constants --

    /**
     * ABI snippet for AccountIngress.transactionAllowed.
     * Used by the framework to understand the interface without needing generated wrappers.
     */
    public static final String ACCOUNT_INGRESS_ABI = """
    [
      {
        "inputs": [
          {"name":"sender","type":"address"},
          {"name":"target","type":"address"},
          {"name":"value","type":"uint256"},
          {"name":"gasPrice","type":"uint256"},
          {"name":"gasLimit","type":"uint256"},
          {"name":"payload","type":"bytes"}
        ],
        "name":"transactionAllowed",
        "outputs":[{"name":"","type":"bool"}],
        "stateMutability":"view",
        "type":"function"
      },
      {
        "inputs":[],
        "name":"getContractVersion",
        "outputs":[{"name":"","type":"uint256"}],
        "stateMutability":"view",
        "type":"function"
      }
    ]""";

    /**
     * ABI snippet for Admin contract.
     */
    public static final String ADMIN_ABI = """
    [
      {
        "inputs":[{"name":"_address","type":"address"}],
        "name":"isAuthorized",
        "outputs":[{"name":"","type":"bool"}],
        "stateMutability":"view",
        "type":"function"
      },
      {
        "inputs":[{"name":"_address","type":"address"}],
        "name":"addAdmin",
        "outputs":[{"name":"","type":"bool"}],
        "stateMutability":"nonpayable",
        "type":"function"
      }
    ]""";
}
