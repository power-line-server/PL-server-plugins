package wayzer.map

import arc.math.geom.Geometry
import arc.math.geom.Point2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mindustry.content.StatusEffects
import mindustry.game.Gamemode
import mindustry.gen.Unit
import java.time.Duration
import kotlin.math.ceil

val time by config.key(600, "pvp保护时间(单位秒,小于等于0关闭)")

val Unit.inEnemyArea: Boolean
    get() {
        val closestCore = state.teams.active
            .mapNotNull { it.cores.minByOrNull(this::dst2) }
            .minByOrNull(this::dst2) ?: return false
        return closestCore.team != team() && (state.rules.polygonCoreProtection ||
                dst(closestCore) < state.rules.enemyCoreBuildRadius)
    }

listen<EventType.WorldLoadEvent> {
    var leftTime = state.rules.tags.getInt("@pvpProtect", time)
    if (state.rules.mode() != Gamemode.pvp || time <= 0) return@listen
    loop(Dispatchers.Default) {
        delay(1000)
        withContext(Dispatchers.game) {
            Groups.unit.forEach {
                if (it.inEnemyArea) {
                    it.player?.sendMessage("{tr pvpProtect.reply.enterEnemy}".with())
                    it.closestCore()?.run {
                        val valid = mutableListOf<Point2>()
                        Geometry.circle(tileX(), tileY(), world.width(), world.height(), 10) { x, y ->
                            if (it.canPass(x, y) && (!it.canDrown() || floorOn()?.isDeep == false))
                                valid.add(Point2(x, y))
                        }
                        val r = valid.randomOrNull() ?: return@run
                        it.x = r.x * tilesize.toFloat()
                        it.y = r.y * tilesize.toFloat()
                        it.snapInterpolation()
                    }
                    it.resetController()
                    if (leftTime > 60)
                        it.apply(StatusEffects.unmoving, (leftTime - 60) * 60f)
                }
            }
        }
    }
    launch(Dispatchers.Default) {
        broadcast(
            "{tr pvpProtect.broadcast.start}".with("time" to Duration.ofSeconds(leftTime.toLong())),
            quite = true
        )
        repeat(leftTime / 60) {
            delay(60_000)
            leftTime -= 60
            broadcast("{tr pvpProtect.broadcast.remain}".with("time" to ceil(leftTime / 60f)), quite = true)
        }
        delay(leftTime * 1000L)
        broadcast("{tr pvpProtect.broadcast.end}".with(), quite = true)
        thisScript.coroutineContext.cancelChildren()
    }
}

listen<EventType.ResetEvent> {
    coroutineContext.cancelChildren()
}