package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.Ids;
import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.config.ToolboxProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class JobService {

    private final ConcurrentMap<String, JobRecord> jobs = new ConcurrentHashMap<String, JobRecord>();
    private final ConcurrentMap<String, Future<?>> futures = new ConcurrentHashMap<String, Future<?>>();
    private final ConcurrentMap<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<String, AtomicBoolean>();
    private final ExecutorService executorService;
    private final JobHistoryStore historyStore;
    private final StoragePaths paths;
    private final int maxJobHistory;

    public JobService(ToolboxProperties properties, JobHistoryStore historyStore, StoragePaths paths) {
        int poolSize = Math.max(1, properties.getJobPoolSize());
        this.executorService = Executors.newFixedThreadPool(poolSize);
        this.historyStore = historyStore;
        this.paths = paths;
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
                job.setStatus(JobStatus.CANCELED);
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
        final AtomicBoolean cancellationFlag = new AtomicBoolean(false);
        job.setId(Ids.newId());
        job.setType(type);
        job.setName(name);
        job.setMessage("等待执行");
        jobs.put(job.getId(), job);
        cancellationFlags.put(job.getId(), cancellationFlag);
        Future<?> future = executorService.submit(new Runnable() {
            public void run() {
                if (cancellationFlag.get()) {
                    finishCanceled(job, "用户请求中断");
                    return;
                }
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(Instant.now());
                job.setMessage("执行中");
                try {
                    work.run(new JobContext(job, cancellationFlag));
                    if (cancellationFlag.get() || job.getStatus() == JobStatus.CANCELED) {
                        finishCanceled(job, "用户请求中断");
                    } else if (job.getStatus() != JobStatus.FAILED) {
                        job.setStatus(JobStatus.SUCCESS);
                        job.setMessage(job.getMessage() == null ? "执行完成" : job.getMessage());
                    }
                } catch (JobCancelledException ex) {
                    finishCanceled(job, ex.getMessage());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    finishCanceled(job, "任务已中断");
                } catch (Exception ex) {
                    if (cancellationFlag.get() || Thread.currentThread().isInterrupted()) {
                        finishCanceled(job, "任务已中断");
                    } else {
                        job.setStatus(JobStatus.FAILED);
                        job.setMessage(ex.getMessage());
                    }
                } finally {
                    if (job.getFinishedAt() == null) {
                        job.setFinishedAt(Instant.now());
                    }
                    futures.remove(job.getId());
                    cancellationFlags.remove(job.getId());
                    persistHistory();
                }
            }
        });
        futures.put(job.getId(), future);
        return job;
    }

    public JobRecord cancel(String id) {
        JobRecord job = get(id);
        if (isTerminal(job)) {
            return job;
        }
        AtomicBoolean flag = cancellationFlags.get(id);
        if (flag != null) {
            flag.set(true);
        }
        job.setStatus(JobStatus.CANCELED);
        job.setMessage("用户请求中断，正在释放资源");
        Future<?> future = futures.get(id);
        if (future != null) {
            future.cancel(true);
        }
        if (job.getStartedAt() == null) {
            job.setFinishedAt(Instant.now());
            futures.remove(id);
            cancellationFlags.remove(id);
        }
        persistHistory();
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

    public FailureFileCleanupResult deleteFailureFile(String id) {
        JobRecord job = get(id);
        if (!isFinished(job)) {
            throw new AppException("任务仍在释放资源，不能清理失败文件");
        }
        FailureFileCleanupResult result = new FailureFileCleanupResult();
        deleteFailureFile(job, result);
        persistHistory();
        return result;
    }

    public synchronized FailureFileCleanupResult cleanupFailureFiles() {
        FailureFileCleanupResult result = new FailureFileCleanupResult();
        Set<Path> protectedFiles = new HashSet<Path>();
        for (JobRecord job : jobs.values()) {
            Path file = safeFailureFile(job);
            if (file == null) {
                continue;
            }
            if (isFinished(job)) {
                deleteFailureFile(job, result);
            } else {
                protectedFiles.add(file);
            }
        }
        deleteOrphanFailureFiles(protectedFiles, result);
        persistHistory();
        return result;
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
            if (isFinished(job)) {
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

    private void finishCanceled(JobRecord job, String message) {
        job.setStatus(JobStatus.CANCELED);
        job.setMessage(message == null ? "任务已中断" : message);
    }

    private boolean isTerminal(JobRecord job) {
        JobStatus status = job.getStatus();
        return status == JobStatus.SUCCESS || status == JobStatus.FAILED || status == JobStatus.CANCELED;
    }

    private boolean isFinished(JobRecord job) {
        return isTerminal(job) && job.getFinishedAt() != null;
    }

    private void deleteFailureFile(JobRecord job, FailureFileCleanupResult result) {
        Path file = safeFailureFile(job);
        if (file == null) {
            job.setFailureFile(null);
            return;
        }
        deleteFile(file, result);
        job.setFailureFile(null);
    }

    private Path safeFailureFile(JobRecord job) {
        String value = job.getFailureFile();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        Path failureDir = paths.failureDir().toAbsolutePath().normalize();
        Path file = Paths.get(value).toAbsolutePath().normalize();
        if (!file.startsWith(failureDir)) {
            return null;
        }
        return file;
    }

    private void deleteOrphanFailureFiles(Set<Path> protectedFiles, FailureFileCleanupResult result) {
        Path failureDir = paths.failureDir().toAbsolutePath().normalize();
        if (!Files.isDirectory(failureDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(failureDir, "*.csv")) {
            for (Path candidate : stream) {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (!protectedFiles.contains(normalized)) {
                    deleteFile(normalized, result);
                }
            }
        } catch (Exception ignored) {
            // 清理失败不能影响任务列表；用户可以下次再点清理。
        }
    }

    private void deleteFile(Path file, FailureFileCleanupResult result) {
        try {
            long bytes = Files.exists(file) ? Files.size(file) : 0L;
            if (Files.deleteIfExists(file)) {
                result.addDeletedFile(bytes);
            }
        } catch (Exception ignored) {
            // 单个文件清理失败时跳过，避免一个被占用的文件阻断整批清理。
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
        persistHistory();
    }
}
