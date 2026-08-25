package wayzer.cmds

import arc.graphics.Colors

command("showColor", "{tr command.showColor.desc}".with()) {
    body {
        reply(Colors.getColors().joinToString("[],") { "[#${it.value}]${it.key}" }.with())
    }
}

command("dosBanClear", "{tr command.dosBanClear.desc}".with()) {
    permission = dotId
    body {
        reply("{tr helpfulCmd.reply.banList}".with("list" to netServer.admins.dosBlacklist))
        netServer.admins.dosBlacklist.clear()
        reply("{tr helpfulCmd.reply.cleared}".with())
    }
}
