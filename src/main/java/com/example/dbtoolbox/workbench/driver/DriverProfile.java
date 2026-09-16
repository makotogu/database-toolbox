package com.example.dbtoolbox.workbench.driver;

import java.util.ArrayList;
import java.util.List;

/** One immutable set of uploaded JARs. Import an upgrade as a new profile. */
public class DriverProfile {
    public String id;
    public String name;
    public String driverClass;
    public List<String> candidates = new ArrayList<String>();
    public List<String> files = new ArrayList<String>();
    public List<String> sha256 = new ArrayList<String>();
    public int revision = 1;
    public String status;
    public boolean bundled;
    public String version;
    public String urlTemplate;
    public String sourceUrl;
    public String license;
}
