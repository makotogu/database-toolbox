package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.workbench.dialect.SqlDialect;
import com.example.dbtoolbox.workbench.execution.ExecutionRecord.*;
import com.example.dbtoolbox.workbench.execution.SessionService.Session;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Cell edits use server-owned preview values, exact primary keys and a locked value check. */
final class CellEdits {
    static final class Table {
        String catalog, schema, name, qualified, signature;
        final List<String> names = new ArrayList<String>();
        final List<Integer> types = new ArrayList<Integer>();
        final List<Boolean> nullable = new ArrayList<Boolean>();
        final List<String> reasons = new ArrayList<String>();
        final List<Integer> keys = new ArrayList<Integer>();
    }
    static final class Snapshot {
        Table table;
        String sessionId;
        long revision;
    }
    static final class Edit {
        Table table;
        int column;
        Object before, after;
        final List<Object> keys = new ArrayList<Object>();
        String sql, predicate;
    }
    private CellEdits() { }

    static void attach(Session session, String catalog, String schema, String name, Result result) {
        try {
            Table table = inspect(session, catalog, schema, name);
            if (table.names.size() != result.columns.size()) throw new AppException("表结构已变化，请刷新预览");
            for (int i = 0; i < table.names.size(); i++) {
                Column column = result.columns.get(i);
                if (!table.names.get(i).equals(column.label) || table.types.get(i) != column.jdbcType)
                    throw new AppException("结果字段与表结构不一致，请刷新预览");
                column.readOnlyReason = table.reasons.get(i);
                column.editable = column.readOnlyReason == null;
                column.nullable = table.nullable.get(i);
            }
            Snapshot snapshot = new Snapshot();snapshot.table = table;snapshot.sessionId = session.id;snapshot.revision = session.revision;
            result.editSnapshot = snapshot;
        } catch (SQLException | AppException ex) {
            result.readOnlyReason = "只读：" + SessionService.safe(ex);
            for (Column column : result.columns) column.editable = false;
        }
    }

