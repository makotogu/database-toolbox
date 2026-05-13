package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.common.Ids;
import com.example.dbtoolbox.common.StringChecks;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
public class DataSourceService {

    private final DataSourceStore store;
    private final JdbcConnectionFactory connectionFactory;

    public DataSourceService(DataSourceStore store, JdbcConnectionFactory connectionFactory) {
        this.store = store;
        this.connectionFactory = connectionFactory;
    }

    public List<DataSourceView> list() {
        List<DataSourceView> views = new ArrayList<DataSourceView>();
        for (DataSourceConfig config : store.readAll()) {
            views.add(DataSourceView.from(config));
        }
        return views;
    }

    public DataSourceView save(DataSourceRequest request) {
        validateRequest(request);
        List<DataSourceConfig> configs = store.readAll();
        Instant now = Instant.now();
        DataSourceConfig existing = null;
        if (StringChecks.hasText(request.getId())) {
            for (DataSourceConfig config : configs) {
                if (config.getId().equals(request.getId())) {
                    existing = config;
                    break;
                }
            }
        }
        if (existing == null) {
            existing = new DataSourceConfig();
            existing.setId(Ids.newId());
            existing.setCreatedAt(now);
            configs.add(existing);
        }
        existing.setName(request.getName().trim());
        existing.setType(request.getType());
        existing.setHost(trimToNull(request.getHost()));
        existing.setPort(request.getPort());
        existing.setDatabaseName(trimToNull(request.getDatabaseName()));
        existing.setUsername(trimToNull(request.getUsername()));
        if (request.getPassword() != null && request.getPassword().length() > 0) {
            existing.setPassword(request.getPassword());
        } else if (!StringChecks.hasText(request.getId())) {
            existing.setPassword("");
        }
        existing.setJdbcUrl(trimToNull(request.getJdbcUrl()));
        existing.setParams(request.getParams());
        existing.setUpdatedAt(now);
        store.writeAll(configs);
        return DataSourceView.from(existing);
    }

    public void delete(String id) {
        List<DataSourceConfig> configs = store.readAll();
        boolean removed = false;
        Iterator<DataSourceConfig> iterator = configs.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getId().equals(id)) {
                iterator.remove();
                removed = true;
            }
        }
        if (!removed) {
            throw new AppException(HttpStatus.NOT_FOUND, "数据源不存在");
        }
        store.writeAll(configs);
    }

    public DataSourceConfig getConfig(String id) {
        for (DataSourceConfig config : store.readAll()) {
            if (config.getId().equals(id)) {
                return config;
            }
        }
        throw new AppException(HttpStatus.NOT_FOUND, "数据源不存在");
    }

    public DataSourceTestResult test(String id) {
        return testConfig(getConfig(id));
    }

    public DataSourceTestResult testRequest(DataSourceRequest request) {
        validateRequest(request);
        DataSourceConfig config = toTempConfig(request);
        return testConfig(config);
    }

    private DataSourceTestResult testConfig(DataSourceConfig config) {
        long start = System.currentTimeMillis();
        DataSourceTestResult result = new DataSourceTestResult();
        try (Connection connection = connectionFactory.open(config)) {
            DatabaseMetaData metaData = connection.getMetaData();
            result.setSuccess(true);
            result.setMessage("连接成功");
            result.setProductName(metaData.getDatabaseProductName());
            result.setProductVersion(metaData.getDatabaseProductVersion());
        } catch (Exception ex) {
            result.setSuccess(false);
            result.setMessage(ex.getMessage());
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    private DataSourceConfig toTempConfig(DataSourceRequest request) {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(request.getId());
        config.setName(request.getName());
        config.setType(request.getType());
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDatabaseName(request.getDatabaseName());
        config.setUsername(request.getUsername());
        config.setPassword(request.getPassword());
        config.setJdbcUrl(request.getJdbcUrl());
        config.setParams(request.getParams());
        return config;
    }

    private void validateRequest(DataSourceRequest request) {
        if (request.getType() == null) {
            throw new AppException("数据库类型不能为空");
        }
        StringChecks.requireText(request.getName(), "数据源名称不能为空");
        if (!StringChecks.hasText(request.getJdbcUrl())) {
            StringChecks.requireText(request.getHost(), "主机不能为空");
            StringChecks.requireText(request.getDatabaseName(), "数据库名不能为空");
        }
    }

    private String trimToNull(String value) {
        if (!StringChecks.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
