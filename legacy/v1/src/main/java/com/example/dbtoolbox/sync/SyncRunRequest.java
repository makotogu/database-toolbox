package com.example.dbtoolbox.sync;

import javax.validation.constraints.NotBlank;

public class SyncRunRequest {

    @NotBlank(message = "同步任务不能为空")
    private String taskId;

    private Boolean backupBeforeWrite;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Boolean getBackupBeforeWrite() {
        return backupBeforeWrite;
    }

    public void setBackupBeforeWrite(Boolean backupBeforeWrite) {
        this.backupBeforeWrite = backupBeforeWrite;
    }
}
