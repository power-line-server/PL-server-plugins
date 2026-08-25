@file:Depends("coreLibrary/db", "数据库存储")
@file:Depends("wayzer/user/lang", "玩家语言时区")
@file:Depends("wayzer/user/shortID", "数字UID")
@file:Depends("coreMindustry/util/textInput", "文本输入复制")
@file:Depends("coreLibrary/time", "时区解析")
@file:Import("org.json:json:20231013", mavenDepends = true)

package wayzer.ext

import arc.util.I18NBundle
import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.util.Services
import coreLib.db.DBApi.WithUpgrade
import coreLib.db.DBApi
import coreLibrary.LangService
import coreLibrary.lib.parseTimeZone
import coreMindustry.MenuV2
import coreMindustry.util.textInput
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.json.JSONObject
import wayzer.lib.PlayerData
import wayzer.user.lang
import wayzer.user.timezone
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap

name = "游戏信息查看"

private val langApi by lazy { Services.get<LangService>().get() }

// ===== H2 表定义 =====
object GameRecordTable : IntIdTable("GameRecord"), WithUpgrade {
    override val version = 1
    val mapName = text("mapName")
    val mode = varchar("mode", 32)
    val winnerTeam = varchar("winnerTeam", 32).nullable()
    val gameStartTime = long("gameStartTime")
    val gameEndTime = long("gameEndTime")
    val wavesLasted = integer("wavesLasted")
    val isPvp = bool("isPvp")
    val defaultTeamStats = text("defaultTeamStats")
    val teamStats = text("teamStats")
}
DBApi.registerTable(GameRecordTable)

val maxGameRecords by config.key(20, "最大存储游戏记录数")

// ===== 按队伍累计统计收集器 =====
data class TeamStats(
    var unitsCreated: Int = 0,
    var unitsLost: Int = 0,
    var buildingsBuilt: Int = 0,
    var buildingsDestroyed: Int = 0,
    var buildingsDeconstructed: Int = 0
)

val currentStats = ConcurrentHashMap<Team, TeamStats>()
var enemiesDestroyed = 0
var gameStartTime: Instant = Instant.now()
var lastInsertedRecordId: Int? = null

// 本次游戏结束已忽略广播的玩家集合
val ignoredPlayers = mutableSetOf<String>()

// ===== 队伍名本地化（按玩家语言 I18NBundle） =====
private fun langToLocale(lang: String): Locale =
    if (lang.isBlank() || lang == "en") Locale.ROOT
    else Locale.forLanguageTag(lang.replace("_", "-"))

private val bundleCache = mutableMapOf<Locale, I18NBundle>()

fun localizedTeamName(team: Team, p: Player): String {
    val lang = PlayerData[p].lang
    val locale = langToLocale(lang)
    val bundle = synchronized(bundleCache) {
        bundleCache.getOrPut(locale) { I18NBundle.createBundle(langApi.getGameBundleBase(), locale) }
    }
    return bundle.get("team.${team.name}.name", team.name)
}

fun localizeTeamNameString(teamName: String, p: Player): String {
    val team = Team.all.find { it.name == teamName } ?: return teamName
    return localizedTeamName(team, p)
}

/** 按玩家语言获取带队伍颜色的本地化队伍名 */
fun localizedTeamNameColored(team: Team, p: Player): String {
    val name = localizedTeamName(team, p)
    return "[#${team.color}]$name[]"
}

fun localizeTeamNameStringColored(teamName: String, p: Player): String {
    val team = Team.all.find { it.name == teamName } ?: return teamName
    return localizedTeamNameColored(team, p)
}

/** 按玩家语言获取游戏模式名称(对应原版 bundle 的 mode.<name>.name) */
fun localizedModeName(mode: String, p: Player): String {
    val lang = PlayerData[p].lang
    val locale = langToLocale(lang)
    val bundle = synchronized(bundleCache) {
        bundleCache.getOrPut(locale) { I18NBundle.createBundle(langApi.getGameBundleBase(), locale) }
    }
    return bundle.get("mode.${mode}.name", mode)
}

// ===== 时间格式化 =====
val timeFormatter = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")

fun formatTimestamp(timestamp: Long, p: Player): String {
    val tz = PlayerData[p].timezone
    val zone = parseTimeZone(tz)
    return Instant.ofEpochSecond(timestamp).atZone(zone).format(timeFormatter)
}

fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}

