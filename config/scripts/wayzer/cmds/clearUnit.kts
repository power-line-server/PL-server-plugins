package wayzer.cmds

import mindustry.game.Team

command("clearUnit", "{tr command.clearUnit.desc}".with()) {
    permission = dotId
    usage = "[队伍ID]"
    body {
        val team = arg.firstOrNull()?.toIntOrNull()?.let { Team.all.getOrNull(it) }
        if (arg.isNotEmpty() && team == null) {
            returnReply("{tr clearUnit.reply.invalidTeam}".with())
        }
        val units = Groups.unit.toList().filter { team == null || it.team == team }
        units.forEach { it.kill() }
        broadcast(
            if (team == null) "{tr clearUnit.broadcast.clear}".with()
            else "{tr clearUnit.broadcast.clearTeam}".with("teamId" to team.id)
        )
    }
}