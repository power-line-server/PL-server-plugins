@file:Depends("coreMindustry/menu")

package wayzer

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.scriptAgent.events.ScriptDisableEvent
import java.time.Duration
import java.time.Instant

name = "投票服务"

listen<EventType.PlayerJoin> {
    VoteEvent.active.get()?.vote(it.player, VoteEvent.Action.Join)
    VoteEvent.activeText.get()?.vote(it.player, VoteEvent.Action.Join)
    VoteEvent.activeMusic.get()?.vote(it.player, VoteEvent.Action.Join)
}

listen<EventType.PlayerLeave> {
    VoteEvent.lastAction = System.currentTimeMillis()
    VoteEvent.active.get()?.vote(it.player, VoteEvent.Action.Quit)
    VoteEvent.activeText.get()?.vote(it.player, VoteEvent.Action.Quit)
    VoteEvent.activeMusic.get()?.vote(it.player, VoteEvent.Action.Quit)
}

listen<EventType.PlayerChatEvent> {
    val msg = it.message.lowercase()
    // 音乐频道: m1/my, m0/mn, m.
    if (msg.startsWith("m")) {
        val action = when (msg.substring(1)) {
            "1", "y" -> VoteEvent.Action.Agree
            "0", "n" -> VoteEvent.Action.Disagree
            "." -> VoteEvent.Action.Ignore
            else -> return@listen
        }
        (VoteEvent.activeMusic.get() ?: return@listen it.player.sendMessage("{tr vote.reply.musicVoteEnded}".with()))
            .vote(it.player, action)
        return@listen
    }
    // 文本频道: t1/ty, t0/tn, t.
    if (msg.startsWith("t")) {
        val action = when (msg.substring(1)) {
            "1", "y" -> VoteEvent.Action.Agree
            "0", "n" -> VoteEvent.Action.Disagree
            "." -> VoteEvent.Action.Ignore
            else -> return@listen
        }
        (VoteEvent.activeText.get() ?: return@listen it.player.sendMessage("{tr vote.reply.textVoteEnded}".with()))
            .vote(it.player, action)
        return@listen
    }
    // 主频道: 1/y, 0/n, .
    val action = when (msg) {
        "1", "y" -> VoteEvent.Action.Agree
        "0", "n" -> VoteEvent.Action.Disagree
        "." -> VoteEvent.Action.Ignore
        else -> return@listen
    }
    (VoteEvent.active.get() ?: return@listen it.player.sendMessage("{tr vote.reply.voteEnded}".with()))
        .vote(it.player, action)
}

listen<EventType.ResetEvent> {
    VoteEvent.coolDowns.clear()
    VoteEvent.consecutiveFailures.clear()
}

registerVar("scoreboard.ext.vote", "投票状态显示", DynamicVar {
    VoteEvent.active.get()?.run {
        "{tr vote.scoreboard.vote}".with(
            "desc" to voteDesc,
            "status" to status(),
            "left" to Duration.between(Instant.now(), endTime)
        )
    }
})

registerVar("scoreboard.ext.voteText", "文本投票状态显示", DynamicVar {
    VoteEvent.activeText.get()?.run {
        "{tr vote.scoreboard.voteText}".with(
            "desc" to voteDesc,
            "status" to status(),
            "left" to Duration.between(Instant.now(), endTime)
        )
    }
})

registerVar("scoreboard.ext.voteMusic", "音乐投票状态显示", DynamicVar {
    VoteEvent.activeMusic.get()?.run {
        "{tr vote.scoreboard.voteMusic}".with(
            "desc" to voteDesc,
            "status" to status(),
            "left" to Duration.between(Instant.now(), endTime)
        )
    }
})

command("vote", "{tr command.vote.desc}".with()) {
    type = CommandType.Client
    aliases = listOf("投票")
    body(VoteEvent.VoteCommands)
}

onEnable {
    // 保留 /vote help 子命令(无参数时 body(VoteEvent.VoteCommands) 显示子命令列表)
    // 注意: 不能移除 help, 否则 /vote 无参数无任何提示
}

