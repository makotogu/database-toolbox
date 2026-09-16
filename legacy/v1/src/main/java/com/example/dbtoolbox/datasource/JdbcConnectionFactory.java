package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.StringChecks;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

@Component
public class JdbcConnectionFactory {

    private final DialectRegistry dialectRegistry;

    public JdbcConnectionFactory(DialectRegistry dialectRegistry) {
        this.dialectRegistry = dialectRegistry;
    }

    public Connection open(DataSourceConfig config) {
        try {
            DatabaseDialect dialect = dialectRegistry.get(config.getType());
            String driverClassName = overrideDriverClassName(config, dialect);
            Class.forName(driverClassName);
            /*
             * jdbcUrl 是高级入口：现场数据库 URL 规则和默认方言不一致时，页面直接填完整 URL。
             * 未填写 jdbcUrl 时，才回退到方言按 host/port/database/params 组装。
             */
            String url = StringChecks.hasText(config.getJdbcUrl())
                    ? config.getJdbcUrl().trim()
                    : dialect.buildJdbcUrl(config);
            Properties properties = new Properties();
            if (config.getUsername() != null) {
                properties.put("user", config.getUsername());
            }
            if (config.getPassword() != null) {
                properties.put("password", config.getPassword());
            }
            return DriverManager.getConnection(url, properties);
        } catch (SQLException ex) {
            throw new AppException("数据库连接失败: " + ex.getMessage());
        } catch (ClassNotFoundException ex) {
            throw new AppException("数据库驱动不存在: " + ex.getMessage());
        }
    }

    private String overrideDriverClassName(DataSourceConfig config, DatabaseDialect dialect) {
        // GaussDB 等兼容库可能使用非默认驱动，允许通过连接参数局部覆盖，不需要新增方言类。
        if (config.getParams() != null && StringChecks.hasText(config.getParams().get("driverClassName"))) {
            return config.getParams().get("driverClassName").trim();
        }
        return dialect.driverClassName();
    }
}
