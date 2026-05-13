package com.example.dbtoolbox.backup;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.CsvFormat;
import com.example.dbtoolbox.common.CsvUtils;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.common.StringChecks;
import com.example.dbtoolbox.datasource.DataSourceConfig;
import com.example.dbtoolbox.datasource.DataSourceService;
import com.example.dbtoolbox.datasource.DatabaseDialect;
import com.example.dbtoolbox.datasource.DialectRegistry;
import com.example.dbtoolbox.datasource.JdbcConnectionFactory;
import com.example.dbtoolbox.datasource.SqlNameUtils;
import com.example.dbtoolbox.job.JobContext;
import com.example.dbtoolbox.job.JobRecord;
import com.example.dbtoolbox.job.JobService;
import com.example.dbtoolbox.job.JobWork;
import com.example.dbtoolbox.metadata.ColumnInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Service
public class BackupService {

    private final DataSourceService dataSourceService;
    private final JdbcConnectionFactory connectionFactory;
    private final DialectRegistry dialectRegistry;
    private final StoragePaths paths;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public BackupService(DataSourceService dataSourceService,
                         JdbcConnectionFactory connectionFactory,
                         DialectRegistry dialectRegistry,
                         StoragePaths paths,
                         JobService jobService,
                         ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
        this.connectionFactory = connectionFactory;
        this.dialectRegistry = dialectRegistry;
        this.paths = paths;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    public JobRecord exportAsync(final BackupRequest request) {
        return jobService.submit("BACKUP_EXPORT", "导出 " + request.getTableName(), new JobWork() {
            public void run(JobContext context) throws Exception {
                exportTableToZip(request, context, "backup");
            }
        });
    }

    public JobRecord restoreAsync(final RestoreRequest request) {
        return jobService.submit("BACKUP_RESTORE", "恢复 " + request.getBackupFile(), new JobWork() {
            public void run(JobContext context) throws Exception {
                restoreFromZip(request, context);
            }
        });
    }

    public Path exportTableToZip(BackupRequest request, JobContext context, String prefix) throws Exception {
        DataSourceConfig config = dataSourceService.getConfig(request.getDatasourceId());
        DatabaseDialect dialect = dialectRegistry.get(config.getType());
        CsvFormat format = CsvFormat.of(request.getDelimiter(), request.getQuoteChar(), request.getEscapeChar(),
                request.getLineSeparator(), request.getCharset());
        Files.createDirectories(paths.backupDir());
        Path file = paths.backupDir().resolve(prefix + "-" + context.jobId() + ".zip");
        context.message("正在导出数据");
        try (Connection connection = connectionFactory.open(config);
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            writeJsonEntry(zip, "manifest.json", manifest(request, format));
            BackupMetadata metadata = new BackupMetadata();
            metadata.setTableName(request.getTableName());
            metadata.setDatabaseType(config.getType());
            metadata.setExportedAt(Instant.now());
            metadata.setColumns(readColumns(connection, request.getTableName()));
            writeJsonEntry(zip, "metadata.json", metadata);
            writeDataEntry(zip, connection, dialect, request, format, context);
        }
        context.artifact(file);
        context.message("导出完成");
        return file;
    }

    public ResponseEntity<Resource> download(String fileName) {
        Path file = paths.backupDir().resolve(fileName).normalize();
        if (!file.startsWith(paths.backupDir()) || !Files.exists(file)) {
            throw new AppException("备份文件不存在");
        }
        Resource resource = new FileSystemResource(file.toFile());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName().toString() + "\"")
                .body(resource);
    }

