# 数据库测试工具箱二次开发指南

本文说明如何在当前单模块项目里扩展数据库类型、页面、任务和测试。

## 项目结构

```text
src/main/java/com/example/dbtoolbox
├── backup       # ZIP 数据包导出和恢复
├── common       # 通用响应、异常、CSV、加密文件存储
├── config       # Spring Boot 配置属性
├── dashboard    # 工作台概览
├── datasource   # 数据源配置、JDBC 连接、数据库方言
├── job          # 异步任务和任务进度
├── metadata     # 表和字段元数据读取
├── sql          # SQL 类型识别和执行
└── sync         # 字段映射同步模板和同步执行

src/main/resources/static
├── css          # base/layout/components/pages 样式拆分
└── js           # api/state/router/components/pages/utils 前端模块
```

后端保持 `controller / service / model / store` 分层。前端使用原生 ES6 module，不引入构建工具。

## 接入一种新数据库

数据库兼容性主要收敛在 `datasource` 包，不建议在 `sync`、`backup`、`sql` 等业务包里直接判断数据库类型。优先扩展方言，业务服务只调用 `DatabaseDialect` 暴露的能力。

1. 在 `DatabaseType` 增加枚举值。
2. 新增一个 `DatabaseDialect` 实现类，至少实现：
   - `defaultPort()`
   - `driverClassName()`
   - `buildJdbcUrl()`
   - `quoteIdentifier()`
   - `limitSql()`
   - `upsertSql()`
3. 在 `DialectRegistry` 注册新方言。
4. 在 `datasources.js` 的类型下拉框增加选项。
5. 在 `DialectSqlTest` 增加 URL、引用标识符、upsert SQL 测试。
6. 如果需要新驱动，把 Maven 依赖加到 `pom.xml`。

当前 `GAUSSDB` 方言按 Oracle 兼容模式实现，upsert 使用 `MERGE INTO`。如果现场驱动类或 URL 前缀不同，可以在数据源“连接参数”中配置：

```text
driverClassName=com.huawei.gaussdb.jdbc.Driver
urlPrefix=jdbc:gaussdb://
```

如果填写了完整 `JDBC URL`，连接工厂会优先使用该 URL。

几个约定需要保持：

- `quoteIdentifier()` 只处理单个字段或表段名，`schema.table` 这种复合名称使用 `SqlNameUtils.quoteQualifiedName()`。
- `upsertSql()` 的 `matchKeys` 是目标字段名，不是源字段名。
- 连接参数里的 `driverClassName` 和 `urlPrefix` 是连接工厂/方言使用的控制参数，不应再拼进 JDBC URL query string。

## 新增页面

1. 在 `src/main/resources/static/js/pages/` 新建页面模块，导出 `renderXxx(root)`。
2. 在 `router.js` 导入并注册路由。
3. 在 `main.js` 的 `nav` 增加菜单项。
4. 页面样式放到 `pages.css`，公共按钮、表单、表格优先复用 `components.css`。
5. 页面 API 请求统一使用 `api/client.js`。

页面应保持工具型布局：信息密度适中、少装饰、可扫描、操作入口明确。

## 新增长任务

备份、恢复、同步都使用 `JobService`。

开发新任务时：

1. 在业务 service 中注入 `JobService`。
2. 调用 `jobService.submit(type, name, work)` 创建任务。
3. 在 `JobContext` 中更新：
   - `message()`
   - `processed()`
   - `addProcessed()`
   - `addFailed()`
   - `artifact()`
   - `failureFile()`
4. 前端通过 `/api/jobs` 或 `/api/dashboard` 查看任务状态。

## 字段映射自动填入

同步页面的“自动填入字段”按钮在前端完成匹配，不新增后端同步模板接口。流程是：

```text
读取源表字段 -> 读取目标表字段 -> 生成 source=target 文本 -> 回填字段映射文本框
```

读取字段复用元数据接口：

```text
GET /api/datasources/{datasourceId}/columns?schema=public&table=user_order
```

当前匹配策略：

- 先按字段名忽略大小写精确匹配。
- 再按忽略大小写和下划线做宽松匹配，例如 `user_id` 可以匹配 `userId`。
- 如果目标字段里有主键，自动回填到 `matchKeys`。
- 如果目标主键不存在但匹配结果包含 `id`，默认用 `id` 作为匹配键。

