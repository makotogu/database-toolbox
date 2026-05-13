package com.example.dbtoolbox.sql;

import javax.validation.constraints.NotBlank;

public class SqlExecutionRequest {

    @NotBlank(message = "数据源不能为空")
    private String datasourceId;

    @NotBlank(message = "SQL不能为空")
    private String sql;

    private boolean confirmedWrite;
    private int maxRows = 500;

    public String getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(String datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public boolean isConfirmedWrite() {
        return confirmedWrite;
    }

    public void setConfirmedWrite(boolean confirmedWrite) {
        this.confirmedWrite = confirmedWrite;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
}
