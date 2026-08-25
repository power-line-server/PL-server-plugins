@file:Depends("coreMindustry")
@file:Depends("coreMindustry/menu")
@file:Depends("coreMindustry/util/textInput", "输入文本")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("wayzer/user/lang", "PlayerData.timezone")
package wayzer.ext

import coreLibrary.lib.parseTimeZone
import coreMindustry.MenuV2
import coreMindustry.lib.MsgType
import coreMindustry.util.textInput
import coreMindustry.lib.broadcastListeners
import mindustry.Vars
import mindustry.game.EventType
import mindustry.net.Administration
import wayzer.lib.PlayerData
import wayzer.user.timezone
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

name = "服务器日志"

// 日志类别(仿ARC-中央监控室分类)
enum class LogCategory(val displayName: String) {
    CHAT("聊天"),
    COMMAND("指令"),
    SERVER("服务器信息"),
    PING("标记坐标"),
    EVENT("事件")
}

// 日志条目: timestamp为Unix秒, category为类别, message为保留颜色代码的原始消息
data class LogEntry(val timestamp: Long, val category: LogCategory, val message: String) {
    // 复制用格式: [时间]消息 (不带类别标签,保留颜色代码字符)
    fun formatted(timeStr: String) = "[$timeStr]$message"
}

// 按查看者时区格式化时间戳
fun formatTimestamp(timestamp: Long, p: mindustry.gen.Player): String {
    val tz = PlayerData[p].timezone
    val zone = parseTimeZone(tz)
    return Instant.ofEpochSecond(timestamp).atZone(zone).format(timeFormatter)
}

// 按查看者语言获取类别显示名
fun LogCategory.trName(p: mindustry.gen.Player): String = when (this) {
    LogCategory.CHAT -> "{tr serverLog.category.chat}".with("receiver" to p).toString()
    LogCategory.COMMAND -> "{tr serverLog.category.command}".with("receiver" to p).toString()
    LogCategory.SERVER -> "{tr serverLog.category.server}".with("receiver" to p).toString()
    LogCategory.PING -> "{tr serverLog.category.ping}".with("receiver" to p).toString()
    LogCategory.EVENT -> "{tr serverLog.category.event}".with("receiver" to p).toString()
}

val MAX_LOGS = 10000
val logs = ArrayDeque<LogEntry>()
val timeFormatter = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")

fun addLog(category: LogCategory, message: String) {
    val timestamp = Instant.now().epochSecond
    synchronized(logs) {
        logs.addLast(LogEntry(timestamp, category, message))
        while (logs.size > MAX_LOGS) logs.removeFirst()
    }
}

// 捕获服务器广播消息(保留颜色代码)
val broadcastListener: (String, MsgType) -> Unit = { msg, _ ->
    addLog(LogCategory.SERVER, msg)
}

// 捕获玩家 ping 位置标记(通过 actionFilter,返回 true 允许动作)
val pingFilter = Administration.ActionFilter { action ->
    if (action.type == Administration.ActionType.pingLocation) {
        val playerName = action.player.name
        val coord = "(${action.pingX.toInt()}, ${action.pingY.toInt()})"
        val msg = if (!action.pingText.isNullOrEmpty()) {
            "{tr serverLog.event.ping}".with("name" to playerName, "coord" to coord, "text" to action.pingText).toString()
        } else {
            "{tr serverLog.event.pingNoText}".with("name" to playerName, "coord" to coord).toString()
        }
        addLog(LogCategory.PING, msg)
    }
    true
}

onEnable {
    broadcastListeners.add(broadcastListener)
    Vars.netServer.admins.actionFilters.add(pingFilter)
}
onDisable {
    broadcastListeners.remove(broadcastListener)
    Vars.netServer.admins.actionFilters.remove(pingFilter)
}

// 捕获玩家聊天/指令(保留颜色代码)
listen<EventType.PlayerChatEvent> {
    val playerName = it.player.name
    if (it.message.startsWith("/")) {
        addLog(LogCategory.COMMAND, "$playerName: ${it.message}")
    } else {
        addLog(LogCategory.CHAT, "$playerName: ${it.message}")
    }
}

// 玩家加入
listen<EventType.PlayerJoin> {
    addLog(LogCategory.EVENT, "{tr serverLog.event.join}".with("name" to it.player.name).toString())
}

// 玩家离开
listen<EventType.PlayerLeave> {
    addLog(LogCategory.EVENT, "{tr serverLog.event.leave}".with("name" to it.player.name).toString())
}

// 波次
listen<EventType.WaveEvent> {
    addLog(LogCategory.EVENT, "{tr serverLog.event.wave}".with("wave" to Vars.state.wave).toString())
}

// 游戏结束
listen<EventType.GameOverEvent> {
    addLog(LogCategory.EVENT, "{tr serverLog.event.gameover}".with().toString())
}

// 地图加载
listen<EventType.WorldLoadEvent> {
    addLog(LogCategory.EVENT, "{tr serverLog.event.mapLoaded}".with("name" to Vars.state.map.name()).toString())
}

val PER_PAGE = 100
val TRUNCATE_LEN = 50

