package com.example.dbtoolbox.datasource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class DataSourceView {

    private String id;
    private String name;
    private DatabaseType type;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String jdbcUrl;
    private boolean hasPassword;
    private Map<String, String> params = new LinkedHashMap<String, String>();
    private Instant createdAt;
    private Instant updatedAt;

    public static DataSourceView from(DataSourceConfig config) {
        DataSourceView view = new DataSourceView();
        view.setId(config.getId());
        view.setName(config.getName());
        view.setType(config.getType());
        view.setHost(config.getHost());
        view.setPort(config.getPort());
        view.setDatabaseName(config.getDatabaseName());
        view.setUsername(config.getUsername());
        view.setJdbcUrl(config.getJdbcUrl());
        view.setHasPassword(config.getPassword() != null && config.getPassword().length() > 0);
        view.setParams(config.getParams());
        view.setCreatedAt(config.getCreatedAt());
        view.setUpdatedAt(config.getUpdatedAt());
        return view;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DatabaseType getType() {
        return type;
    }

    public void setType(DatabaseType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public boolean isHasPassword() {
        return hasPassword;
    }

    public void setHasPassword(boolean hasPassword) {
        this.hasPassword = hasPassword;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params == null ? new LinkedHashMap<String, String>() : params;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
