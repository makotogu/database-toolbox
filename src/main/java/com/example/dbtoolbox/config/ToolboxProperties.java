package com.example.dbtoolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toolbox")
public class ToolboxProperties {

    private String storageRoot = "data";
    private int jobPoolSize = 4;

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
}
