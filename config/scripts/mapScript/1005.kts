@file:Depends("mapScript/shared/hexed")
@file:Depends("mapScript/14562", "填海造陆")

package mapScript

import mapScript.myTiles
import mapScript.shared.HexData
import mapScript.shared.HexedGenerator
import mindustry.game.Gamemode
import mindustry.game.Schematic
import mindustry.game.Schematics
import mindustry.game.Team
import mindustry.type.Category
import mindustry.type.ItemStack
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language

/** @author WayZer
 * 私有脚本，仅供参考 */

val generator = HexedGenerator(spacing = 88, wallWidth = 5)
registerGenerator(
    "{tr mapScript.1005.name}".with(), "WayZer", "{tr mapScript.1005.description}".with(),
    mode = Gamemode.pvp,
    filter = setOf("all", "display", "pvp", "hexed"),
    width = generator.width, height = generator.height
) {
    rules.apply {
        generator.applyRules(this)
        loadout = ItemStack.list(
            Items.sand, 2000, Items.thorium, 10
        )

        revealedBlocks.add(Blocks.buildTower)
        hideBannedBlocks = true
        bannedBlocks.addAll(Blocks.afflict, Blocks.unitCargoLoader)

        bannedUnits.addAll(content.units().select { it.buildSpeed > 0 && it.flying })
        bannedBlocks.addAll(Blocks.overdriveDome, Blocks.mender)
        bannedBlocks.addAll(content.blocks().select { it.category == Category.distribution })
        damageExplosions = false

        //balance
        blockHealthMultiplier = 1f
        unitBuildSpeedMultiplier = 2f
        buildCostMultiplier = 0.01f
        buildSpeedMultiplier = 0.02f

        repeat(generator.chunkCenters.size) {
            teams[Team.get(it + 6)].cheat = true
        }
    }
    @Suppress("SpellCheckingInspection")
    val coreSchema: Schematic =
        Schematics.readBase64(
            "bXNjaAF4nE2Kyw3CMBBEx5+EBBBUkooiDmaziCDHjmxH4k4jlMGNaqAONjc00nz0BhW2Bja4idFe+f59vj7vB3YDZ0rjXMYYANTendln6P5U43iJibibU7wxlZiwp5i4Cwt5XjIOE4fhjzZL8NENnGBcImwyuVJkAS2gREZBo4IY0EAbKY1Mpa20FlrDCFEWK1GVhLwsVmp+DXcoHQ=="
        )

    genRound("topography") { tiles ->
        tiles.forEach { it.setFloor(Blocks.sandWater.asFloor()) }
    }
    genRound("initHexData") { HexData.init(generator.chunkCenters, coreSchema) }
}

@Language("JSON5")
val patch = """{
    "name": "1005",
    "unit.aegires.abilities.0.healPercent": 0.4,
    "block.mechanical-pump.buildTime": 60,//origin 22.5
    "block.rotary-pump.requirements": ["metaglass/3000","surge-alloy/1000"],
    "block.rotary-pump.buildTime": 100,
    "block.impulse-pump.requirements": ["oxide/5000"],
    "block.impulse-pump.buildTime": 1,
    "block.mend-projector.healPercent": 4,//小治疗恢复量
    "block.build-tower.unitType.buildSpeed": 1, //origin 1.5 gamma 1.0
    "block.build-tower.buildTime": 1000,//origin 234
    "block.build-tower.requirements": ["oxide/20000"],
    "block.build-tower.consumes": {remove: "all"},
    "block.overdrive-projector.buildTime": 1,
    "block.overdrive-projector.requirements": ["phase-fabric/10000","oxide/50000"],
    }
""".trimMargin()
mapPatches = listOf(patch)

onEnable {
    //        ${content.blocks().toList().filter { it is GenericCrafter || it is Separator }.joinToString("\n") {
    //            """block.${it.name}.consumers:{clearItems:1,clearLiquids:1},"""
    //        }}
    HexData.extraLoadout.add {
        coreTile.getLinkedTiles { myTiles[it.array()].discover() }
    }
    // 用 Dispatchers.Default 运行循环, delay 在线程池上响应取消, 避免关闭时与主线程 runBlocking 死锁
    loop(Dispatchers.Default) {
        delay(1000)
        withContext(Dispatchers.game) {
            val produce = state.teams.getActive().associate { it.team to it.cores.size }
            produce.forEach { it.key.core()?.handleStack(Items.oxide, it.value, null) }

            val top = produce.entries
                .sortedByDescending { it.value }
                .run { subList(0, size.coerceAtMost(3)) }
                .map { "{tr mapScript.1005.hud.rank}".with("team" to it.key, "rate" to it.value) }
                .joinToString("\n")
            Groups.player.forEach { p ->
                Call.setHudTextReliable(
                    p.con, "{tr mapScript.1005.hud.production}".with(
                        "item" to Items.oxide, "rate" to (produce[p.team()] ?: 0), "top" to top
                    ).toPlayer(p)
                )
            }
        }
    }
}