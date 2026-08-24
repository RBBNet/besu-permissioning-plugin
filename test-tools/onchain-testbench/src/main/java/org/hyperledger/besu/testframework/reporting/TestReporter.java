package org.hyperledger.besu.testframework.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hyperledger.besu.testframework.orchestrator.NetworkOrchestrator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Collects step-by-step evidence during test execution and generates
 * rich Markdown documentation reports with explanatory step-by-step evidence.
 *
 * <pre>{@code
 * TestReporter report = new TestReporter("Fail-Close Scenario",
 *     "Validates that plugin blocks 100% of transactions when Ingress is missing");
 *
 * report.step("Starting validator node", "Besu is started without configuring BESU_PERMISSIONS_ACCOUNTS_CONTRACT_ADDRESS");
 * report.evidence("Container ID", containerId, Evidence.Type.RESULT);
 * report.log("PermissioningPlugin: FAIL-CLOSE mode activated");
 * report.stepPassed();
 *
 * report.conclusion("Fail-Close confirmed: all connections preventively blocked");
 * report.generateMarkdown();
 * }</pre>
 */
public class TestReporter {
    private static final Logger LOG = LoggerFactory.getLogger(TestReporter.class);

    private final String testId;
    private final String testName;
    private final String testDescription;
    private final Instant startTime;
    private Instant endTime;
    private boolean passed;
    private String conclusion;
    private final List<ExecutionStep> steps;
    private final Map<String, String> metadata;
    private ExecutionStep currentStep;
    private String outputDir;
    private NetworkOrchestrator orchestrator;
    private DockerLogCapture logCapture;
    private Path dockerLogDir;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    public TestReporter(String testName, String testDescription) {
        this.testId = "perm-test-" + UUID.randomUUID().toString().substring(0, 8);
        this.testName = testName;
        this.testDescription = testDescription;
        this.startTime = Instant.now();
        this.steps = new ArrayList<>();
        this.metadata = new LinkedHashMap<>();
        this.outputDir = "docs/reports";
        this.passed = true;
    }

    public TestReporter(String testName) {
        this(testName, null);
    }

    // -- Metadata --

    public TestReporter metadata(String key, String value) {
        metadata.put(key, value);
        return this;
    }

    // -- Step management --

    /**
     * Starts a new numbered step with a title and description.
     */
    public TestReporter step(String title, String description) {
        int num = steps.size() + 1;
        currentStep = new ExecutionStep(num, title, description);
        steps.add(currentStep);
        LOG.info("  [{}/{}] {} {}",
            currentStep.getStepNumber(),
            steps.size(),
            ExecutionStep.Status.EXECUTING.getIcon(),
            title);
        return this;
    }

    public TestReporter step(String title) {
        return step(title, null);
    }

    /**
     * Adds raw evidence to the current step.
     */
    public TestReporter evidence(String label, String value, Evidence.Type type) {
        if (currentStep == null) {
            step("(automatic step)");
        }
        currentStep.addEvidence(label, value, type);
        return this;
    }

    /**
     * Adds a result evidence (typically a value returned by the system).
     */
    public TestReporter result(String label, String value) {
        return evidence(label, value, Evidence.Type.RESULT);
    }

    /**
     * Adds a log line evidence (from Besu or plugin output).
     */
    public TestReporter log(String label, String logLine) {
        return evidence(label, logLine, Evidence.Type.LOG);
    }

    /**
     * Adds a code/command evidence (what was executed).
     */
    public TestReporter code(String label, String codeSnippet) {
        return evidence(label, codeSnippet, Evidence.Type.CODE);
    }

    /**
     * Adds an observation (free-form note).
     */
    public TestReporter observation(String note) {
        return evidence("Observation", note, Evidence.Type.OBSERVATION);
    }

    /**
     * Adds a table to the current step.
     */
    public TestReporter table(String label, List<String[]> rows) {
        if (currentStep == null) {
            step("(table)");
        }
        currentStep.addTable(label, rows);
        return this;
    }

    /**
     * Adds an error evidence and marks the current step as FAILED.
     */
    public TestReporter error(String label, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        evidence(label, sw.toString(), Evidence.Type.ERROR);
        if (currentStep != null) {
            currentStep.setStatus(ExecutionStep.Status.FAILED);
        }
        this.passed = false;
        return this;
    }

