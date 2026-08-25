@file:Depends("wayzer/user/shortID", "数字UID")

package wayzer.ext

import arc.util.Log
import cf.wayzer.scriptAgent.Config
import coreLibrary.lib.event.RequestPermissionEvent
import coreLibrary.lib.util.loop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.PlayerData
import java.io.File
import java.time.LocalDate

// ═══════════════════════════════════════════
// 风纪委员成员名单（支持自动过期, 与 VIP 同一套机制）
// 成员文件: data/moderators.txt
//   玩家标识                 永久
//   玩家标识:YYYY-MM-DD      到期自动移除(到期日当天仍有效, 次日 0 点移除)
//   玩家标识:YYYY-MM-DD:vips.txt  到期后移动到其他名单文件(仅限 data 目录下的 .txt)
// 玩家标识支持 uuid / 数字UID / 玩家名(不能含冒号)
// 名单中的玩家自动获得 @moderator 组全部权限(通过权限事件注入)
// 每分钟自动检查一次到期成员
// ═══════════════════════════════════════════

val moderatorListFile: File get() = File(Config.dataDir, "moderators.txt")

/** 名单条目: raw=原始行, id=玩家标识, expire=到期日(null=永久), moveTo=到期目标名单文件(null=移除) */
data class ModeratorEntry(
    val raw: String,
    val id: String,
    val expire: LocalDate?,
    val moveTo: String?
)

// 目标名单文件白名单: 仅允许 data 目录下的 xxx.txt(防路径穿越)
private val MOVE_TARGET = Regex("""[\w-]+\.txt""")

/** 解析一行; 非成员行(注释/空行/格式错误)返回 null */
fun parseModeratorEntry(line: String): ModeratorEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val parts = trimmed.split(":", limit = 3)
    val id = parts[0].trim()
    if (id.isEmpty()) return null
    val expire = parts.getOrNull(1)?.trim()?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    if (parts.size > 1 && expire == null) return null
    val moveTo = parts.getOrNull(2)?.trim()?.takeIf { MOVE_TARGET.matches(it) }
    return ModeratorEntry(trimmed, id, expire, moveTo)
}

fun loadModeratorList(): List<ModeratorEntry> {
    if (!moderatorListFile.exists()) {
        moderatorListFile.parentFile?.mkdirs()
        moderatorListFile.writeText(
            """
            # 风纪委员成员名单(支持自动过期, 如试用期)
            # 每行一个玩家标识, 支持三种格式(任选其一):
            #   1. UUID(32位hex, 或带连字符)
            #   2. 数字UID(聊天中玩家名字旁的数字, 如 [玩家名 6] 中的 6)
            #   3. 玩家名(玩家改名后需更新; 不能包含冒号)
            # 行格式:
            #   玩家标识                 永久
            #   玩家标识:YYYY-MM-DD      到期自动移除(到期日当天仍有效, 次日0点移除)
            #   玩家标识:YYYY-MM-DD:vips.txt  到期后移动到其他名单文件(仅限 data 目录下的 .txt)
            # 以 # 开头的行是注释, 空行忽略
            # 保存文件后自动生效; 到期检查每分钟执行一次
            # 示例:
            #   a1b2c3d4e5f60718293a4b5c6d7e8f90
            #   6:2026-09-15
            #   7:2026-10-01:vips.txt
            """.trimIndent() + "\n"
        )
    }
    return moderatorListFile.readLines().mapNotNull { parseModeratorEntry(it) }
}

// 名单缓存(按文件修改时间失效)
var moderatorListCache: List<ModeratorEntry>? = null
var moderatorListCacheTime = 0L

fun moderatorList(): List<ModeratorEntry> {
    val mt = moderatorListFile.lastModified()
    if (moderatorListCache == null || mt != moderatorListCacheTime) {
        moderatorListCache = loadModeratorList()
        moderatorListCacheTime = mt
    }
    return moderatorListCache ?: emptyList()
}

/** 判断玩家是否在风纪委员名单中(未过期的条目, 支持 uuid / 数字UID / 玩家名) */
fun isModeratorMember(data: PlayerData): Boolean {
    val today = LocalDate.now()
    return moderatorList().any { entry ->
        if (entry.expire != null && entry.expire.isBefore(today)) return@any false
        data.ids.any { it == entry.id } || data.name == entry.id
    }
}

/** 权限注入: 名单中的玩家获得 @moderator 组全部权限 */
listenTo<RequestPermissionEvent> {
    val player = this.subject as? Player ?: return@listenTo
    try {
        if (isModeratorMember(PlayerData[player])) {
            group = group + "@moderator"
        }
    } catch (e: Exception) {
        logger.warning("[moderator] 权限注入失败: ${e.message}")
    }
}

// ═══════════════════════════════════════════
// 到期检查: 每分钟执行, 移除/移动过期成员并重写名单文件(与 VIP 同机制)
// ═══════════════════════════════════════════

/** 检查并处理到期成员; 返回处理数量 */
suspend fun checkModeratorExpiration(): Int {
    val today = LocalDate.now()
    val entries = loadModeratorList()
    val expired = entries.filter { it.expire != null && it.expire.isBefore(today) }
    if (expired.isEmpty()) return 0

    Log.info("[moderator] 发现 ${expired.size} 个到期成员, 开始处理")
    val keepRaw = moderatorListFile.readLines()
    val expiredIds = expired.map { it.id }.toSet()
    val removedIds = mutableSetOf<String>()

    expired.filter { it.moveTo != null }.forEach { entry ->
        val target = File(Config.dataDir, entry.moveTo!!)
        try {
            val existed = target.exists() && target.readLines().any { it.trim() == entry.id }
            if (!existed) target.appendText(entry.id + "\n")
            removedIds.add(entry.id)
            Log.info("[moderator] ${entry.id} 到期, 已移动到 ${entry.moveTo}")
        } catch (e: Exception) {
            Log.warn("[moderator] 移动 ${entry.id} 到 ${entry.moveTo} 失败: ${e.message}")
        }
    }
    expired.filter { it.moveTo == null }.forEach { entry ->
        removedIds.add(entry.id)
        Log.info("[moderator] ${entry.id} 到期, 已从名单移除")
    }

    if (removedIds.isNotEmpty()) {
        val newLines = keepRaw.filter { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@filter true
            val entry = parseModeratorEntry(t)
            if (entry == null) return@filter true
            entry.id !in removedIds
        }
        moderatorListFile.writeText(newLines.joinToString("\n", postfix = "\n"))
        moderatorListCache = null
    }

    withContext(Dispatchers.game) {
        Groups.player.forEach { player ->
            val data = PlayerData[player]
            if (data.ids.any { it in removedIds } || data.name in removedIds) {
                player.sendMessage("{tr moderator.expired}")
            }
        }
    }
    return expired.size
}

// 每分钟检查一次
loop(Dispatchers.Default) {
    delay(60_000)
    try {
        checkModeratorExpiration()
    } catch (e: Exception) {
        Log.warn("[moderator] 到期检查失败: ${e.message}")
    }
}
