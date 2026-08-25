#!/usr/bin/env bash
# headless-client 运行（mindustryx 变体）: ./run.sh [--listen <控制端口>] [--jar <server.jar>]
# 默认控制端口 9090；多实例时用不同端口（9090/9091/...）
cd "$(dirname "$0")"
JAR="../../server/server.jar"
LISTEN=9090
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --listen) LISTEN="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
exec java -XX:ErrorFile=logs/hs_err_pid%p.log --add-opens java.base/sun.misc=ALL-UNNAMED -cp "out:$JAR" headlessclient.HeadlessClient --listen "$LISTEN" "${ARGS[@]}"
