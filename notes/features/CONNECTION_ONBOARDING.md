# 连接向导与 H2 试用

MySQL/PostgreSQL 驱动支持主机、端口和库名输入；复杂 URL、外部驱动和隐藏参数保留完整 URL 编辑。基础字段未修改时不重写 URL；改动时编码库名并校验端口、主机。高级设置折叠但值保留，SQL 草稿开关与隐私说明继续独立展示。

“试用 H2”通过服务端标记创建或复用内置 H2 内存连接，在新 SQL 标签运行固定 `SELECT 1`。不修改既有文本、不恢复事务、不执行草稿。连接配置持久化，内存数据随进程退出消失；用户修改过的演示连接保留，下一次试用创建新的受控配置。接口仍受本机请求令牌保护。

## 验证（2026-09-18）

- macOS 27.0 / Corretto 8u452：`mvn -o -B -ntp clean verify`，101 项通过。新增测试验证内置驱动选择、同名连接不覆盖、重复/重启复用、修改后保护和真实 H2 SELECT 1。
- `node scripts/test-connection-fields.cjs`：MySQL/PostgreSQL URL 编码与 IPv6 往返、复杂地址退回完整模式、非法主机/端口/空库名检查通过。
- 原 SQL 编辑器 6 组、异步状态 9 组逻辑回归通过。
- `scripts/smoke-onboarding-ui.cjs`，Chrome 152.0.7977.84 / Playwright 1.62.1：真实演示查询、prepare token、重复连接复用、原编辑器保留、字段切换与校验、隐藏 URL/属性原样保存通过；1366×960、820×900 截图检查，无页面异常和控制台错误。
- 开发时使用临时 storage root、JAR 后端和本地静态资源。此记录不代表真实 MySQL/PostgreSQL 服务端、GaussDB、Oracle 或 Windows 已验收。

URL 规则参考官方 [PostgreSQL JDBC](https://jdbc.postgresql.org/documentation/use/) 与 [MySQL Connector/J](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-jdbc-url-format.html)。复杂连接交由驱动解释，不通过基础字段猜测重建。

后续整体验收已通过真实 MySQL 8.4.8 / PostgreSQL 16.15 的基础表单连接，并复核编辑连接时切换驱动不会自动填回已清空的用户名；版本和最终 JAR 验收见 [多条件过滤交付记录](TABLE_FILTERS.md)。
