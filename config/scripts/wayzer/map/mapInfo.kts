package wayzer.map

import cf.wayzer.placehold.VarString

@Savable(false)
val customModeIntroduce = mutableListOf<VarString>()
customLoad(::customModeIntroduce) { customModeIntroduce.clear(); customModeIntroduce.addAll(it) }

fun addModeIntroduce(mode: VarString, introduce: VarString) {
    val modeKey = mode.toString()
    if (customModeIntroduce.any { it.toString().contains("===[gold]$modeKey[]===") }) return
    customModeIntroduce += "[magenta]===[gold]{mode}[]===[white]\n{introduce}".with("mode" to mode, "introduce" to introduce)
}
export(::addModeIntroduce)
listen<EventType.ResetEvent> { customModeIntroduce.clear() }

registerVar("scoreboard.ext.customMode", "自定义模式Tip", DynamicVar {
    if (customModeIntroduce.isEmpty()) return@DynamicVar null
    "{tr mapInfo.var.customMode}".with()
})


fun Player.showDetail() {
    val pages = (listOf(buildString {
        appendLine("[white]${state.map.name()}")
        appendLine()
        appendLine("{tr mapInfo.label.author}".with("receiver" to this@showDetail, "author" to state.map.author()).toString())
        appendLine("[white]${state.map.description()}")
    }) + customModeIntroduce.map { it.toPlayer(this@showDetail) }).autoPage()
    for (page in pages.size downTo 1) {
        sendMessage(
            "{tr mapInfo.detail.header}"
                .with("body" to pages[page - 1], "page" to page, "total" to pages.size), type = MsgType.InfoMessage
        )
    }
}

fun Player.showInfo() {
    if (con == null) return
    val self = this
    val desc = state.map.description().autoWrapLine()
    val msg = buildString {
        appendLine("[white]${state.map.name()}")
        appendLine()
        appendLine("{tr mapInfo.label.author}".with("receiver" to self, "author" to state.map.author()).toString())
        appendLine("[white]$desc")
        if (customModeIntroduce.isNotEmpty()) {
            appendLine()
            appendLine("{tr mapInfo.info.specialMode}".with("receiver" to self, "count" to customModeIntroduce.size).toString())
            appendLine("{tr mapInfo.info.viewMapInfo}".with("receiver" to self).toString())
        }
    }
    Call.label(con, msg, 2 * 60f, core()?.x ?: 0f, core()?.y ?: 0f)
}

listen<EventType.WorldLoadEvent> {
    launch(Dispatchers.gamePost) {
        Groups.player.forEach {
            it.showInfo()
        }
    }
}

listen<EventType.PlayerJoin> { e ->
    Core.app.post { e.player.showInfo() }
}

command("mapInfo", "{tr command.mapInfo.desc}".with()) {
    type = CommandType.Client
    body {
        player!!.showDetail()
    }
}

//region util
fun Iterable<String>.autoPage(lines: Int = 10): List<String> {
    val out = mutableListOf<String>()
    var acc = ""
    fun add() {
        if (acc.isBlank()) return
        out.add(acc)
        acc = ""
    }
    for (text in this) {
        acc += text
        if (acc.lineSequence().count() > lines) add()
    }
    add()
    return out
}

fun String.autoWrapLine(limit: Int = 25): String {
    var lastChar = ' '
    var i = 0
    return map {
        if (i > limit && it in charArrayOf(' ', '，', ',', '.', '。', '!', '！'))
            if (it != '.' || lastChar.code !in '0'.code..'9'.code) {
                i = 0
                lastChar = it
                return@map '\n'
            }
        lastChar = it
        i++
        return@map it
    }.joinToString("")
}
//endregion