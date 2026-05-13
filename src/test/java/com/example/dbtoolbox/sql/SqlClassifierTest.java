package com.example.dbtoolbox.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlClassifierTest {

    @Test
    void classifiesReadSqlWithLeadingComment() {
        assertEquals(SqlType.READ, SqlClassifier.classify("-- check\nselect * from users"));
        assertEquals(SqlType.READ, SqlClassifier.classify(" with t as (select 1) select * from t"));
        assertEquals(SqlType.READ, SqlClassifier.classify("explain select * from users"));
    }

    @Test
    void classifiesWriteSql() {
        assertEquals(SqlType.WRITE, SqlClassifier.classify("update users set name = 'a'"));
        assertEquals(SqlType.WRITE, SqlClassifier.classify("delete from users where id = 1"));
        assertEquals(SqlType.WRITE, SqlClassifier.classify("truncate table users"));
    }

    @Test
    void classifiesEmptyAsUnknown() {
        assertEquals(SqlType.UNKNOWN, SqlClassifier.classify("   "));
    }
}
