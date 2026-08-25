@echo off
rem headless-client 构建（mindustryx 变体）
cd /d %~dp0
set JAR=..\..\server\server.jar
if not exist out mkdir out
dir /s /b src\*.java > out\sources.txt
javac -encoding UTF-8 -cp "%JAR%" -d out @out\sources.txt
echo build ok (mindustryx)
