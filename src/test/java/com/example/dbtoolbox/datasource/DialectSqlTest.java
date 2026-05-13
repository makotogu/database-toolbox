package com.example.dbtoolbox.datasource;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectSqlTest {

    @Test
    void buildsMysqlUpsertSql() {
        MySqlDialect dialect = new MySqlDialect();

        String sql = dialect.upsertSql("test_user",
                Arrays.asList(new UpsertColumn("id", null), new UpsertColumn("name", null), new UpsertColumn("age", null)),
                Arrays.asList("id"));

        assertEquals("INSERT INTO `test_user` (`id`, `name`, `age`) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `age` = VALUES(`age`)", sql);
    }

    @Test
    void buildsGaussDbUpsertSql() {
        GaussDbDialect dialect = new GaussDbDialect();

        String sql = dialect.upsertSql("test_user",
                Arrays.asList(new UpsertColumn("id", null), new UpsertColumn("name", null)),
                Arrays.asList("id"));

        assertEquals("MERGE INTO \"test_user\" target USING (SELECT ? AS \"id\", ? AS \"name\" FROM dual) src ON (target.\"id\" = src.\"id\") WHEN MATCHED THEN UPDATE SET target.\"name\" = src.\"name\" WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (src.\"id\", src.\"name\")", sql);
    }

    @Test
    void castsGaussDbMergeParametersWithTargetTypes() {
        GaussDbDialect dialect = new GaussDbDialect();

        String sql = dialect.upsertSql("test_user",
                Arrays.asList(
                        new UpsertColumn("id", "bigint"),
                        new UpsertColumn("updated_at", "timestampz"),
                        new UpsertColumn("amount", "numeric(12,2)")),
                Arrays.asList("id"));

        assertTrue(sql.contains("CAST(? AS bigint) AS \"id\""));
        assertTrue(sql.contains("CAST(? AS timestamp with time zone) AS \"updated_at\""));
        assertTrue(sql.contains("CAST(? AS numeric(12,2)) AS \"amount\""));
    }

    @Test
    void castsGaussDbMergeParametersForArrayTypes() {
        GaussDbDialect dialect = new GaussDbDialect();

        String sql = dialect.upsertSql("test_array",
                Arrays.asList(
                        new UpsertColumn("id", "bigint"),
                        new UpsertColumn("tags", "text[]"),
                        new UpsertColumn("metrics", "numeric(12,2)[]")),
                Arrays.asList("id"));

        assertTrue(sql.contains("CAST(? AS text[]) AS \"tags\""), sql);
        assertTrue(sql.contains("CAST(? AS numeric(12,2)[]) AS \"metrics\""), sql);
    }

    @Test
    void normalizesGaussDbInternalTypeAliases() {
        GaussDbDialect dialect = new GaussDbDialect();

        String sql = dialect.upsertSql("test_alias",
                Arrays.asList(
                        new UpsertColumn("id", "int8"),
                        new UpsertColumn("code", "int4"),
                        new UpsertColumn("flag", "int2"),
                        new UpsertColumn("ratio", "float4"),
                        new UpsertColumn("price", "float8"),
                        new UpsertColumn("name", "bpchar(8)")),
                Arrays.asList("id"));

        assertTrue(sql.contains("CAST(? AS bigint) AS \"id\""), sql);
        assertTrue(sql.contains("CAST(? AS integer) AS \"code\""), sql);
        assertTrue(sql.contains("CAST(? AS smallint) AS \"flag\""), sql);
        assertTrue(sql.contains("CAST(? AS real) AS \"ratio\""), sql);
        assertTrue(sql.contains("CAST(? AS double precision) AS \"price\""), sql);
        assertTrue(sql.contains("CAST(? AS character(8)) AS \"name\""), sql);
    }

    @Test
    void fallsBackToPlainPlaceholderWhenGaussDbTypeUnknown() {
        GaussDbDialect dialect = new GaussDbDialect();

        String sql = dialect.upsertSql("test_unknown",
                Arrays.asList(
                        new UpsertColumn("id", "bigint"),
                        new UpsertColumn("payload", null)),
                Arrays.asList("id"));

        assertTrue(sql.contains("CAST(? AS bigint) AS \"id\""), sql);
        assertTrue(sql.contains("? AS \"payload\""), sql);
        assertTrue(!sql.contains("CAST(? AS null)"), sql);
    }

    @Test
    void buildsGaussDbJdbcUrl() {
        GaussDbDialect dialect = new GaussDbDialect();
        DataSourceConfig config = new DataSourceConfig();
        config.setHost("127.0.0.1");
        config.setPort(8000);
        config.setDatabaseName("gauss_test");
        config.getParams().put("urlPrefix", "jdbc:gaussdb://");
        config.getParams().put("ssl", "false");

        String url = dialect.buildJdbcUrl(config);

        assertEquals("jdbc:gaussdb://127.0.0.1:8000/gauss_test?ssl=false", url);
    }

    @Test
    void rejectsUnsafeIdentifier() {
        GaussDbDialect dialect = new GaussDbDialect();
        try {
            SqlNameUtils.quoteQualifiedName(dialect, "users;drop table users");
        } catch (RuntimeException ex) {
            assertTrue(ex.getMessage().contains("非法数据库标识符"));
            return;
        }
        throw new AssertionError("Expected unsafe identifier to be rejected");
    }
}
