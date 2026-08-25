@file:Suppress("MemberVisibilityCanBePrivate")

package wayzer

import arc.Events
import arc.files.Fi
import arc.struct.StringMap
import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.emitAsync
import cf.wayzer.scriptAgent.thisContextScript
import coreLibrary.lib.config
import coreLibrary.lib.with
import coreMindustry.lib.broadcast
import coreMindustry.lib.game
import coreMindustry.lib.nextTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.core.GameState
import mindustry.game.Rules
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.io.MapIO
import mindustry.io.JsonIO
import mindustry.io.SaveIO
import mindustry.maps.Map

typealias RuleModifier = Rules.() -> Unit

class MapChangeEvent(
    val info: MapInfo,
    val map: Map,
    val rules: Rules = map.applyRules(info.mode),
) :
    Event, Event.Cancellable {
    /** Should call other load*/
    override var cancelled: Boolean = false
    @Deprecated("modify rules directly", ReplaceWith("rules.block()"))
    fun modifyRule(block: RuleModifier) {
        rules.block()
    }

    companion object : Event.Handler()
}

/** 换图完成事件: 在 loadMapSync 末尾所有玩家实体恢复(Groups.player add)后触发, 供依赖方在"玩家已全部恢复"时执行后续逻辑(如匿名分配) */
class MapLoadCompleteEvent(val info: MapInfo) : Event {
    companion object : Event.Handler()
}

object MapManager {
    var current: MapInfo =
        MapInfo(MapRegistry.SaveProvider, Vars.state.rules.idInTag, Vars.state.rules.mode(), Vars.state.map)
        private set
    internal var tmpVarSet: (() -> Unit)? = null
    // 修复: SaveVersion.readRules 在 DataPatchLoadEvent 后执行, 覆盖 state.rules 导致 @ 变量丢失.
    // 此 lambda 在 WorldLoadBeginEvent (readRules 之后, WorldLoadEvent 之前) 时执行, 重新应用 @ 变量
    internal var tmpReapplyMapTags: (() -> Unit)? = null

    @Deprecated("old", level = DeprecationLevel.HIDDEN)
    fun loadMap(info: MapInfo? = null, isSave: Boolean = false) {
        loadMap(info)
    }

    fun loadMap(info: MapInfo? = null) {
        thisContextScript().launch(Dispatchers.game) {
            loadMapSync(info)
        }
    }

    suspend fun loadMapSync(info: MapInfo? = null): Boolean {
        @Suppress("NAME_SHADOWING") var info: MapInfo? = info
        try {
            info = info ?: MapRegistry.nextMapInfo()
            val map = info.loadMap().run {
                Map(file, width, height, StringMap(tags), custom, version, build) //copy tags
            }
            loadMapSync(info, map)
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            broadcast(
                "{tr mapsManager.broadcast.loadMapFailed}".with(
                    "info" to (info ?: ""),
                    "reason" to (e.message ?: "")
                )
            )
            thisContextScript().launch(Dispatchers.game) {
                delay(1000)
                loadMapSync()
            }
            return false
        }
    }

