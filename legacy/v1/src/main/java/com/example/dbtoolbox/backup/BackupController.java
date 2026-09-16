package com.example.dbtoolbox.backup;

import com.example.dbtoolbox.common.ApiResponse;
import com.example.dbtoolbox.job.JobRecord;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @PostMapping("/export")
    public ApiResponse<JobRecord> export(@Valid @RequestBody BackupRequest request) {
        return ApiResponse.ok(backupService.exportAsync(request));
    }

    @PostMapping("/restore")
    public ApiResponse<JobRecord> restore(@Valid @RequestBody RestoreRequest request) {
        return ApiResponse.ok(backupService.restoreAsync(request));
    }

    @GetMapping("/files/{fileName:.+}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        return backupService.download(fileName);
    }
}
