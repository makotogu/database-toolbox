package com.example.dbtoolbox.workbench.execution;

import java.sql.*;
import java.io.*;
import java.math.*;
import java.util.*;
import com.example.dbtoolbox.workbench.execution.ExecutionRecord.*;

public final class ResultReader {
    public static class Budget { public int rows, items; public long bytes; public boolean exhausted(){return rows>=10000||bytes>=16*1024*1024;} }
    private ResultReader(){ }
    public static void consume(Statement statement,boolean hasResult,UnitResult unit,int maxRows,Budget budget) throws SQLException {
        while(true) {
            if(hasResult) {
                try(ResultSet rs=statement.getResultSet()) {
                    Result result=read(rs,maxRows,budget);
                    if(budget.items++<100)unit.results.add(result); else warnOnce(unit,"超过 100 个结果项，后续结果不再保留");
                }
            } else {
                long count=statement.getUpdateCount(); if(count==-1)break;
                if(budget.items++<100){Result result=new Result();result.kind="UPDATE_COUNT";result.updateCount=count;unit.results.add(result);}
                else warnOnce(unit,"超过 100 个结果项，后续结果不再保留");
            }
            try { hasResult=statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT); }
            catch(SQLFeatureNotSupportedException ex) { hasResult=statement.getMoreResults(); }
        }
        SQLWarning warning=statement.getWarnings();int count=0;
        while(warning!=null&&count++<50){unit.warnings.add(SessionService.safe(warning));warning=warning.getNextWarning();}
        statement.clearWarnings();
    }
    public static Result read(ResultSet rs,int maxRows,Budget budget) throws SQLException {
        Result result=new Result();result.kind="RESULT_SET";
        if(rs==null)return result;
        ResultSetMetaData md=rs.getMetaData();
        for(int i=1;i<=md.getColumnCount();i++) {
            Column c=new Column();c.index=i;c.label=md.getColumnLabel(i);c.jdbcType=md.getColumnType(i);c.typeName=md.getColumnTypeName(i);result.columns.add(c);
        }
        Result cell = new Result();
        while(rs.next()) {
            if(result.rows.size()>=maxRows || budget.exhausted() || budget.items>=100) {result.truncated=true;break;}
            List<Object> row=new ArrayList<Object>();
            for(Column c:result.columns) {
                cell.truncated=false;
                Object value=value(rs,c.index,c.jdbcType,cell);
                if(cell.truncated){result.truncated=true;result.truncatedCells.add(Arrays.asList(result.rows.size(),c.index-1));}
                row.add(value);budget.bytes+=value==null?4:String.valueOf(value).length()*2L;
                if(budget.bytes>16*1024*1024) {result.truncated=true;break;}
            }
            if(row.size()==result.columns.size()){result.rows.add(row);budget.rows++;}
            else break;
        }
        return result;
    }
    private static Object value(ResultSet rs,int index,int type,Result result) throws SQLException {
        switch(type) {
            case Types.BINARY:case Types.VARBINARY:case Types.LONGVARBINARY:case Types.BLOB:
                try(InputStream in=rs.getBinaryStream(index)) {
                    if(in==null)return null;
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
                    while(out.size()<32769 && (n=in.read(b,0,Math.min(b.length,32769-out.size())))!=-1)out.write(b,0,n);
                    byte[] data=out.toByteArray();if(data.length>32768){result.truncated=true;data=Arrays.copyOf(data,32768);}
                    return "base64:"+Base64.getEncoder().encodeToString(data);
                } catch(IOException ex){throw new SQLException("读取二进制预览失败",ex);}
            case Types.CLOB:case Types.NCLOB:case Types.LONGVARCHAR:case Types.LONGNVARCHAR:
                try(Reader reader=rs.getCharacterStream(index)) {
                    if(reader==null)return null;
                    StringBuilder s=new StringBuilder();char[] b=new char[4096];int n;
                    while(s.length()<32769 &&(n=reader.read(b,0,Math.min(b.length,32769-s.length())))!=-1)s.append(b,0,n);
                    if(s.length()>32768){result.truncated=true;return s.substring(0,32768)+"…";}return s.toString();
                }catch(IOException ex){throw new SQLException("读取文本预览失败",ex);}
            case Types.BIGINT:case Types.NUMERIC:case Types.DECIMAL:case Types.DATE:case Types.TIME:case Types.TIMESTAMP:case Types.TIME_WITH_TIMEZONE:case Types.TIMESTAMP_WITH_TIMEZONE:
                return clipped(rs.getString(index),result);
            default:return normalize(rs.getObject(index),result);
        }
    }
    public static Object normalize(Object value,Result result) {
        if(value==null||value instanceof Boolean||value instanceof Integer||value instanceof Short||value instanceof Byte)return value;
        if(value instanceof Float||value instanceof Double){double d=((Number)value).doubleValue();return Double.isFinite(d)?value:String.valueOf(value);}
        if(value instanceof byte[]){byte[] bytes=(byte[])value;if(bytes.length>32768){result.truncated=true;bytes=Arrays.copyOf(bytes,32768);}return "base64:"+Base64.getEncoder().encodeToString(bytes);}
        return clipped(String.valueOf(value),result);
    }
    private static String clipped(String s,Result result){if(s!=null&&s.length()>32768){result.truncated=true;return s.substring(0,32768)+"…";}return s;}
    private static void warnOnce(UnitResult unit,String message){if(!unit.warnings.contains(message))unit.warnings.add(message);}
}
