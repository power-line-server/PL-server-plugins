@file:Depends("wayzer/maps", "换图")

package wayzer.reGrief

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mindustry.gen.Groups
import wayzer.MapManager
import kotlin.time.Duration.Companion.seconds

//防"无人游玩挂机": 地图上连续100秒没有存活玩家则自动换图; 有人连接/游玩即重置
//(上游原版含恶意逻辑方块检测, 本服不需要, 已移除)

var newMap = false
var counter = 0
onEnable {
    loop(Dispatchers.Default) {
        delay(1.seconds)
        val shouldChange = withContext(Dispatchers.game) {
            if (newMap || Groups.player.count { !it.dead() && it.unit().health > 0 } > 0) {
                counter = 0
                false
            } else {
                counter += 1
                counter > 100
            }
        }
        if (shouldChange) {
            broadcast("{tr autoChangeMap.broadcast.noPlayer}".with())
            delay(5.seconds)
            withContext(Dispatchers.game) {
                MapManager.loadMap()
                newMap = true
            }
        }
    }
}

listen<EventType.ResetEvent> {
    newMap = true
}

listen<EventType.ConnectPacketEvent> {
    //Someone request connect, maybe want to play
    newMap = false
}