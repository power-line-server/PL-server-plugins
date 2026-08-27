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
HEART="config/scripts/data/heartbeat.txt"
# 输出日志文件(外部重定向时存在; 前台终端运行无此文件则跳过 Load Failed 检测)
OUTLOG="${WATCHDOG_LOG:-watchdog.log}"
# 启动命令统一由 run.sh 提供(与 run.bat 参数一致, 只维护这一份)

if [ "${1:-}" = "stop" ]; then
    pkill -f "java .*server.jar" 2>/dev/null
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
            echo "[watchdog] Load Failed(SA 类加载竞态), 自动重启..."
            pkill -9 -f "java .*server.jar"
            wait "$PID" 2>/dev/null
            RESTART=2
            break
        fi
    done

    if [ "$RESTART" = 0 ]; then
        wait "$PID" 2>/dev/null
        if [ -f "$FLAG" ]; then
            rm -f "$FLAG"
            FAILCNT=0
            echo "[watchdog] restart intent detected, relaunching..."
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