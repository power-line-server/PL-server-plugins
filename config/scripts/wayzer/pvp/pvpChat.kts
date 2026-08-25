package wayzer.pvp

import arc.util.Log
import mindustry.game.Team
import mindustry.net.Administration


fun message(from: Player, msg: String, teamMsg: Boolean) {
    for (p in Groups.player) {
        val prefix: String = when {
            !teamMsg && from.team().id == 255 -> "{tr pvpChat.prefix.publicSpectator}".with("receiver" to p).toString()
            !teamMsg -> "{tr pvpChat.prefix.publicTeam}".with("receiver" to p, "team" to from.team()).toString()
            from.team() == p.team() && from.team().id == 255 -> "{tr pvpChat.prefix.spectator}".with("receiver" to p).toString()
            from.team() == p.team() -> "{tr pvpChat.prefix.team}".with("receiver" to p).toString()
            p.team() == Team.all[255] -> "{tr pvpChat.prefix.teamOther}".with("receiver" to p, "team" to from.team()).toString()
            else -> continue
        }
        p.sendMessage(prefix + netServer.chatFormatter.format(from, msg), from, msg)
    }
}

val filter = Administration.ChatFilter { p, t ->
    if (!state.rules.pvp) return@ChatFilter t
    message(p, t, true)
    Log.info("&fi@: @", "&lc" + p.name, "&lw$t")
    null
}

onEnable {
    netServer.admins.chatFilters.add(filter)
    onDisable {
        netServer.admins.chatFilters.remove(filter)
    }
}

command("t", "{tr command.t.desc}".with()) {
    type = CommandType.Client
    body {
        val msg = arg.joinToString(" ")
        message(player!!, msg, false)
    }
}