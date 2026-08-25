@echo off
rem headless-client 一键构建+重启（用法: build+restart.bat [9090]，多实例传不同端口）
rem 说明: 请在 cmd 窗口运行; javaw 无控制台窗口, 日志走 events.log + 控制端口
cd /d %~dp0
set PORT=%1
if "%PORT%"=="" set PORT=9090
if exist headless-client.pid (
    for /f %%p in (headless-client.pid) do taskkill /f /pid %%p >nul 2>&1
    ping -n 2 127.0.0.1 >nul
)
call build.bat
start "" javaw -cp "out;..\..\server\server.jar" headlessclient.HeadlessClient --listen %PORT%
echo headless-client started on 127.0.0.1:%PORT% (javaw, no console)
