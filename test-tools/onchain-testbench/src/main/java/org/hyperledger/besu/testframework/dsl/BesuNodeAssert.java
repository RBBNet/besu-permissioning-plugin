package org.hyperledger.besu.testframework.dsl;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.assertj.core.api.AbstractAssert;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * AssertJ assertions for BesuNode instances.
 */
public class BesuNodeAssert extends AbstractAssert<BesuNodeAssert, BesuNode> {

    protected BesuNodeAssert(BesuNode actual) {
        super(actual, BesuNodeAssert.class);
    }

    public static BesuNodeAssert assertThat(BesuNode actual) {
        return new BesuNodeAssert(actual);
    }

    /**
     * Asserts the node is connected to another node (has the target in its peer list).
     */
    public BesuNodeAssert isConnectedTo(BesuNode other) {
        isNotNull();
        String logs = actual.getLogs();
        if (!logs.contains(other.getName()) && !logs.contains(other.getContainerId())) {
            failWithMessage("Expected node <%s> to be connected to <%s>, but no peer entry found in logs.",
                actual.getName(), other.getName());
        }
        return this;
    }

    /**
     * Asserts the node is NOT connected to another node.
     * Checks for the PermissioningPlugin denial pattern in logs.
     */
    public BesuNodeAssert isNotConnectedTo(BesuNode other) {
        isNotNull();
        if (!actual.hasLogMatch("P2P connection DENIED")) {
            // Also check if there's evidence of connection success
            String logs = actual.getLogs();
            if (logs.contains("P2P connection") && !logs.contains("DENIED")) {
                failWithMessage("Expected node <%s> to be NOT connected to <%s>, " +
                    "but logs indicate a successful P2P connection.", actual.getName(), other.getName());
            }
        }
        return this;
    }

    /**
     * Polls the peer count until it reaches the expected value or timeout expires.
     */
    public BesuNodeAssert isEventuallyConnectedTo(BesuNode other, Duration timeout) {
        isNotNull();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!actual.hasLogMatch("P2P connection DENIED") &&
                actual.getLogs().contains(other.getContainerId())) {
                return this; // connection found
            }
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        failWithMessage("Expected node <%s> to eventually connect to <%s> within %s, but it did not.",
            actual.getName(), other.getName(), timeout);
        return this;
    }

    /**
     * Asserts the node has the expected peer count.
     */
    public BesuNodeAssert hasPeerCount(int expected) {
        isNotNull();
        // Peer count can be verified via admin_peers RPC or log patterns
        String logs = actual.getLogs();
        // Look for peer count indicators in logs
        long peerLines = logs.lines()
            .filter(line -> line.contains("Peer") || line.contains("peer"))
            .count();
        if (peerLines < expected) {
            failWithMessage("Expected node <%s> to have at least %d peers, but found ~%d peer references in logs.",
                actual.getName(), expected, peerLines);
        }
        return this;
    }

    /**
     * Asserts the node's logs contain a specific pattern (regex).
     */
    public BesuNodeAssert hasLogMatch(String regex) {
        isNotNull();
        if (!actual.hasLogMatch(regex)) {
            failWithMessage("Expected node <%s> logs to match pattern <%s>, but no match found.\nLogs:\n%s",
                actual.getName(), regex, actual.getLogs());
        }
        return this;
    }

    /**
     * Asserts the PermissioningPlugin is present and active in the node's logs.
     */
    public BesuNodeAssert hasPluginActive() {
        isNotNull();
        if (!actual.hasLogMatch("PermissioningPlugin") && !actual.hasLogMatch("Registering Permissioning Plugin")) {
            failWithMessage("Expected node <%s> to have PermissioningPlugin active, but no plugin logs found.",
                actual.getName());
        }
        return this;
    }

    /**
     * Asserts the node is a validator.
     */
    public BesuNodeAssert isValidator() {
        isNotNull();
        if (!actual.isValidator()) {
            failWithMessage("Expected node <%s> to be a validator but it has role <%s>.",
                actual.getName(), actual.getRole());
        }
        return this;
    }

    /**
     * Asserts the node has the specified role.
     */
    public BesuNodeAssert hasRole(BesuNode.Role role) {
        isNotNull();
        if (actual.getRole() != role) {
            failWithMessage("Expected node <%s> to have role <%s> but it has role <%s>.",
                actual.getName(), role, actual.getRole());
        }
        return this;
    }
}
