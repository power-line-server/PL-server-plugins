package coreMindustry

import coreLibrary.lib.Commands.Hidden
import coreLibrary.lib.config

/** 菜单协议开关: 关闭后回退旧 Call.menu/Call.followUpMenu(原版已标记 soft-deprecated), 仅作应急 */
val useMenuBuilderProtocol by config.key(
    true, "使用 160 新菜单协议(MenuBuilder)",
    "关闭后回退到旧 Call.menu/Call.followUpMenu(原版已标记 soft-deprecated); 修改后需重载本脚本"
)

// 旧协议(<=159): MenuCallPacket -> MenuOptionChooseEvent
listen<EventType.MenuOptionChooseEvent> {
    MenuChooseEvent(it.player, it.menuId, it.option).launchEmit(coroutineContext + Dispatchers.game) { e ->
        if (!e.received && it.menuId < 0)
            Call.hideFollowUpMenu(e.player.con, e.menuId)
    }
}

// 160 新协议: MenuBuilderCallPacket -> MenuBuilderOptionChooseEvent
// 只接受属于"当前这次发送"的回包(token 匹配): 同 id 菜单被替换时, 旧对话框会回传一个"取消",
// 若不过滤会误触发新菜单的等待(表现为刷新/翻页后菜单立刻消失)
listen<EventType.MenuBuilderOptionChooseEvent> {
    val result = it.result
    if (!MenuProtocol.accept(it.menuId, result.token)) return@listen
    MenuChooseEvent(it.player, it.menuId, MenuProtocol.optionIndex(result))
        .launchEmit(coroutineContext + Dispatchers.game) { e ->
            if (!e.received && e.menuId < 0)
                Call.hideMenuBuilder(e.player.con, e.menuId)
        }
}

onEnable {
    MenuProtocol.enabled = useMenuBuilderProtocol
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
