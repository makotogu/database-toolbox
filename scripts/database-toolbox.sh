#!/bin/sh
# Copy this file beside database-toolbox.jar. Compatible with POSIX sh.
set -u
umask 077

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd) || exit 1
APP_DIR=${TOOLBOX_HOME:-$SCRIPT_DIR}
APP_DIR=$(CDPATH= cd -P "$APP_DIR" && pwd) || exit 1
JAR_FILE="$APP_DIR/database-toolbox.jar"
RUN_DIR="$APP_DIR/run"
LOG_DIR="$APP_DIR/logs"
PID_FILE="$RUN_DIR/database-toolbox.pid"
LOCK_DIR="$RUN_DIR/launcher.lock"
PORT=${TOOLBOX_PORT:-8080}
DATA_DIR=${TOOLBOX_DATA_DIR:-$APP_DIR/data}
START_TIMEOUT=${TOOLBOX_START_TIMEOUT:-30}
STOP_TIMEOUT=${TOOLBOX_STOP_TIMEOUT:-30}
LOCKED=false
PID=
TOKEN=
SAVED_PORT=

usage() {
    cat <<'EOF'
用法：sh database-toolbox.sh {start|stop|restart|status}

start    后台启动；默认 http://127.0.0.1:8080
stop     优雅停止本脚本启动的进程，最多等待 30 秒
restart  停止成功后重新启动
status   查看本脚本管理的进程（运行中退出码 0，未运行退出码 3）

可选环境变量：
  JAVA_HOME                 Java 安装目录；未设置时使用 PATH 中的 java
  TOOLBOX_PORT               启动端口，默认 8080
  TOOLBOX_DATA_DIR           数据目录，默认脚本旁的 data/
  TOOLBOX_HOME               JAR 所在目录，默认脚本所在目录
  TOOLBOX_START_TIMEOUT      启动等待秒数，默认 30
  TOOLBOX_STOP_TIMEOUT       停止等待秒数，默认 30

日志：logs/database-toolbox-<启动标识>.log（每次启动新建，不覆盖旧日志）
进程：run/database-toolbox.pid
EOF
}

read_pid() {
    PID= TOKEN= SAVED_PORT=
    [ -f "$PID_FILE" ] || return 1
    EXTRA=
    IFS=' ' read -r PID TOKEN SAVED_PORT EXTRA < "$PID_FILE" || return 1
    case "$PID" in ''|*[!0-9]*) return 1 ;; esac
    [ "$PID" -gt 1 ] || return 1
    case "$TOKEN" in ''|*[!0-9_]*) return 1 ;; esac
    case "$SAVED_PORT" in ''|*[!0-9]*) return 1 ;; esac
    [ -z "$EXTRA" ] || return 1
}

is_managed() {
    read_pid || return 1
    kill -0 "$PID" 2>/dev/null || return 1
    # PID reuse must not let stop/restart signal an unrelated process.
    COMMAND=$(ps -ww -p "$PID" -o args= 2>/dev/null) || return 1
    case " $COMMAND " in
        *" -Ddatabase.toolbox.launcher=$TOKEN "*) return 0 ;;
        *) return 1 ;;
    esac
}

release_lock() {
    if [ "$LOCKED" = true ]; then
        rm -f "$LOCK_DIR/owner"
        rmdir "$LOCK_DIR" 2>/dev/null || :
    fi
}

acquire_lock() {
    mkdir -p "$RUN_DIR" || exit 1
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        echo "另一个启停操作正在执行，或上次操作异常中断。" >&2
        echo "确认没有启停操作后，可删除锁目录：$LOCK_DIR" >&2
        exit 1
    fi
    LOCKED=true
    printf '%s\n' "$$" > "$LOCK_DIR/owner"
    trap release_lock 0
    trap 'exit 130' 1 2 15
}

positive_seconds() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ "$1" -ge 1 ] && [ "$1" -le 3600 ]
}

