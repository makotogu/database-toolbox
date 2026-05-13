package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.AppException;

public final class SqlNameUtils {

    private SqlNameUtils() {
    }

    public static String quoteQualifiedName(DatabaseDialect dialect, String rawName) {
        if (rawName == null || rawName.trim().length() == 0) {
            throw new AppException("表名不能为空");
        }
        String[] parts = rawName.trim().split("\\.");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                throw new AppException("非法数据库标识符: " + rawName);
            }
            if (i > 0) {
                builder.append('.');
            }
            builder.append(dialect.quoteIdentifier(part));
        }
        return builder.toString();
    }
}
