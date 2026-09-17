package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.ErrorMessages;
import com.example.dbtoolbox.workbench.connection.ConnectionService;
import com.example.dbtoolbox.workbench.connection.ConnectionProfile;
import com.example.dbtoolbox.workbench.dialect.SqlDialect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SessionService {
    public static final int MAX_SESSIONS=8;
    public static final int CLOSE_WAIT_SECONDS=3;
    public static class Session {
        public String id, connectionId, dialect, schema, catalog;
        public volatile String state="OPEN";
        public volatile boolean autoCommit=true, resourceReleased, recoveryPending;
        @JsonIgnore public Connection connection;
        @JsonIgnore public final AtomicBoolean busy=new AtomicBoolean();
        @JsonIgnore final AtomicBoolean disconnecting=new AtomicBoolean();
        @JsonIgnore final AtomicBoolean cleanupScheduled=new AtomicBoolean();
        @JsonIgnore volatile boolean closeConfirmed;
        @JsonIgnore public volatile long touched=System.currentTimeMillis();
        @JsonIgnore public volatile boolean closing;
        @JsonIgnore public volatile long revision;
    }
    private final ConnectionService connections;
    private final Map<String,Session> sessions=new ConcurrentHashMap<String,Session>();
    // Bounded tombstones let the UI inspect a disconnected session without consuming JDBC capacity.
    private final LinkedHashMap<String,Session> retired=new LinkedHashMap<String,Session>();
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"session-reaper"));
    private final ThreadPoolExecutor cleanup=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16),r->daemon(r,"session-close"),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean stopping;
    public SessionService(ConnectionService connections) {
        this.connections=connections;
        reaper.scheduleWithFixedDelay(()->{
            for(Session s:sessions.values())if(!s.busy.get()&&System.currentTimeMillis()-s.touched>30*60*1000L) scheduleClose(s);
        },60,60,TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    public synchronized void stopAccepting(){stopping=true;}
    public synchronized Session create(String connectionId,String schema,String catalog) {
        if(stopping)throw new AppException("工作台正在退出，不再创建会话");
        if(sessions.size()>=MAX_SESSIONS)throw new AppException("最多同时打开 8 个数据库会话；尚未结束的失效会话仍占用资源，请等待或关闭标签");
        Connection c=connections.open(connectionId);
        try {
            if(catalog!=null&&!catalog.isEmpty())c.setCatalog(catalog);
            if(schema!=null&&!schema.isEmpty())c.setSchema(schema);
            Session s=new Session();s.id=UUID.randomUUID().toString();s.connectionId=connectionId;s.connection=c;
            ConnectionProfile profile=connections.get(connectionId);
            s.dialect=SqlDialect.detect(c,profile.dialectHint);s.autoCommit=c.getAutoCommit();s.catalog=c.getCatalog();
            try{s.schema=c.getSchema();}catch(SQLException|AbstractMethodError ignored){s.schema=schema;}
            sessions.put(s.id,s);return s;
        }catch(Exception|LinkageError ex){try{c.close();}catch(SQLException ignored){}throw new AppException("创建会话失败: "+safe(ex));}
    }
    public Session get(String id) {
        Session s=sessions.get(id);
        if(s==null)synchronized(this){s=retired.get(id);}
        if(s==null)throw new AppException(org.springframework.http.HttpStatus.NOT_FOUND,"会话已关闭，请重新连接");
        s.touched=System.currentTimeMillis();return s;
    }
    public Session acquire(String id) {
        if(stopping)throw new AppException("工作台正在退出，不再接受操作");
        Session s=get(id);
        if(s.closing||s.resourceReleased||"BROKEN".equals(s.state))throw new AppException("会话不可用，请重新连接");
        if(!s.busy.compareAndSet(false,true))throw new AppException("此标签正在执行，请等待完成或取消");
        if(stopping||s.closing){s.busy.set(false);throw new AppException("会话正在关闭");}
        s.state="RUNNING";return s;
    }
    void releaseUnexecuted(Session s) {
        // A drained queue entry never changed JDBC state; shutdown must not call into its driver here.
        if(!s.closing)s.state=s.autoCommit?"OPEN":"TX_PENDING";
        s.touched=System.currentTimeMillis();s.busy.set(false);retireIfClosed(s);
    }
    public void release(Session s) {
        try {
            if(s.disconnecting.get())s.state="BROKEN";
            else if(s.connection.isClosed()){s.closeConfirmed=true;s.state="BROKEN";}
            else if(!s.closing){
                s.autoCommit=s.connection.getAutoCommit();
                try{s.catalog=s.connection.getCatalog();}catch(SQLException ignored){}
                try{s.schema=s.connection.getSchema();}catch(SQLException|AbstractMethodError ignored){}
                if(!"BROKEN".equals(s.state))s.state=s.autoCommit?"OPEN":"TX_PENDING";
            }
        }catch(SQLException ex){s.state="BROKEN";}
        finally{s.touched=System.currentTimeMillis();s.busy.set(false);retireIfClosed(s);}
    }
    private synchronized void retireIfClosed(Session s) {
        if(!s.closeConfirmed||s.busy.get()||s.disconnecting.get()||s.cleanupScheduled.get())return;
        if(!sessions.remove(s.id,s))return;
        s.resourceReleased=true;s.recoveryPending=false;retired.put(s.id,s);
        while(retired.size()>16)retired.remove(retired.keySet().iterator().next());
    }
    public Session transaction(String id,String action,boolean autoCommit) {
        Session s=acquire(id);
        try {
            if("COMMIT".equals(action))s.connection.commit();
            else if("ROLLBACK".equals(action))s.connection.rollback();
            else if("AUTO_COMMIT".equals(action)){
                if(autoCommit&&!s.connection.getAutoCommit())s.connection.rollback();
                s.connection.setAutoCommit(autoCommit);
            }else throw new AppException("不支持的事务操作");
            return s;
        }catch(SQLException ex){throw new AppException("事务操作失败: "+safe(ex));}
        finally{s.revision++;release(s);}
    }
    public void close(String id) {
        Session s;
        synchronized(this){s=retired.remove(id);if(s!=null){s.state="CLOSED";return;}}
        s=sessions.get(id);
        if(s==null)return; // DELETE is idempotent, including expired closed-session tombstones.
        if(s.disconnecting.get()||!s.busy.compareAndSet(false,true))throw new AppException("请等待取消/关闭完成，再关闭会话");
        s.closing=true;
        SQLException rollbackFailure=null;
        try {
            try{if(!s.connection.isClosed()&&!s.connection.getAutoCommit())s.connection.rollback();}catch(SQLException ex){rollbackFailure=ex;}
            s.connection.close();
            if(!s.connection.isClosed())throw new SQLException("驱动尚未确认连接关闭");
            s.closeConfirmed=true;s.state="CLOSED";
            sessions.remove(id,s);s.resourceReleased=true;
        }catch(SQLException ex){s.state="BROKEN";throw new AppException("关闭连接失败，会话已禁用，可再次尝试关闭: "+safe(ex));}
        finally{s.busy.set(false);}
        if(rollbackFailure!=null)throw new AppException("连接已关闭，但回滚未确认，请核实事务结果: "+safe(rollbackFailure));
    }
    /** Recovery never opens a replacement connection or resubmits SQL. Poll resourceReleased first. */
    public Session recover(String id) {
        Session s=get(id);
        if(s.resourceReleased)return s;
        if(!"BROKEN".equals(s.state))throw new AppException("仅可恢复失效会话");
        if(stopping)throw new AppException("工作台正在退出");
        s.recoveryPending=true;
        if(s.busy.get())requestBreak(s);else scheduleClose(s);
        return s;
    }
    private void scheduleClose(Session s) {
        if (!s.cleanupScheduled.compareAndSet(false, true)) return;
        try {
            cleanup.execute(() -> {
                try {
                    close(s.id);
                } catch (AppException ex) {
                    ErrorMessages.diagnostic("session-close-failed", ex);
                } finally {
                    s.cleanupScheduled.set(false);
                    s.recoveryPending = false;
                    if (s.resourceReleased) {
                        synchronized (this) {
                            retired.put(s.id, s);
                            while (retired.size() > 16) retired.remove(retired.keySet().iterator().next());
                        }
                    } else {
                        retireIfClosed(s);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            s.cleanupScheduled.set(false);
            s.recoveryPending = false;
        }
    }
    public void requestBreak(Session s) {
        s.closing=true;s.state="BROKEN";s.recoveryPending=true;
        if(!s.disconnecting.compareAndSet(false,true))return;
        try{cleanup.execute(()->abort(s));}
        catch(RejectedExecutionException ex){s.disconnecting.set(false);s.recoveryPending=false;}
    }
    /** Synchronous seam for tests and a caller already on a dedicated disconnect worker. */
    public void breakSession(Session s) {
        s.closing=true;s.state="BROKEN";
        if(s.disconnecting.compareAndSet(false,true))abort(s);
    }
    private void abort(Session s) {
        try {
            s.connection.abort(Runnable::run);
            s.closeConfirmed = s.connection.isClosed();
        } catch (Throwable ex) {
            // close/rollback must never race a still-running JDBC operation if abort is unsupported.
            if (!s.busy.get()) {
                try {
                    s.connection.close();
                    s.closeConfirmed = s.connection.isClosed();
                } catch (SQLException ignored) { }
            }
            ErrorMessages.diagnostic("session-abort-failed", ex);
        } finally {
            s.disconnecting.set(false);
            s.recoveryPending = false;
            retireIfClosed(s);
        }
    }
    @PreDestroy public void shutdown() {
        stopAccepting();
        reaper.shutdownNow();
        for (Session s : sessions.values()) {
            if (s.busy.get()) requestBreak(s);
            else scheduleClose(s);
        }
        cleanup.shutdown();
        try {
            if (!cleanup.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) cleanup.shutdownNow();
        } catch (InterruptedException ex) {
            cleanup.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Unconfirmed/busy resources stay tracked. Driver leases must not be disposed beneath workers.
    }
    public static String safe(Throwable ex){return ErrorMessages.safe(ex);}
}
