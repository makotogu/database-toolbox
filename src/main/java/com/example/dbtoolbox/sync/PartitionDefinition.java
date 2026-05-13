package com.example.dbtoolbox.sync;

public class PartitionDefinition {

    private String partitionName;
    private String fromValue;
    private String toValue;
    private String lessThanValue;

    public String getPartitionName() {
        return partitionName;
    }

    public void setPartitionName(String partitionName) {
        this.partitionName = partitionName;
    }

    public String getFromValue() {
        return fromValue;
    }

    public void setFromValue(String fromValue) {
        this.fromValue = fromValue;
    }

    public String getToValue() {
        return toValue;
    }

    public void setToValue(String toValue) {
        this.toValue = toValue;
    }

    public String getLessThanValue() {
        return lessThanValue;
    }

    public void setLessThanValue(String lessThanValue) {
        this.lessThanValue = lessThanValue;
    }
}