如果以后要支持更复杂的字段名映射，例如 `src_user_name -> username` 或按字段备注匹配，优先扩展 `sync.js` 的 `buildAutoMappings()`，因为这是页面辅助能力，不影响后端同步执行模型。

## 同步前自动创建分区

字段同步模板通过 `PartitionRule` 保存分区创建规则：

```text
PartitionRule
├── enabled
├── partitionColumn
├── sqlTemplate
├── ignoreCreateErrors
└── definitions[]
    ├── partitionName
    ├── fromValue
    ├── toValue
    └── lessThanValue
```

执行链路在 `SyncService.executeSync()` 中：

```text
validateTask
-> optional backup target table
-> ensurePartitions
-> stream source rows
-> batch upsert target rows
```

`ensurePartitions()` 会按 `sqlTemplate` 渲染每条 `PartitionDefinition` 并执行。模板支持：

```text
{targetTable}
{targetTableQuoted}
{partitionName}
{partitionNameQuoted}
{partitionColumn}
{partitionColumnQuoted}
{fromValue}
{toValue}
{lessThanValue}
```

GaussDB 目标库的 Range 分区策略默认模板：

```sql
ALTER TABLE {targetTableQuoted}
ADD PARTITION {partitionNameQuoted} VALUES LESS THAN ({lessThanValue});
```

GaussDB 目标库的 List 分区策略默认模板：

```sql
ALTER TABLE {targetTableQuoted}
ADD PARTITION {partitionNameQuoted} VALUES ({lessThanValue});
```

页面的“探查源表分区”调用：

```text
POST /api/sync-tasks/probe-partitions
```

请求只依赖源数据源和源表，目标表可选：

```json
{
  "sourceDatasourceId": "xxx",
  "sourceTable": "toolbox_test.user_order",
  "targetTable": "toolbox_test.user_order_copy"
}
```

当前探查策略：

```text
MySQL   -> information_schema.PARTITIONS, PARTITION_METHOD 以 LIST 开头时识别为 LIST
GaussDB -> pg_catalog.pg_partition, partstrategy = l 时识别为 LIST，boundaries 转成模板边界表达式
```

返回的 `definitions[]` 会被前端格式化为 `partitionName|fromValue|toValue|lessThanValue`。GaussDB/MySQL 的 Range/List 分区都复用 `lessThanValue` 承载分区边界表达式。

如果接入新的分区语法，优先通过页面模板配置；只有变量不足或需要预检查元数据时，再扩展 `SyncService` 或新增专门的 partition service。

开发时重点看这几个方法：

- `probePartitions()`：读取源表分区，返回页面可编辑的 `PartitionDefinition`。
- `ensurePartitions()`：同步写入前执行目标分区 DDL。
- `renderPartitionSql()`：把 `PartitionRule.sqlTemplate` 和单条 `PartitionDefinition` 渲染成 SQL。
- `transformPartitionName()`：源表和目标表名称不同的时候，做一次保守的分区名替换。

模板变量里的 `{fromValue}`、`{toValue}`、`{lessThanValue}` 只会转义单引号，不会自动加引号。原因是不同数据库的分区边界可能是字符串、数字、日期表达式或 `MAXVALUE`，自动加引号反而容易生成错误 SQL。需要引号时，请在页面 SQL 模板里显式写出来。

如果未来要支持更多分区策略，优先扩展 `PartitionRule.partitionStrategy`、页面策略下拉框和 `probePartitions()` 的探查分支；目标侧创建仍可继续复用 `ensurePartitions()`。

## 本地配置存储

数据源和同步模板通过 `EncryptedJsonFileStore` 保存到本地加密文件：

```text
data/config/master.key
data/config/datasources.enc
data/config/sync-tasks.enc
```

新增本地配置时，优先复用该 store，不要明文保存密码或连接串。

## 测试和打包

必须用 JDK8 验证：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn package
```

前端 JS 修改后先做语法检查：

```bash
node --check src/main/resources/static/js/pages/xxx.js
```

打包后运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) java -jar target/database-toolbox-0.0.1-SNAPSHOT.jar
```
