package com.example.dbtoolbox.job;

public class JobCancelledException extends RuntimeException {

    public JobCancelledException() {
        super("任务已中断");
    }
}
