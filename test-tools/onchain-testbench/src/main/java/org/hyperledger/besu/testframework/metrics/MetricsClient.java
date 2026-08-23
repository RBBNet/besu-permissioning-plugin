package org.hyperledger.besu.testframework.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * HTTP client for Prometheus API queries.
 * Provides methods for instant and range queries against a Prometheus server.
 */
public class MetricsClient {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsClient.class);

    private final String prometheusBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MetricsClient(String prometheusBaseUrl) {
        this.prometheusBaseUrl = prometheusBaseUrl.endsWith("/")
            ? prometheusBaseUrl.substring(0, prometheusBaseUrl.length() - 1)
            : prometheusBaseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Executes an instant PromQL query and returns the scalar result.
     */
    public double queryInstant(String promql) {
        try {
            String url = prometheusBaseUrl + "/api/v1/query?query="
                + URLEncoder.encode(promql, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warn("Prometheus query returned status {}: {}", response.statusCode(), promql);
                return 0;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String status = root.path("status").asText();
            if (!"success".equals(status)) {
                LOG.warn("Prometheus query failed: {}", promql);
                return 0;
            }

            JsonNode result = root.path("data").path("result");
            if (result.isEmpty()) {
                return 0;
            }

            // Return first result's value
            JsonNode value = result.get(0).path("value");
            if (value.isArray() && value.size() >= 2) {
                return Double.parseDouble(value.get(1).asText());
            }

            return 0;
        } catch (IOException | InterruptedException e) {
            LOG.warn("Failed to query Prometheus ({}): {}", promql, e.getMessage());
            return 0;
        }
    }

    /**
     * Queries the current value of a counter metric for a specific node.
     */
    public double getCounter(String metricName, String nodeName) {
        String promql = String.format("%s{node_name=\"%s\"}", metricName, nodeName);
        return queryInstant(promql);
    }

    /**
     * Queries the current value of a gauge metric for a specific node.
     */
    public double getGauge(String metricName, String nodeName) {
        return getCounter(metricName, nodeName);
    }

    /**
     * Checks if the Prometheus server is reachable.
     */
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(prometheusBaseUrl + "/-/healthy"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Waits for Prometheus to become healthy, with timeout.
     */
    public boolean waitForHealthy(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (isHealthy()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public String getPrometheusBaseUrl() {
        return prometheusBaseUrl;
    }
}
