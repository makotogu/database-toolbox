package com.example.dbtoolbox.job;

public interface JobWork {

    void run(JobContext context) throws Exception;
}
