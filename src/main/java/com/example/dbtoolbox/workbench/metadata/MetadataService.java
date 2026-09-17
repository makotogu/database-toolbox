package com.example.dbtoolbox.workbench.metadata;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.workbench.connection.ConnectionService;
import com.example.dbtoolbox.workbench.dialect.SqlDialect;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only object browsing via short-lived connections, independent from SQL editor sessions. */
@Service
public class MetadataService {
    private final ConnectionService connections;
    public MetadataService(ConnectionService connections) { this.connections = connections; }

    public List<Map<String, Object>> objects(String id, String kind, String catalog, String schema) {
        try (Connection connection = connections.open(id)) {
            return objects(connection, kind, emptyToNull(catalog), emptyToNull(schema));
        } catch (SQLException ex) { throw metadataError("读取数据库对象", ex); }
    }

    public Map<String, Object> tableStructure(String id, String catalog, String schema, String table) {
        requireName(table);
        try (Connection connection = connections.open(id)) {
            return tableStructure(connection, emptyToNull(catalog), emptyToNull(schema), table);
        } catch (SQLException ex) { throw metadataError("读取表结构", ex); }
    }

    public Map<String, Object> routineDetail(String id, String catalog, String schema, String name, String type, String specificName) {
        requireName(name);
        try (Connection connection = connections.open(id)) {
            return routineDetail(connection, emptyToNull(catalog), emptyToNull(schema), name, type, specificName);
        } catch (SQLException ex) { throw metadataError("读取存储过程/函数", ex); }
    }

    // Connection-based overloads also allow vendor integration tests without application storage.
    public List<Map<String, Object>> objects(Connection connection, String kind, String catalog, String schema) throws SQLException {
        catalog = SqlDialect.metadataCatalog(connection, catalog);
        DatabaseMetaData md = connection.getMetaData();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if ("catalogs".equals(kind)) {
            try (ResultSet rs = md.getCatalogs()) {
                while (rs.next()) result.add(row("name", rs.getString("TABLE_CAT"), "catalog", rs.getString("TABLE_CAT"), "schema", null, "type", "CATALOG"));
            }
        } else if ("schemas".equals(kind)) {
            try (ResultSet rs = md.getSchemas(catalog, null)) {
                while (rs.next()) if (optionalSame(catalog, rs.getString("TABLE_CATALOG"))) {
                    result.add(row("name", rs.getString("TABLE_SCHEM"), "catalog", rs.getString("TABLE_CATALOG"), "schema", rs.getString("TABLE_SCHEM"), "type", "SCHEMA"));
                }
            }
        } else if ("tables".equals(kind)) {
            try (ResultSet rs = md.getTables(catalog, SqlDialect.exactPattern(md, schema), null, new String[]{"TABLE", "BASE TABLE", "VIEW", "MATERIALIZED VIEW"})) {
                while (rs.next()) if (scope(rs, catalog, schema, "TABLE_CAT", "TABLE_SCHEM")) {
                    result.add(row("name", rs.getString("TABLE_NAME"), "catalog", rs.getString("TABLE_CAT"), "schema", rs.getString("TABLE_SCHEM"),
                            "type", rs.getString("TABLE_TYPE"), "remarks", rs.getString("REMARKS")));
                }
            }
        } else if ("routines".equals(kind)) {
            int unsupported = 0;
            try (ResultSet rs = md.getProcedures(catalog, SqlDialect.exactPattern(md, schema), null)) {
                while (rs.next()) if (scope(rs, catalog, schema, "PROCEDURE_CAT", "PROCEDURE_SCHEM")) {
                    result.add(row("name", rs.getString("PROCEDURE_NAME"), "catalog", rs.getString("PROCEDURE_CAT"), "schema", rs.getString("PROCEDURE_SCHEM"),
                            "type", "PROCEDURE", "specificName", optionalString(rs, "SPECIFIC_NAME"), "remarks", rs.getString("REMARKS")));
                }
            } catch (SQLException ex) { if (unsupported(ex)) unsupported++; else throw ex; }
            try (ResultSet rs = md.getFunctions(catalog, SqlDialect.exactPattern(md, schema), null)) {
                while (rs.next()) if (scope(rs, catalog, schema, "FUNCTION_CAT", "FUNCTION_SCHEM")) {
                    result.add(row("name", rs.getString("FUNCTION_NAME"), "catalog", rs.getString("FUNCTION_CAT"), "schema", rs.getString("FUNCTION_SCHEM"),
                            "type", "FUNCTION", "specificName", optionalString(rs, "SPECIFIC_NAME"), "remarks", rs.getString("REMARKS")));
                }
            } catch (SQLException ex) { if (unsupported(ex)) unsupported++; else throw ex; }
            if (unsupported == 2) throw new AppException("此驱动不支持过程/函数元数据，请在 SQL 编辑器使用原生 SQL 查看");
        } else throw new AppException("对象种类必须为 catalogs、schemas、tables 或 routines");
        Collections.sort(result, Comparator.comparing(value -> String.valueOf(value.get("name"))));
        return result;
    }

