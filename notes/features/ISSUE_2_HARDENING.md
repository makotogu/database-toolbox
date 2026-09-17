# Issue #2 第一批：安全边界与可靠性

日期：2026-09-16。分支：`codex/issue-2-hardening`。根据 [Issue #2 评估回复](https://github.com/makotogu/database-toolbox/issues/2#issuecomment-5694558061) 中的第一批范围，在所有者批准后实施。评估基线 `b11c01f` 与本分支起点 `772821d` 的文件树相同。本记录不代表整个 Issue 已完成，也不代表发布验收。

## 范围与行为

| Issue 项 | 实施结果 |
| --- | --- |
| 1 加密与文件权限 | 新写入使用带版本头的 AES-GCM、随机 12 字节 IV 和认证标签。旧 ECB 在目录校验后先备份原密文与密钥，再升级。目录、文件、临时文件及备份创建时限制为所有者访问；已有配置启动时收紧权限。认证失败、缺钥、符号链接、备份冲突不会清空或覆盖原配置。 |
| 2 最小访问边界 | 在创建监听 socket 前拒绝非 loopback 地址；HTTP 同时校验真实 remoteAddr、Host、Origin、跨站标记和写 token，覆盖非根 context path。保留浏览器 bootstrap 入口和已有一次性迁移流程。 |
| 3/4 会话回收与退出 | 仅在确认连接关闭且执行/断连结束后释放名额；未释放资源仍受 8 会话上限限制，关闭状态记录另有 16 条上限。提供异步恢复按钮，新连接需确认且不重跑旧 SQL。退出停止接收、取消排队任务、取消并有界等待 worker，再有界清理会话；busy 时不并发 rollback/close。驱动 lease 在确认物理连接关闭后释放，加载器等待引用和正在运行的代理调用归零。 |
| 5 SELECT 确认 | 词法屏蔽注释和引号内容后，确认 SELECT INTO、FOR UPDATE/SHARE（含 NO KEY/KEY）与 LOCK IN SHARE MODE；保留原 prepare 指纹和会话上下文协议。 |
| 8 异步竞态 | 轮询互斥，读请求有中断信号、截止时间和归属校验；过时执行/连接/对象树响应不能覆盖当前状态。读失败继续读取，不重新提交写入。执行更新保留编辑器节点、焦点、选区和导航树。 |
| 11 错误脱敏 | 通用异常使用固定响应与关联 UUID，日志只记录类型及应用代码位置。SQL 错误保留 SQLState/错误码；warning/notice 保留脱敏文本，避免丢失 PostgreSQL NOTICE。 |

没有实施 JDBC URL 策略、非 Driver 类初始化/厂商 unwrap 限制、文件大小策略、CI、类型转换合并或大文件拆分。相关回归测试属于本批范围。没有新增运行时依赖、前端构建链或全 API 登录。

## 初次提交验证环境与结果（48a009e）

仅使用临时工作台目录、合成凭据/SQL、内存 H2 和一次性数据库容器；没有读取真实连接数据。运行环境为 macOS 27.0（26A428）、Corretto `1.8.0_452`、Spring Boot `2.7.18` / Spring `5.3.31`、Node `22.15.1`、Playwright `1.62.1`、Chrome `152.0.7977.84`。

| 验证 | 实际结果 |
| --- | --- |
| JDK 8 `mvn -o -B -ntp clean verify` | 87 项通过，0 失败、0 错误、0 跳过。包括 GCM 防篡改、迁移备份/缺钥/权限、HTTP 保护、异常响应/日志脱敏、关闭配额、回收、忽略取消的 worker、排队任务不调用 JDBC、驱动 lease 和确认分类。 |
| `node scripts/test-async-ui.cjs` | 6 项通过：轮询互斥、取消/旧执行响应、连接及标签归属、对象树刷新、读取失败不重发写入。测试执行实际源码函数。 |
| JS `node --check`、启动器 `sh -n`、`git diff --check` | 通过。 |
| `scripts/smoke-hardening-ui.cjs` | 5 组通过：A/B 切换、完成后编辑器焦点/节点/选区、取消时旧 RUNNING 响应、BROKEN 恢复不重跑 SQL、两种视口（1440×980、1100×800）且无 JS/console 错误。 |
| `scripts/smoke-cell-ui.cjs` | 6 组通过：选中/保存/转义/刷新、键盘/主键只读/NULL、冲突保留草稿、手动提交回滚、行锁取消、视图只读；无页面 JS 错误。 |
| 两组浏览器截图检查 | 编辑器、恢复状态、单元格弹窗的文字和控件正常显示，无可见遮挡；不是仅依据 API 测试推断。 |
| `scripts/smoke-v2.py --use-bundled-drivers` | MySQL 8.4.8 / Connector-J 9.7.0 与 PostgreSQL 16.15 / JDBC 42.7.13，共 19 项通过；含 NOTICE、事务、取消后会话可用。 |
| `scripts/smoke-cell-edit.py` | H2 2.2.224 共 9 项通过；上述 MySQL/PostgreSQL 共 22 项通过，覆盖精度、NULL、并发冲突、事务、跨库定位等已有能力。 |
| `scripts/verify-release.py` + 最终 JAR/JDK 8 | 8 项通过：打包、空目录启动、静态资源、访问保护、内置驱动、查询、目录锁、重启保留。 |
| 最终 JAR 非 loopback 配置 | 传入 `--server.address=0.0.0.0` 时以非零退出并报告 loopback 限制；守卫在监听 socket 创建前拒绝，未建立公网监听。 |

测试曾发现 PostgreSQL NOTICE 被过度隐藏和执行提交响应重绘导致焦点丢失，修正后分别通过厂商与浏览器回归。最后补充的排队任务关闭使用纯状态释放门闩，已由最终全量 Java 测试验证；未重复与该变化无关的已通过浏览器/厂商流程。最终 JAR 另外重跑打包、H2/厂商单元格编辑与已有浏览器检查。

## 重复验证

基础命令与临时启动方式见 [CONTRIBUTING.md](../../CONTRIBUTING.md)。运行数据库脚本前先读 `--help`；脚本会创建并清理夹具，只能使用一次性数据库。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -o -B -ntp clean verify
node --check src/main/resources/static/workbench/app.js
node scripts/test-async-ui.cjs
sh -n scripts/database-toolbox.sh
python3 scripts/smoke-cell-edit.py --base-url http://127.0.0.1:18090
```

使用仓库外安装的 Playwright 与现有 Chrome，通过 `TOOLBOX_PLAYWRIGHT_PATH`、`TOOLBOX_CHROME_PATH` 和 `TOOLBOX_BASE_URL` 分别运行 `node scripts/smoke-hardening-ui.cjs` 与 `node scripts/smoke-cell-ui.cjs`。厂商脚本另传临时数据库 URL；凭据不要写入记录或真实配置。单 JAR 验证运行 `scripts/verify-release.py --jar target/database-toolbox.jar --java <JDK8-java> --port <空闲端口>`。

## PR #3 评审跟进（2026-09-16）

- **对象树超时：接受问题、校正症状。** 原实现已有 `tableError/routineError`，不一定显示空库，但超时保留的是原始浏览器错误且缺少就地重试。现在区分被新请求替代的 Abort 与当前读取超时：前者丢弃，后者进入“读取超时，请重试”状态。不能仅 return 导致一直显示加载中。真实浏览器拦住两类元数据请求，等待实际 15 秒截止时间，确认提示/按钮可见；解除拦截后点重试，真实 H2 表重新出现。另有过时代次、部分成功结果的逻辑回归。
- **驱动配置权限：纳入本批。** `writeCatalog` 改用 `PrivateFiles.replace`，统一运行期目录/文件权限、临时文件和符号链接策略。新增用例先放宽临时配置目录，再触发驱动目录写入；旧实现保持 0755，新实现恢复 0700，目标文件为 0600。符号链接目标拒绝且原文件不改。没有据 `createTempFile` 就推断已实测新文件退回 0644。
- **GaussDB q 引号：保守确认，不一概映射 PostgreSQL。** [华为兼容模式文档](https://support.huaweicloud.com/centralized-devg-v2-gaussdb/gaussdb_42_1217.html)区分 A、B、C、PG；产品名不足以确定实际语法。本批仅修改确认分类，遇代码区域 q 引号便要求确认，普通字符串/美元字符串内的 q 文本仍被屏蔽。保留原脚本和过程体分割路径；未宣称厂商实测。
- **Windows ACL：补写入前检查，保留验收缺口。** 空文件创建后、写入字节前按实际文件 owner 设置并读取验证 ACL；增加模拟 owner 和额外 ACE 拒绝用例。没有直接改成父目录 owner，因为父目录可能归另一账户所有。初始主体查找仍依赖 `user.name`，失败则停止。依据 [Java 8 ACL 接口](https://docs.oracle.com/javase/8/docs/api/java/nio/file/attribute/AclFileAttributeView.html)及 [OpenJDK 8 Windows 实现](https://github.com/openjdk/jdk8u/blob/master/jdk/src/windows/classes/sun/nio/fs/WindowsAclFileAttributeView.java)，目前不声称支持跨平台禁止继承；读取时的 ACL 等值不保证后续继承变化不会传播。真实 Windows 域/服务账户和继承行为仍需验证。
- **可读性：** 展开密文读取/写入/迁移、会话清理队列及 abort/退出中的多语句行，保留流程次序。未趁机拆分模块或变更会话协议。

新增 Node 超时用例在旧实现失败（未进入明确超时状态）；新增 Java 权限、GaussDB 确认用例也先在旧实现失败，再验证修复。跟进版实际执行：

| 验证 | 结果 |
| --- | --- |
| 同一 Corretto 8 下 `mvn -o -B -ntp clean verify` | **91 项通过**，0 失败/错误/跳过，含 2 项模拟 ACL 策略用例；不等于 Windows 实测 |
| `node scripts/test-async-ui.cjs` | **9 项通过**，含超时重试、旧对象响应、部分读取失败 |
| `scripts/smoke-hardening-ui.cjs` | **6 组通过**，包含真实 15 秒对象请求超时和重试恢复；截图检查通过，无页面/console 错误 |
| 最终 JAR 的 `scripts/verify-release.py` | **8 项通过**，含空目录、内置驱动、查询、目录锁及重启持久化 |
| JS/启动器语法、diff 和文档链接 | 通过 |

本轮没有重新运行 MySQL/PostgreSQL 全矩阵及原单元格浏览器脚本；此前证据只用于未改变的对应路径。本轮不改变厂商 JDBC 调用或事务语义，H2 驱动目录写入和最终 JAR 已重新验证。CI、URL 风险策略、类初始化/unwrap、文件上限仍属于未批准的后续范围，不作为本次评审自动追加项。PR 继续保持草稿，未合并或发布。

## 兼容性与剩余边界

- 新 GCM 配置不能被旧发行包直接读取；升级备份只反映升级时点，之后新增的连接不在旧备份中。V1 原件字节保持不变。
- POSIX 所有者权限已实测；非 POSIX 使用 ACL，真实 Windows 尚未验证，不支持等价保护时会在写入秘密前失败。不得据此宣称 Windows 验收通过。
- Oracle、GaussDB 未实测；H2/PostgreSQL 不能代替这些厂商证据。
- loopback、token 和文件权限不构成本机不互信账号/同用户进程隔离；导入的 JDBC 驱动仍是受信任可执行代码。
- 取消/退出有界等待不保证终止不配合的驱动，也不证明数据库事务已回滚；未确认资源不会豁免限额。恢复连接不恢复旧事务。
- SELECT 判断是防误操作启发式，不能证明任意函数无副作用。warning/notice 的脱敏是尽力过滤，应避免在数据库诊断消息中输出秘密。
- 特性包位于忽略的 `dist/features/issue-2-hardening/`，沿用 POM 的 2.0.0，通过目录和 SHA256SUMS 区分；没有覆盖已发布 Release。
