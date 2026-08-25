package wayzer.cmds

import arc.Events
import cf.wayzer.scriptAgent.Config
import mindustry.core.GameState
import mindustry.game.Team
import mindustry.net.Packets
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlin.system.exitProcess

var msg: String? = null
var doRestart: () -> Unit = {}

/** 复用当前 JVM 参数自拉起新实例(run.bat 直启、无 watchdog 守护的场景, 退出后自动重启) */
fun spawnSelfRestart() {
    try {
        val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java" +
            (if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
        // inputArguments = JVM 启动参数(-D/-X/--enable-* 等), 不含 -jar 与主类
        val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
        val cmd = listOf(javaBin) + jvmArgs + listOf("-jar", "server.jar")
        arc.util.Log.info("[restart] 自拉起新实例: ${cmd.joinToString(" ")}")
        ProcessBuilder(cmd)
            .directory(File(System.getProperty("user.dir")))
            .inheritIO()
            .start()
    } catch (e: Throwable) {
        arc.util.Log.err("[restart] 自拉起失败", e)
    }
}
listen<EventType.GameOverEvent> {
    val msg = msg ?: return@listen
    broadcast("{tr restart.broadcast.restartingSoon}".with("msg" to msg), quite = true)
}
listen<EventType.PlayerJoin> {
    val msg = msg ?: return@listen
    launch(Dispatchers.gamePost) {
        it.player.sendMessage("{tr restart.reply.willRestartAfterGame}".with("msg" to msg))
    }
}
//Don't using ResetEvent, as Groups.player is cleared
//listen<EventType.ResetEvent>
listen<EventType.StateChangeEvent> {
    if (it.to == GameState.State.menu)
        doRestart()
}

fun scheduleRestart(reason: String, beforeExit: () -> Unit = {}) {
    msg = reason
    broadcast("{tr restart.broadcast.willRestartAfterGame}".with("msg" to reason))
    doRestart = {
        broadcast("{tr restart.broadcast.restarting}".with("msg" to reason), MsgType.InfoMessage)
        Thread.sleep(1000L)
        Groups.player.forEach {
            it.kick(Packets.KickReason.serverRestarting)
        }
        Thread.sleep(100L)
        beforeExit()
        // 写重启标记: 外部 watchdog 检测到后重新拉起进程(exit 关服会删除该标记)
        runCatching { File(Config.rootDir, "data/restart.flag").writeText("restart ${Instant.now()}") }
        // 无 watchdog 守护(WATCHDOG=1)时自拉起新实例, 保证 run.bat 直启也能真重启
        if (System.getenv("WATCHDOG") != "1") runCatching { spawnSelfRestart() }
        exitProcess(2)
    }
    if (state.isMenu)
        Core.app.post(doRestart)
}

command("restart", "{tr command.restart.desc}".with()) {
    usage = "[[--now] <msg>"
    permission = dotId
    body {
        val now = checkArg("--now")
        val msg = arg.joinToString(" ")
        scheduleRestart(msg)
        if (now) {
            Events.fire(EventType.GameOverEvent(Team.derelict))
            doRestart()
        }
    }
}