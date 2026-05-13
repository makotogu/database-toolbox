package com.example.dbtoolbox.datasource;

import com.example.dbtoolbox.common.AppException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class DialectRegistry {

    private final Map<DatabaseType, DatabaseDialect> dialects = new EnumMap<DatabaseType, DatabaseDialect>(DatabaseType.class);

    public DialectRegistry() {
        /*
         * 新增数据库类型时要在这里注册方言。
         * 当前项目没有引入 Spring 扫描所有 DatabaseDialect bean，是为了让 V1 的加载顺序和依赖更直观。
         */
        register(new MySqlDialect());
        register(new GaussDbDialect());
    }

    public DatabaseDialect get(DatabaseType type) {
        DatabaseDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new AppException("不支持的数据库类型: " + type);
        }
        return dialect;
    }

    private void register(DatabaseDialect dialect) {
        dialects.put(dialect.type(), dialect);
    }
}
