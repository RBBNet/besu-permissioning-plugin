package org.hyperledger.besu.testframework.contracts;

import org.web3j.protocol.Web3j;
import org.web3j.tx.TransactionManager;

import java.math.BigInteger;

/**
 * Strategy pattern interface for deploying and interacting with different
 * versions of the on-chain permissioning contracts.
 *
 * Implementations handle GEN01, GEN02, custom, or mock contracts
 * transparently for the test framework.
 */
public interface PermissioningStrategy {

    /**
     * Executes the full contract deployment sequence.
     * Admin → Rules → Ingress contracts, with appropriate address registrations.
     */
    void executeDeploy(Web3j web3j) throws Exception;

    /**
     * Returns the address of the Account Ingress contract in use.
     */
    String getAccountIngressAddress();

    /**
     * Returns the address of the Node Ingress contract in use.
     */
    String getNodeIngressAddress();

    /**
     * Authorizes a node (by enodeID) to join the network.
     * Returns the transaction hash.
     */
    String authorizeNode(String enodeId) throws Exception;

    /**
     * Authorizes an account address to transact on the network.
     * Returns the transaction hash.
     */
    String authorizeAccount(String accountAddress) throws Exception;

    /**
     * Revokes authorization for a node.
     */
    String revokeNode(String enodeId) throws Exception;

    /**
     * Revokes authorization for an account.
     */
    String revokeAccount(String accountAddress) throws Exception;

    /**
     * Returns true if an account is currently authorized.
     */
    boolean isAccountAuthorized(String address) throws Exception;

    /**
     * Returns true if a node is currently authorized.
     */
    boolean isNodeAuthorized(String enodeId) throws Exception;

    /**
     * Creates a GEN02 strategy using pre-deployed genesis contracts.
     */
    static PermissioningStrategy genesis() {
        return new GenesisStrategy();
    }
}
