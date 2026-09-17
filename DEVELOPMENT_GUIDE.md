# 数据库工作台 V2 开发指南

V2 保持 Java 8、Spring Boot 2.7.18、单模块 Maven，以及无构建步骤的原生 ES6 页面。最终发行物为一个 `database-toolbox.jar`；默认提供 MySQL、PostgreSQL 和 H2 驱动，同时保留用户上传驱动的接入路径。内置驱动作为 `bundled-drivers` 资源打包，不能作为应用的 `BOOT-INF/lib` 依赖混入主类路径。

本文描述当前实现。历史设计保存在 [重构方案](notes/reconstruction/README.md)，验收要求见 [ACCEPTANCE.md](notes/reconstruction/ACCEPTANCE.md)，实际验证范围和剩余工作见 [DELIVERY.md](notes/reconstruction/DELIVERY.md)。

## 目录与边界

```text
src/main/java/com/example/dbtoolbox/
  DatabaseToolboxApplication.java
  common/                   # API 响应、异常、加密文件存储、数据目录
  config/                   # toolbox.storage-root 配置属性
  workbench/
    LocalRuntime.java       # 本机校验、令牌、数据目录锁、bootstrap
    driver/                 # 驱动导入、候选发现、隔离加载、引用释放
    connection/             # 连接配置、凭据脱敏、短连接、V1 迁移
    metadata/               # catalog/schema、表结构、过程定义与参数
    dialect/                # 标识符引用、分页、绑定参数、EXPLAIN
    execution/              # 会话、词法拆分、准备/确认、异步执行、结果读取

src/main/resources/static/
  index.html
  workbench/app.js           # 工作台状态、API 与交互
  workbench/workbench.css    # 中文三栏工作台与响应式样式
  workbench/favicon.svg

src/main/resources/bundled-drivers/ # 内置驱动 JAR、依赖及版本清单

src/test/java/com/example/dbtoolbox/workbench/
scripts/smoke-v2.py          # MySQL/PostgreSQL 临时环境端到端验证
scripts/verify-release.py    # 独立 JAR、空目录启动、重启与本机访问验证
legacy/v1/                  # V1 源码快照，根 Maven 不编译
```

启动类只扫描 `workbench` 与 `common`，并显式启用配置属性。查询核心不依赖 V1 的 `sync/backup/job/dashboard`。后端用 JDBC 直接执行，不引入 ORM、前端构建链或额外配置数据库。

## 驱动与连接

