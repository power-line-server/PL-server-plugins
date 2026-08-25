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
// VIP 成员名单（支持自动过期，用于付费订阅）
// 成员文件: data/vips.txt(每行一个玩家标识)
//   # 玩家标识                        永久 VIP
//   # 玩家标识:YYYY-MM-DD             到期自动移除（到期日当天仍有效，次日 0 点移除）
//   # 玩家标识:YYYY-MM-DD:moderators.txt  到期后移动到其他名单文件（如风纪委员）
// 玩家标识支持三种格式: uuid(32位hex/带连字符) / 数字UID / 玩家名(不能含冒号)
// 名单中的玩家自动获得 @vip 组全部权限(通过权限事件注入)
// 每分钟自动检查一次到期成员；保存文件后立即生效(无需重启)
// ═══════════════════════════════════════════

val vipListFile: File get() = File(Config.dataDir, "vips.txt")

/** 名单条目: raw=原始行(写回用), id=玩家标识, expire=到期日(null=永久), moveTo=到期目标名单文件(null=移除) */
data class VipEntry(
    val raw: String,
    val id: String,
    val expire: LocalDate?,
    val moveTo: String?
)

// 目标名单文件白名单: 仅允许 data 目录下的 xxx.txt(防路径穿越)
private val MOVE_TARGET = Regex("""[\w-]+\.txt""")

/** 解析一行; 非成员行(注释/空行/格式错误)返回 null */
fun parseVipEntry(line: String): VipEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val parts = trimmed.split(":", limit = 3)
    val id = parts[0].trim()
    if (id.isEmpty()) return null
    // 第二部分必须是合法日期才按"带过期"解析, 否则整行按永久成员(兼容旧格式)
    val expire = parts.getOrNull(1)?.trim()?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    if (parts.size > 1 && expire == null) return null // 格式错误: 有冒号但不是日期
    val moveTo = parts.getOrNull(2)?.trim()?.takeIf { MOVE_TARGET.matches(it) }
    return VipEntry(trimmed, id, expire, moveTo)
}

fun loadVipList(): List<VipEntry> {
    if (!vipListFile.exists()) {
        vipListFile.parentFile?.mkdirs()
        vipListFile.writeText(
            """
            # VIP 成员名单(支持自动过期, 用于付费订阅)
            # 每行一个玩家标识, 支持三种格式(任选其一):
            #   1. UUID(32位hex, 或带连字符)
            #   2. 数字UID(聊天中玩家名字旁的数字, 如 [玩家名 6] 中的 6)
            #   3. 玩家名(玩家改名后需更新; 不能包含冒号)
            # 行格式:
            #   玩家标识                 永久 VIP
            #   玩家标识:YYYY-MM-DD      到期自动移除(到期日当天仍有效, 次日0点移除)
            #   玩家标识:YYYY-MM-DD:moderators.txt  到期后移动到其他名单文件(仅限 data 目录下的 .txt)
            # 以 # 开头的行是注释, 空行忽略
            # 保存文件后自动生效(无需重启, 修改时间变化即重读); 到期检查每分钟执行一次
            # 示例:
            #   a1b2c3d4e5f60718293a4b5c6d7e8f90
            #   6:2026-09-15
            #   7:2026-10-01:moderators.txt
            #   玩家名
            """.trimIndent() + "\n"
        )
    }
    return vipListFile.readLines().mapNotNull { parseVipEntry(it) }
}

// 名单缓存(按文件修改时间失效)
var vipListCache: List<VipEntry>? = null
var vipListCacheTime = 0L

fun vipList(): List<VipEntry> {
    val mt = vipListFile.lastModified()
    if (vipListCache == null || mt != vipListCacheTime) {
        vipListCache = loadVipList()
        vipListCacheTime = mt
    }
    return vipListCache ?: emptyList()
}

/** 判断玩家是否在 VIP 名单中(未过期的条目, 支持 uuid / 数字UID / 玩家名) */
fun isVipMember(data: PlayerData): Boolean {
    val today = LocalDate.now()
    return vipList().any { entry ->
        // 过期条目不生效
        if (entry.expire != null && entry.expire.isBefore(today)) return@any false
        data.ids.any { it == entry.id } || data.name == entry.id
    }
}

/** 权限注入: 名单中的玩家获得 @vip 组全部权限 */
listenTo<RequestPermissionEvent> {
    val player = this.subject as? Player ?: return@listenTo
    try {
        if (isVipMember(PlayerData[player])) {
            group = group + "@vip"
        }
    } catch (e: Exception) {
        logger.warning("[vip] 权限注入失败: ${e.message}")
    }
}

// ═══════════════════════════════════════════
// 到期检查: 每分钟执行, 移除/移动过期成员并重写名单文件
// ═══════════════════════════════════════════

/** 检查并处理到期成员; 返回处理数量 */
suspend fun checkVipExpiration(): Int {
    val today = LocalDate.now()
    val entries = loadVipList() // 强制读文件, 绕过 mtime 缓存
    val expired = entries.filter { it.expire != null && it.expire.isBefore(today) }
    if (expired.isEmpty()) return 0

    Log.info("[vip] 发现 ${expired.size} 个到期成员, 开始处理")
    val keepRaw = vipListFile.readLines() // 原始行(保留注释)
    val expiredIds = expired.map { it.id }.toSet()
    val removedIds = mutableSetOf<String>()

    // 1. 移动到目标名单文件(追加成员标识, 无日期 = 永久)
    expired.filter { it.moveTo != null }.forEach { entry ->
        val target = File(Config.dataDir, entry.moveTo!!)
        try {
            val existed = target.exists() && target.readLines().any { it.trim() == entry.id }
            if (!existed) {
                target.appendText(entry.id + "\n")
            }
            removedIds.add(entry.id)
            Log.info("[vip] ${entry.id} 到期, 已移动到 ${entry.moveTo}")
        } catch (e: Exception) {
            Log.warn("[vip] 移动 ${entry.id} 到 ${entry.moveTo} 失败: ${e.message}")
        }
    }
    // 2. 直接移除(无目标文件)
    expired.filter { it.moveTo == null }.forEach { entry ->
        removedIds.add(entry.id)
        Log.info("[vip] ${entry.id} 到期, 已从名单移除")
    }

    // 3. 重写 vips.txt(保留注释与未过期行)
    if (removedIds.isNotEmpty()) {
        val newLines = keepRaw.filter { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@filter true   // 注释/空行保留
            val entry = parseVipEntry(t)
            if (entry == null) return@filter true                      // 无法解析的行保留
            entry.id !in removedIds                                    // 已处理的到期成员删除
        }
        vipListFile.writeText(newLines.joinToString("\n", postfix = "\n"))
        vipListCache = null // 强制刷新缓存
    }

    // 4. 通知在线玩家
    withContext(Dispatchers.game) {
        Groups.player.forEach { player ->
            val data = PlayerData[player]
            if (data.ids.any { it in removedIds } || data.name in removedIds) {
                player.sendMessage("{tr vip.expired}")
            }
        }
    }
    return expired.size
}

// 每分钟检查一次
loop(Dispatchers.Default) {
    delay(60_000)
    try {
        checkVipExpiration()
    } catch (e: Exception) {
        Log.warn("[vip] 到期检查失败: ${e.message}")
    }
}
