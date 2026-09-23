# 数据库工作台 V2

一个本机运行、由用户自主选择 JDBC 驱动的数据库工作台。浏览表结构和数据，执行 SQL、存储过程、匿名代码块和多语句脚本，并查看 EXPLAIN 计划。

应用保持 **Java 8、单模块 Maven、单个可执行 JAR**。服务与原生 JavaScript/CSS 页面一起打包；使用者不需要 Node、Maven、Docker 或额外的配置数据库。默认提供 MySQL、PostgreSQL 和 H2 驱动，也支持导入其他驱动或不同版本，无需修改源码或重新打包。

V2 已实施，数据库和目标运行环境的验收范围以 [交付与验证记录](notes/reconstruction/DELIVERY.md) 为准。架构设计历史见 [重构方案](notes/reconstruction/README.md)，分项要求见 [验收清单](notes/reconstruction/ACCEPTANCE.md)。

## 可视化修改单元格

此功能需构建当前源码；已发布的 v2.0.0 包尚不包含。实现边界与测试证据见 [单元格编辑验收记录](notes/features/CELL_EDITING.md)。

打开表的“数据预览”，单击选中格子后点“编辑选中格”，也可双击或按 Enter 打开。弹窗显示原值、新值和 NULL 选项；留空与 NULL 分开处理。确认保存后刷新当前页，筛选条件可能使修改后的行不再出现在当前结果中。

- 当前支持 **H2、PostgreSQL、MySQL InnoDB** 中有主键的普通表，包括复合主键。支持文本、整数、精确小数、布尔及日期时间类型；主键、生成列、浮点、二进制/LOB、其他未适配类型和视图只读。截断值不能编辑；TIME 编辑目前限整秒值。
- **GaussDB 兼容修复（待内网实测）**：使用匹配版本的厂商 JDBC 驱动；驱动报告 PostgreSQL 或 GaussDB 均可进入编辑检查。缺失生成列标记时，按系统目录补充判断，已确认的普通字段可编辑；生成列、自增列、属性仍不明的字段继续只读。元数据失败提示具体阶段及 SQLState。503 SPC2000C / `GaussDBV5-503.1.0.spc2000c_26.7.13.jar` 尚未实测，详见 [兼容修复记录](notes/features/GAUSS_CELL_EDIT_COMPAT.md)。
- 保存会锁定目标行并核对该格的预览原值；原值变化或记录删除时报告冲突，保留输入供复制，刷新后再操作。检查针对所选格，不是整行版本审计。
- 自动提交模式保存后立即提交；手动模式保存后仍需“提交”或“回滚”。保存失败使用保存点撤销本次修改；提交/回滚后自动刷新预览。
- 普通 SQL 查询结果只读。会话或表结构改变、结果过期后，需要重新预览。触发器及数据库特有副作用仍遵循数据库事务能力。

贡献代码请阅读 [贡献指南](CONTRIBUTING.md)；其他 Agent 从 [AGENTS.md](AGENTS.md) 开始。

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

脚本后台启动服务，每次启动的日志保存在旁边 `logs/`，进程记录在 `run/`；默认数据目录为脚本旁的 `data/`，不受执行命令时所在目录影响。服务就绪后，macOS 使用 `open`、Linux 桌面使用 `xdg-open` 请求打开浏览器；无桌面或 SSH 环境默认只打印地址。浏览器启动失败不影响服务，可手动访问。停止使用 TERM 等待优雅退出，不会自动强杀。脚本只管理自己启动且身份匹配的进程；手动 `java -jar` 启动的进程需由原启动方式停止。

