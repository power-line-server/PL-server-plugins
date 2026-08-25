package mapScript

import mindustry.game.Team

// CoreWar(13545) 脚本的队伍数据, 移到 .api.kt 供 13545.menu.kt 访问

data class TeamData(val team: Team) {
    var blockDamageMultiplier by team.rules()::blockDamageMultiplier
    var blockHealthMultiplier by team.rules()::blockHealthMultiplier
    var unitDamageMultiplier by team.rules()::unitDamageMultiplier
    var unitHealthMultiplier by team.rules()::unitHealthMultiplier
}

val teamData = mutableMapOf<Team, TeamData>()
val Team.myData get() = teamData.getOrPut(this) { TeamData(this) }
