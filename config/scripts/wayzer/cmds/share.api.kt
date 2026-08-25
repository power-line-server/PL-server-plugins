package wayzer.cmds

import cf.wayzer.placehold.VarString
import cf.wayzer.scriptAgent.define.Script
import coreLibrary.lib.CommandContext
import coreLibrary.lib.returnReply
import coreLibrary.lib.with
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.player
import coreMindustry.util.textInput
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.PlayerData

// 读取并消耗第一个参数
fun CommandContext.readArg(): String? = arg.firstOrNull().also { arg = arg.drop(1) }

// 选择目标玩家: 优先参数解析, 回退到菜单选择
suspend fun CommandContext.getTarget(): Player {
    val id = readArg() ?: player?.let { player ->
        var result: Player? = null
        PagedMenuBuilder(Groups.player.toList()) {
            option(it.name) { result = it }
        }.apply {
            title = "{tr voteKick.menu.selectTarget.title}".with("receiver" to player).toString()
            sendTo(player, 60_000)
        }
        if (result != null) return result!!
        null
    } ?: returnReply("{tr voteKick.reply.needPlayerName}".with())
    if (id.startsWith("#"))
        Groups.player.getByID(id.substring(1).toIntOrNull() ?: 0)?.let { return it }
    //Try find by name
    val allPlayers = Groups.player.associateBy { it.name.replace(" ", "") }
    for (addLen in 0..arg.size) {
        val argAsName = id + arg.take(addLen).joinToString("")
        val found = allPlayers[argAsName] ?: continue
        arg = arg.drop(addLen)
        return found
    }
    //find by uuid
    return PlayerData.findByShortId(id)?.player
        ?: returnReply("{tr voteKick.reply.invalidPlayerName}".with())
}

// 获取输入: 优先用剩余参数, 回退到玩家文本输入框
context(_: Script)
suspend fun CommandContext.getInput(name: String, whenEmpty: VarString): String {
    return arg.takeIf { it.isNotEmpty() }?.joinToString(" ")
        ?: player?.let { p ->
            (textInput(p, "{tr voteKick.input.prompt}".with("receiver" to p, "name" to name).toString()) ?: returnReply("{tr voteKick.reply.inputCancelled}".with()))
                .takeIf { it.isNotBlank() }
        } ?: returnReply(whenEmpty)
}
