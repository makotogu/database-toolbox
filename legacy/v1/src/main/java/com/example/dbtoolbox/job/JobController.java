package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public ApiResponse<List<JobRecord>> list() {
        return ApiResponse.ok(jobService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<JobRecord> get(@PathVariable String id) {
        return ApiResponse.ok(jobService.get(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<JobRecord> cancel(@PathVariable String id) {
        return ApiResponse.ok(jobService.cancel(id));
    }

    @DeleteMapping("/{id}/failure-file")
    public ApiResponse<FailureFileCleanupResult> deleteFailureFile(@PathVariable String id) {
        return ApiResponse.ok(jobService.deleteFailureFile(id));
    }

    @DeleteMapping("/failure-files")
    public ApiResponse<FailureFileCleanupResult> cleanupFailureFiles() {
        return ApiResponse.ok(jobService.cleanupFailureFiles());
    }
}
