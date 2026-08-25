#!/usr/bin/env bash
# term-bridge 构建脚本（Linux/macOS）
set -e
cd "$(dirname "$0")"
mkdir -p out
find src -name "*.java" > out/sources.txt
javac -encoding UTF-8 -d out @out/sources.txt
echo "build ok: $(find out -name '*.class' | wc -l) classes"
