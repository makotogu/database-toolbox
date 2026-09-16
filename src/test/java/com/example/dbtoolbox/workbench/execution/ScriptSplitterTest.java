package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScriptSplitterTest {
    @Test void quotesCommentsAndRangesSurvive() {
        String source="-- comment;\nSELECT 'a;''b' AS x;\n/* ; */ SELECT 2;";
        List<ScriptSplitter.Unit> result=ScriptSplitter.split(source,"H2");
        assertEquals(2,result.size());assertEquals("-- comment;\nSELECT 'a;''b' AS x",result.get(0).sql);
        assertEquals(3,result.get(1).startLine);assertEquals(result.get(1).sql,source.substring(result.get(1).startOffset,result.get(1).endOffset));
    }
    @Test void mysqlDelimiterPreservesBodyAndRestoresSeparator() {
        String sql="DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 'x;'; END$$\nDELIMITER ;\nCALL p(); SELECT 3;";
        List<ScriptSplitter.Unit> result=ScriptSplitter.split(sql,"MYSQL",true);
        assertEquals(3,result.size());assertEquals("CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 'x;'; END",result.get(0).sql);
        assertEquals("CALL p()",result.get(1).sql);assertEquals("SELECT 3",result.get(2).sql);
    }
    @Test void postgresDollarTaggedNestedCommentsAndEscapeStrings() {
        String sql="DO $body$ BEGIN RAISE NOTICE 'x;'; END; $body$; /* outer /* inner; */ end */ SELECT E'a\\';b'; SELECT $$a;$other$$$;";
        List<ScriptSplitter.Unit> result=ScriptSplitter.split(sql,"POSTGRESQL");
        assertEquals(3,result.size());assertTrue(result.get(0).sql.endsWith("$body$"));assertTrue(result.get(1).sql.contains("E'a\\';b'"));
    }
    @Test void oracleSlashSeparatesBlocksWithoutChangingDivision() {
        String sql="DECLARE n INTEGER;\nBEGIN n := 2; END;\n/\nSELECT 6 / 2 FROM dual;\nBEGIN x := q'[a;b]'; END;\n/";
        List<ScriptSplitter.Unit> result=ScriptSplitter.split(sql,"ORACLE");
        assertEquals(3,result.size());assertTrue(result.get(0).sql.endsWith("END;"));assertEquals("SELECT 6 / 2 FROM dual",result.get(1).sql);
    }
    @Test void transactionBeginIsNotMistakenForAnonymousBlock() {
        assertEquals(3,ScriptSplitter.split("BEGIN; SELECT 1; COMMIT;","POSTGRESQL").size());
    }
    @Test void missingQuotesAndAmbiguousProcedureAreRejectedBeforeExecution() {
        assertThrows(AppException.class,()->ScriptSplitter.split("SELECT 'unfinished","H2"));
        assertThrows(AppException.class,()->ScriptSplitter.split("DO $a$ BEGIN; $b$","POSTGRESQL"));
        assertThrows(AppException.class,()->ScriptSplitter.split("CREATE PROCEDURE p() BEGIN SELECT 1; END;","MYSQL"));
        assertThrows(AppException.class,()->ScriptSplitter.split("BEGIN x:=1; END; SELECT 1 FROM dual;","ORACLE"));
    }
    @Test void blockModePreservesInternalAndFinalSemicolons() {
        assertEquals("BEGIN x:=1; END;",ScriptSplitter.block("BEGIN x:=1; END;\n/","ORACLE").sql);
        assertThrows(AppException.class,()->ScriptSplitter.block("BEGIN x:=1; END;\n/\nSELECT 2","ORACLE"));
    }
    @Test void mysqlSqlModeChangesBackslashInterpretation() {
        assertEquals(2,ScriptSplitter.split("SELECT 'a\\'; SELECT 2;","MYSQL",false).size());
        assertThrows(AppException.class,()->ScriptSplitter.split("SELECT 'a\\'; SELECT 2;","MYSQL",true));
    }
    @Test void sideEffectHintsDoNotRejectUnknownValidSql() {
        assertTrue(ScriptSplitter.requiresConfirmation("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"));
        assertTrue(ScriptSplitter.requiresConfirmation("CALL p()"));
        assertTrue(ScriptSplitter.requiresConfirmation("EXPLAIN (ANALYZE, FORMAT JSON) SELECT 1"));
        assertFalse(ScriptSplitter.requiresConfirmation("-- hi\n SELECT 1"));
        assertFalse(ScriptSplitter.requiresConfirmation("EXPLAIN SELECT 1"));
    }
    @Test void vendorModifiersCommentsAndDollarIdentifiersAreNotDelimiters() {
        assertEquals(1,ScriptSplitter.split("CREATE OR REPLACE EDITIONABLE PROCEDURE p AS BEGIN NULL; END;\n/","ORACLE").size());
        assertEquals(1,ScriptSplitter.split("BEGIN/* note */ NULL; END;\n/","ORACLE").size());
        assertEquals("SELECT foo$bar$baz FROM t",ScriptSplitter.split("SELECT foo$bar$baz FROM t;","POSTGRESQL").get(0).sql);
        assertEquals("BEGIN x:=q'[\n/\n]'; END;",ScriptSplitter.block("BEGIN x:=q'[\n/\n]'; END;\n/","ORACLE").sql);
        assertEquals("BEGIN x:=q'[\n/\n]'; END;",ScriptSplitter.block("BEGIN x:=q'[\n/\n]'; END;","ORACLE").sql);
    }
}
