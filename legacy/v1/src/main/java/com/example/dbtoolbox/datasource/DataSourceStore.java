package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.EncryptedJsonFileStore;
import com.example.dbtoolbox.common.StoragePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DataSourceStore {

    private final EncryptedJsonFileStore<List<DataSourceConfig>> store;

    public DataSourceStore(StoragePaths paths, ObjectMapper objectMapper) {
        this.store = new EncryptedJsonFileStore<List<DataSourceConfig>>(
                paths.configDir().resolve("datasources.enc"),
                paths.keyFile(),
                objectMapper,
                new TypeReference<List<DataSourceConfig>>() {
                },
                new java.util.function.Supplier<List<DataSourceConfig>>() {
                    public List<DataSourceConfig> get() {
                        return new ArrayList<DataSourceConfig>();
                    }
                });
    }

    public List<DataSourceConfig> readAll() {
        return store.read();
    }

    public void writeAll(List<DataSourceConfig> configs) {
        store.write(configs);
    }
}
