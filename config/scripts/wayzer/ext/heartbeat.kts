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
    // 每隔 30 秒刷新心跳文件, 外部 watchdog 检测到超过 45 秒未更新即判定卡死并强杀重启。
    //
    // 注意: 写入必须经过 Dispatchers.game —— 心跳的语义是"服务器还能干活", 而不只是"JVM 还活着"。
    // 若在 Dispatchers.Default 直接写, 主线程被脚本/GC 卡死时后台线程仍会刷新心跳, watchdog 永远不动作
    // (已实测: 阻塞游戏线程 150s 期间心跳一直新鲜, 但玩家完全无法入服)。经 game 调度器后,
    // 主线程卡死 -> 这行 withContext 不会恢复 -> 心跳文件停止更新 -> watchdog 正常判定卡死。
    loop(Dispatchers.Default) {
        delay(Duration.ofSeconds(30).toMillis())
        try {
            withContext(Dispatchers.game) {
                File(Config.rootDir, "data/heartbeat.txt").writeText(Instant.now().toString())
            }
        } catch (_: Exception) {
        }
    }
}

onDisable {
    // 优雅关服(exit / stop / Core.app.exit)时 SA 会依次 disable 所有脚本, 这里留下"正常退出"标记;
    // 进程被 OOM killer/SIGKILL 杀死或 JVM 崩溃时执行不到这里, watchdog 据此区分崩溃并自动拉起
    // (Windows 版 watchdog.bat 靠轮询进程存活判断, 拿不到退出码, 只能用这个标记区分)
    runCatching { File(Config.rootDir, "data/clean-exit.flag").writeText(Instant.now().toString()) }
}