package com.example.dbtoolbox.workbench.driver;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DriverServiceTest {
    @TempDir Path temporary;
    DriverService drivers;
    StoragePaths paths;

    @BeforeEach void setup() {
        ToolboxProperties config = new ToolboxProperties();
        config.setStorageRoot(temporary.toString());
        paths = new StoragePaths(config);
        drivers = new DriverService(paths, new ObjectMapper());
    }
    @AfterEach void cleanup() { drivers.shutdown(); }

    @Test void externalH2ImportsPersistsConnectsAndReleasesReferences() throws Exception {
        DriverProfile profile = drivers.importFiles(new MultipartFile[]{h2()}, "H2 external", null);
        assertEquals("org.h2.Driver", profile.driverClass);
        assertEquals("ready", profile.status);
        assertEquals(64, profile.sha256.get(0).length());
        Connection connection = drivers.open(profile.id, "jdbc:h2:mem:external", new Properties());
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("select 7")) {
            assertTrue(result.next());
            assertEquals(7, result.getInt(1));
            assertSame(connection, result.getStatement().getConnection());
        }
        assertTrue(assertThrows(AppException.class, () -> drivers.delete(profile.id)).getMessage().contains("活动会话"));
        connection.close();
        connection.close();
        drivers.shutdown();
        drivers = new DriverService(paths, new ObjectMapper());
        assertEquals(profile.id, drivers.list().get(0).id);
        try (Connection reopened = drivers.open(profile.id, "jdbc:h2:mem:reopened", new Properties())) { assertFalse(reopened.isClosed()); }
        drivers.delete(profile.id);
        assertTrue(drivers.list().isEmpty());
        assertFalse(Files.exists(temporary.resolve("drivers").resolve(profile.id)));
    }

    @Test void incompatibleUrlDoesNotLeakLease() throws Exception {
        DriverProfile profile = drivers.importFiles(new MultipartFile[]{h2()}, "H2", null);
        assertTrue(assertThrows(AppException.class, () -> drivers.open(profile.id, "jdbc:unknown:test", new Properties())).getMessage().contains("不匹配"));
        drivers.delete(profile.id);
    }

    @Test void badImportAndTraversalAreRejectedWithoutProfiles() throws Exception {
        assertThrows(AppException.class, () -> drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files", "../evil.jar", "application/java-archive", new byte[]{1})}, null, null));
        assertThrows(AppException.class, () -> drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files", "broken.jar", "application/java-archive", new byte[]{1})}, null, null));
        assertTrue(assertThrows(AppException.class, () -> drivers.importFiles(new MultipartFile[]{h2()}, null, "missing.Driver")).getMessage().contains("找不到"));
        assertTrue(assertThrows(AppException.class, () -> drivers.importFiles(new MultipartFile[]{h2()}, null, "java.lang.String")).getMessage().contains("不是"));
        assertTrue(drivers.list().isEmpty());
        try (java.util.stream.Stream<Path> directories = Files.list(temporary.resolve("drivers"))) { assertEquals(0L, directories.count()); }
    }

    @Test void uploadedFilesAreHashCheckedOnReload() throws Exception {
        DriverProfile profile = drivers.importFiles(new MultipartFile[]{h2()}, "H2", null);
        Files.write(temporary.resolve("drivers").resolve(profile.id).resolve(profile.files.get(0)), new byte[]{9});
        assertTrue(assertThrows(AppException.class, () -> drivers.open(profile.id, "jdbc:h2:mem:test", new Properties())).getMessage().contains("校验失败"));
    }

    @Test void sameClassDifferentVersionsAndSplitDependencyRemainIsolated() throws Exception {
        MultipartFile[] versionOne = fakeDriver("one", false);
        MultipartFile[] versionTwo = fakeDriver("two", false);
        DriverProfile first = drivers.importFiles(versionOne, "v1", null);
        DriverProfile second = drivers.importFiles(versionTwo, "v2", null);
        try (Connection one = drivers.open(first.id, "jdbc:fake:test", new Properties());
             Connection two = drivers.open(second.id, "jdbc:fake:test", new Properties())) {
            assertEquals("one", one.getCatalog());
            assertEquals("two", two.getCatalog());
            assertEquals("one", one.getCatalog());
        }
    }

    @Test void missingDependencyAndNewerBytecodeGiveActionableFailures() throws Exception {
        MultipartFile[] missing = fakeDriver("dependency", false);
        assertTrue(assertThrows(AppException.class, () -> drivers.importFiles(new MultipartFile[]{missing[0]}, "missing dependency", null)).getMessage().contains("缺少依赖"));
        MultipartFile[] newer = fakeDriver("java99", true);
        assertTrue(assertThrows(AppException.class, () -> drivers.importFiles(newer, "new bytecode", null)).getMessage().contains("字节码版本"));
        assertTrue(drivers.list().isEmpty());
    }

    @Test void noSpiCanBeConfiguredManuallyAndReadyVersionIsImmutable() throws Exception {
        MultipartFile[] original = fakeDriver("manual", false);
        byte[] jar = original[0].getBytes();
        ByteArrayOutputStream stripped = new ByteArrayOutputStream();
        try (java.util.jar.JarInputStream input = new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(jar));
             JarOutputStream output = new JarOutputStream(stripped)) {
            JarEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextJarEntry()) != null) {
                if (entry.getName().startsWith("META-INF/services/")) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.closeEntry();
            }
        }
        DriverProfile profile = drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files", "manual.jar", "application/java-archive", stripped.toByteArray()), original[1]}, "manual", null);
        assertEquals("needsDriverClass", profile.status);
        assertThrows(AppException.class, () -> drivers.open(profile.id, "jdbc:fake:test", new Properties()));
        drivers.selectClass(profile.id, "fixture.SameDriver");
        try (Connection connection = drivers.open(profile.id, "jdbc:fake:test", new Properties())) { assertEquals("manual", connection.getCatalog()); }
        assertThrows(AppException.class, () -> drivers.selectClass(profile.id, "another.Driver"));
    }

    @Test void everyJdbcChildCallUsesDriverContextAndRestoresCallerLoader() throws Exception {
        DriverProfile profile = drivers.importFiles(contextDriver(), "context dependent", null);
        ClassLoader caller = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(caller);
        try {
            Connection connection = drivers.open(profile.id, "jdbc:context:test", new Properties());
            assertSame(caller, Thread.currentThread().getContextClassLoader());
            assertSame(connection, connection.unwrap(Connection.class));
            assertTrue(connection.isWrapperFor(Connection.class));
            java.sql.DatabaseMetaData database = connection.getMetaData();
            assertEquals("context", database.getDatabaseProductName());
            assertSame(connection, database.getConnection());
            try (java.sql.PreparedStatement statement = connection.prepareStatement("select 1")) {
                assertSame(statement, statement.unwrap(java.sql.PreparedStatement.class));
                assertSame(connection, statement.getConnection());
                statement.setString(1, "text");
                assertEquals(1, statement.getParameterMetaData().getParameterCount());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertSame(statement, result.getStatement());
                    assertSame(result, result.unwrap(ResultSet.class));
                    assertEquals("context-value", result.getString(1));
                    assertEquals(1, result.getMetaData().getColumnCount());
                    try (InputStream stream = result.getBinaryStream(1)) { assertEquals(42, stream.read()); }
                    try (java.io.Reader reader = result.getCharacterStream(1)) { assertEquals('x', reader.read()); }
                    result.close();
                    assertTrue(result.isClosed());
                }
                assertSame(caller, Thread.currentThread().getContextClassLoader());
                statement.close();
                assertTrue(statement.isClosed());
            }
            try (java.sql.CallableStatement call = connection.prepareCall("{call demo(?)}")) {
                call.registerOutParameter(1, java.sql.Types.VARCHAR);
                assertTrue(call.execute());
                assertEquals("context-value", call.getString(1));
            }
            connection.close();
            connection.close();
            assertTrue(connection.isClosed());
            drivers.delete(profile.id);
            assertSame(caller, Thread.currentThread().getContextClassLoader());
        } finally { Thread.currentThread().setContextClassLoader(previous); ((java.net.URLClassLoader) caller).close(); }
    }

    @Test void driverThatDoesNotConfirmCloseKeepsItsLease() throws Exception {
        DriverProfile profile=drivers.importFiles(fakeDriver("unconfirmed",false),"unconfirmed close",null);
        Connection c=drivers.open(profile.id,"jdbc:fake:test",new Properties());c.close();
        assertFalse(c.isClosed());
        assertTrue(assertThrows(AppException.class,()->drivers.delete(profile.id)).getMessage().contains("活动会话"));
    }

    private MockMultipartFile h2() throws Exception {
        Path jar = Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new MockMultipartFile("files", "h2.jar", "application/java-archive", Files.readAllBytes(jar));
    }

    private MultipartFile[] contextDriver() throws Exception {
        Path build = Files.createTempDirectory(temporary, "context-driver-");
        Path source = build.resolve("ContextDriver.java");
        String implementation = "import java.sql.*;import java.util.*;import java.lang.reflect.*;import java.util.logging.*;import java.io.*;" +
                "public class ContextDriver implements Driver {" +
                "static void check(){if(Thread.currentThread().getContextClassLoader()!=ContextDriver.class.getClassLoader())throw new IllegalStateException(\"wrong TCCL\");}" +
                "static Object make(Class type,Object connection,Object statement){final boolean[] closed={false};return Proxy.newProxyInstance(ContextDriver.class.getClassLoader(),new Class[]{type},(p,m,a)->{check();String n=m.getName();" +
                "if(n.equals(\"unwrap\"))return p;if(n.equals(\"isWrapperFor\"))return true;" +
                "if(n.equals(\"getConnection\"))return connection;if(n.equals(\"getStatement\"))return statement;" +
                "if(n.equals(\"getDatabaseProductName\"))return \"context\";if(n.equals(\"getString\"))return \"context-value\";" +
                "if(n.equals(\"getColumnCount\")||n.equals(\"getParameterCount\"))return 1;" +
                "if(n.equals(\"getMetaData\"))return make(type==Connection.class?DatabaseMetaData.class:ResultSetMetaData.class,p,null);" +
                "if(n.equals(\"getParameterMetaData\"))return make(ParameterMetaData.class,null,null);" +
                "if(n.equals(\"prepareCall\"))return make(CallableStatement.class,p,null);if(n.equals(\"prepareStatement\"))return make(PreparedStatement.class,p,null);if(n.equals(\"createStatement\"))return make(Statement.class,p,null);" +
                "if(n.equals(\"executeQuery\")||n.equals(\"getResultSet\"))return make(ResultSet.class,connection,p);" +
                "if(n.equals(\"getBinaryStream\"))return new InputStream(){public int read(){check();return 42;}public void close(){check();}};" +
                "if(n.equals(\"getCharacterStream\"))return new Reader(){public int read(char[] c,int o,int l){check();c[o]='x';return 1;}public void close(){check();}};" +
                "if(n.equals(\"next\")||n.equals(\"execute\"))return true;if(n.equals(\"close\")||n.equals(\"abort\")){closed[0]=true;return null;}if(n.equals(\"isClosed\"))return closed[0];return null;});}" +
                "public Connection connect(String url,Properties p){check();return (Connection)make(Connection.class,null,null);}public boolean acceptsURL(String u){return true;}" +
                "public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];}public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}public Logger getParentLogger(){return Logger.getGlobal();}}";
        Files.write(source, implementation.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-source", "8", "-target", "8", "-d", build.toString(), source.toString()));
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(content); java.util.stream.Stream<Path> classes = Files.list(build)) {
            for (Path file : (Iterable<Path>) classes.filter(p -> p.toString().endsWith(".class"))::iterator) {
                jar.putNextEntry(new JarEntry(file.getFileName().toString())); jar.write(Files.readAllBytes(file)); jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver")); jar.write("ContextDriver\n".getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
        }
        return new MultipartFile[]{new MockMultipartFile("files", "context.jar", "application/java-archive", content.toByteArray())};
    }

    private MultipartFile[] fakeDriver(String version, boolean newer) throws Exception {
        Path build = Files.createTempDirectory(temporary, "fixture-");
        Path source = build.resolve("fixture");
        Files.createDirectories(source);
        Files.write(source.resolve("Helper.java"), ("package fixture; public class Helper { public static String version() { return \"" + version + "\"; } }").getBytes(StandardCharsets.UTF_8));
        String implementation = "package fixture; import java.sql.*; import java.util.*; import java.util.logging.*; import java.lang.reflect.*; " +
                "public class SameDriver implements Driver { private final String version = Helper.version(); " +
                "public Connection connect(String url, Properties p) { if(!acceptsURL(url)) return null; return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class}, (o,m,a)->{ " +
                "if(m.getName().equals(\"getCatalog\"))return version; if(m.getName().equals(\"isClosed\"))return false; return null; }); } " +
                "public boolean acceptsURL(String url){return url.startsWith(\"jdbc:fake:\");} public DriverPropertyInfo[] getPropertyInfo(String u, Properties p){return new DriverPropertyInfo[0];} " +
                "public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;} public boolean jdbcCompliant(){return false;} public Logger getParentLogger(){return Logger.getGlobal();}}";
        Files.write(source.resolve("SameDriver.java"), implementation.getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, "-source", "8", "-target", "8", "-d", build.toString(), source.resolve("Helper.java").toString(), source.resolve("SameDriver.java").toString()));
        byte[] driverClass = Files.readAllBytes(source.resolve("SameDriver.class"));
        if (newer) { driverClass[6] = 0; driverClass[7] = 99; }
        ByteArrayOutputStream main = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(main)) {
            jar.putNextEntry(new JarEntry("fixture/SameDriver.class")); jar.write(driverClass); jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver")); jar.write("fixture.SameDriver\n".getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
        }
        ByteArrayOutputStream helper = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(helper)) {
            jar.putNextEntry(new JarEntry("fixture/Helper.class")); jar.write(Files.readAllBytes(source.resolve("Helper.class"))); jar.closeEntry();
        }
        return new MultipartFile[]{new MockMultipartFile("files", "main.jar", "application/java-archive", main.toByteArray()), new MockMultipartFile("files", "helper.jar", "application/java-archive", helper.toByteArray())};
    }
}
