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
# JVM options mirror run.bat; remove --enable-final-field-mutation on JDK < 24
JAVA_OPTS="-Djava.net.preferIPv4Stack=true --enable-native-access=ALL-UNNAMED --enable-final-field-mutation=ALL-UNNAMED -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps/ -Xlog:gc*:file=dumps/gc.log:time,uptime,level,tags:filecount=5,filesize=20m -jar server.jar"

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

while true; do
    echo "[watchdog] starting server..."
    # 删除旧心跳文件, 避免本次启动早期被误判为卡死
    rm -f "$HEART"
    # tail 管道保持 stdin 打开: console 的 EOF 分支会在 stdin 关闭时退出服务器
    tail -f /dev/null | java $JAVA_OPTS &
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
    done

    if [ "$RESTART" = 0 ]; then
        wait "$PID" 2>/dev/null
        if [ -f "$FLAG" ]; then
            rm -f "$FLAG"
            echo "[watchdog] restart intent detected, relaunching..."
        else
            echo "[watchdog] server stopped normally, watchdog exits"
            break
        fi
    else
        echo "[watchdog] hung server killed, relaunching..."
    fi
    PID=""
    sleep 3
done