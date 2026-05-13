package com.example.dbtoolbox.sync;

import com.example.dbtoolbox.common.AppException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncServiceValidationTest {

    /*
     * 校验只读 request 字段，不会进 store/db。这里用 null 依赖构造 SyncService 来直接观察
     * validate(...) 的拒绝路径，避免引入 Spring 上下文。
     */
    private final SyncService service = new SyncService(null, null, null, null, null, null, null);

    @Test
    void rejectsSyncFromAndToSameDatasourceAndTable() {
        SyncTaskRequest request = baseRequest();
        request.setSourceDatasourceId("ds-1");
        request.setTargetDatasourceId("ds-1");
        request.setSourceTable("public.user_profile");
        request.setTargetTable("public.user_profile");

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("同步源和目标"), ex.getMessage());
    }

    @Test
    void rejectsSyncFromAndToSameTableIgnoringIdentifierQuotingAndCase() {
        SyncTaskRequest request = baseRequest();
        request.setSourceDatasourceId("ds-1");
        request.setTargetDatasourceId("ds-1");
        request.setSourceTable("\"PUBLIC\".\"User_Profile\"");
        request.setTargetTable("public.user_profile");

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("同步源和目标"), ex.getMessage());
    }

    @Test
    void rejectsWhereClauseWithSemicolon() {
        SyncTaskRequest request = baseRequest();
        request.setWhereClause("tenant_id = 1; DROP TABLE users");

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("分号"), ex.getMessage());
    }

    @Test
    void rejectsWhereClauseWithSqlComment() {
        SyncTaskRequest request = baseRequest();
        request.setWhereClause("tenant_id = 1 -- ignore");

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("注释"), ex.getMessage());
    }

    @Test
    void rejectsWhereClauseWithBlockCommentStart() {
        SyncTaskRequest request = baseRequest();
        request.setWhereClause("tenant_id = 1 /* hacky");

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("注释"), ex.getMessage());
    }

    @Test
    void rejectsWhereClauseWithUnbalancedSingleQuote() {
        SyncTaskRequest request = baseRequest();
        request.setWhereClause("name = 'alice");

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("单引号"), ex.getMessage());
    }

    @Test
    void rejectsRequestWhereAllMappingsAreDisabled() {
        SyncTaskRequest request = baseRequest();
        FieldMapping disabled = new FieldMapping();
        disabled.setSourceColumn("id");
        disabled.setTargetColumn("id");
        disabled.setEnabled(false);
        request.setFieldMappings(Arrays.asList(disabled));

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("启用"), ex.getMessage());
    }

    @Test
    void rejectsMatchKeyThatPointsAtDisabledMapping() {
        SyncTaskRequest request = baseRequest();
        FieldMapping enabled = new FieldMapping();
        enabled.setSourceColumn("name");
        enabled.setTargetColumn("name");
        FieldMapping disabled = new FieldMapping();
        disabled.setSourceColumn("id");
        disabled.setTargetColumn("id");
        disabled.setEnabled(false);
        request.setFieldMappings(Arrays.asList(enabled, disabled));
        request.setMatchKeys(Collections.singletonList("id"));

        AppException ex = assertThrows(AppException.class, () -> service.save(request));
        assertTrue(ex.getMessage().contains("启用的目标字段"), ex.getMessage());
    }

    @Test
    void allowsBalancedSingleQuoteInWhereClause() {
        SyncTaskRequest request = baseRequest();
        request.setWhereClause("name = 'alice' AND status = 'ok'");

        // 仍然会因为 store==null 在保存阶段 NPE，但应当通过校验阶段；
        // 我们直接断言抛出的不是校验异常即可。
        Exception ex = assertThrows(Exception.class, () -> service.save(request));
        assertEquals(NullPointerException.class, ex.getClass(), "WHERE 校验不应该拦截合法表达式");
    }

    private SyncTaskRequest baseRequest() {
        SyncTaskRequest request = new SyncTaskRequest();
        request.setName("test");
        request.setSourceDatasourceId("ds-1");
        request.setTargetDatasourceId("ds-2");
        request.setSourceTable("source_table");
        request.setTargetTable("target_table");
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceColumn("id");
        mapping.setTargetColumn("id");
        request.setFieldMappings(Arrays.asList(mapping));
        request.setMatchKeys(Collections.singletonList("id"));
        return request;
    }
}
