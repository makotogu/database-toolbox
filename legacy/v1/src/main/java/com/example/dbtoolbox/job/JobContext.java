package com.example.dbtoolbox.job;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class JobContext {

    private final JobRecord job;
    private final AtomicBoolean cancellationRequested;

    public JobContext(JobRecord job) {
        this(job, new AtomicBoolean(false));
    }

    public JobContext(JobRecord job, AtomicBoolean cancellationRequested) {
        this.job = job;
        this.cancellationRequested = cancellationRequested;
    }

    public String jobId() {
        return job.getId();
    }

    public void message(String message) {
        checkCancelled();
        job.setMessage(message);
    }

    public void processed(long value) {
        checkCancelled();
        job.setProcessedRows(value);
    }

    public long processedRows() {
        return job.getProcessedRows();
    }

    public void addProcessed(long delta) {
        checkCancelled();
        synchronized (job) {
            job.setProcessedRows(job.getProcessedRows() + delta);
        }
    }

    public long failedRows() {
        return job.getFailedRows();
    }

    public void addFailed(long delta) {
        checkCancelled();
        synchronized (job) {
            job.setFailedRows(job.getFailedRows() + delta);
        }
    }

    public void artifact(Path artifact) {
        checkCancelled();
        job.setArtifact(artifact == null ? null : artifact.toAbsolutePath().normalize().toString());
    }

    public void failureFile(Path file) {
        job.setFailureFile(file == null ? null : file.toAbsolutePath().normalize().toString());
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get() || Thread.currentThread().isInterrupted();
    }

    public void checkCancelled() {
        if (isCancellationRequested()) {
            throw new JobCancelledException();
        }
    }
}