    /**
     * Marks the current step as PASSED.
     */
    public TestReporter stepPassed() {
        if (currentStep != null) {
            currentStep.setStatus(ExecutionStep.Status.PASSED);
            currentStep.setDuration(formatDuration(
                Duration.between(currentStep.getTimestamp(), Instant.now())));
        }
        return this;
    }

    /**
     * Marks the current step as FAILED.
     */
    public TestReporter stepFailed(String reason) {
        if (currentStep != null) {
            currentStep.setStatus(ExecutionStep.Status.FAILED);
            currentStep.addEvidence("Failure reason", reason, Evidence.Type.ERROR);
        }
        this.passed = false;
        return this;
    }

    /**
     * Finalizes the test with a conclusion.
     */
    public TestReporter conclusion(String message) {
        this.conclusion = message;
        this.endTime = Instant.now();
        LOG.info("Conclusion: {}", message);
        return this;
    }

    /**
     * Uses a pre-configured streaming log capture.
     * Use when log capture was set up via {@code orchestrator.withLogCapture(testId)}.
     * The capture will be finalized automatically when the orchestrator shuts down.
     */
    public TestReporter captureDockerLogs(DockerLogCapture logCapture) {
        this.logCapture = logCapture;
        this.dockerLogDir = logCapture.getLogDir();
        return this;
    }

    /**
     * Configures snapshot Docker log capture for this test.
     * Logs are captured when {@link #generateMarkdown()} is called.
     * For real-time capture, prefer {@link #captureDockerLogs(DockerLogCapture)}.
     */
    public TestReporter captureDockerLogs(NetworkOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
        return this;
    }

    /**
     * Returns the path to the captured Docker logs directory, or null if not captured.
     */
    public Path getDockerLogDir() {
        return dockerLogDir;
    }

    // -- Report generation --

    /**
     * Generates a Markdown report file and returns the file path.
     * Also generates a SuperLog audit log and captures Docker logs if configured.
     */
    public String generateMarkdown() {
        if (endTime == null) {
            endTime = Instant.now();
        }
        java.nio.file.Path dir = java.nio.file.Paths.get(outputDir);
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            LOG.error("Failed to create output directory: {}", dir, e);
            return null;
        }

        // Capture Docker logs if configured
        if (logCapture != null) {
            // Streaming mode: capture already running, just reference its directory
            this.dockerLogDir = logCapture.getLogDir();
        } else if (orchestrator != null) {
            // Snapshot mode: capture now (containers may already be stopped)
            this.dockerLogDir = DockerLogCapture.captureAll(orchestrator, testId, dir);
        }

        String filename = DATE_FMT.format(startTime) + "-" + testId + ".md";
        java.nio.file.Path filePath = dir.resolve(filename);

        try (PrintWriter w = new PrintWriter(
                java.nio.file.Files.newBufferedWriter(filePath))) {
            writeHeader(w);
            writeMetadata(w);
            writeSteps(w);
            writeConclusion(w);
            writeFooter(w);
        } catch (Exception e) {
            LOG.error("Failed to generate report: {}", filePath, e);
            return null;
        }

        // Also generate SuperLog audit log
        generateSuperLog(dir);

