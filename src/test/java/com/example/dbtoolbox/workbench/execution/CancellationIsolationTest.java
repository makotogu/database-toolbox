package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.workbench.connection.ConnectionProfile;
import com.example.dbtoolbox.workbench.connection.ConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CancellationIsolationTest {
    @Test
    @Timeout(12)
    void twoBlockedDriverCancelsCannotPreventIndependentConnectionAbort() throws Exception {
        ConnectionService connections = mock(ConnectionService.class);
        SessionService sessions = new SessionService(connections);
        ExecutionService executions = new ExecutionService(sessions, new ObjectMapper());
        CountDownLatch bothExecuting = new CountDownLatch(2);
        CountDownLatch bothCancelEntered = new CountDownLatch(2);
        CountDownLatch bothAborted = new CountDownLatch(2);
        CountDownLatch allowCancelReturn = new CountDownLatch(1);
        CountDownLatch[] endExecution = {new CountDownLatch(1), new CountDownLatch(1)};
        AtomicInteger cancelReturned = new AtomicInteger();
        Connection[] physical = new Connection[2];
        Statement[] statements = new Statement[2];
        ExecutionRecord[] records = new ExecutionRecord[2];
        SessionService.Session[] opened = new SessionService.Session[2];
        try {
            for (int i = 0; i < 2; i++) {
                final int index = i;
                AtomicBoolean closed = new AtomicBoolean();
                physical[i] = mock(Connection.class);
                statements[i] = mock(Statement.class);
                when(physical[i].getAutoCommit()).thenReturn(true);
                when(physical[i].isClosed()).thenAnswer(invocation -> closed.get());
                when(physical[i].createStatement()).thenReturn(statements[i]);
                when(statements[i].execute(anyString())).thenAnswer(invocation -> {
                    bothExecuting.countDown();
                    if (!endExecution[index].await(8, TimeUnit.SECONDS)) throw new SQLException("fixture execution timed out", "57014");
                    throw new SQLException("connection aborted by fallback", "08006");
                });
                doAnswer(invocation -> {
                    bothCancelEntered.countDown();
                    // A deliberately stuck vendor cancel occupies both sql-cancel workers.
                    allowCancelReturn.await(8, TimeUnit.SECONDS);
                    cancelReturned.incrementAndGet();
                    return null;
                }).when(statements[i]).cancel();
                doAnswer(invocation -> {
                    closed.set(true);
                    bothAborted.countDown();
                    endExecution[index].countDown();
                    return null;
                }).when(physical[i]).abort(any(Executor.class));
                ConnectionProfile profile = new ConnectionProfile();
                profile.dialectHint = "generic";
                String connectionId = "fixture-" + i;
                when(connections.open(connectionId)).thenReturn(physical[i]);
                when(connections.get(connectionId)).thenReturn(profile);
                opened[i] = sessions.create(connectionId, null, null);
                ExecutionRequest request = new ExecutionRequest();
                request.sessionId = opened[i].id;
                request.sql = "SELECT 1";
                request.timeoutSeconds = 30;
                request.confirmationToken = executions.prepare(request).confirmationToken;
                records[i] = executions.submit(request);
            }
            assertTrue(bothExecuting.await(2, TimeUnit.SECONDS), "Both SQL workers should enter the fake driver");
            for (ExecutionRecord record : records) {
                executions.cancel(record.id);
                executions.cancel(record.id); // Repeated cancel must not add a second driver request.
            }
            assertTrue(bothCancelEntered.await(2, TimeUnit.SECONDS), "Both cancel workers should be blocked inside the driver");
            for (ExecutionRecord record : records) {
                assertEquals("CANCEL_REQUESTED", record.state);
                assertEquals(0L, record.finishedAt);
            }
            assertTrue(bothAborted.await(5, TimeUnit.SECONDS), "Abort must run while both cancel workers remain blocked");
            assertEquals(0, cancelReturned.get(), "Fallback must not depend on Statement.cancel returning");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((records[0].finishedAt == 0 || records[1].finishedAt == 0) && System.nanoTime() < deadline) Thread.sleep(5);
            for (int i = 0; i < 2; i++) {
                assertTrue(records[i].finishedAt > 0, "Execution should terminate after forced disconnect");
                assertEquals("OUTCOME_UNKNOWN", records[i].state);
                assertEquals("BROKEN", opened[i].state);
                verify(physical[i], times(1)).abort(any(Executor.class));
                verify(statements[i], times(1)).cancel();
            }
        } finally {
            // Every blocking fake has a bounded wait and is explicitly released even on assertion failure.
            allowCancelReturn.countDown();
            for (CountDownLatch latch : endExecution) latch.countDown();
            executions.close();
            sessions.shutdown();
        }
    }
}
