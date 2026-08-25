@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票实现")

package wayzer.cmds

import arc.util.Strings.stripColors
import arc.util.Strings.truncate
import wayzer.*

fun voteMap(player: Player, map: MapInfo) {
    launch(Dispatchers.game) {
        val event = VoteEvent(
            this, player,
            "{tr voteMap.voteDesc.changeMap}".with("nextMap" to map),
            extDesc = "{tr voteMap.extDesc.changeMap}".with(
                "receiver" to player,
                "author" to stripColors(map.author),
                "desc" to truncate(stripColors(map.description), 100, "...")
            ).toString(),
            supportSingle = true
        )
        if (!event.awaitResult()) return@launch
        broadcast("{tr voteMap.broadcast.loading}".with())
        if (MapManager.loadMapSync(map))
            broadcast("{tr voteMap.broadcast.success}".with())
    }
}
export(::voteMap)

fun VoteService.register() {
    addSubVote("{tr voteMap.subVote.map.desc}", "{tr voteMap.subVote.map.usage}", "map", "换图") {
        if (arg.isEmpty())
            returnReply("{tr voteMap.reply.needMapId}".with())
        val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("{tr voteMap.reply.invalidMapId}".with())
        voteMap(player!!, map)
    }
}

onEnable {
    VoteService.register()
}