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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BundledDriverInstallerTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private StoragePaths paths;
    private DriverService drivers;

    @BeforeEach void setup() {
        ToolboxProperties config = new ToolboxProperties();
        config.setStorageRoot(temporary.toString());
        paths = new StoragePaths(config);
        drivers = new DriverService(paths, mapper);
    }

    @AfterEach void cleanup() { drivers.shutdown(); }

    @Test void freshWorkspaceProvidesThreeIndependentDriversAndH2CanQuery() throws Exception {
        install();
        List<DriverProfile> profiles = drivers.list();
        assertEquals(3, profiles.size());
        Set<ClassLoader> loaders = new HashSet<ClassLoader>();
        for (DriverProfile profile : profiles) {
            assertTrue(profile.bundled);
            assertEquals("ready", profile.status);
            assertNotNull(profile.version);
            assertFalse(profile.version.trim().isEmpty());
            assertNotNull(profile.sourceUrl);
            assertTrue(profile.sourceUrl.startsWith("https://"));
            assertEquals(profile.files.size(), profile.sha256.size());
            for (String filename : profile.files) assertTrue(Files.isRegularFile(extracted(profile, filename)));
            Driver driver = loadedDriver(profile);
            assertTrue(driver.acceptsURL(profile.urlTemplate));
            assertNotSame(getClass().getClassLoader(), driver.getClass().getClassLoader());
            assertTrue(loaders.add(driver.getClass().getClassLoader()), "Each bundled driver needs its own loader");
            if (profile.driverClass.startsWith("com.mysql.")) {
                Class<?> dependency = Class.forName("com.google.protobuf.Message", true, driver.getClass().getClassLoader());
                assertSame(driver.getClass().getClassLoader(), dependency.getClassLoader());
            }
        }
        DriverProfile h2 = h2Profile();
        try (Connection connection = drivers.open(h2.id, h2.urlTemplate, h2Properties());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 6 * 7 as answer")) {
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
            assertTrue(result.next());
            assertEquals(42, result.getInt("answer"));
            assertFalse(result.next());
        }
    }

    @Test void repeatedStartupIsIdempotentAndPreservesImportedProfiles() throws Exception {
        install();
        Path jar = Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        byte[] jarBytes = Files.readAllBytes(jar);
        DriverProfile custom = drivers.importFiles(new MultipartFile[]{
                new MockMultipartFile("files", "my-h2.jar", "application/java-archive", jarBytes)
        }, "我的自选 H2 驱动", null);
        byte[] customConfig = mapper.writeValueAsBytes(custom);
        Set<String> ids = profileIds();
        install();
        restart();
        install();

        assertEquals(4, drivers.list().size());
        assertEquals(ids, profileIds());
        DriverProfile retained = drivers.get(custom.id);
        assertFalse(retained.bundled);
        assertArrayEquals(customConfig, mapper.writeValueAsBytes(retained));
        assertArrayEquals(jarBytes, Files.readAllBytes(extracted(retained, retained.files.get(0))));
        Driver bundled = loadedDriver(h2Profile());
        Driver uploaded = loadedDriver(retained);
        assertNotSame(bundled.getClass().getClassLoader(), uploaded.getClass().getClassLoader());
        try (Connection connection = drivers.open(retained.id, "jdbc:h2:mem:custom-preserved", h2Properties());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 'custom'")) {
            assertTrue(result.next());
            assertEquals("custom", result.getString(1));
        }
    }

    @Test void bundledProfilesCannotBeDeletedOrHaveTheirClassChanged() throws Exception {
        install();
        byte[] before = mapper.writeValueAsBytes(drivers.list());
        for (DriverProfile profile : drivers.list()) {
            assertThrows(AppException.class, () -> drivers.delete(profile.id));
            assertThrows(AppException.class, () -> drivers.selectClass(profile.id, profile.driverClass));
            assertThrows(AppException.class, () -> drivers.selectClass(profile.id, "missing.OtherDriver"));
        }
        assertArrayEquals(before, mapper.writeValueAsBytes(drivers.list()));
    }

    @Test void restartRestoresMissingAndCorruptedExtractedJars() throws Exception {
        install();
        DriverProfile h2 = h2Profile();
        Map<Path, byte[]> originals = new LinkedHashMap<Path, byte[]>();
        for (DriverProfile profile : drivers.list()) {
            for (String filename : profile.files) {
                Path jar = extracted(profile, filename);
                originals.put(jar, Files.readAllBytes(jar));
            }
        }
        drivers.shutdown();
        for (Path jar : originals.keySet()) Files.delete(jar);
        restart();
        for (Map.Entry<Path, byte[]> original : originals.entrySet())
            assertArrayEquals(original.getValue(), Files.readAllBytes(original.getKey()));

        drivers.shutdown();
        for (Path jar : originals.keySet()) Files.write(jar, new byte[]{0, 1, 2, 3});
        restart();
        for (Map.Entry<Path, byte[]> original : originals.entrySet())
            assertArrayEquals(original.getValue(), Files.readAllBytes(original.getKey()));
        assertEquals(3, drivers.list().size());
        try (Connection connection = drivers.open(h2.id, h2.urlTemplate, h2Properties());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 99")) {
            assertTrue(result.next());
            assertEquals(99, result.getInt(1));
        }
    }

    private void install() { new BundledDriverInstaller(drivers, mapper).install(); }

    private void restart() {
        drivers.shutdown();
        drivers = new DriverService(paths, mapper);
        install();
    }

    private Set<String> profileIds() {
        return drivers.list().stream().map(profile -> profile.id).collect(Collectors.toSet());
    }

    private DriverProfile h2Profile() {
        return drivers.list().stream().filter(profile -> profile.bundled && "org.h2.Driver".equals(profile.driverClass))
                .findFirst().orElseThrow(() -> new AssertionError("Bundled H2 driver is missing"));
    }

    private Path extracted(DriverProfile profile, String filename) {
        return temporary.resolve("drivers").resolve(profile.id).resolve(filename);
    }

    private Properties h2Properties() {
        Properties properties = new Properties();
        properties.setProperty("user", "sa");
        return properties;
    }

    private Driver loadedDriver(DriverProfile profile) {
        // Exercise the real service loader without attempting a network connection.
        assertThrows(AppException.class, () -> drivers.open(profile.id, "jdbc:unsupported:loader-check", new Properties()));
        Map<?, ?> runtimes = (Map<?, ?>) ReflectionTestUtils.getField(drivers, "runtimes");
        assertNotNull(runtimes);
        Object runtime = runtimes.get(profile.id);
        assertNotNull(runtime, "The driver should load before rejecting an unrelated JDBC URL");
        return (Driver) ReflectionTestUtils.getField(runtime, "driver");
    }
}
