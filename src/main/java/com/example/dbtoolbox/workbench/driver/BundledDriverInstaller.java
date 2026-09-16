package com.example.dbtoolbox.workbench.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;

/** Resources stay off the application JDBC classpath and use the regular isolated loader. */
@Component
@DependsOn("localRuntime")
public class BundledDriverInstaller {
    private final DriverService drivers;
    private final ObjectMapper mapper;

    public BundledDriverInstaller(DriverService drivers, ObjectMapper mapper) {
        this.drivers = drivers;
        this.mapper = mapper;
    }

    @PostConstruct
    public void install() {
        try (InputStream input = getClass().getResourceAsStream("/bundled-drivers/catalog.json")) {
            if (input == null) throw new IllegalStateException("发行包缺少内置驱动清单");
            DriverProfile[] profiles = mapper.readValue(input, DriverProfile[].class);
            for (DriverProfile profile : profiles) drivers.installBundled(profile);
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化内置 JDBC 驱动: " + ex.getMessage(), ex);
        }
    }
}
