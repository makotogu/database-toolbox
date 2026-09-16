package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.workbench.dialect.SqlDialect;
import com.example.dbtoolbox.workbench.execution.ExecutionRecord.*;
import com.example.dbtoolbox.workbench.execution.SessionService.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ExecutionService {
    public static class Plan {
        public List<ScriptSplitter.Unit> units;
        @com.fasterxml.jackson.annotation.JsonIgnore CellEdits.Edit cellEdit;
        @com.fasterxml.jackson.annotation.JsonIgnore String previewCatalog, previewSchema;
        public boolean confirmationRequired;
        public String confirmationToken, message;
        public List<String> warnings=new ArrayList<String>();
        @com.fasterxml.jackson.annotation.JsonIgnore public List<Object> values=new ArrayList<Object>();
        @com.fasterxml.jackson.annotation.JsonIgnore public String fingerprint, context;
        @com.fasterxml.jackson.annotation.JsonIgnore public long created=System.currentTimeMillis();
    }
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final Map<String,ExecutionRecord> executions=new ConcurrentHashMap<String,ExecutionRecord>();
    private final Map<String,Plan> plans=new ConcurrentHashMap<String,Plan>();
    private final Map<String,String> requestIds=new ConcurrentHashMap<String,String>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(16),factory("sql-worker"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timers=Executors.newScheduledThreadPool(2,factory("sql-timeout"));
    private final ExecutorService cancels=Executors.newFixedThreadPool(2,factory("sql-cancel"));
    public static final int CANCEL_GRACE_SECONDS=2;
    public static final int SHUTDOWN_WAIT_SECONDS=5;
    private volatile boolean stopping;
    public ExecutionService(SessionService sessions,ObjectMapper mapper) {
        this.sessions=sessions;this.mapper=mapper;
        timers.scheduleWithFixedDelay(this::prune,60,60,TimeUnit.SECONDS);
    }
    private static ThreadFactory factory(String name){return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};}
    public Plan prepare(ExecutionRequest request) {
        if(stopping)throw new AppException("工作台正在退出，不再接受执行");
        validate(request);
        Session session=sessions.acquire(request.sessionId);
        try {
            Plan plan=buildPlan(request,session);
            plan.fingerprint=fingerprint(request);plan.context=context(session);plan.confirmationToken=UUID.randomUUID().toString();
            prune(); if(plans.size()>=256)throw new AppException("待执行计划过多，请稍后重试");
            plans.put(plan.confirmationToken,plan);return plan;
        } catch(SQLException ex){throw new AppException("准备执行失败: "+SessionService.safe(ex));}
        finally {sessions.release(session);}
    }
    private Plan buildPlan(ExecutionRequest request,Session session) throws SQLException {
        Plan plan=new Plan();String mode=request.mode,sql=request.sql;
        if("TABLE_PREVIEW".equals(mode)) {
            plan.previewCatalog=request.catalog;plan.previewSchema=request.schema;
            String actualDialect=SqlDialect.detect(session.connection,null);
            if(Arrays.asList("H2","POSTGRESQL","MYSQL").contains(actualDialect)) {
                if(plan.previewCatalog==null||plan.previewCatalog.isEmpty())
                    plan.previewCatalog="MYSQL".equals(actualDialect)&&request.schema!=null&&!request.schema.isEmpty()?request.schema:session.connection.getCatalog();
                if("MYSQL".equals(actualDialect))plan.previewSchema=null;
                else if(plan.previewSchema==null||plan.previewSchema.isEmpty())plan.previewSchema=session.connection.getSchema();
            }
            SqlDialect.PreviewQuery query=SqlDialect.previewSql(session.connection,session.dialect,plan.previewCatalog,plan.previewSchema,request.table,request.filters,request.orderBy,request.descending,request.offset,request.limit);
            plan.units=Collections.singletonList(ScriptSplitter.block(query.sql,session.dialect));plan.values=query.params;plan.warnings.addAll(query.warnings);
        } else if("CELL_UPDATE".equals(mode)) {
            ExecutionRequest.CellChange change=request.cellChange;
            if(change==null || change.executionId==null)throw new AppException("请选择表预览中的单元格");
            ExecutionRecord source=get(change.executionId);
            if(!session.id.equals(source.sessionId)||!"TABLE_PREVIEW".equals(source.mode)||!"SUCCEEDED".equals(source.state)||source.finishedAt==0)
                throw new AppException("只能编辑当前会话已完成的表预览");
            List<Result> results=new ArrayList<Result>();for(UnitResult unit:source.statements)results.addAll(unit.results);
            if(change.result<0||change.result>=results.size())throw new AppException("预览结果不存在");
            plan.cellEdit=CellEdits.prepare(session,results.get(change.result),change);
            plan.units=Collections.singletonList(ScriptSplitter.block(plan.cellEdit.sql,session.dialect));plan.confirmationRequired=true;
        } else if("BLOCK".equals(mode)||"CALL".equals(mode)) {
            plan.units=Collections.singletonList(ScriptSplitter.block(sql,session.dialect));
            plan.confirmationRequired=true;
        } else {
            boolean backslashes=backslashEscapes(session);
            List<ScriptSplitter.Unit> units=ScriptSplitter.split(sql,session.dialect,backslashes);
            if("CURRENT".equals(mode) || ("EXPLAIN".equals(mode)&&units.size()>1)) {
                ScriptSplitter.Unit selected=null;
                for(ScriptSplitter.Unit unit:units)if(request.cursorOffset>=unit.startOffset&&request.cursorOffset<=unit.endOffset+1){selected=unit;break;}
                if(selected==null)throw new AppException("光标不在可执行语句内，请选择 SQL 后执行");
                units=Collections.singletonList(selected);
            } else if(!"SCRIPT".equals(mode)&&units.size()!=1)throw new AppException("存在多个执行单元，请使用脚本模式，或只选择一条语句");
            if("EXPLAIN".equals(mode)) {
                String input=units.get(0).sql;
                String explained=SqlDialect.explainSql(session.connection,session.dialect,input,request.analyze);
                units=Collections.singletonList(ScriptSplitter.block(explained,session.dialect));
            }
            plan.units=units;
            for(ScriptSplitter.Unit unit:units)if(ScriptSplitter.requiresConfirmation(unit.sql,session.dialect,backslashes))plan.confirmationRequired=true;
        }
        if(request.analyze)plan.confirmationRequired=true;
        plan.message=plan.confirmationRequired?"此操作可能修改数据或实际执行分析。请核对连接与下列执行内容；自动提交的已完成语句不会自动撤销。":"执行范围已准备";
        if(!session.autoCommit)plan.warnings.add("当前为手动事务；执行后请明确提交或回滚。DDL 和过程内部事务遵循数据库规则。");
        return plan;
    }
    private boolean backslashEscapes(Session session) throws SQLException {
        if("MYSQL".equals(session.dialect)) {
            try(Statement s=session.connection.createStatement()) {
                s.setQueryTimeout(5);
                try(ResultSet rs=s.executeQuery("SELECT @@sql_mode")){return rs.next()&&!String.valueOf(rs.getString(1)).toUpperCase(Locale.ROOT).contains("NO_BACKSLASH_ESCAPES");}
            }
        }
        if("POSTGRESQL".equals(session.dialect)) {
            try(Statement s=session.connection.createStatement()) {
                s.setQueryTimeout(5);
                try(ResultSet rs=s.executeQuery("SHOW standard_conforming_strings")){return rs.next()&&"off".equalsIgnoreCase(rs.getString(1));}
            }
        }
        return false;
    }
    public synchronized ExecutionRecord submit(ExecutionRequest request) {
        if(stopping)throw new AppException("工作台正在退出，不再接受执行");
        validate(request);prune();
        if(request.requestId!=null && request.requestId.length()>100)throw new AppException("请求 ID 过长");
        String key=request.sessionId+":"+request.requestId;
        String fingerprint=fingerprint(request);
        if(request.requestId!=null && requestIds.containsKey(key)) {
            String id=requestIds.get(key);ExecutionRecord previous=executions.get(id);
            if(previous!=null) {
                if(!Objects.equals(submittedFingerprints.get(id),fingerprint))throw new AppException("同一请求 ID 不能用于不同的执行内容");
                return previous;
            }
        }
        if(executions.size()>=16) {
            ExecutionRecord oldest=executions.values().stream().filter(r->r.finishedAt>0).min(Comparator.comparingLong(r->r.finishedAt)).orElse(null);
            if(oldest!=null)delete(oldest.id);else throw new AppException("执行结果缓存已满，请等待当前任务完成");
        }
        Plan plan=plans.get(request.confirmationToken==null?"":request.confirmationToken);
        if(plan==null||System.currentTimeMillis()-plan.created>10*60*1000L||!fingerprint.equals(plan.fingerprint))throw new AppException("执行内容或连接已改变，请重新准备并确认执行");
        Session session=sessions.acquire(request.sessionId);
        if(!Objects.equals(plan.context,context(session))){sessions.release(session);throw new AppException("会话上下文已改变，请重新准备执行");}
        session.revision++;
        ExecutionRecord record=new ExecutionRecord();record.id=UUID.randomUUID().toString();record.sessionId=session.id;record.mode=request.mode;
        int i=0;for(ScriptSplitter.Unit unit:plan.units) {UnitResult result=new UnitResult();result.index=++i;result.sql=unit.sql;result.startLine=unit.startLine;result.endLine=unit.endLine;record.statements.add(result);}
        executions.put(record.id,record);submittedFingerprints.put(record.id,fingerprint);
        try {
            workers.execute(new ExecutionTask(record,request,plan,session));
            plans.remove(plan.confirmationToken);
            if(request.requestId!=null)requestIds.put(key,record.id);
            return record;
        } catch(RejectedExecutionException ex) {
            executions.remove(record.id);submittedFingerprints.remove(record.id);sessions.release(session);throw new AppException("执行队列已满，请稍后重试");
        }
    }
    private final Map<String,String> submittedFingerprints=new ConcurrentHashMap<String,String>();
    private final class ExecutionTask implements Runnable {
        final ExecutionRecord record;final ExecutionRequest request;final Plan plan;final Session session;
        ExecutionTask(ExecutionRecord record,ExecutionRequest request,Plan plan,Session session){this.record=record;this.request=request;this.plan=plan;this.session=session;}
        public void run(){if(stopping)cancelBeforeStart();else ExecutionService.this.run(record,request,plan,session);}
        void cancelBeforeStart(){
            for(UnitResult unit:record.statements)unit.state="SKIPPED";
            record.cancelRequested=true;record.state="CANCELED";record.message="工作台退出，排队任务未执行";
            sessions.releaseUnexecuted(session);record.finishedAt=System.currentTimeMillis();
        }
    }
    private void run(ExecutionRecord record,ExecutionRequest request,Plan plan,Session session) {
        record.state="RUNNING";record.startedAt=System.currentTimeMillis();
        ScheduledFuture<?> deadline=null;
        ResultReader.Budget budget=new ResultReader.Budget();
        String terminalState=null;
        try {
            deadline=timers.schedule(()->requestCancellation(record,session,true),request.timeoutSeconds,TimeUnit.SECONDS);
            for(UnitResult unit:record.statements) {
                if(record.cancelRequested)break;
                long started=System.currentTimeMillis();unit.state="RUNNING";unit.warnings.addAll(plan.warnings);
                try {
                  if(plan.cellEdit!=null) {
                    CellEdits.run(session,request,record,unit,plan.cellEdit);
                  } else try(Statement statement=createStatement(session,request,unit.sql,plan)) {
                    record.activeStatement=statement;
                    if(record.cancelRequested) {unit.state="CANCELED";break;}
                    try{statement.setQueryTimeout(request.timeoutSeconds);}catch(SQLFeatureNotSupportedException ex){unit.warnings.add("驱动不支持语句超时，将使用会话截止时间");}
                    int rowLimit="TABLE_PREVIEW".equals(request.mode)?Math.min(request.limit,request.maxRows):request.maxRows;
                    try{statement.setMaxRows(rowLimit+1);}catch(SQLFeatureNotSupportedException ex){unit.warnings.add("驱动不支持行数上限，应用将限制结果收集");}
                    boolean result=statement instanceof PreparedStatement?((PreparedStatement)statement).execute():statement.execute(unit.sql);
                    // H2 exposes function OUT values through its current result cursor; consume them before advancing/closing it.
                    boolean earlyOutputs=statement instanceof CallableStatement && "H2".equals(session.dialect);
                    if(earlyOutputs)readOutputs((CallableStatement)statement,request,unit,budget);
                    ResultReader.consume(statement,result,unit,rowLimit,budget);
                    if(statement instanceof CallableStatement&&!earlyOutputs)readOutputs((CallableStatement)statement,request,unit,budget);
                    if("EXPLAIN".equals(request.mode)) for(Result r:unit.results)if("RESULT_SET".equals(r.kind))r.kind="PLAN";
                    SQLWarning warning=session.connection.getWarnings();int warningCount=0;
                    while(warning!=null&&warningCount++<50){unit.warnings.add(SessionService.safe(warning));warning=warning.getNextWarning();}
                    session.connection.clearWarnings();
                  }
                    if("TABLE_PREVIEW".equals(request.mode))for(Result r:unit.results)if("RESULT_SET".equals(r.kind))CellEdits.attach(session,plan.previewCatalog,plan.previewSchema,request.table,r);
                    unit.state="SUCCEEDED";
                } catch(Throwable ex) {
                    if(ex instanceof VirtualMachineError)throw (VirtualMachineError)ex;
                    SQLException sq=findSql(ex);
                    unit.error=new ErrorInfo(SessionService.safe(ex),sq==null?null:sq.getSQLState(),sq==null?0:sq.getErrorCode());
                    unit.state="FAILED";
                    if(record.cancelRequested) {
                        boolean acknowledged=sq!=null&&(sq instanceof SQLTimeoutException || Arrays.asList("57014","HY008","70100").contains(sq.getSQLState()));
                        terminalState=acknowledged?(record.timeoutRequested?"TIMED_OUT":"CANCELED"):"OUTCOME_UNKNOWN";
                    }
                    else if(sq!=null&&sq.getSQLState()!=null&&sq.getSQLState().startsWith("08")){terminalState="OUTCOME_UNKNOWN";session.state="BROKEN";}
                    else terminalState="FAILED";
                    record.message=record.cancelRequested?"执行已中断；是否已提交取决于数据库，必要时核实数据状态":unit.error.message;
                    break;
                } finally {record.activeStatement=null;unit.elapsedMs=System.currentTimeMillis()-started;}
            }
            if(terminalState==null) {
                terminalState=record.cancelRequested?(record.timeoutRequested?"TIMED_OUT":"CANCELED"):"SUCCEEDED";
                record.message=record.cancelRequested?"已停止后续执行；已提交操作不会撤销":"执行完成";
            }
        } finally {
            if(deadline!=null)deadline.cancel(false);record.activeStatement=null;
            for(UnitResult unit:record.statements)if("PENDING".equals(unit.state))unit.state="SKIPPED";
            record.elapsedMs=System.currentTimeMillis()-record.startedAt;sessions.release(session);record.state=terminalState==null?"OUTCOME_UNKNOWN":terminalState;record.finishedAt=System.currentTimeMillis();
        }
    }
    private Statement createStatement(Session session,ExecutionRequest request,String sql,Plan plan) throws SQLException {
        if("CALL".equals(request.mode)) {
            CallableStatement statement=session.connection.prepareCall(sql);
            try {
                Set<Integer> positions=new HashSet<Integer>();
                for(ExecutionRequest.Parameter parameter:request.parameters) {
                    if(parameter.position<1||!positions.add(parameter.position))throw new AppException("参数位置必须是从 1 开始且不重复的序号");
                    String mode=parameter.mode==null?"IN":parameter.mode.toUpperCase(Locale.ROOT);
                    if(!Arrays.asList("IN","OUT","INOUT","RETURN").contains(mode))throw new AppException("参数方向不支持: "+mode);
                    if(!"IN".equals(mode))statement.registerOutParameter(parameter.position,parameter.jdbcType);
                    if("IN".equals(mode)||"INOUT".equals(mode)) {
                        if(parameter.nullValue||parameter.value==null)statement.setNull(parameter.position,parameter.jdbcType);
                        else statement.setObject(parameter.position,typed(parameter.value,parameter.jdbcType),parameter.jdbcType);
                    }
                }
                return statement;
            } catch(Throwable ex){try{statement.close();}catch(SQLException ignored){}if(ex instanceof SQLException)throw (SQLException)ex;if(ex instanceof RuntimeException)throw (RuntimeException)ex;throw new SQLException(ex);}
        }
        if("TABLE_PREVIEW".equals(request.mode)) {
            PreparedStatement statement=session.connection.prepareStatement(sql);
            try{for(int i=0;i<plan.values.size();i++)statement.setObject(i+1,plan.values.get(i));return statement;}
            catch(SQLException ex){statement.close();throw ex;}
        }
        return session.connection.createStatement();
    }
    private Object typed(Object value,int type) {
        String text=String.valueOf(value);
        try {
            switch(type) {
                case Types.INTEGER:case Types.SMALLINT:case Types.TINYINT:return Integer.valueOf(text);
                case Types.BIGINT:return Long.valueOf(text);
                case Types.DECIMAL:case Types.NUMERIC:return new BigDecimal(text);
                case Types.BOOLEAN:case Types.BIT:if(!"true".equalsIgnoreCase(text)&&!"false".equalsIgnoreCase(text))throw new IllegalArgumentException();return Boolean.valueOf(text);
                case Types.DATE:return java.sql.Date.valueOf(text);
                case Types.TIME:return Time.valueOf(text);
                case Types.TIMESTAMP:return Timestamp.valueOf(text.replace('T',' '));
                default:return value;
            }
        }catch(IllegalArgumentException ex){throw new AppException("参数值不符合 JDBC 类型 "+type);}
    }
    private void readOutputs(CallableStatement statement,ExecutionRequest request,UnitResult unit,ResultReader.Budget budget) throws SQLException {
        Result output=new Result();output.kind="OUT_PARAMETERS";
        for(ExecutionRequest.Parameter p:request.parameters)if(!"IN".equalsIgnoreCase(p.mode)) {
            Object value=statement.getObject(p.position);
            if(value instanceof ResultSet){try(ResultSet rs=(ResultSet)value){Result cursor=ResultReader.read(rs,request.maxRows,budget);if(budget.items++<100)unit.results.add(cursor);else output.truncated=true;value="游标结果见结果标签";}}
            if(budget.exhausted()||budget.items>=100){output.truncated=true;continue;}
            Object normalized=ResultReader.normalize(value,output);
            budget.bytes+=normalized==null?4:String.valueOf(normalized).length()*2L;
            if(budget.exhausted()){output.truncated=true;continue;}
            Map<String,Object> param=new LinkedHashMap<String,Object>();param.put("position",p.position);param.put("name",p.name);param.put("mode",p.mode);param.put("jdbcType",p.jdbcType);param.put("value",normalized);output.parameters.add(param);
        }
        if(!output.parameters.isEmpty()&&budget.items++<100)unit.results.add(output);
        if(output.truncated)unit.warnings.add("输出参数或游标结果超过预览上限，部分内容已截断");
    }
    public ExecutionRecord get(String id){ExecutionRecord r=executions.get(id);if(r==null)throw new AppException("执行结果已过期或不存在");if(r.finishedAt==0)r.elapsedMs=System.currentTimeMillis()-r.startedAt;return r;}
    public ExecutionRecord cancel(String id){ExecutionRecord r=get(id);if(r.finishedAt==0)requestCancellation(r,sessions.get(r.sessionId),false);return r;}
    private synchronized void requestCancellation(ExecutionRecord r,Session session,boolean timeout) {
        if(r.finishedAt!=0||r.cancelRequested)return;
        r.cancelRequested=true;r.timeoutRequested|=timeout;r.state="CANCEL_REQUESTED";r.message=timeout?"执行超时，正在请求取消":"正在请求数据库取消执行";
        Statement statement=r.activeStatement;
        if(statement!=null)cancels.submit(()->{try{statement.cancel();}catch(SQLException ignored){}});
        timers.schedule(()->{if(r.finishedAt==0&&r.cancelRequested){r.message="驱动尚未终止，正在关闭会话；数据库结果可能未知";sessions.requestBreak(session);}},CANCEL_GRACE_SECONDS,TimeUnit.SECONDS);
    }
    public void delete(String id){ExecutionRecord r=get(id);if(r.finishedAt==0)throw new AppException("请等待执行结束后清除结果");executions.remove(id);submittedFingerprints.remove(id);requestIds.values().removeIf(id::equals);}
    private void prune(){long now=System.currentTimeMillis();plans.values().removeIf(p->now-p.created>10*60*1000L);for(ExecutionRecord r:executions.values())if(r.finishedAt>0&&now-r.finishedAt>15*60*1000L){executions.remove(r.id);submittedFingerprints.remove(r.id);requestIds.values().removeIf(r.id::equals);}}
    private String fingerprint(ExecutionRequest request) {
        try {
            Map<String,Object> fields=mapper.convertValue(request,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});fields.remove("requestId");fields.remove("confirmationToken");
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(fields));return Base64.getEncoder().encodeToString(hash);
        }catch(Exception ex){throw new AppException("无法校验执行内容");}
    }
    private String context(Session session){return session.connectionId+":"+session.autoCommit+":"+session.catalog+":"+session.schema+":"+session.dialect+":"+session.revision;}
    private void validate(ExecutionRequest r) {
        if(r.sessionId==null||r.sessionId.trim().isEmpty())throw new AppException("请选择数据库会话");
        if(r.mode==null)r.mode="SQL";r.mode=r.mode.toUpperCase(Locale.ROOT);
        if(!Arrays.asList("SQL","CURRENT","BLOCK","SCRIPT","CALL","EXPLAIN","TABLE_PREVIEW","CELL_UPDATE").contains(r.mode))throw new AppException("执行模式不支持");
        if(!"TABLE_PREVIEW".equals(r.mode)&&!"CELL_UPDATE".equals(r.mode)&&(r.sql==null||r.sql.trim().isEmpty()))throw new AppException("SQL 不能为空");
        if(r.maxRows<1||r.maxRows>5000)throw new AppException("结果行数必须在 1 到 5000 之间");
        if(r.timeoutSeconds<1||r.timeoutSeconds>3600)throw new AppException("超时必须在 1 到 3600 秒之间");
        if(r.parameters==null)r.parameters=new ArrayList<ExecutionRequest.Parameter>();
        if(r.parameters.size()>256)throw new AppException("调用参数不能超过 256 个");
    }
    private static SQLException findSql(Throwable ex){while(ex!=null){if(ex instanceof SQLException)return (SQLException)ex;ex=ex.getCause();}return null;}
    @PreDestroy public void close(){
        List<Runnable> queued=new ArrayList<Runnable>();
        synchronized(this){
            if(stopping)return;
            stopping=true;sessions.stopAccepting();workers.shutdown();workers.getQueue().drainTo(queued);
        }
        for(Runnable task:queued)((ExecutionTask)task).cancelBeforeStart();
        for(ExecutionRecord record:executions.values())if(record.finishedAt==0)
            try{requestCancellation(record,sessions.get(record.sessionId),false);}catch(AppException ignored){}
        boolean terminated=false;
        try{terminated=workers.awaitTermination(SHUTDOWN_WAIT_SECONDS,TimeUnit.SECONDS);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();}
        if(!terminated){
            workers.shutdownNow();
            for(ExecutionRecord record:executions.values())if(record.finishedAt==0){
                record.state="OUTCOME_UNKNOWN";record.message="应用退出时驱动仍未结束，请核实数据库结果";
            }
        }
        timers.shutdownNow();cancels.shutdownNow();
    }
}