        LOG.info("Report generated: {}", filePath.toAbsolutePath());
        return filePath.toAbsolutePath().toString();
    }

    /**
     * Generates a SuperLog audit log alongside the Markdown report.
     * Captures all steps, evidence, and results in immutable audit format with SHA-256.
     */
    private void generateSuperLog(java.nio.file.Path dir) {
        String superlogFilename = DATE_FMT.format(startTime) + "-" + testId + ".superlog";
        java.nio.file.Path superlogPath = dir.resolve(superlogFilename);

        try (PrintWriter w = new PrintWriter(
                java.nio.file.Files.newBufferedWriter(superlogPath))) {
            // Header
            w.println("================================================================");
            w.println("  SUPER LOG — " + testName);
            w.println("  Test ID: " + testId);
            w.println("  Started: " + TIME_FMT.format(startTime));
            w.println("  Status: " + (passed ? "PASSED" : "FAILED"));
            if (endTime != null) {
                Duration d = Duration.between(startTime, endTime);
                w.println("  Duration: " + formatDuration(d));
            }
            w.println("================================================================");
            w.println();

            // Metadata as evidence
            if (!metadata.isEmpty()) {
                w.println("---- [CONTEXT] " + Instant.now() + " ----");
                for (var entry : metadata.entrySet()) {
                    w.println("[META] " + entry.getKey() + ": " + entry.getValue());
                }
                w.println();
            }

            // Steps as audit trail
            for (ExecutionStep step : steps) {
                w.println("---- [STEP " + step.getStepNumber() + "] " +
                    step.getTimestamp() + " ----");
                w.println("  Title: " + step.getTitle());
                if (step.getDescription() != null) {
                    w.println("  Description: " + step.getDescription());
                }
                w.println("  Status: " + step.getStatus().name());

                for (Evidence ev : step.getEvidence()) {
                    String label = ev.getLabel();
                    String value = ev.getValue();
                    switch (ev.getType()) {
                        case LOG:
                            w.println("  [LOG:" + label + "]");
                            for (String line : value.split("\n")) {
                                w.println("    " + line);
                            }
                            break;
                        case CODE:
                            w.println("  [CODE:" + label + "]");
                            for (String line : value.split("\n")) {
                                w.println("    " + line);
                            }
                            break;
                        case RESULT:
                            w.println("  [RESULT] " + label + ": " + value);
                            break;
                        case TABLE:
                            w.println("  [TABLE:" + label + "]");
                            break;
                        default:
                            w.println("  [EVIDENCE] " + label + ": " + value);
                    }
                }
                w.println();
            }

            // Conclusion
            if (conclusion != null) {
                w.println("---- [CONCLUSION] ----");
                w.println("  " + conclusion);
                w.println();
            }

            // Footer with SHA-256
            w.flush();
            w.close();

            // Compute SHA-256 of the file
            try {
                byte[] fileBytes = java.nio.file.Files.readAllBytes(superlogPath);
                java.security.MessageDigest sha256 =
                    java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = sha256.digest(fileBytes);
                StringBuilder hexHash = new StringBuilder();
                for (byte b : hash) {
                    hexHash.append(String.format("%02x", b));
                }

                long lineCount = java.nio.file.Files.readAllLines(superlogPath).size();

                // Append footer
                try (PrintWriter fw = new PrintWriter(
                        java.nio.file.Files.newBufferedWriter(
                            superlogPath,
                            java.nio.file.StandardOpenOption.APPEND))) {
                    fw.println("================================================================");
                    fw.println("  SUPER LOG CLOSED: " + Instant.now());
                    fw.println("  SHA-256: " + hexHash.toString());
                    fw.println("  Lines: " + lineCount);
                    fw.println("================================================================");
                }

                // Write hash file
                java.nio.file.Path hashFile = dir.resolve(
                    superlogFilename.replace(".superlog", ".superlog.sha256"));
                java.nio.file.Files.writeString(hashFile,
                    hexHash.toString() + "  " + superlogFilename);

                LOG.info("SuperLog generated: {} ({} lines, SHA-256: {})",
                    superlogPath, lineCount, hexHash);

            } catch (Exception e) {
                LOG.error("Failed to compute SuperLog SHA-256: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOG.error("Failed to generate SuperLog: {}", superlogPath, e);
        }
    }

    public String generateMarkdown(String customPath) {
        this.outputDir = customPath;
        return generateMarkdown();
    }

    // -- Report sections --

    private void writeHeader(PrintWriter w) {
        w.println("# Permissioning Test Report: " + testName);
        w.println();
        w.println("| Field | Value |");
        w.println("| :--- | :--- |");
        w.println("| **Test ID** | `" + testId + "` |");
        w.println("| **Timestamp** | " + TIME_FMT.format(startTime) + " |");
        w.println("| **Status** | " + (passed ? "✅ PASSED" : "❌ FAILED") + " |");
        if (endTime != null) {
            Duration d = Duration.between(startTime, endTime);
            w.println("| **Total Duration** | " + formatDuration(d) + " |");
        if (dockerLogDir != null) {
            w.println("| **Docker Logs** | `" + dockerLogDir + "` |");
        }
        }
        w.println();
        if (testDescription != null) {
            w.println("> **Goal:** " + testDescription);
            w.println();
        }
        w.println("---");
        w.println();
    }

    private void writeMetadata(PrintWriter w) {
        if (metadata.isEmpty()) return;
        w.println("## Environment Context");
        w.println();
        w.println("| Parameter | Value |");
        w.println("| :--- | :--- |");
        for (var entry : metadata.entrySet()) {
            w.println("| **" + entry.getKey() + "** | `" + entry.getValue() + "` |");
        }
        w.println();
    }

    private void writeSteps(PrintWriter w) {
        w.println("## Execution Flow");
        w.println();

        for (ExecutionStep step : steps) {
            w.println("### Step " + step.getStepNumber() + ": " +
                step.getStatus().getIcon() + " " + step.getTitle());
            w.println();

            if (step.getDescription() != null) {
                w.println("> " + step.getDescription());
                w.println();
            }

            // If there's evidence, it PROVES what the step claims.
            // Evidence comes first — it's the proof, not metadata.
            if (!step.getEvidence().isEmpty()) {
                w.println("**Evidence:**");
                w.println();

                for (Evidence ev : step.getEvidence()) {
                    switch (ev.getType()) {
                        case LOG:
                            w.println("📋 **" + ev.getLabel() + "**");
                            w.println();
                            w.println("```log");
                            w.println(ev.getValue());
                            w.println("```");
                            w.println();
                            break;

                        case CODE:
                            w.println("💻 **" + ev.getLabel() + "**");
                            w.println();
                            w.println("```java");
                            w.println(ev.getValue());
                            w.println("```");
                            w.println();
                            break;

                        case RESULT:
                            w.println("📊 **" + ev.getLabel() + ":** `" + ev.getValue() + "`");
                            w.println();
                            break;

                        case METRIC:
                            w.println("📐 **" + ev.getLabel() + ":** `" + ev.getValue() + "`");
                            w.println();
                            break;

                        case ERROR:
                            w.println("🚨 **" + ev.getLabel() + "**");
                            w.println();
                            w.println("```text");
                            w.println(ev.getValue());
                            w.println("```");
                            w.println();
                            break;

                        case OBSERVATION:
                            w.println("> 💡 " + ev.getValue());
                            w.println();
                            break;

                        case TABLE:
                            w.println("📈 **" + ev.getLabel() + "**");
                            w.println();
                            writeEvidenceTable(w, ev.getTableData());
                            w.println();
                            break;
                    }
                }
            } else {
                // No evidence = step is purely explanatory
                w.println("*(explanatory step)*");
                w.println();
            }

            // Compact status footer per step
            w.println("<sub>⏱️ " + TIME_FMT.format(step.getTimestamp()) +
                " | " + step.getStatus().getIcon() + " " + step.getStatus().name() +
                (step.getDuration() != null ? " | ⌛ " + step.getDuration() : "") +
                "</sub>");
            w.println();
            w.println("---");
            w.println();
        }
    }

    private void writeEvidenceTable(PrintWriter w, List<String[]> rows) {
        if (rows == null || rows.isEmpty()) return;
        // Header row
        String[] header = rows.get(0);
        w.print("| ");
        for (String h : header) {
            w.print(h + " | ");
        }
        w.println();
        // Separator
        w.print("| ");
        for (int i = 0; i < header.length; i++) {
            w.print(":--- | ");
        }
        w.println();
        // Data rows
        for (int r = 1; r < rows.size(); r++) {
            w.print("| ");
            for (String cell : rows.get(r)) {
                w.print(cell + " | ");
            }
            w.println();
        }
    }

    private void writeConclusion(PrintWriter w) {
        w.println("## Conclusion");
        w.println();
        w.println("| Field | Value |");
        w.println("| :--- | :--- |");
        w.println("| **Final Status** | " +
            (passed ? "✅ PASSED" : "❌ FAILED") + " |");
        if (conclusion != null) {
            w.println("| **Conclusion** | " + conclusion + " |");
        }
        w.println("| **Total Steps** | " + steps.size() + " |");
        long passedSteps = steps.stream()
            .filter(s -> s.getStatus() == ExecutionStep.Status.PASSED).count();
        long failedSteps = steps.stream()
            .filter(s -> s.getStatus() == ExecutionStep.Status.FAILED).count();
        w.println("| **Passed Steps** | " + passedSteps + " |");
        w.println("| **Failed Steps** | " + failedSteps + " |");
        w.println();
    }

    private void writeFooter(PrintWriter w) {
        w.println("---");
        w.println();
        w.println("*Report automatically generated by Besu Permissioning Test Framework on " +
            TIME_FMT.format(Instant.now()) + "*");
    }

    // -- Formatting helpers --

    private String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) {
            return ms + "ms";
        }
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        s = s % 60;
        return m + "m " + s + "s";
    }

    // -- Accessors --

    public boolean isPassed() { return passed; }
    public String getTestId() { return testId; }
    public List<ExecutionStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
