@file:Depends("coreLibrary/lang", "多语言服务")
@file:Depends("coreLibrary/extApi/KVStore", "存储已读标记")
@file:Depends("coreMindustry/menu", "公告菜单")
@file:Depends("coreMindustry/util/textInput", "复制公告")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("wayzer/user/lang", "玩家语言和时区")
@file:Import("org.json:json:20231013", mavenDepends = true)

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.KVStore
import coreLibrary.LangService
import coreLibrary.lib.parseTimeZone
import coreMindustry.MenuV2
import coreMindustry.util.textInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import org.h2.mvstore.type.StringDataType
import org.json.JSONArray
import org.json.JSONObject
import wayzer.lib.PlayerData
import wayzer.user.lang
import wayzer.user.timezone
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

name = "公告系统"

// ==================== 数据模型 ====================

data class Announcement(
    val id: Int,
    val title: String,
    val content: String,
    val timestamp: Long, // Unix 秒
    val pinned: Boolean = false
)

val announcementFile = File(Config.rootDir, "data/announcements.json")

@Volatile
var announcements: List<Announcement> = emptyList()
    private set

@Volatile
private var nextId = 1

// 已读标记: playerId -> 最后已读的公告时间戳(Unix秒)
val readTracker by lazy {
    Services.get<KVStore>().get().open("announcementRead", StringDataType.INSTANCE)
}

val langApi by lazy { Services.get<LangService>().get() }

// ==================== 数据读写 ====================

@Synchronized
fun loadAnnouncements() {
    if (!announcementFile.exists()) {
        announcementFile.parentFile?.mkdirs()
        saveAnnouncements()
        return
    }
    try {
        val obj = JSONObject(announcementFile.readText())
        val arr = obj.optJSONArray("announcements") ?: JSONArray()
        announcements = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Announcement(
                id = o.getInt("id"),
                title = o.getString("title"),
                content = o.getString("content"),
                timestamp = o.getLong("timestamp"),
                pinned = o.optBoolean("pinned", false)
            )
        }.sortedWith(compareByDescending<Announcement> { it.pinned }.thenByDescending { it.timestamp })
        nextId = obj.optInt("nextId", (announcements.maxOfOrNull { it.id } ?: 0) + 1)
        logger.info("[announcements] 已加载 ${announcements.size} 条公告")
    } catch (e: Exception) {
        logger.warning("[announcements] 加载失败: ${e.message}")
        announcements = emptyList()
    }
}

@Synchronized
fun saveAnnouncements() {
    val obj = JSONObject()
    val arr = JSONArray()
    announcements.forEach { a ->
        arr.put(JSONObject().apply {
            put("id", a.id)
            put("title", a.title)
            put("content", a.content)
            put("timestamp", a.timestamp)
            put("pinned", a.pinned)
        })
    }
    obj.put("announcements", arr)
    obj.put("nextId", nextId)
    announcementFile.writeText(obj.toString(2))
}

@Synchronized
fun addAnnouncement(title: String, content: String, pinned: Boolean = false): Announcement {
    val ann = Announcement(
        id = nextId++,
        title = title,
        content = content,
        timestamp = Instant.now().epochSecond,
        pinned = pinned
    )
    announcements = (announcements + ann).sortedWith(
        compareByDescending<Announcement> { it.pinned }.thenByDescending { it.timestamp }
    )
    saveAnnouncements()
    return ann
}

@Synchronized
fun updateAnnouncement(id: Int, title: String?, content: String?, pinned: Boolean?): Boolean {
    val idx = announcements.indexOfFirst { it.id == id }
    if (idx < 0) return false
    val old = announcements[idx]
    announcements = announcements.toMutableList().also {
        it[idx] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            pinned = pinned ?: old.pinned,
            timestamp = Instant.now().epochSecond // 编辑后刷新时间，让已读玩家重新看到
        )
    }
    saveAnnouncements()
    return true
}

@Synchronized
fun deleteAnnouncement(id: Int): Boolean {
    val removed = announcements.any { it.id == id }
    if (removed) {
        announcements = announcements.filterNot { it.id == id }
        saveAnnouncements()
    }
    return removed
}

// ==================== 已读追踪 ====================

fun getUnreadCount(player: Player): Int {
    val playerId = player.uuid()
    val lastRead = readTracker[playerId]?.toLongOrNull() ?: 0L
    return announcements.count { it.timestamp > lastRead }
}

fun markAllRead(player: Player) {
    readTracker[player.uuid()] = Instant.now().epochSecond.toString()
}

fun getAnnouncementList(): List<Announcement> = announcements

fun getAnnouncementsJson(): JSONArray {
    val arr = JSONArray()
    announcements.forEach { a ->
        arr.put(JSONObject().apply {
            put("id", a.id)
            put("title", a.title)
            put("content", a.content)
            put("timestamp", a.timestamp)
            put("pinned", a.pinned)
        })
    }
    return arr
}