// ===== 展示数据模型 =====
data class GameDisplayData(
    val id: Int?,
    val mapName: String,
    val mode: String,
    val isPvp: Boolean,
    val winnerTeam: String?,
    val gameStartTime: Long,
    val gameEndTime: Long?,
    val gameTimeSeconds: Long,
    val wavesLasted: Int,
    val defaultTeamStats: JSONObject,
    val teamStats: JSONObject
)

data class GameRecordSummary(val id: Int, val mapName: String, val gameEndTime: Long)

// ===== 数据构建 =====
fun buildCurrentGameData(): GameDisplayData {
    val defaultTeam = Vars.state.rules.defaultTeam
    val defaultStats = currentStats[defaultTeam] ?: TeamStats()
    val defaultTeamStatsJson = JSONObject().apply {
        put("unitsCreated", defaultStats.unitsCreated)
        put("enemiesDestroyed", enemiesDestroyed)
        put("buildingsBuilt", defaultStats.buildingsBuilt)
        put("buildingsDestroyed", defaultStats.buildingsDestroyed)
        put("buildingsDeconstructed", defaultStats.buildingsDeconstructed)
    }
    val teamStatsJson = JSONObject()
    currentStats.forEach { (team, stats) ->
        val coresAlive = Vars.state.teams.get(team)?.cores?.size ?: 0
        teamStatsJson.put(team.name, JSONObject().apply {
            put("unitsCreated", stats.unitsCreated)
            put("unitsLost", stats.unitsLost)
            put("buildingsBuilt", stats.buildingsBuilt)
            put("buildingsDestroyed", stats.buildingsDestroyed)
            put("buildingsDeconstructed", stats.buildingsDeconstructed)
            put("coresAlive", coresAlive)
        })
    }
    return GameDisplayData(
        id = null,
        mapName = Vars.state.map?.name() ?: "N/A",
        mode = Vars.state.rules.mode().name,
        isPvp = Vars.state.rules.pvp,
        winnerTeam = null,
        gameStartTime = gameStartTime.epochSecond,
        gameEndTime = null,
        gameTimeSeconds = (Vars.state.tick / 60).toLong(),
        wavesLasted = Vars.state.wave,
        defaultTeamStats = defaultTeamStatsJson,
        teamStats = teamStatsJson
    )
}

fun loadGameData(id: Int): GameDisplayData? = transaction {
    val row = GameRecordTable.selectAll().where { GameRecordTable.id eq id }.firstOrNull()
        ?: return@transaction null
    GameDisplayData(
        id = row[GameRecordTable.id].value,
        mapName = row[GameRecordTable.mapName],
        mode = row[GameRecordTable.mode],
        isPvp = row[GameRecordTable.isPvp],
        winnerTeam = row[GameRecordTable.winnerTeam],
        gameStartTime = row[GameRecordTable.gameStartTime],
        gameEndTime = row[GameRecordTable.gameEndTime],
        gameTimeSeconds = row[GameRecordTable.gameEndTime] - row[GameRecordTable.gameStartTime],
        wavesLasted = row[GameRecordTable.wavesLasted],
        defaultTeamStats = JSONObject(row[GameRecordTable.defaultTeamStats]),
        teamStats = JSONObject(row[GameRecordTable.teamStats])
    )
}

fun getRecentRecords(limit: Int): List<GameRecordSummary> = transaction {
    GameRecordTable.selectAll().orderBy(GameRecordTable.id, SortOrder.DESC).limit(limit).map {
        GameRecordSummary(
            it[GameRecordTable.id].value,
            it[GameRecordTable.mapName],
            it[GameRecordTable.gameEndTime]
        )
    }
}

