package wayzer

import arc.Events
import mindustry.game.EventType.WorldLoadBeginEvent
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.io.SaveIO
import java.time.Duration
import mindustry.maps.Map as MdtMap

name = "基础: 地图控制与管理"

val configEnableInternMaps by config.key(false, "是否开启原版内置地图")
val nextSameMode by config.key(false, "自动换图是否选择相同模式地图,否则选择生存模式")

MapRegistry.register(this, object : MapProvider() {
    override suspend fun searchMaps(search: String?): Collection<MapInfo> {
        if (search == "@internal") return maps.defaultMaps()
            .mapIndexed { i, map -> MapInfo(this, i + 1, Gamemode.survival, map) }
        maps.reload()
        val mapList = (if (configEnableInternMaps) maps.all() else maps.customMaps())
            .sortedBy { it.file.lastModified() }
            .mapIndexed { i, map -> MapInfo(this, i + 1, bestMode(map), map) }
        return when {
            search.isNullOrEmpty() -> mapList
            search == "survive" -> mapList.filter { it.mode == Gamemode.survival }
            search == "attack" -> mapList.filter { it.mode == Gamemode.attack }
            search == "pvp" -> mapList.filter { it.mode == Gamemode.pvp }
            else -> mapList.filter {
                it.name.contains(search, ignoreCase = true) || it.description.contains(search, ignoreCase = true)
            }
        }
    }

    private fun bestMode(map: mindustry.maps.Map): Gamemode {
        return when (map.file.name()[0]) {
            'A' -> Gamemode.attack
            'P' -> Gamemode.pvp
            'S' -> Gamemode.survival
            'C' -> Gamemode.sandbox
            'E' -> Gamemode.editor
            else -> Gamemode.survival
        }
    }
})

registerVarForType<MapInfo>().apply {
    registerChild("id", "在/maps中的id") { it.id.toString().padStart(3, '0') }
    registerChild("mode", "地图设定模式") { it.mode.name }
    registerChild("name", "名字") { it.name }
    registerChild("author", "作者") { it.author }
    registerChild("description", "介绍") { it.description }
}

registerVarForType<MdtMap>().apply {
    registerChild("id", "在/maps中的id(仅支持当前地图)") {
        if (it === state.map) MapManager.current.id
        else -1
    }
    registerChild("mode", "地图设定模式(仅支持当前地图)") {
        if (it === state.map) MapManager.current.mode.name
        else "UnSupport"
    }
}

onEnable {
    //hack to stop origin gameOver logic
    val control = Core.app.listeners.find { it.javaClass.simpleName == "ServerControl" }
    val field = control.javaClass.getDeclaredField("inGameOverWait")
    field.apply {
        isAccessible = true
        logger.info("inExtraRound:" + get(control))
        setBoolean(control, true)
    }
}

val waitingTime by config.key(Duration.ofSeconds(10)!!, "游戏结束换图的等待时间")
val gameOverMsgType by config.key(MsgType.InfoMessage, "游戏结束消息是显示方式")

class GameOverEvent(val winner: Team) : Event, Event.Cancellable {
    /**After cancelled, there is no broadcast and changeMap */
    override var cancelled: Boolean = false
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}
listen<EventType.GameOverEvent> { event ->
    state.gameOver = true
    Call.updateGameOver(event.winner)

    ContentHelper.logToConsole(
        if (state.rules.pvp) "&lcGame over! Team &ly${event.winner.name}&lc is victorious with &ly${Groups.player.size()}&lc players online on map &ly${state.map.name()}&lc."
        else "&lcGame over! Reached wave &ly${state.wave}&lc with &ly${Groups.player.size()}&lc players online on map &ly${state.map.name()}&lc."
    )
    val now = state.map
    launch(Dispatchers.game) {
        if (GameOverEvent(event.winner).emitAsync().cancelled) return@launch
        val map = MapRegistry.nextMapInfo(
            MapManager.current,
            if (nextSameMode) MapManager.current.mode else Gamemode.survival
        )
        if (state.map != now) return@launch//已经通过其他方式换图
        val winnerMsg: Any =
            if (state.rules.pvp) "{tr maps.broadcast.pvpWinner}".with("team" to event.winner) else ""
        val msg = """
                | {tr maps.broadcast.gameOver}
                | {winnerMsg}
                | {tr maps.broadcast.nextMap}
                | {tr maps.broadcast.nextGameStart}
            """.trimMargin().with(
            "nextMap" to map, "winnerMsg" to winnerMsg,
            "waitTime" to waitingTime.seconds
        )
        broadcast(msg, gameOverMsgType, quite = true)
        ContentHelper.logToConsole("Next Map is ${map.name}(ID:${map.id})")

        delay(waitingTime.toMillis())
        if (state.map != now) return@launch//已经通过其他方式换图
        MapManager.loadMap(map)
    }
}
//DataPatchLoad is the first event when loading map or save
listen<EventType.DataPatchLoadEvent>(insert = true) {
    MapManager.tmpVarSet?.invoke()
    MapManager.tmpVarSet = null
}
//WorldLoadBeginEvent 在 SaveVersion.readRules 之后触发 (readRules 会覆盖 state.rules 导致 @ 变量丢失)
//在此重新将地图介绍中的 @ 变量应用到 state.rules.tags, 供后续 WorldLoadEvent 等使用
listen<EventType.WorldLoadBeginEvent> {
    MapManager.tmpReapplyMapTags?.invoke()
    MapManager.tmpReapplyMapTags = null
}
command("host", "{tr command.host.desc}".with()) {
    usage = "[[mapId]"
    permission = "wayzer.maps.host"
    body {
        val map = if (arg.isEmpty()) MapRegistry.nextMapInfo(MapManager.current)
        else arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("{tr maps.reply.invalidMapId}".with())
        MapBlacklistHook.forced.add(map.id) // 管理员强制换图, 跳过黑名单检查
        MapManager.loadMap(map)
        broadcast("{tr maps.broadcast.forceChangeMap}".with("info" to map))
    }
}
command("gameover", "{tr command.gameover.desc}".with()) {
    usage = "[[winner]"
    permission = "wayzer.maps.gameover"
    body {
        val winner = arg.firstOrNull()?.let { Team.all.firstOrNull { t -> t.name == it } }
            ?: state.rules.waveTeam
        Events.fire(EventType.GameOverEvent(winner))
    }
}