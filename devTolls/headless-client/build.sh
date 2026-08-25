#!/usr/bin/env bash
# headless-client 构建（mindustryx 变体，依赖 server.jar 改端 core）
# 用法: ./build.sh [server.jar 路径]   (默认 ../../server/server.jar)
set -e
cd "$(dirname "$0")"
JAR="${1:-../../server/server.jar}"
rm -rf out && mkdir -p out
find src -name "*.java" > out/sources.txt
javac -encoding UTF-8 -sourcepath src -cp "$JAR" -d out @out/sources.txt
echo "build ok (mindustryx, jar=$JAR): $(find out -name '*.class' | wc -l) classes"
