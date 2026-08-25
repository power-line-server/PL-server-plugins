@file:Depends("coreLibrary/lang", "多语言支持")

package wayzer.ext

import arc.Core
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import coreMindustry.lib.NotForClient
import mindustry.net.Administration.Config

name = "原版命令本地化"

// ===== 原版命令描述翻译 key 表 =====
// ServerControl.java 注册的服务端命令 + NetServer.java registerCommands 注册的客户端命令
// 仅翻译未被插件覆盖的原版命令; 被插件覆盖的(host/maps/status/gameover/js/help/vote/votekick)已是中文或已隐藏
// 值为 {tr key} 模板, 渲染时按 receiver.lang 查找 bundle 翻译
val vanillaDesc = mapOf(
    "version" to "{tr vanilla.command.version.desc}",
    "exit" to "{tr vanilla.command.exit.desc}",
    "stop" to "{tr vanilla.command.stop.desc}",
    "reloadassets" to "{tr vanilla.command.reloadassets.desc}",
    "reloadmaps" to "{tr vanilla.command.reloadmaps.desc}",
    "mods" to "{tr vanilla.command.mods.desc}",
    "mod" to "{tr vanilla.command.mod.desc}",
    "say" to "{tr vanilla.command.say.desc}",
    "pause" to "{tr vanilla.command.pause.desc}",
    "rules" to "{tr vanilla.command.rules.desc}",
    "dumpsettings" to "{tr vanilla.command.dumpsettings.desc}",
    "fillitems" to "{tr vanilla.command.fillitems.desc}",
    "playerlimit" to "{tr vanilla.command.playerlimit.desc}",
    "config" to "{tr vanilla.command.config.desc}",
    "subnet-ban" to "{tr vanilla.command.subnet-ban.desc}",
    "name-ban" to "{tr vanilla.command.name-ban.desc}",
    "whitelist" to "{tr vanilla.command.whitelist.desc}",
    "shuffle" to "{tr vanilla.command.shuffle.desc}",
    "nextmap" to "{tr vanilla.command.nextmap.desc}",
    "kick" to "{tr vanilla.command.kick.desc}",
    "ban" to "{tr vanilla.command.ban.desc}",
    "bans" to "{tr vanilla.command.bans.desc}",
    "unban" to "{tr vanilla.command.unban.desc}",
    "pardon" to "{tr vanilla.command.pardon.desc}",
    "admin" to "{tr vanilla.command.admin.desc}",
    "admins" to "{tr vanilla.command.admins.desc}",
    "players" to "{tr vanilla.command.players.desc}",
    "runwave" to "{tr vanilla.command.runwave.desc}",
    "loadautosave" to "{tr vanilla.command.loadautosave.desc}",
    "load" to "{tr vanilla.command.load.desc}",
    "save" to "{tr vanilla.command.save.desc}",
    "saves" to "{tr vanilla.command.saves.desc}",
    "info" to "{tr vanilla.command.info.desc}",
    "search" to "{tr vanilla.command.search.desc}",
    "gc" to "{tr vanilla.command.gc.desc}",
    "yes" to "{tr vanilla.command.yes.desc}",
    "dos-ban" to "{tr vanilla.command.dos-ban.desc}",
    // 客户端命令 (NetServer.registerCommands)
    // 注意: /t 被 wayzer/pvp/pvpChat.kts 覆盖为 PVP 公开聊天 (command.t.desc), 不在此翻译
    "a" to "{tr vanilla.command.a.desc}",
    "sync" to "{tr vanilla.command.sync.desc}"
)

// ===== Config.all 配置项描述翻译 key 表 (Administration.java Config 枚举) =====
// 值为 {tr key} 模板, 渲染时按 receiver.lang 查找 bundle 翻译
val configDesc = mapOf(
    "name" to "{tr vanilla.config.name.desc}",
    "desc" to "{tr vanilla.config.desc.desc}",
    "port" to "{tr vanilla.config.port.desc}",
    "autoUpdate" to "{tr vanilla.config.autoUpdate.desc}",
    "showConnectMessages" to "{tr vanilla.config.showConnectMessages.desc}",
    "enableVotekick" to "{tr vanilla.config.enableVotekick.desc}",
    "startCommands" to "{tr vanilla.config.startCommands.desc}",
    "logging" to "{tr vanilla.config.logging.desc}",
    "strict" to "{tr vanilla.config.strict.desc}",
    "antiSpam" to "{tr vanilla.config.antiSpam.desc}",
    "interactRateWindow" to "{tr vanilla.config.interactRateWindow.desc}",
    "interactRateLimit" to "{tr vanilla.config.interactRateLimit.desc}",
    "interactRateKick" to "{tr vanilla.config.interactRateKick.desc}",
    "messageRateLimit" to "{tr vanilla.config.messageRateLimit.desc}",
    "messageSpamKick" to "{tr vanilla.config.messageSpamKick.desc}",
    "packetSpamLimit" to "{tr vanilla.config.packetSpamLimit.desc}",
    "uuidChangeLimit" to "{tr vanilla.config.uuidChangeLimit.desc}",
    "uuidChangeTimePeriod" to "{tr vanilla.config.uuidChangeTimePeriod.desc}",
    "chatSpamLimit" to "{tr vanilla.config.chatSpamLimit.desc}",
    "socketInput" to "{tr vanilla.config.socketInput.desc}",
    "socketInputPort" to "{tr vanilla.config.socketInputPort.desc}",
    "socketInputAddress" to "{tr vanilla.config.socketInputAddress.desc}",
    "allowCustomClients" to "{tr vanilla.config.allowCustomClients.desc}",
    "whitelist" to "{tr vanilla.config.whitelist.desc}",
    "motd" to "{tr vanilla.config.motd.desc}",
    "autosave" to "{tr vanilla.config.autosave.desc}",
    "autosaveAmount" to "{tr vanilla.config.autosaveAmount.desc}",
    "autosaveSpacing" to "{tr vanilla.config.autosaveSpacing.desc}",
    "debug" to "{tr vanilla.config.debug.desc}",
    "snapshotInterval" to "{tr vanilla.config.snapshotInterval.desc}",
    "autoPause" to "{tr vanilla.config.autoPause.desc}",
    "roundExtraTime" to "{tr vanilla.config.roundExtraTime.desc}",
    "maxLogLength" to "{tr vanilla.config.maxLogLength.desc}",
    "logCommands" to "{tr vanilla.config.logCommands.desc}"
)

