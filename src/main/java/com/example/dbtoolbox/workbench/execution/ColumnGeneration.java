package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import java.sql.*;
import java.util.*;

/** Optional JDBC flags are tri-state: missing information never means a writable column. */
final class ColumnGeneration {
    static final class Flags {
        Boolean generated, identity;
        boolean unknown() { return generated == null || identity == null; }
        String readOnlyReason() {
            if (Boolean.TRUE.equals(generated) || Boolean.TRUE.equals(identity)) return "生成列或自增列只读";
            return unknown() ? "驱动元数据不完整，无法确认生成列或自增属性，此字段只读" : null;
        }
    }
    private ColumnGeneration() { }

    static Map<String, Integer> labels(ResultSet rs) throws SQLException {
        Map<String, Integer> labels = new HashMap<String, Integer>();
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) labels.put(md.getColumnLabel(i).toUpperCase(Locale.ROOT), i);
        return labels;
    }
    static Flags jdbc(ResultSet rs, Map<String, Integer> labels) throws SQLException {
        Flags flags = new Flags();
        flags.generated = yesNo(optional(rs, labels, "IS_GENERATEDCOLUMN"));
        flags.identity = yesNo(optional(rs, labels, "IS_AUTOINCREMENT"));
        return flags;
    }
    private static Boolean yesNo(String value) {
        return "YES".equalsIgnoreCase(value) ? Boolean.TRUE : "NO".equalsIgnoreCase(value) ? Boolean.FALSE : null;
    }
    private static String optional(ResultSet rs, Map<String, Integer> labels, String name) throws SQLException {
        Integer index = labels.get(name);
        return index == null ? null : rs.getString(index);
    }
    private static boolean zero(String value) { return "".equals(value) || "\0".equals(value); }

    /** Query existing catalog columns with *, avoiding version guesses and undefined-column probes. */
    static void supplement(Connection connection, String schema, String table, Map<String, Flags> columns) throws SQLException {
        String sql = "SELECT a.*, d.* FROM pg_catalog.pg_attribute a " +
            "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid " +
            "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
            "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum " +
            "WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum";
        Set<String> seen = new HashSet<String>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);statement.setString(1, schema);statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                Map<String, Integer> labels = labels(rs);
                while (rs.next()) {
                    String name = rs.getString("attname");
                    if (!seen.add(name)) throw new AppException("生成列系统目录返回重复字段，无法安全编辑");
                    Flags flags = columns.get(name);
                    if (flags == null) continue;
                    boolean hasDefault = rs.getBoolean("atthasdef"), knownDefault = !rs.wasNull();
                    boolean defaultRow = rs.getObject("adnum") != null;
                    Boolean generated = null;
                    if (labels.containsKey("ATTGENERATED")) {
                        String value = optional(rs, labels, "ATTGENERATED");
                        if (value != null) generated = !zero(value);
                    } else if (knownDefault && !hasDefault && !defaultRow) {
                        // PG/Gauss generated expressions require a pg_attrdef row and atthasdef.
                        generated = false;
                    } else if (defaultRow && labels.containsKey("ADGENCOL")) {
                        String value = optional(rs, labels, "ADGENCOL");
                        if (value != null) generated = !zero(value);
                    }
                    if (flags.generated == null || Boolean.TRUE.equals(generated)) flags.generated = generated;
                    String identity = optional(rs, labels, "ATTIDENTITY");
                    if (identity != null && !zero(identity)) flags.identity = true;
                    else if (flags.identity == null && identity != null && knownDefault && !hasDefault && !defaultRow)
                        flags.identity = false;
                    // A default may use a sequence. Do not infer identity=false from attidentity alone.
                }
            }
        }
    }
}
