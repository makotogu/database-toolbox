package com.example.dbtoolbox.workbench.connection;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.EncryptedJsonFileStore;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.example.dbtoolbox.workbench.driver.DriverProfile;
import com.example.dbtoolbox.workbench.driver.DriverService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionServiceTest {
    @TempDir Path temporary;
    ObjectMapper mapper = new ObjectMapper();
    StoragePaths paths;
    DriverService drivers;
    ConnectionService connections;
    DriverProfile h2;

    @BeforeEach void setup() throws Exception {
        ToolboxProperties properties = new ToolboxProperties();
        properties.setStorageRoot(temporary.toString());
        paths = new StoragePaths(properties);
        drivers = new DriverService(paths, mapper);
        connections = new ConnectionService(paths, mapper, drivers);
        Path driverFile = Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        h2 = drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files", "h2.jar", "application/java-archive", Files.readAllBytes(driverFile))}, "H2", null);
    }
    @AfterEach void cleanup() { drivers.shutdown(); }

    @Test void propertiesAuthenticationAndPasswordRetainReplaceClearAreReal() throws Exception {
        ConnectionProfile initial = request("jdbc:h2:mem:credentials;DB_CLOSE_DELAY=-1");
        initial.password = "initial-secret";
        initial.properties.put("MODE", "PostgreSQL");
        initial.properties.put("user", "ignored-user");
        initial.properties.put("password", "ignored-property-password");
        ConnectionProfile saved = connections.save(initial);
        assertNull(saved.password);
        assertTrue(saved.hasPassword);
        assertEquals(ConnectionService.SAVED_VALUE, saved.properties.get("MODE"));
        assertTrue((Boolean) connections.test(saved).get("success"));
        try (Connection connection = connections.open(saved.id)) {
            assertEquals("SA", connection.getMetaData().getUserName());
            assertEquals("PostgreSQL", connections.get(saved.id).properties.get("MODE"));
        }
        assertEquals("initial-secret", connections.get(saved.id).password);
        saved.password = "replacement-secret";
        connections.save(saved);
        assertEquals("replacement-secret", connections.get(saved.id).password);
        saved.password = null;
        saved.clearPassword = true;
        connections.save(saved);
        assertEquals("", connections.get(saved.id).password);
        assertFalse(connections.list().get(0).hasPassword);
    }

    @Test void listAndStoreNeverExposeUrlOrPropertySecretsAndEditingPreservesThem() throws Exception {
        ConnectionProfile initial = request("jdbc:h2:mem:private;PASSWORD=url-secret;MODE=PostgreSQL");
        initial.password = "field-secret";
        initial.properties.put("customCredential", "property-secret");
        ConnectionProfile saved = connections.save(initial);
        String exposed = mapper.writeValueAsString(connections.list());
        assertFalse(exposed.contains("field-secret"));
        assertFalse(exposed.contains("url-secret"));
        assertFalse(exposed.contains("property-secret"));
        saved.name = "renamed";
        connections.save(saved);
        assertEquals(initial.jdbcUrl, connections.get(saved.id).jdbcUrl);
        assertEquals("property-secret", connections.get(saved.id).properties.get("customCredential"));
        String disk = new String(Files.readAllBytes(paths.configDir().resolve("connections-v2.enc")), StandardCharsets.UTF_8);
        assertFalse(disk.contains("field-secret"));
        assertFalse(disk.contains("url-secret"));
        ConnectionService restarted = new ConnectionService(paths, mapper, drivers);
        assertEquals(initial.jdbcUrl, restarted.get(saved.id).jdbcUrl);
        saved.jdbcUrl += ";MODE=Oracle";
        assertThrows(AppException.class, () -> connections.save(saved));
    }

    @Test void savedDriverReferencesBlockDeletionUntilConnectionRemoved() {
        ConnectionProfile saved = connections.save(request("jdbc:h2:mem:reference"));
        assertTrue(assertThrows(AppException.class, () -> drivers.delete(h2.id)).getMessage().contains("已保存的连接"));
        connections.delete(saved.id);
        drivers.delete(h2.id);
        assertTrue(drivers.list().isEmpty());
    }

    @Test void migrationPreservesRawAliasUnknownTypeAndBacksUpExactBytesOnce() throws Exception {
        List<Map<String, Object>> old = Arrays.asList(legacy("pg", "POSTGRESQL", "jdbc:postgresql://localhost/test"),
                legacy("range", "RANGE", "jdbc:custom:test"), legacy("gauss", "GAUSSDB", null));
        writeLegacy(old);
        byte[] encrypted = Files.readAllBytes(paths.configDir().resolve("datasources.enc"));
        byte[] key = Files.readAllBytes(paths.keyFile());
        List<ConnectionProfile> migrated = connections.list();
        assertEquals(3, migrated.size());
        assertEquals("POSTGRESQL", connections.get("pg").legacyType);
        assertEquals("postgresql", connections.get("pg").dialectHint);
        assertEquals("RANGE", connections.get("range").legacyType);
        assertEquals("generic", connections.get("range").dialectHint);
        assertEquals("jdbc:gaussdb://localhost:8000/test", connections.get("gauss").jdbcUrl);
        assertTrue(migrated.get(0).needsDriver);
        assertNull(migrated.get(0).password);
        assertEquals("legacy-secret", connections.get("pg").password);
        assertArrayEquals(encrypted, Files.readAllBytes(paths.configDir().resolve("datasources.enc")));
        assertArrayEquals(encrypted, Files.readAllBytes(temporary.resolve("migration-backups/legacy-v1/datasources.enc")));
        assertArrayEquals(key, Files.readAllBytes(temporary.resolve("migration-backups/legacy-v1/master.key")));
        assertEquals(3, new ConnectionService(paths, mapper, drivers).list().size());
        assertEquals(true, connections.migrationStatus().get("migrated"));
        assertThrows(AppException.class, () -> connections.open("pg"));
    }

    @Test void corruptedLegacyAndMissingKeysFailWithoutOverwritingOrStartingEmpty() throws Exception {
        Files.createDirectories(paths.configDir());
        Files.write(paths.configDir().resolve("datasources.enc"), new byte[]{1, 2, 3});
        assertTrue(assertThrows(AppException.class, () -> connections.list()).getMessage().contains("密钥"));
        assertFalse(Files.exists(paths.keyFile()));
        assertFalse(Files.exists(paths.configDir().resolve("connections-v2.enc")));
        Files.write(paths.keyFile(), "AAAAAAAAAAAAAAAAAAAAAA==".getBytes(StandardCharsets.UTF_8));
        assertThrows(AppException.class, () -> connections.list());
        assertFalse(Files.exists(paths.configDir().resolve("connections-v2.enc")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(paths.configDir().resolve("datasources.enc")));
    }

    @Test void corruptV2AndMissingV2KeyAreNotTreatedAsEmptyConfig() throws Exception {
        connections.save(request("jdbc:h2:mem:corruption"));
        Path file = paths.configDir().resolve("connections-v2.enc");
        Files.write(file, new byte[]{1, 2});
        assertThrows(AppException.class, () -> connections.list());
        Files.delete(paths.keyFile());
        assertTrue(assertThrows(AppException.class, () -> connections.list()).getMessage().contains("密钥"));
        assertFalse(Files.exists(paths.keyFile()));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(file));
    }

    @Test void zeroLengthEncryptedFilesAreCorruptionRatherThanEmptyLists() throws Exception {
        writeLegacy(new ArrayList<Map<String, Object>>());
        Files.write(paths.configDir().resolve("datasources.enc"), new byte[0]);
        assertTrue(assertThrows(AppException.class, () -> connections.list()).getMessage().contains("文件为空"));
        assertFalse(Files.exists(paths.configDir().resolve("connections-v2.enc")));
        Files.delete(paths.configDir().resolve("datasources.enc"));
        connections.save(request("jdbc:h2:mem:empty"));
        Files.write(paths.configDir().resolve("connections-v2.enc"), new byte[0]);
        assertTrue(assertThrows(AppException.class, () -> connections.list()).getMessage().contains("文件为空"));
    }

    @Test void oldDriverWithoutSchemaApiIsClosedWhenSchemaInitializationFails() throws Exception {
        DriverService stubDrivers = org.mockito.Mockito.spy(drivers);
        Connection physical = org.mockito.Mockito.mock(Connection.class);
        org.mockito.Mockito.doThrow(new AbstractMethodError("old JDBC implementation")).when(physical).setSchema("PUBLIC");
        org.mockito.Mockito.doReturn(physical).when(stubDrivers).open(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(java.util.Properties.class));
        ConnectionService configured = new ConnectionService(paths, mapper, stubDrivers);
        ConnectionProfile config = request("jdbc:h2:mem:old-driver");
        config.schema = "PUBLIC";
        String id = configured.save(config).id;
        assertTrue(assertThrows(AppException.class, () -> configured.open(id)).getMessage().contains("schema"));
        org.mockito.Mockito.verify(physical).close();
    }

    private ConnectionProfile request(String url) {
        ConnectionProfile request = new ConnectionProfile();
        request.name = "test";
        request.driverId = h2.id;
        request.jdbcUrl = url;
        request.username = "sa";
        request.dialectHint = "generic";
        return request;
    }
    private Map<String, Object> legacy(String id, String type, String url) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", id); value.put("name", id); value.put("type", type); value.put("jdbcUrl", url);
        value.put("password", "legacy-secret"); value.put("host", "localhost"); value.put("databaseName", "test");
        Map<String, String> params = new LinkedHashMap<String, String>(); params.put("sslPassword", "ssl-secret"); value.put("params", params);
        return value;
    }
    private void writeLegacy(List<Map<String, Object>> profiles) {
        EncryptedJsonFileStore<List<Map<String, Object>>> legacy = new EncryptedJsonFileStore<List<Map<String, Object>>>(
                paths.configDir().resolve("datasources.enc"), paths.keyFile(), mapper, new TypeReference<List<Map<String, Object>>>() { }, ArrayList::new);
        legacy.write(profiles);
    }
}
