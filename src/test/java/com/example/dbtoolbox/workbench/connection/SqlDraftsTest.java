package com.example.dbtoolbox.workbench.connection;

import com.example.dbtoolbox.common.*;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.example.dbtoolbox.workbench.driver.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftsTest {
    @TempDir Path temporary;
    ObjectMapper mapper = new ObjectMapper();
    StoragePaths paths;
    DriverService drivers;
    ConnectionService connections;
    ConnectionProfile profile;

    @BeforeEach void setup() throws Exception {
        ToolboxProperties properties = new ToolboxProperties(); properties.setStorageRoot(temporary.toString());
        paths = new StoragePaths(properties); drivers = new DriverService(paths, mapper);
        connections = new ConnectionService(paths, mapper, drivers);
        Path jar = Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        DriverProfile h2 = drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files", "h2.jar", "application/java-archive", Files.readAllBytes(jar))}, "Draft fixture", null);
        profile = new ConnectionProfile(); profile.name = "Draft fixture"; profile.driverId = h2.id;
        profile.jdbcUrl = "jdbc:h2:mem:drafts"; profile.username = "sa";
        profile = connections.save(profile);
    }
    @AfterEach void cleanup() { drivers.shutdown(); }
    void enable() { profile.saveSqlDrafts = true; profile = connections.save(profile); }
    SqlDrafts.Update update(String sql) {
        SqlDrafts.Update update = new SqlDrafts.Update(); update.revision = connections.sqlDrafts(profile.id).revision;
        update.requestId = UUID.randomUUID().toString(); SqlDrafts.Draft tab = new SqlDrafts.Draft();
        tab.id = UUID.randomUUID().toString(); tab.name = "Example.sql"; tab.sql = sql; update.tabs.add(tab); return update;
    }
    Path file() { return paths.configDir().resolve("sql-drafts.enc"); }

    @Test void disabledByDefaultAndNeverCreatesDraftStorage() throws Exception {
        assertFalse(profile.saveSqlDrafts);
        assertFalse(mapper.readValue("{\"name\":\"old profile\"}", ConnectionProfile.class).saveSqlDrafts);
        assertThrows(AppException.class, () -> connections.sqlDrafts(profile.id));
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, new SqlDrafts.Update()));
        assertFalse(Files.exists(file()));
    }
    @Test void encryptedTextRestoresWithoutSessionOrResultData() throws Exception {
        enable(); String sql = "SELECT 'synthetic-private-value', NULL; -- 中文";
        SqlDrafts.Workspace saved = connections.saveSqlDrafts(profile.id, update(sql));
        assertFalse(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8).contains("synthetic-private-value"));
        assertFalse(mapper.writeValueAsString(connections.list()).contains("synthetic-private-value"));
        assertNull(profile.sqlDraftEpoch);
        ConnectionService restarted = new ConnectionService(paths, mapper, drivers);
        SqlDrafts.Workspace restored = restarted.sqlDrafts(profile.id);
        assertEquals(saved.revision, restored.revision); assertEquals(sql, restored.tabs.get(0).sql);
        Set<String> fields = new HashSet<String>(); mapper.valueToTree(restored.tabs.get(0)).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<String>(Arrays.asList("id", "name", "sql")), fields);
        if (Files.getFileStore(file()).supportsFileAttributeView("posix"))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
    }
    @Test void retriesAreIdempotentAndConcurrentPagesCannotOverwrite() {
        enable(); SqlDrafts.Update first = update("SELECT 1"), stale = update("SELECT 2");
        SqlDrafts.Workspace saved = connections.saveSqlDrafts(profile.id, first);
        assertEquals(saved.revision, connections.saveSqlDrafts(profile.id, first).revision);
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, stale));
        first.tabs.get(0).sql = "SELECT 3";
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, first));
        assertEquals("SELECT 1", connections.sqlDrafts(profile.id).tabs.get(0).sql);
    }
    @Test void disableAndDeleteClearOnlyThatConnectionsDraftsAndRejectOldGeneration() {
        enable(); SqlDrafts.Update old = update("SELECT 'first'"); connections.saveSqlDrafts(profile.id, old);
        ConnectionProfile second = new ConnectionProfile(); second.name = "Other"; second.driverId = profile.driverId;
        second.jdbcUrl = profile.jdbcUrl; second.saveSqlDrafts = true; second = connections.save(second);
        SqlDrafts.Update other = new SqlDrafts.Update(); other.revision = connections.sqlDrafts(second.id).revision;
        other.requestId = UUID.randomUUID().toString(); other.tabs = old.tabs;
        connections.saveSqlDrafts(second.id, other);
        profile.saveSqlDrafts = false; profile = connections.save(profile);
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, old));
        enable(); assertTrue(connections.sqlDrafts(profile.id).tabs.isEmpty());
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, old));
        assertEquals(1, connections.sqlDrafts(second.id).tabs.size());
        connections.delete(second.id);
        assertTrue(new SqlDrafts(paths, mapper).read(second.id, UUID.randomUUID().toString()).tabs.isEmpty());
    }
    @Test void optInEpochRejectsDelayedFirstSaveAcrossDisableAndReenable() {
        enable(); SqlDrafts.Update old = update("SELECT 'stale first save'");
        profile.saveSqlDrafts = false; profile = connections.save(profile); enable();
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, old));
        assertTrue(connections.sqlDrafts(profile.id).tabs.isEmpty());
    }
    @Test void limitsAndDuplicateIdsRejectWithoutReplacingPreviousDraft() {
        enable(); connections.saveSqlDrafts(profile.id, update("SELECT 'keep'"));
        SqlDrafts.Update huge = update(String.join("", Collections.nCopies(SqlDrafts.MAX_SQL + 1, "x")));
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, huge));
        SqlDrafts.Update duplicate = update("SELECT 2"); duplicate.tabs.add(duplicate.tabs.get(0));
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, duplicate));
        SqlDrafts.Update tooMany = update("SELECT 3");
        for (int i = 0; i < 20; i++) { SqlDrafts.Draft t = new SqlDrafts.Draft(); t.id = UUID.randomUUID().toString(); t.name = "tab"; t.sql = "x"; tooMany.tabs.add(t); }
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, tooMany));
        assertEquals("SELECT 'keep'", connections.sqlDrafts(profile.id).tabs.get(0).sql);
    }
    @Test void totalLimitRejectsWithoutReplacingExistingText() {
        enable(); connections.saveSqlDrafts(profile.id, update("SELECT 'keep'"));
        SqlDrafts.Update oversized = update("x"); oversized.tabs.clear();
        String text = String.join("", Collections.nCopies(SqlDrafts.MAX_SQL, "x"));
        for (int i = 0; i < 9; i++) {
            SqlDrafts.Draft draft = new SqlDrafts.Draft(); draft.id = UUID.randomUUID().toString();
            draft.name = "tab"; draft.sql = text; oversized.tabs.add(draft);
        }
        assertThrows(AppException.class, () -> connections.saveSqlDrafts(profile.id, oversized));
        assertEquals("SELECT 'keep'", connections.sqlDrafts(profile.id).tabs.get(0).sql);
    }
    @Test void corruptDraftFileIsPreservedAndOptOutDoesNotClaimSuccess() throws Exception {
        enable(); connections.saveSqlDrafts(profile.id, update("SELECT 1"));
        byte[] bytes = Files.readAllBytes(file()); bytes[bytes.length - 1] ^= 1; Files.write(file(), bytes);
        profile.saveSqlDrafts = false;
        assertThrows(AppException.class, () -> connections.save(profile));
        assertTrue(connections.list().get(0).saveSqlDrafts);
        assertArrayEquals(bytes, Files.readAllBytes(file()));
    }
    @Test void closingAllTabsStoresAnEmptyWorkspace() {
        enable(); connections.saveSqlDrafts(profile.id, update("SELECT 1"));
        SqlDrafts.Update empty = update(""); empty.tabs.clear(); connections.saveSqlDrafts(profile.id, empty);
        assertTrue(connections.sqlDrafts(profile.id).tabs.isEmpty());
    }
}
