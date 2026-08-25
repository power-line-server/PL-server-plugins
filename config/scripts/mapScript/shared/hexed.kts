@file:Depends("wayzer/map/betterTeam", "队伍分配")

package mapScript.shared

import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import mindustry.world.modules.ItemModule
import kotlin.time.Duration.Companion.minutes

listen<EventType.BlockDestroyEvent> {
    val core = it.tile.build as? CoreBuild ?: return@listen
    core.items = ItemModule() //防止爆炸
    Call.clearItems(core)
    launch(Dispatchers.gamePost) {
        HexData.pos2hex[core.pos()]?.occupy(core.lastDamage)
    }
}
listen<EventType.BlockBuildEndEvent> {
    val core = it.tile.build as? CoreBuild ?: return@listen
    launch(Dispatchers.gamePost) {
        HexData.pos2hex[core.pos()]?.occupy(core.team)
    }
}

listenTo<wayzer.map.AssignTeamEvent> {
    // 仅六边形地图(HexData 已初始化)接管队伍分配; 其他地图交给 betterTeam 处理(新玩家均分/管理员干预)
    if (HexData.pos2hex.isEmpty()) return@listenTo
    team = HexData.assignTeam(player, group)
}

onEnable {
    launch(Dispatchers.game) {
        delay(1.minutes)
        state.rules.canGameOver = true
        Call.setRules(state.rules)
    }
}

onDisable {
    HexData.reset()
}