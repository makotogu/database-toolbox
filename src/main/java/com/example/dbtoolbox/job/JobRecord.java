package com.example.dbtoolbox.job;

import java.time.Instant;

public class JobRecord {

    private String id;
    private String type;
    private String name;
    private JobStatus status = JobStatus.PENDING;
    private long processedRows;
    private long failedRows;
    private String message;
    private String artifact;
    private String failureFile;
    private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;

    public synchronized String getId() {
        return id;
    }

    public synchronized void setId(String id) {
        this.id = id;
    }

    public synchronized String getType() {
        return type;
    }

    public synchronized void setType(String type) {
        this.type = type;
    }

    public synchronized String getName() {
        return name;
    }

    public synchronized void setName(String name) {
        this.name = name;
    }

    public synchronized JobStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(JobStatus status) {
        this.status = status;
    }

    public synchronized long getProcessedRows() {
        return processedRows;
    }

    public synchronized void setProcessedRows(long processedRows) {
        this.processedRows = processedRows;
    }

    public synchronized long getFailedRows() {
        return failedRows;
    }

    public synchronized void setFailedRows(long failedRows) {
        this.failedRows = failedRows;
    }

    public synchronized String getMessage() {
        return message;
    }

    public synchronized void setMessage(String message) {
        this.message = message;
    }

    public synchronized String getArtifact() {
        return artifact;
    }

    public synchronized void setArtifact(String artifact) {
        this.artifact = artifact;
    }

    public synchronized String getFailureFile() {
        return failureFile;
    }

    public synchronized void setFailureFile(String failureFile) {
        this.failureFile = failureFile;
    }

    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public synchronized Instant getStartedAt() {
        return startedAt;
    }

    public synchronized void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public synchronized Instant getFinishedAt() {
        return finishedAt;
    }

    public synchronized void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