start_app() {
    if is_managed; then
        echo "已在运行，PID=${PID}，地址：http://127.0.0.1:$SAVED_PORT"
        return 0
    fi
    case "$PORT" in ''|*[!0-9]*) echo "TOOLBOX_PORT 必须是 1–65535 的整数" >&2; return 1 ;; esac
    if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
        echo "TOOLBOX_PORT 必须是 1–65535 的整数" >&2; return 1
    fi
    positive_seconds "$START_TIMEOUT" || { echo "启动等待时间必须为 1–3600 秒" >&2; return 1; }
    [ -f "$JAR_FILE" ] || { echo "找不到 JAR：$JAR_FILE" >&2; return 1; }
    if [ -n "${JAVA_HOME:-}" ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
        [ -x "$JAVA_BIN" ] || { echo "JAVA_HOME 下没有可执行的 bin/java" >&2; return 1; }
    else
        JAVA_BIN=$(command -v java) || { echo "未找到 java，请安装 Java 8 并设置 JAVA_HOME" >&2; return 1; }
    fi
    mkdir -p "$LOG_DIR" || return 1
    TOKEN="$(date +%s)_$$"
    LOG_FILE="$LOG_DIR/database-toolbox-$TOKEN.log"
    # Consistent cwd makes relative data paths independent of the invoking terminal.
    cd "$APP_DIR" || return 1
    nohup "$JAVA_BIN" "-Ddatabase.toolbox.launcher=$TOKEN" -jar "$JAR_FILE" \
        --server.address=127.0.0.1 "--server.port=$PORT" \
        "--toolbox.storage-root=$DATA_DIR" > "$LOG_FILE" 2>&1 < /dev/null &
    PID=$!
    if ! printf '%s %s %s\n' "$PID" "$TOKEN" "$PORT" > "$PID_FILE.tmp" || ! mv "$PID_FILE.tmp" "$PID_FILE"; then
        echo "无法保存进程号，正在终止刚启动的进程" >&2
        kill "$PID" 2>/dev/null || :
        return 1
    fi
    COUNT=0
    while [ "$COUNT" -lt "$START_TIMEOUT" ]; do
        if ! kill -0 "$PID" 2>/dev/null; then
            rm -f "$PID_FILE"
            echo "启动失败，日志：$LOG_FILE" >&2
            tail -n 25 "$LOG_FILE" >&2
            return 1
        fi
        if grep -F "数据库工作台已启动: http://127.0.0.1:$PORT" "$LOG_FILE" >/dev/null 2>&1 && is_managed; then
            echo "启动成功，PID=$PID"
            echo "访问：http://127.0.0.1:$PORT"
            echo "日志：$LOG_FILE"
            return 0
        fi
        sleep 1
        COUNT=$((COUNT + 1))
    done
    echo "进程仍在运行，但尚未确认启动完成；请检查日志：$LOG_FILE" >&2
    echo "可使用 status 查看进程，或 stop 停止。" >&2
    return 1
}

stop_app() {
    if ! is_managed; then
        rm -f "$PID_FILE"
        echo "未运行，或 PID 文件不属于本脚本的进程；未发送停止信号。"
        return 0
    fi
    positive_seconds "$STOP_TIMEOUT" || { echo "停止等待时间必须为 1–3600 秒" >&2; return 1; }
    echo "正在停止 PID=${PID}…"
    kill -TERM "$PID" || return 1
    COUNT=0
    while [ "$COUNT" -lt "$STOP_TIMEOUT" ]; do
        if ! is_managed; then
            rm -f "$PID_FILE"
            echo "已停止。"
            return 0
        fi
        sleep 1
        COUNT=$((COUNT + 1))
    done
    echo "进程尚未退出，保留 PID 文件。请检查日志后重试；未强制 kill -9。" >&2
    return 1
}

case "${1:-help}" in
    start|stop|restart)
        [ "$#" -eq 1 ] || { usage; exit 2; }
        acquire_lock
        case "$1" in
            start) start_app ;;
            stop) stop_app ;;
            restart) stop_app && start_app ;;
        esac
        ;;
    status)
        if is_managed; then
            echo "运行中，PID=${PID}，地址：http://127.0.0.1:$SAVED_PORT"
            echo "日志：$LOG_DIR/database-toolbox-$TOKEN.log"
        else
            echo "未找到由本脚本管理的运行进程。"
            exit 3
        fi
        ;;
    help|-h|--help) usage ;;
    *) usage; exit 2 ;;
esac
