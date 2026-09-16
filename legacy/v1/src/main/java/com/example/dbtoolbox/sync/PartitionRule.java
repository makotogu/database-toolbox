package com.example.dbtoolbox.sync;

import java.util.ArrayList;
import java.util.List;

public class PartitionRule {

    /*
     * PartitionRule 是“同步前自动建分区”的页面配置模型。
     * definitions 负责描述分区边界，sqlTemplate 负责把边界渲染成目标库能执行的 DDL。
     */
    private boolean enabled;
    private String partitionStrategy = "RANGE";

    /*
     * 预留给模板使用的分区字段名。
     * 当前默认 RANGE/LIST 策略模板不直接使用它，但部分现场语法可能需要 {partitionColumnQuoted}。
     */
    private String partitionColumn;
    private String sqlTemplate;

    /*
     * 测试环境经常重复执行同步任务，分区已存在不应该默认中断整批同步。
     * 如果要严格校验分区创建结果，在页面把该项关掉即可。
     */
    private boolean ignoreCreateErrors = true;
    private List<PartitionDefinition> definitions = new ArrayList<PartitionDefinition>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPartitionStrategy() {
        return partitionStrategy;
    }

    public void setPartitionStrategy(String partitionStrategy) {
        this.partitionStrategy = partitionStrategy == null || partitionStrategy.trim().length() == 0
                ? "RANGE"
                : partitionStrategy.trim().toUpperCase();
    }

    public String getPartitionColumn() {
        return partitionColumn;
    }

    public void setPartitionColumn(String partitionColumn) {
        this.partitionColumn = partitionColumn;
    }

    public String getSqlTemplate() {
        return sqlTemplate;
    }

    public void setSqlTemplate(String sqlTemplate) {
        this.sqlTemplate = sqlTemplate;
    }

    public boolean isIgnoreCreateErrors() {
        return ignoreCreateErrors;
    }

    public void setIgnoreCreateErrors(boolean ignoreCreateErrors) {
        this.ignoreCreateErrors = ignoreCreateErrors;
    }

    public List<PartitionDefinition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(List<PartitionDefinition> definitions) {
        this.definitions = definitions == null ? new ArrayList<PartitionDefinition>() : definitions;
    }
}
