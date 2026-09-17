# SQL 高亮与按连接选择的草稿保存

关联 [Issue #4](https://github.com/makotogu/database-toolbox/issues/4)。基于已合并 PR #3 的 `main`（`9eac403`），分支 `codex/sql-editor-drafts`。2026-09-17 验收。

## 本次范围

- 原生 SQL 编辑器增加离线高亮：关键词、数字、字符串、引号标识符、注释与代码块引用。保留 textarea 作为输入源；着色不参与执行解析，不新增依赖、CDN 或构建系统。
- 在连接设置中增加默认关闭的“保存此连接的 SQL 草稿”。说明 SQL 可能含口令、个人信息和业务数据，以及本机账户/密钥访问、下载文件与备份的边界；开启和关闭时分别确认保存风险、清除影响。
- 只保存普通 SQL 标签的文本、名称及连接引用。停止输入约 900ms 后加密写入本机 `data/config/sql-drafts.enc`；显示真实保存状态。恢复不自动建立数据库会话、恢复事务或执行 SQL。恢复后顶部连接与活动 SQL 标签一致。
- 关闭标签会删除对应草稿，关闭选项或删除连接会清除该连接的草稿。当前页面文本可手动下载；不承诺擦除已有下载、备份或文件系统快照。
- CSV 留待完整导出功能统一规划；没有增加 CSV、结果导出、查询历史、SQL 自动补全、格式化或框架迁移。

## 保存边界

草稿通过现有本机 Host/Origin/令牌保护的 API 访问，复用 AES-GCM 与 owner-only 文件权限。连接列表不包含草稿或内部 generation，浏览器 localStorage/sessionStorage 不保存 SQL。

每个连接最多 20 个非空 SQL 标签；单条 SQL 最多 1 Mi UTF-16 单元，全部连接合计最多 8 Mi；名称最多 200 字符。超限或损坏会报错，原草稿不会被空数据或截断内容替换。高亮超过 200000 字符时暂停，编辑与手动文件保存仍可使用。

每连接版本比较交换，防止多页面相互覆盖；保存响应丢失时，保留相同 requestId/revision/内容供用户重试。读取失败不覆盖未知草稿，409 冲突提示先下载页面文本再刷新。关闭并重新开启保存会更新服务端 generation，拒绝旧页面迟到的首次写入。每个连接的保存、关闭选项与删除操作在同一锁内进行。

只保留最近成功保存的当前标签集合，不是执行历史或版本历史。不会恢复结果、调用参数、选区、滚动位置或标签临时 catalog/schema。恢复后使用连接默认上下文，执行前仍需核对连接与内容。草稿清除先于连接配置写入；清除失败会保留连接设置并返回失败，后续配置写入失败不会回填已清除的草稿。

## 已验证

环境：macOS `27.0`、Amazon Corretto `1.8.0_452`、Spring Boot `2.7.18`、H2 `2.2.224`、Node `22.15.1`、Playwright `1.62.1`、Chrome `152.0.7977.84`。所有测试使用独立临时数据目录、合成 SQL 和 H2 夹具，没有读取真实连接数据。

| 检查 | 实际结果 |
| --- | --- |
| JDK 8 `mvn -o -B -ntp clean verify` | 100 项 Java 测试通过，0 失败/错误/跳过；包含 9 项新增草稿测试 |
| `node scripts/test-sql-editor.cjs` | 6 组通过：显示保持源文本、转义、大文本退化、保存串行化、相同请求重试、冲突/读取失败/过期响应保护 |
| `node scripts/test-async-ui.cjs` | 9 组既有异步回归通过 |
| `node --check`：app.js、sql-highlight.js、sql-drafts.js；`sh -n scripts/database-toolbox.sh` | 通过 |
| `scripts/smoke-sql-editor-ui.cjs` | 12 组真实 Chrome 检查通过，见下方操作范围 |
| `scripts/smoke-hardening-ui.cjs` | 6 组既有浏览器回归通过，包括执行完成保留编辑器节点/焦点/选区，取消和会话恢复不重放 SQL |
| `scripts/verify-release.py --jar target/database-toolbox.jar --java <JDK8>/bin/java --port 18094` | 9 组通过：空目录启动、6 个静态资源、3 组隔离驱动、本机请求保护、H2 查询、目录锁、停止重启和草稿持久化 |
| `git diff --check` 与修改文档链接 | 通过 |

完整 Java 验证后，后续前端调整补齐“未绑定标签通过顶部选择连接时开始保存”的触发器，并将恢复标签的页面 ID 与持久化 ID 分开，恢复活动标签的 schema/catalog 选项；使用 JDK 8 `mvn -o -B -ntp -DskipTests package` 重打 JAR，再运行编辑器浏览器与独立 JAR 验收。Java 源码与已通过的 100 项测试一致。

浏览器操作覆盖：默认不发送草稿写请求、缺少令牌拒绝写入、拒绝/接受隐私确认、原样显示中文/大整数/HTML 文本、刷新恢复不创建执行会话、精确选区执行、原生撤销、composition 事件期间回到原生文本、水平/垂直滚动对齐、大文本提示、保存响应丢失后原请求重试、两个页面冲突、仅恢复已开启连接、未绑定编辑器分配连接、跨连接相同草稿标识的独立标签、恢复后可选 schema、关闭标签删除草稿、关闭选项保留当前文本并清除持久化。

截图检查了 1366×900 与 768×900 布局、隐私表单、高亮文本和滚动状态；控件可见，无页面横向溢出，没有意外 JS/console 错误或外部网络请求。浏览器自动化使用中文文本插入与 composition 事件，不代替人工操作系统输入法候选窗验收。

独立 JAR 验证保存了一条未执行的 `INSERT` 草稿后停止/重启；重启后草稿内容与版本保持，H2 表中仍只有原来的 42，证明该草稿没有自动执行。

## 交付与剩余范围

使用方法见 [README](../../README.md)，协议与维护约束见 [开发指南](../../DEVELOPMENT_GUIDE.md)。独立预览包放在忽略的 `dist/features/sql-editor-drafts/`；它不是新的公开 Release，版本号仍为 2.0.0。

未实测 Windows/Linux 权限、Safari/Firefox 和各系统输入法候选窗。未新增或声明 Oracle/GaussDB 等厂商执行兼容性；高亮仅作阅读提示。此 PR 不关闭 Issue #4 的完整规划，不合并 main，也不覆盖既有发行资产。
