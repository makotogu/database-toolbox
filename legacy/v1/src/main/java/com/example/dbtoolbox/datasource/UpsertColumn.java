package com.example.dbtoolbox.datasource;

public class UpsertColumn {

    private final String name;
    private final String typeName;

    public UpsertColumn(String name, String typeName) {
        this.name = name;
        this.typeName = typeName;
    }

    public String getName() {
        return name;
    }

    public String getTypeName() {
        return typeName;
    }
}
