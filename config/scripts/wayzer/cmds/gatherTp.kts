@file:Depends("wayzer/user/ext/skills", "Gather也算技能")

package wayzer.cmds

import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.entities.Units
import mindustry.gen.Call
import mindustry.gen.Unit
import mindustry.world.Tile
import wayzer.user.ext.SkillCooldown
import wayzer.user.ext.SkillNoPvp
import wayzer.user.ext.SkillPrecheck
import wayzer.user.ext.broadcastSkill
import wayzer.user.ext.player
import wayzer.user.ext.skillBody
import java.time.Duration
import java.time.Instant

var lastPos: Tile? = null
var lastTime: Instant = Instant.MIN

command("gather", "{tr command.gather.desc}".with()) {
    usage = "{tr usage.gatherTp}"
    aliases = listOf("集合")
    attr(SkillPrecheck)
    attr(SkillNoPvp)
    attr(SkillCooldown(30_000))
    requirePermission("wayzer.ext.gather")
    skillBody {
        if (Duration.between(lastTime, Instant.now()) < Duration.ofSeconds(10)) {
            returnReply("{tr gatherTp.reply.cooldown}".with())
        }
        val message = "[white]\"${arg.joinToString(" ")}[white]\""
        val tile = player.tileOn() ?: returnReply("{tr gatherTp.reply.mapOnly}".with())
        lastPos = tile
        lastTime = Instant.now()
        broadcastSkill("{tr gatherTp.skillName}".with("x" to tile.x, "y" to tile.y).toString())
        broadcast("{tr gatherTp.broadcast.go}".with("message" to message), quite = true)
    }
}

command("tp", "{tr command.tp.desc}".with()) {
    attr(ClientOnly)
    requirePermission("wayzer.ext.tp")
    body {
        val player = player!!
        player.unit()?.apply {
            set(player.mouseX, player.mouseY)
            snapInterpolation()
        }
    }
}

fun check(unit: Unit, tile: Tile): Boolean {
    if (unit.type.flying) return true
    return unit.canPass(tile.x.toInt(), tile.y.toInt()) &&
            Units.count(tile.worldx(), tile.worldy(), unit.physicSize()) { it.isGrounded && it.hitSize > 14.0F } > 0
}
listen<EventType.PlayerChatEvent> {
    val tile = lastPos ?: return@listen
    if (it.message.equals("go", true)) {
        it.player.unit()?.apply {
            if (!check(this, tile)) {
                it.player.sendMessage("{tr gatherTp.reply.unsafeTarget}".with())
                return@listen
            }
            set(tile)
            snapInterpolation()
            Call.setCameraPosition(it.player.con, tile.worldx(), tile.worldy())
        }
    }
}

listen<EventType.ResetEvent> {
    lastPos = null
}