fun buildInfoMsg(p: Player, data: GameDisplayData, selectedTeam: String): String {
    val lines = mutableListOf<String>()
    if (data.isPvp && data.winnerTeam != null) {
        val winnerDisplay = localizeTeamNameStringColored(data.winnerTeam, p)
        lines.add("{tr gameInfo.menu.winner}".with("receiver" to p, "team" to winnerDisplay).toString())
    }
    lines.add("{tr gameInfo.menu.map}".with("receiver" to p, "name" to data.mapName).toString())
    val modeDisplay = localizedModeName(data.mode, p)
    lines.add("{tr gameInfo.menu.mode}".with("receiver" to p, "mode" to modeDisplay).toString())
    val gameTimeStr = formatDuration(Duration.ofSeconds(data.gameTimeSeconds))
    lines.add("{tr gameInfo.menu.gameTime}".with("receiver" to p, "time" to gameTimeStr).toString())
    lines.add("{tr gameInfo.menu.waves}".with("receiver" to p, "waves" to data.wavesLasted).toString())
    // 默认统计(所有模式统一显示, 和原版 gameover 弹窗一致)
    val stats = data.defaultTeamStats
    lines.add("{tr gameInfo.menu.unitsCreated}".with("receiver" to p, "count" to stats.optInt("unitsCreated")).toString())
    lines.add("{tr gameInfo.menu.enemiesDestroyed}".with("receiver" to p, "count" to stats.optInt("enemiesDestroyed")).toString())
    lines.add("{tr gameInfo.menu.buildingsBuilt}".with("receiver" to p, "count" to stats.optInt("buildingsBuilt")).toString())
    lines.add("{tr gameInfo.menu.buildingsDestroyed}".with("receiver" to p, "count" to stats.optInt("buildingsDestroyed")).toString())
    lines.add("{tr gameInfo.menu.buildingsDeconstructed}".with("receiver" to p, "count" to stats.optInt("buildingsDeconstructed")).toString())
    // PVP 额外显示选中队伍的详细统计
    if (data.isPvp) {
        val teamName = selectedTeam.ifEmpty { data.teamStats.keySet().firstOrNull() ?: "" }
        val teamData = if (teamName.isNotEmpty()) data.teamStats.optJSONObject(teamName) else null
        if (teamData != null) {
            lines.add(localizeTeamNameStringColored(teamName, p))
            lines.add("{tr gameInfo.menu.team.unitsCreated}".with("receiver" to p, "count" to teamData.optInt("unitsCreated")).toString())
            lines.add("{tr gameInfo.menu.team.unitsLost}".with("receiver" to p, "count" to teamData.optInt("unitsLost")).toString())
            lines.add("{tr gameInfo.menu.team.buildingsBuilt}".with("receiver" to p, "count" to teamData.optInt("buildingsBuilt")).toString())
            lines.add("{tr gameInfo.menu.team.buildingsDestroyed}".with("receiver" to p, "count" to teamData.optInt("buildingsDestroyed")).toString())
            lines.add("{tr gameInfo.menu.team.buildingsDeconstructed}".with("receiver" to p, "count" to teamData.optInt("buildingsDeconstructed")).toString())
            lines.add("{tr gameInfo.menu.team.coresAlive}".with("receiver" to p, "count" to teamData.optInt("coresAlive")).toString())
        }
    }
    return lines.joinToString("\n")
}

// ===== 事件监听 =====
listen<EventType.PlayEvent> {
    currentStats.clear()
    enemiesDestroyed = 0
    gameStartTime = Instant.now()
}

listen<EventType.UnitCreateEvent> {
    currentStats.getOrPut(it.unit.team) { TeamStats() }.unitsCreated++
}

listen<EventType.UnitDestroyEvent> {
    currentStats.getOrPut(it.unit.team) { TeamStats() }.unitsLost++
    if (it.unit.team != Vars.state.rules.defaultTeam) enemiesDestroyed++
}

listen<EventType.BlockBuildEndEvent> {
    val stats = currentStats.getOrPut(it.team) { TeamStats() }
    if (it.breaking) stats.buildingsDeconstructed++
    else stats.buildingsBuilt++
}

listen<EventType.BlockDestroyEvent> {
    currentStats.getOrPut(it.tile.team()) { TeamStats() }.buildingsDestroyed++
}

