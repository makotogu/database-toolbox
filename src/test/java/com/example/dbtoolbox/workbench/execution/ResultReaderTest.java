package com.example.dbtoolbox.workbench.execution;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResultReaderTest {
    @Test void mixedResultsContinueAcrossZeroUpdateAndCloseEachResult()throws Exception{
        Statement statement=mock(Statement.class);ResultSet first=rows(11),second=rows(22);
        when(statement.getResultSet()).thenReturn(first,second);
        when(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(false,true,false);
        when(statement.getUpdateCount()).thenReturn(0,-1);
        ExecutionRecord.UnitResult unit=new ExecutionRecord.UnitResult();ResultReader.consume(statement,true,unit,500,new ResultReader.Budget());
        assertEquals(3,unit.results.size());assertEquals(11,unit.results.get(0).rows.get(0).get(0));assertEquals("UPDATE_COUNT",unit.results.get(1).kind);assertEquals(0,unit.results.get(1).updateCount);assertEquals(22,unit.results.get(2).rows.get(0).get(0));
        verify(first).close();verify(second).close();verify(statement,times(3)).getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }
    private ResultSet rows(int value)throws Exception{ResultSet rs=mock(ResultSet.class);ResultSetMetaData md=mock(ResultSetMetaData.class);when(rs.getMetaData()).thenReturn(md);when(md.getColumnCount()).thenReturn(1);when(md.getColumnType(1)).thenReturn(Types.INTEGER);when(md.getColumnLabel(1)).thenReturn("id");when(rs.next()).thenReturn(true,false);when(rs.getObject(1)).thenReturn(value);return rs;}
}
