@file:Depends("coreLibrary/extApi/KVStore", "累计统计持久化")

package wayzer.cmds

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.KVStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mindustry.game.EventType
import org.h2.mvstore.type.StringDataType

name = "服务器状态"

/**
 * 服务器统计: 今日(玩家数/人流量/聊天数/峰值在线) + 累计(玩家数/人流量/聊天数/在线时长)。
 * 累计值持久化到 KVStore(脚本重启不丢), 每 5 分钟落一次盘; 今日值跨天自动清零。
 * 数据格式: 纯文本以 | 分隔(不引入 JSON 依赖): totalJoins|totalChats|totalSeconds|totalPlayers|day|dayJoins|dayChats|dayPeak|playersCsv
 */
private val statsStore by lazy { Services.get<KVStore>().get().open("serverStats", StringDataType.INSTANCE) }

private class ServerStats {
    var totalJoins = 0L
    var totalChats = 0L
    var totalSeconds = 0L
    var day = java.time.LocalDate.now().toString()
    var dayJoins = 0L
    var dayChats = 0L
    var dayPeak = 0
    var dayPlayers = linkedSetOf<String>()
    var allPlayers = linkedSetOf<String>()
    val joinTime = mutableMapOf<String, Long>()

    fun toText() = listOf(
        totalJoins, totalChats, totalSeconds, allPlayers.size, day, dayJoins, dayChats, dayPeak,
        allPlayers.joinToString(",")
    ).joinToString("|")

    fun fromText(text: String) {
        val parts = text.split("|")
        if (parts.size < 9) return
        totalJoins = parts[0].toLongOrNull() ?: 0L
        totalChats = parts[1].toLongOrNull() ?: 0L
        totalSeconds = parts[2].toLongOrNull() ?: 0L
        day = parts[4]
        dayJoins = parts[5].toLongOrNull() ?: 0L
        dayChats = parts[6].toLongOrNull() ?: 0L
        dayPeak = parts[7].toIntOrNull() ?: 0
        allPlayers.addAll(parts[8].split(",").filter { it.isNotBlank() })
        if (day != java.time.LocalDate.now().toString()) resetDay()
    }

    /** 跨天: 今日数据清零(累计不动) */
    fun resetDay() {
        day = java.time.LocalDate.now().toString()
        dayJoins = 0L
        dayChats = 0L
        dayPeak = 0
        dayPlayers.clear()
    }

    fun checkDay() {
        if (day != java.time.LocalDate.now().toString()) resetDay()
    }

    fun save() = runCatching { statsStore["data"] = toText() }.isSuccess
}

private val stats = ServerStats()

onEnable {
    runCatching { statsStore["data"]?.let { stats.fromText(it) } }
    launch(Dispatchers.Default) {
        while (true) {
            delay(5 * 60 * 1000L)
            stats.save()
        }
    }
}

onDisable {
    stats.save()
}

listen<EventType.PlayerJoin> {
    stats.checkDay()
    val p = it.player
    stats.totalJoins++
    stats.dayJoins++
    stats.dayPlayers.add(p.uuid())
    stats.allPlayers.add(p.uuid())
    stats.joinTime[p.uuid()] = System.currentTimeMillis()
    // listen 的 lambda 不是 suspend, 统计峰值需另起协程切回游戏线程
    launch {
        withContext(Dispatchers.game) {
            stats.dayPeak = maxOf(stats.dayPeak, mindustry.gen.Groups.player.size())
        }
    }
}

listen<EventType.PlayerLeave> {
    val p = it.player
    val start = stats.joinTime.remove(p.uuid()) ?: return@listen
    stats.totalSeconds += (System.currentTimeMillis() - start) / 1000
}

listen<EventType.PlayerChatEvent> {
    stats.checkDay()
    stats.totalChats++
    stats.dayChats++
}

command("status", "{tr command.status.desc}".with()) {
    aliases = listOf("服务器状态")
    body {
        reply("{tr serverStatus.reply.status}".with())
        reply(
            "{tr serverStatus.reply.stats}".with(
                "dayPlayers" to stats.dayPlayers.size,
                "dayJoins" to stats.dayJoins,
                "dayChats" to stats.dayChats,
                "dayPeak" to stats.dayPeak,
                "totalPlayers" to stats.allPlayers.size,
                "totalJoins" to stats.totalJoins,
                "totalChats" to stats.totalChats,
                "hours" to (stats.totalSeconds / 3600)
            )
        )
    }
}