suspend fun showLogMenu(p: mindustry.gen.Player) {
    MenuV2(p) {
        title = "{tr serverLog.menu.main.title}".with("receiver" to p).toString()
        onCancel = { close() }

        var currentPage by stateKey(1, "page")
        var detailEntry by stateKey<LogEntry?>(null, "detailEntry")
        var inFilterView by stateKey(false, "inFilterView")
        // 默认仅查看聊天
        var showChat by stateKey(true, "f_chat")
        var showCommand by stateKey(false, "f_command")
        var showServer by stateKey(false, "f_server")
        var showPing by stateKey(false, "f_ping")
        var showEvent by stateKey(false, "f_event")

        fun categoryEnabled(c: LogCategory) = when (c) {
            LogCategory.CHAT -> showChat
            LogCategory.COMMAND -> showCommand
            LogCategory.SERVER -> showServer
            LogCategory.PING -> showPing
            LogCategory.EVENT -> showEvent
        }

        fun toggleCategory(c: LogCategory) {
            when (c) {
                LogCategory.CHAT -> showChat = !showChat
                LogCategory.COMMAND -> showCommand = !showCommand
                LogCategory.SERVER -> showServer = !showServer
                LogCategory.PING -> showPing = !showPing
                LogCategory.EVENT -> showEvent = !showEvent
            }
        }

        fun selectOnly(c: LogCategory) {
            showChat = false; showCommand = false; showServer = false; showPing = false; showEvent = false
            when (c) {
                LogCategory.CHAT -> showChat = true
                LogCategory.COMMAND -> showCommand = true
                LogCategory.SERVER -> showServer = true
                LogCategory.PING -> showPing = true
                LogCategory.EVENT -> showEvent = true
            }
        }

        fun selectAll() {
            showChat = true; showCommand = true; showServer = true; showPing = true; showEvent = true
        }

        if (inFilterView) {
            // 筛选视图: 依赖自动关闭按钮
            autoCloseButton = true
            msg = "{tr serverLog.menu.filter.msg}".with("receiver" to p).toString()
            for (cat in LogCategory.entries) {
                val mark = if (categoryEnabled(cat)) "[green]√[] " else "[red]×[] "
                option("$mark${cat.trName(p)}") {
                    toggleCategory(cat)
                    currentPage = 1
                    refresh()
                }
            }
            newRow()
            option("{tr serverLog.menu.filter.all}".with("receiver" to p).toString()) {
                selectAll()
                currentPage = 1
                refresh()
            }
            option("{tr serverLog.menu.filter.chatOnly}".with("receiver" to p).toString()) {
                selectOnly(LogCategory.CHAT)
                currentPage = 1
                refresh()
            }
            newRow()
            option("{tr serverLog.menu.option.back}".with("receiver" to p).toString()) {
                inFilterView = false
                refresh()
            }
            return@MenuV2
        }

        if (detailEntry != null) {
            // 详情视图: 关闭按钮手动管理
            autoCloseButton = false
            val entry = detailEntry!!
            // 时间戳用[[转义,消息原样保留让颜色代码渲染
            val timeStr = formatTimestamp(entry.timestamp, p)
            msg = "[[${timeStr}]\n${entry.message}"

            column(2) {
                option("{tr serverLog.menu.option.back}".with("receiver" to p).toString()) { detailEntry = null; refresh() }
                option("{tr serverLog.menu.option.copy}".with("receiver" to p).toString()) {
                    textInput(p, "{tr serverLog.menu.copy.title}".with("receiver" to p).toString(), "{tr serverLog.menu.copy.msg}".with("receiver" to p).toString(), entry.formatted(timeStr), Int.MAX_VALUE)
                    refresh()
                }
            }
            newRow()
            option("{tr serverLog.menu.option.close}".with("receiver" to p).toString()) { close() }
        } else {
            // 列表视图: 依赖自动关闭按钮
            autoCloseButton = true
            val allLogs = synchronized(logs) { logs.toList() }
            val enabledCategories = LogCategory.entries.filter { categoryEnabled(it) }.toSet()
            val filteredLogs = allLogs.filter { it.category in enabledCategories }
            val totalPage = ((filteredLogs.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
            currentPage = currentPage.coerceIn(1, totalPage)

            val filterDesc = if (enabledCategories.size == LogCategory.entries.size) "{tr serverLog.menu.list.filterAll}".with("receiver" to p).toString()
                else if (enabledCategories.isEmpty()) "{tr serverLog.menu.list.filterNone}".with("receiver" to p).toString()
                else enabledCategories.joinToString("、") { it.trName(p) }
            msg = "{tr serverLog.menu.list.msg}".with("receiver" to p, "count" to filteredLogs.size, "page" to currentPage, "total" to totalPage, "filter" to filterDesc).toString()

            column(3) {
                option("{tr serverLog.menu.list.prevPage}".with("receiver" to p).toString()) {
                    if (currentPage > 1) currentPage--
                    refresh()
                }
                option("$currentPage/$totalPage") { refresh() }
                option("{tr serverLog.menu.list.nextPage}".with("receiver" to p).toString()) {
                    if (currentPage < totalPage) currentPage++
                    refresh()
                }
            }

            option("{tr serverLog.menu.list.filter}".with("receiver" to p).toString()) {
                inFilterView = true
                refresh()
            }

            val startIndex = (currentPage - 1) * PER_PAGE
            val endIndex = minOf(startIndex + PER_PAGE, filteredLogs.size)
            for (i in startIndex until endIndex) {
                val entry = filteredLogs[i]
                val displayMsg = if (entry.message.length > TRUNCATE_LEN) {
                    entry.message.substring(0, TRUNCATE_LEN) + "..."
                } else {
                    entry.message
                }
                // 时间戳用[[转义,消息原样保留让颜色代码渲染
                option("[[${formatTimestamp(entry.timestamp, p)}]${displayMsg}") {
                    detailEntry = entry
                    refresh()
                }
            }

            if (filteredLogs.isEmpty()) {
                option("{tr serverLog.menu.list.empty}".with("receiver" to p).toString()) { refresh() }
            }
        }
    }.send().await()
}

command("serverLog", "{tr command.serverLog.desc}".with()) {
    aliases = listOf("日志", "log")
    type = CommandType.Client
    body {
        val p = player ?: returnReply("{tr serverLog.reply.playerOnly}".with())
        launch { showLogMenu(p) }
    }
}
