# POSIX 启动体验

服务就绪后才请求打开浏览器：macOS `open`，Linux `xdg-open`。`TOOLBOX_OPEN_BROWSER=auto` 为默认；SSH 或无 DISPLAY/WAYLAND_DISPLAY 的 Linux 只打印地址，`0` 禁止、`1` 显式尝试。缺少打开工具或工具失败不会停止服务。重复 start 不再打开浏览器。

保留运行标识校验、数据目录锁与 TERM 退出；端口冲突、数据目录占用及路径权限失败增加操作建议。Windows 暂不纳入本轮。

## 验证（2026-09-18）

- macOS 27.0 / Corretto 8u452；Linux Ubuntu 26.04.1 容器 / Temurin 8u502。
- JDK 8 `mvn -o -B -ntp clean verify`：100 项测试通过；`sh -n scripts/database-toolbox.sh` 通过。
- 两个平台运行 `python3 scripts/verify-launcher.py --jar target/database-toolbox.jar --java-home <JDK8>`。七组检查通过：参数错误、中文及空格路径、重复启动与状态、端口与数据目录冲突、陈旧 PID 不误杀、TERM 后重启验证已提交数据保留且未提交数据回滚、自动打开及 SSH/显式关闭。
- 浏览器打开命令使用捕获夹具，并在调用时请求真实服务 bootstrap 验证就绪；没有测试 Linux 桌面浏览器实际弹窗。应用进程、磁盘 H2、停止/重启都是真实运行。

验证脚本仅用于开发，依赖 Python 3；终端用户运行 JAR 不需要 Python 或 Docker。脚本创建自己的临时目录，不读取用户保存的连接。
