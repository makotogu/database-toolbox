package com.example.dbtoolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toolbox")
public class ToolboxProperties {

    private String storageRoot = "data";
    private int jobPoolSize = 4;

    /*
     * 任务历史保留条数上限。终态（SUCCESS/FAILED）任务超过该数量后，
     * 按 createdAt 从旧到新被淘汰；运行中的任务不会被裁剪。
     */
    private int maxJobHistory = 200;

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public int getJobPoolSize() {
        return jobPoolSize;
    }

    public void setJobPoolSize(int jobPoolSize) {
        this.jobPoolSize = jobPoolSize;
    }

    public int getMaxJobHistory() {
        return maxJobHistory;
    }

    public void setMaxJobHistory(int maxJobHistory) {
        this.maxJobHistory = maxJobHistory;
    }
}
