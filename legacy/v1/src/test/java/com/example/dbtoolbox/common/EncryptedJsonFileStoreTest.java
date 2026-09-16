package com.example.dbtoolbox.common;

import com.example.dbtoolbox.datasource.DataSourceConfig;
import com.example.dbtoolbox.datasource.DatabaseType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EncryptedJsonFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesJsonEncryptedOnDisk() throws Exception {
        Path file = tempDir.resolve("datasources.enc");
        Path key = tempDir.resolve("master.key");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EncryptedJsonFileStore<List<DataSourceConfig>> store = new EncryptedJsonFileStore<List<DataSourceConfig>>(
                file,
                key,
                objectMapper,
                new TypeReference<List<DataSourceConfig>>() {
                },
                new java.util.function.Supplier<List<DataSourceConfig>>() {
                    public List<DataSourceConfig> get() {
                        return new ArrayList<DataSourceConfig>();
                    }
                });

        DataSourceConfig config = new DataSourceConfig();
        config.setId("ds1");
        config.setName("local mysql");
        config.setType(DatabaseType.MYSQL);
        config.setPassword("secret-password");
        config.setCreatedAt(Instant.parse("2026-04-30T00:00:00Z"));

        store.write(Collections.singletonList(config));

        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(raw.contains("secret-password"));
        assertFalse(raw.contains("local mysql"));

        List<DataSourceConfig> restored = store.read();
        assertEquals(1, restored.size());
        assertEquals("secret-password", restored.get(0).getPassword());
        assertEquals(DatabaseType.MYSQL, restored.get(0).getType());
    }
}
