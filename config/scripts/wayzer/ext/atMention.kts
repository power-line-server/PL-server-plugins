@file:Depends("wayzer/user/lang", "PlayerData.lang for localization")
@file:Depends("wayzer/user/shortID", "Short ID lookup")

package wayzer.ext

import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.PlayerData

// @提及: @all/<uid>/<name> <message>
// 向目标发送屏幕中央 announce(等同 world processor Flush Message announce)。
// 注意: PlayerChatEvent 不可取消, @ 消息仍会出现在普通聊天中。
listen<EventType.PlayerChatEvent> {
    val msg = it.message
    if (!msg.startsWith("@")) return@listen

    val parts = msg.removePrefix("@").trim().split(Regex("\\s+"), 2)
    if (parts.isEmpty() || parts[0].isEmpty()) return@listen
    val target = parts[0]
    val message = parts.getOrNull(1) ?: ""

    val sender = it.player
    val targets: List<Player> = when {
        target.equals("all", true) -> Groups.player.toList()
        target.toIntOrNull() != null ->
            PlayerData.findByShortId(target)?.player?.let { listOf(it) } ?: emptyList()
        else ->
            Groups.player.find { p -> p.name.replace(" ", "").equals(target, true) }?.let { listOf(it) } ?: emptyList()
    }

    if (targets.isEmpty()) {
        sender.sendMessage("{tr atMention.reply.notFound}".with("receiver" to sender, "target" to target).toString())
        return@listen
    }

    // announce 文本按每个目标自己的语言渲染
    targets.forEach { p ->
        val localizedAnnounce = "{tr atMention.announce}".with(
            "receiver" to p,
            "sender" to sender,
            "message" to message
        ).toString()
        Call.announce(p.con, localizedAnnounce)
    }

    sender.sendMessage("{tr atMention.reply.success}".with("receiver" to sender, "count" to targets.size).toString())
}
