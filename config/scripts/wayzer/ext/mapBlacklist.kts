@file:Depends("wayzer/maps", "地图管理")

package wayzer.ext

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.listenTo
import coreLibrary.lib.with
import coreMindustry.lib.broadcast
import coreMindustry.lib.game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wayzer.GetNextMapEvent
import wayzer.MapBlacklistHook
import wayzer.MapChangeEvent
import wayzer.MapInfo
import wayzer.MapRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

name = "地图黑名单"

val blacklistFile = File(Config.rootDir, "data/mapBlacklist.txt")
val blacklist: MutableSet<Int> = ConcurrentHashMap.newKeySet()

@Volatile
var cachedMaps: List<MapInfo> = emptyList()

fun isBlacklisted(id: Int): Boolean = blacklist.contains(id)

fun loadBlacklist() {
    blacklist.clear()
    if (!blacklistFile.exists()) {
        blacklistFile.parentFile?.mkdirs()
        blacklistFile.writeText(
            """
            # 地图黑名单
            # 每行一个地图ID(以#开头的行将被忽略)
            # 黑名单中的地图无法通过投票换图,管理员可使用host强制换图
            """.trimIndent() + "\n"
        )
        logger.info("[mapBlacklist] 黑名单文件不存在,已创建默认文件")
        return
    }
    blacklistFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            line.toIntOrNull()?.let { blacklist.add(it) }
        }
    logger.info("[mapBlacklist] 已加载黑名单: ${blacklist.size} 个地图ID")
}

fun saveBlacklist() {
    blacklistFile.writeText(
        """
        # 地图黑名单
        # 每行一个地图ID(以#开头的行将被忽略)
        # 黑名单中的地图无法通过投票换图,管理员可使用host强制换图
        """.trimIndent() + "\n" +
        blacklist.sorted().joinToString("\n") { it.toString() } + "\n"
    )
}

suspend fun refreshCache() {
    try {
        cachedMaps = MapRegistry.searchMaps()
        logger.info("[mapBlacklist] 地图缓存已刷新: ${cachedMaps.size} 张地图")
    } catch (e: Exception) {
        logger.warning("[mapBlacklist] 刷新地图缓存失败: ${e.message}")
    }
}

onEnable {
    loadBlacklist()
}

listen<EventType.ServerLoadEvent> {
    launch(Dispatchers.game) { refreshCache() }
}

// 过滤自动选图: 黑名单中的地图不被自动选择(游戏结束换图、自动开服、host无参数)
listenTo<GetNextMapEvent>(Event.Priority.Before) {
    if (!isBlacklisted(mapInfo.id)) return@listenTo
    // 优先使用缓存; 缓存为空时(启动时序问题) fallback 到实时查询
    val source = cachedMaps.takeIf { it.isNotEmpty() }
        ?: MapRegistry.searchMaps().also { cachedMaps = it }
    val alternative = source
        .filter { it.mode == mapInfo.mode && it != previous && !isBlacklisted(it.id) }
        .randomOrNull()
    if (alternative != null) {
        logger.info("[mapBlacklist] 地图 ${mapInfo.id}(${mapInfo.name}) 在黑名单中,自动替换为 ${alternative.id}(${alternative.name})")
        mapInfo = alternative
    } else {
        logger.warning("[mapBlacklist] 地图 ${mapInfo.id}(${mapInfo.name}) 在黑名单中,但未找到替代地图")
    }
}

// 拦截黑名单地图加载(投票换图兜底), host 命令通过 forced 集合放行
listenTo<MapChangeEvent>(Event.Priority.Before) {
    if (MapBlacklistHook.forced.remove(info.id)) return@listenTo
    if (isBlacklisted(info.id)) {
        cancelled = true
        broadcast("{tr mapBlacklist.broadcast.blocked}".with("id" to info.id, "name" to info.name))
        logger.info("[mapBlacklist] 阻止加载黑名单地图 ${info.id}(${info.name})")
    }
}

command("mapBlacklist", "{tr command.mapBlacklist.desc}".with()) {
    usage = "[[add/remove/list/reload] [[mapId]"
    aliases = listOf("地图黑名单")
    permission = "wayzer.mapBlacklist.manage"
    body {
        when (arg.getOrNull(0)?.lowercase()) {
            "add" -> {
                val id = arg.getOrNull(1)?.toIntOrNull()
                    ?: returnReply("{tr mapBlacklist.reply.invalidId}".with())
                if (blacklist.contains(id)) {
                    returnReply("{tr mapBlacklist.reply.alreadyInBlacklist}".with("id" to id))
                }
                blacklist.add(id)
                saveBlacklist()
                returnReply("{tr mapBlacklist.reply.added}".with("id" to id))
            }
            "remove", "del" -> {
                val id = arg.getOrNull(1)?.toIntOrNull()
                    ?: returnReply("{tr mapBlacklist.reply.invalidId}".with())
                if (!blacklist.contains(id)) {
                    returnReply("{tr mapBlacklist.reply.notInBlacklist}".with("id" to id))
                }
                blacklist.remove(id)
                saveBlacklist()
                returnReply("{tr mapBlacklist.reply.removed}".with("id" to id))
            }
            "reload" -> {
                loadBlacklist()
                launch(Dispatchers.game) { refreshCache() }
                returnReply("{tr mapBlacklist.reply.reloaded}".with("count" to blacklist.size))
            }
            "list", null -> {
                if (blacklist.isEmpty()) {
                    returnReply("{tr mapBlacklist.reply.empty}".with())
                } else {
                    returnReply("{tr mapBlacklist.reply.list}".with("list" to blacklist.sorted().joinToString(", ")))
                }
            }
            else -> {
                returnReply("{tr mapBlacklist.reply.usage}".with())
            }
        }
    }
}
