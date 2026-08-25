@file:Depends("wayzer/map/betterTeam", "获取可用队伍")

package wayzer.pvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mindustry.game.Team
import mindustry.gen.Groups
import wayzer.map.TeamService

val teams by Services.get<TeamService>().notNull

val score = IntArray(Team.all.size)
fun doCheck() {
    val existed = Groups.player.map { it.team() }.toSet()
    teams.allTeam.forEach { team ->
        val old = score[team.id]
        score[team.id] = when {
            team in existed -> 0
            old >= 60 -> 1.also {
                broadcast("{tr autoGameover.broadcast.surrender}".with("team0" to team))
                team.cores().toArray().forEach {
                    it.lastDamage = Team.derelict
                    it.kill()
                }
            }

            else -> (old + 1).also {
                if (old == 0)
                    broadcast("{tr autoGameover.broadcast.surrenderSoon}".with("team0" to team))
            }
        }
    }
}

fun check() {
    if (!state.rules.pvp) return
    launch(Dispatchers.Default) {
        delay(30000)
        while (true) {
            withContext(Dispatchers.game) { doCheck() }
            delay(1000)
        }
    }
}

listen<EventType.WorldLoadEvent> { check() }
onEnable { check() }

listen<EventType.ResetEvent> {
    coroutineContext[Job]?.cancelChildren()
    score.fill(0)
}