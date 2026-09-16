package com.example.dbtoolbox.datasource;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum DatabaseType {
    MYSQL,
    GAUSSDB;

    @JsonCreator
    public static DatabaseType from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        // 兼容旧版本本地配置文件：旧的连接类型统一迁移为 GaussDB。
        if ("POSTGRESQL".equals(normalized) || "RANGE".equals(normalized)) {
            return GAUSSDB;
        }
        return DatabaseType.valueOf(normalized);
    }
}
