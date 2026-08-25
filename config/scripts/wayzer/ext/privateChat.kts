@file:Depends("coreMindustry/menu", "菜单选人")
@file:Depends("coreMindustry/util/textInput", "输入消息")

package wayzer.ext

import coreMindustry.PagedMenuBuilder
import coreMindustry.util.textInput

command("pm", "{tr command.pm.desc}".with()){
    aliases = listOf("私聊")
    usage = "{tr usage.pm}"
    body {
        // 确保命令由玩家执行
        val sender = player ?: returnReply("{tr privateChat.reply.consoleOnly}".with())

        // 获取目标玩家和消息内容
        // 支持: /pm <name> <message...>  或  /pm <name with spaces> <message...>
        // 策略: 先尝试 arg[0] 精确匹配,失败时逐步拼接 arg[0..i] 作为玩家名,arg[i+1..] 作为消息
        var target: Player? = null
        var message: String? = null
        if (arg.isNotEmpty()) {
            // 先尝试第一个参数精确匹配
            target = Groups.player.find { it.name.equals(arg[0], true) }
            if (target != null) {
                message = if (arg.size > 1) arg.drop(1).joinToString(" ") else null
            } else if (arg.size > 1) {
                // 第一个参数匹配失败,尝试拼接前 i 个参数作为玩家名
                for (i in 1 until arg.size) {
                    val possibleName = arg.subList(0, i + 1).joinToString(" ")
                    val matched = Groups.player.find { it.name.equals(possibleName, true) }
                    if (matched != null) {
                        target = matched
                        message = if (i + 1 < arg.size) arg.subList(i + 1, arg.size).joinToString(" ") else null
                        break
                    }
                }
            }
            if (target == null) {
                returnReply("{tr privateChat.reply.playerNotFound}".with("arg" to arg[0]))
            }
        } else {
            // 菜单选择玩家
            var result: Player? = null
            PagedMenuBuilder(Groups.player.toList()) {
                option(it.name) { result = it }
            }.apply {
                title = "{tr privateChat.menu.selectTarget.title}".with("receiver" to sender).toString()
                sendTo(sender, 60_000)
            }
            target = result ?: returnReply("{tr privateChat.reply.cancelled}".with())
        }

        // 若未通过命令行提供消息,则通过 textInput 获取
        if (message == null) {
            message = textInput(sender, "{tr privateChat.reply.inputMessage}".with("receiver" to sender).toString())
                ?: returnReply("{tr privateChat.reply.cancelled}".with())
        }

        // 发送私聊消息
        target.sendMessage("{tr privateChat.message.toTarget}".with("sender" to sender, "message" to message))
        reply("{tr privateChat.message.toSender}".with("target" to target, "message" to message))
    }
}