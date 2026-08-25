@echo off
rem term-bridge 构建脚本（Windows）
cd /d %~dp0
if not exist out mkdir out
dir /s /b src\*.java > out\sources.txt
javac -encoding UTF-8 -d out @out\sources.txt
echo build ok
