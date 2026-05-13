package com.example.dbtoolbox.sync;

import com.example.dbtoolbox.backup.BackupRequest;
import com.example.dbtoolbox.backup.BackupService;
import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.CsvFormat;
import com.example.dbtoolbox.common.CsvUtils;
import com.example.dbtoolbox.common.Ids;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.common.StringChecks;
import com.example.dbtoolbox.datasource.DataSourceConfig;
import com.example.dbtoolbox.datasource.DataSourceService;
import com.example.dbtoolbox.datasource.DatabaseDialect;
import com.example.dbtoolbox.datasource.DatabaseType;
import com.example.dbtoolbox.datasource.DialectRegistry;
import com.example.dbtoolbox.datasource.JdbcConnectionFactory;
import com.example.dbtoolbox.datasource.SqlNameUtils;
import com.example.dbtoolbox.datasource.UpsertColumn;
import com.example.dbtoolbox.job.JobContext;
import com.example.dbtoolbox.job.JobRecord;
import com.example.dbtoolbox.job.JobService;
import com.example.dbtoolbox.job.JobWork;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class SyncService {

    private static final String MYSQL_RANGE_PARTITION_TEMPLATE =
            "ALTER TABLE {targetTableQuoted} ADD PARTITION (PARTITION {partitionName} VALUES LESS THAN ({lessThanValue}));";
    private static final String MYSQL_LIST_PARTITION_TEMPLATE =
            "ALTER TABLE {targetTableQuoted} ADD PARTITION (PARTITION {partitionName} VALUES IN ({lessThanValue}));";
    private static final String GAUSS_RANGE_PARTITION_TEMPLATE =
            "ALTER TABLE {targetTableQuoted} ADD PARTITION {partitionNameQuoted} VALUES LESS THAN ({lessThanValue});";
    private static final String GAUSS_LIST_PARTITION_TEMPLATE =
            "ALTER TABLE {targetTableQuoted} ADD PARTITION {partitionNameQuoted} VALUES ({lessThanValue});";

    private final SyncTaskStore store;
    private final DataSourceService dataSourceService;
    private final JdbcConnectionFactory connectionFactory;
    private final DialectRegistry dialectRegistry;
    private final JobService jobService;
    private final BackupService backupService;
    private final StoragePaths paths;

    public SyncService(SyncTaskStore store,
                       DataSourceService dataSourceService,
                       JdbcConnectionFactory connectionFactory,
                       DialectRegistry dialectRegistry,
                       JobService jobService,
                       BackupService backupService,
                       StoragePaths paths) {
        this.store = store;
        this.dataSourceService = dataSourceService;
        this.connectionFactory = connectionFactory;
        this.dialectRegistry = dialectRegistry;
        this.jobService = jobService;
        this.backupService = backupService;
        this.paths = paths;
    }

    public List<SyncTask> list() {
        return store.readAll();
    }

    public SyncTask save(SyncTaskRequest request) {
        validate(request);
        List<SyncTask> tasks = store.readAll();
        SyncTask task = null;
        if (StringChecks.hasText(request.getId())) {
            for (SyncTask existing : tasks) {
                if (existing.getId().equals(request.getId())) {
                    task = existing;
                    break;
                }
            }
        }
        Instant now = Instant.now();
        if (task == null) {
            task = new SyncTask();
            task.setId(Ids.newId());
            task.setCreatedAt(now);
            tasks.add(task);
        }
        task.setName(request.getName().trim());
        task.setSourceDatasourceId(request.getSourceDatasourceId());
        task.setTargetDatasourceId(request.getTargetDatasourceId());
        task.setSourceTable(request.getSourceTable());
        task.setTargetTable(request.getTargetTable());
        task.setWhereClause(request.getWhereClause());
        task.setFieldMappings(request.getFieldMappings());
        task.setMatchKeys(request.getMatchKeys());
        task.setFetchSize(request.getFetchSize() <= 0 ? 1000 : request.getFetchSize());
        task.setBatchSize(request.getBatchSize() <= 0 ? 1000 : request.getBatchSize());
        task.setBackupBeforeWrite(request.isBackupBeforeWrite());
        task.setPartitionRule(request.getPartitionRule());
        task.setUpdatedAt(now);
        store.writeAll(tasks);
        return task;
    }

    public void delete(String id) {
        List<SyncTask> tasks = store.readAll();
        Iterator<SyncTask> iterator = tasks.iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            if (iterator.next().getId().equals(id)) {
                iterator.remove();
                removed = true;
            }
        }
        if (!removed) {
            throw new AppException(HttpStatus.NOT_FOUND, "同步任务不存在");
        }
        store.writeAll(tasks);
    }

    public JobRecord run(final SyncRunRequest request) {
        final SyncTask task = get(request.getTaskId());
        final boolean backupBeforeWrite = request.getBackupBeforeWrite() == null
                ? task.isBackupBeforeWrite()
                : request.getBackupBeforeWrite().booleanValue();
        return jobService.submit("SYNC", "同步 " + task.getName(), new JobWork() {
            public void run(JobContext context) throws Exception {
                executeSync(task, backupBeforeWrite, context);
            }
        });
    }

    public SyncTask get(String id) {
        for (SyncTask task : store.readAll()) {
            if (task.getId().equals(id)) {
                return task;
            }
        }
        throw new AppException(HttpStatus.NOT_FOUND, "同步任务不存在");
    }

    public PartitionProbeResult probePartitions(PartitionProbeRequest request) {
        /*
         * 探查只读取“源表”的分区定义，不会连接目标库，也不会创建任何对象。
         * 前端拿到 definitions 后仍然允许用户手工修改，再保存为同步模板的一部分。
         */
        DataSourceConfig sourceConfig = dataSourceService.getConfig(request.getSourceDatasourceId());
        PartitionProbeResult result = new PartitionProbeResult();
        result.setSourceTable(request.getSourceTable());
        result.setTargetTable(request.getTargetTable());
        if (sourceConfig.getType() == DatabaseType.MYSQL) {
            PartitionProbeData probeData = probeMysqlPartitions(sourceConfig, request);
            result.setPartitionStrategy(probeData.partitionStrategy);
            result.setRecommendedSqlTemplate(templateForPartitionStrategy(sourceConfig.getType(), probeData.partitionStrategy));
            result.setDefinitions(probeData.definitions);
        } else if (sourceConfig.getType() == DatabaseType.GAUSSDB) {
            PartitionProbeData probeData = probeGaussDbPartitions(sourceConfig, request);
            result.setPartitionStrategy(probeData.partitionStrategy);
            result.setRecommendedSqlTemplate(templateForPartitionStrategy(sourceConfig.getType(), probeData.partitionStrategy));
            result.setDefinitions(probeData.definitions);
        } else {
            throw new AppException("当前数据库类型暂不支持自动探查分区: " + sourceConfig.getType());
        }
        result.setMessage("探查到 " + result.getDefinitions().size() + " 个分区");
        return result;
    }

    private void executeSync(SyncTask task, boolean backupBeforeWrite, JobContext context) throws Exception {
        validateTask(task);
        DataSourceConfig sourceConfig = dataSourceService.getConfig(task.getSourceDatasourceId());
        DataSourceConfig targetConfig = dataSourceService.getConfig(task.getTargetDatasourceId());
        DatabaseDialect sourceDialect = dialectRegistry.get(sourceConfig.getType());
        DatabaseDialect targetDialect = dialectRegistry.get(targetConfig.getType());
        if (backupBeforeWrite) {
            context.message("写入前备份目标表");
            BackupRequest backup = new BackupRequest();
            backup.setDatasourceId(task.getTargetDatasourceId());
            backup.setTableName(task.getTargetTable());
            backup.setFetchSize(task.getFetchSize());
            backupService.exportTableToZip(backup, context, "before-sync");
        }
        /*
         * 分区必须在写入前创建，否则后面的 batch upsert 可能因为目标分区不存在而整批失败。
         * 建分区失败是否中断，由 PartitionRule.ignoreCreateErrors 控制。
         */
        ensurePartitions(task, targetConfig, targetDialect, context);
        context.message("开始同步数据");
        Files.createDirectories(paths.failureDir());
        Path failureFile = paths.failureDir().resolve("sync-" + context.jobId() + "-failures.csv");
        context.failureFile(failureFile);
        CsvFormat failureFormat = new CsvFormat();
        try (Connection source = connectionFactory.open(sourceConfig);
             Connection target = connectionFactory.open(targetConfig);
             Writer failureWriter = new OutputStreamWriter(Files.newOutputStream(failureFile), failureFormat.getCharset())) {
            source.setReadOnly(true);
            source.setAutoCommit(false);
            target.setAutoCommit(false);
            String selectSql = selectSql(sourceDialect, task);
            List<UpsertColumn> targetColumns = targetColumns(task, target, targetConfig);
            String upsertSql = targetDialect.upsertSql(task.getTargetTable(), targetColumns, task.getMatchKeys());
            try (PreparedStatement select = source.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 PreparedStatement upsert = target.prepareStatement(upsertSql)) {
                select.setFetchSize(task.getFetchSize() <= 0 ? 1000 : task.getFetchSize());
                CsvUtils.writeRow(failureWriter, failureHeader(task), failureFormat);
                try (ResultSet rs = select.executeQuery()) {
                    streamRows(rs, upsert, target, task, failureWriter, failureFormat, context);
                }
            }
        }
        context.message("同步完成");
    }

    private void streamRows(ResultSet rs,
                            PreparedStatement upsert,
                            Connection target,
                            SyncTask task,
                            Writer failureWriter,
                            CsvFormat failureFormat,
                            JobContext context) throws Exception {
        int batchSize = task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();
        List<List<Object>> batchRows = new ArrayList<List<Object>>();
        while (rs.next()) {
            List<Object> values = rowValues(rs, task);
            batchRows.add(values);
            if (batchRows.size() >= batchSize) {
                flushBatch(upsert, target, batchRows, failureWriter, failureFormat, context);
                batchRows.clear();
            }
        }
        if (!batchRows.isEmpty()) {
            flushBatch(upsert, target, batchRows, failureWriter, failureFormat, context);
        }
    }

    private void flushBatch(PreparedStatement upsert,
                            Connection target,
                            List<List<Object>> rows,
                            Writer failureWriter,
                            CsvFormat failureFormat,
                            JobContext context) throws Exception {
        try {
            for (List<Object> row : rows) {
                bind(upsert, row);
                upsert.addBatch();
            }
            upsert.executeBatch();
            target.commit();
            context.addProcessed(rows.size());
            context.message("已同步 " + context.jobId() + "，最近批次 " + rows.size() + " 行");
        } catch (Exception batchEx) {
            /*
             * 批量写入失败后降级为逐行写入，目的是尽量同步可成功的数据，
             * 同时把失败行和失败原因写到 data/failures，方便事后定位脏数据。
             */
            target.rollback();
            for (List<Object> row : rows) {
                try {
                    bind(upsert, row);
                    upsert.executeUpdate();
                    target.commit();
                    context.addProcessed(1);
                } catch (Exception rowEx) {
                    target.rollback();
                    List<Object> failure = new ArrayList<Object>(row);
                    failure.add(rowEx.getMessage());
                    CsvUtils.writeRow(failureWriter, failure, failureFormat);
                    context.addFailed(1);
                }
            }
            failureWriter.flush();
        }
    }

    private void bind(PreparedStatement statement, List<Object> row) throws Exception {
        for (int i = 0; i < row.size(); i++) {
            statement.setObject(i + 1, row.get(i));
        }
    }

    private List<Object> rowValues(ResultSet rs, SyncTask task) throws Exception {
        List<Object> values = new ArrayList<Object>();
        for (int i = 0; i < task.getFieldMappings().size(); i++) {
            values.add(rs.getObject(i + 1));
        }
        return values;
    }

    private String selectSql(DatabaseDialect dialect, SyncTask task) {
        List<String> columns = new ArrayList<String>();
        for (FieldMapping mapping : task.getFieldMappings()) {
            columns.add(dialect.quoteIdentifier(mapping.getSourceColumn()));
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(join(columns))
                .append(" FROM ")
                .append(SqlNameUtils.quoteQualifiedName(dialect, task.getSourceTable()));
        if (StringChecks.hasText(task.getWhereClause())) {
            sql.append(" WHERE ").append(task.getWhereClause().trim());
        }
        return sql.toString();
    }

    private List<UpsertColumn> targetColumns(SyncTask task, Connection target, DataSourceConfig targetConfig) throws Exception {
        Map<String, String> targetTypes = targetConfig.getType() == DatabaseType.GAUSSDB
                ? gaussTargetColumnTypes(target, task.getTargetTable())
                : new LinkedHashMap<String, String>();
        List<UpsertColumn> columns = new ArrayList<UpsertColumn>();
        for (FieldMapping mapping : task.getFieldMappings()) {
            columns.add(new UpsertColumn(mapping.getTargetColumn(), targetTypes.get(mapping.getTargetColumn())));
        }
        return columns;
    }

    private Map<String, String> gaussTargetColumnTypes(Connection connection, String rawTableName) throws Exception {
        QualifiedTable table = parseQualifiedTable(rawTableName);
        Map<String, String> columns = new LinkedHashMap<String, String>();
        String sql = "SELECT a.attname AS column_name, "
                + "pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type "
                + "FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid "
                + "WHERE n.nspname = COALESCE(?, current_schema()) "
                + "AND c.relname = ? "
                + "AND a.attnum > 0 "
                + "AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.schema);
            statement.setString(2, table.table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.put(rs.getString("column_name"), rs.getString("data_type"));
                }
            }
        }
        return columns;
    }

    private QualifiedTable parseQualifiedTable(String rawTableName) {
        String value = StringChecks.requireText(rawTableName, "表名不能为空").trim();
        String[] parts = value.split("\\.");
        if (parts.length == 1) {
            return new QualifiedTable(null, unquoteIdentifier(parts[0]));
        }
        if (parts.length == 2) {
            return new QualifiedTable(unquoteIdentifier(parts[0]), unquoteIdentifier(parts[1]));
        }
        throw new AppException("非法数据库表名: " + rawTableName);
    }

    private String unquoteIdentifier(String value) {
        String trimmed = StringChecks.requireText(value, "数据库标识符不能为空").trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private List<String> failureHeader(SyncTask task) {
        List<String> headers = targetColumnNames(task);
        headers.add("failure_reason");
        return headers;
    }

    private List<String> targetColumnNames(SyncTask task) {
        List<String> columns = new ArrayList<String>();
        for (FieldMapping mapping : task.getFieldMappings()) {
            columns.add(mapping.getTargetColumn());
        }
        return columns;
    }

    private void validate(SyncTaskRequest request) {
        SyncTask task = new SyncTask();
        task.setName(request.getName());
        task.setSourceDatasourceId(request.getSourceDatasourceId());
        task.setTargetDatasourceId(request.getTargetDatasourceId());
        task.setSourceTable(request.getSourceTable());
        task.setTargetTable(request.getTargetTable());
        task.setFieldMappings(request.getFieldMappings());
        task.setMatchKeys(request.getMatchKeys());
        task.setPartitionRule(request.getPartitionRule());
        validateTask(task);
    }

    private void validateTask(SyncTask task) {
        StringChecks.requireText(task.getName(), "同步任务名称不能为空");
        StringChecks.requireText(task.getSourceDatasourceId(), "源数据源不能为空");
        StringChecks.requireText(task.getTargetDatasourceId(), "目标数据源不能为空");
        StringChecks.requireText(task.getSourceTable(), "源表不能为空");
        StringChecks.requireText(task.getTargetTable(), "目标表不能为空");
        if (task.getFieldMappings() == null || task.getFieldMappings().isEmpty()) {
            throw new AppException("字段映射不能为空");
        }
        if (task.getMatchKeys() == null || task.getMatchKeys().isEmpty()) {
            throw new AppException("匹配键不能为空");
        }
        Set<String> targetColumns = new LinkedHashSet<String>();
        for (FieldMapping mapping : task.getFieldMappings()) {
            StringChecks.requireText(mapping.getSourceColumn(), "源字段不能为空");
            String targetColumn = StringChecks.requireText(mapping.getTargetColumn(), "目标字段不能为空");
            targetColumns.add(targetColumn);
        }
        for (String key : task.getMatchKeys()) {
            if (!targetColumns.contains(key)) {
                throw new AppException("匹配键必须是目标字段映射之一: " + key);
            }
        }
        validatePartitionRule(task.getPartitionRule());
    }

    private void validatePartitionRule(PartitionRule rule) {
        if (rule == null || !rule.isEnabled()) {
            return;
        }
        if (rule.getDefinitions() == null || rule.getDefinitions().isEmpty()) {
            throw new AppException("启用分区创建时，分区定义不能为空");
        }
        if (StringChecks.hasText(rule.getPartitionStrategy()) && !isSupportedPartitionStrategy(rule.getPartitionStrategy())) {
            throw new AppException("当前只支持 RANGE/LIST 分区策略");
        }
        StringChecks.requireText(rule.getSqlTemplate(), "启用分区创建时，建分区SQL模板不能为空");
        for (PartitionDefinition definition : rule.getDefinitions()) {
            StringChecks.requireText(definition.getPartitionName(), "分区名称不能为空");
        }
    }

    private PartitionProbeData probeMysqlPartitions(DataSourceConfig sourceConfig,
                                                    PartitionProbeRequest request) {
        List<PartitionDefinition> definitions = new ArrayList<PartitionDefinition>();
        TableName sourceTable = parseTableName(request.getSourceTable());
        TableName targetTable = parseTableName(StringChecks.hasText(request.getTargetTable())
                ? request.getTargetTable()
                : request.getSourceTable());
        /*
         * MySQL 的 PARTITION_DESCRIPTION 保存 LESS THAN 后面的表达式。
         * 例如 RANGE COLUMNS 日期分区可能是 '2026-06-01'，普通 RANGE 分区可能是 202606 或 MAXVALUE。
         * LIST/LIST COLUMNS 也会把 VALUES IN 后面的值保存在 PARTITION_DESCRIPTION，复用 lessThanValue 字段承载模板变量。
         */
        String sql = "SELECT PARTITION_NAME, PARTITION_METHOD, PARTITION_DESCRIPTION "
                + "FROM information_schema.PARTITIONS "
                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) "
                + "AND TABLE_NAME = ? "
                + "AND PARTITION_NAME IS NOT NULL "
                + "ORDER BY PARTITION_ORDINAL_POSITION";
        try (Connection connection = connectionFactory.open(sourceConfig);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceTable.schema);
            statement.setString(2, sourceTable.name);
            String partitionStrategy = "RANGE";
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    partitionStrategy = strategyFromPartitionMethod(rs.getString("PARTITION_METHOD"));
                    PartitionDefinition definition = new PartitionDefinition();
                    definition.setPartitionName(transformPartitionName(rs.getString("PARTITION_NAME"), sourceTable.name, targetTable.name));
                    definition.setLessThanValue(emptyIfNull(rs.getString("PARTITION_DESCRIPTION")));
                    definitions.add(definition);
                }
            }
            return new PartitionProbeData(partitionStrategy, definitions);
        } catch (Exception ex) {
            throw new AppException("探查源表分区失败: " + ex.getMessage());
        }
    }

    private PartitionProbeData probeGaussDbPartitions(DataSourceConfig sourceConfig,
                                                      PartitionProbeRequest request) {
        List<PartitionDefinition> definitions = new ArrayList<PartitionDefinition>();
        TableName sourceTable = parseTableName(request.getSourceTable());
        TableName targetTable = parseTableName(StringChecks.hasText(request.getTargetTable())
                ? request.getTargetTable()
                : request.getSourceTable());
        /*
         * 现场库已确认不存在 Oracle ALL_/USER_ 字典视图。
         * GaussDB 分区信息走 openGauss 风格系统表 pg_catalog.pg_partition：
         * - parttype = 'p' 表示表分区。
         * - partstrategy = 'l' 表示 LIST，其余当前按 RANGE/INTERVAL 处理。
         * - boundaries 是 text[]，保存 RANGE 上边界或 LIST 枚举值。
         */
        String sql = "SELECT p.relname AS partition_name, p.partstrategy, p.boundaries::text AS boundaries "
                + "FROM pg_catalog.pg_partition p "
                + "JOIN pg_catalog.pg_class c ON p.parentid = c.oid "
                + "JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid "
                + "WHERE n.nspname = COALESCE(?, current_schema()) "
                + "AND c.relname = ? "
                + "AND p.parttype = 'p' "
                + "ORDER BY p.partitionno, p.relname";
        try (Connection connection = connectionFactory.open(sourceConfig);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceTable.schema);
            statement.setString(2, sourceTable.name);
            String partitionStrategy = "RANGE";
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    partitionStrategy = strategyFromPartitionMethod(rs.getString("partstrategy"));
                    PartitionDefinition definition = new PartitionDefinition();
                    definition.setPartitionName(transformPartitionName(rs.getString("partition_name"), sourceTable.name, targetTable.name));
                    definition.setLessThanValue(pgTextArrayToSqlExpression(rs.getString("boundaries")));
                    definitions.add(definition);
                }
            }
            return new PartitionProbeData(partitionStrategy, definitions);
        } catch (Exception ex) {
            throw new AppException("探查源表分区失败: " + ex.getMessage());
        }
    }

    private boolean isSupportedPartitionStrategy(String strategy) {
        return "RANGE".equalsIgnoreCase(strategy) || "LIST".equalsIgnoreCase(strategy);
    }

    private String strategyFromPartitionMethod(String method) {
        String value = method == null ? "" : method.trim().toUpperCase();
        if (value.startsWith("LIST") || "L".equals(value)) {
            return "LIST";
        }
        return "RANGE";
    }

    private String templateForPartitionStrategy(DatabaseType databaseType, String strategy) {
        if (databaseType == DatabaseType.GAUSSDB) {
            return "LIST".equalsIgnoreCase(strategy) ? GAUSS_LIST_PARTITION_TEMPLATE : GAUSS_RANGE_PARTITION_TEMPLATE;
        }
        return "LIST".equalsIgnoreCase(strategy) ? MYSQL_LIST_PARTITION_TEMPLATE : MYSQL_RANGE_PARTITION_TEMPLATE;
    }

    private String transformPartitionName(String sourcePartitionName, String sourceBaseTable, String targetBaseTable) {
        String partitionName = StringChecks.requireText(sourcePartitionName, "源分区名称不能为空");
        /*
         * 常见命名是 table_p202605。目标表名不同的时候，先做一次保守替换，
         * 让探查结果更接近目标表；如果现场命名更复杂，页面仍可以手工改。
         */
        if (StringChecks.hasText(sourceBaseTable) && StringChecks.hasText(targetBaseTable)
                && !sourceBaseTable.equals(targetBaseTable)
                && partitionName.contains(sourceBaseTable)) {
            return partitionName.replace(sourceBaseTable, targetBaseTable);
        }
        return partitionName;
    }

    private TableName parseTableName(String rawName) {
        String value = StringChecks.requireText(rawName, "表名不能为空");
        int dot = value.lastIndexOf('.');
        if (dot > 0 && dot < value.length() - 1) {
            return new TableName(stripIdentifierQuote(value.substring(0, dot)), stripIdentifierQuote(value.substring(dot + 1)));
        }
        return new TableName(null, stripIdentifierQuote(value));
    }

    private String stripIdentifierQuote(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.length() < 2) {
            return trimmed;
        }
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private String pgTextArrayToSqlExpression(String value) {
        if (!StringChecks.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<ArrayItem> items = parsePgArrayItems(trimmed);
        List<String> expressions = new ArrayList<String>();
        for (ArrayItem item : items) {
            if (item.quoted) {
                expressions.add("'" + item.value.replace("'", "''") + "'");
            } else {
                expressions.add(item.value);
            }
        }
        return join(expressions);
    }

    private List<ArrayItem> parsePgArrayItems(String value) {
        List<ArrayItem> items = new ArrayList<ArrayItem>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean currentQuoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
            } else if (ch == '\\' && quoted) {
                escaped = true;
            } else if (ch == '"') {
                quoted = !quoted;
                currentQuoted = true;
            } else if (ch == ',' && !quoted) {
                addArrayItem(items, current, currentQuoted);
                currentQuoted = false;
            } else {
                current.append(ch);
            }
        }
        addArrayItem(items, current, currentQuoted);
        return items;
    }

    private void addArrayItem(List<ArrayItem> items, StringBuilder value, boolean quoted) {
        String item = value.toString().trim();
        if (item.length() > 0) {
            items.add(new ArrayItem(item, quoted));
        }
        value.setLength(0);
    }

    private void ensurePartitions(SyncTask task,
                                  DataSourceConfig targetConfig,
                                  DatabaseDialect targetDialect,
                                  JobContext context) throws Exception {
        PartitionRule rule = task.getPartitionRule();
        if (rule == null || !rule.isEnabled()) {
            return;
        }
        context.message("检查并创建目标分区");
        try (Connection connection = connectionFactory.open(targetConfig);
             Statement statement = connection.createStatement()) {
            for (PartitionDefinition definition : rule.getDefinitions()) {
                // 这里执行的是用户可见、可编辑的模板渲染结果，所以新增方言时优先调整模板而不是硬编码 DDL。
                String sql = renderPartitionSql(rule, definition, targetDialect, task.getTargetTable());
                try {
                    statement.execute(sql);
                } catch (Exception ex) {
                    if (!rule.isIgnoreCreateErrors()) {
                        throw ex;
                    }
                    context.message("分区创建跳过: " + definition.getPartitionName() + "，" + ex.getMessage());
                }
            }
        }
    }

    private String renderPartitionSql(PartitionRule rule,
                                      PartitionDefinition definition,
                                      DatabaseDialect dialect,
                                      String targetTable) {
        String template = rule.getSqlTemplate();
        /*
         * 模板变量只做最小替换：
         * - 标识符变量提供 quoted/raw 两种形式。
         * - 值变量只转义单引号，不自动补引号，因为 MySQL MAXVALUE、数字、日期表达式的写法不同。
         */
        return template
                .replace("{targetTable}", targetTable)
                .replace("{targetTableQuoted}", SqlNameUtils.quoteQualifiedName(dialect, targetTable))
                .replace("{partitionName}", safeSqlName(definition.getPartitionName(), "分区名称"))
                .replace("{partitionNameQuoted}", dialect.quoteIdentifier(safeSqlName(definition.getPartitionName(), "分区名称")))
                .replace("{partitionColumn}", nullToEmpty(rule.getPartitionColumn()))
                .replace("{partitionColumnQuoted}", StringChecks.hasText(rule.getPartitionColumn()) ? dialect.quoteIdentifier(rule.getPartitionColumn().trim()) : "")
                .replace("{fromValue}", sqlLiteral(definition.getFromValue()))
                .replace("{toValue}", sqlLiteral(definition.getToValue()))
                .replace("{lessThanValue}", sqlLiteral(definition.getLessThanValue()));
    }

    private String safeSqlName(String value, String label) {
        String name = StringChecks.requireText(value, label + "不能为空");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new AppException(label + "只能包含字母、数字、下划线和$，且不能以数字开头: " + value);
        }
        return name;
    }

    private String sqlLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class TableName {
        private final String schema;
        private final String name;

        private TableName(String schema, String name) {
            this.schema = schema;
            this.name = name;
        }
    }

    private static class QualifiedTable {
        private final String schema;
        private final String table;

        private QualifiedTable(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }

    private static class PartitionProbeData {
        private final String partitionStrategy;
        private final List<PartitionDefinition> definitions;

        private PartitionProbeData(String partitionStrategy, List<PartitionDefinition> definitions) {
            this.partitionStrategy = partitionStrategy;
            this.definitions = definitions;
        }
    }

    private static class ArrayItem {
        private final String value;
        private final boolean quoted;

        private ArrayItem(String value, boolean quoted) {
            this.value = value;
            this.quoted = quoted;
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
