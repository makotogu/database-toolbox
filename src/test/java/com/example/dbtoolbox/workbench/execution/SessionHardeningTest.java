package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.workbench.connection.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionHardeningTest {
    ConnectionService service(Connection c)throws Exception{
        ConnectionService cs=mock(ConnectionService.class);ConnectionProfile p=new ConnectionProfile();p.dialectHint="generic";
        when(cs.open(anyString())).thenReturn(c);when(cs.get(anyString())).thenReturn(p);return cs;
    }
    Connection fixture(AtomicBoolean closed)throws Exception{
        Connection c=mock(Connection.class);when(c.getAutoCommit()).thenReturn(true);when(c.isClosed()).thenAnswer(i->closed.get());
        doAnswer(i->{closed.set(true);return null;}).when(c).close();return c;
    }
    void await(java.util.function.BooleanSupplier done)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(done.getAsBoolean());
    }
    @Test void onlyConfirmedClosedAndReleasedSessionsLeaveQuota()throws Exception{
        AtomicBoolean closed=new AtomicBoolean();Connection c=fixture(closed);doAnswer(i->{closed.set(true);return null;}).when(c).abort(any(Executor.class));
        SessionService ss=new SessionService(service(c));
        try{
            List<SessionService.Session> all=new ArrayList<>();for(int i=0;i<8;i++)all.add(ss.create("fixture",null,null));
            SessionService.Session s=ss.acquire(all.get(0).id);ss.breakSession(s);
            assertFalse(s.resourceReleased);assertThrows(AppException.class,()->ss.create("fixture",null,null));
            ss.release(s);assertTrue(s.resourceReleased);assertSame(s,ss.get(s.id));assertEquals("BROKEN",s.state);
            closed.set(false);ss.create("fixture",null,null);assertThrows(AppException.class,()->ss.acquire(s.id));
        }finally{ss.shutdown();}
    }
    @Test void unsuccessfulAbortDoesNotFreeCapacityAndRecoveryClosesAsynchronously()throws Exception{
        AtomicBoolean closed=new AtomicBoolean();Connection c=fixture(closed);SessionService ss=new SessionService(service(c));
        CountDownLatch closeEntered=new CountDownLatch(1),allowClose=new CountDownLatch(1);
        try{
            List<SessionService.Session> all=new ArrayList<>();for(int i=0;i<8;i++)all.add(ss.create("fixture",null,null));
            SessionService.Session s=ss.acquire(all.get(0).id);ss.breakSession(s);ss.release(s);
            assertFalse(s.resourceReleased);assertThrows(AppException.class,()->ss.create("fixture",null,null));
            doAnswer(i->{closeEntered.countDown();assertTrue(allowClose.await(2,TimeUnit.SECONDS));closed.set(true);return null;}).when(c).close();
            assertSame(s,ss.recover(s.id));assertTrue(closeEntered.await(1,TimeUnit.SECONDS));assertTrue(s.recoveryPending);assertFalse(s.resourceReleased);
            ss.recover(s.id);allowClose.countDown();await(()->s.resourceReleased&&!s.recoveryPending);assertSame(s,ss.get(s.id));verify(c,times(1)).close();
        }finally{allowClose.countDown();ss.shutdown();}
    }
    @Test void shutdownRollsBackIdleH2ManualTransaction()throws Exception{
        String url="jdbc:h2:mem:shutdown_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(Connection observer=new org.h2.Driver().connect(url,new Properties());Connection c=new org.h2.Driver().connect(url,new Properties())){
            observer.createStatement().execute("CREATE TABLE T(ID INT)");SessionService ss=new SessionService(service(c));
            SessionService.Session s=ss.create("fixture",null,null);ss.transaction(s.id,"AUTO_COMMIT",false);c.createStatement().execute("INSERT INTO T VALUES(1)");ss.shutdown();
            assertTrue(c.isClosed());try(ResultSet rs=observer.createStatement().executeQuery("SELECT COUNT(*) FROM T")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
        }
    }
    @Test @Timeout(15) void shutdownDoesNotRollbackOrCloseWhileWorkerIgnoresCancellation()throws Exception{
        AtomicBoolean closed=new AtomicBoolean();Connection c=fixture(closed);Statement st=mock(Statement.class);when(c.createStatement()).thenReturn(st);
        CountDownLatch entered=new CountDownLatch(1),exit=new CountDownLatch(1);AtomicBoolean executing=new AtomicBoolean();
        when(st.execute(anyString())).thenAnswer(i->{executing.set(true);entered.countDown();long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(exit.getCount()>0&&System.nanoTime()<limit){try{exit.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}executing.set(false);throw new SQLException("synthetic driver exited","08006");});
        SessionService ss=new SessionService(service(c));ExecutionService es=new ExecutionService(ss,new ObjectMapper());ExecutionRecord r=null;
        try{
            SessionService.Session s=ss.create("fixture",null,null);ExecutionRequest q=new ExecutionRequest();q.sessionId=s.id;q.sql="SELECT 1";q.timeoutSeconds=30;q.confirmationToken=es.prepare(q).confirmationToken;r=es.submit(q);
            assertTrue(entered.await(2,TimeUnit.SECONDS));es.close();ss.shutdown();assertTrue(executing.get());assertEquals("OUTCOME_UNKNOWN",r.state);assertFalse(s.resourceReleased);assertTrue(s.busy.get());
            verify(c,never()).close();verify(c,never()).rollback();assertThrows(AppException.class,()->ss.create("fixture",null,null));assertThrows(AppException.class,()->es.prepare(q));
        }finally{exit.countDown();if(r!=null){final ExecutionRecord record=r;await(()->record.finishedAt>0);}es.close();ss.shutdown();}
    }
    @Test @Timeout(12) void shutdownCancelsQueuedTasksWithoutExecutingTheirSql()throws Exception{
        ConnectionService cs=mock(ConnectionService.class);ConnectionProfile p=new ConnectionProfile();p.dialectHint="generic";when(cs.get(anyString())).thenReturn(p);
        CountDownLatch started=new CountDownLatch(4),exit=new CountDownLatch(1);AtomicInteger invoked=new AtomicInteger();
        when(cs.open(anyString())).thenAnswer(i->{Connection c=fixture(new AtomicBoolean());Statement st=mock(Statement.class);when(c.createStatement()).thenReturn(st);
            when(st.execute(anyString())).thenAnswer(x->{invoked.incrementAndGet();started.countDown();exit.await(4,TimeUnit.SECONDS);throw new SQLException("synthetic canceled","57014");});
            doAnswer(x->{exit.countDown();return null;}).when(st).cancel();return c;});
        SessionService ss=new SessionService(cs);ExecutionService es=new ExecutionService(ss,new ObjectMapper());List<ExecutionRecord> records=new ArrayList<>();
        try{
            for(int i=0;i<6;i++){ExecutionRequest q=new ExecutionRequest();q.sessionId=ss.create("fixture",null,null).id;q.sql="SELECT "+i;q.timeoutSeconds=30;q.confirmationToken=es.prepare(q).confirmationToken;records.add(es.submit(q));if(i==3)assertTrue(started.await(2,TimeUnit.SECONDS));}
            for(int i=4;i<6;i++)clearInvocations(ss.get(records.get(i).sessionId).connection);
            es.close();assertEquals(4,invoked.get());
            for(int i=4;i<6;i++)verifyNoInteractions(ss.get(records.get(i).sessionId).connection);
            for(int i=4;i<6;i++){ExecutionRecord r=records.get(i);assertEquals("CANCELED",r.state);assertEquals("SKIPPED",r.statements.get(0).state);assertTrue(r.finishedAt>0);assertFalse(ss.get(r.sessionId).busy.get());}
        }finally{exit.countDown();es.close();ss.shutdown();}
    }
}
