package com.example.dbtoolbox.sync;

import javax.validation.constraints.NotBlank;

public class PartitionProbeRequest {

    @NotBlank(message = "源数据源不能为空")
    private String sourceDatasourceId;

    @NotBlank(message = "源表不能为空")
    private String sourceTable;

    private String targetTable;

    public String getSourceDatasourceId() {
        return sourceDatasourceId;
    }

    public void setSourceDatasourceId(String sourceDatasourceId) {
        this.sourceDatasourceId = sourceDatasourceId;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }
}
