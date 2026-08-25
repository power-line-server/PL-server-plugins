@file:Depends("wayzer/user/shortID")

package wayzer.user.ext

import arc.util.Strings
import mindustry.net.Administration

val regex = Regex("@([a-zA-Z0-9]{3,10})")

val filter = Administration.ChatFilter { p, msg ->
    val players = mutableListOf<Player>()
    msg.replace(regex) { match ->
        val id = match.groupValues[1]
        val name = if (id.equals("all", ignoreCase = true) && runBlocking { p.hasPermission("$dotId.pingAll") }) {
            players.addAll(Groups.player)
            "{tr ext_chatPing.allName}".with("receiver" to p).toString()
        } else {
            val player = PlayerData.findByShortId(id)?.player ?: return@replace match.value
            players.add(player)
            Strings.stripColors(player.name)
        }
        " [gold]@$name[] "
    }.also { newMsg ->
        if (players.isNotEmpty()) {
            players.forEach {
                Call.announce(it.con, "{tr ext_chatPing.announce}".with("receiver" to it, "msg" to newMsg).toString())
            }
        }
    }
}

onEnable { netServer.admins.chatFilters.add(filter) }
onDisable { netServer.admins.chatFilters.remove(filter) }
