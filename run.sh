#!/bin/bash
# MindustryX server launcher (Linux, JDK 21+)
# 与 run.bat 参数保持一致(JVM 调优/GC日志/堆转储); watchdog.sh 通过本脚本启动,
# 参数只维护这一份. exec 确保 java 替换本 shell 进程(供 watchdog 以 $! 拿到 java PID).
exec java -Djava.net.preferIPv4Stack=true --enable-native-access=ALL-UNNAMED --enable-final-field-mutation=ALL-UNNAMED -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps/ -Xlog:gc*:file=./dumps/gc.log:time,uptime,level,tags:filecount=5,filesize=20m -jar server.jar