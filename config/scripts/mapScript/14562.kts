package mapScript

import mindustry.game.EventType.Trigger
import mindustry.gen.Iconc

name = "填海造陆"
modeIntroduce(
    "{tr mapScript.14562.title}".with(),
    "{tr mapScript.14562.introduce}".with(
        "pumpSmall" to "${Iconc.blockMechanicalPump}",
        "pumpMedium" to "${Iconc.blockRotaryPump}",
        "mine" to "${Iconc.blockShockMine}",
        "pumpLarge" to "${Iconc.blockImpulsePump}"
    )
)

val blocksToOpen = mapOf(
    Blocks.mechanicalPump to 1,
    Blocks.rotaryPump to 3,
    Blocks.impulsePump to 5,
)

onEnable {
    myTiles = world.tiles.map(::IslandTile)
    Groups.build.filter { it.block in blocksToOpen }.forEach {
        // 仅在启动阶段(onEnable)使用 setAir()，此时无玩家在线无需网络同步
        // 运行时事件中必须使用 tile.removeNet() 进行网络同步
        it.tile.setAir()
        it.tile.circle(blocksToOpen[it.block]!!) { x, y ->
            myTiles[world.packArray(x, y)].discoverInit()
        }
    }
}

onDisable {
    discoverQueue.clear()
    myTiles = emptyList()
}

listen<EventType.BlockBuildEndEvent> {
    if (it.breaking) return@listen
    val tile = it.tile
    when (val block = tile.block()) {
        in blocksToOpen -> {
            var any = false
            tile.getLinkedTiles { if (myTiles[it.array()].discover()) any = true }
            if (any) {
                launch(Dispatchers.game) {
                    delay(100)
                    Call.deconstructFinish(tile, block, null)
                }
                tile.circle(blocksToOpen[block]!!) { x, y ->
                    discoverQueue.add(myTiles[world.packArray(x, y)])
                }
            }
        }

        Blocks.shockMine ->
            if (myTiles[tile.array()].unDiscover())
                launch(Dispatchers.game) {
                    delay(100)
                    Call.deconstructFinish(tile, block, null)
                }
    }
}

listen(Trigger.update) {
    repeat(5) {
        val tile = discoverQueue.removeFirstOrNull() ?: return@listen
        tile.discover()
    }
}