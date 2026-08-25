package wayzer.map

import cf.wayzer.scriptAgent.Event
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player

// 队伍分配事件, 由 betterTeam.kts 触发, voteOb.kts / hexed.kts 等监听
data class AssignTeamEvent(val player: Player, val group: Iterable<Player>, val oldTeam: Team?) : Event,
    Event.Cancellable {
    var team: Team? = oldTeam
        set(value) {
            field = value
            cancelled = true
        }
    override var cancelled: Boolean = false
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

// 队伍服务接口, betterTeam.kts 实现, voteOb.kts / observer.kts 通过 Services.get<TeamService>().get() 调用
interface TeamService {
    val spectateTeam: Team
    val allTeam: Set<Team>
    var bannedTeam: Set<Team>
    /** 管理员/终端 /team 干预记录: uuid -> 队伍 (仅本局有效, 换图清除后重新均分) */
    val forcedTeams: MutableMap<String, Team>
    fun updateBannedTeam(force: Boolean = false)
    fun randomTeam(player: Player, group: Iterable<Player> = Groups.player): Team
    fun changeTeam(p: Player, team: Team = randomTeam(p))
}
