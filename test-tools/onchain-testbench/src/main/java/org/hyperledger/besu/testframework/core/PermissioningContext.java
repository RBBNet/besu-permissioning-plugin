package org.hyperledger.besu.testframework.core;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized state container holding the addresses of the active Ingress/Rules contracts
 * and the current snapshot of the blockchain state during scenario execution.
 */
public class PermissioningContext {

    public static final String ADMIN_CONTRACT = "admin";
    public static final String RULES_CONTRACT = "rules";

    private String accountIngressAddress;
    private String nodeIngressAddress;
    private String accountRulesAddress;
    private String nodeRulesAddress;
    private String adminAddress;
    private BigInteger chainId;
    private long currentBlockNumber;
    private final Map<String, String> registeredContracts = new ConcurrentHashMap<>();

    public PermissioningContext() {
    }

    public PermissioningContext(BigInteger chainId) {
        this.chainId = chainId;
    }

    public String getAccountIngressAddress() {
        return accountIngressAddress;
    }

    public void setAccountIngressAddress(String accountIngressAddress) {
        this.accountIngressAddress = accountIngressAddress;
    }

    public String getNodeIngressAddress() {
        return nodeIngressAddress;
    }

    public void setNodeIngressAddress(String nodeIngressAddress) {
        this.nodeIngressAddress = nodeIngressAddress;
    }

    public String getAccountRulesAddress() {
        return accountRulesAddress;
    }

    public void setAccountRulesAddress(String accountRulesAddress) {
        this.accountRulesAddress = accountRulesAddress;
    }

    public String getNodeRulesAddress() {
        return nodeRulesAddress;
    }

    public void setNodeRulesAddress(String nodeRulesAddress) {
        this.nodeRulesAddress = nodeRulesAddress;
    }

    public String getAdminAddress() {
        return adminAddress;
    }

    public void setAdminAddress(String adminAddress) {
        this.adminAddress = adminAddress;
    }

    public BigInteger getChainId() {
        return chainId;
    }

    public void setChainId(BigInteger chainId) {
        this.chainId = chainId;
    }

    public long getCurrentBlockNumber() {
        return currentBlockNumber;
    }

    public void setCurrentBlockNumber(long currentBlockNumber) {
        this.currentBlockNumber = currentBlockNumber;
    }

    public void registerContract(String name, String address) {
        registeredContracts.put(name, address);
    }

    public String getRegisteredContract(String name) {
        return registeredContracts.get(name);
    }

    /**
     * Returns true if all critical addresses are configured.
     */
    public boolean isFullyConfigured() {
        return accountIngressAddress != null
            && nodeIngressAddress != null
            && adminAddress != null;
    }

    @Override
    public String toString() {
        return "PermissioningContext{" +
            "accountIngress='" + accountIngressAddress + '\'' +
            ", nodeIngress='" + nodeIngressAddress + '\'' +
            ", accountRules='" + accountRulesAddress + '\'' +
            ", nodeRules='" + nodeRulesAddress + '\'' +
            ", admin='" + adminAddress + '\'' +
            ", chainId=" + chainId +
            '}';
    }
}
