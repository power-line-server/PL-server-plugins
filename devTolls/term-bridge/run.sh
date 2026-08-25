#!/usr/bin/env bash
# term-bridge 运行（Linux/macOS）: ./run.sh [--listen <port>]
cd "$(dirname "$0")"
exec java -cp out termbridge.Main "$@"
