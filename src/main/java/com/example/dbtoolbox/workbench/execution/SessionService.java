package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
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
    public static class Session {
        public String id, connectionId, dialect, schema, catalog;
        public volatile String state = "OPEN";
        public volatile boolean autoCommit = true;
        @JsonIgnore public Connection connection;
        @JsonIgnore public final AtomicBoolean busy = new AtomicBoolean();
        @JsonIgnore public volatile long touched = System.currentTimeMillis();
        @JsonIgnore public volatile boolean closing;
        @JsonIgnore public volatile long revision;
    }
    private final ConnectionService connections;
    private final Map<String,Session> sessions = new ConcurrentHashMap<String,Session>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"session-reaper"); t.setDaemon(true); return t; });
    public SessionService(ConnectionService connections) {
        this.connections = connections;
        reaper.scheduleWithFixedDelay(() -> {
            for (Session s : sessions.values()) if (!s.busy.get() && System.currentTimeMillis()-s.touched > 30*60*1000L) try { close(s.id); } catch (Exception ignored) { }
        }, 60,60,TimeUnit.SECONDS);
    }
    public synchronized Session create(String connectionId, String schema, String catalog) {
        if (sessions.size() >= 8) throw new AppException("最多同时打开 8 个数据库会话，请先关闭不用的标签");
        Connection c = connections.open(connectionId);
        try {
            if (catalog != null && !catalog.isEmpty()) c.setCatalog(catalog);
            if (schema != null && !schema.isEmpty()) c.setSchema(schema);
            Session s = new Session(); s.id=UUID.randomUUID().toString(); s.connectionId=connectionId; s.connection=c;
            ConnectionProfile profile = connections.get(connectionId);
            s.dialect=SqlDialect.detect(c,profile.dialectHint); s.autoCommit=c.getAutoCommit();
            s.catalog=c.getCatalog();
            try { s.schema=c.getSchema(); } catch (SQLException | AbstractMethodError ignored) { s.schema=schema; }
            sessions.put(s.id,s); return s;
        } catch (Exception | LinkageError ex) { try { c.close(); } catch (SQLException ignored) { } throw new AppException("创建会话失败: "+safe(ex)); }
    }
    public Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new AppException("会话已关闭，请重新连接");
        s.touched=System.currentTimeMillis(); return s;
    }
    public Session acquire(String id) {
        Session s=get(id);
        if (s.closing || "BROKEN".equals(s.state)) throw new AppException("会话不可用，请重新连接");
        if (!s.busy.compareAndSet(false,true)) throw new AppException("此标签正在执行，请等待完成或取消");
        if (s.closing) { s.busy.set(false); throw new AppException("会话正在关闭"); }
        s.state="RUNNING"; return s;
    }
    public void release(Session s) {
        try {
            if (s.connection.isClosed()) s.state="BROKEN";
            else {
                s.autoCommit=s.connection.getAutoCommit();
                try{s.catalog=s.connection.getCatalog();}catch(SQLException ignored){}
                try{s.schema=s.connection.getSchema();}catch(SQLException|AbstractMethodError ignored){}
                if (!"BROKEN".equals(s.state)) s.state=s.autoCommit?"OPEN":"TX_PENDING";
            }
        } catch (SQLException ex) { s.state="BROKEN"; }
        s.touched=System.currentTimeMillis(); s.busy.set(false);
    }
    public Session transaction(String id, String action, boolean autoCommit) {
        Session s=acquire(id);
        try {
            if ("COMMIT".equals(action)) s.connection.commit();
            else if ("ROLLBACK".equals(action)) s.connection.rollback();
            else if ("AUTO_COMMIT".equals(action)) {
                if (autoCommit && !s.connection.getAutoCommit()) s.connection.rollback();
                s.connection.setAutoCommit(autoCommit);
            } else throw new AppException("不支持的事务操作");
            return s;
        } catch (SQLException ex) { throw new AppException("事务操作失败: "+safe(ex)); }
        finally { s.revision++;release(s); }
    }
    public synchronized void close(String id) {
        Session s=get(id);
        if (!s.busy.compareAndSet(false,true)) throw new AppException("请先取消执行，再关闭会话");
        s.closing=true;
        SQLException rollbackFailure=null;
        try { if (!s.connection.isClosed() && !s.connection.getAutoCommit()) s.connection.rollback(); }
        catch (SQLException ex) { rollbackFailure=ex; }
        try {
            s.connection.close();
            if(!s.connection.isClosed())throw new SQLException("驱动尚未确认连接关闭");
            sessions.remove(id);s.state="CLOSED";
        } catch(SQLException ex) {
            s.state="BROKEN";
            throw new AppException("关闭连接失败，会话已禁用，可再次尝试关闭: "+safe(ex));
        } finally { s.busy.set(false); }
        if(rollbackFailure!=null)throw new AppException("连接已关闭，但回滚未确认，请核实事务结果: "+safe(rollbackFailure));
    }
    public void breakSession(Session s) {
        s.closing=true; s.state="BROKEN";
        try { s.connection.abort(Runnable::run); } catch (Throwable ex) { try { s.connection.close(); } catch (SQLException ignored) { } }
    }
    @PreDestroy public void shutdown() {
        reaper.shutdownNow();
        for (Session s:sessions.values()) {
            try { if (!s.connection.getAutoCommit()) s.connection.rollback(); } catch (SQLException ignored) { }
            try { s.connection.close(); } catch (SQLException ignored) { }
        }
        sessions.clear();
    }
    public static String safe(Throwable ex) {
        String message=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
        return message.replaceAll("(?i)(password|passwd|pwd|token|secret)(\\s*[=:]\\s*)[^\\s;&]+", "$1$2***");
    }
}