    suspend fun loadMapSync(info: MapInfo, map: Map, savedTags: StringMap? = null) {
        val event = MapChangeEvent(info, map).apply {
            rules.idInTag = info.id
            rules.applyMapTags(map)
        }
        if (event.emitAsync().cancelled) return

        thisContextScript().logger.info("loadMap $info")
        if (!Vars.net.server()) Vars.netServer.openServer()
        val players = Groups.player.toList()
        Call.worldDataBegin()
        Vars.logic.reset()
        //Hack: Some old tasks have posted, so we let they run.
        Vars.world.resize(0, 0)
        nextTick()

        current = info
        try {
            tmpVarSet = block@{
                if (map == MapRegistry.GeneratorMap) {
                    Vars.state.rules.idInTag = info.id
                    return@block
                }
                Vars.state.map = map
                Vars.state.rules = event.rules
            }
            // 在 WorldLoadBeginEvent (readRules 之后) 恢复 rules.tags: 有存档快照则还原(JsonIO 序列化 Rules 不含 tags), 否则重新解析地图描述中的 @ 变量
            tmpReapplyMapTags = if (map != MapRegistry.GeneratorMap) {
                {
                    if (savedTags != null) Vars.state.rules.tags.putAll(savedTags)
                    else Vars.state.rules.applyMapTags(map)
                }
            } else null
            info.provider.loadMap(info) // EventType.ResetEvent
            // EventType.WorldLoadBeginEvent : do set state.rules (tmpReapplyMapTags 在此执行)
            // EventType.WorldLoadEndEvent
            // EventType.WorldLoadEvent
            // Not generator: EventType.SaveLoadEvent
        } catch (e: Throwable) {
            tmpVarSet = null
            tmpReapplyMapTags = null
            players.forEach { it.add() }
            throw e
        }


        if (info.provider == MapRegistry.SaveProvider) {
            Vars.state.set(GameState.State.playing)
        } else {
            Vars.logic.play() // EventType.PlayEvent
        }

        players.forEach {
            if (it.con == null) return@forEach
            it.admin.let { was ->
                it.reset()
                it.admin = was
            }
            it.team(Vars.netServer.assignTeam(it, players))
            Vars.netServer.sendWorldData(it)
        }
        players.forEach { it.add() }
        // 所有玩家实体已恢复(Groups.player), 通知依赖方(如 pvpAnonymous 的换图匿名分配)
        Events.fire(MapLoadCompleteEvent(info))
    }

    fun loadSave(file: Fi) {
        val map = MapIO.createMap(file, true)
        thisContextScript().launch(Dispatchers.game) {
            // 恢复地图 id: 存档 extraTags 的 mapId 键 → rules.tags.id(JsonIO 序列化 Rules 不含 tags, 恒 -1) → 按地图名匹配注册表
            val savedId = map.tags.getInt("mapId", -1)
            val id = if (savedId >= 0) savedId
            else map.rules().idInTag.takeIf { it >= 0 }
                ?: run {
                    // 存档 meta 的 "mapname" 是存档时 state.map.name()(纯地图名, 无 [存档N] 前缀), 与注册表 MapInfo.name 同源
                    val savedName = map.tags.get("mapname") ?: map.name()
                    MapRegistry.searchMaps().firstNotNullOfOrNull { m -> m.name.takeIf { it == savedName }?.let { m.id } }
                } ?: -1
            // 恢复 rules.tags(@ 变量等): 存档 extraTags 的 mapTags 键保存 state.rules.tags 快照
            // 注意: 必须用 read(Class, ...) 而非 read(base, ...), 后者走 readFields 对 StringMap 无效
            // runCatching: 损坏/非 Json 的 mapTags 值解析失败时回退到描述解析
            val savedTags = map.tags.get("mapTags")?.let { tag ->
                runCatching { JsonIO.read(StringMap::class.java, tag) }.getOrNull()
            }
            loadMapSync(MapInfo(MapRegistry.SaveProvider, id, map.rules().mode(), map), map, savedTags)
        }
    }

    fun getSlot(id: Int): Fi? {
        val file = SaveIO.fileFor(id)
        if (!SaveIO.isSaveValid(file)) return null
        val voteFile = SaveIO.fileFor(configTempSaveSlot)
        if (voteFile.exists()) voteFile.delete()
        file.copyTo(voteFile)
        return voteFile
    }

    //private
    private val configTempSaveSlot by thisContextScript().config.key(111, "临时缓存的存档格位")

    /** Use for identity Save */
    private var Rules.idInTag: Int
        get() = tags.getInt("id", -1)
        set(value) {
            tags.put("id", value.toString())
        }
}

/** 从地图介绍中解析 @ 变量并应用到 rules.tags (如 [@noSkills], [@pvpProtect=0]) */
private fun Rules.applyMapTags(map: Map) {
    Regex("\\[(@[a-zA-Z0-9]+)(=[^=\\]]+)?]").findAll(map.description()).forEach {
        val value = it.groupValues[2].takeIf(String::isNotEmpty) ?: "true"
        tags.put(it.groupValues[1], value.removePrefix("="))
    }
}

/** 黑名单放行标记: host 命令设置, MapChangeEvent 监听器检查并移除 */
object MapBlacklistHook {
    val forced = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
}