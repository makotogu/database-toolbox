package com.example.dbtoolbox.sync;

import java.util.ArrayList;
import java.util.List;

public class PartitionProbeResult {

    private String sourceTable;
    private String targetTable;
    private String message;
    private String partitionStrategy;
    private String recommendedSqlTemplate;
    private List<PartitionDefinition> definitions = new ArrayList<PartitionDefinition>();

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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPartitionStrategy() {
        return partitionStrategy;
    }

    public void setPartitionStrategy(String partitionStrategy) {
        this.partitionStrategy = partitionStrategy;
    }

    public String getRecommendedSqlTemplate() {
        return recommendedSqlTemplate;
    }

    public void setRecommendedSqlTemplate(String recommendedSqlTemplate) {
        this.recommendedSqlTemplate = recommendedSqlTemplate;
    }

    public List<PartitionDefinition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(List<PartitionDefinition> definitions) {
        this.definitions = definitions == null ? new ArrayList<PartitionDefinition>() : definitions;
    }
}
