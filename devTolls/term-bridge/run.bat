@echo off
rem term-bridge 运行（Windows）: run.bat [--listen <port>]
cd /d %~dp0
java -cp out termbridge.Main %*