```bash
# 自定义端口；status/stop 会读取记录的进程，无需重复传端口
TOOLBOX_PORT=18080 sh database-toolbox.sh start
# 禁止自动打开浏览器；设置为 1 则显式尝试打开，默认 auto
TOOLBOX_OPEN_BROWSER=0 sh database-toolbox.sh start
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

## 本地配置与失效会话

当前源码会拒绝非 loopback 监听地址。访问仍使用本机浏览器入口，无需登录；此机制不隔离同一台机器上不互信的 OS 账号。

连接配置使用 AES-GCM 加密，敏感目录和文件限制为所有者访问。首次读取旧 V2 配置时，先在 `data/config/connections-v2.enc.legacy-backup/` 备份原密文与密钥，再升级格式；V1 原文件仍保留。旧发行包不能直接读取升级后的配置，备份只代表升级时点。认证失败、密钥丢失或无法限制权限时会停止操作并保留文件，不会自动清空配置。

本批权限保护已在 POSIX 环境验证；Windows ACL、域/服务账户及权限继承行为尚未验收。

会话失效后可点“恢复会话”。资源回收完成后，再确认建立新连接；旧事务不会恢复，SQL 不会自动重跑。尚未停止的驱动操作仍占用会话名额，需等待并核实数据库结果。“取消已请求”不代表数据库已停止；退出也不会承诺撤销已经提交的操作。

带 `SELECT INTO`、`FOR UPDATE/SHARE` 或共享锁的语句现在也需要确认。判断会跳过注释和引号内容，但不能证明所有 SELECT 都没有副作用。

对象列表读取超时时会显示明确提示，可点“重试”重新读取该命名空间。GaussDB 的兼容模式尚未识别时，q 引号形式会保守要求执行确认。

## 从驱动到查询

1. **选择驱动**：MySQL、PostgreSQL 和 H2 可直接使用内置驱动。需要其他数据库或不同版本时，打开“驱动管理”，同时选择主驱动和所需依赖 JAR。应用从 JDBC Service 声明发现候选驱动类；没有声明或存在多个候选时，手动填写/选择完整类名。
2. **新建连接**：在数据库导航点击“＋”，选择驱动，填写名称、用户名及密码；MySQL / PostgreSQL 可填写主机、端口、数据库名称，也可切换到完整 JDBC URL。含参数、多主机或隐藏值的已有 URL 保留完整编辑，高级设置提供方言、catalog/schema 与 JSON 属性。测试成功后保存。驱动必须与本机 Java 运行时兼容；首版面向纯 Java JDBC 驱动。
   尚无数据库时可点击侧栏“试用 H2”，创建或复用独立的内存演示连接并在新标签执行固定 `SELECT 1`。应用退出后演示数据清空，连接配置保留；不会执行已有 SQL 草稿。
3. **浏览对象**：展开连接与命名空间，打开表或存过。表支持字段、索引、外键查看，以及最多 30 条条件的 AND/OR 过滤、排序和每页 200 行预览；不自动执行全表 `COUNT`。条件改变后显示“待应用”，点击“读取数据”从第一页应用；NULL 使用 IS NULL/IS NOT NULL，空输入在普通比较中表示空字符串。读取失败保留上次成功结果并标为只读，重新读取成功后可编辑。不支持嵌套条件组，复杂条件可使用 SQL。
4. **执行 SQL**：新建 SQL 标签并选择连接。首次执行时建立该标签的独立 JDBC 会话；可指定默认 catalog/schema。每个标签依次执行自己的语句，不与其他标签共享事务。
5. **查看结果**：多个结果集、更新计数、输出参数和消息分别展示。同名列按位置保留；`NULL` 与空字符串分开显示。双击单元格查看和复制预览值。

表预览会展示实际参数化 SQL 和参数输入顺序。“SQL 模板”将其打开到编辑器；其中 `?` 是占位符，执行前需要按数据库语法替换。翻页会重新查询；没有主键或数据库并发修改时，页面之间可能出现重复或遗漏。

### 菜单与标签操作

顶部“文件 / 执行 / 视图 / 帮助”集中提供 SQL 文件、新建连接、执行范围、事务、对象刷新和快捷键入口。不可用的操作会显示原因；菜单不会绕过执行确认。桌面也可在“视图”中收起数据库导航，留出更多编辑空间。

- 连接和对象旁的“…”可打开菜单，也支持右键。表的“查看结构”只加载元数据；选择“查看数据”才读取表数据。
- 右键标签可重命名普通 SQL 标签、复制 SQL 到新标签、关闭其他或右侧标签。副本保留 SQL、连接上下文与执行设置，新标签拥有独立会话；不会自动执行或继承事务。
- 批量关闭逐个处理，涉及未保存 SQL、持久草稿或手动事务时确认，取消一次就停止后续关闭。手动事务标签即使 SQL 已保存，也会提示关闭将回滚；关闭失败保留标签。
- 右键结果单元格可查看、复制或编辑。是否可编辑仍由表预览和主键等安全条件决定；结果刷新会关闭旧菜单。
- 菜单支持方向键、Enter、Esc；聚焦连接、对象、标签或单元格后可用 Shift+F10 打开菜单。聚焦标签后可用左右方向键、Home / End 切换。

这些功能需构建当前源码，未改动公开 Release。验证记录见 [工作台菜单](notes/features/WORKBENCH_MENUS.md)。

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

“执行设置”可调整当前标签的结果行数和超时。编辑器提供离线 SQL 高亮，保留原生文本选区、中文输入和撤销；超过 200000 字符时暂停高亮并显示提示，文本仍可编辑。高亮仅帮助阅读，不代表语法校验，也不改变后端执行范围与确认规则。

SQL 草稿保存按连接选择，**默认关闭**。在“编辑数据库连接”勾选“保存此连接的 SQL 草稿”并确认隐私提示后，属于该连接的普通 SQL 标签会在停止输入约 0.9 秒后保存到本机。编辑器下方显示待保存、保存中、已保存或失败；确认“草稿已加密保存”后再退出。刷新或重新启动后恢复文本和标签名称，不恢复数据库会话、事务、结果、调用参数或临时 catalog/schema，也不会自动执行。没有开启的连接、过程调用标签仍需手动保存。

SQL 可能含口令、个人信息和业务数据。草稿与连接配置使用同一本机密钥加密；拥有本机账户与密钥的人仍可读取。草稿不写入浏览器 localStorage/sessionStorage。关闭 SQL 标签会删除对应草稿；关闭保存选项或删除连接会清除该连接全部草稿，当前页面文本仍可手动下载。备份、已下载文件和磁盘历史副本需自行管理，不承诺物理擦除。多个页面编辑同一连接时，版本冲突会停止保存并保留页面文本，请先下载 SQL 再刷新，避免覆盖。

随时可点击工具栏“保存 SQL 文件”或按 `⌘/Ctrl + S` 下载 `.sql` 文件，下载的文件是普通文本。查询结果不自动保存。CSV 与其他格式将放到完整导出功能中统一设计，本次未增加导出功能。

## 本地数据与资源上限

默认在启动命令所在目录创建 `data/`：

```text
data/
  .workbench.lock
  config/
    master.key
    connections-v2.enc
    sql-drafts.enc              # 开启并保存 SQL 草稿后生成
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
| SQL 草稿 | 每个连接最多 20 个非空 SQL 标签；单标签 1 Mi 字符，所有草稿合计 8 Mi 字符（UTF-16 单元）；超限提示失败，不截断 |

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
