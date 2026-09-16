# 现状证据与验证记录

检查日期：2026-09-16。范围：当前工作目录及其未提交改动。

## 1. 证据标签

- `verified/source`：本次读取源码确认的结构或分支，不等同已观察真实数据库行为。
- `verified/runtime`：本次实际命令/运行结果。
- `inferred`：由代码推出的限制或设计判断，仍需对应场景验证。
- `unverified`：本轮未运行的能力/环境。

本轮用户已确认“先方案与分阶段验收”和“代码块包括匿名块、过程体与脚本”。重构范围取自本轮需求；下列旧代码证据仅用于判断可复用部分和实现缺口。

## 2. 源码证据

路径相对仓库根；行号以本次工作区为准。

| ID | 已确认事实 | 来源 | 对设计的影响 |
| --- | --- | --- | --- |
| E01 | `verified/source`：Java 8、Spring Boot 2.7.18、Maven 重打包；仅回环监听 | `pom.xml:6–21,50–65`；`application.yml:1–3`；`DEVELOPMENT_GUIDE.md:24,215–229` | 沿用运行底座，静态前端继续打入单 JAR |
| E02 | `verified/source`：MySQL 驱动是 Maven 依赖；连接工厂使用 `Class.forName` 与全局 `DriverManager`，构建前还要求方言 | `pom.xml:33–37`；`datasource/JdbcConnectionFactory.java:21–44`；`datasource/DialectRegistry.java:13–25` | 当前没有用户 JAR 导入/版本隔离路径；需独立驱动管理 |
| E03 | `verified/source`：SQL 首词分为 READ/WRITE/UNKNOWN，UNKNOWN 被拒绝；READ 用 executeQuery、WRITE 用 executeUpdate；每次请求结束关闭连接 | `sql/SqlClassifier.java:12–26`；`sql/SqlService.java:29–56` | 需要 execute 多结果模型和持久标签会话；CALL/DO/DECLARE 无法进入当前正常执行分支 |
| E04 | `verified/source`：每个结果只有 columns/rows/updateCount；按列标签写入 Map；页面 textarea + 单个结果面板 | `sql/SqlExecutionResult.java:10–16`；`sql/SqlService.java:71–86`；`static/js/pages/sql.js:10–39,72–83` | 同名列会覆盖，属于静态可推断缺陷；需列序号数组与多结果标签 |
| E05 | `verified/source`：元数据仅暴露 tables/columns；GaussDB 分支直查 pg_catalog；通用分支已使用 DatabaseMetaData | `metadata/MetadataController.java:22–32`；`metadata/MetadataService.java:32–91,94–159` | 可复用查询片段；需补 catalog/schema、索引/外键、过程元数据和表内容入口 |
| E06 | `verified/source`：配置加密文件保存，临时文件原子替换；目录相对工作目录解析 | `common/EncryptedJsonFileStore.java:40–85`；`common/StoragePaths.java:14–15` | 可以复用本地文件方案，新增格式版本/迁移/单实例锁 |
| E07 | `verified/source`：方言接口同时暴露驱动、URL、LIMIT、upsert；job 取消设置标志并中断 Future；菜单含同步、导出、恢复 | `datasource/DatabaseDialect.java:11–46`；`job/JobService.java:129–149`；`static/js/main.js:11–19` | `inferred`：直接扩展旧业务抽象会携带无关依赖；查询执行应重新收敛 |
| E08 | `verified/source`：类型仅 MYSQL/GAUSSDB；反序列化将 POSTGRESQL/RANGE 映射为 GAUSSDB | `datasource/DatabaseType.java:5–19` | 迁移时保留原始信息，新连接不按旧枚举限制 |
| E09 | `verified/runtime`：现有 32 项测试通过，可产出约 21 MiB JAR，临时目录启动接口成功 | 下方构建/启动记录 | 底座可复用；“代码陈旧”不等于当前不能启动 |

Java 源码前缀为 `src/main/java/com/example/dbtoolbox/`；静态前缀为 `src/main/resources/`。E01 的 `application.yml` 位于 `src/main/resources/`。

补充规模数据（本次 `wc -l`）：`SyncService.java` 1799 行、`BackupService.java` 374 行、`JobService.java` 304 行。行数只表示规模，不构成质量或删除的独立依据。

