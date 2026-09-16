package com.example.dbtoolbox.job;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StoragePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Component
public class JobHistoryStore {

    /*
     * 任务历史持久化是本地工具箱的"看板/任务历史"功能依赖。
     * 任务元信息不含敏感字段（不存连接串/密码），所以直接用明文 JSON，方便用户外部查看。
     * 写盘使用临时文件 + 原子替换，防止进程异常退出时留下半截 JSON 把历史全部读丢。
     */
    private final Path file;
    private final ObjectMapper objectMapper;

    public JobHistoryStore(StoragePaths paths, ObjectMapper objectMapper) {
        this.file = paths.jobHistoryFile();
        this.objectMapper = objectMapper;
    }

    public synchronized List<JobRecord> read() {
        if (!Files.exists(file)) {
            return new ArrayList<JobRecord>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ArrayList<JobRecord>();
            }
            return objectMapper.readValue(bytes, new TypeReference<List<JobRecord>>() {
            });
        } catch (IOException ex) {
            /*
             * 历史文件损坏不应该阻塞工具启动；否则一次磁盘损坏就让用户连数据源管理也用不了。
             * 直接返回空列表，让 JobService 继续运行；下一次终态任务回写时会覆盖坏文件。
             */
            return new ArrayList<JobRecord>();
        }
    }

    public synchronized void write(List<JobRecord> records) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(records);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new AppException("写入任务历史失败: " + ex.getMessage());
        }
    }
}
