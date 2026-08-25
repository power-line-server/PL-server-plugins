package coreMindustry.lib

import arc.util.Log
import coreLibrary.lib.*
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Iconc
import mindustry.gen.Player

object ContentHelper {
    fun logToConsole(text: String) {
        // 直接传给 Log.info, 由 console.kts 的 Log.formatter 统一处理所有颜色码:
        // - mindustryColorToArc 覆盖所有 Mindustry 颜色 ([red], [sky], [light_yellow], [#hex], [] 重置, [[ 转义)
        // - 终端: &xx → ANSI 码显示颜色
        // - 日志文件/WebUI: 保留原始颜色码, 前端解析渲染
        Log.info(text)
    }

    fun logToConsole(text: PlaceHoldString) {
        val parsed = text.with("receiver" to CommandContext.ConsoleReceiver).toString()
        logToConsole(parsed)
    }

    fun mindustryColorHandler(color: ColorApi.Color): String {
        if (color is ConsoleColor) {
            return when (color) {
                ConsoleColor.LIGHT_YELLOW -> "[gold]"
                ConsoleColor.LIGHT_PURPLE -> "[magenta]"
                ConsoleColor.LIGHT_RED -> "[scarlet]"
                ConsoleColor.LIGHT_CYAN -> "[cyan]"
                ConsoleColor.LIGHT_GREEN -> "[acid]"
                else -> "[${color.name.lowercase()}]"
            }
        }
        return ""
    }
}

enum class MsgType { Message, InfoMessage, InfoToast, WarningToast, Announce }

/** 服务器广播消息监听器,用于日志插件捕获服务器消息 */
val broadcastListeners = mutableListOf<(String, MsgType) -> Unit>()

fun broadcast(
    text: PlaceHoldString,
    type: MsgType = MsgType.Message,
    time: Float = 10f,
    quite: Boolean = false,
    players: Iterable<Player> = Groups.player
) {
    if (!quite) ContentHelper.logToConsole(text)
    try {
        val parsed = text.with("receiver" to CommandContext.ConsoleReceiver).toString()
        broadcastListeners.forEach { it(parsed, type) }
    } catch (_: Throwable) { }
    MindustryDispatcher.runInMain {
        players.forEach {
            if (it.con != null)
                it.sendMessage(text, type, time)
        }
    }
}

fun Player?.sendMessage(text: PlaceHoldString, type: MsgType = MsgType.Message, time: Float = 10f) {
    if (this == null) ContentHelper.logToConsole(text)
    else {
        if (con == null) return
        MindustryDispatcher.runInMain {
            val msg = text.toPlayer(this)
            when (type) {
                MsgType.Message -> Call.sendMessage(this.con, msg, null, null)
                MsgType.InfoMessage -> Call.infoMessage(this.con, msg)
                MsgType.InfoToast -> Call.infoToast(this.con, msg, time)
                MsgType.WarningToast -> Call.warningToast(this.con, Iconc.warning.code, msg)
                MsgType.Announce -> Call.announce(this.con, msg)
            }
        }
    }
}

fun PlaceHoldString.toPlayer(player: Player): String = ColorApi.handle(
    with("player" to player, "receiver" to player).toString(),
    ContentHelper::mindustryColorHandler
)

@Deprecated("use PlaceHoldString", ReplaceWith("sendMessage(text.with(), type, time)", "coreLibrary.lib.with"))
fun Player?.sendMessage(text: String, type: MsgType = MsgType.Message, time: Float = 10f) =
    sendMessage(text.with(), type, time)