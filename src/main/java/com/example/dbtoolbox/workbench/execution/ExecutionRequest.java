package com.example.dbtoolbox.workbench.execution;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.Types;

public class ExecutionRequest {
    public String sessionId, sql, requestId, confirmationToken;
    public String mode = "SQL";
    public int cursorOffset;
    public int maxRows = 500;
    public int timeoutSeconds = 60;
    public boolean analyze;
    public List<Parameter> parameters = new ArrayList<Parameter>();
    public String table, catalog, schema, orderBy;
    public boolean descending;
    public int offset;
    public int limit = 200;
    public String filterMatch = "ALL";
    public List<Map<String,Object>> filters = new ArrayList<Map<String,Object>>();
    public CellChange cellChange;
    public static class CellChange {
        public String executionId, value;
        public int result, row, column;
        public boolean nullValue;
    }
    public static class Parameter {
        public int position;
        public String name, typeName;
        public String mode = "IN";
        public int jdbcType = Types.VARCHAR;
        public Object value;
        @JsonAlias({"isNull", "null"}) public boolean nullValue;
    }
}
