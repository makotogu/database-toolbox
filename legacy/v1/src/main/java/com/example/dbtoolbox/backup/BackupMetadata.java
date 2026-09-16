package com.example.dbtoolbox.backup;

import com.example.dbtoolbox.datasource.DatabaseType;
import com.example.dbtoolbox.metadata.ColumnInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BackupMetadata {

    private String tableName;
    private DatabaseType databaseType;
    private Instant exportedAt;
    private List<ColumnInfo> columns = new ArrayList<ColumnInfo>();

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns == null ? new ArrayList<ColumnInfo>() : columns;
    }
}
