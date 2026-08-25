@file:Depends("wayzer/vote", "投票实现")

package wayzer.cmds

import arc.Events
import arc.util.Time
import mindustry.game.EventType.GameOverEvent
import wayzer.VoteService
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

fun VoteService.register() {
    addSubVote("{tr vote.subVote.gameOver.desc}", "", "gameOver", "投降", "结算") {
        if (!state.rules.canGameOver)
            returnReply("{tr vote.reply.cannotSurrender}".with())
        if (state.rules.pvp) {
            val team = player!!.team()
            if (!state.teams.isActive(team) || state.teams.get(team)!!.cores.isEmpty)
                returnReply("{tr vote.reply.teamAlreadyLost}".with())

            start(
                player!!, "{tr vote.voteDesc.surrender}".with("player" to player!!, "team" to team),
                canVote = { it.team() == team }, requireNum = { ceil(it * 0.8).toInt() }
            ) {
                team.data().cores.toArray().forEach {
                    if (it.team == team) it.kill()
                }
            }
            return@addSubVote
        }
        start(player!!, "{tr vote.voteDesc.surrenderShort}".with(), supportSingle = true) {
            player!!.team().cores().toArray().forEach { Time.run(Random.nextFloat() * 60 * 3, it::kill) }
            Events.fire(GameOverEvent(state.rules.waveTeam))
        }
    }
    addSubVote("{tr vote.subVote.skipWave.desc}", "{tr vote.subVote.skipWave.usage}", "skipWave", "跳波") {
        if (Groups.player.any { it.team() == state.rules.waveTeam })
            returnReply("{tr vote.reply.cannotSkipWave}".with())
        val lastResetTime by PlaceHold.reference<Instant>("state.startTime")
        val t = (arg.firstOrNull()?.toIntOrNull() ?: 1).coerceIn(1, 1000)
        start(player!!, "{tr vote.voteDesc.skipWave}".with("t" to t), supportSingle = true) {
            val startTime = Instant.now()
            repeat(t) {
                if (lastResetTime > startTime) return@start //Have change map
                val before = state.enemies
                logic.runWave()
                while (spawner.isSpawning) delay(1000L)
                val after = state.enemies
                while (state.enemies > max(before, (after - before) * 3 / 10)) {
                    delay(1000L)
                }
                delay(3000L)
            }
        }
    }
    addSubVote("{tr vote.subVote.text.desc}", "{tr vote.subVote.text.usage}", "text", "文本", "t") {
        if (arg.isEmpty()) returnReply("{tr vote.reply.emptyVoteContent}".with())
        start(player!!, "{tr vote.voteDesc.custom}".with("text" to arg.joinToString(" ")), isTextChannel = true) {}
    }
}

onEnable {
    VoteService.register()
}