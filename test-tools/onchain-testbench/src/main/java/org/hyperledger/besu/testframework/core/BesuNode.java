package org.hyperledger.besu.testframework.core;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Abstraction of a Hyperledger Besu node running inside a Docker container.
 * Encapsulates RPC/P2P ports, EnodeID, cryptographic keys, and operational state.
 */
public class BesuNode {

    private static final String PLUGIN_LOG_MARKER = "PermissioningPlugin";

    public enum Role {
        VALIDATOR,
        RPC,
        BOOTNODE,
        ROGUE
    }

    private final String name;
    private final Role role;
    private final GenericContainer<?> container;
    private final int rpcPort;
    private final int p2pPort;
    private final String enodeKey;
    private String enodeId;
    private String sharedVolumeName;
    private int metricsPort = 9545;
    private boolean metricsEnabled = false;

    public BesuNode(String name, Role role, GenericContainer<?> container,
                    int rpcPort, int p2pPort, String enodeKey) {
        this.name = name;
        this.role = role;
        this.container = container;
        this.rpcPort = rpcPort;
        this.p2pPort = p2pPort;
        this.enodeKey = enodeKey;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public boolean isValidator() {
        return role == Role.VALIDATOR || role == Role.BOOTNODE;
    }

    public String getRpcUrl() {
        String host = container.getHost();
        Integer mappedPort = container.getMappedPort(rpcPort);
        return "http://" + host + ":" + mappedPort;
    }

    public String getContainerIp() {
        return container.getContainerInfo()
            .getNetworkSettings()
            .getNetworks()
            .values().iterator().next()
            .getIpAddress();
    }

    public int getP2pPort() {
        return p2pPort;
    }

    public String getEnodeId() {
        return enodeId;
    }

    public String getEnodeKey() {
        return enodeKey;
    }

    public String getEnodeUrl() {
        if (enodeId == null) {
            return null;
        }
        return "enode://" + enodeId + "@" + getContainerIp() + ":" + p2pPort;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    public String getContainerId() {
        return container.getContainerId();
    }

    public String getSharedVolumeName() {
        return sharedVolumeName;
    }

    public void setEnodeId(String enodeId) {
        this.enodeId = enodeId;
    }

    public void setSharedVolumeName(String sharedVolumeName) {
        this.sharedVolumeName = sharedVolumeName;
    }

    public int getMetricsPort() {
        return metricsPort;
    }

    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    /**
     * Returns the Prometheus metrics endpoint URL for this node.
     */
    public String getMetricsUrl() {
        if (!metricsEnabled) {
            return null;
        }
        return "http://" + getContainerIp() + ":" + metricsPort + "/metrics";
    }

    /**
     * Fetches logs from the container.
     */
    public String getLogs() {
        return container.getLogs();
    }

    /**
     * Returns logs containing the PermissioningPlugin marker.
     */
    public String getPluginLogs() {
        return container.getLogs()
            .lines()
            .filter(line -> line.contains(PLUGIN_LOG_MARKER))
            .reduce("", (a, b) -> a + "\n" + b);
    }

    /**
     * Checks if the container logs contain a specific pattern.
     */
    public boolean hasLogMatch(String regex) {
        return container.getLogs().lines().anyMatch(line -> line.matches(".*" + regex + ".*"));
    }

    /**
     * Terminates the container without removing its Docker volumes.
     */
    public void terminate() {
        container.stop();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BesuNode that)) return false;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "BesuNode{name='" + name + "', role=" + role + "}";
    }
}
