package com.example.dbtoolbox.sql;

import com.example.dbtoolbox.common.AppException;
import com.example.dbtoolbox.datasource.DataSourceConfig;
import com.example.dbtoolbox.datasource.DataSourceService;
import com.example.dbtoolbox.datasource.JdbcConnectionFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SqlService {

    private final DataSourceService dataSourceService;
    private final JdbcConnectionFactory connectionFactory;

    public SqlService(DataSourceService dataSourceService, JdbcConnectionFactory connectionFactory) {
        this.dataSourceService = dataSourceService;
        this.connectionFactory = connectionFactory;
    }

    public SqlExecutionResult execute(SqlExecutionRequest request) {
        SqlType type = SqlClassifier.classify(request.getSql());
        if (type == SqlType.UNKNOWN) {
            throw new AppException("无法识别SQL类型");
        }
        if (type == SqlType.WRITE && !request.isConfirmedWrite()) {
            return confirmationRequired(request.getSql(), type);
        }
        long start = System.currentTimeMillis();
        DataSourceConfig config = dataSourceService.getConfig(request.getDatasourceId());
        try (Connection connection = connectionFactory.open(config);
             Statement statement = connection.createStatement()) {
            SqlExecutionResult result = new SqlExecutionResult();
            result.setSqlType(type);
            if (type == SqlType.READ) {
                int maxRows = request.getMaxRows() <= 0 ? 500 : Math.min(request.getMaxRows(), 5000);
                statement.setMaxRows(maxRows);
                statement.setFetchSize(Math.min(maxRows, 1000));
                try (ResultSet rs = statement.executeQuery(request.getSql())) {
                    fillRows(rs, result);
                }
            } else {
                result.setUpdateCount(statement.executeUpdate(request.getSql()));
            }
            result.setElapsedMs(System.currentTimeMillis() - start);
            return result;
        } catch (Exception ex) {
            throw new AppException("SQL执行失败: " + ex.getMessage());
        }
    }

    private SqlExecutionResult confirmationRequired(String sql, SqlType type) {
        SqlExecutionResult result = new SqlExecutionResult();
        result.setSqlType(type);
        result.setConfirmationRequired(true);
        Map<String, Object> preview = new LinkedHashMap<String, Object>();
        preview.put("message", "写操作需要二次确认");
        preview.put("sql", sql);
        result.setPreview(preview);
        return result;
    }

    private void fillRows(ResultSet rs, SqlExecutionResult result) throws Exception {
        ResultSetMetaData metaData = rs.getMetaData();
        List<String> columns = new ArrayList<String>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            columns.add(label == null || label.length() == 0 ? metaData.getColumnName(i) : label);
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= columns.size(); i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        result.setColumns(columns);
        result.setRows(rows);
    }
}
