#!/bin/bash
# Mindustry server launcher + watchdog (Linux)
# Usage: ./watchdog.sh            start server and supervise it
#        ./watchdog.sh stop       stop server and exit watchdog
# Features:
#   1) /restart command -> server exits with restart.flag, watchdog relaunches
#   2) exit command / manual shutdown -> watchdog exits, no relaunch
#   3) heartbeat file (config/data/heartbeat.txt) stale >45s -> kill -9 & relaunch (hung server)
set -u
cd "$(dirname "$0")" || exit 1
FLAG="config/scripts/data/restart.flag"
STOPFLAG="config/scripts/data/stop.flag"
# 服务器优雅关服时由 heartbeat.kts 的 onDisable 写出; 崩溃时不会有该文件
CLEANFLAG="config/scripts/data/clean-exit.flag"
HEART="config/scripts/data/heartbeat.txt"
# 输出日志文件: 优先 WATCHDOG_LOG; 否则取"本脚本 stdout 重定向到的那个文件"
# (常见用法 nohup ./watchdog.sh > /tmp/ui.log 下, 写死 watchdog.log 会让 Load Failed 检测永远失效);
# stdout 不是普通文件(前台终端/管道)时退回默认名, 检测函数发现文件不存在会自动跳过.
OUTLOG="${WATCHDOG_LOG:-}"
if [ -z "$OUTLOG" ]; then
    # 注意用 /proc/$$/fd/1(本脚本自己的 stdout): $( ) 里 /proc/self 会变成 readlink 进程, 解析出来是管道
    _out="$(readlink -f /proc/$$/fd/1 2>/dev/null || true)"
    if [ -n "$_out" ] && [ -f "$_out" ] && [ ! -t 1 ]; then
        OUTLOG="$_out"
    else
        OUTLOG="watchdog.log"
    fi
fi
# 启动命令统一由 run.sh 提供(与 run.bat 参数一致, 只维护这一份)

if [ "${1:-}" = "stop" ]; then
    # 标记"人工停止": 运行中的 watchdog 看到后不会再自动拉起(否则会被当成异常退出)
    : > "$STOPFLAG"
    # 同时补一个正常退出标记: 服务器收到 SIGTERM 后可能走优雅关服路径(写 clean-exit.flag),
    # 两者任一都能让 watchdog 正确退出
    : > "$CLEANFLAG"
    # [j]ava 写法避免 pkill 把本脚本自身的命令行也算作匹配目标
    pkill -f "[j]ava .*server\.jar" 2>/dev/null
    echo "[watchdog] stop requested, exiting"
    exit 0
fi

# 告知服务器由 watchdog 守护(WATCHDOG=1): restart 不自拉起, 由本脚本重新拉起
export WATCHDOG=1

is_heartbeat_stale() {
    # no heartbeat file yet (server still booting) -> not stale
    [ -f "$HEART" ] || return 1
    local age
    age=$(( $(date +%s) - $(stat -c %Y "$HEART" 2>/dev/null || echo "$(date +%s)") ))
    [ "$age" -gt 45 ]
}

# 检测本次启动是否出现脚本加载失败(SA 在 Linux 的类加载竞态, 偶发批量 Load Failed)
# 有日志文件才检测; 前台终端运行(无文件)时跳过
check_load_failed() {
    [ -f "$OUTLOG" ] || return 1
    tail -n 300 "$OUTLOG" 2>/dev/null | grep -aq "Load Failed"
}

FAILCNT=0
while true; do
    echo "[watchdog] starting server..."
    # 删除旧心跳文件, 避免本次启动早期被误判为卡死
    rm -f "$HEART"
    # 清理陈旧标记(例如 stop 时本脚本已不在运行), 避免它们压制后续的崩溃自动拉起
    rm -f "$STOPFLAG" "$CLEANFLAG"
    # 终端运行=控制台交互; 非终端(面板/nohup/重定向)时用管道保活, 否则 EOF 秒退
    # 交互分支: 后台作业的 stdin 会被 bash 强制改为 /dev/null, 必须显式指向控制终端(/dev/tty),
    # 否则即使在前台终端跑也读不到输入且持续 EOF. 无控制终端时用管道保活.
    if [ -t 0 ] && [ -r /dev/tty ] 2>/dev/null; then
        sh run.sh < /dev/tty &
    else
        tail -f /dev/null | sh run.sh &
    fi
    PID=$!

    RESTART=0
    # 启动宽限期: 冷启动/全量编译可能超过 45 秒才写第一个心跳, 期间不判卡死
    BOOT_AT=$(date +%s)
    # monitor: check heartbeat every 20s while java is alive
    while kill -0 "$PID" 2>/dev/null; do
        sleep 20
        if [ $(( $(date +%s) - BOOT_AT )) -lt 180 ]; then continue; fi
        if is_heartbeat_stale; then
            echo "[watchdog] heartbeat stale, server may be hung, killing..."
            kill -9 "$PID" 2>/dev/null
            wait "$PID" 2>/dev/null
            RESTART=1
            break
        fi
        if check_load_failed; then
            # 第 1 次多为编译期竞态, 原样重启即可; 连续第 2 次说明多半是陈旧 .ktc 导致的构建不兼容
            # (缓存键只含脚本自身源码, 改库文件/换 jar 后依赖方仍复用旧缓存), 清掉编译缓存再拉起
            if [ "$FAILCNT" -ge 1 ]; then
                echo "[watchdog] Load Failed 连续第 $((FAILCNT + 1)) 次, 清理脚本编译缓存后重启(需重新编译, 稍慢)..."
                rm -rf config/scripts/cache/compiled
            else
                echo "[watchdog] Load Failed(编译期竞态或陈旧缓存), 自动重启..."
            fi
            pkill -9 -f "[j]ava .*server\.jar"
            wait "$PID" 2>/dev/null
            RESTART=2
            break
        fi
    done

    if [ "$RESTART" = 0 ]; then
        wait "$PID" 2>/dev/null
        CODE=$?
        if [ -f "$FLAG" ]; then
            rm -f "$FLAG"
            FAILCNT=0
            echo "[watchdog] restart intent detected, relaunching..."
        elif [ -f "$STOPFLAG" ]; then
            rm -f "$STOPFLAG"
            echo "[watchdog] stop intent detected, watchdog exits"
            break
        elif [ -f "$CLEANFLAG" ]; then
            rm -f "$CLEANFLAG"
            echo "[watchdog] graceful shutdown, watchdog exits"
            break
        elif [ "$CODE" -ne 0 ]; then
            # 进程被信号杀死(SIGKILL=137, 如 OOM killer)或非 0 退出(JVM 崩溃/OOM) -> 异常退出, 自动拉起
            FAILCNT=$((FAILCNT + 1))
            echo "[watchdog] 服务器异常退出(exit=$CODE), 自动重启... (失败 $FAILCNT/3)"
        else
            echo "[watchdog] server stopped normally, watchdog exits"
            break
        fi
    else
        FAILCNT=$((FAILCNT + 1))
        echo "[watchdog] hung server killed, relaunching... (失败 $FAILCNT/3)"
    fi
    if [ "$FAILCNT" -ge 3 ]; then
        echo "[watchdog] 连续 3 次启动失败, 退出并请人工处理"
        break
    fi
    PID=""
    sleep 3
done