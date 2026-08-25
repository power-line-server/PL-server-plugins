@file:Depends("wayzer/map/betterTeam")
@file:Depends("coreMindustry/menu")
@file:Depends("wayzer/user/lang", "PlayerData.lang")

package wayzer.ext

import arc.util.I18NBundle
import cf.wayzer.placehold.DynamicVar
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.LangService
import coreMindustry.MenuBuilder
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.PlayerSpawnCallPacket
import mindustry.world.blocks.storage.CoreBlock
import wayzer.lib.PlayerData
import wayzer.map.TeamService
import wayzer.user.lang
import java.util.Locale

val teams by lazy { Services.get<TeamService>().get() }
private val langApi by lazy { Services.get<LangService>().get() }

// 按玩家语言缓存原版 bundle, 用于在 /ob 菜单中按玩家语言显示队伍名
// (Team.localized() 使用 Core.bundle, 即服务器固定语言, 无法按玩家本地化)
// langToLocale: "en" 映射到 Locale.ROOT 直接加载 bundle.properties, 避免 fallback 到 Locale.getDefault() (中文系统为 zh_CN)
private fun langToLocale(lang: String): Locale =
    if (lang.isBlank() || lang == "en") Locale.ROOT else Locale.forLanguageTag(lang.replace("_", "-"))

private val bundleCache = mutableMapOf<Locale, I18NBundle>()
fun bundleFor(lang: String): I18NBundle = bundleCache.getOrPut(langToLocale(lang)) {
    I18NBundle.createBundle(langApi.getGameBundleBase(), langToLocale(lang))
}

@Savable(serializable = false)
val obTeam = mutableMapOf<Player, Team>()
customLoad(::obTeam) {
    obTeam.putAll(it.filterKeys { it.con != null })
}
fun getObTeam(player: Player): Team? = obTeam[player]?.takeIf { it != player.team() }
export(::getObTeam)
listen<EventType.ResetEvent> { obTeam.clear() }
listen<EventType.PlayerLeave> { obTeam.remove(it.player) }
listenPacket2Server<PlayerSpawnCallPacket> { con, _ -> con.player !in obTeam }
listen<EventType.TapEvent> {
    if (it.tile.build is CoreBlock.CoreBuild && it.player in obTeam) {
        val team = it.tile.team()
        if (obTeam[it.player] == team) return@listen
        obTeam[it.player] = team
        broadcast(
            "{tr observer.broadcast.spectating}"
                .with("player" to it.player, "team" to team), type = MsgType.InfoToast, quite = true
        )
    }
}
registerVarForType<Player>().apply {
    registerChild("prefix.3obTeam", "观战队伍显示") {
        getObTeam(it)?.let { team ->
            val lang = PlayerData[it].lang
            val teamName = bundleFor(lang).get("team.${team.name}.name", team.name)
            "{tr observer.prefix.obTeam}".with("receiver" to it, "teamName" to "[#${team.color}]$teamName").toString()
        }
    }
}
fun setObTeam(player: Player, team: Team?) {
    // 解除附身，防止将单位带入观察者队伍
    Call.unitClear(player)

    if (team == null) {
        teams.changeTeam(player, teams.spectateTeam)
        obTeam.remove(player)
        teams.changeTeam(player)
        broadcast(
            "{tr observer.broadcast.respawn}"
                .with("player" to player), type = MsgType.InfoToast, quite = true
        )
        return
    }

    teams.changeTeam(player, teams.spectateTeam)
    obTeam[player] = team
    broadcast(
        "{tr observer.broadcast.spectating}"
            .with("player" to player, "team" to team), type = MsgType.InfoToast, quite = true
    )
    player.sendMessage("{tr observer.reply.respawnHint}".with())
}

command("ob", "{tr command.ob.desc}".with()) {
    type = CommandType.Client
    permission = "wayzer.ext.observer"
    body {
        val player = player!!
        val team = arg.firstOrNull()?.toIntOrNull()?.let { Team.all.getOrNull(it) }
        if (team != null) {
            setObTeam(player, team.takeUnless { it == Team.derelict })
            return@body
        }
        MenuBuilder {
            title = "{tr observer.menu.title}".with("receiver" to player).toString()
            msg = "{tr observer.menu.msg}".with("receiver" to player).toString()
            val bundle = bundleFor(PlayerData[player].lang)
            teams.allTeam.forEach { t ->
                val teamName = bundle.get("team.${t.name}.name", t.name)
                option("${t.emoji}[#${t.color}]$teamName[]") { setObTeam(player, t) }
                newRow()
            }
            option("{tr observer.menu.exit}".with("receiver" to player).toString()) { setObTeam(player, null) }
        }.sendTo(player)
    }
}

listen<EventType.WorldLoadEndEvent> {
    world.tiles.iterator().forEach {
        if (it.team().id == 255) it.setAir()
    }
}