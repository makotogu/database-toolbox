package com.example.dbtoolbox.workbench.metadata;

import com.example.dbtoolbox.common.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MetadataServiceTest {
    private Connection connection;
    private final MetadataService metadata = new MetadataService(null);

    @BeforeEach void setup() throws Exception {
        connection = new org.h2.Driver().connect("jdbc:h2:mem:" + UUID.randomUUID(), new java.util.Properties());
        connection.createStatement().execute("CREATE SCHEMA \"s_%\"");
        connection.createStatement().execute("CREATE SCHEMA \"s_ax\"");
        connection.createStatement().execute("CREATE TABLE \"s_%\".\"parent\" (\"id\" INT PRIMARY KEY)");
        connection.createStatement().execute("CREATE TABLE \"s_%\".\"t_%\" (\"id\" INT PRIMARY KEY, \"value\" VARCHAR(40) DEFAULT 'hello', \"parent_id\" INT REFERENCES \"s_%\".\"parent\"(\"id\"))");
        connection.createStatement().execute("CREATE TABLE \"s_%\".\"t_ax\" (\"wrong\" INT)");
        connection.createStatement().execute("CREATE TABLE \"s_ax\".\"t_%\" (\"wrong schema\" INT)");
        connection.createStatement().execute("CREATE VIEW \"s_%\".\"view with.dot\" AS SELECT * FROM \"s_%\".\"t_%\"");
        connection.createStatement().execute("COMMENT ON COLUMN \"s_%\".\"t_%\".\"value\" IS '中文备注'");
    }
    @AfterEach void cleanup() throws Exception { connection.close(); }

    @Test void tablesAndViewsStayWithinExactSchema() throws Exception {
        List<Map<String, Object>> objects = metadata.objects(connection, "tables", connection.getCatalog(), "s_%");
        assertEquals(4, objects.size());
        assertTrue(objects.stream().allMatch(o -> "s_%".equals(o.get("schema"))));
        assertTrue(objects.stream().anyMatch(o -> "view with.dot".equals(o.get("name")) && "VIEW".equals(o.get("type"))));
    }

    @SuppressWarnings("unchecked")
    @Test void exactTableLookupPreservesStructureAndRelationships() throws Exception {
        Map<String, Object> structure = metadata.tableStructure(connection, connection.getCatalog(), "s_%", "t_%");
        List<Map<String, Object>> columns = (List<Map<String, Object>>) structure.get("columns");
        assertEquals(3, columns.size());
        assertEquals("id", columns.get(0).get("name"));
        assertEquals(true, columns.get(0).get("primaryKey"));
        assertEquals(false, columns.get(0).get("nullable"));
        assertEquals("中文备注", columns.get(1).get("remarks"));
        assertEquals("'hello'", columns.get(1).get("defaultValue"));
        assertFalse(((List<?>) structure.get("indexes")).isEmpty());
        List<Map<String, Object>> foreignKeys = (List<Map<String, Object>>) structure.get("foreignKeys");
        assertEquals(1, foreignKeys.size());
        assertEquals("parent", foreignKeys.get(0).get("referencedTable"));
        assertEquals("parent_id", foreignKeys.get(0).get("column"));
    }

    @Test void absentTableAndInvalidKindAreExplicitErrors() {
        assertThrows(AppException.class, () -> metadata.tableStructure(connection, null, "s_%", "t_"));
        assertThrows(AppException.class, () -> metadata.objects(connection, "unexpected", null, null));
    }

    @SuppressWarnings("unchecked")
    @Test void routineMetadataProvidesReturnAndCallableTemplate() throws Exception {
        connection.createStatement().execute("CREATE ALIAS \"s_%\".\"absolute_%\" FOR 'java.lang.Math.abs(int)'");
        List<Map<String, Object>> routines = metadata.objects(connection, "routines", connection.getCatalog(), "s_%");
        Map<String, Object> routine = routines.stream().filter(r -> "absolute_%".equals(r.get("name"))).findFirst().orElseThrow(AssertionError::new);
        Map<String, Object> detail = metadata.routineDetail(connection, connection.getCatalog(), "s_%", "absolute_%", (String) routine.get("type"), (String) routine.get("specificName"));
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) detail.get("parameters");
        assertEquals(2, parameters.size());
        assertEquals("RETURN", parameters.get(0).get("mode"));
        assertEquals(0, parameters.get(0).get("ordinalPosition"));
        assertEquals(1, parameters.get(0).get("position"));
        assertEquals("IN", parameters.get(1).get("mode"));
        assertEquals(1, parameters.get(1).get("ordinalPosition"));
        assertEquals(2, parameters.get(1).get("position"));
        assertEquals("{? = call \"s_%\".\"absolute_%\"(?)}", detail.get("callSql"));
        assertNull(detail.get("definition"));
        assertFalse(((List<?>) detail.get("warnings")).isEmpty());
    }
}