// ===== 包装原版命令描述 =====
// 必须在 onEnable 中执行: coreMindustry/module.kts 的 onEnable 会调用 RootCommands.hookGameHandler()
// 触发 RootCommands.init 设置 subCommandOverwrite, wayzer 模块在 coreMindustry 之后启用
private var oldOverwrite: ((Map<String, CommandInfo>) -> Map<String, CommandInfo>)? = null

onEnable {
    oldOverwrite = Commands.Root.subCommandOverwrite
    Commands.Root.subCommandOverwrite = { parent ->
        val original = oldOverwrite?.invoke(parent) ?: parent
        original.mapValues { (name, cmd) ->
            val tr = vanillaDesc[name]
            if (tr != null && cmd.script == null) {
                // 创建新 CommandInfo, 描述用 {tr key} 模板, body 委托给原命令
                val wrapped = CommandInfo(null, cmd.name, tr.with(), cmd.aliases)
                wrapped.usage = cmd.usage
                // 复制 attrs (ClientOnly/NotForClient/Permission 等), 保持原有过滤行为
                cmd.attrs.forEach { wrapped.attr(it) }
                wrapped.body {
                    // yes 特判: 插件框架替换了原版命令处理器(MyCommandHandler 恒返回 valid),
                    // 原版 ServerControl 的 suggested 永远不会被填充, 委托原版 handler 只会
                    // 输出硬编码英文 "There is nothing to say to yes to.". 改走中文语言包回复.
                    if (name == "yes") {
                        reply("{tr vanilla.command.yes.reply}".with())
                    } else {
                        // 委托给原命令执行(原命令会再次检查 attrs, 结果一致, 仅略微低效)
                        cmd.handle()
                    }
                }
                wrapped
            } else cmd
        }
    }
}

onDisable {
    Commands.Root.subCommandOverwrite = oldOverwrite
}

// ===== 重写 config 命令: {tr key} 描述 + {tr key} 配置项描述 + {tr key} 回复 =====
command("config", "{tr vanilla.command.config.desc}".with()) {
    usage = "[[name] [[value...]"
    attr(NotForClient) // 原版 config 是终端命令, 保持终端专用
    body {
        if (arg.isEmpty()) {
            // 列出所有配置项
            val sb = StringBuilder()
            sb.appendLine("{tr vanilla.config.reply.listTitle}".with("receiver" to receiver).toString())
            for (c in Config.all) {
                sb.appendLine("&lk| &ly{name}&fr: &lc{value}".with(
                    "receiver" to receiver, "name" to c.name, "value" to c.get()
                ).toString())
                val descKey = configDesc[c.name]
                val descVar = (descKey ?: c.description).with("receiver" to receiver)
                sb.appendLine("&lk| | &lw{desc}".with("receiver" to receiver, "desc" to descVar).toString())
                sb.appendLine("&lk|")
            }
            returnReply(sb.toString().with())
        }
        val c = Config.all.find { it.name.equals(arg[0], ignoreCase = true) }
        if (c == null) {
            returnReply("{tr vanilla.config.reply.unknown}".with("name" to arg[0]))
        } else if (arg.size == 1) {
            // 显示单个配置项
            returnReply("{tr vanilla.config.reply.current}".with(
                "name" to c.name, "value" to c.get()
            ))
        } else {
            // 设置配置项 (字符串值可能含空格,如服务器名,需拼接剩余参数)
            val value = arg.drop(1).joinToString(" ")
            when {
                arg[1] == "default" -> c.set(c.defaultValue)
                c.isBool() -> c.set(arg[1] == "on" || arg[1] == "true")
                c.isNum() -> {
                    try {
                        c.set(arg[1].toInt())
                    } catch (e: NumberFormatException) {
                        returnReply("{tr vanilla.config.reply.invalidNumber}".with("value" to value))
                    }
                }
                c.isString() -> c.set(value.replace("\\n", "\n"))
            }
            Core.settings.forceSave()
            returnReply("{tr vanilla.config.reply.set}".with(
                "name" to c.name, "value" to c.get()
            ))
        }
    }
}
