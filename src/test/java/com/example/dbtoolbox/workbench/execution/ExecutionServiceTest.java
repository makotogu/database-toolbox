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

class ExecutionServiceTest {
    @TempDir Path directory;
    DriverService drivers;ConnectionService connections;SessionService sessions;ExecutionService executions;SessionService.Session session;
    @BeforeEach void setup() throws Exception {
        ToolboxProperties props=new ToolboxProperties();props.setStorageRoot(directory.toString());StoragePaths paths=new StoragePaths(props);
        ObjectMapper mapper=new ObjectMapper();drivers=new DriverService(paths,mapper);connections=new ConnectionService(paths,mapper,drivers);sessions=new SessionService(connections);executions=new ExecutionService(sessions,mapper);
        Path jar=Paths.get(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        DriverProfile driver=drivers.importFiles(new MultipartFile[]{new MockMultipartFile("files","h2.jar","application/java-archive",Files.readAllBytes(jar))},"H2",null);
        ConnectionProfile c=new ConnectionProfile();c.name="test";c.driverId=driver.id;c.jdbcUrl="jdbc:h2:mem:exec;DB_CLOSE_DELAY=-1";c.username="sa";c.password="";
        session=sessions.create(connections.save(c).id,null,null);
    }
    @AfterEach void close(){if(executions!=null)executions.close();if(sessions!=null)sessions.shutdown();if(drivers!=null)drivers.shutdown();}
    private ExecutionRequest request(String sql,String mode){ExecutionRequest r=new ExecutionRequest();r.sessionId=session.id;r.sql=sql;r.mode=mode;r.requestId=UUID.randomUUID().toString();return r;}
    private ExecutionRecord execute(ExecutionRequest request)throws Exception{request.confirmationToken=executions.prepare(request).confirmationToken;ExecutionRecord record=executions.submit(request);await(record);return record;}
    private void await(ExecutionRecord r)throws Exception{long until=System.currentTimeMillis()+8000;while(r.finishedAt==0&&System.currentTimeMillis()<until)Thread.sleep(10);assertTrue(r.finishedAt>0,"Execution did not terminate: "+r.state);}
    @Test void duplicateColumnsPrecisionNullAndBoundedRows()throws Exception{
        ExecutionRequest r=request("SELECT 1 AS id, 2 AS id, CAST(1234567890123456789 AS BIGINT) AS big, CAST(1.1234567890123456789 AS DECIMAL(30,19)) AS amount, NULL AS n, '' AS empty FROM SYSTEM_RANGE(1,4)","SQL");r.maxRows=2;
        ExecutionRecord result=execute(r);assertEquals("SUCCEEDED",result.state);
        ExecutionRecord.Result grid=result.statements.get(0).results.get(0);assertEquals(2,grid.rows.size());assertTrue(grid.truncated);
        assertEquals(Arrays.asList(1,2,"1234567890123456789","1.1234567890123456789",null,""),grid.rows.get(0));assertEquals("ID",grid.columns.get(0).label);assertEquals("ID",grid.columns.get(1).label);
    }
    @Test void scriptUsesOneSessionStopsAtErrorAndReturnsZeroUpdate()throws Exception{
        ExecutionRecord r=execute(request("CREATE LOCAL TEMPORARY TABLE probe(id INT); INSERT INTO probe VALUES(7); UPDATE probe SET id=9 WHERE id=0; SELECT * FROM probe; SELECT * FROM missing; INSERT INTO probe VALUES(8);","SCRIPT"));
        assertEquals("FAILED",r.state);assertEquals(0,r.statements.get(2).results.get(0).updateCount);assertEquals(7,r.statements.get(3).results.get(0).rows.get(0).get(0));assertEquals("SKIPPED",r.statements.get(5).state);assertNotNull(r.statements.get(4).error.sqlState);
        assertEquals(1,execute(request("SELECT COUNT(*) FROM probe","SQL")).statements.get(0).results.get(0).rows.size());
    }
    @Test void callableFunctionReadsReturnParameter()throws Exception{
        ExecutionRequest r=request("{? = call ABS(?)}","CALL");ExecutionRequest.Parameter out=new ExecutionRequest.Parameter();out.position=1;out.mode="RETURN";out.jdbcType=Types.INTEGER;
        ExecutionRequest.Parameter in=new ExecutionRequest.Parameter();in.position=2;in.mode="IN";in.jdbcType=Types.INTEGER;in.value="-9";r.parameters=Arrays.asList(out,in);
        ExecutionRecord result=execute(r);assertEquals("SUCCEEDED",result.state,result.message);
        ExecutionRecord.Result output=result.statements.get(0).results.stream().filter(x->"OUT_PARAMETERS".equals(x.kind)).findFirst().get();assertEquals(9,output.parameters.get(0).get("value"));
    }
    @Test void manualTransactionRollbackAndSessionIsolation()throws Exception{
        execute(request("CREATE TABLE tx(id INT)","SQL"));sessions.transaction(session.id,"AUTO_COMMIT",false);
        execute(request("INSERT INTO tx VALUES(1)","SQL"));
        try(Connection other=connections.open(session.connectionId);Statement s=other.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM tx")){rs.next();assertEquals(0,rs.getInt(1));}
        sessions.transaction(session.id,"ROLLBACK",false);assertFalse(session.autoCommit);
        assertEquals("0",execute(request("SELECT COUNT(*) FROM tx","SQL")).statements.get(0).results.get(0).rows.get(0).get(0));
    }
    @Test void preparedContentAndTransactionContextCannotChange()throws Exception{
        ExecutionRequest r=request("SELECT 1","SQL");r.confirmationToken=executions.prepare(r).confirmationToken;r.sql="SELECT 2";assertThrows(AppException.class,()->executions.submit(r));
        r.sql="SELECT 1";r.confirmationToken=executions.prepare(r).confirmationToken;sessions.transaction(session.id,"AUTO_COMMIT",false);assertThrows(AppException.class,()->executions.submit(r));
    }
    @Test void retryIsIdempotentAndNewPayloadWithSameIdRejected()throws Exception{
        ExecutionRequest r=request("CREATE TABLE once_only(id INT)","SQL");ExecutionRecord first=execute(r);assertSame(first,executions.submit(r));
        r.sql="DROP TABLE once_only";assertThrows(AppException.class,()->executions.submit(r));
    }
    @Test void oldPlanCannotExecuteAfterAnotherStatementChangesSessionContext()throws Exception{
        ExecutionRequest pending=request("SELECT 1","SQL");pending.confirmationToken=executions.prepare(pending).confirmationToken;
        execute(request("CREATE SCHEMA second_schema; SET SCHEMA second_schema;","SCRIPT"));
        assertEquals("SECOND_SCHEMA",session.schema);
        assertThrows(AppException.class,()->executions.submit(pending));
    }
    @Test void tablePreviewBindsFilterAndExplainUsesSameResultModel()throws Exception{
        execute(request("CREATE TABLE items(id INT PRIMARY KEY, title VARCHAR); INSERT INTO items VALUES(1,'a'),(2,'b'),(3,'c');","SCRIPT"));
        ExecutionRequest r=request(null,"TABLE_PREVIEW");r.table="ITEMS";r.schema="PUBLIC";r.limit=1;Map<String,Object> filter=new HashMap<String,Object>();filter.put("column","ID");filter.put("operator",">");filter.put("value","1");r.filters.add(filter);
        ExecutionRecord table=execute(r);assertEquals("SUCCEEDED",table.state,table.message);assertEquals(2,table.statements.get(0).results.get(0).rows.get(0).get(0));assertEquals(1,table.statements.get(0).results.get(0).rows.size());
        ExecutionRecord plan=execute(request("SELECT * FROM items WHERE id=1","EXPLAIN"));assertEquals("PLAN",plan.statements.get(0).results.get(0).kind);
    }
    @Test void runningQueryCanBeCanceled()throws Exception{
        ExecutionRequest r=request("SELECT SUM(X) FROM SYSTEM_RANGE(1,1000000000)","SQL");r.confirmationToken=executions.prepare(r).confirmationToken;ExecutionRecord record=executions.submit(r);
        long until=System.currentTimeMillis()+2000;while(record.activeStatement==null&&record.finishedAt==0&&System.currentTimeMillis()<until)Thread.sleep(5);
        executions.cancel(record.id);await(record);assertTrue(Arrays.asList("CANCELED","OUTCOME_UNKNOWN").contains(record.state),record.state);
    }

    @Test void flatFilterGroupsBindValuesAndBindMatchModeToConfirmation() throws Exception {
        execute(request("CREATE TABLE grouped(id INT PRIMARY KEY, title VARCHAR, amount DECIMAL(30,3)); INSERT INTO grouped VALUES(1,NULL,12345678901234567890.123),(2,'',2),(3,'x',3);", "SCRIPT"));
        ExecutionRequest r = request(null, "TABLE_PREVIEW"); r.table="GROUPED"; r.schema="PUBLIC";
        Map<String,Object> first = new LinkedHashMap<String,Object>(); first.put("column","ID"); first.put("operator","="); first.put("value","1");
        Map<String,Object> second = new LinkedHashMap<String,Object>(); second.put("column","TITLE"); second.put("operator","="); second.put("value","");
        r.filters.add(first); r.filters.add(second);
        r.confirmationToken = executions.prepare(r).confirmationToken; r.filterMatch="ANY";
        assertThrows(AppException.class, () -> executions.submit(r));
        r.requestId=UUID.randomUUID().toString();
        ExecutionRecord any=execute(r); assertEquals("SUCCEEDED",any.state,any.message);
        assertEquals(2,any.statements.get(0).results.get(0).rows.size());
        r.requestId=UUID.randomUUID().toString(); r.filterMatch="ALL";
        assertEquals(0,execute(r).statements.get(0).results.get(0).rows.size());
        r.requestId=UUID.randomUUID().toString(); first.put("column","AMOUNT"); first.put("value","12345678901234567890.123"); second.put("operator","IS NULL");
        assertEquals(1,execute(r).statements.get(0).results.get(0).rows.size());
        r.filterMatch="ANY OR 1=1"; assertThrows(AppException.class, () -> executions.prepare(r));
        r.filterMatch="ALL"; r.filters=new ArrayList<Map<String,Object>>(Collections.nCopies(31,first));
        assertThrows(AppException.class, () -> executions.prepare(r));
        r.filters=new ArrayList<Map<String,Object>>(Collections.nCopies(30,first));
        r.requestId=UUID.randomUUID().toString(); assertEquals(1,execute(r).statements.get(0).results.get(0).rows.size());
        r.filters=Collections.singletonList(second); second.put("operator","="); second.put("value","' OR 1=1 --");
        r.requestId=UUID.randomUUID().toString(); assertEquals(0,execute(r).statements.get(0).results.get(0).rows.size());
    }
}
