package com.example.dbtoolbox.job;

public class FailureFileCleanupResult {

    private int deletedFiles;
    private long deletedBytes;

    public int getDeletedFiles() {
        return deletedFiles;
    }

    public void setDeletedFiles(int deletedFiles) {
        this.deletedFiles = deletedFiles;
    }

    public long getDeletedBytes() {
        return deletedBytes;
    }

    public void setDeletedBytes(long deletedBytes) {
        this.deletedBytes = deletedBytes;
    }

    public void addDeletedFile(long bytes) {
        this.deletedFiles++;
        this.deletedBytes += Math.max(0L, bytes);
    }
}
