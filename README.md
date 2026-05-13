# 数据库测试工具箱

本项目是一个本机运行的数据库测试工具箱，使用 Java 1.8、Spring Boot 2.7.18 和原生 ES6 静态页面。

## 运行

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn package
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) java -jar target/database-toolbox-0.0.1-SNAPSHOT.jar
```

访问地址：

```text
http://127.0.0.1:8080
```

默认只监听 `127.0.0.1`，配置见 `src/main/resources/application.yml`。

## 本地测试 MySQL

```bash
docker compose up -d toolbox-mysql
docker compose ps
```

工具箱页面中新增 MySQL 数据源：

```text
名称: local-mysql-toolbox
类型: MYSQL
主机: 127.0.0.1
端口: 3307
数据库: toolbox_test
用户名: toolbox
密码: toolbox123
```

可用于测试的表：

```text
user_profile_source
user_profile_target
order_snapshot
```

字段同步模板可先用：

```text
源表: user_profile_source
目标表: user_profile_target
匹配键: id
字段映射:
id=id
tenant_id=tenant_id
user_name=user_name
email=email
status=status
balance=balance
```

数据导出和备份恢复是两个独立菜单。导出页面的分隔符使用下拉选择，默认 `Char(27) · ESC`；恢复页面优先读取 ZIP 包内 `manifest.json` 的分隔符、字符集、Quote 和 Escape 配置，不需要手动重复选择。后端也支持接口传入 `CHAR(27)`、`\u001B`、`0x1B` 或实际控制字符。

## V1 功能

- 工作台：首页展示数据源、同步模板、备份文件、任务状态和最近任务。
- 数据源配置：MySQL、GaussDB，配置保存到本地加密文件。
- 元数据读取：表列表、字段列表、主键信息。
- SQL 执行：读取 SQL 直接执行，写 SQL 必须显式确认。
- 数据导出：导出 ZIP 数据包，包含 `manifest.json`、`metadata.json`、`data.csv`，支持自定义分隔符。
- 备份恢复：从 ZIP 数据包恢复，默认读取包内 CSV 格式信息。
- 字段映射同步：保存同步模板，按目标匹配键执行 upsert，支持写前备份和失败行文件。
- 字段自动映射：同步页面可从源表/目标表读取字段，自动生成同名字段映射并回填匹配键。
- 分区同步：同步模板可配置目标分区创建规则，执行同步前自动创建目标分区。
- 长任务：备份、恢复、同步统一进入 job 列表，页面轮询展示进度。

## GaussDB 数据源

GaussDB 按 Oracle 兼容模式处理，upsert 使用 `MERGE INTO`：

```text
类型: GAUSSDB
主机: GaussDB 服务地址
端口: 8000 或现场端口
数据库: 目标库名
用户名: GaussDB 用户名
密码: GaussDB 密码
```

如果现场驱动类不同，可以在“连接参数”里覆盖：

```text
driverClassName=com.huawei.gaussdb.jdbc.Driver
urlPrefix=jdbc:gaussdb://
```

如果已经有完整 JDBC URL，也可以直接填写 `JDBC URL`，工具会优先使用该 URL。

## 同步前自动创建分区

字段同步模板里可以开启“自动创建分区”。配置项包括：

```text
分区字段: created_at
分区定义: partitionName|fromValue|toValue|lessThanValue
Range建分区SQL模板:
ALTER TABLE {targetTableQuoted} ADD PARTITION {partitionNameQuoted} VALUES LESS THAN ({lessThanValue});
List建分区SQL模板:
ALTER TABLE {targetTableQuoted} ADD PARTITION {partitionNameQuoted} VALUES ({lessThanValue});
```

MySQL 目标库的默认模板是：

```text
Range建分区SQL模板:
ALTER TABLE {targetTableQuoted} ADD PARTITION (PARTITION {partitionName} VALUES LESS THAN ({lessThanValue}));
List建分区SQL模板:
ALTER TABLE {targetTableQuoted} ADD PARTITION (PARTITION {partitionName} VALUES IN ({lessThanValue}));
```

常用变量：

```text
{targetTable}           原始目标表名
{targetTableQuoted}     方言转义后的目标表名
{partitionName}         原始分区名
{partitionNameQuoted}   方言转义后的分区名
{partitionColumn}       原始分区字段
{partitionColumnQuoted} 方言转义后的分区字段
{fromValue}             分区起始值
{toValue}               分区结束值
{lessThanValue}         GaussDB/MySQL Range/List 分区边界值或枚举值
```

Range/List 是分区策略，不是数据源类型。GaussDB 和 MySQL 的默认模板已经拆开；若现场分区语法不同，直接在页面改 SQL 模板即可保存到同步模板中。

页面提供“探查源表分区”按钮，会从源表读取现有分区并自动填入分区定义：

```text
MySQL: 读取 information_schema.PARTITIONS，按 PARTITION_METHOD 自动识别 RANGE/LIST。
GaussDB: 读取 pg_catalog.pg_partition，按 partstrategy 自动识别 RANGE/LIST，并把 boundaries 转成模板边界表达式。
```

如果目标表名和源表名不同，探查时会尝试把分区名中的源表名替换成目标表名，生成后仍可在页面手工调整。

## 本地数据

运行后会在项目工作目录下生成：

```text
data/config/master.key
data/config/datasources.enc
data/config/sync-tasks.enc
data/backups/
data/failures/
```

`master.key` 用于加密本机配置文件。这个机制用于避免明文密码落盘，不等同于多用户权限体系。

## 验证

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn package
```
