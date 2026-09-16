package com.example.dbtoolbox.workbench.dialect;

import com.example.dbtoolbox.common.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlDialectTest {
    private Connection connection;
    @BeforeEach void setup() throws Exception {
        connection = new org.h2.Driver().connect("jdbc:h2:mem:" + UUID.randomUUID(), new java.util.Properties());
        connection.createStatement().execute("CREATE SCHEMA \"s_% dot\"");
        connection.createStatement().execute("CREATE TABLE \"s_% dot\".\"table_%\"\" dot\" (\"id\" INT PRIMARY KEY, \"value_%\" VARCHAR(80), \"money\" DECIMAL(25,3))");
        connection.createStatement().execute("INSERT INTO \"s_% dot\".\"table_%\"\" dot\" VALUES(1, 'first', 12345678901234567890.123),(2, 'second', NULL),(3, 'second', 4.500)");
    }
    @AfterEach void cleanup() throws Exception { connection.close(); }

    @Test void quotesWholeIdentifiersIncludingDotsAndEmbeddedQuotes() throws Exception {
        assertEquals("\"s_% dot\".\"table_%\"\" dot\"", SqlDialect.qualified(connection, connection.getCatalog(), "s_% dot", "table_%\" dot"));
        assertEquals("H2", SqlDialect.detect(connection, null));
    }

    @Test void postgresCatalogScopeUsesOnlyTheConnectedDatabase() throws Exception {
        Connection postgres = mock(Connection.class);
        java.sql.DatabaseMetaData md = mock(java.sql.DatabaseMetaData.class);
        when(postgres.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(postgres.getCatalog()).thenReturn("current_database");
        assertNull(SqlDialect.metadataCatalog(postgres, "current_database"));
        assertNull(SqlDialect.metadataCatalog(postgres, null));
        assertThrows(AppException.class, () -> SqlDialect.metadataCatalog(postgres, "other_database"));
    }

    @Test void filtersAreBoundAndPagingUsesPrimaryKeyTieBreaker() throws Exception {
        SqlDialect.PreviewQuery query = preview(Collections.singletonMap("column", "value_%"), "value_%", 1, 1);
        // A null equality becomes IS NULL, without a bound null value.
        assertTrue(query.sql.contains("\"value_%\" IS NULL"));
        Map<String, Object> filter = filter("value_%", "=", "second");
        query = preview(filter, "value_%", 1, 1);
        assertTrue(query.sql.contains("ORDER BY \"value_%\" ASC, \"id\" ASC"));
        assertFalse(query.sql.contains("second"));
        try (PreparedStatement ps = connection.prepareStatement(query.sql)) {
            for (int i = 0; i < query.params.size(); i++) ps.setObject(i + 1, query.params.get(i));
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertFalse(rs.next()); }
        }
    }

    @Test void exactMetadataPatternDoesNotAcceptWildcardNeighbour() throws Exception {
        assertThrows(AppException.class, () -> SqlDialect.previewSql(connection, null, null, "s_% dot", "table_%", null, null, false, 0, 20));
    }

    @Test void rejectsUnknownColumnsAndOperatorInjection() throws Exception {
        assertThrows(AppException.class, () -> preview(filter("value_%", "= ? OR 1=1 --", "x"), null, 0, 10));
        assertThrows(AppException.class, () -> preview(filter("not a column", "=", "x"), null, 0, 10));
        assertThrows(AppException.class, () -> preview(null, "id DESC; DROP TABLE t", 0, 10));
    }

    @Test void bindsNumericValuesWithoutLosingPrecision() throws Exception {
        SqlDialect.PreviewQuery query = preview(filter("money", "=", "12345678901234567890.123"), null, 0, 10);
        assertEquals("12345678901234567890.123", query.params.get(0).toString());
        try (PreparedStatement ps = connection.prepareStatement(query.sql)) {
            for (int i = 0; i < query.params.size(); i++) ps.setObject(i + 1, query.params.get(i));
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); }
        }
        assertThrows(AppException.class, () -> preview(filter("id", "=", "1.5"), null, 0, 10));
        assertThrows(AppException.class, () -> preview(filter("id", "LIKE", "1%"), null, 0, 10));
    }

    @Test void genericDialectAllowsOnlyBoundedFirstPage() throws Exception {
        SqlDialect.PreviewQuery query = SqlDialect.previewSql(connection, "GENERIC", null, "s_% dot", "table_%\" dot", null, null, false, 0, 20);
        assertFalse(query.pagingSupported);
        assertEquals(20, query.limit);
        assertFalse(query.sql.contains("LIMIT"));
        assertThrows(AppException.class, () -> SqlDialect.previewSql(connection, "GENERIC", null, "s_% dot", "table_%\" dot", null, null, false, 20, 20));
    }

    @Test void explainPreservesExistingPrefixAndRunsOnH2() throws Exception {
        String sql = SqlDialect.explainSql(connection, null, "-- test\nSELECT 1;", false);
        assertEquals("EXPLAIN SELECT 1", sql);
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) { assertTrue(rs.next()); assertTrue(rs.getString(1).contains("SELECT")); }
        assertEquals(sql, SqlDialect.explainSql(connection, null, sql, false));
        assertEquals("EXPLAIN (FORMAT JSON) SELECT 1", SqlDialect.explainSql(connection, "POSTGRESQL", "SELECT 1", false));
    }

    @Test void explainAnalyzeRequiresExplicitExecutionModeEvenWithComments() {
        for (String sql : Arrays.asList("EXPLAIN ANALYZE SELECT 1", "EXPLAIN (ANALYZE TRUE, FORMAT JSON) SELECT 1", "EXPLAIN /* SELECT */ ANALYZE SELECT 1", "EXPLAIN /*!80018 ANALYZE */ SELECT 1")) {
            assertThrows(AppException.class, () -> SqlDialect.explainSql(connection, "MYSQL", sql, false));
        }
        assertDoesNotThrow(() -> SqlDialect.explainSql(connection, null, "EXPLAIN SELECT 'ANALYZE'", false));
        assertDoesNotThrow(() -> SqlDialect.explainSql(connection, null, "EXPLAIN ANALYZE SELECT 1", true));
    }

    @Test void explainDoesNotInventUnsupportedVendorSyntax() {
        assertThrows(AppException.class, () -> SqlDialect.explainSql(connection, "ORACLE", "SELECT 1 FROM dual", false));
        assertThrows(AppException.class, () -> SqlDialect.explainSql(connection, "GENERIC", "SELECT 1", false));
        assertThrows(AppException.class, () -> SqlDialect.explainSql(connection, null, "CALL SOME_PROC()", false));
    }

    private SqlDialect.PreviewQuery preview(Map<String, Object> filter, String orderBy, int offset, int limit) throws Exception {
        return SqlDialect.previewSql(connection, null, connection.getCatalog(), "s_% dot", "table_%\" dot", filter == null ? null : Collections.singletonList(filter), orderBy, false, offset, limit);
    }
    private static Map<String, Object> filter(String column, String operator, Object value) {
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("column", column); filter.put("operator", operator); filter.put("value", value); return filter;
    }
}