`SqlClassifier` 将 WITH/EXPLAIN 一律归 READ 是已核实源码事实；这并不能保证语句没有副作用。规划因此移除“首词分类决定 JDBC 执行方式”的设计。真实数据库副作用用例本轮未执行。

## 3. 工作区保护

本轮开始时 HEAD 为 `0ed31c4`，已有 25 个已跟踪文件修改，diff 统计为 2715 行增加、220 行删除；还有新 job 源文件/测试和 `notes/` 未跟踪内容。

本轮没有重置、提交、移动这些改动，也未读取真实连接配置/密钥。规划新增在 `notes/reconstruction/`，README 仅添加明确标注的规划入口；运行实现保持原样。构建生成的 `target/` 已由 `.gitignore` 排除。

## 4. Java 8 构建记录

使用本机已有依赖缓存，命令：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -o -B -ntp package
```

环境：macOS arm64，Amazon Corretto `1.8.0_452`；输出 `BUILD SUCCESS`。

| 测试类 | 用例数 | 失败/错误/跳过 |
| --- | ---: | --- |
| DialectSqlTest | 11 | 0 / 0 / 0 |
| EncryptedJsonFileStoreTest | 1 | 0 / 0 / 0 |
| CsvUtilsTest | 2 | 0 / 0 / 0 |
| JobServiceTest | 2 | 0 / 0 / 0 |
| JobHistoryStoreTest | 2 | 0 / 0 / 0 |
| SyncServiceValidationTest | 11 | 0 / 0 / 0 |
| SqlClassifierTest | 3 | 0 / 0 / 0 |
| 合计 | 32 | 0 / 0 / 0 |

产物：`target/database-toolbox-0.0.1-SNAPSHOT.jar`，`du -h` 显示 21M。该产物为**旧版基线构建**，不是 V2 交付。

## 5. 旧版 JAR 启动冒烟

先在沙箱内尝试监听端口，因 `java.net.SocketException: Operation not permitted` 失败；属于工具执行环境的端口权限限制。随后经工具权限流程在沙箱外，仅监听 `127.0.0.1:18089`，使用全新的临时空数据目录重试成功。

启动等价命令：

```bash
java -jar target/database-toolbox-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=18089 \
  --toolbox.storage-root=<临时目录>/data
```

实际响应：

```text
GET /                  200   HTML 541 bytes
GET /js/main.js         200   3011 bytes
GET /api/datasources    200   {"success":true,"message":"OK","data":[]}
```

检查完成后测试进程已停止。启动日志暂存在 `<temporary-data-directory>`；临时目录可能被系统清理，关键输出已保存在本文。

此检查证明 JAR 启动、页面资源服务及空连接列表接口可用；没有浏览器视觉验收，没有连接实际数据库。

## 6. 尚未验证的内容

- `unverified`：V2 所有新功能，当前只是方案。
- `unverified`：真实 MySQL/GaussDB/PostgreSQL 的存过、代码块、EXPLAIN、事务与取消。本次未启动 Docker、未访问既有数据库。
- `unverified`：GaussDB 的具体产品/版本/兼容模式，以及现场驱动包对 Java 8 的支持情况。
- `unverified`：Windows/Linux 发行运行和浏览器布局。当前启动结论仅限本机 macOS + Java 8。

## 7. 设计所查阅的官方接口资料

- [Java 8 Driver](https://docs.oracle.com/javase/8/docs/api/java/sql/Driver.html)：显式驱动连接入口。
- [Java 8 URLClassLoader](https://docs.oracle.com/javase/8/docs/api/java/net/URLClassLoader.html)：外置 JAR 加载及关闭。
- [Java 8 Statement](https://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html)：execute、多结果、取消及超时。
- [Java 8 CallableStatement](https://docs.oracle.com/javase/8/docs/api/java/sql/CallableStatement.html)：过程参数和返回参数。
- [MySQL 存储程序定义](https://dev.mysql.com/doc/refman/8.0/en/stored-programs-defining.html)：客户端 DELIMITER 与完整过程体。
- [pgJDBC 过程与函数](https://jdbc.postgresql.org/documentation/callproc/)：调用模式及游标差异。
- [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html)：估算计划与实际执行分析的区别。

资料用于约束设计，不替代特定驱动/数据库版本的集成验收。
