package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.StringChecks;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MySqlDialect implements DatabaseDialect {

    public DatabaseType type() {
        return DatabaseType.MYSQL;
    }

    public int defaultPort() {
        return 3306;
    }

    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    public String buildJdbcUrl(DataSourceConfig config) {
        String host = StringChecks.requireText(config.getHost(), "MySQL 主机不能为空");
        String database = StringChecks.requireText(config.getDatabaseName(), "MySQL 数据库名不能为空");
        int port = config.getPort() == null ? defaultPort() : config.getPort().intValue();
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(host).append(':').append(port).append('/').append(database)
                .append("?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false")
                .append("&allowPublicKeyRetrieval=true&useCursorFetch=true");
        appendParams(url, config.getParams());
        return url.toString();
    }

    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public String limitSql(String sql, int maxRows) {
        return sql + " LIMIT " + maxRows;
    }

    public String upsertSql(String tableName, List<UpsertColumn> targetColumns, List<String> matchKeys) {
        List<String> quotedColumns = new ArrayList<String>();
        List<String> placeholders = new ArrayList<String>();
        List<String> updates = new ArrayList<String>();
        for (UpsertColumn targetColumn : targetColumns) {
            String column = targetColumn.getName();
            quotedColumns.add(quoteIdentifier(column));
            placeholders.add("?");
            if (!matchKeys.contains(column)) {
                updates.add(quoteIdentifier(column) + " = VALUES(" + quoteIdentifier(column) + ")");
            }
        }
        if (updates.isEmpty() && !targetColumns.isEmpty()) {
            String column = targetColumns.get(0).getName();
            updates.add(quoteIdentifier(column) + " = VALUES(" + quoteIdentifier(column) + ")");
        }
        return "INSERT INTO " + SqlNameUtils.quoteQualifiedName(this, tableName)
                + " (" + join(quotedColumns) + ") VALUES (" + join(placeholders) + ")"
                + " ON DUPLICATE KEY UPDATE " + join(updates);
    }

    private void appendParams(StringBuilder url, Map<String, String> params) {
        if (params == null) {
            return;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (StringChecks.hasText(entry.getKey()) && entry.getValue() != null) {
                url.append('&').append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
