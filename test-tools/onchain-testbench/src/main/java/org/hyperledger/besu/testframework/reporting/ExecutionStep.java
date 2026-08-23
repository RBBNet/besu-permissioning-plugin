package org.hyperledger.besu.testframework.reporting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single step in a test execution, with timestamp,
 * description, status, and collected evidence (logs, outputs, etc.).
 */
public class ExecutionStep {
    private final int stepNumber;
    private final String title;
    private final String description;
    private final Instant timestamp;
    private Status status;
    private final List<Evidence> evidence;
    private String duration;

    public enum Status {
        EXECUTING("🔵"),
        PASSED("✅"),
        FAILED("❌"),
        WARNING("⚠️"),
        INFO("ℹ️"),
        SKIPPED("⏭️");

        private final String icon;

        Status(String icon) {
            this.icon = icon;
        }

        public String getIcon() {
            return icon;
        }
    }

    public ExecutionStep(int stepNumber, String title, String description) {
        this.stepNumber = stepNumber;
        this.title = title;
        this.description = description;
        this.timestamp = Instant.now();
        this.status = Status.EXECUTING;
        this.evidence = new ArrayList<>();
    }

    public ExecutionStep(int stepNumber, String title) {
        this(stepNumber, title, null);
    }

    public void addEvidence(String label, String value, Evidence.Type type) {
        evidence.add(new Evidence(label, value, type, Instant.now()));
    }

    public void addCode(String label, String code) {
        evidence.add(new Evidence(label, code, Evidence.Type.CODE, Instant.now()));
    }

    public void addLog(String label, String log) {
        evidence.add(new Evidence(label, log, Evidence.Type.LOG, Instant.now()));
    }

    public void addResult(String label, String value) {
        evidence.add(new Evidence(label, value, Evidence.Type.RESULT, Instant.now()));
    }

    public void addTable(String label, List<String[]> rows) {
        evidence.add(new Evidence(label, rows));
    }

    public int getStepNumber() { return stepNumber; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Instant getTimestamp() { return timestamp; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public List<Evidence> getEvidence() { return evidence; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
}
