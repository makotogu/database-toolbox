package com.example.dbtoolbox.workbench.connection;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.EncryptedJsonFileStore;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.workbench.driver.DriverProfile;
import com.example.dbtoolbox.workbench.driver.DriverService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConnectionService {
    public static final String SAVED_VALUE = "<saved>";
    private static final Pattern URL_PROPERTY = Pattern.compile("([?;&][^=;?&]+)=([^;&]*)");
    private final StoragePaths paths;
    private final ObjectMapper mapper;
    private final DriverService drivers;
    private final Path file;
    private final EncryptedJsonFileStore<ConnectionCatalog> store;

    public ConnectionService(StoragePaths paths, ObjectMapper mapper, DriverService drivers) {
        this.paths = paths;
        this.mapper = mapper;
        this.drivers = drivers;
        this.file = paths.configDir().resolve("connections-v2.enc");
        this.store = new EncryptedJsonFileStore<ConnectionCatalog>(file, paths.keyFile(), mapper,
                new TypeReference<ConnectionCatalog>() { }, ConnectionCatalog::new);
        drivers.setReferenceChecker(id -> {
            // Do not acquire this service's monitor while DriverService owns its monitor.
            for (ConnectionProfile profile : readCatalog().profiles) if (id.equals(profile.driverId)) return true;
            return false;
        });
    }

    public synchronized List<ConnectionProfile> list() {
        ensureMigrated();
        List<ConnectionProfile> result = new ArrayList<ConnectionProfile>();
        for (ConnectionProfile profile : readCatalog().profiles) result.add(sanitized(profile));
        return result;
    }

    /** Internal API only. Controllers must never serialize this result. */
    public synchronized ConnectionProfile get(String id) {
        ensureMigrated();
        for (ConnectionProfile profile : readCatalog().profiles) if (profile.id.equals(id)) return profile;
        throw new AppException(HttpStatus.NOT_FOUND, "连接配置不存在");
    }

    public Connection open(String id) { return openConfig(get(id)); }

    public synchronized ConnectionProfile save(ConnectionProfile request) {
        if (request == null) throw new AppException("缺少连接配置");
        ensureMigrated();
        // Driver deletion must not slip between validating the reference and persisting it.
        synchronized (drivers) {
            ConnectionCatalog catalog = readCatalog();
            ConnectionProfile existing = existing(catalog, request.id);
            ConnectionProfile next = prepare(request, existing, true);
            if (existing != null) catalog.profiles.remove(existing);
            catalog.profiles.add(next);
            store.write(catalog);
            return sanitized(next);
        }
    }

    public synchronized void delete(String id) {
        ensureMigrated();
        ConnectionCatalog catalog = readCatalog();
        ConnectionProfile existing = existing(catalog, id);
        if (existing == null) throw new AppException(HttpStatus.NOT_FOUND, "连接配置不存在");
        catalog.profiles.remove(existing);
        store.write(catalog);
    }

    public Map<String, Object> test(ConnectionProfile request) {
        if (request == null) throw new AppException("缺少连接配置");
        final ConnectionProfile profile;
        synchronized (this) {
            ensureMigrated();
            profile = prepare(request, existing(readCatalog(), request.id), false);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        try (Connection connection = openConfig(profile)) {
            DatabaseMetaData metadata = connection.getMetaData();
            result.put("success", true);
            result.put("message", "连接成功");
            result.put("productName", metadata.getDatabaseProductName());
            result.put("productVersion", metadata.getDatabaseProductVersion());
            result.put("driverName", metadata.getDriverName());
            result.put("driverVersion", metadata.getDriverVersion());
        } catch (AppException ex) {
            result.put("success", false);
            result.put("message", ex.getMessage());
        } catch (SQLException ex) {
            result.put("success", false);
            result.put("message", "读取数据库信息失败（SQLState " + ex.getSQLState() + "，错误码 " + ex.getErrorCode() + "）");
        }
        result.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
        return result;
    }

    public synchronized Map<String, Object> migrationStatus() {
        ensureMigrated();
        ConnectionCatalog catalog = readCatalog();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("migrated", catalog.legacyMigrated);
        result.put("count", catalog.legacyCount);
        result.put("backupDirectory", catalog.legacyMigrated ? paths.root().resolve("migration-backups/legacy-v1").toString() : null);
        result.put("message", catalog.legacyMigrated ? "旧连接已迁移；原文件与密钥已备份，请为迁移连接选择驱动并确认方言。" : "没有需要迁移的旧连接配置。");
        return result;
    }

    private Connection openConfig(ConnectionProfile profile) {
        if (!hasText(profile.driverId)) throw new AppException("此连接尚未选择驱动，请先编辑连接配置");
        Properties properties = new Properties();
        if (profile.properties != null) properties.putAll(profile.properties);
        // Dedicated authentication fields take precedence, and are never mixed with application controls.
        if (profile.username != null) properties.setProperty("user", profile.username);
        if (profile.password != null) properties.setProperty("password", profile.password);
        Connection connection = drivers.open(profile.driverId, profile.jdbcUrl, properties);
        try {
            if (hasText(profile.catalog)) connection.setCatalog(profile.catalog);
            if (hasText(profile.schema)) connection.setSchema(profile.schema);
            return connection;
        } catch (SQLException | RuntimeException | AbstractMethodError ex) {
            try { connection.close(); } catch (SQLException ignored) { }
            throw new AppException("设置 catalog/schema 失败，请检查名称、权限及驱动支持");
        }
    }

    private ConnectionProfile prepare(ConnectionProfile request, ConnectionProfile existing, boolean requireName) {
        if (request == null) throw new AppException("缺少连接配置");
        if (hasText(request.id) && existing == null) throw new AppException(HttpStatus.NOT_FOUND, "连接配置不存在，请刷新列表");
        ConnectionProfile next = new ConnectionProfile();
        next.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        next.name = trim(request.name);
        if (requireName && !hasText(next.name)) throw new AppException("连接名称不能为空");
        next.driverId = trim(request.driverId);
        if (hasText(next.driverId)) {
            DriverProfile driver = drivers.get(next.driverId);
            if (!hasText(driver.driverClass)) throw new AppException("请选择已配置 JDBC 类名的驱动");
        } else if (existing == null || !hasText(existing.legacyType)) {
            throw new AppException("请选择 JDBC 驱动");
        }
        next.needsDriver = !hasText(next.driverId);
        next.jdbcUrl = trim(request.jdbcUrl);
        if (existing != null && next.jdbcUrl != null && next.jdbcUrl.equals(redactUrl(existing))) next.jdbcUrl = existing.jdbcUrl;
        if (!hasText(next.jdbcUrl) || !next.jdbcUrl.startsWith("jdbc:")) throw new AppException("请输入完整 jdbc: 连接串");
        if (next.jdbcUrl.contains(SAVED_VALUE)) throw new AppException("修改连接串后请重新填写被隐藏的参数值");
        next.username = request.username;
        next.password = request.clearPassword ? "" :
                (request.password == null || request.password.isEmpty() ? (existing == null ? "" : existing.password) : request.password);
        next.dialectHint = trim(request.dialectHint);
        next.catalog = trim(request.catalog);
        next.schema = trim(request.schema);
        next.legacyType = existing == null ? null : existing.legacyType;
        if (existing != null && existing.legacyFields != null) next.legacyFields.putAll(existing.legacyFields);
        if (request.properties != null) {
            for (Map.Entry<String, String> entry : request.properties.entrySet()) {
                if (!hasText(entry.getKey()) || entry.getValue() == null) throw new AppException("JDBC 属性名称与值不能为空值");
                String value = entry.getValue();
                if (SAVED_VALUE.equals(value)) {
                    if (existing == null || existing.properties == null || !existing.properties.containsKey(entry.getKey())) {
                        throw new AppException("属性 " + entry.getKey() + " 需要填写实际值");
                    }
                    value = existing.properties.get(entry.getKey());
                }
                next.properties.put(entry.getKey(), value);
            }
        }
        next.hasPassword = hasText(next.password);
        next.hasSecretProperties = !next.properties.isEmpty();
        return next;
    }

    private ConnectionProfile sanitized(ConnectionProfile source) {
        ConnectionProfile result = new ConnectionProfile();
        result.id = source.id;
        result.name = source.name;
        result.driverId = source.driverId;
        result.jdbcUrl = redactUrl(source);
        result.username = source.username;
        result.dialectHint = source.dialectHint;
        result.catalog = source.catalog;
        result.schema = source.schema;
        result.hasPassword = hasText(source.password);
        result.needsDriver = !hasText(source.driverId);
        result.legacyType = source.legacyType;
        if (source.legacyFields != null) for (String key : source.legacyFields.keySet()) result.legacyFields.put(key, SAVED_VALUE);
        if (source.properties != null) for (String key : source.properties.keySet()) result.properties.put(key, SAVED_VALUE);
        result.hasSecretProperties = !result.properties.isEmpty();
        return result;
    }

    private static String redactUrl(ConnectionProfile source) {
        if (source.jdbcUrl == null) return null;
        String url = source.jdbcUrl;
        if (hasText(source.password)) url = url.replace(source.password, SAVED_VALUE);
        url = url.replaceAll("(//)[^/@]+@", "$1<saved>@");
        url = url.replaceAll("(?i)(jdbc:oracle:thin:)[^/@:]+/[^@]+@", "$1<saved>@");
        Matcher matcher = URL_PROPERTY.matcher(url);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + "=" + SAVED_VALUE));
        matcher.appendTail(output);
        return output.toString();
    }

    private ConnectionCatalog readCatalog() {
        if (Files.exists(file) && !Files.exists(paths.keyFile())) throw new AppException("连接配置存在但密钥丢失，请从备份恢复 master.key；未创建或覆盖配置");
        requireNonemptyFile(file, "连接配置");
        ConnectionCatalog catalog;
        try { catalog = store.read(); }
        catch (AppException ex) { throw new AppException("连接配置损坏或密钥不匹配，原文件已保留，请从备份恢复"); }
        if (catalog == null || catalog.version != 2 || catalog.profiles == null) throw new AppException("不支持或已损坏的连接配置格式，原文件已保留");
        return catalog;
    }

    private void ensureMigrated() {
        if (Files.exists(file)) { readCatalog(); return; }
        Path legacy = paths.configDir().resolve("datasources.enc");
        if (!Files.exists(legacy)) return;
        if (!Files.exists(paths.keyFile())) throw new AppException("旧连接密钥 master.key 丢失，迁移已停止；请恢复密钥");
        requireNonemptyFile(legacy, "旧连接配置");
        EncryptedJsonFileStore<List<Map<String, Object>>> oldStore = new EncryptedJsonFileStore<List<Map<String, Object>>>(
                legacy, paths.keyFile(), mapper, new TypeReference<List<Map<String, Object>>>() { }, ArrayList::new);
        List<Map<String, Object>> legacyProfiles;
        try { legacyProfiles = oldStore.read(); }
        catch (AppException ex) { throw new AppException("旧连接配置损坏或密钥不匹配，迁移已停止；原文件已保留"); }
        if (legacyProfiles == null) throw new AppException("旧连接配置格式无效，迁移已停止");
        ConnectionCatalog catalog = new ConnectionCatalog();
        for (Map<String, Object> source : legacyProfiles) {
            if (source == null) throw new AppException("旧连接配置包含空记录，迁移已停止");
            ConnectionProfile target = new ConnectionProfile();
            target.id = string(source.get("id"));
            if (!hasText(target.id)) target.id = UUID.randomUUID().toString();
            if (existing(catalog, target.id) != null) throw new AppException("旧连接包含重复标识，迁移已停止；原文件已保留");
            target.name = string(source.get("name"));
            target.legacyType = string(source.get("type"));
            target.username = string(source.get("username"));
            target.password = string(source.get("password"));
            target.jdbcUrl = string(source.get("jdbcUrl"));
            target.needsDriver = true;
            target.dialectHint = "generic";
            if ("MYSQL".equalsIgnoreCase(target.legacyType)) target.dialectHint = "mysql";
            if ("POSTGRESQL".equalsIgnoreCase(target.legacyType)) target.dialectHint = "postgresql";
            if ("GAUSSDB".equalsIgnoreCase(target.legacyType)) target.dialectHint = "gaussdb";
            Object params = source.get("params");
            if (params instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) params).entrySet()) {
                    if (entry.getValue() != null) target.properties.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            if (!hasText(target.jdbcUrl)) {
                String host = string(source.get("host"));
                String database = string(source.get("databaseName"));
                String port = string(source.get("port"));
                if ("MYSQL".equalsIgnoreCase(target.legacyType)) target.jdbcUrl = "jdbc:mysql://" + host + ":" + (hasText(port) ? port : "3306") + "/" + database;
                else if ("POSTGRESQL".equalsIgnoreCase(target.legacyType)) {
                    target.jdbcUrl = "jdbc:postgresql://" + host + ":" + (hasText(port) ? port : "5432") + "/" + database;
                } else if ("GAUSSDB".equalsIgnoreCase(target.legacyType)) {
                    String prefix = target.properties.containsKey("urlPrefix") ? target.properties.get("urlPrefix") : "jdbc:gaussdb://";
                    target.jdbcUrl = prefix + host + ":" + (hasText(port) ? port : "8000") + "/" + database;
                }
                // Preserve unknown legacy connection fields without guessing a vendor URL.
                target.legacyFields.put("host", host == null ? "" : host);
                target.legacyFields.put("port", port == null ? "" : port);
                target.legacyFields.put("databaseName", database == null ? "" : database);
            }
            for (String control : new String[]{"driverClassName", "urlPrefix"}) {
                if (target.properties.containsKey(control)) target.legacyFields.put(control, target.properties.remove(control));
            }
            catalog.profiles.add(target);
        }
        try {
            Path backup = paths.root().resolve("migration-backups/legacy-v1");
            Files.createDirectories(backup);
            Files.copy(legacy, backup.resolve("datasources.enc"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(paths.keyFile(), backup.resolve("master.key"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new AppException("备份旧配置失败，迁移已停止；原文件已保留");
        }
        catalog.legacyMigrated = true;
        catalog.legacyCount = catalog.profiles.size();
        store.write(catalog);
    }

    private static ConnectionProfile existing(ConnectionCatalog catalog, String id) {
        if (hasText(id)) for (ConnectionProfile profile : catalog.profiles) if (profile.id.equals(id)) return profile;
        return null;
    }
    private static void requireNonemptyFile(Path path, String label) {
        try {
            if (Files.exists(path) && Files.size(path) == 0) throw new AppException(label + "文件为空，原文件已保留，请从备份恢复");
        } catch (IOException ex) { throw new AppException("无法读取" + label + "，请检查文件权限"); }
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    public static class ConnectionCatalog {
        public int version = 2;
        public List<ConnectionProfile> profiles = new ArrayList<ConnectionProfile>();
        public boolean legacyMigrated;
        public int legacyCount;
    }
}
