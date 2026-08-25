#!/usr/bin/env bash
# headless-client 构建（vanilla 变体，依赖原版 core：server-release.jar）
# 用法: ./build-vanilla.sh [原版 jar 路径]   (默认 ../../server-release.jar)
set -e
cd "$(dirname "$0")"
JAR="${1:-../../server-release.jar}"
rm -rf out-vanilla && mkdir -p out-vanilla
find src -name "*.java" > out-vanilla/sources.txt
javac -encoding UTF-8 --add-exports jdk.unsupported/sun.misc=ALL-UNNAMED -sourcepath src -cp "$JAR" -d out-vanilla @out-vanilla/sources.txt
echo "build ok (vanilla, jar=$JAR): $(find out-vanilla -name '*.class' | wc -l) classes"
