@file:Depends("wayzer")

package wayzer.ext

import cf.wayzer.scriptAgent.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

name = "服务器心跳: 供外部 watchdog 检测卡死"

onEnable {
    // 启动即写一次心跳, 缩短 watchdog 启动窗口(冷启动/编译期也能先落一个心跳)
    try { File(Config.rootDir, "data/heartbeat.txt").writeText(Instant.now().toString()) } catch (_: Exception) {}
    // 每隔 30 秒刷新心跳文件, 外部 watchdog 检测到超过 45 秒未更新即判定卡死并强杀重启
    loop(Dispatchers.Default) {
        delay(Duration.ofSeconds(30).toMillis())
        try {
            File(Config.rootDir, "data/heartbeat.txt").writeText(Instant.now().toString())
        } catch (_: Exception) {
        }
    }
}