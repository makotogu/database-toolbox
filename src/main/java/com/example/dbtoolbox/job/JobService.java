package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.Ids;
import com.example.dbtoolbox.config.ToolboxProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class JobService {

    private final ConcurrentMap<String, JobRecord> jobs = new ConcurrentHashMap<String, JobRecord>();
    private final ExecutorService executorService;

    public JobService(ToolboxProperties properties) {
        int poolSize = Math.max(1, properties.getJobPoolSize());
        this.executorService = Executors.newFixedThreadPool(poolSize);
    }

    public JobRecord submit(String type, String name, JobWork work) {
        final JobRecord job = new JobRecord();
        job.setId(Ids.newId());
        job.setType(type);
        job.setName(name);
        job.setMessage("等待执行");
        jobs.put(job.getId(), job);
        executorService.submit(new Runnable() {
            public void run() {
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(Instant.now());
                job.setMessage("执行中");
                try {
                    work.run(new JobContext(job));
                    if (job.getStatus() != JobStatus.FAILED) {
                        job.setStatus(JobStatus.SUCCESS);
                        job.setMessage(job.getMessage() == null ? "执行完成" : job.getMessage());
                    }
                } catch (Exception ex) {
                    job.setStatus(JobStatus.FAILED);
                    job.setMessage(ex.getMessage());
                } finally {
                    job.setFinishedAt(Instant.now());
                }
            }
        });
        return job;
    }

    public JobRecord get(String id) {
        JobRecord job = jobs.get(id);
        if (job == null) {
            throw new AppException(HttpStatus.NOT_FOUND, "任务不存在");
        }
        return job;
    }

    public List<JobRecord> list() {
        List<JobRecord> records = new ArrayList<JobRecord>(jobs.values());
        Collections.sort(records, new Comparator<JobRecord>() {
            public int compare(JobRecord left, JobRecord right) {
                return right.getCreatedAt().compareTo(left.getCreatedAt());
            }
        });
        return records;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }
}
