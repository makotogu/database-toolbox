package com.example.dbtoolbox.workbench.connection;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConnectionProfile {
    public boolean saveSqlDrafts;
    // Server-owned opt-in generation; never copied to the public profile.
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String sqlDraftEpoch;
    public String id;
    public String name;
    public String driverId;
    public String jdbcUrl;
    public String username;
    public String password;
    public String dialectHint;
    public String catalog;
    public String schema;
    public Map<String, String> properties = new LinkedHashMap<String, String>();
    public boolean clearPassword;
    public boolean hasPassword;
    public boolean hasSecretProperties;
    public boolean needsDriver;
    public String legacyType;
    public Map<String, String> legacyFields = new LinkedHashMap<String, String>();
}
