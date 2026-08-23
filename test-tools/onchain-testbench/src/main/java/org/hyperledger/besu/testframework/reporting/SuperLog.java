package org.hyperledger.besu.testframework.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Immutable audit log ("Super LOG") that captures every step of the permissioning plugin test.
 *
 * Architecture:
 * - Single append-only file in logs/evolution/
 * - Phase markers with timestamps
 * - Docker logs aggregated in background
 * - SHA-256 integrity hash at close
 *
 * Usage:
 * <pre>{@code
 * SuperLog log = SuperLog.start("evolution-test");
 * log.phase("FASE 1", "Ecossistema Legado - Besu Hardcoded + GEN02");
 * log.evidence("Block number", "0x10");
 * log.shell("deploy-admin", "forge create ...", "Deployed to: 0x...");
 * log.close();
 * }</pre>
 */
public class SuperLog implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SuperLog.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final Path auditFile;
    private final BufferedWriter writer;
    private final String testName;
    private boolean closed = false;

    private SuperLog(String testName, Path auditFile) throws IOException {
        this.testName = testName;
        this.auditFile = auditFile;
        Files.createDirectories(auditFile.getParent());
        this.writer = Files.newBufferedWriter(auditFile, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Initializes a new Super LOG session with header metadata.
     */
    public static SuperLog start(String testName) {
        try {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
            Path dir = Paths.get("logs/evolution");
            Files.createDirectories(dir);
            Path file = dir.resolve("superlog-" + testName + "-" + timestamp + ".log");

            SuperLog log = new SuperLog(testName, file);

            log.raw("================================================================");
            log.raw("  SUPER LOG — PERMISSIONING PLUGIN AUDIT TRAIL");
            log.raw("  Test: " + testName);
            log.raw("  Started: " + Instant.now().toString());
            log.raw("  Host: " + getHostname());
            log.raw("  User: " + System.getProperty("user.name"));
            log.raw("================================================================");
            log.raw("");

            LOG.info("Super LOG started: {}", file);
            return log;
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Super LOG", e);
        }
    }

    /**
     * Injects a phase marker with timestamp.
     */
    public void phase(String phaseId, String description) {
        raw("");
        raw("==================== [" + phaseId + "] " + Instant.now().toString() + " ====================");
        raw("  " + description);
        raw("");
    }

    /**
     * Records a shell command with its output.
     */
    public void shell(String label, String command, String output) {
        section("SHELL: " + label);
        raw("  Command: " + command);
        if (output != null && !output.isEmpty()) {
            raw("  Output:");
            for (String line : output.split("\n")) {
                raw("    " + line);
            }
        }
    }

    /**
     * Records an evidence entry (test result, block number, contract address, etc.).
     */
    public void evidence(String key, String value) {
        raw("[EVIDENCE] " + key + ": " + value);
    }

    /**
     * Records a PASS verification.
     */
    public void pass(String description) {
        raw("[PASS] " + description);
    }

    /**
     * Records a FAIL verification.
     */
    public void fail(String description, String expected, String actual) {
        raw("[FAIL] " + description + " — expected: " + expected + ", actual: " + actual);
    }

    /**
     * Records a warning.
     */
    public void warn(String message) {
        raw("[WARN] " + message);
    }

    /**
     * Captures Docker container logs into the audit trail.
     */
    public void dockerLogs(String containerName, String logs) {
        section("DOCKER: " + containerName);
        if (logs != null) {
            for (String line : logs.split("\n")) {
                raw("  " + containerName + " | " + line);
            }
        }
    }

    /**
     * Appends a raw header-like section.
     */
    public void section(String title) {
        raw("---- " + title + " ----");
    }

    /**
     * Appends a raw string to the log.
     */
    public void raw(String line) {
        if (closed) {
            LOG.warn("Super LOG already closed. Ignoring write: {}", line);
            return;
        }
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOG.error("Failed to write to Super LOG: {}", e.getMessage());
        }
    }

    /**
     * Closes the Super LOG, computes SHA-256 hash, and writes the footer.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        try {
            writer.flush();
            writer.close();

            // Compute hash
            byte[] fileBytes = Files.readAllBytes(auditFile);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(fileBytes);
            StringBuilder hexHash = new StringBuilder();
            for (byte b : hash) {
                hexHash.append(String.format("%02x", b));
            }

            long lineCount = Files.readAllLines(auditFile).size();

            // Append footer
            BufferedWriter footer = Files.newBufferedWriter(auditFile, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
            footer.newLine();
            footer.write("================================================================");
            footer.newLine();
            footer.write("  SUPER LOG CLOSED: " + Instant.now().toString());
            footer.newLine();
            footer.write("  SHA-256: " + hexHash.toString());
            footer.newLine();
            footer.write("  Lines: " + lineCount);
            footer.newLine();
            footer.write("================================================================");
            footer.newLine();
            footer.close();

            // Write hash file
            Path hashFile = Paths.get(auditFile.toString().replace(".log", ".sha256"));
            Files.writeString(hashFile, hexHash.toString() + "  " + auditFile.getFileName().toString());

            LOG.info("Super LOG closed: {} ({} lines, SHA-256: {})", auditFile, lineCount, hexHash);

        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.error("Failed to close Super LOG: {}", e.getMessage());
        }
    }

    /**
     * Returns the path to the audit file.
     */
    public Path getAuditFilePath() {
        return auditFile;
    }

    private static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
