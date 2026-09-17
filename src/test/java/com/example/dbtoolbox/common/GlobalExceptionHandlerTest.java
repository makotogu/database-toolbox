package com.example.dbtoolbox.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test void unexpectedExceptionHasCorrelatedMessageWithoutSecretInResponseOrLogs(){
        Logger log=(Logger)LoggerFactory.getLogger(ErrorMessages.class);ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();log.addAppender(appender);
        try{
            String secret="synthetic-value password=should-never-be-logged SELECT private_fixture FROM t";
            String message=new GlobalExceptionHandler().handleException(new RuntimeException(secret)).getBody().getMessage();
            assertTrue(message.contains("错误编号"));assertFalse(message.contains("synthetic-value"));assertEquals(1,appender.list.size());
            ILoggingEvent event=appender.list.get(0);assertNull(event.getThrowableProxy());assertFalse(event.getFormattedMessage().contains("SELECT"));assertTrue(message.endsWith(String.valueOf(event.getArgumentArray()[1])));
        }finally{log.detachAppender(appender);}
    }
    @Test void appMessagesAndSqlDiagnosticsDoNotLeakCredentialValues(){
        String message=ErrorMessages.redact("password='two words' sslPassword=abc token=xyz jdbc:vendor://user:pw@host/db");
        for(String value:new String[]{"two words","abc","xyz","user:pw"})assertFalse(message.contains(value));
        assertEquals("notice inside;block password=***",ErrorMessages.safe(new java.sql.SQLWarning("notice inside;block password=fixture")));
        String sql=ErrorMessages.safe(new SQLException("sensitive SQL and password=raw","42000",17));assertTrue(sql.contains("42000"));assertFalse(sql.contains("raw"));
        assertFalse(new GlobalExceptionHandler().handleAppException(new AppException("password=xyz")).getBody().getMessage().contains("xyz"));
    }
}
