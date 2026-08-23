package org.hyperledger.besu.testframework.reporting;

import java.time.Instant;
import java.util.List;

/**
 * A piece of evidence collected during a test step.
 * Can be a log line, a code snippet, a table, a result value, or an observation.
 */
public class Evidence {
    private final String label;
    private final String value;
    private final Type type;
    private final List<String[]> tableData;
    private final Instant timestamp;

    public enum Type {
        /** A log line from the Besu node or plugin */
        LOG,
        /** Source code or command executed */
        CODE,
        /** A test result or assertion outcome */
        RESULT,
        /** A free-form observation */
        OBSERVATION,
        /** Tabular data */
        TABLE,
        /** An error or stack trace */
        ERROR,
        /** A metric or measurement */
        METRIC
    }

    public Evidence(String label, String value, Type type, Instant timestamp) {
        this.label = label;
        this.value = value;
        this.type = type;
        this.tableData = null;
        this.timestamp = timestamp;
    }

    public Evidence(String label, List<String[]> tableData) {
        this.label = label;
        this.value = null;
        this.type = Type.TABLE;
        this.tableData = tableData;
        this.timestamp = Instant.now();
    }

    public String getLabel() { return label; }
    public String getValue() { return value; }
    public Type getType() { return type; }
    public List<String[]> getTableData() { return tableData; }
    public Instant getTimestamp() { return timestamp; }

    public boolean isCode() { return type == Type.CODE; }
    public boolean isLog() { return type == Type.LOG; }
    public boolean isResult() { return type == Type.RESULT; }
    public boolean isError() { return type == Type.ERROR; }
    public boolean isTable() { return type == Type.TABLE; }
}
