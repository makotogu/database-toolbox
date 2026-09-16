package com.example.dbtoolbox.workbench.driver;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/** Loaded into each isolated loader, because DriverManager filters by the caller's loader. */
public final class DriverRegistrationCleanup {
    private DriverRegistrationCleanup() { }

    public static void deregister(ClassLoader owner) throws SQLException {
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver driver = registered.nextElement();
            if (driver.getClass().getClassLoader() == owner) {
                DriverManager.deregisterDriver(driver);
            }
        }
    }
}
