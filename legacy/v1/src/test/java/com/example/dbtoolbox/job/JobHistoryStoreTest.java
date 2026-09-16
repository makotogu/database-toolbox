package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobHistoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsJobRecordsAsPlainJson() throws Exception {
        JobHistoryStore store = newStore();

        JobRecord success = new JobRecord();
        success.setId("job-1");
        success.setType("SYNC");
        success.setName("同步 user_profile");
        success.setStatus(JobStatus.SUCCESS);
        success.setProcessedRows(1024);
        success.setMessage("执行完成");
        success.setCreatedAt(Instant.parse("2026-04-01T10:00:00Z"));
        success.setFinishedAt(Instant.parse("2026-04-01T10:05:00Z"));

        JobRecord failed = new JobRecord();
        failed.setId("job-2");
        failed.setType("BACKUP_EXPORT");
        failed.setName("导出 orders");
        failed.setStatus(JobStatus.FAILED);
        failed.setMessage("驱动不存在");
        failed.setCreatedAt(Instant.parse("2026-04-01T11:00:00Z"));

        store.write(Arrays.asList(success, failed));

        Path file = tempDir.resolve("config").resolve("jobs.json");
        assertTrue(Files.exists(file));
        String raw = new String(Files.readAllBytes(file));
        assertTrue(raw.contains("job-1"), raw);
        assertTrue(raw.contains("SUCCESS"), raw);
        assertTrue(raw.contains("2026-04-01"), raw);

        List<JobRecord> restored = store.read();
        assertEquals(2, restored.size());
        assertEquals("job-1", restored.get(0).getId());
        assertEquals(JobStatus.SUCCESS, restored.get(0).getStatus());
        assertEquals(1024L, restored.get(0).getProcessedRows());
        assertEquals(JobStatus.FAILED, restored.get(1).getStatus());
    }

    @Test
    void returnsEmptyListWhenFileMissingOrCorrupt() throws Exception {
        JobHistoryStore store = newStore();
        assertEquals(0, store.read().size());

        Path file = tempDir.resolve("config").resolve("jobs.json");
        Files.createDirectories(file.getParent());
        Files.write(file, "{not json".getBytes());

        // 损坏文件不应该让 read() 抛异常，否则会阻塞应用启动。
        assertEquals(0, store.read().size());
    }

    private JobHistoryStore newStore() {
        ToolboxProperties properties = new ToolboxProperties();
        properties.setStorageRoot(tempDir.toString());
        StoragePaths paths = new StoragePaths(properties);
        // 和 Spring Boot 默认配置对齐：JSR310 模块 + ISO 字符串，避免和线上格式不一致。
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JobHistoryStore(paths, objectMapper);
    }
}
