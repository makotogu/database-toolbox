package com.example.dbtoolbox.dashboard;

import com.example.dbtoolbox.job.JobRecord;

import java.util.ArrayList;
import java.util.List;

public class DashboardOverview {

    private int datasourceCount;
    private int syncTaskCount;
    private int backupFileCount;
    private int jobCount;
    private int runningJobCount;
    private int successJobCount;
    private int failedJobCount;
    private List<JobRecord> recentJobs = new ArrayList<JobRecord>();

    public int getDatasourceCount() {
        return datasourceCount;
    }

    public void setDatasourceCount(int datasourceCount) {
        this.datasourceCount = datasourceCount;
    }

    public int getSyncTaskCount() {
        return syncTaskCount;
    }

    public void setSyncTaskCount(int syncTaskCount) {
        this.syncTaskCount = syncTaskCount;
    }

    public int getBackupFileCount() {
        return backupFileCount;
    }

    public void setBackupFileCount(int backupFileCount) {
        this.backupFileCount = backupFileCount;
    }

    public int getJobCount() {
        return jobCount;
    }

    public void setJobCount(int jobCount) {
        this.jobCount = jobCount;
    }

    public int getRunningJobCount() {
        return runningJobCount;
    }

    public void setRunningJobCount(int runningJobCount) {
        this.runningJobCount = runningJobCount;
    }

    public int getSuccessJobCount() {
        return successJobCount;
    }

    public void setSuccessJobCount(int successJobCount) {
        this.successJobCount = successJobCount;
    }

    public int getFailedJobCount() {
        return failedJobCount;
    }

    public void setFailedJobCount(int failedJobCount) {
        this.failedJobCount = failedJobCount;
    }

    public List<JobRecord> getRecentJobs() {
        return recentJobs;
    }

    public void setRecentJobs(List<JobRecord> recentJobs) {
        this.recentJobs = recentJobs == null ? new ArrayList<JobRecord>() : recentJobs;
    }
}
