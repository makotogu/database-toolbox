package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.example.dbtoolbox.workbench.connection.ConnectionProfile;
import com.example.dbtoolbox.workbench.connection.ConnectionService;
import com.example.dbtoolbox.workbench.driver.DriverProfile;
import com.example.dbtoolbox.workbench.driver.DriverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class SessionLifecycleTest {
    @TempDir Path temporary;

    @Test void closeFailureKeepsBrokenSessionAndItsDriverLeaseUntilRetrySucceeds() throws Exception {
        ToolboxProperties configuration = new ToolboxProperties();
        configuration.setStorageRoot(temporary.toString());
        DriverService drivers = new DriverService(new StoragePaths(configuration), new ObjectMapper());
        ConnectionService connections = mock(ConnectionService.class);
        SessionService sessions = new SessionService(connections);
        try {
            Path h2 = Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            DriverProfile driver = drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files", "h2.jar", "application/java-archive", Files.readAllBytes(h2))}, "lease fixture", null);
            // This is a real imported-driver lease; the outer wrapper models a JDBC close failure.
            Connection lease = drivers.open(driver.id, "jdbc:h2:mem:lifecycle", new Properties());
            Connection unreliable = mock(Connection.class, delegatesTo(lease));
            AtomicInteger closeAttempts = new AtomicInteger();
            doAnswer(invocation -> {
                if (closeAttempts.incrementAndGet() == 1) throw new SQLException("connection not closed", "08006");
                lease.close();
                return null;
            }).when(unreliable).close();
            ConnectionProfile profile = new ConnectionProfile();
            profile.dialectHint = "generic";
            when(connections.open("fixture")).thenReturn(unreliable);
            when(connections.get("fixture")).thenReturn(profile);
            SessionService.Session session = sessions.create("fixture", null, null);

            assertTrue(assertThrows(AppException.class, () -> sessions.close(session.id)).getMessage().contains("关闭连接失败"));
            assertSame(session, sessions.get(session.id));
            assertEquals("BROKEN", session.state);
            assertTrue(session.closing);
            assertFalse(session.busy.get());
            assertFalse(lease.isClosed());
            assertThrows(AppException.class, () -> sessions.acquire(session.id));
            assertTrue(assertThrows(AppException.class, () -> drivers.delete(driver.id)).getMessage().contains("活动会话"));

            sessions.close(session.id);
            assertEquals(2, closeAttempts.get());
            assertTrue(lease.isClosed());
            assertEquals("CLOSED", session.state);
            assertThrows(AppException.class, () -> sessions.get(session.id));
            drivers.delete(driver.id);
            assertTrue(drivers.list().isEmpty());
        } finally {
            sessions.shutdown();
            drivers.shutdown();
        }
    }

    @Test void schemaAbstractMethodErrorClosesConnectionBeforeRejectingSession() throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        Connection oldDriver = mock(Connection.class);
        when(connections.open("legacy")).thenReturn(oldDriver);
        doThrow(new AbstractMethodError("JDBC implementation predates setSchema")).when(oldDriver).setSchema("PUBLIC");
        SessionService sessions = new SessionService(connections);
        try {
            AppException failure = assertThrows(AppException.class, () -> sessions.create("legacy", "PUBLIC", null));
            assertTrue(failure.getMessage().contains("创建会话失败"));
            verify(oldDriver, times(1)).close();
            verify(connections, never()).get(anyString());
        } finally { sessions.shutdown(); }
    }
}