// 供 WebUI 跨脚本调用, 返回 JSON 字符串避免 ClassLoader 不一致问题
fun getAnnouncementsJsonString(): String = getAnnouncementsJson().toString()

// ==================== 时间格式化 ====================

val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun formatTime(timestamp: Long, player: Player): String {
    val tz = PlayerData[player].timezone
    val zone = parseTimeZone(tz)
    return Instant.ofEpochSecond(timestamp).atZone(zone).format(timeFormatter)
}

// ==================== 菜单 ====================

fun showAnnouncementList(player: Player) {
    launch(Dispatchers.game) {
        MenuV2(player) {
            title = "{tr announcement.menu.list.title}".with("receiver" to player).toString()
            msg = if (announcements.isEmpty()) {
                "{tr announcement.menu.list.empty}".with("receiver" to player).toString()
            } else ""

            val perPage = 8
            var page by stateKey(1, "annPage")
            val total = ((announcements.size + perPage - 1) / perPage).coerceAtLeast(1)
            page = page.coerceIn(1, total)
            val start = (page - 1) * perPage
            val end = minOf(start + perPage, announcements.size)

            for (i in start until end) {
                val ann = announcements[i]
                // 截取前20字作为按钮显示
                val preview = if (ann.title.length > 20) ann.title.take(20) + "…" else ann.title
                val timeStr = formatTime(ann.timestamp, player)
                option("[gray][$timeStr][] $preview") {
                    close()
                    showAnnouncementDetail(player, ann.id)
                }
            }

            // 分页
            column(3) {
                option("{tr announcement.menu.prev}".with("receiver" to player).toString()) {
                    if (page > 1) page--
                    refresh()
                }
                option("$page/$total") { refresh() }
                option("{tr announcement.menu.next}".with("receiver" to player).toString()) {
                    if (page < total) page++
                    refresh()
                }
            }
        }.send().awaitWithTimeout()
    }
}

fun showAnnouncementDetail(player: Player, annId: Int) {
    launch(Dispatchers.game) {
        val ann = announcements.find { it.id == annId } ?: return@launch
        val timeStr = formatTime(ann.timestamp, player)
        MenuV2(player) {
            title = ann.title
            // 完整内容放 msg,菜单的 msg 区域可滚动,按钮固定在底部
            msg = "[gray]$timeStr[]\n\n${ann.content}"
            autoCloseButton = false
            column(3) {
                option("{tr announcement.menu.back}".with("receiver" to player).toString()) {
                    close()
                    showAnnouncementList(player)
                }
                option("{tr announcement.menu.copy}".with("receiver" to player).toString()) {
                    textInput(player, "{tr announcement.input.copy.title}".with("receiver" to player).toString(), "{tr announcement.input.copy.hint}".with("receiver" to player).toString(), ann.content, Int.MAX_VALUE)
                    close()
                    showAnnouncementDetail(player, annId)
                }
                option("{tr coreMenu.close}".with("receiver" to player).toString()) {
                    close()
                }
            }
        }.send().awaitWithTimeout()
    }
}

fun showUnreadNotice(player: Player) {
    val unread = getUnreadCount(player)
    if (unread == 0) return
    launch(Dispatchers.game) {
        try {
            MenuV2(player) {
                title = "{tr announcement.menu.unread.title}".with("receiver" to player).toString()
                msg = "{tr announcement.menu.unread.msg}".with("receiver" to player, "count" to unread).toString()
                option("{tr announcement.menu.view}".with("receiver" to player).toString()) {
                    markAllRead(player)
                    close()
                    showAnnouncementList(player)
                }
                option("{tr announcement.menu.later}".with("receiver" to player).toString()) {
                    markAllRead(player)
                }
            }.send().awaitWithTimeout()
        } catch (e: Exception) {
            logger.warning("[announcements] 未读弹窗异常: ${e.message}")
        }
    }
}

// ==================== 通知在线玩家 ====================

fun notifyAllOnline() {
    Groups.player.forEach { p ->
        val unread = getUnreadCount(p)
        if (unread > 0) {
            showUnreadNotice(p)
        }
    }
}

// ==================== 命令 ====================

command("announcement", "{tr announcement.command.desc}".with()) {
    aliases = listOf("公告")
    body {
        val p = player ?: returnReply("{tr announcement.reply.playerOnly}".with())
        showAnnouncementList(p)
    }
}

// ==================== 事件监听 ====================

onEnable {
    loadAnnouncements()
}

listen<EventType.PlayerJoin> {
    val p = it.player
    launch(Dispatchers.game) {
        kotlinx.coroutines.delay(2000) // 等待玩家完全加载
        val unread = getUnreadCount(p)
        if (unread > 0) {
            showUnreadNotice(p)
        }
    }
}

// 导出供 WebUI 调用
export(::loadAnnouncements)
export(::getAnnouncementList)
export(::getAnnouncementsJson)
export(::getAnnouncementsJsonString)
export(::addAnnouncement)
export(::updateAnnouncement)
export(::deleteAnnouncement)
export(::notifyAllOnline)
