package com.example.dbtoolbox.workbench.driver;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.Reader;
import java.io.FilterReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.DatabaseMetaData;
import java.sql.ResultSetMetaData;
import java.sql.ParameterMetaData;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLXML;
import java.sql.Array;
import java.sql.Ref;
import java.sql.Savepoint;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

@Service
public class DriverService {
    private static final long MAX_JAR_BYTES = 128L * 1024 * 1024;
    private static final long MAX_IMPORT_BYTES = 256L * 1024 * 1024;
    private static final String CLEANUP_CLASS = DriverRegistrationCleanup.class.getName();
    private final Path driverRoot;
    private final Path catalogFile;
    private final ObjectMapper mapper;
    private final Map<String, RuntimeDriver> runtimes = new LinkedHashMap<String, RuntimeDriver>();
    private Predicate<String> referenced = id -> false;

    public DriverService(StoragePaths paths, ObjectMapper mapper) {
        this.driverRoot = paths.root().resolve("drivers");
        this.catalogFile = paths.configDir().resolve("drivers-v2.json");
        this.mapper = mapper;
    }

    public synchronized void setReferenceChecker(Predicate<String> checker) {
        this.referenced = checker;
    }

    public synchronized List<DriverProfile> list() {
        return readCatalog().profiles;
    }

    public synchronized DriverProfile get(String id) {
        return require(readCatalog(), id);
    }

