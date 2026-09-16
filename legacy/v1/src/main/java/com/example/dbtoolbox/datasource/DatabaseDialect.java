package com.example.dbtoolbox.datasource;

import java.util.List;

public interface DatabaseDialect {

    /*
     * 方言是本工具箱隔离数据库差异的核心扩展点。
     * 新增数据库类型时，优先在这里补齐 SQL 生成规则，不要把 if/else 散落到业务 service。
     */
    DatabaseType type();

    int defaultPort();

    String driverClassName();

    /*
     * 只负责根据页面上的 host/port/database/params 拼出默认 URL。
     * 如果用户在页面填写了完整 jdbcUrl，JdbcConnectionFactory 会优先使用用户填写的 URL。
     */
    String buildJdbcUrl(DataSourceConfig config);

    /*
     * 只处理单个标识符，例如 column_name。
     * schema.table 这类复合表名统一交给 SqlNameUtils.quoteQualifiedName 处理。
     */
    String quoteIdentifier(String identifier);

    /*
     * SQL 执行页面用于给 SELECT 自动加最大行数限制。
     * 这里假设传入 sql 已经是一个完整查询，方言只追加自己的 limit/top 语法。
     */
    String limitSql(String sql, int maxRows);

    /*
     * 字段同步默认策略是“按匹配键覆盖”：目标匹配键存在就更新，不存在就插入。
     * targetColumns 是目标字段信息列表，matchKeys 必须是 targetColumns.name 的子集。
     * typeName 允许方言按目标列类型生成参数 cast，避免 MERGE/SELECT 参数被数据库推断成 text。
     */
    String upsertSql(String tableName, List<UpsertColumn> targetColumns, List<String> matchKeys);

    /*
     * 可选的集合式批量 upsert。默认返回 null，表示继续使用 upsertSql + JDBC batch。
     * GaussDB MERGE 每行一条语句时解析/执行开销很高，可用一条 MERGE 合并多行源数据。
     */
    default String batchUpsertSql(String tableName,
                                  List<UpsertColumn> targetColumns,
                                  List<String> matchKeys,
                                  int rowCount) {
        return null;
    }
}
