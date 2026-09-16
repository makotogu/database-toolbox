package com.example.dbtoolbox.sync;

public class FieldMapping {

    private String sourceColumn;
    private String targetColumn;

    /*
     * 字段映射的"启用"开关：禁用的映射会被保存到模板里，但同步执行时跳过。
     * 默认 true 是为了兼容历史模板（旧 JSON 里没有这个字段，Jackson 会用 setter 默认值）。
     */
    private boolean enabled = true;

    public String getSourceColumn() {
        return sourceColumn;
    }

    public void setSourceColumn(String sourceColumn) {
        this.sourceColumn = sourceColumn;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = targetColumn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
