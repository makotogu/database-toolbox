package com.example.dbtoolbox.sql;

import com.example.dbtoolbox.common.StringChecks;

import java.util.Locale;

public final class SqlClassifier {

    private SqlClassifier() {
    }

    public static SqlType classify(String sql) {
        if (!StringChecks.hasText(sql)) {
            return SqlType.UNKNOWN;
        }
        String normalized = stripComments(sql).trim().toLowerCase(Locale.ENGLISH);
        if (normalized.startsWith("select") || normalized.startsWith("with") || normalized.startsWith("show")
                || normalized.startsWith("desc") || normalized.startsWith("describe") || normalized.startsWith("explain")) {
            return SqlType.READ;
        }
        if (normalized.startsWith("insert") || normalized.startsWith("update") || normalized.startsWith("delete")
                || normalized.startsWith("truncate") || normalized.startsWith("drop") || normalized.startsWith("alter")
                || normalized.startsWith("create") || normalized.startsWith("replace") || normalized.startsWith("merge")) {
            return SqlType.WRITE;
        }
        return SqlType.UNKNOWN;
    }

    private static String stripComments(String sql) {
        StringBuilder builder = new StringBuilder();
        String[] lines = sql.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--")) {
                int comment = line.indexOf("--");
                builder.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
            }
        }
        return builder.toString();
    }
}
