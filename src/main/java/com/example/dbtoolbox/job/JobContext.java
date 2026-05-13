package com.example.dbtoolbox.job;

import java.nio.file.Path;

public class JobContext {

    private final JobRecord job;

    public JobContext(JobRecord job) {
        this.job = job;
    }

    public String jobId() {
        return job.getId();
    }

    public void message(String message) {
        job.setMessage(message);
    }

    public void processed(long value) {
        job.setProcessedRows(value);
    }

    public void addProcessed(long delta) {
        job.setProcessedRows(job.getProcessedRows() + delta);
    }

    public void addFailed(long delta) {
        job.setFailedRows(job.getFailedRows() + delta);
    }

    public void artifact(Path artifact) {
        job.setArtifact(artifact == null ? null : artifact.toAbsolutePath().normalize().toString());
    }

    public void failureFile(Path file) {
        job.setFailureFile(file == null ? null : file.toAbsolutePath().normalize().toString());
    }
}