// ===== GameOver 存储 =====
listen<EventType.GameOverEvent> { event ->
    ignoredPlayers.clear()
    val mapName = Vars.state.map?.name() ?: "N/A"
    val mode = Vars.state.rules.mode().name
    val isPvp = Vars.state.rules.pvp
    val winnerTeam = event.winner.name
    val startTime = gameStartTime.epochSecond
    val endTime = Instant.now().epochSecond
    val wavesLasted = Vars.state.wave

    val defaultTeam = Vars.state.rules.defaultTeam
    val defaultStats = currentStats[defaultTeam] ?: TeamStats()
    val defaultTeamStatsJson = JSONObject().apply {
        put("unitsCreated", defaultStats.unitsCreated)
        put("enemiesDestroyed", enemiesDestroyed)
        put("buildingsBuilt", defaultStats.buildingsBuilt)
        put("buildingsDestroyed", defaultStats.buildingsDestroyed)
        put("buildingsDeconstructed", defaultStats.buildingsDeconstructed)
    }

    val teamStatsJson = JSONObject()
    currentStats.forEach { (team, stats) ->
        val coresAlive = Vars.state.teams.get(team)?.cores?.size ?: 0
        teamStatsJson.put(team.name, JSONObject().apply {
            put("unitsCreated", stats.unitsCreated)
            put("unitsLost", stats.unitsLost)
            put("buildingsBuilt", stats.buildingsBuilt)
            put("buildingsDestroyed", stats.buildingsDestroyed)
            put("buildingsDeconstructed", stats.buildingsDeconstructed)
            put("coresAlive", coresAlive)
        })
    }

    val recordId = transaction {
        val newId = GameRecordTable.insertAndGetId {
            it[GameRecordTable.mapName] = mapName
            it[GameRecordTable.mode] = mode
            it[GameRecordTable.winnerTeam] = winnerTeam
            it[GameRecordTable.gameStartTime] = startTime
            it[GameRecordTable.gameEndTime] = endTime
            it[GameRecordTable.wavesLasted] = wavesLasted
            it[GameRecordTable.isPvp] = isPvp
            it[GameRecordTable.defaultTeamStats] = defaultTeamStatsJson.toString()
            it[GameRecordTable.teamStats] = teamStatsJson.toString()
        }.value
        // 超过上限删除最旧记录
        val count = GameRecordTable.selectAll().count()
        if (count > maxGameRecords.toLong()) {
            val toDelete = (count - maxGameRecords.toLong()).toInt()
            val idsToDelete = GameRecordTable.selectAll()
                .orderBy(GameRecordTable.id, SortOrder.ASC)
                .limit(toDelete)
                .map { it[GameRecordTable.id].value }
            if (idsToDelete.isNotEmpty()) {
                idsToDelete.forEach { id ->
                    GameRecordTable.deleteWhere { GameRecordTable.id eq id }
                }
            }
        }
        newId
    }
    lastInsertedRecordId = recordId

    // 延迟 1 秒后广播含菜单按钮的消息
    launch(Dispatchers.game) {
        delay(1000)
        Groups.player.forEach { p ->
            if (p.uuid() !in ignoredPlayers) {
                launch { sendGameOverBroadcast(p, recordId) }
            }
        }
    }
}

// ===== 菜单函数 =====

/** 显示游戏信息菜单（用于 current/last/局号/广播按钮） */
suspend fun showGameInfoMenu(p: Player, data: GameDisplayData) {
    MenuV2(p) {
        var inTeamSelect by stateKey(false, "inTeamSelect")
        var selectedTeam by stateKey(
            data.winnerTeam ?: data.teamStats.keySet().firstOrNull() ?: "",
            "selectedTeam"
        )

        if (inTeamSelect && data.isPvp) {
            title = "{tr gameInfo.menu.viewOtherTeam}".with("receiver" to p).toString()
            msg = ""
            for (teamName in data.teamStats.keySet()) {
                val displayName = localizeTeamNameStringColored(teamName, p)
                val mark = if (teamName == selectedTeam) "[green]✓ []" else ""
                option("$mark$displayName") {
                    selectedTeam = teamName
                    inTeamSelect = false
                    refresh()
                }
            }
            newRow()
            option("{tr gameInfo.menu.back}".with("receiver" to p).toString()) {
                inTeamSelect = false
                refresh()
            }
            return@MenuV2
        }

        title = "{tr gameInfo.menu.title}".with("receiver" to p).toString()
        msg = buildInfoMsg(p, data, selectedTeam)

        if (data.isPvp) {
            option("{tr gameInfo.menu.viewOtherTeam}".with("receiver" to p).toString()) {
                inTeamSelect = true
                refresh()
            }
        }
    }.send().await()
}

