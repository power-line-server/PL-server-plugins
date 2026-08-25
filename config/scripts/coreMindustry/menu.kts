package coreMindustry

import coreLibrary.lib.Commands.Hidden


listen<EventType.MenuOptionChooseEvent> {
    MenuChooseEvent(it.player, it.menuId, it.option).launchEmit(coroutineContext + Dispatchers.game) { e ->
        if (!e.received && it.menuId < 0)
            Call.hideFollowUpMenu(e.player.con, e.menuId)
    }
}

onEnable {
    val bak = Commands.helpOverwrite
    onDisable { Commands.helpOverwrite = bak }
    Commands.helpOverwrite = impl@{ cmds, showAll, page ->
        val player = player ?: return@impl

        var commands = cmds.subCommands().values.toSet().sortedBy { it.name }
        if (!showAll) commands = commands.filter { info ->
            info.attrs.all { it !is Hidden || it.visible() }
        }
        MenuV2(player) {
            title = if (prefix.isEmpty()) "{tr command.help.title}".with("receiver" to player).toString()
            else "{tr command.help.titlePrefix}".with("receiver" to player, "prefix" to prefix).toString()
            msg = "{tr coreMenu.help.msg}".with("receiver" to player).toString()
            renderPaged(commands, page) {
                option(buildString {
                    append("[lightgray]${prefix}[gold]${it.name}")
                    if (it.aliases.isNotEmpty())
                        append("[gray](${it.aliases.joinToString()})")
                    appendLine(" [lightgray]${it.usage.with().toPlayer(player)}")
                    append("[sky]${it.description.toPlayer(player)}")
                    if (showAll) {
                        it.script?.let { append(" | ${it.id}") }
                        if (it.permission.isNotBlank()) append(" | ${it.permission}")
                    }
                }) {
                    shortcut = true
                    arg = listOf(it.name)
                    reply("{tr coreMenu.help.quickInput}".with("command" to (prefix + it.name)))
                    cmds.handle()
                }
            }
        }.send().awaitWithTimeout()
        CommandInfo.Return()
    }
}
