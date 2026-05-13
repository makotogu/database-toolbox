package com.example.dbtoolbox;

import com.example.dbtoolbox.config.ToolboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ToolboxProperties.class)
public class DatabaseToolboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseToolboxApplication.class, args);
    }
}
