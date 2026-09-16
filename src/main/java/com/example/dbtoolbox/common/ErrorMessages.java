package com.example.dbtoolbox.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import java.util.UUID;

/** Diagnostics must not serialize exception messages, SQL, request bodies, or JDBC URLs into logs. */
public final class ErrorMessages {
    private static final Logger LOG=LoggerFactory.getLogger(ErrorMessages.class);
    private ErrorMessages() { }
    public static String redact(String message) {
        if(message==null)return "操作失败";
        return message.replaceAll("(?is)SQL statement:.*", "SQL 内容已省略")
                .replaceAll("(?i)jdbc:[^\\s]+", "jdbc:<redacted>")
                .replaceAll("(?i)((?:[\\w.-]*(?:password|passwd|pwd|token|secret|credential)[\\w.-]*)[\\\"']?\\s*[=:]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}&]+)", "$1***");
    }
    public static String safe(Throwable ex) {
        if(ex instanceof java.sql.SQLWarning)return redact(ex.getMessage());
        if(ex instanceof SQLException) {
            SQLException sql=(SQLException)ex;
            String state=sql.getSQLState();
            return "数据库操作失败（SQLState "+(state!=null&&state.matches("[A-Z0-9]{5}")?state:"未知")+"，错误码 "+sql.getErrorCode()+"）";
        }
        if(ex instanceof AppException)return redact(ex.getMessage());
        return "操作失败（"+ex.getClass().getSimpleName()+"）";
    }
    public static String diagnostic(String operation, Throwable ex) {
        String id=UUID.randomUUID().toString();
        // Only application code locations; no Throwable argument or vendor exception text.
        StringBuilder frames=new StringBuilder();
        for(StackTraceElement frame:ex.getStackTrace())if(frame.getClassName().startsWith("com.example.dbtoolbox.")) {
            if(frames.length()>500)break;
            frames.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
        }
        LOG.warn("{} id={} type={} locations={}",operation,id,ex.getClass().getName(),frames);
        return id;
    }
}
