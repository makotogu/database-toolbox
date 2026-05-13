package com.example.dbtoolbox.common;

import com.example.dbtoolbox.config.ToolboxProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class StoragePaths {

    private final Path root;

    public StoragePaths(ToolboxProperties properties) {
        this.root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path configDir() {
        return root.resolve("config");
    }

    public Path backupDir() {
        return root.resolve("backups");
    }

    public Path failureDir() {
        return root.resolve("failures");
    }

    public Path keyFile() {
        return configDir().resolve("master.key");
    }

    public Path jobHistoryFile() {
        return configDir().resolve("jobs.json");
    }
}
