package mapScript

import mindustry.content.Blocks
import mindustry.ctype.ContentType
import mindustry.Vars.content
import mindustry.Vars.state
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.environment.Floor

// 填海造陆脚本的数据结构, 移到 .api.kt 供 1005.kts 等地图脚本访问

var myTiles = emptyList<IslandTile>()

val waterFloor: Floor by lazy {
    state.rules.tags.get("@waterFloor")
        ?.let { content.getByName(ContentType.block, it) as? Floor }
        ?: Blocks.deepwater.asFloor()!!
}

class IslandTile(val tile: Tile) {
    val floor: Floor = tile.floor()
    val overlay: Block = tile.overlay()
    var discovered = false

    init {
        if (overlay != Blocks.spawn && tile.block() == Blocks.air) {
            tile.setBlock(Blocks.stone)//remove ore
            tile.setFloor(waterFloor)
            tile.setAir()
        } else
            discovered = true
    }

    //use in init stage, no net sync
    fun discoverInit() {
        if (discovered) return
        discovered = true
        tile.setBlock(Blocks.stone)//remove ore
        tile.setFloor(floor)
        tile.setOverlay(overlay)
        tile.setAir()
    }

    fun discover(): Boolean {
        if (discovered) return false
        discovered = true
        tile.setFloorNet(floor, overlay)
        if (tile.block() == Blocks.air)
            tile.removeNet()
        if (floor.isDeep)//auto discover
            repeat(4) {
                val tile = tile.nearby(it) ?: return@repeat
                discoverQueue.add(myTiles[tile.array()])
            }
        return true
    }

    fun unDiscover(): Boolean {
        if (!discovered) return false
        discovered = false
        tile.setBlock(Blocks.stone)//remove ore
        tile.setFloorNet(waterFloor, Blocks.air)
        tile.removeNet()
        return true
    }
}

val discoverQueue = mutableListOf<IslandTile>()
