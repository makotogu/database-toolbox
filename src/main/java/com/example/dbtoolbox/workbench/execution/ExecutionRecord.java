package com.example.dbtoolbox.workbench.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.sql.Statement;

public class ExecutionRecord {
    public String id, sessionId, mode;
    public volatile String state = "QUEUED";
    public volatile String message;
    public long startedAt = System.currentTimeMillis();
    public volatile long elapsedMs;
    public List<UnitResult> statements = new CopyOnWriteArrayList<UnitResult>();
    @JsonIgnore public volatile Statement activeStatement;
    @JsonIgnore public volatile boolean cancelRequested, timeoutRequested;
    @JsonIgnore public volatile long finishedAt;
    public static class UnitResult {
        public int index, startLine, endLine;
        public String sql;
        public volatile String state = "PENDING";
        public List<Result> results = new CopyOnWriteArrayList<Result>();
        public List<String> warnings = new CopyOnWriteArrayList<String>();
        public volatile ErrorInfo error;
        public long elapsedMs;
    }
    public static class Result {
        public String kind;
        public List<Column> columns = new ArrayList<Column>();
        public List<List<Object>> rows = new ArrayList<List<Object>>();
        public List<Map<String,Object>> parameters = new ArrayList<Map<String,Object>>();
        public long updateCount;
        public boolean truncated;
        public String message;
    }
    public static class Column {
        public int index, jdbcType;
        public String label, typeName;
    }
    public static class ErrorInfo {
        public String message, sqlState;
        public int vendorCode;
        public ErrorInfo(String message, String sqlState, int vendorCode) { this.message=message; this.sqlState=sqlState; this.vendorCode=vendorCode; }
    }
}