    private void restoreFromZip(RestoreRequest request, JobContext context) throws Exception {
        DataSourceConfig config = dataSourceService.getConfig(request.getDatasourceId());
        DatabaseDialect dialect = dialectRegistry.get(config.getType());
        Path file = resolveBackupFile(request.getBackupFile());
        context.message("正在读取备份包");
        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            BackupMetadata metadata = readMetadata(zipFile);
            Map<String, Object> manifest = readManifest(zipFile);
            CsvFormat format = restoreFormat(request, manifest);
            String targetTable = StringChecks.hasText(request.getTargetTableName())
                    ? request.getTargetTableName().trim()
                    : metadata.getTableName();
            ZipEntry dataEntry = zipFile.getEntry("data.csv");
            if (dataEntry == null) {
                throw new AppException("备份包缺少 data.csv");
            }
            try (InputStream input = zipFile.getInputStream(dataEntry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, format.getCharset()));
                 Connection connection = connectionFactory.open(config)) {
                connection.setAutoCommit(false);
                restoreRows(reader, connection, dialect, targetTable, format, request.getBatchSize(), context);
            }
        }
        context.message("恢复完成");
    }

    private void restoreRows(BufferedReader reader,
                             Connection connection,
                             DatabaseDialect dialect,
                             String targetTable,
                             CsvFormat format,
                             int batchSize,
                             JobContext context) throws Exception {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new AppException("data.csv 为空");
        }
        List<String> columns = CsvUtils.parseLine(headerLine, format);
        String sql = insertSql(dialect, targetTable, columns);
        int effectiveBatchSize = batchSize <= 0 ? 1000 : batchSize;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            String line;
            int batch = 0;
            while ((line = reader.readLine()) != null) {
                List<String> values = CsvUtils.parseLine(line, format);
                for (int i = 0; i < columns.size(); i++) {
                    ps.setObject(i + 1, i < values.size() ? values.get(i) : null);
                }
                ps.addBatch();
                batch++;
                if (batch >= effectiveBatchSize) {
                    ps.executeBatch();
                    connection.commit();
                    context.addProcessed(batch);
                    batch = 0;
                }
            }
            if (batch > 0) {
                ps.executeBatch();
                connection.commit();
                context.addProcessed(batch);
            }
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        }
    }

    private void writeDataEntry(ZipOutputStream zip,
                                Connection connection,
                                DatabaseDialect dialect,
                                BackupRequest request,
                                CsvFormat format,
                                JobContext context) throws Exception {
        zip.putNextEntry(new ZipEntry("data.csv"));
        Writer writer = new OutputStreamWriter(zip, format.getCharset());
        String sql = selectSql(connection, dialect, request);
        int fetchSize = request.getFetchSize() <= 0 ? 1000 : request.getFetchSize();
        try (PreparedStatement ps = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(fetchSize);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                List<String> headers = new ArrayList<String>();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    headers.add(metaData.getColumnName(i));
                }
                CsvUtils.writeRow(writer, headers, format);
                long count = 0;
                while (rs.next()) {
                    List<Object> values = new ArrayList<Object>();
                    for (int i = 1; i <= headers.size(); i++) {
                        values.add(rs.getObject(i));
                    }
                    CsvUtils.writeRow(writer, values, format);
                    count++;
                    if (count % 1000 == 0) {
                        context.processed(count);
                        context.message("已导出 " + count + " 行");
                    }
                }
                context.processed(count);
            }
        }
        writer.flush();
        zip.closeEntry();
    }

    private String selectSql(Connection connection, DatabaseDialect dialect, BackupRequest request) throws Exception {
        List<ColumnInfo> columns = readColumns(connection, request.getTableName());
        List<String> quoted = new ArrayList<String>();
        for (ColumnInfo column : columns) {
            quoted.add(dialect.quoteIdentifier(column.getColumnName()));
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(join(quoted))
                .append(" FROM ")
                .append(SqlNameUtils.quoteQualifiedName(dialect, request.getTableName()));
        if (StringChecks.hasText(request.getWhereClause())) {
            sql.append(" WHERE ").append(request.getWhereClause().trim());
        }
        return sql.toString();
    }

    private List<ColumnInfo> readColumns(Connection connection, String tableName) throws Exception {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        DatabaseMetaData metaData = connection.getMetaData();
        String lookupTable = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, lookupTable, "%")) {
            while (rs.next()) {
                ColumnInfo column = new ColumnInfo();
                column.setColumnName(rs.getString("COLUMN_NAME"));
                column.setTypeName(rs.getString("TYPE_NAME"));
                column.setDataType(rs.getInt("DATA_TYPE"));
                column.setColumnSize(rs.getInt("COLUMN_SIZE"));
                column.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
                column.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                columns.add(column);
            }
        }
        if (columns.isEmpty()) {
            throw new AppException("未读取到表字段: " + tableName);
        }
        return columns;
    }

    private void writeJsonEntry(ZipOutputStream zip, String name, Object value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        zip.write(json);
        zip.closeEntry();
    }

    private Map<String, Object> manifest(BackupRequest request, CsvFormat format) {
        Map<String, Object> manifest = new LinkedHashMap<String, Object>();
        manifest.put("version", 1);
        manifest.put("tableName", request.getTableName());
        manifest.put("whereClause", request.getWhereClause());
        manifest.put("delimiter", String.valueOf(format.getDelimiter()));
        manifest.put("delimiterCode", (int) format.getDelimiter());
        manifest.put("quoteChar", String.valueOf(format.getQuoteChar()));
        manifest.put("quoteCharCode", (int) format.getQuoteChar());
        manifest.put("escapeChar", String.valueOf(format.getEscapeChar()));
        manifest.put("escapeCharCode", (int) format.getEscapeChar());
        manifest.put("lineSeparator", format.getLineSeparator());
        manifest.put("charset", format.getCharset().name());
        manifest.put("createdAt", Instant.now().toString());
        return manifest;
    }

    private CsvFormat restoreFormat(RestoreRequest request, Map<String, Object> manifest) {
        return CsvFormat.of(firstText(request.getDelimiter(), manifest.get("delimiter"), "CHAR(27)"),
                firstText(request.getQuoteChar(), manifest.get("quoteChar"), "\""),
                firstText(request.getEscapeChar(), manifest.get("escapeChar"), "\""),
                firstText(request.getLineSeparator(), manifest.get("lineSeparator"), "\\n"),
                firstText(request.getCharset(), manifest.get("charset"), "UTF-8"));
    }

    private String firstText(String requestValue, Object manifestValue, String defaultValue) {
        if (requestValue != null && requestValue.length() > 0) {
            return requestValue;
        }
        if (manifestValue != null && String.valueOf(manifestValue).length() > 0) {
            return String.valueOf(manifestValue);
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readManifest(ZipFile zipFile) throws Exception {
        ZipEntry entry = zipFile.getEntry("manifest.json");
        if (entry == null) {
            return new LinkedHashMap<String, Object>();
        }
        try (InputStream input = zipFile.getInputStream(entry)) {
            return objectMapper.readValue(input, Map.class);
        }
    }

    private BackupMetadata readMetadata(ZipFile zipFile) throws Exception {
        ZipEntry entry = zipFile.getEntry("metadata.json");
        if (entry == null) {
            throw new AppException("备份包缺少 metadata.json");
        }
        try (InputStream input = zipFile.getInputStream(entry)) {
            return objectMapper.readValue(input, BackupMetadata.class);
        }
    }

    private Path resolveBackupFile(String backupFile) {
        Path path = paths.backupDir().resolve(backupFile).normalize();
        if (backupFile.startsWith("/") || backupFile.indexOf(':') >= 0) {
            path = java.nio.file.Paths.get(backupFile).normalize();
        }
        if (!Files.exists(path)) {
            throw new AppException("备份文件不存在: " + backupFile);
        }
        return path;
    }

    private String insertSql(DatabaseDialect dialect, String targetTable, List<String> columns) {
        List<String> quoted = new ArrayList<String>();
        List<String> placeholders = new ArrayList<String>();
        for (String column : columns) {
            quoted.add(dialect.quoteIdentifier(column));
            placeholders.add("?");
        }
        return "INSERT INTO " + SqlNameUtils.quoteQualifiedName(dialect, targetTable)
                + " (" + join(quoted) + ") VALUES (" + join(placeholders) + ")";
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
