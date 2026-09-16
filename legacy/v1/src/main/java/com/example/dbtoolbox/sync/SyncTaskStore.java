package com.example.dbtoolbox.sync;

import com.example.dbtoolbox.common.EncryptedJsonFileStore;
import com.example.dbtoolbox.common.StoragePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SyncTaskStore {

    private final EncryptedJsonFileStore<List<SyncTask>> store;

    public SyncTaskStore(StoragePaths paths, ObjectMapper objectMapper) {
        this.store = new EncryptedJsonFileStore<List<SyncTask>>(
                paths.configDir().resolve("sync-tasks.enc"),
                paths.keyFile(),
                objectMapper,
                new TypeReference<List<SyncTask>>() {
                },
                new java.util.function.Supplier<List<SyncTask>>() {
                    public List<SyncTask> get() {
                        return new ArrayList<SyncTask>();
                    }
                });
    }

    public List<SyncTask> readAll() {
        return store.read();
    }

    public void writeAll(List<SyncTask> tasks) {
        store.write(tasks);
    }
}
