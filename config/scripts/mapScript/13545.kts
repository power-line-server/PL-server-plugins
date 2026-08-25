@file:Depends("coreMindustry/menu", "调用菜单")
@file:Depends("coreMindustry/util/spawnAround")

/**@author WayZer*/

package mapScript

import arc.util.Align
import coreLibrary.lib.util.loop
import kotlinx.coroutines.withContext
import mindustry.game.Team
import mindustry.gen.Iconc
import mindustry.net.Administration
import mindustry.world.blocks.storage.CoreBlock
import org.intellij.lang.annotations.Language

modeIntroduce(
    "{tr mapScript.13545.title}".with(), "{tr mapScript.13545.introduce}".with()
)

@Language("JSON5")
val patch = """
{
    "name": "CoreWar",
    "block.core-foundation.unitType": "alpha",
    "block.core-nucleus.unitType": "alpha",
    "block.core-nucleus.itemCapacity": 1000000,
}
""".trimIndent()
mapPatches = listOf(patch)

onDisable { teamData.clear() }

registerActionFilter {
    if(it.type == Administration.ActionType.control || it.type==Administration.ActionType.command){
        if(it.unit.type == UnitTypes.mono)
            return@registerActionFilter false
    }
    true
}

listen<EventType.TapEvent> {
    (it.tile.build as? CoreBlock.CoreBuild)?.let { core ->
        val player = it.player
        if (!player.dead() && core.team == player.team())
            launch(Dispatchers.game) {
                CoreWarMenu(player, core).sendTo(player, 60_000)
            }
    }
}

onEnable {
    state.rules.bannedBlocks.add(Blocks.deconstructor)
    Call.setRules(state.rules)
    // 用 Dispatchers.Default 运行循环, delay 在线程池上响应取消, 避免关闭时与主线程 runBlocking 死锁
    loop(Dispatchers.Default) {
        delay(2000)
        withContext(Dispatchers.game) {
            val teams = Groups.player.mapTo(mutableSetOf()) { it.team() }
            teams.removeAll { !it.active() }
            val teamsStr = teams.sortedByDescending { it.myData.unitDamageMultiplier }.take(5)
                .joinToString("\n") { team ->
                    "[#${team.color}]${team.name}[white]{tr mapScript.13545.hud.stats}${Iconc.modePvp}${team.myData.unitDamageMultiplier} ${team.myData.unitHealthMultiplier} ${Iconc.turret}${team.myData.blockDamageMultiplier} ${Iconc.defense}${team.myData.blockHealthMultiplier} "
                }
            val text = "{tr mapScript.13545.hud.menuTip}\n{tr mapScript.13545.hud.update}\n$teamsStr".with()
            Groups.player.forEach { p ->
                val con = p.con ?: return@forEach
                Call.infoPopup(
                    con, text.toPlayer(p), 2.013f,
                    Align.topLeft, 350, 0, 0, 0
                )
            }
        }
    }
}