package org.hyperledger.besu.testframework.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Encapsulates a Grafana container pre-configured with a Prometheus datasource
 * and a permissioning dashboard for Besu node metrics.
 */
public class GrafanaContainer {
    private static final Logger LOG = LoggerFactory.getLogger(GrafanaContainer.class);

    private static final String GRAFANA_IMAGE = "grafana/grafana-oss:10.4.0";
    private static final int GRAFANA_PORT = 3000;

    private final GenericContainer<?> container;
    private final String prometheusUrl;

    public GrafanaContainer(Network dockerNetwork, String prometheusContainerName) {
        // The Prometheus URL from inside the Docker network
        this.prometheusUrl = "http://" + prometheusContainerName + ":9090";

        this.container = new GenericContainer<>(DockerImageName.parse(GRAFANA_IMAGE))
            .withNetwork(dockerNetwork)
            .withNetworkAliases("grafana")
            .withExposedPorts(GRAFANA_PORT)
            .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "true")
            .withEnv("GF_AUTH_ANONYMOUS_ORG_ROLE", "Admin")
            .withEnv("GF_SECURITY_ADMIN_PASSWORD", "admin")
            .withEnv("GF_INSTALL_PLUGINS", "")
            .waitingFor(Wait.forHttp("/api/health").forPort(GRAFANA_PORT))
            .withStartupTimeout(Duration.ofSeconds(45));
    }

    /**
     * Starts the Grafana container with provisioned datasource and dashboard.
     */
    public void start() {
        LOG.info("Starting Grafana container...");

        // Provision datasource
        String datasourceYaml = generateDatasourceYaml();
        Path dsPath = writeTempFile("datasource-", ".yaml", datasourceYaml);
        container.withCopyToContainer(
            MountableFile.forHostPath(dsPath),
            "/etc/grafana/provisioning/datasources/prometheus.yaml"
        );

        // Provision dashboard
        String dashboardJson = generateDashboardJson();
        Path dashPath = writeTempFile("dashboard-", ".json", dashboardJson);
        container.withCopyToContainer(
            MountableFile.forHostPath(dashPath),
            "/etc/grafana/provisioning/dashboards/permissioning.json"
        );

        // Dashboard provisioning config
        String dashProviderYaml = "apiVersion: 1\n\nproviders:\n" +
            "  - name: 'default'\n" +
            "    orgId: 1\n" +
            "    folder: ''\n" +
            "    type: file\n" +
            "    disableDeletion: false\n" +
            "    editable: true\n" +
            "    options:\n" +
            "      path: /etc/grafana/provisioning/dashboards\n";
        Path providerPath = writeTempFile("dash-provider-", ".yaml", dashProviderYaml);
        container.withCopyToContainer(
            MountableFile.forHostPath(providerPath),
            "/etc/grafana/provisioning/dashboards/default.yaml"
        );

        container.start();
        LOG.info("Grafana started at {}", getUrl());
    }

    /**
     * Stops the Grafana container.
     */
    public void stop() {
        container.stop();
    }

    /**
     * Returns the Grafana web UI URL.
     */
    public String getUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(GRAFANA_PORT);
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    public boolean isRunning() {
        return container.isRunning();
    }

    private String generateDatasourceYaml() {
        return "apiVersion: 1\n\n" +
            "datasources:\n" +
            "  - name: Prometheus\n" +
            "    type: prometheus\n" +
            "    access: proxy\n" +
            "    url: " + prometheusUrl + "\n" +
            "    isDefault: true\n" +
            "    editable: true\n";
    }

    private String generateDashboardJson() {
        return "{\n" +
            "  \"title\": \"Besu Permissioning\",\n" +
            "  \"uid\": \"besu-permissioning\",\n" +
            "  \"panels\": [\n" +
            "    {\n" +
            "      \"id\": 1,\n" +
            "      \"title\": \"Transaction Check Rate\",\n" +
            "      \"type\": \"graph\",\n" +
            "      \"gridPos\": { \"x\": 0, \"y\": 0, \"w\": 12, \"h\": 8 },\n" +
            "      \"targets\": [\n" +
            "        { \"expr\": \"rate(onchain_transaction_check_count_permitted[1m])\", \"legendFormat\": \"Permitted — {{node_name}}\" },\n" +
            "        { \"expr\": \"rate(onchain_transaction_check_count_denied[1m])\", \"legendFormat\": \"Denied — {{node_name}}\" }\n" +
            "      ],\n" +
            "      \"yaxes\": [{ \"format\": \"short\", \"label\": \"Rate (per second)\" }]\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": 2,\n" +
            "      \"title\": \"P2P Connected Peers\",\n" +
            "      \"type\": \"graph\",\n" +
            "      \"gridPos\": { \"x\": 12, \"y\": 0, \"w\": 12, \"h\": 8 },\n" +
            "      \"targets\": [\n" +
            "        { \"expr\": \"p2p_connected_peers\", \"legendFormat\": \"{{node_name}}\" }\n" +
            "      ],\n" +
            "      \"yaxes\": [{ \"format\": \"short\", \"label\": \"Peers\" }]\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": 3,\n" +
            "      \"title\": \"Permissioning Latency (p99)\",\n" +
            "      \"type\": \"graph\",\n" +
            "      \"gridPos\": { \"x\": 0, \"y\": 8, \"w\": 12, \"h\": 8 },\n" +
            "      \"targets\": [\n" +
            "        { \"expr\": \"histogram_quantile(0.99, rate(onchain_transaction_check_duration_seconds_bucket[5m]))\", \"legendFormat\": \"{{node_name}}\" }\n" +
            "      ],\n" +
            "      \"yaxes\": [{ \"format\": \"s\", \"label\": \"Latency (seconds)\" }]\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": 4,\n" +
            "      \"title\": \"Block Height\",\n" +
            "      \"type\": \"graph\",\n" +
            "      \"gridPos\": { \"x\": 12, \"y\": 8, \"w\": 12, \"h\": 8 },\n" +
            "      \"targets\": [\n" +
            "        { \"expr\": \"blockchain_best_known_block_number\", \"legendFormat\": \"{{node_name}}\" }\n" +
            "      ],\n" +
            "      \"yaxes\": [{ \"format\": \"short\", \"label\": \"Block Number\" }]\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": 5,\n" +
            "      \"title\": \"JVM Memory Used\",\n" +
            "      \"type\": \"graph\",\n" +
            "      \"gridPos\": { \"x\": 0, \"y\": 16, \"w\": 12, \"h\": 8 },\n" +
            "      \"targets\": [\n" +
            "        { \"expr\": \"jvm_memory_used_bytes{area=\\\"heap\\\"}\", \"legendFormat\": \"Heap — {{node_name}}\" },\n" +
            "        { \"expr\": \"jvm_memory_used_bytes{area=\\\"nonheap\\\"}\", \"legendFormat\": \"Non-Heap — {{node_name}}\" }\n" +
            "      ],\n" +
            "      \"yaxes\": [{ \"format\": \"bytes\", \"label\": \"Memory\" }]\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": 6,\n" +
            "      \"title\": \"CPU Usage\",\n" +
            "      \"type\": \"graph\",\n" +
            "      \"gridPos\": { \"x\": 12, \"y\": 16, \"w\": 12, \"h\": 8 },\n" +
            "      \"targets\": [\n" +
            "        { \"expr\": \"rate(process_cpu_seconds_total[1m])\", \"legendFormat\": \"{{node_name}}\" }\n" +
            "      ],\n" +
            "      \"yaxes\": [{ \"format\": \"short\", \"label\": \"CPU Cores\" }]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"schemaVersion\": 16,\n" +
            "  \"refresh\": \"5s\"\n" +
            "}";
    }

    private static Path writeTempFile(String prefix, String suffix, String content) {
        try {
            Path tmp = Files.createTempFile(prefix, suffix);
            Files.writeString(tmp, content);
            tmp.toFile().deleteOnExit();
            return tmp.toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write temp file", e);
        }
    }
}