    public Map<String, Object> tableStructure(Connection connection, String catalog, String schema, String table) throws SQLException {
        catalog = SqlDialect.metadataCatalog(connection, catalog);
        DatabaseMetaData md = connection.getMetaData();
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> indexes = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> foreignKeys = new ArrayList<Map<String, Object>>();
        List<String> warnings = new ArrayList<String>();
        Set<String> keys = new HashSet<String>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) if (tableScope(rs, catalog, schema, table)) keys.add(rs.getString("COLUMN_NAME"));
        } catch (SQLException ex) { if (unsupported(ex)) warnings.add("驱动不支持主键元数据"); else throw ex; }
        try (ResultSet rs = md.getColumns(catalog, SqlDialect.exactPattern(md, schema), SqlDialect.exactPattern(md, table), null)) {
            while (rs.next()) if (tableScope(rs, catalog, schema, table)) {
                int nullable = rs.getInt("NULLABLE");
                String name = rs.getString("COLUMN_NAME");
                columns.add(row("name", name, "type", rs.getString("TYPE_NAME"), "jdbcType", rs.getInt("DATA_TYPE"),
                        "size", rs.getInt("COLUMN_SIZE"), "scale", rs.getInt("DECIMAL_DIGITS"), "position", rs.getInt("ORDINAL_POSITION"),
                        "nullable", nullable == DatabaseMetaData.columnNullableUnknown ? null : nullable == DatabaseMetaData.columnNullable,
                        "defaultValue", rs.getString("COLUMN_DEF"), "remarks", rs.getString("REMARKS"), "primaryKey", keys.contains(name)));
            }
        }
        if (columns.isEmpty()) throw new AppException("未找到表字段或没有元数据访问权限，请检查 catalog、schema 和表名");
        try (ResultSet rs = md.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) if (tableScope(rs, catalog, schema, table) && rs.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic) {
                indexes.add(row("name", rs.getString("INDEX_NAME"), "column", rs.getString("COLUMN_NAME"), "unique", !rs.getBoolean("NON_UNIQUE"),
                        "position", rs.getInt("ORDINAL_POSITION"), "direction", rs.getString("ASC_OR_DESC"), "type", rs.getShort("TYPE")));
            }
        } catch (SQLException ex) { if (unsupported(ex)) warnings.add("驱动不支持索引元数据"); else throw ex; }
        try (ResultSet rs = md.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) if (scope(rs, catalog, schema, "FKTABLE_CAT", "FKTABLE_SCHEM") && table.equals(rs.getString("FKTABLE_NAME"))) {
                foreignKeys.add(row("name", rs.getString("FK_NAME"), "column", rs.getString("FKCOLUMN_NAME"), "position", rs.getInt("KEY_SEQ"),
                        "referencedCatalog", rs.getString("PKTABLE_CAT"), "referencedSchema", rs.getString("PKTABLE_SCHEM"),
                        "referencedTable", rs.getString("PKTABLE_NAME"), "referencedColumn", rs.getString("PKCOLUMN_NAME"),
                        "updateRule", rs.getShort("UPDATE_RULE"), "deleteRule", rs.getShort("DELETE_RULE")));
            }
        } catch (SQLException ex) { if (unsupported(ex)) warnings.add("驱动不支持外键元数据"); else throw ex; }
        Collections.sort(columns, Comparator.comparingInt(value -> (Integer) value.get("position")));
        return row("columns", columns, "indexes", indexes, "foreignKeys", foreignKeys, "warnings", warnings);
    }

    public Map<String, Object> routineDetail(Connection connection, String catalog, String schema, String name, String type, String specificName) throws SQLException {
        catalog = SqlDialect.metadataCatalog(connection, catalog);
        String routineType = type == null ? "PROCEDURE" : type.toUpperCase(Locale.ROOT);
        if (!"PROCEDURE".equals(routineType) && !"FUNCTION".equals(routineType)) throw new AppException("过程类型必须为 PROCEDURE 或 FUNCTION");
        DatabaseMetaData md = connection.getMetaData();
        List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
        List<String> warnings = new ArrayList<String>();
        boolean function = "FUNCTION".equals(routineType);
        Set<String> seenSpecific = new HashSet<String>();
        try (ResultSet rs = function
                ? md.getFunctionColumns(catalog, SqlDialect.exactPattern(md, schema), SqlDialect.exactPattern(md, name), null)
                : md.getProcedureColumns(catalog, SqlDialect.exactPattern(md, schema), SqlDialect.exactPattern(md, name), null)) {
            String prefix = function ? "FUNCTION" : "PROCEDURE";
            while (rs.next()) {
                if (!name.equals(rs.getString(prefix + "_NAME")) || !scope(rs, catalog, schema, prefix + "_CAT", prefix + "_SCHEM")) continue;
                String actualSpecific = optionalString(rs, "SPECIFIC_NAME");
                if (specificName != null && !specificName.isEmpty() && actualSpecific != null && !specificName.equals(actualSpecific)) continue;
                if (actualSpecific != null) seenSpecific.add(actualSpecific);
                int direction = rs.getInt("COLUMN_TYPE");
                if ((function && direction == DatabaseMetaData.functionColumnResult) || (!function && direction == DatabaseMetaData.procedureColumnResult)) continue;
                parameters.add(row("ordinalPosition", rs.getInt("ORDINAL_POSITION"), "name", rs.getString("COLUMN_NAME"),
                        "mode", parameterMode(direction, function), "jdbcType", rs.getInt("DATA_TYPE"), "typeName", rs.getString("TYPE_NAME"),
                        "specificName", actualSpecific));
            }
        } catch (SQLException ex) { if (unsupported(ex)) warnings.add("驱动不支持参数元数据，请手动补充参数"); else throw ex; }
        if (seenSpecific.size() > 1) {
            parameters.clear();
            warnings.add("存在多个重载，请选择具体 specificName 后查看参数或手工输入调用");
        }
        Collections.sort(parameters, Comparator.comparingInt(value -> "RETURN".equals(value.get("mode")) ? -1 : (Integer) value.get("ordinalPosition")));
        // JDBC metadata ordinal 0 denotes a function return. CallableStatement positions
        // are always one-based and include that return placeholder at position 1.
        for (int i = 0; i < parameters.size(); i++) parameters.get(i).put("position", i + 1);
        String definition = null;
        try { definition = routineDefinition(connection, catalog, schema, name, routineType, specificName, warnings); }
        catch (SQLException ex) { warnings.add("无法读取定义（可能未适配或权限不足）：" + com.example.dbtoolbox.common.ErrorMessages.safe(ex)); }
        if (definition == null) warnings.add("定义不可用；参数仍可查看，也可直接在编辑器编写原生调用");
        boolean returns = parameters.stream().anyMatch(p -> "RETURN".equals(p.get("mode")));
        int count = (int) parameters.stream().filter(p -> !"RETURN".equals(p.get("mode"))).count();
        String callSql = "{" + (returns ? "? = " : "") + "call " + SqlDialect.qualified(connection, catalog, schema, name) + "(" + String.join(", ", Collections.nCopies(count, "?")) + ")}";
        if (parameters.isEmpty()) warnings.add("未读取到参数；该过程可能无参数，也可能元数据不可用，请核对调用模板");
        return row("name", name, "type", routineType, "specificName", specificName, "definition", definition, "parameters", parameters, "callSql", callSql, "warnings", warnings);
    }

    private String routineDefinition(Connection connection, String catalog, String schema, String name, String type, String specificName, List<String> warnings) throws SQLException {
        String dialect = SqlDialect.detect(connection, null);
        if ("MYSQL".equals(dialect)) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                try (ResultSet rs = statement.executeQuery("SHOW CREATE " + type + " " + SqlDialect.qualified(connection, catalog, schema, name))) {
                    if (rs.next()) for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        if (rs.getMetaData().getColumnLabel(i).toUpperCase(Locale.ROOT).startsWith("CREATE ")) return rs.getString(i);
                    }
                }
            }
        } else if ("POSTGRESQL".equals(dialect) || "GAUSSDB".equals(dialect)) {
            String actualSchema = schema == null ? connection.getSchema() : schema;
            String oid = null;
            if (specificName != null && specificName.startsWith(name + "_")) {
                String suffix = specificName.substring(name.length() + 1);
                if (suffix.matches("[0-9]+")) oid = suffix;
            }
            String query = "SELECT pg_get_functiondef(p.oid) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON p.pronamespace=n.oid WHERE p.proname=? AND n.nspname=?";
            if (oid != null) query += " AND CAST(p.oid AS VARCHAR)=?";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, name);
                statement.setString(2, actualSchema);
                if (oid != null) statement.setString(3, oid);
                statement.setMaxRows(2);
                statement.setQueryTimeout(15);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        String definition = rs.getString(1);
                        if (rs.next()) { warnings.add("过程名称对应多个重载；请指定 specificName 获取准确的定义"); return null; }
                        return definition;
                    }
                }
            }
        }
        return null;
    }

    private static String parameterMode(int direction, boolean function) {
        if (function) {
            if (direction == DatabaseMetaData.functionReturn) return "RETURN";
            if (direction == DatabaseMetaData.functionColumnIn) return "IN";
            if (direction == DatabaseMetaData.functionColumnOut) return "OUT";
            if (direction == DatabaseMetaData.functionColumnInOut) return "INOUT";
        } else {
            if (direction == DatabaseMetaData.procedureColumnReturn) return "RETURN";
            if (direction == DatabaseMetaData.procedureColumnIn) return "IN";
            if (direction == DatabaseMetaData.procedureColumnOut) return "OUT";
            if (direction == DatabaseMetaData.procedureColumnInOut) return "INOUT";
        }
        return "UNKNOWN";
    }
    private static boolean tableScope(ResultSet rs, String catalog, String schema, String table) throws SQLException {
        return table.equals(rs.getString("TABLE_NAME")) && scope(rs, catalog, schema, "TABLE_CAT", "TABLE_SCHEM");
    }
    private static boolean scope(ResultSet rs, String catalog, String schema, String catalogColumn, String schemaColumn) throws SQLException {
        return optionalSame(catalog, rs.getString(catalogColumn)) && optionalSame(schema, rs.getString(schemaColumn));
    }
    private static boolean optionalSame(String expected, String actual) { return expected == null || expected.equals(actual); }
    private static String optionalString(ResultSet rs, String column) { try { return rs.getString(column); } catch (SQLException ex) { return null; } }
    private static boolean unsupported(SQLException ex) { return ex instanceof SQLFeatureNotSupportedException || "0A000".equals(ex.getSQLState()); }
    private static AppException metadataError(String action, SQLException ex) { return new AppException(action + "失败：" + com.example.dbtoolbox.common.ErrorMessages.safe(ex)); }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private static void requireName(String name) { if (name == null || name.isEmpty()) throw new AppException("对象名称不能为空"); }
    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
