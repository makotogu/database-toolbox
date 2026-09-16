# 可视化单元格编辑：实现与验收

日期：2026-09-16。分支：`codex/edit-table-cells`。这是特性分支构建，已发布的 v2.0.0 不包含本功能。

## 用户行为

在表内容中单击选择格子，点“编辑选中格”，或双击/Enter 打开。弹窗保留原值、提供新值与 NULL 选项，显示自动提交或手动事务的保存语义。保存需确认；成功刷新当前分页与筛选。错误时保留输入供复制，写请求已尝试后禁止重复保存，需核对结果并刷新。

表预览新增自动提交、提交、回滚控件。手动事务的保存不会擅自提交；提交/回滚后刷新。等待数据库行锁时可取消保存。

## 写入边界

- H2、PostgreSQL、MySQL InnoDB 的有主键普通表；支持复合主键。
- 普通 SQL 结果、无主键表、视图、主键、生成/自增字段只读。元数据不完整则保持只读。
- 文本、整数、精确小数、布尔、日期与无时区时间戳；TIME 当前限整秒。浮点、二进制/LOB、带时区等未适配类型只读；被截断的单元格或主键不能回写。
- 使用服务端预览快照与会话版本绑定。预览与写入使用相同的明确 catalog/schema，避免同名表歧义。
- 锁定主键对应行后精确比较所选格原值；参数化更新须影响一行；回读核对输入未被静默转换。结构变化、冲突、警告、舍入或触发器改写所选值时回滚本次操作。
- 自动提交使用独立短事务；手动事务使用保存点。回滚无法确认则关闭失效连接，不能恢复 autoCommit 导致隐式提交。
- 冲突检查针对所选格，不是整行版本审计；不能检测以相同主键/原值重建的记录。触发器跨库、外部或非事务副作用仍遵循数据库能力。

## 实际验证

环境：macOS，Corretto `1.8.0_452`，Chrome/Playwright；使用临时存储和一次性数据库。未使用真实业务数据。

| 验证 | 结果 |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -o -B -ntp clean verify` | 70 项通过，0 失败，0 跳过；包括 13 项 CellEditingTest |
| JS `node --check`、启动脚本 `sh -n`、`git diff --check` | 通过 |
| `scripts/smoke-cell-edit.py` 默认 H2 2.2.224 | 9 项通过；仅使用内置驱动 |
| 同脚本 MySQL 8.4.8 / Connector-J 9.7.0 与 PostgreSQL 16.15 / JDBC 42.7.13 | 22 项通过；包括精度、NULL、冲突、事务、MySQL 跨数据库同名表定位与 MyISAM 只读 |
| `scripts/smoke-cell-ui.cjs` | 6 组浏览器流程通过：选中/保存/转义/刷新、键盘/主键只读/NULL、冲突保留草稿、手动提交回滚、行锁取消、视图只读；无页面 JS 错误 |
| 编辑弹窗截图人工检查 | 原值/新值、NULL、提交语义与操作按钮均正常显示 |
| `scripts/verify-release.py` 最终 JAR + JDK 8 | 8 项通过：打包、空目录启动、静态资源、访问保护、默认驱动、查询、目录锁、重启保留 |

完整回归命令与可重复脚本入口见 [CONTRIBUTING.md](../../CONTRIBUTING.md)。厂商检查示例（将端口替换为自己的临时数据库端口）：

```sh
python3 scripts/smoke-cell-edit.py --base-url http://127.0.0.1:18090 \
  --mysql-url 'jdbc:mysql://127.0.0.1:MYSQL_PORT/toolbox_test?allowPublicKeyRetrieval=true&useSSL=false' \
  --postgres-url 'jdbc:postgresql://127.0.0.1:POSTGRES_PORT/toolbox_test'
```

脚本只为一次性环境编写，会创建和清理数据库对象；MySQL 跨数据库检查需要测试账号有建库权限。密码可用 `TOOLBOX_MYSQL_PASSWORD` / `TOOLBOX_POSTGRES_PASSWORD` 提供，不放进命令记录。

## 产物及剩余范围

本机特性包：`dist/features/edit-table-cells/`，含 JAR、启停脚本、LICENSE、README、SHA256SUMS。JAR SHA-256：

```text
5372b9d78ab3802370822ef2cbcf3a033bc5e4d0f4baec9a1e30185c37111832
```

此构建沿用 POM/启动页的 2.0.0 版本号，通过特性分支与上述摘要区分，不覆盖已发布的 v2.0.0 Release。未验证 Oracle、GaussDB 或 Windows 的本次编辑能力；这些厂商尚未开放可视化写回。没有新增行/删除行、多格批量保存或任意 SQL 结果编辑。

其他 Agent 的入口是根目录 [AGENTS.md](../../AGENTS.md)，开发者入口是 [CONTRIBUTING.md](../../CONTRIBUTING.md)。
