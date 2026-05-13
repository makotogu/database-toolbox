package com.example.dbtoolbox.dashboard;

import com.example.dbtoolbox.common.StoragePaths;
import com.example.dbtoolbox.datasource.DataSourceService;
import com.example.dbtoolbox.job.JobRecord;
import com.example.dbtoolbox.job.JobService;
import com.example.dbtoolbox.job.JobStatus;
import com.example.dbtoolbox.sync.SyncService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Service
public class DashboardService {

    private final DataSourceService dataSourceService;
    private final SyncService syncService;
    private final JobService jobService;
    private final StoragePaths storagePaths;

    public DashboardService(DataSourceService dataSourceService,
                            SyncService syncService,
                            JobService jobService,
                            StoragePaths storagePaths) {
        this.dataSourceService = dataSourceService;
        this.syncService = syncService;
        this.jobService = jobService;
        this.storagePaths = storagePaths;
    }

    public DashboardOverview overview() {
        List<JobRecord> jobs = jobService.list();
        DashboardOverview overview = new DashboardOverview();
        overview.setDatasourceCount(dataSourceService.list().size());
        overview.setSyncTaskCount(syncService.list().size());
        overview.setBackupFileCount(countBackupFiles());
        overview.setJobCount(jobs.size());
        overview.setRunningJobCount(countStatus(jobs, JobStatus.RUNNING) + countStatus(jobs, JobStatus.PENDING));
        overview.setSuccessJobCount(countStatus(jobs, JobStatus.SUCCESS));
        overview.setFailedJobCount(countStatus(jobs, JobStatus.FAILED));
        overview.setRecentJobs(jobs.subList(0, Math.min(5, jobs.size())));
        return overview;
    }

    private int countStatus(List<JobRecord> jobs, JobStatus status) {
        int count = 0;
        for (JobRecord job : jobs) {
            if (job.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    private int countBackupFiles() {
        Path backupDir = storagePaths.backupDir();
        if (!Files.exists(backupDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(backupDir)) {
            return (int) stream.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".zip")).count();
        } catch (IOException ex) {
            return 0;
        }
    }
}
