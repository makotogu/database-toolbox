package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void cancelsRunningJobThroughContextFlag() throws Exception {
        JobService service = newService();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        JobRecord job = service.submit("SYNC", "slow sync", new JobWork() {
            public void run(JobContext context) throws Exception {
                started.countDown();
                try {
                    while (true) {
                        context.checkCancelled();
                        Thread.sleep(20L);
                    }
                } finally {
                    finished.countDown();
                }
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        service.cancel(job.getId());

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(JobStatus.CANCELED, service.get(job.getId()).getStatus());
        service.shutdown();
    }

    @Test
    void deletesFailureFileAndClearsJobReference() throws Exception {
        JobService service = newService();
        final Path failureFile = tempDir.resolve("failures").resolve("sync-job-failures.csv");

        JobRecord job = service.submit("SYNC", "failed rows", new JobWork() {
            public void run(JobContext context) throws Exception {
                Files.createDirectories(failureFile.getParent());
                Files.write(failureFile, "id,failure_reason\n1,bad row\n".getBytes("UTF-8"));
                context.failureFile(failureFile);
            }
        });

        waitTerminal(service, job.getId());
        assertTrue(Files.exists(failureFile));

        FailureFileCleanupResult result = service.deleteFailureFile(job.getId());

        assertEquals(1, result.getDeletedFiles());
        assertFalse(Files.exists(failureFile));
        assertNull(service.get(job.getId()).getFailureFile());
        service.shutdown();
    }

    private void waitTerminal(JobService service, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            JobStatus status = service.get(id).getStatus();
            if (status == JobStatus.SUCCESS || status == JobStatus.FAILED || status == JobStatus.CANCELED) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("job did not finish");
    }

    private JobService newService() {
        ToolboxProperties properties = new ToolboxProperties();
        properties.setStorageRoot(tempDir.toString());
        properties.setJobPoolSize(1);
        StoragePaths paths = new StoragePaths(properties);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JobService(properties, new JobHistoryStore(paths, objectMapper), paths);
    }
}
