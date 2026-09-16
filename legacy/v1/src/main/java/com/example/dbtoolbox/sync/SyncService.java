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
import com.example.dbtoolbox.datasource.GaussDbDialect;
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
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        task.setCopyBatchSize(normalizeCopyBatchSize(request.getCopyBatchSize()));
        task.setWriteConcurrency(normalizeWriteConcurrency(request.getWriteConcurrency()));
        task.setWriteMode(normalizeWriteMode(request.getWriteMode()));
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
        context.checkCancelled();
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
            /*
             * exportTableToZip 会复用当前 JobContext 更新 processedRows。对同步任务来说，
             * 页面上的"成功处理行"应该只表示源查询成功写入/更新的行数，不应叠加写入前备份行数。
             */
            context.processed(0);
        }
        context.checkCancelled();
        /*
         * 分区必须在写入前创建，否则后面的 batch upsert 可能因为目标分区不存在而整批失败。
         * 建分区失败是否中断，由 PartitionRule.ignoreCreateErrors 控制。
         */
        PartitionEnsureResult partitionResult = ensurePartitions(task, targetConfig, targetDialect, context);
        context.checkCancelled();
        context.message("开始同步数据" + partitionResult.messageSuffix());
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
            List<FieldMapping> activeMappings = activeMappings(task);
            String selectSql = selectSql(sourceDialect, task, activeMappings);
            PreparedTarget prepared = prepareTarget(task, activeMappings, target, targetConfig);
            boolean overwrite = overwriteMode(task);
            boolean copyFastPath = targetDialect.type() == DatabaseType.GAUSSDB && copyManager(target) != null;
            boolean setBasedBatch = !overwrite && supportsSetBasedBatch(targetDialect, task.getTargetTable(), prepared);
            int writeConcurrency = normalizeWriteConcurrency(task.getWriteConcurrency());
            if (overwrite) {
                truncateTarget(target, targetDialect, task.getTargetTable(), context);
            }
            try (PreparedStatement select = source.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                select.setFetchSize(task.getFetchSize() <= 0 ? 1000 : task.getFetchSize());
                CsvUtils.writeRow(failureWriter, failureHeader(activeMappings), failureFormat);
                context.checkCancelled();
                try (ResultSet rs = select.executeQuery()) {
                    if (overwrite) {
                        if (writeConcurrency > 1) {
                            streamRowsOverwriteParallel(rs, targetConfig, targetDialect, task, activeMappings, prepared,
                                    failureWriter, failureFormat, context, copyFastPath, writeConcurrency);
                        } else {
                            streamRowsOverwrite(rs, target, targetDialect, task, activeMappings, prepared,
                                    failureWriter, failureFormat, context, copyFastPath);
                        }
                    } else if (writeConcurrency > 1) {
                        streamRowsParallel(rs, targetConfig, targetDialect, task, activeMappings, prepared,
                                failureWriter, failureFormat, context, setBasedBatch, copyFastPath, writeConcurrency);
                    } else if (setBasedBatch) {
                        streamRowsSetBased(rs, target, targetDialect, task, activeMappings, prepared,
                                failureWriter, failureFormat, context, copyFastPath);
                    } else {
                        String upsertSql = targetDialect.upsertSql(task.getTargetTable(), prepared.columns, prepared.matchKeys);
                        try (PreparedStatement upsert = target.prepareStatement(upsertSql)) {
                            streamRows(rs, upsert, target, activeMappings, task, failureWriter, failureFormat, context);
                        }
                    }
                }
                if (context.failedRows() == 0
                        && context.processedRows() > 0
                        && !targetHasAnyRows(targetConfig, targetDialect, task.getTargetTable())) {
                    throw new AppException("同步执行已报告成功处理 " + context.processedRows()
                            + " 行，但目标表 " + task.getTargetTable()
                            + " 查询不到任何数据。请检查 MERGE 语句、目标分区、触发器或权限配置。");
                }
            }
        }
        String summary = syncSummary(context, failureFile, partitionResult);
        if (context.failedRows() > 0) {
            /*
             * 逐行降级可能已经提交了部分成功行。只要有失败行，就把任务标成失败，
             * 让看板显式提醒用户查看失败文件，避免"状态成功但目标库没有预期结果"。
             */
            throw new AppException(summary);
        }
        context.message(summary);
    }

    private boolean supportsSetBasedBatch(DatabaseDialect dialect, String targetTable, PreparedTarget prepared) {
        return dialect.batchUpsertSql(targetTable, prepared.columns, prepared.matchKeys, 2) != null;
    }

    private int normalizeWriteConcurrency(int value) {
        if (value <= 0) {
            return 2;
        }
        return Math.min(8, Math.max(1, value));
    }

    private int normalizeCopyBatchSize(int value) {
        if (value <= 0) {
            return 50000;
        }
        return Math.min(500000, Math.max(1000, value));
    }

    private String normalizeWriteMode(String value) {
        if (!StringChecks.hasText(value)) {
            return "UPSERT";
        }
        String normalized = value.trim().toUpperCase();
        if ("OVERWRITE".equals(normalized)) {
            return "OVERWRITE";
        }
        return "UPSERT";
    }

    private boolean overwriteMode(SyncTask task) {
        return "OVERWRITE".equals(normalizeWriteMode(task.getWriteMode()));
    }

    private void streamRows(ResultSet rs,
                            PreparedStatement upsert,
                            Connection target,
                            List<FieldMapping> activeMappings,
                            SyncTask task,
                            Writer failureWriter,
                            CsvFormat failureFormat,
                            JobContext context) throws Exception {
        int batchSize = task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();
        List<List<Object>> batchRows = new ArrayList<List<Object>>();
        while (rs.next()) {
            context.checkCancelled();
            List<Object> values = rowValues(rs, activeMappings);
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

    private void streamRowsSetBased(ResultSet rs,
                                    Connection target,
                                    DatabaseDialect targetDialect,
                                    SyncTask task,
                                    List<FieldMapping> activeMappings,
                                    PreparedTarget prepared,
                                    Writer failureWriter,
                                    CsvFormat failureFormat,
                                    JobContext context,
                                    boolean copyFastPath) throws Exception {
        int batchSize = copyFastPath ? effectiveCopyBatchSize(task) : effectiveSetBasedBatchSize(task, activeMappings.size());
        List<List<Object>> batchRows = new ArrayList<List<Object>>();
        context.message("开始集合式批量同步，批大小 " + batchSize);
        while (rs.next()) {
            context.checkCancelled();
            List<Object> values = rowValues(rs, activeMappings);
            batchRows.add(values);
            if (batchRows.size() >= batchSize) {
                flushSetBasedBatch(target, targetDialect, task, prepared, batchRows, failureWriter, failureFormat, context);
                batchRows.clear();
            }
        }
        if (!batchRows.isEmpty()) {
            flushSetBasedBatch(target, targetDialect, task, prepared, batchRows, failureWriter, failureFormat, context);
        }
    }

    private void streamRowsOverwrite(ResultSet rs,
                                     Connection target,
                                     DatabaseDialect targetDialect,
                                     SyncTask task,
                                     List<FieldMapping> activeMappings,
                                     PreparedTarget prepared,
                                     Writer failureWriter,
                                     CsvFormat failureFormat,
                                     JobContext context,
                                     boolean copyFastPath) throws Exception {
        int batchSize = copyFastPath ? effectiveCopyBatchSize(task) : (task.getBatchSize() <= 0 ? 1000 : task.getBatchSize());
        List<List<Object>> batchRows = new ArrayList<List<Object>>();
        context.message("开始覆盖写入，批大小 " + batchSize);
        while (rs.next()) {
            context.checkCancelled();
            batchRows.add(rowValues(rs, activeMappings));
            if (batchRows.size() >= batchSize) {
                flushOverwriteBatch(target, targetDialect, task, prepared, batchRows,
                        failureWriter, failureFormat, context, copyFastPath);
                batchRows.clear();
            }
        }
        if (!batchRows.isEmpty()) {
            flushOverwriteBatch(target, targetDialect, task, prepared, batchRows,
                    failureWriter, failureFormat, context, copyFastPath);
        }
    }

    private void streamRowsOverwriteParallel(ResultSet rs,
                                             DataSourceConfig targetConfig,
                                             DatabaseDialect targetDialect,
                                             SyncTask task,
                                             List<FieldMapping> activeMappings,
                                             PreparedTarget prepared,
                                             Writer failureWriter,
                                             CsvFormat failureFormat,
                                             JobContext context,
                                             boolean copyFastPath,
                                             int writeConcurrency) throws Exception {
        int batchSize = copyFastPath ? effectiveCopyBatchSize(task) : (task.getBatchSize() <= 0 ? 1000 : task.getBatchSize());
        int maxInflight = Math.max(writeConcurrency, writeConcurrency * 2);
        ExecutorService executor = Executors.newFixedThreadPool(writeConcurrency);
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<Void>(executor);
        int pending = 0;
        List<List<Object>> batchRows = new ArrayList<List<Object>>();
        context.message("开始并发覆盖写入，写入并发 " + writeConcurrency + "，批大小 " + batchSize);
        try {
            while (rs.next()) {
                context.checkCancelled();
                batchRows.add(rowValues(rs, activeMappings));
                if (batchRows.size() >= batchSize) {
                    submitOverwriteBatch(completion, targetConfig, targetDialect, task, prepared,
                            batchRows, failureWriter, failureFormat, context, copyFastPath);
                    pending++;
                    batchRows = new ArrayList<List<Object>>();
                    if (pending >= maxInflight) {
                        waitParallelBatch(completion);
                        pending--;
                    }
                }
            }
            if (!batchRows.isEmpty()) {
                submitOverwriteBatch(completion, targetConfig, targetDialect, task, prepared,
                        batchRows, failureWriter, failureFormat, context, copyFastPath);
                pending++;
            }
            while (pending > 0) {
                context.checkCancelled();
                waitParallelBatch(completion);
                pending--;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void submitOverwriteBatch(ExecutorCompletionService<Void> completion,
                                      final DataSourceConfig targetConfig,
                                      final DatabaseDialect targetDialect,
                                      final SyncTask task,
                                      final PreparedTarget prepared,
                                      final List<List<Object>> rows,
                                      final Writer failureWriter,
                                      final CsvFormat failureFormat,
                                      final JobContext context,
                                      final boolean copyFastPath) {
        completion.submit(new Callable<Void>() {
            public Void call() throws Exception {
                context.checkCancelled();
                try (Connection target = connectionFactory.open(targetConfig)) {
                    target.setAutoCommit(false);
                    flushOverwriteBatch(target, targetDialect, task, prepared,
                            rows, failureWriter, failureFormat, context, copyFastPath);
                }
                return null;
            }
        });
    }

    private void streamRowsParallel(ResultSet rs,
                                    DataSourceConfig targetConfig,
                                    DatabaseDialect targetDialect,
                                    SyncTask task,
                                    List<FieldMapping> activeMappings,
                                    PreparedTarget prepared,
                                    Writer failureWriter,
                                    CsvFormat failureFormat,
                                    JobContext context,
                                    boolean setBasedBatch,
                                    boolean copyFastPath,
                                    int writeConcurrency) throws Exception {
        int batchSize = copyFastPath
                ? effectiveCopyBatchSize(task)
                : setBasedBatch
                ? effectiveSetBasedBatchSize(task, activeMappings.size())
                : (task.getBatchSize() <= 0 ? 1000 : task.getBatchSize());
        int maxInflight = Math.max(writeConcurrency, writeConcurrency * 2);
        ExecutorService executor = Executors.newFixedThreadPool(writeConcurrency);
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<Void>(executor);
        int pending = 0;
        List<List<Object>> batchRows = new ArrayList<List<Object>>();
        context.message("开始并发同步，写入并发 " + writeConcurrency + "，批大小 " + batchSize);
        try {
            while (rs.next()) {
                context.checkCancelled();
                batchRows.add(rowValues(rs, activeMappings));
                if (batchRows.size() >= batchSize) {
                    submitParallelBatch(completion, targetConfig, targetDialect, task, prepared,
                            batchRows, failureWriter, failureFormat, context, setBasedBatch);
                    pending++;
                    batchRows = new ArrayList<List<Object>>();
                    if (pending >= maxInflight) {
                        waitParallelBatch(completion);
                        pending--;
                    }
                }
            }
            if (!batchRows.isEmpty()) {
                submitParallelBatch(completion, targetConfig, targetDialect, task, prepared,
                        batchRows, failureWriter, failureFormat, context, setBasedBatch);
                pending++;
            }
            while (pending > 0) {
                context.checkCancelled();
                waitParallelBatch(completion);
                pending--;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void submitParallelBatch(ExecutorCompletionService<Void> completion,
                                     final DataSourceConfig targetConfig,
                                     final DatabaseDialect targetDialect,
                                     final SyncTask task,
                                     final PreparedTarget prepared,
                                     final List<List<Object>> rows,
                                     final Writer failureWriter,
                                     final CsvFormat failureFormat,
                                     final JobContext context,
                                     final boolean setBasedBatch) {
        completion.submit(new Callable<Void>() {
            public Void call() throws Exception {
                context.checkCancelled();
                try (Connection target = connectionFactory.open(targetConfig)) {
                    target.setAutoCommit(false);
                    if (setBasedBatch) {
                        flushSetBasedBatch(target, targetDialect, task, prepared,
                                rows, failureWriter, failureFormat, context);
                    } else {
                        flushRowWiseBatch(target, targetDialect, task, prepared,
                                rows, failureWriter, failureFormat, context);
                    }
                }
                return null;
            }
        });
    }

    private void waitParallelBatch(ExecutorCompletionService<Void> completion) throws Exception {
        try {
            Future<Void> future = completion.take();
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AppException(cause == null ? "并发同步失败" : String.valueOf(cause.getMessage()));
        }
    }

    private int effectiveSetBasedBatchSize(SyncTask task, int columnCount) {
        int configured = task.getBatchSize() <= 0 ? 1000 : task.getBatchSize();
        /*
         * 多行 MERGE 会把一批数据展开成一条 SQL，参数数量 = 行数 * 字段数。
         * 给 GaussDB 保守控制在约 1 万个参数以内，避免超大 SQL 反而拖慢解析或触发驱动限制。
         */
        int safeColumnCount = Math.max(1, columnCount);
        int parameterCapRows = Math.max(50, 10000 / safeColumnCount);
        return Math.max(1, Math.min(configured, parameterCapRows));
    }

    private int effectiveCopyBatchSize(SyncTask task) {
        return normalizeCopyBatchSize(task.getCopyBatchSize());
    }

    private void flushOverwriteBatch(Connection target,
                                     DatabaseDialect targetDialect,
                                     SyncTask task,
                                     PreparedTarget prepared,
                                     List<List<Object>> rows,
                                     Writer failureWriter,
                                     CsvFormat failureFormat,
                                     JobContext context,
                                     boolean copyFastPath) throws Exception {
        if (copyFastPath && flushGaussCopyDirectBatch(target, targetDialect, task, prepared, rows, context)) {
            return;
        }
        context.checkCancelled();
        String sql = insertSql(targetDialect, task.getTargetTable(), prepared.columns);
        try (PreparedStatement insert = target.prepareStatement(sql)) {
            flushBatch(insert, target, rows, failureWriter, failureFormat, context);
        }
    }

    private boolean flushGaussCopyDirectBatch(Connection target,
                                             DatabaseDialect targetDialect,
                                             SyncTask task,
                                             PreparedTarget prepared,
                                             List<List<Object>> rows,
                                             JobContext context) throws Exception {
        Object copyManager = copyManager(target);
        if (copyManager == null) {
            return false;
        }
        long startNanos = System.nanoTime();
        try {
            copyRows(copyManager,
                    copySql(targetDialect, SqlNameUtils.quoteQualifiedName(targetDialect, task.getTargetTable()), prepared.columns),
                    rows,
                    context);
            target.commit();
            context.addProcessed(rows.size());
            context.message(batchProgressMessage(context, rows.size(), startNanos, "COPY覆盖写入"));
            return true;
        } catch (Exception ex) {
            target.rollback();
            return false;
        }
    }

    private void flushSetBasedBatch(Connection target,
                                    DatabaseDialect targetDialect,
                                    SyncTask task,
                                    PreparedTarget prepared,
                                    List<List<Object>> rows,
                                    Writer failureWriter,
                                    CsvFormat failureFormat,
                                    JobContext context) throws Exception {
        if (hasDuplicateMatchKeys(rows, prepared)) {
            flushRowWiseBatch(target, targetDialect, task, prepared, rows, failureWriter, failureFormat, context);
            return;
        }
        context.checkCancelled();
        if (targetDialect.type() == DatabaseType.GAUSSDB) {
            if (flushGaussCopyMergeBatch(target, targetDialect, task, prepared, rows, context)) {
                return;
            }
            /*
             * COPY 可用性是在连接/驱动层判断的，COPY SQL 或现场临时表语法仍可能失败。
             * 此时不能把 copyBatchSize 规模的行数直接展开成一条巨大 MERGE，必须切回安全批大小。
             */
            flushSetBasedFallbackChunks(target, targetDialect, task, prepared, rows,
                    failureWriter, failureFormat, context);
            return;
        }
        String sql = targetDialect.batchUpsertSql(task.getTargetTable(), prepared.columns, prepared.matchKeys, rows.size());
        long startNanos = System.nanoTime();
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            bindRows(statement, rows);
            statement.executeUpdate();
            target.commit();
            context.addProcessed(rows.size());
            context.message(batchProgressMessage(context, rows.size(), startNanos, "集合式批量同步"));
        } catch (Exception ex) {
            /*
             * 多行 MERGE 对重复匹配键、现场方言差异更敏感。失败时退回旧的单行 batch，
             * 保证正确性优先；真正失败的行仍会进入 failure csv。
             */
            target.rollback();
            flushRowWiseBatch(target, targetDialect, task, prepared, rows, failureWriter, failureFormat, context);
        }
    }

    private void flushSetBasedFallbackChunks(Connection target,
                                             DatabaseDialect targetDialect,
                                             SyncTask task,
                                             PreparedTarget prepared,
                                             List<List<Object>> rows,
                                             Writer failureWriter,
                                             CsvFormat failureFormat,
                                             JobContext context) throws Exception {
        int chunkSize = effectiveSetBasedBatchSize(task, prepared.columns.size());
        for (int start = 0; start < rows.size(); start += chunkSize) {
            context.checkCancelled();
            int end = Math.min(rows.size(), start + chunkSize);
            List<List<Object>> chunk = new ArrayList<List<Object>>(rows.subList(start, end));
            String sql = targetDialect.batchUpsertSql(task.getTargetTable(), prepared.columns, prepared.matchKeys, chunk.size());
            long startNanos = System.nanoTime();
            try (PreparedStatement statement = target.prepareStatement(sql)) {
                bindRows(statement, chunk);
                statement.executeUpdate();
                target.commit();
                context.addProcessed(chunk.size());
                context.message(batchProgressMessage(context, chunk.size(), startNanos, "集合式批量同步"));
            } catch (Exception ex) {
                target.rollback();
                flushRowWiseBatch(target, targetDialect, task, prepared, chunk, failureWriter, failureFormat, context);
            }
        }
    }

    private boolean flushGaussCopyMergeBatch(Connection target,
                                             DatabaseDialect targetDialect,
                                             SyncTask task,
                                             PreparedTarget prepared,
                                             List<List<Object>> rows,
                                             JobContext context) throws Exception {
        Object copyManager = copyManager(target);
        if (copyManager == null) {
            return false;
        }
        String tempTableName = "tmp_sync_" + Long.toHexString(System.nanoTime());
        String tempTableQuoted = targetDialect.quoteIdentifier(tempTableName);
        long startNanos = System.nanoTime();
        try (Statement statement = target.createStatement()) {
            context.checkCancelled();
            statement.execute(createTempTableSql(targetDialect, task.getTargetTable(), tempTableQuoted, prepared.columns));
            copyRows(copyManager, copySql(targetDialect, tempTableQuoted, prepared.columns), rows, context);
            context.checkCancelled();
            String mergeSql = ((GaussDbDialect) targetDialect).mergeFromTableSql(
                    task.getTargetTable(),
                    stagingSourceSql(targetDialect, tempTableQuoted, prepared.columns),
                    prepared.columns,
                    prepared.matchKeys);
            statement.executeUpdate(mergeSql);
            target.commit();
            context.addProcessed(rows.size());
            context.message(batchProgressMessage(context, rows.size(), startNanos, "COPY临时表同步"));
            return true;
        } catch (Exception ex) {
            target.rollback();
            return false;
        } finally {
            dropTableQuietly(target, tempTableQuoted);
        }
    }

    private Object copyManager(Connection connection) {
        String[] connectionTypes = new String[] {
                "org.postgresql.PGConnection",
                "org.opengauss.PGConnection"
        };
        for (String connectionType : connectionTypes) {
            try {
                Class<?> pgConnectionClass = Class.forName(connectionType);
                Object pgConnection = connection.unwrap(pgConnectionClass);
                Method getCopyApi = pgConnectionClass.getMethod("getCopyAPI");
                return getCopyApi.invoke(pgConnection);
            } catch (Exception ignored) {
                // 驱动不暴露 PGConnection/CopyManager 时继续尝试下一个包名，最后回退普通 MERGE。
            }
        }
        return null;
    }

    private String createTempTableSql(DatabaseDialect dialect,
                                      String targetTable,
                                      String tempTableQuoted,
                                      List<UpsertColumn> columns) {
        List<String> sourceColumns = new ArrayList<String>();
        for (UpsertColumn column : columns) {
            String quoted = dialect.quoteIdentifier(column.getName());
            sourceColumns.add("target." + quoted + " AS " + quoted);
        }
        return "CREATE TEMP TABLE " + tempTableQuoted
                + " AS SELECT " + join(sourceColumns)
                + " FROM " + SqlNameUtils.quoteQualifiedName(dialect, targetTable)
                + " target WHERE 1 = 0";
    }

    private String stagingSourceSql(DatabaseDialect dialect,
                                    String tempTableQuoted,
                                    List<UpsertColumn> columns) {
        List<String> sourceColumns = new ArrayList<String>();
        for (UpsertColumn column : columns) {
            sourceColumns.add(dialect.quoteIdentifier(column.getName()));
        }
        return "SELECT " + join(sourceColumns) + " FROM " + tempTableQuoted;
    }

    private String copySql(DatabaseDialect dialect, String tempTableQuoted, List<UpsertColumn> columns) {
        List<String> columnNames = new ArrayList<String>();
        for (UpsertColumn column : columns) {
            columnNames.add(dialect.quoteIdentifier(column.getName()));
        }
        return "COPY " + tempTableQuoted
                + " (" + join(columnNames) + ") FROM STDIN WITH (FORMAT csv, NULL '\\N')";
    }

    private String insertSql(DatabaseDialect dialect, String targetTable, List<UpsertColumn> columns) {
        List<String> columnNames = new ArrayList<String>();
        List<String> placeholders = new ArrayList<String>();
        for (UpsertColumn column : columns) {
            columnNames.add(dialect.quoteIdentifier(column.getName()));
            placeholders.add("?");
        }
        return "INSERT INTO " + SqlNameUtils.quoteQualifiedName(dialect, targetTable)
                + " (" + join(columnNames) + ") VALUES (" + join(placeholders) + ")";
    }

    private void truncateTarget(Connection target,
                                DatabaseDialect dialect,
                                String targetTable,
                                JobContext context) throws Exception {
        String sql = "TRUNCATE TABLE " + SqlNameUtils.quoteQualifiedName(dialect, targetTable);
        try (Statement statement = target.createStatement()) {
            context.checkCancelled();
            statement.execute(sql);
            target.commit();
            context.message("目标表已清空，开始覆盖写入");
        } catch (Exception ex) {
            target.rollback();
            throw ex;
        }
    }

    private void copyRows(Object copyManager, String sql, List<List<Object>> rows, JobContext context) throws Exception {
        StringWriter writer = new StringWriter();
        CsvFormat copyFormat = new CsvFormat();
        for (List<Object> row : rows) {
            context.checkCancelled();
            CsvUtils.writeRow(writer, row, copyFormat);
        }
        Method copyIn = copyManager.getClass().getMethod("copyIn", String.class, Reader.class);
        try {
            context.checkCancelled();
            copyIn.invoke(copyManager, sql, new StringReader(writer.toString()));
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AppException(cause == null ? "COPY 写入失败" : String.valueOf(cause.getMessage()));
        }
    }

    private void dropTableQuietly(Connection connection, String tableNameSql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + tableNameSql);
        } catch (Exception ignored) {
            // 临时表随连接关闭也会清理，这里只做尽力释放。
        }
    }

    private void flushRowWiseBatch(Connection target,
                                   DatabaseDialect targetDialect,
                                   SyncTask task,
                                   PreparedTarget prepared,
                                   List<List<Object>> rows,
                                   Writer failureWriter,
                                   CsvFormat failureFormat,
                                   JobContext context) throws Exception {
        String sql = targetDialect.upsertSql(task.getTargetTable(), prepared.columns, prepared.matchKeys);
        try (PreparedStatement upsert = target.prepareStatement(sql)) {
            flushBatch(upsert, target, rows, failureWriter, failureFormat, context);
        }
    }

    private void flushBatch(PreparedStatement upsert,
                            Connection target,
                            List<List<Object>> rows,
                            Writer failureWriter,
                            CsvFormat failureFormat,
                            JobContext context) throws Exception {
        long startNanos = System.nanoTime();
        try {
            for (List<Object> row : rows) {
                context.checkCancelled();
                bind(upsert, row);
                upsert.addBatch();
            }
            upsert.executeBatch();
            target.commit();
            context.addProcessed(rows.size());
            context.message(batchProgressMessage(context, rows.size(), startNanos, "批量同步"));
        } catch (Exception batchEx) {
            /*
             * 批量写入失败后降级为逐行写入，目的是尽量同步可成功的数据，
             * 同时把失败行和失败原因写到 data/failures，方便事后定位脏数据。
             * 注意：executeBatch 抛异常后，部分驱动仍然保留剩余未执行的 batch 条目，
             * 必须显式 clearBatch，否则下一批 addBatch 会和上一批失败的行混在一起重发。
             */
            target.rollback();
            try {
                upsert.clearBatch();
            } catch (Exception ignored) {
                // 驱动不支持 clearBatch 时退化为信任 rollback。
            }
            for (List<Object> row : rows) {
                context.checkCancelled();
                try {
                    bind(upsert, row);
                    upsert.executeUpdate();
                    target.commit();
                    context.addProcessed(1);
                } catch (Exception rowEx) {
                    target.rollback();
                    List<Object> failure = new ArrayList<Object>(row);
                    failure.add(rowEx.getMessage());
                    synchronized (failureWriter) {
                        CsvUtils.writeRow(failureWriter, failure, failureFormat);
                    }
                    context.addFailed(1);
                }
            }
            synchronized (failureWriter) {
                failureWriter.flush();
            }
        }
    }

    private String batchProgressMessage(JobContext context, int batchRows, long startNanos, String mode) {
        long elapsedMillis = Math.max(1L, (System.nanoTime() - startNanos) / 1000000L);
        long rowsPerSecond = batchRows * 1000L / elapsedMillis;
        return mode + "已处理 " + context.processedRows()
                + " 行，最近批次 " + batchRows
                + " 行，耗时 " + elapsedMillis
                + "ms，约 " + rowsPerSecond + " 行/秒";
    }

    private void bindRows(PreparedStatement statement, List<List<Object>> rows) throws Exception {
        int parameterIndex = 1;
        for (List<Object> row : rows) {
            for (Object value : row) {
                statement.setObject(parameterIndex++, value);
            }
        }
    }

    private boolean hasDuplicateMatchKeys(List<List<Object>> rows, PreparedTarget prepared) {
        List<Integer> indexes = matchKeyIndexes(prepared);
        if (indexes.isEmpty()) {
            return false;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (List<Object> row : rows) {
            String key = rowKey(row, indexes);
            if (key == null || !seen.add(key)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> matchKeyIndexes(PreparedTarget prepared) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (String key : prepared.matchKeys) {
            for (int i = 0; i < prepared.columns.size(); i++) {
                if (prepared.columns.get(i).getName().equals(key)) {
                    indexes.add(Integer.valueOf(i));
                    break;
                }
            }
        }
        return indexes;
    }

    private String rowKey(List<Object> row, List<Integer> indexes) {
        StringBuilder builder = new StringBuilder();
        for (Integer index : indexes) {
            Object value = row.get(index.intValue());
            if (value == null) {
                return null;
            }
            if (builder.length() > 0) {
                builder.append('\u001f');
            }
            builder.append(value.getClass().getName()).append(':').append(value);
        }
        return builder.toString();
    }

    private String syncSummary(JobContext context, Path failureFile, PartitionEnsureResult partitionResult) {
        StringBuilder message = new StringBuilder("同步完成，成功处理 ")
                .append(context.processedRows())
                .append(" 行");
        if (context.failedRows() > 0) {
            message.append("，失败 ").append(context.failedRows()).append(" 行");
            if (failureFile != null) {
                message.append("，失败明细: ")
                        .append(failureFile.toAbsolutePath().normalize());
            }
        }
        String suffix = partitionResult.messageSuffix();
        if (suffix.length() > 0) {
            message.append(suffix);
        }
        return message.toString();
    }

    private boolean targetHasAnyRows(DataSourceConfig targetConfig, DatabaseDialect dialect, String targetTable) throws Exception {
        /*
         * 这是同步后的快速兜底校验，不做 count(*)，避免超大表上额外全表计数。
         * 只要写入链路声称处理过数据，目标表至少应该能查到 1 行。
         */
        String sql = dialect.limitSql("SELECT 1 AS exists_flag FROM "
                + SqlNameUtils.quoteQualifiedName(dialect, targetTable), 1);
        try (Connection target = connectionFactory.open(targetConfig);
             PreparedStatement statement = target.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }

    private void bind(PreparedStatement statement, List<Object> row) throws Exception {
        for (int i = 0; i < row.size(); i++) {
            statement.setObject(i + 1, row.get(i));
        }
    }

    private List<FieldMapping> activeMappings(SyncTask task) {
        /*
         * 字段映射现在带 enabled 开关：禁用行仍然保存到模板，但执行同步时跳过。
         * 所有 SELECT 列、目标列、参数绑定都必须基于"启用行"，否则会出现"读 5 列写 4 列"的错位。
         */
        List<FieldMapping> active = new ArrayList<FieldMapping>();
        for (FieldMapping mapping : task.getFieldMappings()) {
            if (mapping.isEnabled()) {
                active.add(mapping);
            }
        }
        return active;
    }

    private List<Object> rowValues(ResultSet rs, List<FieldMapping> activeMappings) throws Exception {
        List<Object> values = new ArrayList<Object>();
        for (int i = 0; i < activeMappings.size(); i++) {
            values.add(rs.getObject(i + 1));
        }
        return values;
    }

    private String selectSql(DatabaseDialect dialect, SyncTask task, List<FieldMapping> activeMappings) {
        List<String> columns = new ArrayList<String>();
        for (FieldMapping mapping : activeMappings) {
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

    private PreparedTarget prepareTarget(SyncTask task,
                                         List<FieldMapping> activeMappings,
                                         Connection target,
                                         DataSourceConfig targetConfig) throws Exception {
        if (targetConfig.getType() != DatabaseType.GAUSSDB) {
            /*
             * MySQL 路径维持原状：上游用户写的列名直接交给 dialect，
             * 大小写折叠由 MySQL 服务端按平台规则处理（Linux 默认大小写敏感）。
             */
            List<UpsertColumn> columns = new ArrayList<UpsertColumn>();
            for (FieldMapping mapping : activeMappings) {
                columns.add(new UpsertColumn(mapping.getTargetColumn(), null));
            }
            return new PreparedTarget(columns, new ArrayList<String>(task.getMatchKeys()));
        }
        Map<String, GaussTargetColumn> catalog = gaussTargetColumnTypes(target, task.getTargetTable());
        List<UpsertColumn> columns = new ArrayList<UpsertColumn>();
        List<String> missing = new ArrayList<String>();
        for (FieldMapping mapping : activeMappings) {
            String userColumn = mapping.getTargetColumn();
            GaussTargetColumn descriptor = catalog.get(userColumn);
            if (descriptor == null) {
                /*
                 * GaussDB MERGE INTO 子查询里 ? 会被推断为 text，导致 timestamptz/numeric 等列
                 * 报"不能从 text 转换到目标类型"。这里宁可让任务在启动阶段直接失败，也不要让
                 * 类型推断退化到原 bug 的静默回退路径，因此目标列必须能在 pg_catalog 里查到。
                 */
                missing.add(userColumn);
                continue;
            }
            /*
             * 用 pg_catalog 真实 attname 而不是用户在模板里填的字面值。前者保证 quoteIdentifier
             * 出来的 "列名" 能在目标表上命中；前一版只把类型查对了，列名还是用户原文，会出现
             * "类型对、引号里的列不存在" 的奇怪错误。
             */
            columns.add(new UpsertColumn(descriptor.actualName, descriptor.typeName));
        }
        if (!missing.isEmpty()) {
            throw new AppException("无法在目标库读取以下列的类型: " + String.join(", ", missing)
                    + "。请确认目标表 " + task.getTargetTable()
                    + " 在当前 schema 下存在，且列名大小写与 pg_catalog 中一致。");
        }
        List<String> matchKeys = new ArrayList<String>();
        for (String key : task.getMatchKeys()) {
            GaussTargetColumn descriptor = catalog.get(key);
            // matchKey 已在 validateTask 里保证一定能命中启用的目标字段；这里只做大小写归一化。
            matchKeys.add(descriptor != null ? descriptor.actualName : key);
        }
        return new PreparedTarget(columns, matchKeys);
    }

    private Map<String, GaussTargetColumn> gaussTargetColumnTypes(Connection connection, String rawTableName) throws Exception {
        QualifiedTable table = parseQualifiedTable(rawTableName);
        /*
         * 用大小写不敏感的 TreeMap 做查表，避免用户在前端写 "Updated_At" 而 pg_catalog 实际是
         * updated_at 时 lookup 落空。Value 同时保存 pg_catalog 里的实际 attname，让上层 SQL
         * 拼接时改用真实大小写，避免 quote 出一个目标表上不存在的列名。
         */
        Map<String, GaussTargetColumn> columns = new TreeMap<String, GaussTargetColumn>(String.CASE_INSENSITIVE_ORDER);
        String sql = "SELECT a.attname AS column_name, "
                + "pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type "
                + "FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid "
                + "WHERE lower(n.nspname) = lower(COALESCE(?, current_schema())) "
                + "AND lower(c.relname) = lower(?) "
                + "AND a.attnum > 0 "
                + "AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.schema);
            statement.setString(2, table.table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String actualName = rs.getString("column_name");
                    String typeName = rs.getString("data_type");
                    columns.put(actualName, new GaussTargetColumn(actualName, typeName));
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
        /*
         * 当前不支持 schema 或 table 本身包含点号，例如 "schema.with.dot"."tbl"。
         * 把限制直接写进报错信息，避免现场拿到一句"非法数据库表名"无法判断要怎么改。
         */
        throw new AppException("表名格式不支持: " + rawTableName
                + "。schema 和 table 都不能包含点号；当前只支持 table 或 schema.table 形式。");
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

    private List<String> failureHeader(List<FieldMapping> activeMappings) {
        List<String> headers = new ArrayList<String>();
        for (FieldMapping mapping : activeMappings) {
            headers.add(mapping.getTargetColumn());
        }
        headers.add("failure_reason");
        return headers;
    }

    private void validate(SyncTaskRequest request) {
        SyncTask task = new SyncTask();
        task.setName(request.getName());
        task.setSourceDatasourceId(request.getSourceDatasourceId());
        task.setTargetDatasourceId(request.getTargetDatasourceId());
        task.setSourceTable(request.getSourceTable());
        task.setTargetTable(request.getTargetTable());
        task.setWhereClause(request.getWhereClause());
        task.setFieldMappings(request.getFieldMappings());
        task.setMatchKeys(request.getMatchKeys());
        task.setWriteMode(normalizeWriteMode(request.getWriteMode()));
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
        boolean overwrite = overwriteMode(task);
        if (!overwrite && (task.getMatchKeys() == null || task.getMatchKeys().isEmpty())) {
            throw new AppException("匹配键不能为空");
        }
        /*
         * 字段映射的"启用"开关：保存阶段允许有禁用行（用户保留备用），
         * 但启用行至少要有一条且 source/target 都必须非空，否则 selectSql 会拼出空 SELECT 列表。
         * 匹配键也必须出现在启用行的目标字段里，否则同步会按禁用列做 ON 条件，必然落空。
         */
        Set<String> activeTargetColumns = new LinkedHashSet<String>();
        int activeCount = 0;
        for (FieldMapping mapping : task.getFieldMappings()) {
            if (!mapping.isEnabled()) {
                continue;
            }
            activeCount++;
            StringChecks.requireText(mapping.getSourceColumn(), "源字段不能为空");
            String targetColumn = StringChecks.requireText(mapping.getTargetColumn(), "目标字段不能为空");
            activeTargetColumns.add(targetColumn);
        }
        if (activeCount == 0) {
            throw new AppException("至少需要启用一条字段映射");
        }
        if (!overwrite) {
            for (String key : task.getMatchKeys()) {
                if (!activeTargetColumns.contains(key)) {
                    throw new AppException("匹配键必须是启用的目标字段映射之一: " + key);
                }
            }
        }
        validateWhereClause(task.getWhereClause());
        validateNotSelfSync(task);
        validatePartitionRule(task.getPartitionRule());
    }

    private void validateWhereClause(String whereClause) {
        if (!StringChecks.hasText(whereClause)) {
            return;
        }
        /*
         * whereClause 会原样拼接到 SELECT 之后，不做参数化。这里禁止常见的多语句/注释片段，
         * 把"在 WHERE 里挂另一段 SQL"的风险拦在保存阶段。仍然不能完全替代参数化，但是足以
         * 把意外/手抖触发的多语句注入挡住，配合本工具仅 127.0.0.1 的部署是合理的折中。
         */
        String value = whereClause.trim();
        if (value.contains(";")) {
            throw new AppException("源表 WHERE 条件不允许包含分号");
        }
        if (value.contains("--") || value.contains("/*") || value.contains("*/")) {
            throw new AppException("源表 WHERE 条件不允许包含 SQL 注释片段（--、/*、*/）");
        }
        long singleQuotes = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\'') {
                singleQuotes++;
            }
        }
        if ((singleQuotes & 1L) == 1L) {
            throw new AppException("源表 WHERE 条件中的单引号必须成对出现");
        }
    }

    private void validateNotSelfSync(SyncTask task) {
        if (!task.getSourceDatasourceId().equals(task.getTargetDatasourceId())) {
            return;
        }
        /*
         * 同库同表同步会读到刚刚 upsert 进去的数据，且在 autoCommit=false 的写事务下
         * 容易和流式读发生锁等待/死锁。明确拒绝，避免现场出现一个"看起来在跑但永远不结束"的任务。
         * 比较时去掉引号、按小写归一，避免 "user.Order" vs user.order 这种判错。
         */
        String source = normalizeQualifiedName(task.getSourceTable());
        String target = normalizeQualifiedName(task.getTargetTable());
        if (source.equals(target)) {
            throw new AppException("同步源和目标指向同一数据源的同一张表，会读写自身导致死锁，已拒绝。");
        }
    }

    private String normalizeQualifiedName(String rawName) {
        if (!StringChecks.hasText(rawName)) {
            return "";
        }
        String[] parts = rawName.trim().split("\\.");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('.');
            }
            builder.append(unquoteIdentifier(parts[i]).toLowerCase());
        }
        return builder.toString();
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

    private PartitionEnsureResult ensurePartitions(SyncTask task,
                                                   DataSourceConfig targetConfig,
                                                   DatabaseDialect targetDialect,
                                                   JobContext context) throws Exception {
        PartitionEnsureResult result = new PartitionEnsureResult();
        PartitionRule rule = task.getPartitionRule();
        if (rule == null || !rule.isEnabled()) {
            return result;
        }
        context.message("检查并创建目标分区");
        try (Connection connection = connectionFactory.open(targetConfig);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (PartitionDefinition definition : rule.getDefinitions()) {
                result.attempted++;
                if (partitionExists(connection, targetConfig, task.getTargetTable(), definition.getPartitionName())) {
                    result.existingSkipped++;
                    context.message("分区已存在，跳过: " + definition.getPartitionName());
                    continue;
                }
                // 这里执行的是用户可见、可编辑的模板渲染结果，所以新增方言时优先调整模板而不是硬编码 DDL。
                String sql = renderPartitionSql(rule, definition, targetDialect, task.getTargetTable());
                try {
                    statement.execute(sql);
                    connection.commit();
                    result.created++;
                } catch (Exception ex) {
                    rollbackQuietly(connection);
                    if (!rule.isIgnoreCreateErrors()) {
                        throw ex;
                    }
                    if (isPartitionAlreadyExists(ex)) {
                        result.existingSkipped++;
                        context.message("分区已存在，跳过: " + definition.getPartitionName());
                    } else {
                        result.ignoredFailures++;
                        result.lastIgnoredFailure = compactError(ex);
                        context.message("分区创建失败但已按配置忽略: "
                                + definition.getPartitionName() + "，" + result.lastIgnoredFailure);
                    }
                }
            }
        }
        return result;
    }

    private boolean partitionExists(Connection connection,
                                    DataSourceConfig targetConfig,
                                    String targetTable,
                                    String partitionName) throws Exception {
        /*
         * 建分区是同步前的幂等准备动作：分区存在时直接跳过，避免用 DDL 报错来驱动正常流程。
         * 异常捕获里的 already-exists 判断仍保留，处理并发创建或现场字典查询差异。
         */
        if (targetConfig.getType() == DatabaseType.GAUSSDB) {
            return gaussPartitionExists(connection, targetTable, partitionName);
        }
        return mysqlPartitionExists(connection, targetConfig, targetTable, partitionName);
    }

    private boolean mysqlPartitionExists(Connection connection,
                                         DataSourceConfig targetConfig,
                                         String targetTable,
                                         String partitionName) throws Exception {
        TableName table = parseTableName(targetTable);
        String schema = StringChecks.hasText(table.schema) ? table.schema : targetConfig.getDatabaseName();
        String sql = "SELECT 1 FROM information_schema.partitions "
                + "WHERE table_schema = COALESCE(?, DATABASE()) "
                + "AND table_name = ? "
                + "AND partition_name = ? "
                + "LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table.name);
            statement.setString(3, partitionName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean gaussPartitionExists(Connection connection,
                                         String targetTable,
                                         String partitionName) throws Exception {
        TableName table = parseTableName(targetTable);
        String sql = "SELECT 1 "
                + "FROM pg_catalog.pg_partition p "
                + "JOIN pg_catalog.pg_class c ON p.parentid = c.oid "
                + "JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid "
                + "WHERE lower(n.nspname) = lower(COALESCE(?, current_schema())) "
                + "AND lower(c.relname) = lower(?) "
                + "AND lower(p.relname) = lower(?) "
                + "AND p.parttype = 'p' "
                + "AND ROWNUM <= 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.schema);
            statement.setString(2, table.name);
            statement.setString(3, partitionName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // 分区 DDL 失败后尽力清理事务状态；失败本身会通过原异常继续向外暴露或记录。
        }
    }

    private boolean isPartitionAlreadyExists(Exception ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException) current;
                String state = sqlException.getSQLState();
                if ("42P07".equals(state) || "42710".equals(state)) {
                    return true;
                }
                int code = sqlException.getErrorCode();
                if (code == 1050 || code == 1517) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("already exists")
                        || lower.contains("duplicate partition")
                        || lower.contains("duplicate object")
                        || lower.contains("same name")
                        || message.contains("已存在")
                        || message.contains("已经存在")
                        || message.contains("重复")
                        || message.contains("同名")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String compactError(Exception ex) {
        String message = ex.getMessage();
        if (!StringChecks.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String compact = message.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 240 ? compact.substring(0, 240) + "..." : compact;
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

    private static class GaussTargetColumn {
        private final String actualName;
        private final String typeName;

        private GaussTargetColumn(String actualName, String typeName) {
            this.actualName = actualName;
            this.typeName = typeName;
        }
    }

    private static class PreparedTarget {
        private final List<UpsertColumn> columns;
        private final List<String> matchKeys;

        private PreparedTarget(List<UpsertColumn> columns, List<String> matchKeys) {
            this.columns = columns;
            this.matchKeys = matchKeys;
        }
    }

    private static class PartitionEnsureResult {
        private int attempted;
        private int created;
        private int existingSkipped;
        private int ignoredFailures;
        private String lastIgnoredFailure;

        private String messageSuffix() {
            if (attempted <= 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder("；分区检查: 尝试 ")
                    .append(attempted)
                    .append(" 个，创建 ")
                    .append(created)
                    .append(" 个，已存在跳过 ")
                    .append(existingSkipped)
                    .append(" 个");
            if (ignoredFailures > 0) {
                builder.append("，忽略失败 ")
                        .append(ignoredFailures)
                        .append(" 个");
                if (lastIgnoredFailure != null) {
                    builder.append("，最后错误: ").append(lastIgnoredFailure);
                }
            }
            return builder.toString();
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
