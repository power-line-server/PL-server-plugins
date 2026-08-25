#!/usr/bin/env bash
# headless-client 运行（vanilla 变体）: ./run-vanilla.sh [--listen <控制端口>] [--jar <原版jar>]
cd "$(dirname "$0")"
JAR="../../server-release.jar"
LISTEN=9092
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --listen) LISTEN="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
exec java --add-exports jdk.unsupported/sun.misc=ALL-UNNAMED -cp "out-vanilla:$JAR" headlessclient.HeadlessClient --listen "$LISTEN" "${ARGS[@]}"
