# 表预览多条件过滤

支持一组 ALL/AND 或 ANY/OR 条件，最多 30 条；添加、移除、清空后点击“读取数据”应用。空字符串与 NULL 分开表达，NULL 操作禁用值输入。复杂嵌套组合仍使用 SQL 编辑器。

条件草稿和成功应用的条件独立。新条件从第一页读取，翻页使用已经应用的条件；待应用或失败时禁用翻页。成功时更新条件、页码、SQL 模板；准备失败、执行失败或取消时保留上次成功结果，明确提示仅供查看，禁止旧快照写回。服务端仍使用元数据标识符、类型化参数和 prepare 指纹，改变 ALL/ANY 后不能复用确认令牌。

保留旧结果的回归检查暴露了事务切换至刷新之间的可操作窗口；现将两者连续标为忙，并显式将读取绑定到原标签。延迟事务响应时切换标签，不会将读取提交到新标签会话。

## 验证（2026-09-18）

- macOS 27.0 / Corretto 8u452 / Chrome 152.0.7977.84 / Playwright 1.62.1。
- JDK 8 `mvn -o -B -ntp clean verify`：102 项通过。新增 H2 集成测试：ALL/ANY 结果差异、确认令牌篡改拒绝、30/31 条边界、NULL/空字符串、精确小数、注入样式字符串仍为绑定值。
- `node scripts/smoke-table-filters.cjs`：真实浏览器 H2 405 行夹具，检查 AND/OR、待应用状态、翻页及首屏重置、NULL/空字符串、NOT LIKE、精确小数、非法数字、prepare 后删除表导致真实执行失败、旧结果只读、恢复与 30 条 UI 上限。1440×980 / 820×900 截图检查，无横向页面溢出。
- 同一脚本通过基础连接表单连接一次性 MySQL 8.4.8 / Connector-J 9.7.0 和 PostgreSQL 16.15 / JDBC 42.7.13。真实执行 AND/OR、翻页、NULL/空字符串、DECIMAL(30,3) 和注入样式绑定值检查通过；未将 PostgreSQL 结果当作 GaussDB 验收。
- 原单元格浏览器 6 组、SQL 编辑器 12 组检查通过；Node 异步状态 9 组、SQL 编辑器 6 组、连接 URL 检查通过。事务延迟与切换标签单独回归通过。
- 原异步 UI 浏览器 6 组回归通过。最终前端调整后执行 `mvn -o -B -ntp package -DskipTests`；逐文件校验 JAR 内静态资源与源码一致，再以 JAR 自带 UI、全新临时存储复跑连接向导和 H2 过滤检查，通过。最终 JAR 的七组启停/事务重启检查通过。
- 独立预览包：`dist/features/workbench-experience/`（忽略提交）；JAR SHA-256：`24ea6bef3a3fd7b9eb5744c7c42d9c65ff8a9b2dd2ef99666e3c443630c9db1b`。三个阶段均为功能分支 PR，未覆盖公开 Release。

## 重跑

所有运行均使用临时 storage root；不要指向真实连接目录。`scripts/smoke-table-filters.cjs --help` 说明依赖。可选 `TOOLBOX_FILTER_FIXTURES` 指向临时 JSON 数组，元素为 `{kind, host, port, database, username, password}`；kind 为 mysql/postgresql，数据库必须可丢弃，脚本创建并删除独立表。不要提交凭据文件或日志。

当前只验证上述数据库与平台组合；GaussDB、Oracle、Windows 和 Linux 桌面弹窗未验收。Linux POSIX 启动单独见 [启动记录](POSIX_STARTUP.md)。CSV/完整导出、SQL 历史、行增删、自动补全与格式化不在本轮范围。