内置驱动包括 `com.mysql:mysql-connector-j:9.7.0`、`org.postgresql:postgresql:42.7.13` 和 `com.h2database:h2:2.2.224`。MySQL 同时携带 `com.google.protobuf:protobuf-java:4.31.1`，与主驱动进入同一个隔离加载器；这是 [9.7.0 官方 POM](https://raw.githubusercontent.com/mysql/mysql-connector-j/9.7.0/src/build/misc/pom.xml) 声明的非 optional 依赖。OCI SDK 等 optional 依赖不作为默认连接能力的一部分。

版本边界：MySQL 9.7.0 的 [发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html) 明确支持 MySQL 8.0+，并遵循 [Java 8 平台要求](https://dev.mysql.com/doc/connector-j/en/connector-j-java-8.html)；PostgreSQL [官方下载页](https://jdbc.postgresql.org/download/) 将 42.7.13 列为 Java 8 的 JDBC 4.2 驱动。H2 [2.2.224 安装文档](https://github.com/h2database/h2database/blob/version-2.2.224/h2/src/docsrc/html/installation.html) 支持 Java 8，[2.3.230 起要求 Java 11](https://h2database.com/html/changelog.html)，因此不能随其他依赖一起无条件升级。

启动时从 `bundled-drivers` 资源向 `data/drivers/bundled-xxx/` 安装内置文件，并检查文件完整性；缺失或损坏时从发行资源恢复，不覆盖用户配置。内置驱动配置不可删除或修改驱动类；版本替换通过正常导入产生新的 `driverId`，由连接显式切换。内置和用户导入驱动使用相同的隔离执行路径，不依赖应用主类路径。

`DriverService` 将一次上传的主 JAR 和依赖包作为独立驱动配置，存入 `data/drivers/<id>/`。导入时记录 SHA-256，读取 `META-INF/services/java.sql.Driver` 候选，也允许手工指定类名。新版本驱动以新配置导入，不覆盖已有配置；连接通过 `driverId` 选择具体驱动。

加载器隔离厂商依赖，建立连接时直接调用选定 `Driver`，不依靠全局 `DriverManager` 选择版本。需要区分缺依赖、字节码版本不兼容、驱动类无效、URL 不匹配和数据库连接失败。仍被保存的连接配置或活动 JDBC 连接引用时，不允许删除驱动。

`ConnectionService` 负责加密配置和创建 JDBC 连接：

- `jdbcUrl` 原样交给用户指定的驱动；`username/password` 为专用字段，扩展参数通过 `Properties` 传入。
- 读取列表返回脱敏视图。编辑请求中的 `<saved>` 用于保留已有 URL 参数或属性；密码留空保留原密码，`clearPassword` 显式清除。
- 元数据使用独立短连接，不能改变 SQL 标签事务。
- 配置必须原子落盘；损坏或密钥不匹配时报告错误并保留文件，不重新生成空配置覆盖。

新增数据库的普通查询能力通常只需要在页面导入驱动。只有表预览分页、定义查询、计划语法或脚本边界确实不同，才扩展 `SqlDialect`、`MetadataService` 或 `ScriptSplitter`；不要增加限制可连接数据库的白名单。

H2 即开即试的 URL 模板为 `jdbc:h2:mem:toolbox;DB_CLOSE_DELAY=-1`，用户名 `sa`、密码为空；数据仅在本次应用进程中保留。GaussDB 仍由用户导入匹配厂商版本与兼容模式的驱动，不能用内置 PostgreSQL 驱动冒充其适配。

## 会话、执行与事务

每个编辑标签在首次执行时建立 `SessionService.Session`，持有一条 JDBC Connection。不同标签独立，同一会话用 `busy` 门闩排斥并发执行、事务操作和关闭操作。

执行流程：

```text
创建/复用会话
→ POST /executions/prepare
→ 核对 units、源码行号与 confirmationRequired
→ POST /executions，回传 confirmationToken 与唯一 requestId
→ 轮询执行状态，读取结果
→ 终态后释放旧结果；关闭标签时关闭会话
```

**所有提交都必须携带 `prepare` 返回的令牌**，即使 `confirmationRequired=false`。令牌绑定请求指纹与会话上下文；编辑 SQL、参数、模式或改变事务/会话上下文后，重新准备。同一个 `requestId` 仅可重试完全相同的执行内容；不要因网络错误重新生成 ID 自动补发写语句。

`CURRENT` 定位光标单元，`SQL` 执行一个选定单元，`BLOCK`/`CALL` 保留完整代码块或调用，`SCRIPT` 顺序执行到首个错误，`EXPLAIN` 生成方言计划语句，`TABLE_PREVIEW` 使用绑定参数读取表内容。词法扫描器处理注释、引号、dollar quote 和客户端分隔符，不能退回 `split(";")`。

事务约定：

- 默认自动提交；手动模式提供显式 `COMMIT`、`ROLLBACK`。
- `AUTO_COMMIT` 从关闭切换为开启时，**先 `rollback()`，再 `setAutoCommit(true)`**，不能隐式提交用户待处理事务。
- 关闭、闲置回收和应用退出时尝试回滚未提交事务；回滚或关闭不能确认时，需要保留错误状态。
- 释放执行后刷新实际 autoCommit、catalog、schema，避免原生事务/上下文 SQL 使 UI 与 JDBC 状态不一致。
- DDL、存过内部提交和数据库特有事务行为不由客户端统一承诺。

取消先调用活动 Statement 的 `cancel()`；驱动没有终止时，再尝试中止/关闭连接。前端需要区分 `CANCEL_REQUESTED` 与终态 `CANCELED/TIMED_OUT/OUTCOME_UNKNOWN`，不能把发送了取消请求当成数据库已经停止。

## 结果与元数据

`ResultReader` 使用 `Statement.execute()`、`getMoreResults()` 与 `getUpdateCount()` 消费全部 JDBC 结果；更新计数 0 有效，不能被当作结束标记。调用使用 `CallableStatement` 注册 OUT/INOUT/RETURN 参数，并在消费结果后读取输出。

结果模型要保持以下约束：

```text
ExecutionRecord
  id, sessionId, state, mode, elapsedMs, message
  statements[]
    index, sql, startLine, endLine, state, warnings[], error
    results[]
      kind: RESULT_SET | UPDATE_COUNT | OUT_PARAMETERS | PLAN
      columns[{index,label,jdbcType,typeName}]
      rows[][] / updateCount / parameters[] / truncated
```

- `rows` 按列序号保存，重复列名不得覆盖。
- 大整数、小数、日期/时间使用字符串避免精度或时区被浏览器改写；NULL 保留为 JSON null。二进制展示有上限的 Base64 预览。
- 无法解析计划树时仍保留原始计划；估算 cost 不能显示为实际毫秒。
- 表、schema、catalog 分开传递；元数据名称匹配需转义 `%/_`，不要用点号拆分用户原始对象名。
- 标识符依照驱动的引用规则生成，过滤值使用绑定参数，排序列由元数据验证。表预览不默认全表计数，分页不能伪装成稳定快照。
- 泛型/尚未适配方言保留有上限的首屏读取，不提供未经实现的下一页或猜测 EXPLAIN 语法。

## API 速查

统一响应为 `{success,message,data}`。先读取 `GET /api/bootstrap` 获取本次进程的 token；非 GET/HEAD 请求带 `X-Toolbox-Token`。`LocalRuntime` 对请求同时检查真实 remoteAddr、Host、Origin 和跨站请求，不用 X-Forwarded-For 替代来源校验；写请求需要 token。`LoopbackServer` 在创建监听 socket 前拒绝非 loopback 地址。响应禁用缓存。

| 路径 | 方法与用途 |
| --- | --- |
| `/api/bootstrap` | GET：启动令牌、版本、数据目录 |
| `/api/drivers` | GET：驱动配置列表 |
| `/api/drivers/import` | POST multipart：`files` 多文件、`name`、可选 `driverClass` |
| `/api/drivers/{id}/class` | POST：指定用户导入驱动的类；内置驱动不可修改 |
| `/api/drivers/{id}` | DELETE：删除未引用的用户导入驱动；内置驱动不可删除 |
| `/api/connections` | GET/POST：列出和保存连接 |
| `/api/connections/test` | POST：测试未保存/已有连接；还需检查 `data.success` |
| `/api/connections/{id}` | DELETE：删除连接配置 |
| `/api/connections/legacy` | GET：旧配置迁移状态 |
| `/api/connections/{id}/objects` | GET：`kind=catalogs/schemas/tables/routines`，可选 catalog/schema |
| `/api/connections/{id}/table-structure` | GET：catalog/schema/table 对应字段、索引、外键与警告 |
| `/api/connections/{id}/routine-detail` | GET：catalog/schema/name/type/specificName 对应定义、参数、调用模板 |
| `/api/sessions` | POST：按 connectionId、可选 catalog/schema 建立会话 |
| `/api/sessions/{id}` | GET/DELETE：会话状态/关闭；删除已关闭或不存在的会话幂等 |
| `/api/sessions/{id}/recover` | POST：请求回收失效会话；返回 resourceReleased/recoveryPending，不创建新连接 |
| `/api/sessions/{id}/transaction` | POST：`action=COMMIT/ROLLBACK/AUTO_COMMIT`，后者带 autoCommit |
| `/api/executions/prepare` | POST：解析单元、生成预览 SQL 和确认令牌 |
| `/api/executions` | POST：异步提交；字段定义见 `ExecutionRequest` |
| `/api/executions/{id}` | GET/DELETE：状态与结果/删除终态缓存 |
| `/api/executions/{id}/cancel` | POST：请求取消 |
| `/api/executions/{id}/results/{index}` | GET：读取按执行顺序展平的某个结果；不是重新执行或数据库分页 |

HTTP 请求成功不等于数据库执行成功。检查执行终态与每条语句的 `error`；脚本可能已有成功并提交的前置语句。

## 资源与数据生命周期

当前上限在相应实现中定义，扩展时同步测试和文档：

| 所在类 | 约束 |
| --- | --- |
| `DriverService` / multipart 配置 | 一次 1–32 包，单包 128 MiB、合计 256 MiB |
| `SessionService` | 最多 8 个未释放资源的会话；最多 16 个已关闭状态记录；闲置约 30 分钟回收，扫描周期 60 秒 |
| `SessionService` | 4 个清理线程、16 队列槽；退出时清理最多等待 3 秒 |
| `ExecutionService` | 取消宽限 2 秒后请求 abort；退出时 worker 最多等待 5 秒 |
| `ExecutionService` | 4 执行线程、16 队列槽；最多 16 条执行缓存；终态约 15 分钟过期 |
| `ExecutionService` | 准备令牌最多 256 条，约 10 分钟有效；同会话执行互斥 |
| `ExecutionRequest` | 默认 500 行、60 秒；可调至 5000 行、3600 秒；调用参数最多 256 个 |
| `ResultReader` | 单次执行约 16 MiB、10000 行、100 个结果项；文本/二进制有单元格预览上限 |

前端替换旧结果或关闭标签时删除终态执行缓存。结果、会话和准备令牌仅保存在有界内存中；应用重启不恢复或自动续跑 SQL。SQL 编辑文本仅在用户主动下载文件时保存；不要把 SQL、密码或结果自动写入 localStorage。

配置和迁移文件：

```text
data/config/master.key
data/config/connections-v2.enc
data/config/connections-v2.enc.legacy-backup/connections-v2.enc
data/config/connections-v2.enc.legacy-backup/master.key
data/config/drivers-v2.json
data/drivers/<id>/*.jar
data/drivers/bundled-xxx/*.jar
data/migration-backups/legacy-v1/datasources.enc
data/migration-backups/legacy-v1/master.key
```

首次读取连接时，若不存在 V2 配置而发现旧 `datasources.enc`，备份旧文件及密钥后迁移。保留原类型、URL、凭据和无法直接映射的历史字段；连接标记为待选驱动。损坏、重复标识或密钥异常停止迁移，不能覆盖旧文件。V1 源码、说明与旧业务在 `legacy/v1/`，不回迁到 V2 运行包。

当前密文格式为 `DBTX` + 版本字节 `1` + 12 字节随机 IV + AES-GCM 密文/128 位认证标签，头部作为 AAD。明确识别的无头旧 ECB 文件仅用于读取与升级；GCM 认证失败不回退 ECB。先通过目录语义校验，再备份旧 V2 文件和密钥，原子替换为新格式。V1 迁移只升级新写入的 V2 文件，V1 原件与备份字节不变。已有备份内容不匹配时停止，不能覆盖备份。

`PrivateFiles` 在创建时使用所有者专用属性，POSIX 目录 0700、文件 0600；已有配置目录、迁移备份在启动时收紧权限，符号链接拒绝。非 POSIX 使用 owner-only ACL；不支持等价权限时在写入秘密前失败。Windows ACL 分支需在真实 Windows 环境另行验证。升级后老版本不能直接读取新密文。

`drivers-v2.json` 也通过 `PrivateFiles.replace` 更新，运行中写入会重新收紧父目录和目标文件，并拒绝符号链接目标。ACL 创建主体仍由 `user.name` 查找；创建空文件后、写入内容前，再按文件的实际 owner 设置并读取验证 ACL，避免只在写完后检查。不能简单用父目录 owner 代替当前账户。Java 8 的 ACL 抽象不提供可移植的 DACL 禁止继承控制；当前等值读取检查不能保证后续父目录 ACL 传播始终被阻止。域/服务账户、继承变化及实际 Windows 文件系统仍待专项验证；模拟 ACL 用例只验证程序的接受/拒绝策略。

退出次序：停止接收执行/会话 → 标记排队任务未执行并释放门闩 → 请求取消活动 Statement → 保留定时器完成 abort 升级并有界等待 worker → 有界关闭空闲会话。busy 会话只请求 abort，不并发 rollback/close；未确认结束保留 OUTCOME_UNKNOWN 和资源记录。驱动加载器只有在连接引用与正在运行的 JDBC 代理调用都归零时才能释放。进程内不能保证终止不遵守取消的厂商代码。

关闭确认且 worker/disconnect 已退出的会话可转为最多 16 个状态记录，不再占 8 个 JDBC 名额。`resourceReleased` 只代表资源释放，不能用它推导事务提交/回滚结果。前端恢复必须确认建立新会话，不得重新提交旧 SQL。失败回收可重试，仍使用有界清理队列。

通用 HTTP 异常返回固定文案与关联 UUID；日志只记录异常类别和应用代码位置，不记录 Throwable 原文、SQL、URL 或请求体。JDBC 错误仅返回 SQLState/错误码；数据库 warning/notice 保留脱敏文本，应用提示统一脱敏。

## 修改前端

所有运行资源必须留在 `src/main/resources/static/` 并打入 JAR，不引用 CDN。继续使用原生模块、浏览器表单和现有 API 封装，不引入额外编译步骤。

修改时保持：标签文本与选区独立、结果按列位置读取、数据库错误可定位、异步执行状态真实、关闭前处理事务、所有数据库文本正确转义。表单禁止默认导航提交，避免连接凭据进入 URL。可见改动需在真实浏览器检查空态、含数据状态、错误状态及目标尺寸。

轮询有单一调度入口和互斥标记；每个只读请求有 AbortController、15 秒读取截止时间及所属执行/连接标识校验。取消写请求使旧 poll 失效，但不会中断或重发写请求。读失败保留活动状态并重试读取。连接上下文和对象树刷新同样拒绝过时代次。执行状态变化只重绘当前标签，保留编辑器节点、焦点、选区和导航树。

对象表/过程列表的当前请求超时进入明确错误状态，提供同一命名空间的“重试”，不缓存为空对象；被新请求替代的旧 Abort 响应直接丢弃。普通的部分读取失败仍保留成功的对象组，并显示另一组的错误。

`ScriptSplitter.requiresConfirmation` 使用词法屏蔽注释/引号文本后检查 SELECT INTO、行锁和共享锁；执行注释与未知顶层语句保守确认。它不做完整语法或函数副作用分析。

确认分类仅对 `ORACLE` 直接解释 q 引号。`GAUSSDB` 未识别实际兼容模式时，代码区域出现 q 引号形式会要求确认，而非假定 Oracle 或 PostgreSQL 语义；已在普通字符串、美元字符串或注释内的文本不触发该规则。该变更不修改已有脚本/过程块分割能力，不构成 GaussDB 运行验证。

## 构建与验证

异步前端逻辑回归：`node scripts/test-async-ui.cjs`。真实浏览器仍需检查取消、连接切换、恢复和编辑器焦点；Node 逻辑测试不能代替渲染验收。

使用 JDK 8。macOS 可以先设置：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"
```

构建和源代码检查：

```bash
java -version
mvn clean verify
node --check src/main/resources/static/workbench/app.js
java -jar target/database-toolbox.jar
```

Node 只用于开发时可选的语法检查，使用发行 JAR 不需要 Node。H2 仍作为 Maven 测试依赖，并另以原始 JAR 资源提供默认驱动；打包后核查三组驱动及 MySQL 的 protobuf 依赖均位于 `bundled-drivers` 资源中，未进入 `BOOT-INF/lib`。再将 `target/database-toolbox.jar` 复制到空目录执行：

```bash
java -jar database-toolbox.jar --server.port=18080
```

需要真实数据库验证时，可在一次性 MySQL/PostgreSQL 环境运行 `scripts/smoke-v2.py`；参数说明通过 `python3 scripts/smoke-v2.py --help` 查看。该脚本会创建并清理测试对象、连接和会话，导入的驱动留给 UI 验证；密码可通过 `TOOLBOX_MYSQL_PASSWORD` / `TOOLBOX_POSTGRES_PASSWORD` 提供。不要在生产库执行该脚本。

验证应覆盖内置驱动首次安装、缺失/损坏恢复、内置配置保护、外部驱动导入与隔离、重启后保留、查询、同名列/NULL、元数据、存过、脚本边界、计划、事务、取消与独立 JAR 启动。运行过哪些具体组合，以 [DELIVERY.md](notes/reconstruction/DELIVERY.md) 为准；本指南不代表 Oracle、GaussDB 或 Windows 已完成环境验收。


## 单元格修改协议

`CELL_UPDATE` 复用 `/api/executions/prepare` → `/api/executions`，仍需令牌与唯一 requestId。请求 `cellChange` 包含 `{executionId, result, row, column, value, nullValue}`，下标从 0 开始，result 按 statements/results 展开。value 是字符串；NULL 必须显式指定。客户端不提供表名、主键值或原值。

`ResultReader` 记录 `truncatedCells` 的 `[row, column]`；行数截断不会禁止其他完整单元格编辑。表预览结果公开列的 `editable/nullable/readOnlyReason` 和结果级 `readOnlyReason`，隐藏 `CellEdits.Snapshot`。服务端校验预览所属会话、版本、成功终态、行列、主键和类型，从缓存取原值与完整主键。执行后旧快照失效，必须刷新。

`CellEdits` 仅为 H2、PostgreSQL、MySQL InnoDB 开启写回。根据 JDBC 元数据引用标识符并绑定值；执行前复核表结构。自动提交时使用短事务，手动事务使用保存点。`SELECT … FOR UPDATE` 后按类型精确比较所选格原值，避免文本排序规则导致误判；UPDATE 必须影响一行。重新读取验证输入未被静默舍入/转换，数据库警告或触发器改写输入值也回滚本次保存。不会检查其他列是否改变，不保证检测删除后以相同主键/原值重建的记录。

回滚失败时禁止通过恢复 autoCommit 隐式提交，关闭失效会话。厂商/驱动未经验证或元数据不足时保持只读，不能为了开放编辑绕过主键、事务或保存点检查。新增能力需补充 `CellEditingTest`、一次性厂商数据库检查和浏览器验证。
