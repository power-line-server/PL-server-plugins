package mapScript.tags

import coreLibrary.lib.util.loop
import kotlinx.coroutines.withContext

registerMapTag("@limitAir")

onEnable {
    // 用 Dispatchers.Default 运行循环, delay 在线程池上响应取消, 避免关闭时与主线程 runBlocking 死锁
    loop(Dispatchers.Default) {
        delay(3_000)
        withContext(Dispatchers.game) {
            Groups.unit.forEach {
                if (it.type().flying && it.closestEnemyCore()?.within(it, state.rules.enemyCoreBuildRadius) == true) {
                    it.player?.sendMessage("{tr mapScript.limitAir.message.noAirForce}".with())
                    it.kill()
                }
            }
        }
    }
}

listen<EventType.BlockBuildEndEvent> {
    if (it.tile.block() == Blocks.airFactory && !it.breaking) {
        Call.label("{tr mapScript.limitAir.label.noAirForce}".with().toString(), 60f, it.tile.getX(), it.tile.getY())
    }
}

listen<EventType.PlayerJoin> {
    it.player.sendMessage("{tr mapScript.limitAir.label.noAirForce}".with())
}