package com.example.dbtoolbox.metadata;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StringChecks;
import com.example.dbtoolbox.datasource.DataSourceConfig;
import com.example.dbtoolbox.datasource.DataSourceService;
import com.example.dbtoolbox.datasource.DatabaseType;
import com.example.dbtoolbox.datasource.JdbcConnectionFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class MetadataService {

    private final DataSourceService dataSourceService;
    private final JdbcConnectionFactory connectionFactory;

    public MetadataService(DataSourceService dataSourceService, JdbcConnectionFactory connectionFactory) {
        this.dataSourceService = dataSourceService;
        this.connectionFactory = connectionFactory;
    }

    public List<TableInfo> listTables(String datasourceId, String schemaPattern) {
        DataSourceConfig config = dataSourceService.getConfig(datasourceId);
        if (config.getType() == DatabaseType.GAUSSDB) {
            return listGaussTables(config, schemaPattern);
        }
        List<TableInfo> tables = new ArrayList<TableInfo>();
        try (Connection connection = connectionFactory.open(config)) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(),
                    trimToNull(schemaPattern), "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    TableInfo table = new TableInfo();
                    table.setSchemaName(rs.getString("TABLE_SCHEM"));
                    table.setTableName(rs.getString("TABLE_NAME"));
                    table.setTableType(rs.getString("TABLE_TYPE"));
                    table.setRemarks(rs.getString("REMARKS"));
                    tables.add(table);
                }
            }
        } catch (SQLException ex) {
            throw new AppException("读取表元数据失败: " + ex.getMessage());
        }
        return tables;
    }

    public List<ColumnInfo> listColumns(String datasourceId, String schemaPattern, String tableName) {
        DataSourceConfig config = dataSourceService.getConfig(datasourceId);
        String table = StringChecks.requireText(tableName, "表名不能为空");
        if (config.getType() == DatabaseType.GAUSSDB) {
            return listGaussColumns(config, schemaPattern, table);
        }
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (Connection connection = connectionFactory.open(config)) {
            DatabaseMetaData metaData = connection.getMetaData();
            /*
             * JDBC 元数据里 MySQL 把数据库名放在 catalog。
             * GaussDB 走 Oracle 兼容模式时按 schema 处理，避免和 MySQL catalog 逻辑混在一起。
             */
            String schema = trimToNull(schemaPattern);
            String catalog = metadataCatalog(config, connection, schema);
            String metadataSchema = metadataSchema(config, schema);
            Set<String> primaryKeys = primaryKeys(metaData, catalog, metadataSchema, table);
            try (ResultSet rs = metaData.getColumns(catalog, metadataSchema, table, "%")) {
                while (rs.next()) {
                    ColumnInfo column = new ColumnInfo();
                    column.setColumnName(rs.getString("COLUMN_NAME"));
                    column.setTypeName(rs.getString("TYPE_NAME"));
                    column.setDataType(rs.getInt("DATA_TYPE"));
                    column.setColumnSize(rs.getInt("COLUMN_SIZE"));
                    column.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
                    column.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    column.setPrimaryKey(primaryKeys.contains(column.getColumnName()));
                    column.setRemarks(rs.getString("REMARKS"));
                    columns.add(column);
                }
            }
        } catch (SQLException ex) {
            throw new AppException("读取字段元数据失败: " + ex.getMessage());
        }
        return columns;
    }

    private List<TableInfo> listGaussTables(DataSourceConfig config, String schemaPattern) {
        List<TableInfo> tables = new ArrayList<TableInfo>();
        String sql = "SELECT n.nspname AS schema_name, c.relname AS table_name, c.relkind AS object_type "
                + "FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE (? IS NULL OR n.nspname = ?) "
                + "AND c.relkind IN ('r', 'p', 'v') "
                + "AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
                + "ORDER BY n.nspname, c.relname";
        try (Connection connection = connectionFactory.open(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            String schema = trimToNull(schemaPattern);
            statement.setString(1, schema);
            statement.setString(2, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    TableInfo table = new TableInfo();
                    table.setSchemaName(rs.getString("schema_name"));
                    table.setTableName(rs.getString("table_name"));
                    table.setTableType("v".equals(rs.getString("object_type")) ? "VIEW" : "TABLE");
                    tables.add(table);
                }
            }
        } catch (SQLException ex) {
            throw new AppException("读取GaussDB表元数据失败: " + ex.getMessage());
        }
        return tables;
    }

    private List<ColumnInfo> listGaussColumns(DataSourceConfig config, String schemaPattern, String tableName) {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        String sql = "SELECT a.attname AS column_name, "
                + "pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type, "
                + "a.attnotnull AS not_null, "
                + "a.attnum AS column_position, "
                + "d.description AS column_comment "
                + "FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid "
                + "LEFT JOIN pg_catalog.pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum "
                + "WHERE n.nspname = COALESCE(?, current_schema()) "
                + "AND c.relname = ? "
                + "AND a.attnum > 0 "
                + "AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
        try (Connection connection = connectionFactory.open(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            String schema = trimToNull(schemaPattern);
            Set<String> primaryKeys = gaussPrimaryKeys(connection, schema, tableName);
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo column = new ColumnInfo();
                    column.setColumnName(rs.getString("column_name"));
                    column.setTypeName(rs.getString("data_type"));
                    column.setNullable(!rs.getBoolean("not_null"));
                    column.setPrimaryKey(primaryKeys.contains(column.getColumnName()));
                    column.setRemarks(rs.getString("column_comment"));
                    columns.add(column);
                }
            }
        } catch (SQLException ex) {
            throw new AppException("读取GaussDB字段元数据失败: " + ex.getMessage());
        }
        return columns;
    }

    private Set<String> gaussPrimaryKeys(Connection connection, String schema, String table) throws SQLException {
        Set<String> keys = new LinkedHashSet<String>();
        String sql = "SELECT kcu.column_name, kcu.ordinal_position "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "ON kcu.constraint_schema = tc.constraint_schema "
                + "AND kcu.constraint_name = tc.constraint_name "
                + "AND kcu.table_schema = tc.table_schema "
                + "AND kcu.table_name = tc.table_name "
                + "WHERE tc.table_schema = COALESCE(?, current_schema()) "
                + "AND tc.table_name = ? "
                + "AND tc.constraint_type = 'PRIMARY KEY' "
                + "ORDER BY kcu.ordinal_position";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("column_name"));
                }
            }
        }
        return keys;
    }

    private String metadataCatalog(DataSourceConfig config, Connection connection, String schema) throws SQLException {
        if (config.getType() == DatabaseType.MYSQL && StringChecks.hasText(schema)) {
            return schema;
        }
        return connection.getCatalog();
    }

    private String metadataSchema(DataSourceConfig config, String schema) {
        if (config.getType() == DatabaseType.MYSQL) {
            return null;
        }
        return schema;
    }

    private Set<String> primaryKeys(DatabaseMetaData metaData, String catalog, String schema, String table) throws SQLException {
        Set<String> keys = new LinkedHashSet<String>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private String trimToNull(String value) {
        return StringChecks.hasText(value) ? value.trim() : null;
    }
}