/** 显示最近游戏列表菜单（用于 /gameInfo 无参数） */
suspend fun showGameListMenu(p: Player) {
    MenuV2(p) {
        var selectedId by stateKey<Int?>(null, "selectedId")
        var inTeamSelect by stateKey(false, "inTeamSelect")
        var selectedTeam by stateKey("", "selectedTeam")

        if (selectedId != null) {
            // 信息视图
            val data = loadGameData(selectedId!!)
            if (data == null) {
                title = "{tr gameInfo.menu.title}".with("receiver" to p).toString()
                msg = "{tr gameInfo.reply.notFound}".with("receiver" to p).toString()
                newRow()
                option("{tr gameInfo.menu.back}".with("receiver" to p).toString()) {
                    selectedId = null
                    refresh()
                }
                return@MenuV2
            }

            if (inTeamSelect && data.isPvp) {
                title = "{tr gameInfo.menu.viewOtherTeam}".with("receiver" to p).toString()
                msg = ""
                for (teamName in data.teamStats.keySet()) {
                    val displayName = localizeTeamNameStringColored(teamName, p)
                    val mark = if (teamName == selectedTeam) "[green]✓ []" else ""
                    option("$mark$displayName") {
                        selectedTeam = teamName
                        inTeamSelect = false
                        refresh()
                    }
                }
                newRow()
                option("{tr gameInfo.menu.back}".with("receiver" to p).toString()) {
                    inTeamSelect = false
                    refresh()
                }
                return@MenuV2
            }

            title = "{tr gameInfo.menu.title}".with("receiver" to p).toString()
            msg = buildInfoMsg(p, data, selectedTeam)

            if (data.isPvp) {
                option("{tr gameInfo.menu.viewOtherTeam}".with("receiver" to p).toString()) {
                    inTeamSelect = true
                    refresh()
                }
            }
            newRow()
            option("{tr gameInfo.menu.back}".with("receiver" to p).toString()) {
                selectedId = null
                inTeamSelect = false
                selectedTeam = ""
                refresh()
            }
            return@MenuV2
        }

        // 列表视图
        title = "{tr gameInfo.menu.selectGame}".with("receiver" to p).toString()
        val records = getRecentRecords(maxGameRecords)
        if (records.isEmpty()) {
            msg = "{tr gameInfo.reply.noRecords}".with("receiver" to p).toString()
            option("{tr gameInfo.reply.noRecords}".with("receiver" to p).toString()) { refresh() }
        } else {
            msg = ""
            for (record in records) {
                val timeStr = formatTimestamp(record.gameEndTime, p)
                option(
                    "{tr gameInfo.menu.gameEntry}".with(
                        "receiver" to p,
                        "id" to record.id,
                        "mapName" to record.mapName,
                        "time" to timeStr
                    ).toString()
                ) {
                    selectedId = record.id
                    inTeamSelect = false
                    selectedTeam = ""
                    refresh()
                }
            }
        }
    }.send().await()
}

/** 游戏结束广播菜单 */
suspend fun sendGameOverBroadcast(p: Player, recordId: Int) {
    MenuV2(p) {
        title = "{tr gameInfo.broadcast.title}".with("receiver" to p).toString()
        msg = "{tr gameInfo.broadcast.msg}".with("receiver" to p).toString()
        option("{tr gameInfo.broadcast.viewInfo}".with("receiver" to p).toString()) {
            close()
            val data = loadGameData(recordId)
            if (data != null) showGameInfoMenu(p, data)
        }
        option("{tr gameInfo.broadcast.dismiss}".with("receiver" to p).toString()) {
            ignoredPlayers.add(p.uuid())
            close()
        }
    }.send().awaitWithTimeout()
}

// ===== 命令注册 =====
command("gameInfo", "{tr command.gameInfo.desc}".with()) {
    aliases = listOf("游戏信息")
    usage = "{tr usage.gameInfo}"
    body {
        val p = player ?: returnReply("{tr gameInfo.reply.playerOnly}".with())
        val arg = arg.firstOrNull()
        when (arg) {
            null -> launch { showGameListMenu(p) }
            "current" -> launch {
                val data = buildCurrentGameData()
                showGameInfoMenu(p, data)
            }
            "last" -> {
                val lastId = transaction {
                    GameRecordTable.selectAll().orderBy(GameRecordTable.id, SortOrder.DESC)
                        .limit(1).firstOrNull()?.get(GameRecordTable.id)?.value
                }
                if (lastId == null) {
                    returnReply("{tr gameInfo.reply.noRecords}".with())
                } else {
                    val data = loadGameData(lastId)
                    if (data == null) returnReply("{tr gameInfo.reply.notFound}".with())
                    else launch { showGameInfoMenu(p, data) }
                }
            }
            else -> {
                val id = arg.toIntOrNull()
                if (id == null) {
                    returnReply("{tr gameInfo.reply.notFound}".with())
                } else {
                    val data = loadGameData(id)
                    if (data == null) returnReply("{tr gameInfo.reply.notFound}".with())
                    else launch { showGameInfoMenu(p, data) }
                }
            }
        }
    }
}
