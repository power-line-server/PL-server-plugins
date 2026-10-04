#!/bin/bash
# MindustryX server launcher (Linux, JDK 27; 与 OneKeyInstall 下载的便携 JDK 版本一致)
# 与 run.bat 参数保持一致(JVM 调优/GC日志/堆转储); watchdog.sh 通过本脚本启动,
# 参数只维护这一份. exec 确保 java 替换本 shell 进程(供 watchdog 以 $! 拿到 java PID).
# SA-MaxParallelism: 脚本编译并行度, 默认 min(核数,8)。本机 24 核下默认 8 路并发编译会引入
# "依赖脚本还没编译完就编译调用方" 的竞态(症状: 偶发 Unresolved reference / Load Failed),
# 降到 2 可基本消除; 代价只是首次全量编译稍慢(一次性)。
# 强制 UTF-8 locale: JVM 的"文件名编码" sun.jnu.encoding 取自系统 locale(POSIX/C locale 下是 ASCII),
# 那样中文地图文件名会被解码成 ?????? 导致 "Failed to load custom map file"(无法用 -D 覆盖, 只能靠 locale)。
# 若系统没有 C.UTF-8, 则退回 zh_CN.UTF-8 / en_US.UTF-8。
if ! locale -a 2>/dev/null | grep -qix "C.utf8\|C.UTF-8"; then
    export LC_ALL="${LC_ALL_UTF8_FALLBACK:-zh_CN.UTF-8}"
else
    export LC_ALL=C.UTF-8
fi
export LANG="$LC_ALL"
exec java -Djava.net.preferIPv4Stack=true -DSA-MaxParallelism=2 --enable-native-access=ALL-UNNAMED --enable-final-field-mutation=ALL-UNNAMED -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps/ -Xlog:gc*:file=./dumps/gc.log:time,uptime,level,tags:filecount=5,filesize=20m -jar server.jar
