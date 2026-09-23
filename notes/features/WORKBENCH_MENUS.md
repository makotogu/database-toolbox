# 工作台菜单与标签交互

## 行为与边界

顶部增加“文件 / 执行 / 视图 / 帮助”。连接、对象、标签和单元格提供上下文菜单，连接/对象/标签也有可见的“…”入口。菜单显示当前不可用的原因，支持方向键、Enter、Esc 和 Shift+F10；桌面数据库导航可收起。

- 普通 SQL 标签可重命名、复制 SQL、关闭其他或右侧标签。副本保留 SQL、连接上下文和执行设置，首次执行才建立独立会话，不复制执行结果或事务。
- 批量关闭逐个等待确认和会话关闭；取消、忙状态或失败后停止。手动事务独立于 SQL 是否已保存而要求回滚确认。关闭期间禁止新执行和编辑文本，失败恢复可操作状态。
- “查看结构”仅取元数据，不自动读取表数据。首次切换到“数据”或选择“查看数据”沿用既有表预览路径，已有执行结果不会因切换重复查询。
- 单元格菜单的“查看”仅展示原值，“编辑”单独打开编辑器，双击/Enter 保持原编辑入口。沿用只读条件与安全写回路径；菜单绑定原执行、结果行数组及 DOM 单元格，刷新后失效；普通查询结果不能通过菜单写回。
- 所有执行、事务及编辑仍使用现有 API 与确认流程。SQL 草稿保持按连接选择并提示隐私风险。未加入 CSV、完整导出、SQL 历史、行增删或自动补全。

## 仓库整理（2026-09-23）

PR #7、#8 原先合并到功能分支，尚未进入 main。通过 [PR #10](https://github.com/makotogu/database-toolbox/pull/10) 补入，合并提交 `713fccbdc1b45044f055a76ebd9b743e79d8ea8f`。

[PR #9](https://github.com/makotogu/database-toolbox/pull/9) 改以 main 为目标，补充生成列标志冲突时的保守保护及回归测试后合并，提交 `218eb95a30413f01314879bee76356df4df99e97`。GaussDB 503 SPC2000C 与用户内网驱动仍未实测，不能把合并或 H2 验证视为厂商兼容验收。细节见 [高斯兼容记录](GAUSS_CELL_EDIT_COMPAT.md)。

## 验证（2026-09-23）

环境：macOS 27.0、Corretto 8u452、Chrome 153.0.8010.53、Playwright 1.62.1、H2 2.2.224。只使用临时 storage root 和独立测试连接。未使用真实连接、SQL 或内网驱动。

- `node scripts/test-menu-state.cjs`：7 组通过，检查已保存 SQL 的手动事务关闭确认、取消/确认、批量中止、关闭期间忙状态和编辑器只读、失败恢复、旧结果/旧 DOM 菜单拒绝及正常结果可用。
- `node scripts/smoke-menus-ui.cjs`：真实浏览器检查菜单键盘/焦点、禁用原因、SQL 副本独立会话、非活动标签的右键目标、批量关闭取消中止、结构仅浏览、对象菜单、帮助与导航切换。下载 SQL 后手动事务关闭仍确认，拒绝后原会话保留；延迟关闭期间执行禁用，键入与 Tab/Shift+Tab 不改变 SQL。1440×980 和 360×800 无横向页面溢出、弹出菜单裁切或控制台/页面错误；截图人工查看通过。
- 原浏览器回归通过：`smoke-cell-ui.cjs`（编辑、并发冲突、手动提交/回滚、锁等待取消）、`smoke-sql-editor-ui.cjs`（选区、草稿隐私、恢复和冲突）、`smoke-onboarding-ui.cjs`（连接向导和 H2 演示）、`smoke-table-filters.cjs`（H2 过滤、分页、失败保留只读结果和切换标签时事务刷新归属）。
- JDK 8 `mvn -o -B -ntp clean verify`：111 项，110 通过、1 跳过（未配置真实 PostgreSQL 集成环境），0 失败/错误。`node --check app.js`、ES 模块语法检查、`sh -n scripts/database-toolbox.sh`、既有 Node 异步/SQL/连接字段回归及 `git diff --check` 通过。最后两处仅前端交互修正后，重跑 Node 菜单/异步/SQL 回归，使用 `mvn -o -B -ntp package -DskipTests` 重新打包；复用上述未变化的后端验证结果。
- 最终 JAR 的 9 个静态资源逐文件与源码一致；不使用静态资源覆盖，临时存储下通过全部 `smoke-menus-ui.cjs` 浏览器检查，额外确认结构切数据首次恰好一次预览、再次进入不重复查询、查看/编辑对话框分开，并人工查看桌面/窄屏截图。`verify-release.py` 的 9 项独立检查通过，覆盖空目录启动、静态首页与资源、默认驱动查询、请求保护、目录锁、重启配置及 SQL 草稿恢复不执行。
- 独立功能包：`dist/features/workbench-menus/`（忽略提交）。JAR SHA-256：`9282ebea7e9e6b08eae2d7284a3a28c5f5d75032410816372bb994baef05efbc`。

公开 Release 保持不变；Linux 优先的运行边界不变，未增加 Windows 适配或声明新厂商兼容性。本轮浏览器只验证上述 Chrome 与尺寸，未验证其他浏览器或实际 Linux 桌面渲染。

## 重跑

先读 `node scripts/smoke-menus-ui.cjs --help`，按 [贡献指南](../../CONTRIBUTING.md) 提供现有 Playwright/Chrome 路径，并指定使用临时存储的工作台地址。脚本创建并清理自己的 H2 内存连接和会话；禁止指向生产实例。浏览器与 Node 依赖仅用于开发验证，发行 JAR 无需安装它们。
