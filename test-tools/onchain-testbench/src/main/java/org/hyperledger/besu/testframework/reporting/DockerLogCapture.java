package org.hyperledger.besu.testframework.reporting;

import org.hyperledger.besu.testframework.core.BesuNode;
import org.hyperledger.besu.testframework.orchestrator.NetworkOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.OutputFrame;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Captures Docker container logs for post-mortem analysis.
 *
 * Two modes:
 *
 * <b>Streaming (recommended):</b> captures every log line in real-time as containers run.
 * <pre>{@code
 * DockerLogCapture capture = DockerLogCapture.start("smoke-test");
 * node.getContainer().withLogConsumer(capture.createConsumer("validator-1"));
 * // ... containers run ...
 * capture.close(); // finalizes files with footer
 * }</pre>
 *
 * <b>Snapshot (backward compatible):</b> captures all logs at a point in time.
 * <pre>{@code
 * Path dir = DockerLogCapture.captureAll(orchestrator, "smoke-test");
 * }</pre>
 *
 * Output: {@code docs/relatorios/docker-logs/<testId>/<containerName>.log}
 */
public class DockerLogCapture implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DockerLogCapture.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .withZone(ZoneId.of("America/Sao_Paulo"));

    private final Path logDir;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private final Instant startedAt = Instant.now();
    private boolean closed = false;

    private DockerLogCapture(String testId, Path baseDir) {
        this.logDir = baseDir.resolve("docker-logs").resolve(sanitize(testId));
    }

    /**
     * Starts streaming log capture. Creates the output directory.
     * Call {@link #createConsumer} for each container, then call {@link #close()} when done.
     */
    public static DockerLogCapture start(String testId, Path baseDir) {
        DockerLogCapture capture = new DockerLogCapture(testId, baseDir);
        try {
            Files.createDirectories(capture.logDir);
        } catch (IOException e) {
            LOG.error("Failed to create Docker logs directory: {}", capture.logDir, e);
            return null;
        }
        LOG.info("Streaming Docker logs to {}", capture.logDir);
        return capture;
    }

    /**
     * Starts streaming with default base directory ({@code docs/relatorios}).
     */
    public static DockerLogCapture start(String testId) {
        return start(testId, Paths.get("docs/relatorios"));
    }

    /**
     * Creates a log consumer for a named container.
     * Attach it via {@code container.withLogConsumer(capture.createConsumer("name"))}
     * BEFORE calling {@code container.start()}.
     */
    public Consumer<OutputFrame> createConsumer(String containerName) {
        try {
            Path logFile = logDir.resolve(sanitize(containerName) + ".log");
            BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
            writers.put(containerName, writer);

            // Write header
            writer.write("# Docker container log: " + containerName + "\n");
            writer.write("# Started streaming at: " + ISO.format(startedAt) + "\n");
            writer.write("# ================================================================\n\n");
            writer.flush();

            LOG.info("Streaming {} → {}", containerName, logFile);

            return frame -> {
                if (closed) return;
                try {
                    String text = frame.getUtf8String();
                    if (text != null) {
                        writer.write(text);
                        if (!text.endsWith("\n")) {
                            writer.write("\n");
                        }
                        writer.flush();
                    }
                } catch (IOException e) {
                    LOG.warn("Failed to write log frame for {}: {}", containerName, e.getMessage());
                }
            };
        } catch (IOException e) {
            LOG.error("Failed to create log file for {}: {}", containerName, e.getMessage());
            return frame -> {};
        }
    }

    /**
     * Attaches a streaming consumer to this node.
     * Convenience method — equivalent to {@code node.getContainer().withLogConsumer(capture.createConsumer(node.getName()))}.
     */
    public void attachTo(BesuNode node) {
        node.getContainer().withLogConsumer(createConsumer(node.getName()));
    }

    /**
     * Attaches streaming consumers to all nodes in the orchestrator.
     * Must be called BEFORE {@link NetworkOrchestrator#start()}.
     */
    public void attachToAll(NetworkOrchestrator orchestrator) {
        for (BesuNode node : orchestrator.getAllNodes()) {
            attachTo(node);
        }
        if (orchestrator.isMetricsEnabled()) {
            if (orchestrator.getPrometheus() != null) {
                orchestrator.getPrometheus().getContainer()
                    .withLogConsumer(createConsumer("prometheus"));
            }
            if (orchestrator.getGrafana() != null) {
                orchestrator.getGrafana().getContainer()
                    .withLogConsumer(createConsumer("grafana"));
            }
        }
        LOG.info("Attached streaming consumers to {} containers", writers.size());
    }

    /**
     * Finalizes all log files with footer metadata and closes writers.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        Instant endedAt = Instant.now();
        Duration elapsed = Duration.between(startedAt, endedAt);
        int totalLines = 0;

        for (var entry : writers.entrySet()) {
            String name = entry.getKey();
            BufferedWriter writer = entry.getValue();
            try {
                writer.write("\n# ================================================================\n");
                writer.write("# Streaming ended at: " + ISO.format(endedAt) + "\n");
                writer.write("# Duration: " + formatDuration(elapsed) + "\n");
                writer.write("# ================================================================\n");
                writer.flush();
                writer.close();
            } catch (IOException e) {
                LOG.warn("Failed to finalize log for {}: {}", name, e.getMessage());
            }
        }

        // Count lines for summary
        for (String name : writers.keySet()) {
            try {
                Path file = logDir.resolve(sanitize(name) + ".log");
                totalLines += Files.readAllLines(file).size();
            } catch (IOException ignored) {}
        }

        LOG.info("Docker logs captured for {} containers → {} ({} lines total, {})",
            writers.size(), logDir, totalLines, formatDuration(elapsed));
    }

    /**
     * Returns the directory where log files are being written.
     */
    public Path getLogDir() {
        return logDir;
    }

    // -- Synchronous capture (backward compatible) --

    /**
     * Captures a snapshot of all container logs synchronously.
     * Prefer streaming mode ({@link #start}) for real-time capture.
     */
    public static Path captureAll(NetworkOrchestrator orchestrator, String testId, Path baseDir) {
        DockerLogCapture capture = start(testId, baseDir);
        if (capture == null) return null;

        try {
            for (BesuNode node : orchestrator.getAllNodes()) {
                try {
                    String logs = node.getLogs();
                    Path logFile = capture.logDir.resolve(sanitize(node.getName()) + ".log");
                    writeSnapshot(logFile, node.getName(), logs);
                } catch (Exception e) {
                    LOG.warn("Failed to capture snapshot for {}: {}", node.getName(), e.getMessage());
                }
            }

            if (orchestrator.isMetricsEnabled()) {
                try {
                    if (orchestrator.getPrometheus() != null) {
                        String logs = orchestrator.getPrometheus().getContainer().getLogs();
                        writeSnapshot(capture.logDir.resolve("prometheus.log"), "prometheus", logs);
                    }
                    if (orchestrator.getGrafana() != null) {
                        String logs = orchestrator.getGrafana().getContainer().getLogs();
                        writeSnapshot(capture.logDir.resolve("grafana.log"), "grafana", logs);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to capture metrics container logs: {}", e.getMessage());
                }
            }
        } finally {
            capture.close();
        }

        LOG.info("Snapshot logs captured → {}", capture.logDir);
        return capture.logDir;
    }

    public static Path captureAll(NetworkOrchestrator orchestrator, String testId) {
        return captureAll(orchestrator, testId, Paths.get("docs/relatorios"));
    }

    private static void writeSnapshot(Path file, String containerName, String content) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Docker container log (snapshot): ").append(containerName).append("\n");
        sb.append("# Captured at: ").append(ISO.format(Instant.now())).append("\n");
        sb.append("# ================================================================\n\n");
        if (content != null && !content.isEmpty()) {
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
        } else {
            sb.append("(empty log)\n");
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        s = s % 60;
        return m + "m " + s + "s";
    }
}
