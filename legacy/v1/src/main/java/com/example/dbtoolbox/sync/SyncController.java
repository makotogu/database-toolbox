package com.example.dbtoolbox.sync;

import com.example.dbtoolbox.common.ApiResponse;
import com.example.dbtoolbox.job.JobRecord;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/sync-tasks")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping
    public ApiResponse<List<SyncTask>> list() {
        return ApiResponse.ok(syncService.list());
    }

    @PostMapping
    public ApiResponse<SyncTask> save(@Valid @RequestBody SyncTaskRequest request) {
        return ApiResponse.ok(syncService.save(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        syncService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/run")
    public ApiResponse<JobRecord> run(@Valid @RequestBody SyncRunRequest request) {
        return ApiResponse.ok(syncService.run(request));
    }

    @PostMapping("/probe-partitions")
    public ApiResponse<PartitionProbeResult> probePartitions(@Valid @RequestBody PartitionProbeRequest request) {
        return ApiResponse.ok(syncService.probePartitions(request));
    }
}
