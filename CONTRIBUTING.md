# 贡献指南（开发者与 Agent）

欢迎通过特性分支和 Pull Request 贡献。项目采用 [GPL-2.0](LICENSE)；提交的贡献需允许按本项目许可证分发。自动化编码 Agent 请先阅读根目录 [AGENTS.md](AGENTS.md)，再根据修改范围查阅 [开发指南](DEVELOPMENT_GUIDE.md)。

## 1. 准备环境与分支

开发需要 JDK 8、Maven 3；Node 仅用于可选的 JS 语法检查和浏览器测试。运行发行版只需要 Java 8 或兼容运行时。

```sh
git clone https://github.com/makotogu/database-toolbox.git
cd database-toolbox
git switch -c codex/your-feature
# macOS 示例；其他系统将 JAVA_HOME 指向 JDK 8
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn clean verify
```

有仓库写权限时推送特性分支；其他贡献者先 Fork，再向上游 `main` 发起 PR。不要把本地数据、密码、驱动上传记录、日志或编译产物提交进 Git。

## 2. 实现边界

- 保持单 JAR、Java 8、单模块与离线静态前端。当前实现位于 `src/`，`legacy/v1/` 只供追溯。
- JDBC 通用能力与厂商方言分开；外部驱动仍由用户选择。新增厂商支持需写明产品版本、驱动版本与已验证能力。
- SQL/事务改动必须考虑同会话执行、失败后的事务状态、取消与重复提交。
- 可视化修改必须经过服务器预览快照、主键定位和并发冲突检查。不能仅按前端行号拼接 UPDATE，也不能绕过执行确认或自动重试写请求。
- 请先说明涉及协议、运行环境、依赖、许可证或架构变化的提案；普通功能、修复和文档可直接提交 PR。

## 3. 验证要求

| 修改范围 | 最少证据 |
| --- | --- |
| 文档 | 链接、命令和当前实现一致；说明未验证项 |
| 后端 | JDK 8 `mvn clean verify`；覆盖成功、失败与相关边界的测试 |
| 前端 | `node --check src/main/resources/static/workbench/app.js`；浏览器实际操作并检查渲染、键盘与错误反馈 |
| JDBC / 方言 | H2 集成测试；对宣称支持的厂商运行真实临时数据库验证，记录版本 |
| 发行 / 启停 | `sh -n scripts/database-toolbox.sh`；最终 JAR 空目录启动、查询、停止/重启与摘要检查 |

本机测试启动（使用临时数据目录，端口按需调整）：

```sh
TASK_DATA=$(mktemp -d)
java -jar target/database-toolbox.jar \
  --server.address=127.0.0.1 --server.port=18090 \
  --toolbox.storage-root="$TASK_DATA"
```

打开 `http://127.0.0.1:18090/`，用内置 H2 创建专用测试连接。不要使用已有真实连接目录。数据库集成脚本会创建/清理测试对象，只允许连接一次性数据库：

```sh
python3 scripts/smoke-v2.py --help
python3 scripts/smoke-cell-edit.py --help
python3 scripts/verify-release.py --help
```

可重复的浏览器脚本需要 Node 18+、Playwright 与 Chromium（仅开发环境需要）。可在仓库外的临时目录安装，避免引入前端构建依赖：

```sh
TASK_UI=$(mktemp -d)
npm install --prefix "$TASK_UI" playwright
"$TASK_UI/node_modules/.bin/playwright" install chromium
TOOLBOX_PLAYWRIGHT_PATH="$TASK_UI/node_modules/playwright" \
  TOOLBOX_BASE_URL=http://127.0.0.1:18090 node scripts/smoke-cell-ui.cjs
```

已有 Chrome 时可通过 `TOOLBOX_CHROME_PATH` 指定可执行文件，省去 Chromium 下载；`TOOLBOX_UI_SCREENSHOT` 指定截图路径，默认 `/tmp/toolbox-cell-editor.png`。脚本新建并清理独立 H2 内存库、连接和会话，仍应在临时工作台实例运行。

异步状态回归可直接运行 `node scripts/test-async-ui.cjs`，不需要第三方 Node 依赖。同样的 Playwright 环境可运行 `scripts/smoke-hardening-ui.cjs`，检查连接切换、取消旧响应、编辑器焦点和失效会话恢复；它创建独立 H2 夹具，默认截图 `/tmp/toolbox-hardening-ui.png`。逻辑测试不能替代浏览器检查。第一批 Issue #2 的实际记录见 [安全边界与可靠性验收](notes/features/ISSUE_2_HARDENING.md)。

SQL 编辑器检查使用 `node scripts/test-sql-editor.cjs` 和 `node scripts/smoke-sql-editor-ui.cjs`，后者沿用上面的 Playwright 环境变量、独立 H2 夹具及截图配置。覆盖高亮与选区、按连接选择的草稿保存、隐私确认、失败重试、多页面冲突和清除；记录见 [SQL 编辑器验收](notes/features/SQL_EDITOR_DRAFTS.md)。

可视化单元格修改至少检查：点击选中与 Enter/双击打开、普通字段保存、NULL 与空字符串、只读字段说明、冲突失败保留输入、手动提交/回滚后刷新。检查文字和值经过转义，不把数据库内容插入可执行 HTML。

不具备某数据库或平台时，明确写“未验证”，不要以 H2 或源码推断替代厂商实测。

## 4. 提交与 PR

提交前检查 `git diff --check` 和变更清单，确认没有凭据、个人数据或发行二进制。提交信息描述行为，例如 `feat: edit table cells with transaction-safe conflict checks`。

PR 描述请包含：

1. **问题与行为**：用户如何触发，修改前后有何不同。
2. **实现范围**：影响的模块、关键边界和不支持的场景。
3. **验证**：实际命令、结果、浏览器操作，以及数据库/驱动/JDK/OS 版本。
4. **剩余事项**：未验证环境、兼容性限制、迁移或发布需要维护者完成的操作。

Agent 交付还需给出分支名、提交 SHA、修改文档入口和未完成项。保持 PR 可审查；未经授权不合并 `main`、不改写历史、不覆盖已发布 Release。CI 结果不能代替需要真实数据库和浏览器的验证。