    /** Install immutable release resources without replacing user-imported profiles. */
    public synchronized void installBundled(DriverProfile profile) {
        if (!profile.bundled || !profile.id.startsWith("bundled-") || profile.files.isEmpty()
                || profile.files.size() != profile.sha256.size()) {
            throw new AppException("内置驱动清单无效");
        }
        DriverCatalog catalog = readCatalog();
        DriverProfile existing = null;
        for (DriverProfile item : catalog.profiles) if (profile.id.equals(item.id)) existing = item;
        if (existing != null && (!existing.bundled || !existing.files.equals(profile.files)
                || !existing.sha256.equals(profile.sha256) || !profile.driverClass.equals(existing.driverClass))) {
            throw new AppException("内置驱动标识冲突，原配置已保留: " + profile.name);
        }
        Path destination = directory(profile.id);
        try {
            Files.createDirectories(destination);
            for (int i = 0; i < profile.files.size(); i++) {
                String filename = safeFilename(profile.files.get(i));
                Path target = destination.resolve(filename);
                if (Files.isRegularFile(target) && profile.sha256.get(i).equals(sha256(target))) continue;
                RuntimeDriver active = runtimes.get(profile.id);
                if (active != null) throw new AppException("内置驱动正在使用，请重启后恢复文件");
                Path temporary = Files.createTempFile(destination, "bundled-", ".tmp");
                try {
                    try (InputStream input = DriverService.class.getResourceAsStream("/bundled-drivers/" + filename)) {
                        if (input == null) throw new AppException("发行包缺少内置驱动: " + filename);
                        Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!profile.sha256.get(i).equals(sha256(temporary))) {
                        throw new AppException("发行包内置驱动校验失败: " + filename);
                    }
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temporary); }
            }
            RuntimeDriver checked = load(profile);
            checked.dispose();
            if (existing == null) {
                catalog.profiles.add(profile);
                writeCatalog(catalog);
            }
        } catch (AppException ex) { throw ex; }
        catch (Exception ex) { throw new AppException("初始化内置驱动失败: " + profile.name + "（" + ex.getClass().getSimpleName() + "）"); }
    }

    public synchronized DriverProfile importFiles(MultipartFile[] uploads, String name, String driverClass) {
        if (uploads == null || uploads.length == 0 || uploads.length > 32) {
            throw new AppException("请选择 1 至 32 个 JDBC 驱动及依赖 JAR");
        }
        DriverProfile profile = new DriverProfile();
        profile.id = UUID.randomUUID().toString();
        profile.name = hasText(name) ? name.trim() : "JDBC 驱动";
        Path directory = directory(profile.id);
        long total = 0;
        Set<String> names = new LinkedHashSet<String>();
        Set<String> candidates = new LinkedHashSet<String>();
        boolean committed = false;
        try {
            Files.createDirectories(directory);
            for (MultipartFile upload : uploads) {
                String filename = safeFilename(upload.getOriginalFilename());
                if (!names.add(filename.toLowerCase(java.util.Locale.ROOT))) {
                    throw new AppException("驱动包文件名重复: " + filename);
                }
                if (upload.isEmpty() || upload.getSize() > MAX_JAR_BYTES || total + upload.getSize() > MAX_IMPORT_BYTES) {
                    throw new AppException("驱动包不能为空；单包上限 128 MB，总量上限 256 MB");
                }
                Path output = directory.resolve(filename);
                try (InputStream input = upload.getInputStream(); java.io.OutputStream out = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    long size = 0;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        total += count;
                        if (size > MAX_JAR_BYTES || total > MAX_IMPORT_BYTES) {
                            throw new AppException("驱动包超过上传大小限制");
                        }
                        out.write(buffer, 0, count);
                    }
                }
                inspectJar(output, candidates);
                profile.files.add(filename);
                profile.sha256.add(sha256(output));
            }
            profile.candidates.addAll(candidates);
            profile.driverClass = hasText(driverClass) ? driverClass.trim() :
                    (candidates.size() == 1 ? candidates.iterator().next() : null);
            profile.status = hasText(profile.driverClass) ? "ready" : "needsDriverClass";
            if (hasText(profile.driverClass)) {
                RuntimeDriver runtime = load(profile);
                runtime.dispose();
            }
            DriverCatalog catalog = readCatalog();
            catalog.profiles.add(profile);
            writeCatalog(catalog);
            committed = true;
            return profile;
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AppException("导入驱动失败: " + safeMessage(ex));
        } finally {
            if (!committed) removeDirectory(directory);
        }
    }

    public synchronized DriverProfile selectClass(String id, String className) {
        if (!hasText(className)) throw new AppException("驱动类名不能为空");
        DriverCatalog catalog = readCatalog();
        DriverProfile profile = require(catalog, id);
        if (profile.bundled) throw new AppException("内置驱动类不可修改；需要其他版本时请另行导入");
        if (hasText(profile.driverClass) && !profile.driverClass.equals(className.trim())) {
            throw new AppException("已就绪的驱动版本不可修改，请重新导入为新驱动配置");
        }
        profile.driverClass = className.trim();
        RuntimeDriver runtime = load(profile);
        runtime.dispose();
        profile.status = "ready";
        writeCatalog(catalog);
        return profile;
    }

    public Connection open(String driverId, String url, Properties properties) {
        if (!hasText(url) || !url.startsWith("jdbc:")) throw new AppException("请输入完整 jdbc: 连接串");
        final RuntimeDriver runtime;
        synchronized (this) {
            runtime = runtime(driverId);
            runtime.references++;
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(runtime.loader);
            Properties supplied = new Properties();
            if (properties != null) supplied.putAll(properties);
            Connection physical = runtime.driver.connect(url, supplied);
            if (physical == null) throw new AppException("JDBC URL 与所选驱动不匹配");
            return wrap(physical, runtime);
        } catch (SQLException ex) {
            release(runtime);
            String state = ex.getSQLState();
            String reason = state != null && state.startsWith("28") ? "数据库认证失败" :
                    (state != null && state.startsWith("08") ? "数据库网络连接失败" : "数据库连接失败");
            throw new AppException(reason + "（SQLState " + (state == null ? "未知" : state) + "，错误码 " + ex.getErrorCode() + "）");
        } catch (AppException ex) {
            release(runtime);
            throw ex;
        } catch (LinkageError ex) {
            release(runtime);
            throw loadingError(ex);
        } catch (RuntimeException ex) {
            release(runtime);
            throw new AppException("驱动建立连接失败，请检查连接属性与所需依赖");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public synchronized void delete(String id) {
        DriverCatalog catalog = readCatalog();
        DriverProfile profile = require(catalog, id);
        if (profile.bundled) throw new AppException("内置驱动随应用提供，无需删除；可以另行导入并选择其他版本");
        RuntimeDriver runtime = runtimes.get(id);
        if (runtime != null && runtime.references > 0) throw new AppException("驱动仍有活动会话，请先断开连接");
        if (referenced.test(id)) throw new AppException("已保存的连接仍引用此驱动，请先修改或删除连接配置");
        if (runtime != null) {
            runtime.dispose();
            runtimes.remove(id);
        }
        catalog.profiles.remove(profile);
        writeCatalog(catalog);
        removeDirectory(directory(id));
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (RuntimeDriver runtime : runtimes.values()) runtime.dispose();
        runtimes.clear();
    }

    private RuntimeDriver runtime(String id) {
        RuntimeDriver runtime = runtimes.get(id);
        if (runtime == null) {
            runtime = load(get(id));
            runtimes.put(id, runtime);
        }
        return runtime;
    }

    private RuntimeDriver load(DriverProfile profile) {
        if (!hasText(profile.driverClass)) throw new AppException("请先选择或填写 JDBC 驱动类名");
        IsolatedLoader loader = null;
        try {
            URL[] urls = new URL[profile.files.size()];
            for (int i = 0; i < urls.length; i++) {
                Path jar = directory(profile.id).resolve(safeFilename(profile.files.get(i)));
                if (!Files.isRegularFile(jar)) throw new AppException("驱动包丢失: " + profile.files.get(i));
                if (profile.sha256.size() <= i || !sha256(jar).equals(profile.sha256.get(i))) {
                    throw new AppException("驱动包校验失败，请重新导入: " + profile.files.get(i));
                }
                urls[i] = jar.toUri().toURL();
            }
            loader = new IsolatedLoader(urls);
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                Class<?> type = Class.forName(profile.driverClass, true, loader);
                if (!Driver.class.isAssignableFrom(type)) throw new AppException("所选类不是 java.sql.Driver: " + profile.driverClass);
                Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
                return new RuntimeDriver(loader, driver);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        } catch (Throwable ex) {
            if (loader != null) loader.dispose();
            if (ex instanceof AppException) throw (AppException) ex;
            if (ex instanceof VirtualMachineError || ex instanceof ThreadDeath) throw (Error) ex;
            Throwable cause = ex instanceof InvocationTargetException ? ((InvocationTargetException) ex).getCause() : ex;
            throw loadingError(cause);
        }
    }

    private Connection wrap(final Connection physical, final RuntimeDriver runtime) {
        JdbcGraph graph = new JdbcGraph(physical, runtime);
        graph.connection = (Connection) graph.proxy(physical, Connection.class, null);
        return graph.connection;
    }

    /** Child objects keep the same classloader context and point back to the leased connection. */
    private final class JdbcGraph {
        final Connection physical;
        final RuntimeDriver runtime;
        final AtomicBoolean released = new AtomicBoolean();
        Connection connection;
        JdbcGraph(Connection physical, RuntimeDriver runtime) { this.physical = physical; this.runtime = runtime; }

        Object proxy(Object target, Class<?> type, Object ownerStatement) {
            return Proxy.newProxyInstance(DriverService.class.getClassLoader(), new Class<?>[]{type}, new JdbcInvocation(this, target, ownerStatement));
        }

        Object child(Object value, Object ownerStatement) {
            if (value == null) return null;
            if (value instanceof Connection) return connection;
            if (value instanceof Statement && ownerStatement != null) return ownerStatement;
            if (value instanceof CallableStatement) return proxy(value, CallableStatement.class, null);
            if (value instanceof PreparedStatement) return proxy(value, PreparedStatement.class, null);
            if (value instanceof Statement) return proxy(value, Statement.class, null);
            if (value instanceof ResultSet) return proxy(value, ResultSet.class, ownerStatement);
            if (value instanceof DatabaseMetaData) return proxy(value, DatabaseMetaData.class, null);
            if (value instanceof ResultSetMetaData) return proxy(value, ResultSetMetaData.class, null);
            if (value instanceof ParameterMetaData) return proxy(value, ParameterMetaData.class, null);
            if (value instanceof NClob) return proxy(value, NClob.class, null);
            if (value instanceof Clob) return proxy(value, Clob.class, null);
            if (value instanceof Blob) return proxy(value, Blob.class, null);
            if (value instanceof SQLXML) return proxy(value, SQLXML.class, null);
            if (value instanceof Array) return proxy(value, Array.class, null);
            if (value instanceof Ref) return proxy(value, Ref.class, null);
            if (value instanceof Savepoint) return proxy(value, Savepoint.class, null);
            if (value instanceof Struct) return proxy(value, Struct.class, null);
            if (value instanceof InputStream) return new ContextInputStream((InputStream) value, runtime.loader);
            if (value instanceof Reader) return new ContextReader((Reader) value, runtime.loader);
            return value;
        }

        void releaseOnce() { if (released.compareAndSet(false, true)) release(runtime); }
    }

    private final class JdbcInvocation implements InvocationHandler {
        final JdbcGraph graph;
        final Object target;
        final Object ownerStatement;
        final AtomicBoolean closed = new AtomicBoolean();
        JdbcInvocation(JdbcGraph graph, Object target, Object ownerStatement) {
            this.graph = graph; this.target = target; this.ownerStatement = ownerStatement;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(name)) return "IsolatedJdbc" + proxy.getClass().getInterfaces()[0].getSimpleName();
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == arguments[0];
            }
            if ("isClosed".equals(name) && (closed.get() || graph.released.get())) return true;
            if ("close".equals(name) && (closed.get() || graph.released.get())) return null;
            if (("unwrap".equals(name) || "isWrapperFor".equals(name)) && arguments != null && arguments[0] instanceof Class) {
                Class<?> requested = (Class<?>) arguments[0];
                if (requested.isInstance(proxy)) return "unwrap".equals(name) ? proxy : true;
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            boolean closingConnection = target == graph.physical && ("close".equals(name) || "abort".equals(name));
            try {
                Thread.currentThread().setContextClassLoader(graph.runtime.loader);
                Object[] args = arguments == null ? null : arguments.clone();
                if (args != null) for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && Proxy.isProxyClass(args[i].getClass())) {
                        InvocationHandler handler = Proxy.getInvocationHandler(args[i]);
                        if (handler instanceof JdbcInvocation) args[i] = ((JdbcInvocation) handler).target;
                    }
                }
                Object value = method.invoke(target, args);
                if ("close".equals(name)) closed.set(true);
                if (closingConnection || (target == graph.physical && "isClosed".equals(name) && Boolean.TRUE.equals(value))) graph.releaseOnce();
                Object result = graph.child(value, target instanceof Statement ? proxy : ownerStatement);
                // Standard unwrap stays inside the graph. Vendor concrete classes/interfaces remain supported.
                if ("unwrap".equals(name) && arguments != null && !((Class<?>) arguments[0]).isInstance(result)) return value;
                return result;
            } catch (InvocationTargetException ex) {
                if (closingConnection) {
                    try { if (graph.physical.isClosed()) graph.releaseOnce(); } catch (SQLException ignored) { }
                }
                throw ex.getCause();
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    private interface IoCall<T> { T run() throws IOException; }
    private static <T> T withLoader(ClassLoader loader, IoCall<T> call) throws IOException {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try { Thread.currentThread().setContextClassLoader(loader); return call.run(); }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    private static class ContextInputStream extends FilterInputStream {
        final ClassLoader loader;
        ContextInputStream(InputStream input, ClassLoader loader) { super(input); this.loader = loader; }
        @Override public int read() throws IOException { return withLoader(loader, () -> in.read()); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { return withLoader(loader, () -> in.read(bytes, offset, length)); }
        @Override public long skip(long count) throws IOException { return withLoader(loader, () -> in.skip(count)); }
        @Override public int available() throws IOException { return withLoader(loader, () -> in.available()); }
        @Override public void close() throws IOException { withLoader(loader, () -> { in.close(); return null; }); }
        @Override public synchronized void reset() throws IOException { withLoader(loader, () -> { in.reset(); return null; }); }
        @Override public synchronized void mark(int limit) { try { withLoader(loader, () -> { in.mark(limit); return null; }); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
        @Override public boolean markSupported() { try { return withLoader(loader, () -> in.markSupported()); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
    }
    private static class ContextReader extends FilterReader {
        final ClassLoader loader;
        ContextReader(Reader reader, ClassLoader loader) { super(reader); this.loader = loader; }
        @Override public int read() throws IOException { return withLoader(loader, () -> in.read()); }
        @Override public int read(char[] chars, int offset, int length) throws IOException { return withLoader(loader, () -> in.read(chars, offset, length)); }
        @Override public long skip(long count) throws IOException { return withLoader(loader, () -> in.skip(count)); }
        @Override public boolean ready() throws IOException { return withLoader(loader, () -> in.ready()); }
        @Override public void close() throws IOException { withLoader(loader, () -> { in.close(); return null; }); }
        @Override public void reset() throws IOException { withLoader(loader, () -> { in.reset(); return null; }); }
        @Override public void mark(int limit) throws IOException { withLoader(loader, () -> { in.mark(limit); return null; }); }
        @Override public boolean markSupported() { try { return withLoader(loader, () -> in.markSupported()); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
    }

    private synchronized void release(RuntimeDriver runtime) { runtime.references--; }

    private void inspectJar(Path path, Set<String> candidates) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (!jar.entries().hasMoreElements()) throw new AppException("JAR 为空: " + path.getFileName());
            JarEntry spi = jar.getJarEntry("META-INF/services/java.sql.Driver");
            if (spi != null) {
                if (spi.getSize() > 65536) throw new AppException("JDBC SPI 声明异常");
                try (InputStream input = jar.getInputStream(spi)) {
                    String text = new String(readLimited(input, 65536), StandardCharsets.UTF_8);
                    for (String line : text.split("\\r?\\n")) {
                        String candidate = line.split("#", 2)[0].trim();
                        if (!candidate.isEmpty()) candidates.add(candidate);
                    }
                }
            }
        } catch (java.util.zip.ZipException ex) {
            throw new AppException("损坏或不是有效的 JAR: " + path.getFileName());
        }
    }

    private DriverCatalog readCatalog() {
        if (!Files.exists(catalogFile)) return new DriverCatalog();
        try {
            DriverCatalog catalog = mapper.readValue(catalogFile.toFile(), DriverCatalog.class);
            if (catalog.version != 2 || catalog.profiles == null) throw new IOException("不支持的驱动配置格式");
            return catalog;
        } catch (IOException ex) { throw new AppException("读取驱动配置失败，原文件已保留: " + safeMessage(ex)); }
    }

    private void writeCatalog(DriverCatalog catalog) {
        try {
            Files.createDirectories(catalogFile.getParent());
            Path temporary = Files.createTempFile(catalogFile.getParent(), "drivers-v2-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), catalog);
                try { Files.move(temporary, catalogFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, catalogFile, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException ex) { throw new AppException("保存驱动配置失败: " + safeMessage(ex)); }
    }

    private DriverProfile require(DriverCatalog catalog, String id) {
        for (DriverProfile profile : catalog.profiles) if (profile.id.equals(id)) return profile;
        throw new AppException(HttpStatus.NOT_FOUND, "驱动配置不存在");
    }

    private Path directory(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) throw new AppException("无效的驱动标识");
        return driverRoot.resolve(id);
    }

    private static String safeFilename(String filename) {
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ||
                filename.contains("/") || filename.contains("\\") || filename.contains("..") || filename.length() > 200 ||
                filename.chars().anyMatch(c -> c < 32)) {
            throw new AppException("请选择文件名合法的 .jar 文件");
        }
        return filename;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[16384];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }

    private static AppException loadingError(Throwable ex) {
        if (ex instanceof UnsupportedClassVersionError) return new AppException("驱动字节码版本不兼容，请选择支持当前 Java 版本（发行基线 Java 8）的驱动");
        if (ex instanceof ClassNotFoundException) return new AppException("找不到驱动类，请检查类名和主驱动 JAR");
        if (ex instanceof NoClassDefFoundError) return new AppException("驱动缺少依赖，请将主驱动和所需依赖 JAR 一起导入");
        return new AppException("驱动加载失败（" + ex.getClass().getSimpleName() + "），请检查驱动类、依赖及 Java 版本");
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private static String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        int count;
        while ((count = input.read(bytes)) != -1) {
            if (output.size() + count > limit) throw new IOException("文件内容超出允许大小");
            output.write(bytes, 0, count);
        }
        return output.toByteArray();
    }

    private static void removeDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> paths = new ArrayList<Path>();
            files.forEach(paths::add);
            Collections.sort(paths, Collections.reverseOrder());
            for (Path path : paths) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Some vendor threads retain handles. The unused directory is safe to remove after restart.
        }
    }

    public static class DriverCatalog {
        public int version = 2;
        public List<DriverProfile> profiles = new ArrayList<DriverProfile>();
    }

    private static class RuntimeDriver {
        final IsolatedLoader loader;
        final Driver driver;
        int references;
        RuntimeDriver(IsolatedLoader loader, Driver driver) { this.loader = loader; this.driver = driver; }
        void dispose() { loader.dispose(); }
    }

    private static class IsolatedLoader extends URLClassLoader {
        IsolatedLoader(URL[] urls) { super(urls, ClassLoader.getSystemClassLoader().getParent()); }

        void dispose() {
            try {
                Class<?> helper = findLoadedClass(CLEANUP_CLASS);
                if (helper == null) {
                    try (InputStream bytes = DriverService.class.getResourceAsStream("DriverRegistrationCleanup.class")) {
                        if (bytes == null) throw new IOException("缺少驱动清理辅助类");
                        byte[] source = readLimited(bytes, 65536);
                        helper = defineClass(CLEANUP_CLASS, source, 0, source.length);
                    }
                }
                helper.getMethod("deregister", ClassLoader.class).invoke(null, this);
            } catch (ReflectiveOperationException | IOException | LinkageError ignored) {
                // A driver may own native resources/threads; unloading those cannot be guaranteed by JDBC.
            }
            try { close(); } catch (IOException ignored) { }
        }
    }
}
