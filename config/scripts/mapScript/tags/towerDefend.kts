@file:Depends("mapScript/tags/TDDrop", "掉落", soft = true)

package mapScript.tags

import arc.math.geom.Geometry
import mindustry.net.Administration
import mindustry.world.blocks.ConstructBlock
import mindustry.world.blocks.environment.Floor

/** 塔防模式
 * @author WayZer
 * 如果有修改建议，建议提交PR，维护社区统一。
 * */

registerMapTag("@towerDefend")
modeIntroduce(
    "{tr mapScript.towerDefend.title}".with(), "{tr mapScript.towerDefend.introduce}".with()
)

val allowBlocks = arrayOf(Blocks.armoredConveyor, Blocks.plastaniumConveyor)
val floors = mutableSetOf<Floor>()
onEnable {
    state.rules.bannedBlocks.takeIf { it.isEmpty }?.apply {
        add(Blocks.arc)
        add(Blocks.lancer)
        add(Blocks.airFactory)
        add(Blocks.mendProjector)
        Call.setRules(state.rules)
    }
    for (tile in spawner.spawns) {
        Geometry.circle(0, 0, 4) { dx, dy ->
            floors += tile.nearby(dx, dy)?.floor() ?: return@circle
        }
    }
}
onDisable { floors.clear() }

//build

registerActionFilter {
    when (it.type) {
        Administration.ActionType.placeBlock -> {
            if (it.block in allowBlocks && it.player.team().cores().any { core -> core.dst(it.tile) < 80 }) {
                return@registerActionFilter true //允许在核心附近建武装传送带
            }
            (it.tile.floor() !in floors).also { b ->
                if (!b) it.player.sendMessage("{tr mapScript.towerDefend.message.noBuild}".with(), MsgType.InfoToast, 3f)
            }
        }

        Administration.ActionType.configure -> {
            when {
                it.tile.block() == Blocks.itemSource || it.tile.block() == Blocks.liquidSource -> {
                    it.player.sendMessage("{tr mapScript.towerDefend.message.noModifySource}".with(), MsgType.InfoToast, 3f)
                    return@registerActionFilter false
                }
            }
            true
        }

        else -> true
    }
}
listen<EventType.BlockBuildBeginEvent> {
    if (it.breaking) return@listen
    if (it.tile.floor() in floors) {
        if (it.unit.isPlayer) {
            return@listen//limited by filter
        }
        it.tile.removeNet()
    }
}
listen<EventType.TileChangeEvent> {
    val tile = it.tile
    if (tile.block() == Blocks.air || tile.block() in allowBlocks || tile.floor() !in floors) return@listen
    val building = tile.build
    if (building is ConstructBlock.ConstructBuild && building.current in allowBlocks)
        return@listen
    launch(Dispatchers.gamePost) {
        Call.deconstructFinish(tile, Blocks.air, null)
    }
}

//unit

val specialFlag = 1024.0//use for identify unit spawned from factory
listen<EventType.UnitCreateEvent> {
    if (it.unit.team == state.rules.waveTeam)
        it.unit.flag = specialFlag
}
listen(EventType.Trigger.update) {
    val units = state.rules.waveTeam.data().units
    units.forEach {
        if (it.flag == specialFlag) return@forEach
        if (it.controller() !is TowerDefendAI)
            it.controller(TowerDefendAI(floors))
    }
}
