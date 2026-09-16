# 数据库工作台 V2

一个本机运行、由用户自主选择 JDBC 驱动的数据库工作台。浏览表结构和数据，执行 SQL、存储过程、匿名代码块和多语句脚本，并查看 EXPLAIN 计划。

应用保持 **Java 8、单模块 Maven、单个可执行 JAR**。服务与原生 JavaScript/CSS 页面一起打包；使用者不需要 Node、Maven、Docker 或额外的配置数据库。默认提供 MySQL、PostgreSQL 和 H2 驱动，也支持导入其他驱动或不同版本，无需修改源码或重新打包。

V2 已实施，数据库和目标运行环境的验收范围以 [交付与验证记录](notes/reconstruction/DELIVERY.md) 为准。架构设计历史见 [重构方案](notes/reconstruction/README.md)，分项要求见 [验收清单](notes/reconstruction/ACCEPTANCE.md)。

## 启动

### 直接下载使用

前往 [GitHub Releases](https://github.com/makotogu/database-toolbox/releases/latest)，下载 `database-toolbox-2.0.0.zip` 并解压。安装 Java 8 后，在解压目录运行：

```bash
sh database-toolbox.sh start
```

浏览器访问 `http://127.0.0.1:8080`。停止服务使用 `sh database-toolbox.sh stop`。也可按下面的命令直接运行 JAR。

准备 Java 8，将发行 JAR 放到一个可写目录：

```bash
java -version
java -jar database-toolbox.jar
```

浏览器打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)。服务默认仅监听本机，启动日志会打印访问地址和实际数据目录。

### 用脚本启停

将 [database-toolbox.sh](scripts/database-toolbox.sh) 与 `database-toolbox.jar` 放在同一目录（本次 `dist/` 已配好）：

```bash
sh database-toolbox.sh start
sh database-toolbox.sh status
sh database-toolbox.sh restart
sh database-toolbox.sh stop
```

脚本后台启动服务，每次启动的日志保存在旁边 `logs/`，进程记录在 `run/`；默认数据目录为脚本旁的 `data/`，不受执行命令时所在目录影响。停止使用 TERM 等待优雅退出，不会自动强杀。脚本只管理自己启动且身份匹配的进程；手动 `java -jar` 启动的进程需由原启动方式停止。

```bash
# 自定义端口；status/stop 会读取记录的进程，无需重复传端口
TOOLBOX_PORT=18080 sh database-toolbox.sh start
# 自定义 Java 和数据目录
JAVA_HOME=/path/to/java8 TOOLBOX_DATA_DIR=/path/to/data sh database-toolbox.sh start
```

启动和停止默认各等待 30 秒，可分别用 `TOOLBOX_START_TIMEOUT`、`TOOLBOX_STOP_TIMEOUT` 调整。`restart` 使用本次命令的环境变量重新启动；自定义端口/数据目录需再次传入或预先 `export`。使用 `sh database-toolbox.sh help` 查看全部参数。

### 直接启动的可选参数

可指定端口与数据目录：

```bash
java -jar database-toolbox.jar --server.port=18080 --toolbox.storage-root=/path/to/data
```

端口被占用时更换 `--server.port`。同一数据目录只允许一个 V2 进程使用；需要同时启动多份工作台时，分别指定端口和数据目录。

## 从驱动到查询

1. **选择驱动**：MySQL、PostgreSQL 和 H2 可直接使用内置驱动。需要其他数据库或不同版本时，打开“驱动管理”，同时选择主驱动和所需依赖 JAR。应用从 JDBC Service 声明发现候选驱动类；没有声明或存在多个候选时，手动填写/选择完整类名。
2. **新建连接**：在数据库导航点击“＋”，选择驱动，填写名称、完整 JDBC URL、用户名、密码及可选的 JSON 扩展属性。测试成功后保存。驱动必须与本机 Java 运行时兼容；首版面向纯 Java JDBC 驱动。
3. **浏览对象**：展开连接与命名空间，打开表或存过。表支持字段、索引、外键查看，以及数据过滤、排序和每页 200 行预览；不自动执行全表 `COUNT`。
4. **执行 SQL**：新建 SQL 标签并选择连接。首次执行时建立该标签的独立 JDBC 会话；可指定默认 catalog/schema。每个标签依次执行自己的语句，不与其他标签共享事务。
5. **查看结果**：多个结果集、更新计数、输出参数和消息分别展示。同名列按位置保留；`NULL` 与空字符串分开显示。双击单元格查看和复制预览值。

