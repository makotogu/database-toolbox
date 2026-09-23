# GaussDB 单元格编辑元数据兼容修复

日期：2026-09-18。分支：`codex/gauss-cell-edit-compat`，基于 `codex/table-filters` 的功能预览。公开 v2.0.0 Release 未改动。

2026-09-23 合并前复核：修复同时存在 `attgenerated` / `adgencol` 时空标记掩盖生成标记的问题，任一标记为生成即只读。新增 H2 集成用例先失败再通过，`CellEditingTest` 21 项通过。JDK 8 全量验证共 111 项、0 失败，其中需外部 PostgreSQL 环境的 1 项跳过。以下 2026-09-18 产物与厂商验证记录保留其历史范围，不代表新提交已在 GaussDB 内网完成验收。

## 触发和证据

用户报告 GaussDB 表预览提示“只读：数据库操作失败（SQLState 42703）”，无法双击编辑。环境为 503 SPC2000C，厂商驱动 `GaussDBV5-503.1.0.spc2000c_26.7.13.jar`，驱动类 `org.postgresql.Driver`。同一驱动在 DBeaver 的 PostgreSQL 连接中可编辑。JAR 位于内网，用户同意先做兼容修复，不提供驱动。

修复前，测试隐藏 `getColumns()` 的 `IS_GENERATEDCOLUMN` 字段并让按名称读取抛出 42703，重现相同整表只读；驱动产品名为 GaussDB 时，另有硬编码白名单拒绝。以上是已复现的代码路径，不能断言内网驱动一定在相同位置报错。

## DBeaver 参考

- [PostgreSchema.TableCache](https://github.com/dbeaver/dbeaver/blob/devel/plugins/org.jkiss.dbeaver.ext.postgresql/src/org/jkiss/dbeaver/ext/postgresql/model/PostgreSchema.java) 直接从 `pg_attribute`、`pg_attrdef` 等系统目录加载列，从 `pg_constraint` 加载约束，不依赖 JDBC `IS_GENERATEDCOLUMN` 完成这条路径。
- [PostgreAttribute](https://github.com/dbeaver/dbeaver/blob/devel/plugins/org.jkiss.dbeaver.ext.postgresql/src/org/jkiss/dbeaver/ext/postgresql/model/PostgreAttribute.java) 结合生成标记、自增标记与默认值判断生成属性；[JDBCUtils.safeGetString](https://github.com/dbeaver/dbeaver/blob/devel/plugins/org.jkiss.dbeaver.model.jdbc/src/org/jkiss/dbeaver/model/impl/jdbc/JDBCUtils.java) 对读取失败返回 null。这能解释两种工具对旧驱动元数据差异的容忍程度不同；未检查用户所用 DBeaver 的精确版本。
- [openGauss PG_ATTRDEF 文档](https://docs.opengauss.org/en/docs/6.0.0/docs/DatabaseReference/pg_attrdef.html) 描述 `adgencol` 的生成标记。该资料用于选择可选字段，不能证明 GaussDB 503 拥有相同目录形状。

本项目借鉴目录查询与可选字段探测方法，独立实现。没有复制 DBeaver 源码、引入其依赖，或开启虚拟主键/无主键写回。

## 修复行为

1. JDBC 可选标记按实际结果标签探测，以索引读取；缺失、NULL、空串及非 YES/NO 值均为未知。
2. PostgreSQL/GaussDB 在属性未知时，按 schema/table 绑定参数查询同一会话的系统目录。读取 `a.*` / `d.*`，避免拼接不存在的版本专属字段。支持目录提供 `attgenerated` 或 `adgencol`；两者都无时，仅对明确没有默认表达式的列确认非生成。默认值属性未知仍只读。
3. 缺少自增信息时不猜测；若 `attidentity` 为空且无默认值可确认非自增，有默认值则仍需 JDBC 明确信息。已识别生成列、自增列、主键继续只读。
4. 接受实际产品名 PostgreSQL 或 GaussDB 的兼容路径，不要求把厂商驱动替换为内置 PostgreSQL 驱动。保留事务/保存点、主键、类型、预览快照、锁定原值、恰好一行与写后核验。
5. 手动事务内，元数据检查有自己的保存点；失败撤销检查，保留此前工作。写入前重新检查目录和表结构。失败提示表类型/主键/字段/生成列兼容查询阶段及脱敏 SQLState，不输出原始数据库异常。

## 验证记录

修复前两项测试失败：旧驱动夹具显示 42703、GaussDB 产品名被拒绝。修复后的 H2 2.2.224 集成夹具覆盖成功写入、主键/生成列/自增列只读、默认值未知、缺失自增标记、其他驱动只读、手动事务失败恢复、保存前重新核验。

真实 PostgreSQL 测试使用一次性 PostgreSQL 16.15（Debian 16.15-1.pgdg13+2）与 JDBC 42.7.13：隐藏 JDBC 生成列标记，执行真实系统目录查询与写回；故意执行无效目录查询后，保存点回滚成功，先前事务修改保留。测试入口为 `PostgresCellMetadataTest`，环境变量与命令见开发指南。

运行时为 macOS / Corretto 1.8.0_452，实际完成：

| 检查 | 结果 |
| --- | --- |
| JDK 8 `mvn -o clean verify`（启用上述 PostgreSQL 环境变量） | 110 项测试通过，0 失败、0 跳过 |
| `node --check src/main/resources/static/workbench/app.js`、`sh -n scripts/database-toolbox.sh`、`git diff --check` | 通过 |
| 最终 JAR + `scripts/smoke-cell-edit.py`，PostgreSQL 16.15 / JDBC 42.7.13 | 10 项通过：复合主键、绑定值、幂等、精度、NULL、冲突、保存点、提交及清理 |
| 最终 JAR + `scripts/smoke-cell-ui.cjs`，Chrome 152 / Playwright 1.62.1 / H2 2.2.224 | 6 组通过：选中/双击/键盘编辑、转义显示、保存刷新、只读主键/视图、NULL/空串、冲突保留输入、手动提交/回滚、取消；无浏览器脚本错误。已查看编辑弹窗截图 |
| `scripts/verify-release.py --jar dist/features/gauss-cell-edit-compat/database-toolbox.jar --java <JDK8>/bin/java --port 18094` | 9 项通过：空目录启动、静态资源、访问保护、内置驱动、查询、目录锁、重启持久化与草稿恢复 |

独立产物为 `dist/features/gauss-cell-edit-compat/database-toolbox.jar`，SHA-256：`15409bdf0c3ec6b68e69b93e734cd58347fd67209e5ff3ea459324056aeef0cd`。先前 `workbench-experience` 预览包摘要未变化。

## 内网验收

用独立测试连接和有主键的测试表检查：普通字段双击保存；生成列/自增列只读；手动事务修改后回滚与提交。继续使用匹配版本的厂商 JAR。若仍只读，提供新的阶段提示和 SQLState 即可，无需提供连接地址、账号、SQL 或数据。

GaussDB 503 SPC2000C、其他兼容模式及分布式场景未实测。不保证所有 GaussDB 版本的系统目录、行锁或事务行为；本次交付是可在内网验证的兼容修复包。