command("voteadmin", "{tr command.voteadmin.desc}".with()) {
    aliases = listOf("va")
    // 不设 type: 默认 Both, 玩家(需 wayzer.admin 权限)与终端(控制台自动有权限)均可执行
    permission = "wayzer.admin"
    usage = "<menu|text|music>? <y|n>"
    body {
        // 频道选择: 可选 menu/text/music(支持中文别名), 缺省按 主->文本->音乐 优先级自动找
        val rawArgs = arg.map { it.lowercase() }
        val channelArg = rawArgs.firstOrNull()?.let {
            when (it) {
                "menu", "主", "主频道" -> "menu"
                "text", "文本", "文字", "文本频道" -> "text"
                "music", "音乐", "音乐频道" -> "music"
                else -> null
            }
        }
        val rest = if (channelArg != null) rawArgs.drop(1) else rawArgs
        val actionArg = rest.firstOrNull()
        val operatorName = player?.name ?: "终端"

        // 玩家无参数时弹出菜单选频道+操作; 终端或带参数走原指令逻辑
        val p = player
        if (p != null && channelArg == null && actionArg == null) {
            coreMindustry.MenuV2(p) {
                title = "{tr vote.menu.va.title}".with("receiver" to player).toString()
                msg = "{tr vote.menu.va.msg}".with("receiver" to player).toString()
                // 频道+操作矩阵
                fun doVote(ch: String?, act: String) {
                    val vote = when (ch) {
                        "menu" -> VoteEvent.active.get()
                        "text" -> VoteEvent.activeText.get()
                        "music" -> VoteEvent.activeMusic.get()
                        null -> VoteEvent.active.get() ?: VoteEvent.activeText.get() ?: VoteEvent.activeMusic.get()
                        else -> null
                    }
                    if (vote == null) {
                        reply("{tr vote.reply.noActiveVote}".with())
                        return
                    }
                    vote.succeed = act == "y"
                    vote.mainJob.cancel()
                    broadcast(
                        if (act == "y") "{tr vote.broadcast.adminForcePass}".with("operator" to operatorName)
                        else "{tr vote.broadcast.adminForceReject}".with("operator" to operatorName)
                    )
                }
                // 主频道
                option("{tr vote.menu.va.pass.main}".with("receiver" to p).toString()) { doVote(null, "y") }
                option("{tr vote.menu.va.reject.main}".with("receiver" to p).toString()) { doVote(null, "n") }
                option("{tr vote.menu.va.view.main}".with("receiver" to p).toString()) {}
                // 文本频道
                option("{tr vote.menu.va.pass.text}".with("receiver" to p).toString()) { doVote("text", "y") }
                option("{tr vote.menu.va.reject.text}".with("receiver" to p).toString()) { doVote("text", "n") }
                option("{tr vote.menu.va.view.text}".with("receiver" to p).toString()) {}
                // 音乐频道
                option("{tr vote.menu.va.pass.music}".with("receiver" to p).toString()) { doVote("music", "y") }
                option("{tr vote.menu.va.reject.music}".with("receiver" to p).toString()) { doVote("music", "n") }
                option("{tr vote.menu.va.view.music}".with("receiver" to p).toString()) {}
            }.send().awaitWithTimeout()
            return@body
        }

        val vote = when (channelArg) {
            "menu" -> VoteEvent.active.get()
            "text" -> VoteEvent.activeText.get()
            "music" -> VoteEvent.activeMusic.get()
            null -> VoteEvent.active.get() ?: VoteEvent.activeText.get() ?: VoteEvent.activeMusic.get()
            else -> null
        } ?: returnReply("{tr vote.reply.noActiveVote}".with())

        when (actionArg) {
            "y", "1" -> {
                vote.succeed = true
                vote.mainJob.cancel()
                broadcast("{tr vote.broadcast.adminForcePass}".with("operator" to operatorName))
            }
            "n", "0" -> {
                vote.succeed = false
                vote.mainJob.cancel()
                broadcast("{tr vote.broadcast.adminForceReject}".with("operator" to operatorName))
            }
            else -> returnReply("{tr vote.reply.voteadminUsage}".with())
        }
    }
}

listenTo<ScriptDisableEvent> {
    VoteEvent.VoteCommands.removeAll(script)
}
