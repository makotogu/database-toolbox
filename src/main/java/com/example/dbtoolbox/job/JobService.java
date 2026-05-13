package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.Ids;
import com.example.dbtoolbox.config.ToolboxProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
    private final JobHistoryStore historyStore;
    private final int maxJobHistory;

    public JobService(ToolboxProperties properties, JobHistoryStore historyStore) {
        int poolSize = Math.max(1, properties.getJobPoolSize());
        this.executorService = Executors.newFixedThreadPool(poolSize);
        this.historyStore = historyStore;
        this.maxJobHistory = Math.max(1, properties.getMaxJobHistory());
    }

    @PostConstruct
    public void loadHistory() {
        /*
         * 启动时把上次落盘的任务历史读回内存，让"任务历史/看板"在重启后仍然可见。
         * 上次进程异常退出时停在 RUNNING/PENDING 的任务实际工作早就被打断，回读时统一改记为 FAILED，
         * 避免页面上一直挂着永远不会结束的"运行中"任务。
         */
        List<JobRecord> persisted = historyStore.read();
        Instant now = Instant.now();
        for (JobRecord job : persisted) {
            if (job == null || job.getId() == null) {
                continue;
            }
            if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.PENDING) {
                job.setStatus(JobStatus.FAILED);
                job.setMessage("应用重启，任务被中断");
                if (job.getFinishedAt() == null) {
                    job.setFinishedAt(now);
                }
            }
            jobs.put(job.getId(), job);
        }
        if (!persisted.isEmpty()) {
            // 启动时立刻把"中断"状态回写一次，保证再次崩溃前页面看到的状态和文件一致。
            persistHistory();
        }
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
                    persistHistory();
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

    private synchronized void persistHistory() {
        /*
         * 保留策略：
         * 1. 运行中/等待中的任务必须保留，不能被淘汰，否则页面会丢失正在跑的任务条目。
         * 2. 终态任务按 createdAt 倒序保留最多 maxJobHistory 条，超额的从内存和文件里一起清掉。
         * 持久化只在终态切换和启动时触发，避免高频 message 更新带来的写放大。
         */
        List<JobRecord> sorted = list();
        List<JobRecord> live = new ArrayList<JobRecord>();
        List<JobRecord> terminal = new ArrayList<JobRecord>();
        for (JobRecord job : sorted) {
            if (job.getStatus() == JobStatus.SUCCESS || job.getStatus() == JobStatus.FAILED) {
                terminal.add(job);
            } else {
                live.add(job);
            }
        }
        if (terminal.size() > maxJobHistory) {
            for (JobRecord evicted : terminal.subList(maxJobHistory, terminal.size())) {
                jobs.remove(evicted.getId());
            }
            terminal = new ArrayList<JobRecord>(terminal.subList(0, maxJobHistory));
        }
        List<JobRecord> toPersist = new ArrayList<JobRecord>(live.size() + terminal.size());
        toPersist.addAll(live);
        toPersist.addAll(terminal);
        try {
            historyStore.write(toPersist);
        } catch (Exception ex) {
            // 持久化失败不能影响业务线程；下一次终态切换会再尝试一次。
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
        persistHistory();
    }
}
