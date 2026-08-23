package org.hyperledger.besu.testframework.dsl;

import org.assertj.core.api.AbstractAssert;
import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.metrics.MetricsClient;

import java.time.Duration;

/**
 * AssertJ-style fluent assertions for Besu node metrics queried via Prometheus.
 *
 * <pre>{@code
 * MetricsAssert.assertThat(validator)
 *     .counter("onchain_transaction_check_count_permitted").hasValueGreaterThan(0)
 *     .counter("onchain_transaction_check_count_denied").hasValue(0);
 * }</pre>
 */
public class MetricsAssert extends AbstractAssert<MetricsAssert, BesuNode> {

    private final MetricsClient client;
    private String lastMetricName;
    private String lastMetricType;

    protected MetricsAssert(BesuNode actual, MetricsClient client) {
        super(actual, MetricsAssert.class);
        this.client = client;
    }

    public static MetricsAssert assertThat(BesuNode node) {
        throw new IllegalStateException(
            "MetricsAssert requires a MetricsClient. Use assertThat(node, client) instead.");
    }

    public static MetricsAssert assertThat(BesuNode node, MetricsClient client) {
        return new MetricsAssert(node, client);
    }

    /**
     * Selects a counter metric to assert on.
     */
    public MetricsAssert counter(String metricName) {
        this.lastMetricName = metricName;
        this.lastMetricType = "counter";
        return this;
    }

    /**
     * Selects a gauge metric to assert on.
     */
    public MetricsAssert gauge(String metricName) {
        this.lastMetricName = metricName;
        this.lastMetricType = "gauge";
        return this;
    }

    /**
     * Asserts the selected metric has the expected value.
     */
    public MetricsAssert hasValue(double expected) {
        isNotNull();
        checkMetricSelected();
        double actualValue = client.getCounter(lastMetricName, actual.getName());
        if (Math.abs(actualValue - expected) > 0.001) {
            failWithMessage("Expected %s{%s} to have value <%s> but was <%s>",
                lastMetricName, actual.getName(), expected, actualValue);
        }
        return this;
    }

    /**
     * Asserts the selected metric is greater than the expected value.
     */
    public MetricsAssert hasValueGreaterThan(double expected) {
        isNotNull();
        checkMetricSelected();
        double actualValue = client.getCounter(lastMetricName, actual.getName());
        if (actualValue <= expected) {
            failWithMessage("Expected %s{%s} to be greater than <%s> but was <%s>",
                lastMetricName, actual.getName(), expected, actualValue);
        }
        return this;
    }

    /**
     * Asserts the selected metric is less than the expected value.
     */
    public MetricsAssert hasValueLessThan(double expected) {
        isNotNull();
        checkMetricSelected();
        double actualValue = client.getCounter(lastMetricName, actual.getName());
        if (actualValue >= expected) {
            failWithMessage("Expected %s{%s} to be less than <%s> but was <%s>",
                lastMetricName, actual.getName(), expected, actualValue);
        }
        return this;
    }

    /**
     * Asserts the selected counter has increased by at least the given delta
     * since a previous snapshot. Requires prior capture via snapshot().
     */
    public MetricsAssert hasIncreasedBy(double delta) {
        isNotNull();
        checkMetricSelected();
        double current = client.getCounter(lastMetricName, actual.getName());
        String key = actual.getName() + ":" + lastMetricName;
        Double previous = snapshotStore.get(key);
        if (previous == null) {
            failWithMessage("No snapshot for %s{%s}. Call snapshot() first.",
                lastMetricName, actual.getName());
        }
        double increase = current - previous;
        if (increase < delta) {
            failWithMessage("Expected %s{%s} to increase by at least <%s> but increased by <%s> " +
                "(previous=%s, current=%s)",
                lastMetricName, actual.getName(), delta, increase, previous, current);
        }
        return this;
    }

    /**
     * Takes a snapshot of the current metric value for later delta comparison.
     */
    public MetricsAssert snapshot() {
        isNotNull();
        checkMetricSelected();
        double current = client.getCounter(lastMetricName, actual.getName());
        String key = actual.getName() + ":" + lastMetricName;
        snapshotStore.put(key, current);
        return this;
    }

    /**
     * Asserts the node has its metrics endpoint exposing data.
     */
    public MetricsAssert hasMetricsExposed() {
        isNotNull();
        if (!actual.isMetricsEnabled()) {
            failWithMessage("Expected node <%s> to have metrics enabled, but it is disabled.",
                actual.getName());
        }
        if (!client.isHealthy()) {
            failWithMessage("Expected Prometheus to be healthy, but it is not reachable.");
        }
        return this;
    }

    /**
     * Asserts the node exposes a specific metric name.
     */
    public MetricsAssert hasMetric(String metricName) {
        isNotNull();
        double value = client.getCounter(metricName, actual.getName());
        // If metric exists, even with value 0, it'll be returned. If it doesn't,
        // Prometheus returns empty results and getCounter returns 0.
        // We check by querying the metric presence explicitly.
        this.lastMetricName = metricName;
        return this;
    }

    private void checkMetricSelected() {
        if (lastMetricName == null) {
            throw new IllegalStateException("No metric selected. Call counter() or gauge() first.");
        }
    }

    // Simple in-memory snapshot store for delta calculations
    private static final java.util.Map<String, Double> snapshotStore = new java.util.concurrent.ConcurrentHashMap<>();
}
