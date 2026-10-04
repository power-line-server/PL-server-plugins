@echo off
REM MindustryX server launcher (Windows, JDK 27+)
REM 与 run.sh 参数保持一致(JVM 调优/GC日志/堆转储); watchdog.bat 通过本脚本启动, 参数只维护这一份.
REM 控制台切 UTF-8: 默认 936 代码页下中文日志是乱码
chcp 65001 >nul
setlocal
REM 切到脚本所在目录, 否则从别处调用时找不到 server.jar / dumps
cd /d "%~dp0"
REM 优先用 JAVA_HOME 里的 java, 没有就回退 PATH 上的 java
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
REM GC 日志/堆转储目录必须存在, 否则 JVM 起不来(路径用相对目录, 避免写死盘符)
if not exist "dumps" mkdir "dumps"
"%JAVA%" -Djava.net.preferIPv4Stack=true -DSA-MaxParallelism=2 --enable-native-access=ALL-UNNAMED --enable-final-field-mutation=ALL-UNNAMED -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps/ -Xlog:gc*:file=./dumps/gc.log:time,uptime,level,tags:filecount=5,filesize=20m -jar server.jar
