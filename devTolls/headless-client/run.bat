@echo off
rem headless-client 运行（mindustryx 变体）: run.bat [--listen <控制端口>]
cd /d %~dp0
set JAR=..\..\server\server.jar
java -XX:ErrorFile=logs\hs_err_pid%%p.log --add-opens java.base/sun.misc=ALL-UNNAMED -cp "out;%JAR%" headlessclient.HeadlessClient %*
