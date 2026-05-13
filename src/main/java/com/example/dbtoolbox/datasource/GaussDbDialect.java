package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StringChecks;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GaussDbDialect implements DatabaseDialect {

    private static final String PARAM_DRIVER_CLASS_NAME = "driverClassName";
    private static final String PARAM_URL_PREFIX = "urlPrefix";
    private static final String DEFAULT_URL_PREFIX = "jdbc:gaussdb://";

    /*
     * 这里按用户现场的 GaussDB Oracle 兼容形态实现。
     * upsert 使用 MERGE INTO，避免和 MySQL 的 ON DUPLICATE KEY UPDATE 混在一起。
     */
    public DatabaseType type() {
        return DatabaseType.GAUSSDB;
    }

    public int defaultPort() {
        return 8000;
    }

    public String driverClassName() {
        return "com.huawei.gaussdb.jdbc.Driver";
    }

    public String buildJdbcUrl(DataSourceConfig config) {
        String host = StringChecks.requireText(config.getHost(), "GaussDB 主机不能为空");
        String database = StringChecks.requireText(config.getDatabaseName(), "GaussDB 数据库名不能为空");
        int port = config.getPort() == null ? defaultPort() : config.getPort().intValue();
        StringBuilder url = new StringBuilder(urlPrefix(config.getParams()))
                .append(host).append(':').append(port).append('/').append(database);
        appendParams(url, config.getParams());
        return url.toString();
    }

    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public String limitSql(String sql, int maxRows) {
        return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + maxRows;
    }

    public String upsertSql(String tableName, List<UpsertColumn> targetColumns, List<String> matchKeys) {
        List<String> sourceColumns = new ArrayList<String>();
        List<String> insertColumns = new ArrayList<String>();
        List<String> insertValues = new ArrayList<String>();
        List<String> matchConditions = new ArrayList<String>();
        List<String> updates = new ArrayList<String>();
        for (UpsertColumn targetColumn : targetColumns) {
            String column = targetColumn.getName();
            String quoted = quoteIdentifier(column);
            sourceColumns.add(parameterExpression(targetColumn) + " AS " + quoted);
            insertColumns.add(quoted);
            insertValues.add("src." + quoted);
            if (!matchKeys.contains(column)) {
                updates.add("target." + quoted + " = src." + quoted);
            }
        }
        for (String key : matchKeys) {
            String quoted = quoteIdentifier(key);
            matchConditions.add("target." + quoted + " = src." + quoted);
        }
        StringBuilder sql = new StringBuilder("MERGE INTO ")
                .append(SqlNameUtils.quoteQualifiedName(this, tableName))
                .append(" target USING (SELECT ")
                .append(join(sourceColumns))
                .append(" FROM dual) src ON (")
                .append(join(matchConditions, " AND "))
                .append(") ");
        if (!updates.isEmpty()) {
            sql.append("WHEN MATCHED THEN UPDATE SET ").append(join(updates)).append(" ");
        }
        sql.append("WHEN NOT MATCHED THEN INSERT (")
                .append(join(insertColumns))
                .append(") VALUES (")
                .append(join(insertValues))
                .append(")");
        return sql.toString();
    }

    private String parameterExpression(UpsertColumn column) {
        String typeName = safeCastType(column.getTypeName());
        if (!StringChecks.hasText(typeName)) {
            return "?";
        }
        return "CAST(? AS " + typeName + ")";
    }

    private String safeCastType(String typeName) {
        if (!StringChecks.hasText(typeName)) {
            // 没拿到类型时由上层决定是否补 CAST：SyncService 走 GaussDB 路径已要求必须查到类型，
            // dialect 单测/历史 caller 仍然允许传 null 退化到纯 ?。
            return null;
        }
        String normalized = normalizeTypeAlias(typeName.trim());
        /*
         * 类型名来自数据库系统目录。白名单覆盖 GaussDB 常见格式：
         * timestamp with time zone、numeric(12,2)、character varying(64)、schema."type"、
         * text[]、numeric(12,2)[]。
         * 一旦白名单挡住非空类型，绝不能静默退化到 "?"——否则 GaussDB 又会推断成 text 再触发
         * "could not determine data type of parameter"。直接报错让上层在保存阶段就停下来排查。
         */
        if (!normalized.matches("[A-Za-z0-9_ .,\"()\\[\\]]+")) {
            throw new AppException("无法识别的 GaussDB 列类型: " + typeName
                    + "。请在 GaussDbDialect.normalizeTypeAlias 中补一条映射，或修正目标表的列类型。");
        }
        return normalized;
    }

    private String normalizeTypeAlias(String typeName) {
        /*
         * format_type 在 openGauss 上可能返回内部别名（int4/int8/bpchar 等），
         * 这些别名 CAST 时不一定被接受，统一映射成 SQL 标准名称。
         * 同时容忍前端传入的 timestampz/timetz 这两个常见写错的简写。
         */
        String lower = typeName.toLowerCase();
        if ("timestampz".equals(lower) || "timestamptz".equals(lower)) {
            return "timestamp with time zone";
        }
        if ("timetz".equals(lower)) {
            return "time with time zone";
        }
        if ("int2".equals(lower)) {
            return "smallint";
        }
        if ("int4".equals(lower)) {
            return "integer";
        }
        if ("int8".equals(lower)) {
            return "bigint";
        }
        if ("float4".equals(lower)) {
            return "real";
        }
        if ("float8".equals(lower)) {
            return "double precision";
        }
        if (lower.startsWith("bpchar")) {
            return "character" + typeName.substring("bpchar".length());
        }
        return typeName;
    }

    private void appendParams(StringBuilder url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        int index = 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (isConnectionOnlyParam(entry.getKey())) {
                continue;
            }
            if (StringChecks.hasText(entry.getKey()) && entry.getValue() != null) {
                url.append(index == 0 ? '?' : '&');
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                index++;
            }
        }
    }

    private String urlPrefix(Map<String, String> params) {
        if (params != null && StringChecks.hasText(params.get(PARAM_URL_PREFIX))) {
            return params.get(PARAM_URL_PREFIX).trim();
        }
        return DEFAULT_URL_PREFIX;
    }

    private boolean isConnectionOnlyParam(String key) {
        return PARAM_DRIVER_CLASS_NAME.equals(key) || PARAM_URL_PREFIX.equals(key);
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String join(List<String> values) {
        return join(values, ", ");
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
