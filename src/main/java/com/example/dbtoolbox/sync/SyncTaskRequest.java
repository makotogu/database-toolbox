package com.example.dbtoolbox.sync;

import javax.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

public class SyncTaskRequest {

    private String id;

    @NotBlank(message = "同步任务名称不能为空")
    private String name;

    @NotBlank(message = "源数据源不能为空")
    private String sourceDatasourceId;

    @NotBlank(message = "目标数据源不能为空")
    private String targetDatasourceId;

    @NotBlank(message = "源表不能为空")
    private String sourceTable;

    @NotBlank(message = "目标表不能为空")
    private String targetTable;

    private String whereClause;
    private List<FieldMapping> fieldMappings = new ArrayList<FieldMapping>();
    private List<String> matchKeys = new ArrayList<String>();
    private int fetchSize = 1000;
    private int batchSize = 1000;
    private boolean backupBeforeWrite = true;
    private PartitionRule partitionRule = new PartitionRule();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceDatasourceId() {
        return sourceDatasourceId;
    }

    public void setSourceDatasourceId(String sourceDatasourceId) {
        this.sourceDatasourceId = sourceDatasourceId;
    }

    public String getTargetDatasourceId() {
        return targetDatasourceId;
    }

    public void setTargetDatasourceId(String targetDatasourceId) {
        this.targetDatasourceId = targetDatasourceId;
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

    public String getWhereClause() {
        return whereClause;
    }

    public void setWhereClause(String whereClause) {
        this.whereClause = whereClause;
    }

    public List<FieldMapping> getFieldMappings() {
        return fieldMappings;
    }

    public void setFieldMappings(List<FieldMapping> fieldMappings) {
        this.fieldMappings = fieldMappings == null ? new ArrayList<FieldMapping>() : fieldMappings;
    }

    public List<String> getMatchKeys() {
        return matchKeys;
    }

    public void setMatchKeys(List<String> matchKeys) {
        this.matchKeys = matchKeys == null ? new ArrayList<String>() : matchKeys;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isBackupBeforeWrite() {
        return backupBeforeWrite;
    }

    public void setBackupBeforeWrite(boolean backupBeforeWrite) {
        this.backupBeforeWrite = backupBeforeWrite;
    }

    public PartitionRule getPartitionRule() {
        return partitionRule;
    }

    public void setPartitionRule(PartitionRule partitionRule) {
        this.partitionRule = partitionRule == null ? new PartitionRule() : partitionRule;
    }
}