表预览会展示实际参数化 SQL 和参数输入顺序。“SQL 模板”将其打开到编辑器；其中 `?` 是占位符，执行前需要按数据库语法替换。翻页会重新查询；没有主键或数据库并发修改时，页面之间可能出现重复或遗漏。

### 默认驱动

| 驱动 | 内置版本 | 适用范围 |
| --- | --- | --- |
| MySQL Connector/J | 9.7.0，附带 protobuf-java 4.31.1 | Java 8，MySQL 8.0 及以上 |
| PostgreSQL JDBC | 42.7.13 | Java 8 的 JDBC 4.2 驱动 |
| H2 | 2.2.224 | Java 8；可用内存数据库直接试用 |

MySQL 版本依据 [9.7.0 发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html) 和 [Java 8 兼容说明](https://dev.mysql.com/doc/connector-j/en/connector-j-java-8.html)；PostgreSQL 版本见 [官方下载页](https://jdbc.postgresql.org/download/)。H2 保留 2.2.224，因为 [该版本支持 Java 8](https://github.com/h2database/h2database/blob/version-2.2.224/h2/src/docsrc/html/installation.html)，[2.3.230 起要求 Java 11](https://h2database.com/html/changelog.html)。

首次启动会自动把内置驱动解压到 `data/drivers/bundled-xxx/`，不覆盖用户导入的驱动和连接配置。后续启动会校验内置文件；缺失或损坏时从发行 JAR 恢复。内置驱动不能删除或修改驱动类；切换版本请另行导入，再在连接中选择。

即开即试：新建连接，选择 H2，URL 填 `jdbc:h2:mem:toolbox;DB_CLOSE_DELAY=-1`，用户名填 `sa`，密码留空；保存后执行 `SELECT 1`。这是内存数据库，应用退出后数据消失。GaussDB 仍需导入与目标版本和兼容模式匹配的厂商驱动，不能把 PostgreSQL 驱动视为 GaussDB 驱动。

## 执行范围与事务

| 操作 | 行为 |
| --- | --- |
| 执行当前 | 后端按 SQL 方言识别光标所在语句；`⌘/Ctrl + Enter` |
| 选区 | 执行选中的 SQL；`⌘/Ctrl + Shift + Enter` |
| 整块 | 将选区或全部文本作为一个数据库代码块，保留块内分号 |
| 脚本 | 后端拆分执行单元，在同一会话顺序执行，遇错停止 |
| 调用存过 | 通过调用模板与参数表执行，支持 IN、OUT、INOUT 和函数返回参数；元数据不足时可手工补充 |
| EXPLAIN | 为当前或选中的语句请求估算计划；保留原始输出，可识别的 PostgreSQL JSON 计划显示树形视图 |
| 实际分析 | 请求 `EXPLAIN ANALYZE`，**会真正执行被分析语句**，需要执行确认 |

脚本识别包括 MySQL `DELIMITER`、PostgreSQL dollar quote，以及对应方言的声明块和独占一行 `/`。解析存在歧义时会要求明确选择执行范围。导入驱动提供 JDBC 连接能力；厂商的代码块、元数据和计划语法仍取决于具体方言与数据库版本。

- 默认使用自动提交。关闭自动提交后，通过“提交”或“回滚”处理当前标签事务。
- **从手动切回自动提交时，应用先回滚未提交事务，再开启自动提交。** 要保留当前修改，应先点击“提交”。关闭/断开标签会话或闲置回收时，也会尝试回滚未提交事务。
- DDL、过程内部事务及数据库自动提交规则由数据库决定；客户端不能保证撤销所有数据库操作。
- 写语句、代码块、存过和实际分析等需要核对连接与执行内容后确认。已成功的自动提交语句不会因脚本后续报错而撤销。
- “取消”首先请求数据库取消；终态以执行结果为准。驱动不响应时会尝试关闭会话。出现“结果未知”后，请先核对数据库，不要直接重试可能产生写入的语句。

“执行设置”可调整当前标签的结果行数和超时。SQL 文本与查询结果不自动写入磁盘；需要保留 SQL 时，主动点击保存或按 `⌘/Ctrl + S` 下载文件。刷新页面或关闭浏览器不会保存编辑内容。

## 本地数据与资源上限

默认在启动命令所在目录创建 `data/`：

```text
data/
  .workbench.lock
  config/
    master.key
    connections-v2.enc
    drivers-v2.json
  drivers/<driver-id>/*.jar     # 用户导入的驱动
  drivers/bundled-xxx/*.jar     # 从发行 JAR 解压的内置驱动
  migration-backups/legacy-v1/   # 迁移过旧连接时生成
```

连接 URL、密码和扩展属性加密保存；读取连接列表时隐藏敏感值。编辑界面中的 `<saved>` 表示保留已保存值。备份连接配置时必须同时保留 `master.key`，丢失密钥后不能读取配置。驱动在本机进程中执行，应使用可信来源的 JAR。

| 资源 | 当前上限 |
| --- | --- |
| 驱动导入 | 每次 1–32 个 JAR；单包 128 MiB，总计 256 MiB |
| 标签数据库会话 | 最多 8 个；闲置约 30 分钟后回收 |
| 执行器 | 4 个工作线程，16 个等待位置；一个会话只接受一个未结束执行 |
| 查询结果 | 每个结果默认 500 行，可设为 1–5000 行；单次执行最多收集 10000 行、约 16 MiB、100 个结果项 |
| 单元格预览 | 文本最多约 32768 个字符；二进制最多 32768 字节，显示为 Base64；截断会提示 |
| 超时 | 默认 60 秒，可设为 1–3600 秒 |
| 后端结果缓存 | 最多 16 次执行；终态结果约 15 分钟后清理，容量满时可提前淘汰旧结果 |

这些上限约束应用收集和缓存的数据。驱动可能自行缓存更多数据；数据库专有流式读取、游标及取消行为需要按目标驱动验证。

## 旧版迁移与回退

同步、自动建分区、备份恢复和旧页面已退出 V2 运行包。V1 源码及开发说明保存在 [legacy/v1](legacy/v1/ARCHIVE.md)，不参与根项目构建。

使用旧数据目录时，V2 在首次读取连接配置时检查 `config/datasources.enc`：先备份旧加密文件和密钥，再创建 `connections-v2.enc`。原文件保持不变；以后读取 V2 配置，不重复迁移。旧同步模板、备份和失败文件保留在原目录。

迁移连接需要重新选择内置或用户导入的驱动，并核对 URL、方言和兼容模式。历史 `POSTGRESQL` 和 `GAUSSDB` 类型分别保留，不能据此推断数据库实际兼容模式。旧文件损坏、密钥丢失或不匹配时，迁移停止并保留原文件。

回退时使用 V1 程序和旧配置/备份，给 V1、V2 分别指定数据目录。V1 不读取 V2 连接配置。

## 从源码构建

在已配置 JDK 8 和 Maven 的终端执行：

```bash
mvn clean verify
java -jar target/database-toolbox.jar
```

构建产物固定为 `target/database-toolbox.jar`。将该文件复制到独立目录后，即可按上面的 `java -jar database-toolbox.jar` 启动。

开发结构、API 和测试方法见 [开发指南](DEVELOPMENT_GUIDE.md)。已执行的验证、具体数据库/驱动版本和剩余环境验收见 [DELIVERY.md](notes/reconstruction/DELIVERY.md)。Oracle、GaussDB 的厂商扩展以及 Windows 启动不能仅凭通用 JDBC 实现推定已经实测。

## 许可证

本项目采用 [GNU GPL-2.0](LICENSE)。随包第三方组件保留各自许可证和声明，见 [third-party](src/main/resources/third-party/README.txt)。发布页同时提供当前应用源码入口，以及内置 MySQL 驱动对应版本的源码归档。