    private static Table inspect(Session session, String catalog, String schema, String name) throws SQLException {
        Connection connection = session.connection;
        Savepoint point = connection.getAutoCommit() ? null : connection.setSavepoint();
        try {
            Table table = inspect(connection, catalog, schema, name);
            if (point != null) connection.releaseSavepoint(point);
            return table;
        } catch (SQLException | AppException failure) {
            if (point != null) {
                try { connection.rollback(point);connection.releaseSavepoint(point); }
                catch (SQLException rollback) { session.state = "BROKEN";sessionsBroken(session);throw rollback; }
            }
            throw failure;
        }
    }
    private static Table inspect(Connection connection, String catalog, String schema, String name) throws SQLException {
        String dialect = SqlDialect.detect(connection, null);
        if (!Arrays.asList("H2", "POSTGRESQL", "GAUSSDB", "MYSQL").contains(dialect))
            throw new AppException("此数据库尚未适配单元格编辑（H2、PostgreSQL、GaussDB 兼容路径、MySQL InnoDB）");
        DatabaseMetaData md = connection.getMetaData();
        if (!md.supportsTransactions() || !md.supportsSavepoints()) throw new AppException("驱动不支持事务与保存点");
        if (connection.isReadOnly()) throw new AppException("连接为只读");
        Table table = new Table();
        table.catalog = SqlDialect.metadataCatalog(connection, empty(catalog) ? connection.getCatalog() : catalog);
        table.schema = "MYSQL".equals(dialect) ? null : empty(schema) ? connection.getSchema() : schema;
        table.name = name;
        int matches = 0;
        try (ResultSet rs = md.getTables(table.catalog, SqlDialect.exactPattern(md, table.schema), SqlDialect.exactPattern(md, name), null)) {
            while (rs.next()) if (matches(rs, table)) {
                String type = rs.getString("TABLE_TYPE");
                if (!"TABLE".equals(type) && !"BASE TABLE".equals(type)) throw new AppException("视图或非普通表不可编辑");
                matches++;
            }
        } catch (SQLException ex) { throw new AppException("读取表类型元数据失败：" + SessionService.safe(ex)); }
        if (matches != 1) throw new AppException("无法唯一识别普通表，请指定 catalog/schema");
        table.qualified = SqlDialect.qualified(connection, table.catalog, table.schema, table.name);
        if ("MYSQL".equals(dialect)) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                statement.setQueryTimeout(5);statement.setString(1, table.catalog);statement.setString(2, table.name);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next() || !"InnoDB".equalsIgnoreCase(rs.getString(1))) throw new AppException("MySQL 单元格编辑仅支持 InnoDB 表");
                }
            }
        }
        List<String> keyNames = new ArrayList<String>();
        try (ResultSet rs = md.getPrimaryKeys(table.catalog, table.schema, table.name)) {
            while (rs.next()) if (matches(rs, table)) keyNames.add(rs.getString("COLUMN_NAME"));
        } catch (SQLException ex) { throw new AppException("读取主键元数据失败：" + SessionService.safe(ex)); }
        if (keyNames.isEmpty()) throw new AppException("表没有主键，无法安全定位记录");
        StringBuilder signature = new StringBuilder(table.qualified);
        Map<String, ColumnGeneration.Flags> generation = new LinkedHashMap<String, ColumnGeneration.Flags>();
        try (ResultSet rs = md.getColumns(table.catalog, SqlDialect.exactPattern(md, table.schema), SqlDialect.exactPattern(md, table.name), null)) {
            Map<String, Integer> labels = ColumnGeneration.labels(rs);
            while (rs.next()) if (matches(rs, table)) {
                String column = rs.getString("COLUMN_NAME");int type = rs.getInt("DATA_TYPE");
                boolean key = keyNames.contains(column);
                generation.put(column, ColumnGeneration.jdbc(rs, labels));
                String reason = key ? "主键列只读" : !supported(type) ? "此字段类型暂不支持编辑" : null;
                if (key) {
                    if (!supported(type)) throw new AppException("主键类型不支持安全回写");
                    table.keys.add(table.names.size());
                }
                table.names.add(column);table.types.add(type);table.nullable.add(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);table.reasons.add(reason);
                signature.append('|').append(column).append(':').append(type).append(':').append(rs.getString("TYPE_NAME")).append(':').append(rs.getInt("COLUMN_SIZE")).append(':').append(rs.getInt("DECIMAL_DIGITS")).append(':').append(reason).append(':').append(table.nullable.get(table.nullable.size()-1));
            }
        } catch (SQLException ex) { throw new AppException("读取字段元数据失败：" + SessionService.safe(ex)); }
        if (Arrays.asList("POSTGRESQL", "GAUSSDB").contains(dialect) && generation.values().stream().anyMatch(ColumnGeneration.Flags::unknown)) {
            try { ColumnGeneration.supplement(connection, table.schema, table.name, generation); }
            catch (SQLException ex) { throw new AppException("生成列元数据兼容查询失败：" + SessionService.safe(ex)); }
        }
        for (int i = 0; i < table.names.size(); i++) {
            if (table.reasons.get(i) == null) table.reasons.set(i, generation.get(table.names.get(i)).readOnlyReason());
            signature.append('|').append(table.reasons.get(i));
        }
        if (table.keys.size() != keyNames.size()) throw new AppException("主键元数据不完整");
        table.signature = signature.toString();return table;
    }
    private static boolean matches(ResultSet rs, Table table) throws SQLException {
        return Objects.equals(table.name, rs.getString("TABLE_NAME")) && (table.catalog == null || Objects.equals(table.catalog, rs.getString("TABLE_CAT"))) && (table.schema == null || Objects.equals(table.schema, rs.getString("TABLE_SCHEM")));
    }
    private static boolean empty(String s) { return s == null || s.isEmpty(); }
    private static boolean supported(int type) {
        return Arrays.asList(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL, Types.BOOLEAN,
                Types.DATE, Types.TIME, Types.TIMESTAMP).contains(type);
    }
    static Edit prepare(Session session, Result result, ExecutionRequest.CellChange change) throws SQLException {
        Snapshot snapshot = result.editSnapshot;
        if (snapshot == null) throw new AppException(result.readOnlyReason == null ? "此结果只读；请从表内容预览中编辑" : result.readOnlyReason);
        if (!session.id.equals(snapshot.sessionId) || session.revision != snapshot.revision) throw new AppException("预览会话已变化，请刷新表内容后重试");
        Table table = snapshot.table;
        if (change.row < 0 || change.row >= result.rows.size() || change.column < 0 || change.column >= table.names.size()) throw new AppException("选中单元格不存在");
        if (table.reasons.get(change.column) != null) throw new AppException(table.reasons.get(change.column));
        List<Object> row = result.rows.get(change.row);
        for (int column : table.keys) if (row.get(column) == null || result.truncatedCells.contains(Arrays.asList(change.row, column))) throw new AppException("主键值为空或被截断，不能编辑此行");
        if (result.truncatedCells.contains(Arrays.asList(change.row, change.column))) throw new AppException("单元格内容已截断，不能编辑");
        if (change.nullValue && !table.nullable.get(change.column)) throw new AppException("此字段不允许 NULL");
        if (!change.nullValue && change.value == null) throw new AppException("请提供新值，或明确选择 NULL");
        if (change.value != null && change.value.length() > 32768) throw new AppException("单元格编辑最多支持 32768 个字符");
        Edit edit = new Edit();edit.table = table;edit.column = change.column;
        edit.before = row.get(change.column);edit.after = change.nullValue ? null : typed(change.value, table.types.get(change.column));
        if (equal(edit.before, edit.after, table.types.get(change.column))) throw new AppException("新值与原值相同");
        List<String> clauses = new ArrayList<String>();
        for (int key : table.keys) {clauses.add(SqlDialect.quote(session.connection, table.names.get(key)) + " = ?");edit.keys.add(typed(row.get(key), table.types.get(key)));}
        edit.predicate = String.join(" AND ", clauses);
        edit.sql = "UPDATE " + table.qualified + " SET " + SqlDialect.quote(session.connection, table.names.get(change.column)) + " = ? WHERE " + edit.predicate;
        return edit;
    }
    static void run(Session session, ExecutionRequest request, ExecutionRecord record, UnitResult unit, Edit edit) throws SQLException {
        Connection connection = session.connection;
        Table fresh = inspect(session, edit.table.catalog, edit.table.schema, edit.table.name);
        if (!fresh.signature.equals(edit.table.signature)) throw new AppException("表结构已变化，请刷新预览后重试");
        boolean autoCommit = connection.getAutoCommit(), completed = false, rolledBack = false;
        Savepoint savepoint = null;
        if (autoCommit) connection.setAutoCommit(false);else savepoint = connection.setSavepoint();
        try {
            String sql = "SELECT " + SqlDialect.quote(connection, edit.table.names.get(edit.column)) + " FROM " + edit.table.qualified + " WHERE " + edit.predicate + " FOR UPDATE";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                activate(statement, record, request);bindKeys(statement, edit, 1);
                try (ResultSet rs = statement.executeQuery()) {
                    Result current = ResultReader.read(rs, 2, new ResultReader.Budget());
                    if (current.rows.size() != 1 || current.truncated || !equal(current.rows.get(0).get(0), edit.before, edit.table.types.get(edit.column)))
                        throw new AppException("保存冲突：记录已删除或此单元格已被修改，请刷新表内容后重试");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(edit.sql)) {
                activate(statement, record, request);
                int type = edit.table.types.get(edit.column);
                if (edit.after == null) statement.setNull(1, type);else statement.setObject(1, edit.after, type);
                bindKeys(statement, edit, 2);
                int count = statement.executeUpdate();
                if (count != 1) throw new AppException("保存未影响恰好一行，已取消此修改，请刷新预览");
                if (statement.getWarnings() != null) throw new AppException("数据库返回警告，已取消此修改：" + SessionService.safe(statement.getWarnings()));
            }
            // Reject silent rounding/coercion and trigger rewrites instead of reporting the requested value as saved.
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                activate(statement, record, request);bindKeys(statement, edit, 1);
                try (ResultSet rs = statement.executeQuery()) {
                    Result current = ResultReader.read(rs, 2, new ResultReader.Budget());
                    if (current.rows.size() != 1 || current.truncated || !equal(current.rows.get(0).get(0), edit.after, edit.table.types.get(edit.column)))
                        throw new AppException("数据库转换或触发器改变了输入值，已取消此修改，请检查字段类型与精度");
                }
            }
            if (record.cancelRequested) throw new SQLException("保存已取消", "57014");
            if (autoCommit) connection.commit();else connection.releaseSavepoint(savepoint);
            completed = true;
            Result result = new Result();result.kind = "UPDATE_COUNT";result.updateCount = 1;unit.results.add(result);
            unit.warnings.add(autoCommit ? "单元格已保存并提交" : "单元格已保存到当前事务，请提交或回滚");
        } finally {
            record.activeStatement = null;
            if (!completed) {
                try {
                    if (autoCommit) connection.rollback();else {connection.rollback(savepoint);connection.releaseSavepoint(savepoint);}
                    rolledBack = true;
                } catch (SQLException ex) {session.state = "BROKEN";sessionsBroken(session);throw ex;}
            }
            // Never restore auto-commit after a failed rollback: doing so could commit the failed edit.
            if (autoCommit && (completed || rolledBack)) {
                try {connection.setAutoCommit(true);}catch (SQLException ex) {session.state = "BROKEN";sessionsBroken(session);throw ex;}
            }
        }
    }
    private static void sessionsBroken(Session session) {try {session.connection.close();}catch (SQLException ignored) { } }
    private static void activate(PreparedStatement statement, ExecutionRecord record, ExecutionRequest request) throws SQLException {
        record.activeStatement = statement;
        if (record.cancelRequested) throw new SQLException("保存已取消", "57014");
        statement.setQueryTimeout(request.timeoutSeconds);
    }
    private static void bindKeys(PreparedStatement statement, Edit edit, int offset) throws SQLException {
        for (int i = 0; i < edit.keys.size(); i++) statement.setObject(offset + i, edit.keys.get(i), edit.table.types.get(edit.table.keys.get(i)));
    }
    private static boolean equal(Object a, Object b, int type) {
        return Objects.equals(typed(a, type), typed(b, type));
    }
    private static Object typed(Object value, int type) {
        if (value == null) return null;
        String text = String.valueOf(value);
        try {
            switch (type) {
                case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
                    return new BigDecimal(text).toBigIntegerExact();
                case Types.NUMERIC: case Types.DECIMAL: return new BigDecimal(text).stripTrailingZeros();
                case Types.BOOLEAN:
                    if ("true".equalsIgnoreCase(text)) return true;
                    if ("false".equalsIgnoreCase(text)) return false;
                    throw new IllegalArgumentException();
                case Types.DATE: return java.sql.Date.valueOf(java.time.LocalDate.parse(text));
                case Types.TIME:
                    java.time.LocalTime time=java.time.LocalTime.parse(text);
                    if(time.getNano()!=0)throw new IllegalArgumentException();
                    return java.sql.Time.valueOf(time);
                case Types.TIMESTAMP: return java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(text.replace(' ', 'T')));
                default: return text;
            }
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException ex) {throw new AppException("新值或原值不符合字段类型，请检查格式");}
    }
}
