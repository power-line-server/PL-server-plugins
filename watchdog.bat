@echo off
REM 控制台切 UTF-8: 否则中文日志在默认 936 代码页下是乱码
chcp 65001 >nul
REM Mindustry server launcher + watchdog (Windows)
REM Usage: run this script instead of run.bat to start the server.
REM Features:
REM   1) /restart command -> server exits, watchdog relaunches it
REM   2) exit command / manual shutdown -> watchdog exits, no relaunch
REM   3) heartbeat file (config\scripts\data\heartbeat.txt) stale for >45s -> kill & relaunch (hung server)
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "FLAG=config\scripts\data\restart.flag"
set "STOPFLAG=config\scripts\data\stop.flag"
REM 服务器优雅关服时由 heartbeat.kts 的 onDisable 写出; 崩溃/OOM 被杀时不会有该文件
set "CLEANFLAG=config\scripts\data\clean-exit.flag"
set /a CRASHCNT=0

:service
echo [watchdog] starting server...
REM 告知服务器由 watchdog 守护(WATCHDOG=1): restart 不自拉起, 由本脚本重新拉起
set "WATCHDOG=1"
REM 删除旧心跳文件, 避免本次启动早期被误判为卡死
del "config\scripts\data\heartbeat.txt" >nul 2>&1
REM 清理陈旧标记(例如 stop 时本脚本已不在运行), 避免它们压制后续的崩溃自动拉起
del "%STOPFLAG%" >nul 2>&1
del "%CLEANFLAG%" >nul 2>&1
start "MindustryServer" /min cmd /c "call run.bat"

:watch
ping -n 11 127.0.0.1 >nul
powershell -NoProfile -Command "$j = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*server.jar*' } | Select-Object -First 1; if (-not $j) { exit 2 }; if (Test-Path 'config\scripts\data\heartbeat.txt') { $age = (Get-Date) - (Get-Item 'config\scripts\data\heartbeat.txt').LastWriteTime; if ($age.TotalSeconds -gt 45) { exit 1 } }; exit 0"
if errorlevel 2 goto stopped
if errorlevel 1 goto hung
goto watch

:stopped
echo [watchdog] server exited, checking restart intent...
if exist "%FLAG%" (
    del "%FLAG%"
    echo [watchdog] restart intent detected, relaunching...
    set /a CRASHCNT=0
    ping -n 4 127.0.0.1 >nul
    goto service
)
if exist "%STOPFLAG%" (
    del "%STOPFLAG%"
    echo [watchdog] stop intent detected, watchdog exits
    exit /b 0
)
if exist "%CLEANFLAG%" (
    del "%CLEANFLAG%"
    echo [watchdog] graceful shutdown, watchdog exits
    exit /b 0
)
REM 没有任何标记 = java.exe 异常消失(OOM/崩溃/taskkill), 自动拉起
set /a CRASHCNT+=1
echo [watchdog] server crashed without any marker, relaunching... (!CRASHCNT!/3)
if !CRASHCNT! GEQ 3 (
    echo [watchdog] 连续 3 次异常退出, 退出并请人工处理
    exit /b 1
)
ping -n 4 127.0.0.1 >nul
goto service
exit /b 0

:hung
echo [watchdog] heartbeat stale, server may be hung, killing...
taskkill /f /t /fi "WINDOWTITLE eq MindustryServer*" >nul 2>&1
ping -n 4 127.0.0.1 >nul
goto service