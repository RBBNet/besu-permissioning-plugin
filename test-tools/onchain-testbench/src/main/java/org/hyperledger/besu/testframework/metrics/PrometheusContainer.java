package org.hyperledger.besu.testframework.metrics;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

/**
 * Encapsulates a Prometheus container for scraping Besu node metrics.
 * Generates prometheus.yml dynamically from the list of active nodes.
 */
public class PrometheusContainer {
    private static final Logger LOG = LoggerFactory.getLogger(PrometheusContainer.class);

    private static final String PROMETHEUS_IMAGE = "prom/prometheus:v2.52.0";
    private static final int PROMETHEUS_PORT = 9090;
    private static final int SCRAPE_INTERVAL_SECONDS = 5;

    private final GenericContainer<?> container;
    private final String configYml;

    public PrometheusContainer(Network dockerNetwork, List<BesuNode> nodes) {
        this.configYml = generateConfig(nodes);

        this.container = new GenericContainer<>(DockerImageName.parse(PROMETHEUS_IMAGE))
            .withNetwork(dockerNetwork)
            .withNetworkAliases("prometheus")
            .withExposedPorts(PROMETHEUS_PORT)
            .withCreateContainerCmdModifier(cmd -> {
                cmd.withEntrypoint("/bin/prometheus");
                cmd.withCmd(
                    "--config.file=/etc/prometheus/prometheus.yml",
                    "--storage.tsdb.path=/prometheus",
                    "--web.console.libraries=/usr/share/prometheus/console_libraries",
                    "--web.console.templates=/usr/share/prometheus/consoles"
                );
            })
            .waitingFor(Wait.forHttp("/-/healthy").forPort(PROMETHEUS_PORT))
            .withStartupTimeout(Duration.ofSeconds(30));
    }

    /**
     * Generates a prometheus.yml configuration targeting all provided nodes.
     */
    private static String generateConfig(List<BesuNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("global:\n");
        sb.append("  scrape_interval: ").append(SCRAPE_INTERVAL_SECONDS).append("s\n");
        sb.append("  evaluation_interval: ").append(SCRAPE_INTERVAL_SECONDS).append("s\n");
        sb.append("\n");
        sb.append("scrape_configs:\n");
        sb.append("  - job_name: 'besu-nodes'\n");
        sb.append("    metrics_path: '/metrics'\n");
        sb.append("    static_configs:\n");
        sb.append("      - targets:\n");

        for (BesuNode node : nodes) {
            if (node.isMetricsEnabled()) {
                sb.append("          - '").append(node.getContainerIp())
                  .append(":").append(node.getMetricsPort()).append("'\n");
                sb.append("        labels:\n");
                sb.append("          node_name: '").append(node.getName()).append("'\n");
                sb.append("          node_role: '").append(node.getRole().name().toLowerCase()).append("'\n");
            }
        }

        String config = sb.toString();
        LOG.debug("Generated prometheus.yml:\n{}", config);
        return config;
    }

    /**
     * Starts the Prometheus container with the generated configuration.
     */
    public void start() {
        LOG.info("Starting Prometheus container...");
        // Write config to a temporary file and mount it
        container.withCopyToContainer(
            org.testcontainers.utility.MountableFile.forHostPath(
                writeTempConfig(configYml)),
            "/etc/prometheus/prometheus.yml"
        );
        container.start();
        LOG.info("Prometheus started at {}", getApiUrl());
    }

    /**
     * Stops the Prometheus container.
     */
    public void stop() {
        container.stop();
    }

    /**
     * Returns the Prometheus HTTP API base URL.
     */
    public String getApiUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(PROMETHEUS_PORT);
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    /**
     * Returns the generated prometheus.yml content.
     */
    public String getConfigYml() {
        return configYml;
    }

    public boolean isRunning() {
        return container.isRunning();
    }

    private static String writeTempConfig(String content) {
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("prometheus-", ".yml");
            java.nio.file.Files.writeString(tmp, content);
            tmp.toFile().deleteOnExit();
            return tmp.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write Prometheus config", e);
        }
    }
}
