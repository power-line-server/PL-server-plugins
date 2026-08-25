package wayzer.cmds

command("status", "{tr command.status.desc}".with()) {
    aliases = listOf("服务器状态")
    body {
        reply("{tr serverStatus.reply.status}".with())
    }
}
