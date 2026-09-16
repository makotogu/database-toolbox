package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.*;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.example.dbtoolbox.workbench.connection.*;
import com.example.dbtoolbox.workbench.driver.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CellEditingTest {
    @TempDir Path directory;
    DriverService drivers;ConnectionService connections;SessionService sessions;ExecutionService executions;SessionService.Session session;
    @BeforeEach void setup() throws Exception {
        ToolboxProperties props=new ToolboxProperties();props.setStorageRoot(directory.toString());StoragePaths paths=new StoragePaths(props);
        ObjectMapper mapper=new ObjectMapper();drivers=new DriverService(paths,mapper);connections=new ConnectionService(paths,mapper,drivers);sessions=new SessionService(connections);executions=new ExecutionService(sessions,mapper);
        Path jar=Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        DriverProfile driver=drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files","h2.jar","application/java-archive",Files.readAllBytes(jar))},"H2",null);
        ConnectionProfile c=new ConnectionProfile();c.name="test";c.driverId=driver.id;c.jdbcUrl="jdbc:h2:mem:cells;DB_CLOSE_DELAY=-1";c.username="sa";c.password="";
        session=sessions.create(connections.save(c).id,null,null);
    }
    @AfterEach void close(){if(executions!=null)executions.close();if(sessions!=null)sessions.shutdown();if(drivers!=null)drivers.shutdown();}
    private ExecutionRequest request(String sql,String mode){ExecutionRequest r=new ExecutionRequest();r.sessionId=session.id;r.sql=sql;r.mode=mode;r.requestId=UUID.randomUUID().toString();return r;}
    private ExecutionRecord execute(ExecutionRequest request)throws Exception{request.confirmationToken=executions.prepare(request).confirmationToken;ExecutionRecord record=executions.submit(request);await(record);return record;}
    private void await(ExecutionRecord r)throws Exception{long until=System.currentTimeMillis()+8000;while(r.finishedAt==0&&System.currentTimeMillis()<until)Thread.sleep(10);assertTrue(r.finishedAt>0,"Execution did not terminate: "+r.state);}
    private void sql(String sql) throws Exception {
        try(Statement statement=session.connection.createStatement()){statement.execute(sql);}
    }
    private ExecutionRecord preview(String table) throws Exception {
        ExecutionRequest request=request(null,"TABLE_PREVIEW");request.table=table;request.schema="PUBLIC";
        ExecutionRecord result=execute(request);assertEquals("SUCCEEDED",result.state,result.message);return result;
    }
    private ExecutionRecord.Result grid(ExecutionRecord record){return record.statements.get(0).results.get(0);}
    private ExecutionRequest edit(ExecutionRecord preview,int row,int column,String value,boolean nil) {
        ExecutionRequest request=request(null,"CELL_UPDATE");request.cellChange=new ExecutionRequest.CellChange();
        request.cellChange.executionId=preview.id;request.cellChange.row=row;request.cellChange.column=column;request.cellChange.value=value;request.cellChange.nullValue=nil;return request;
    }
    private Object value(String query) throws Exception {
        try(Connection connection=connections.open(session.connectionId);Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery(query)){rs.next();return rs.getObject(1);}
    }
    private void create() throws Exception {sql("CREATE TABLE cells(id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(30,19), required INT NOT NULL DEFAULT 7)");sql("INSERT INTO cells(id,name,amount) VALUES(1,'old',1.1234567890123456789),(2,'second',2)");}
    @Test void writesOnlySelectedCellAndDuplicateSubmissionIsIdempotent() throws Exception {
        create();ExecutionRecord source=preview("CELLS");assertTrue(grid(source).columns.get(1).editable);
        ExecutionRequest request=edit(source,0,1,"quote ' ; DROP TABLE cells; -- <script>",false);
        ExecutionService.Plan plan=executions.prepare(request);assertTrue(plan.confirmationRequired);assertFalse(plan.units.get(0).sql.contains("DROP"));
        request.confirmationToken=plan.confirmationToken;ExecutionRecord saved=executions.submit(request);await(saved);
        assertEquals("SUCCEEDED",saved.state,saved.message);assertEquals(1,grid(saved).updateCount);assertSame(saved,executions.submit(request));
        assertEquals(request.cellChange.value,value("SELECT name FROM cells WHERE id=1"));assertEquals("second",value("SELECT name FROM cells WHERE id=2"));assertTrue(session.autoCommit);
    }
    @Test void supportsCompositeQuotedKeysAndPreservesDecimalPrecision() throws Exception {
        sql("CREATE TABLE \"Odd Table\"(\"Key One\" BIGINT, \"Key Two\" VARCHAR(30), amount DECIMAL(30,19), PRIMARY KEY(\"Key One\",\"Key Two\"))");
        sql("INSERT INTO \"Odd Table\" VALUES(1234567890123456789,'a',0),(1234567890123456789,'b',0)");
        ExecutionRecord saved=execute(edit(preview("Odd Table"),1,2,"1.1234567890123456789",false));assertEquals("SUCCEEDED",saved.state,saved.message);
        assertEquals(new java.math.BigDecimal("1.1234567890123456789"),value("SELECT amount FROM \"Odd Table\" WHERE \"Key Two\"='b'"));
        assertEquals(new java.math.BigDecimal("0.0000000000000000000"),value("SELECT amount FROM \"Odd Table\" WHERE \"Key Two\"='a'"));
    }
    @Test void nullAndEmptyStringRemainDistinct() throws Exception {
        create();assertEquals("SUCCEEDED",execute(edit(preview("CELLS"),0,1,null,true)).state);assertNull(value("SELECT name FROM cells WHERE id=1"));
        assertEquals("SUCCEEDED",execute(edit(preview("CELLS"),0,1,"",false)).state);assertEquals("",value("SELECT name FROM cells WHERE id=1"));
        assertThrows(AppException.class,()->executions.prepare(edit(preview("CELLS"),0,3,null,true)));
    }
    @Test void concurrentChangeAndDeletionAreConflicts() throws Exception {
        create();ExecutionRecord source=preview("CELLS");
        try(Connection c=connections.open(session.connectionId);Statement s=c.createStatement()){s.executeUpdate("UPDATE cells SET name='OLD' WHERE id=1");}
        ExecutionRecord conflict=execute(edit(source,0,1,"mine",false));assertEquals("FAILED",conflict.state);assertTrue(conflict.message.contains("冲突"));assertEquals("OLD",value("SELECT name FROM cells WHERE id=1"));
        source=preview("CELLS");sql("DELETE FROM cells WHERE id=1");conflict=execute(edit(source,0,1,"mine",false));assertEquals("FAILED",conflict.state);assertTrue(conflict.message.contains("冲突"));
    }
    @Test void manualSavepointFailurePreservesEarlierChangesAndAllowsRollback() throws Exception {
        create();sessions.transaction(session.id,"AUTO_COMMIT",false);sql("UPDATE cells SET name='prior' WHERE id=2");
        ExecutionRecord failed=execute(edit(preview("CELLS"),0,1,String.join("",Collections.nCopies(101,"x")),false));assertEquals("FAILED",failed.state);
        assertEquals("second",value("SELECT name FROM cells WHERE id=2"));
        try(Statement s=session.connection.createStatement();ResultSet rs=s.executeQuery("SELECT name FROM cells WHERE id=2")){rs.next();assertEquals("prior",rs.getString(1));}
        ExecutionRecord saved=execute(edit(preview("CELLS"),0,1,"pending",false));assertEquals("SUCCEEDED",saved.state,saved.message);assertFalse(session.autoCommit);assertEquals("old",value("SELECT name FROM cells WHERE id=1"));
        sessions.transaction(session.id,"ROLLBACK",false);assertEquals("old",value("SELECT name FROM cells WHERE id=1"));assertEquals("second",value("SELECT name FROM cells WHERE id=2"));
    }
    @Test void manualSaveCanBeCommittedExplicitly() throws Exception {
        create();sessions.transaction(session.id,"AUTO_COMMIT",false);
        assertEquals("SUCCEEDED",execute(edit(preview("CELLS"),0,1,"committed",false)).state);
        sessions.transaction(session.id,"COMMIT",false);assertEquals("committed",value("SELECT name FROM cells WHERE id=1"));
    }
    @Test void rejectsPrimaryKeysGeneratedColumnsViewsAndMissingKeys() throws Exception {
        create();ExecutionRecord source=preview("CELLS");assertFalse(grid(source).columns.get(0).editable);assertThrows(AppException.class,()->executions.prepare(edit(source,0,0,"5",false)));
        sql("CREATE VIEW cell_view AS SELECT * FROM cells");assertTrue(grid(preview("CELL_VIEW")).readOnlyReason.contains("视图"));
        sql("CREATE TABLE no_key(name VARCHAR)");assertTrue(grid(preview("NO_KEY")).readOnlyReason.contains("主键"));
        sql("CREATE TABLE generated(id INT PRIMARY KEY, source INT, derived INT GENERATED ALWAYS AS (source+1))");sql("INSERT INTO generated(id,source) VALUES(1,1)");
        ExecutionRecord generated=preview("GENERATED");assertFalse(grid(generated).columns.get(2).editable);assertThrows(AppException.class,()->executions.prepare(edit(generated,0,2,"3",false)));
    }
    @Test void refusesOtherSessionSqlResultsStaleSessionAndTamperedConfirmation() throws Exception {
        create();ExecutionRecord source=preview("CELLS");ExecutionRequest request=edit(source,0,1,"new",false);
        SessionService.Session other=sessions.create(session.connectionId,null,null);request.sessionId=other.id;assertThrows(AppException.class,()->executions.prepare(request));request.sessionId=session.id;
        request.confirmationToken=executions.prepare(request).confirmationToken;request.cellChange.row=1;assertThrows(AppException.class,()->executions.submit(request));
        sessions.transaction(session.id,"AUTO_COMMIT",false);assertThrows(AppException.class,()->executions.prepare(edit(source,0,1,"new",false)));
        ExecutionRecord arbitrary=execute(request("SELECT * FROM cells","SQL"));assertThrows(AppException.class,()->executions.prepare(edit(arbitrary,0,1,"new",false)));
    }
    @Test void selectedCellAndKeyTruncationBlockedButRowLimitDoesNotBlockEditing() throws Exception {
        sql("CREATE TABLE long_cells(id INT PRIMARY KEY, long_text VARCHAR, short_text VARCHAR)");sql("INSERT INTO long_cells VALUES(1,REPEAT('x',40000),'short'),(2,'ok','ok')");
        ExecutionRequest r=request(null,"TABLE_PREVIEW");r.table="LONG_CELLS";r.schema="PUBLIC";r.maxRows=1;
        ExecutionRecord source=execute(r);assertTrue(grid(source).truncated);assertEquals(Arrays.asList(0,1),grid(source).truncatedCells.get(0));
        assertThrows(AppException.class,()->executions.prepare(edit(source,0,1,"new",false)));
        assertEquals("SUCCEEDED",execute(edit(source,0,2,"new",false)).state);
        sql("CREATE TABLE long_key(id VARCHAR PRIMARY KEY, val VARCHAR)");sql("INSERT INTO long_key VALUES(REPEAT('x',40000),'old')");
        ExecutionRecord key=preview("LONG_KEY");assertThrows(AppException.class,()->executions.prepare(edit(key,0,1,"new",false)));
    }
    @Test void rejectsRoundingNoopInvalidValuesAndChangedSchema() throws Exception {
        create();ExecutionRecord source=preview("CELLS");
        assertThrows(AppException.class,()->executions.prepare(edit(source,0,1,"old",false)));
        assertThrows(AppException.class,()->executions.prepare(edit(source,0,3,"1.5",false)));
        ExecutionRecord rounded=execute(edit(source,0,2,"1.12345678901234567891",false));assertEquals("FAILED",rounded.state);assertTrue(rounded.message.contains("精度"));
        ExecutionRecord fresh=preview("CELLS");ExecutionRequest request=edit(fresh,0,1,"new",false);request.confirmationToken=executions.prepare(request).confirmationToken;
        sql("ALTER TABLE cells ADD extra VARCHAR");ExecutionRecord changed=executions.submit(request);await(changed);assertEquals("FAILED",changed.state);assertTrue(changed.message.contains("表结构"));assertEquals("old",value("SELECT name FROM cells WHERE id=1"));
    }
    @Test void cancellationWhileWaitingForRowLockDoesNotWrite() throws Exception {
        create();ExecutionRecord source=preview("CELLS");
        try(Connection other=connections.open(session.connectionId)) {
            other.setAutoCommit(false);
            try(Statement statement=other.createStatement()){statement.executeUpdate("UPDATE cells SET name='locked' WHERE id=1");}
            ExecutionRequest request=edit(source,0,1,"must not save",false);request.confirmationToken=executions.prepare(request).confirmationToken;
            ExecutionRecord saved=executions.submit(request);
            long until=System.currentTimeMillis()+3000;while(saved.activeStatement==null&&saved.finishedAt==0&&System.currentTimeMillis()<until)Thread.sleep(5);
            assertNotNull(saved.activeStatement);executions.cancel(saved.id);other.rollback();await(saved);
            assertNotEquals("SUCCEEDED",saved.state);assertEquals("old",value("SELECT name FROM cells WHERE id=1"));
        }
    }
    @Test void temporalValuesAreStrictAndUnsupportedTypesStayReadOnly() throws Exception {
        sql("CREATE TABLE temporal(id INT PRIMARY KEY, d DATE, ts TIMESTAMP(9), tm TIME, raw BINARY, approximate DOUBLE)");
        sql("INSERT INTO temporal(id) VALUES(1)");ExecutionRecord source=preview("TEMPORAL");
        assertThrows(AppException.class,()->executions.prepare(edit(source,0,1,"2026-02-31",false)));
        assertFalse(grid(source).columns.get(4).editable);assertFalse(grid(source).columns.get(5).editable);
        assertEquals("SUCCEEDED",execute(edit(source,0,1,"2026-09-16",false)).state);
        assertEquals("SUCCEEDED",execute(edit(preview("TEMPORAL"),0,2,"2026-09-16T10:20:30.123456789",false)).state);
        assertEquals("SUCCEEDED",execute(edit(preview("TEMPORAL"),0,3,"10:20:30",false)).state);
    }
    @Test void defaultAndExplicitSchemasTargetThePreviewedTableOnly() throws Exception {
        create();sql("CREATE SCHEMA other");sql("CREATE TABLE other.cells(id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(30,19), required INT NOT NULL DEFAULT 7)");sql("INSERT INTO other.cells(id,name,amount) VALUES(1,'old',1)");
        ExecutionRequest preview=request(null,"TABLE_PREVIEW");preview.table="CELLS";preview.schema="OTHER";
        assertEquals("SUCCEEDED",execute(edit(execute(preview),0,1,"other schema",false)).state);
        assertEquals("other schema",value("SELECT name FROM other.cells WHERE id=1"));assertEquals("old",value("SELECT name FROM public.cells WHERE id=1"));
        preview.schema="";preview.catalog="";preview.requestId=UUID.randomUUID().toString();
        assertEquals("SUCCEEDED",execute(edit(execute(preview),0,1,"default schema",false)).state);
        assertEquals("default schema",value("SELECT name FROM public.cells WHERE id=1"));assertEquals("other schema",value("SELECT name FROM other.cells WHERE id=1"));
    }
}
