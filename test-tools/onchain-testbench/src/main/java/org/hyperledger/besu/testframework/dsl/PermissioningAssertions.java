package org.hyperledger.besu.testframework.dsl;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.metrics.MetricsClient;
import org.assertj.core.api.Assertions;

import java.time.Duration;

/**
 * Entry point for permissioning-specific fluent assertions, extending AssertJ.
 *
 * <pre>{@code
 * PermissioningAssertions.assertThat(rogueNode)
 * .isNotConnectedTo(validatorNode)
 * .hasPluginLogMatch("P2P connection DENIED");
 *
 * PermissioningAssertions.assertThat(receipt)
 * .hasReversionReason("Account Not Allowed On-Chain");
 * }</pre>
 */
public class PermissioningAssertions extends Assertions {

    /**
     * Creates a BesuNode-specific assertion.
     */
    public static BesuNodeAssert assertThat(BesuNode actual) {
        return new BesuNodeAssert(actual);
    }

    /**
     * Creates a container log assertion.
     */
    public static ContainerLogAssert assertThatLogs(BesuNode actual) {
        return new ContainerLogAssert(actual.getLogs());
    }

    /**
     * Creates a metrics assertion for validating Prometheus-exposed metrics.
     */
    public static MetricsAssert assertThatMetrics(BesuNode node, MetricsClient client) {
        return new MetricsAssert(node, client);
    }
}
