@file:Implement(TeamService::class)
@file:Depends("wayzer/maps", "MapManager.current.mode PVP 判定")
package wayzer.map

import arc.Events
import arc.util.I18NBundle
import mindustry.Vars
import mindustry.core.NetServer
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import wayzer.MapManager
import wayzer.MapLoadCompleteEvent
import java.util.Locale

name = "更好的队伍"

// 按玩家语言缓存原版 bundle, 用于在 /team 命令中按玩家语言显示队伍名
// langToLocale: "en" 映射到 Locale.ROOT 直接加载 bundle.properties, 避免 fallback 到 Locale.getDefault() (中文系统为 zh_CN)
private fun langToLocale(lang: String): Locale =
    if (lang.isBlank() || lang == "en") Locale.ROOT else Locale.forLanguageTag(lang.replace("_", "-"))

private val teamBundleCache = mutableMapOf<Locale, I18NBundle>()
fun bundleForTeam(lang: String): I18NBundle = synchronized(teamBundleCache) {
    teamBundleCache.getOrPut(langToLocale(lang)) {
        I18NBundle.createBundle(Vars.tree.get("bundles/bundle"), langToLocale(lang))
    }
}

val spectateTeam = Team.all[255]!!
/** PVP 判定: 实际规则 或 地图 mode(文件名推断, maps.kts bestMode: P 前缀=PvP; 原版地图文件不含 mode 字段) */
val isPvp: Boolean
    get() = state.rules.pvp || MapManager.current?.mode == Gamemode.pvp
val allTeam: Set<Team>
    get() {
        if (!isPvp) return setOf(state.rules.defaultTeam)
        return state.teams.getActive().mapTo(mutableSetOf()) { it.team }.apply {
            remove(Team.derelict)
            removeIf { !it.data().hasCore() }
            removeAll(bannedTeam)
        }.ifEmpty { setOf(state.rules.defaultTeam) }
    }

var bannedTeam = emptySet<Team>()

onEnable {
    val backup = netServer.assigner
    netServer.assigner = NetServer.TeamAssigner { p, g ->
        randomTeam(p, g)
    }
    onDisable { netServer.assigner = backup }
    updateBannedTeam(true)
}
listen<EventType.WorldLoadEvent> { updateBannedTeam(true) }

// 管理员/终端 /team 干预记录: uuid -> 队伍 (仅本局有效, 换图 ResetEvent 清除后重新均分)
val forcedTeams = mutableMapOf<String, Team>()
listen<EventType.ResetEvent> {
    bannedTeam = emptySet()
    forcedTeams.clear()
}
// 与原版一致: PVP 图单人时自动暂停等待玩家加入(NetServer.isWaitingForPlayers 依赖 rules.pvp)
// 地图文件 rules 通常不含 pvp 字段, 且回档时 readRules 会用存档 rules 覆盖, 故在加载完成时按 mode 补齐
listen<MapLoadCompleteEvent> {
    if (isPvp && !state.rules.pvp) {
        state.rules.pvp = true
    }
}
listen<EventType.PlayerLeave> { forcedTeams.remove(it.player.uuid()) }

listen<EventType.BlockDestroyEvent> { e ->
    if (state.gameOver) return@listen
    if (e.tile.block() is CoreBlock)
        launch(Dispatchers.gamePost) {
            if (state.gameOver) return@launch
            if (state.rules.pvp) {
                allTeam.singleOrNull()?.let {
                    state.gameOver = true
                    Events.fire(EventType.GameOverEvent(it))
                }
            }
            // 非PVP不干预，交给原版判定
        }
}

listen<EventType.CoreChangeEvent> { e ->
    val team = e.core.team
    launch(Dispatchers.gamePost) {
        if (!team.active())
            Groups.build.filterIsInstance<CoreBuild>().forEach {
                if (it.lastDamage == team) it.lastDamage = Team.derelict
            }
    }
}

fun updateBannedTeam(force: Boolean = false) {
    if (force || bannedTeam.isEmpty())
        bannedTeam = state.rules.tags.get("@banTeam")?.split(',').orEmpty()
            .mapNotNull { Team.all.getOrNull(it.toIntOrNull() ?: -1) }.toSet()
    Groups.player.filter { it.team() in bannedTeam }.forEach {
        changeTeam(it)
        it.sendMessage("{tr betterTeam.notify.teamBanned}".with(), MsgType.InfoMessage)
    }
}

fun randomTeam(player: Player, group: Iterable<Player> = Groups.player): Team {
    val allTeam = allTeam
    // 管理员/终端 /team 干预过的玩家保持指定队伍(不在 allTeam 时失效回退均分)
    val forced = forcedTeams[player.uuid()]?.takeIf { it in allTeam }
    // oldTeam 传干预队伍: 未干预时为 null, AssignTeamEvent.team 默认 null -> 走均分
    // runCatching: 监听方(如 voteOb)异常不应导致分配失败
    val fromEvent = runCatching {
        Dispatchers.game.safeBlocking { AssignTeamEvent(player, group, forced).emitAsync() }.team
    }.getOrNull()
    if (fromEvent != null) return fromEvent
    // 非PVP直接返回默认队伍，PVP才均分
    if (!isPvp) return state.rules.defaultTeam
    return allTeam.shuffled()
        .minByOrNull { group.count { p -> p.team() == it && player != p } }
        ?: state.rules.defaultTeam
}

fun changeTeam(p: Player, team: Team = randomTeam(p)) {
    val unit = p.unit()
    if (unit != null && !unit.dead() && unit.type != null) {
        unit.team(team)
    }
    p.team(team)
}

command("team", "{tr command.team.desc}".with()) {
    usage = "{tr usage.team}"
    requirePermission("wayzer.ext.team.change")
    body {
        val team = arg.getOrNull(0)?.let { input ->
          val id = input.toIntOrNull()
            ?: returnReply("{tr betterTeam.reply.teamIdMustNumber}".with())
        Team.all.getOrNull(id)
          ?: returnReply("{tr betterTeam.reply.teamNotFound}".with())
        } ?: let {
        val p = player ?: returnReply("{tr betterTeam.reply.needPlayerId}".with())
        // 通过 PlaceHoldLib 变量系统获取玩家语言, 避免 wayzer.user.Lang 编译期依赖
        val lang = "{player.lang}".with("player" to p).toString()
        val bundle = bundleForTeam(lang)
        val teams = state.teams.getActive()
          .filter { it.team != Team.derelict && it.hasCore() }
          .map { t -> "{id}([#{teamColor}]{teamName}[])".with("id" to t.team.id, "teamColor" to t.team.color.toString(), "teamName" to bundle.get("team.${t.team.name}.name", t.team.name)) }
        returnReply("{tr betterTeam.reply.availableTeams}".with("list" to teams))
    }
        val player = arg.getOrNull(1)?.let {
            PlayerData.findByShortId(it)?.player
                ?: returnReply("{tr betterTeam.reply.playerNotFound}".with())
        } ?: (player ?: returnReply("{tr betterTeam.reply.needPlayerId}".with()))
        val unit = player.unit()
        if (unit != null) unit.team(team)
        player.team(team)
        // 记录干预: 玩家在服期间保持该队伍, 退服后清除(重进重新均分)
        forcedTeams[player.uuid()] = team
        broadcast(
            "{tr betterTeam.broadcast.teamChanged}".with("player" to player, "team" to team)
        )
    }
}   