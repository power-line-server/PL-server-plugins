package coreLibrary.commands

import cf.wayzer.placehold.PlaceHoldApi.with

val configCommands = Commands()
command("config", "{tr command.config.desc}".with(), commands = Commands.controlCommand) {
    usage = "[[help/arg...]"
    requirePermission("scriptAgent.$name")
    onComplete {
        try {
            configCommands.handle()
        } catch (_: CommandInfo.Return) {
        }
        onCompleteArg(0) {
            remove("<key>")
            configCommands.getSub("get")?.handle()
        }
    }
    body {
        if (arg.firstOrNull() in ConfigBuilder.all) {
            configCommands.getSub("<key>")?.handle()
            return@body
        }
        configCommands.handle()
    }
}

command("<key>", "{tr command.config.key.desc}".with(), commands = configCommands) {
    usage = "[[value]"
    body {
        when (arg.size) {
            0 -> returnReply("{tr config.reply.notCallable}".with())
            1 -> configCommands.getSub("get")?.handle()
            else -> configCommands.getSub("set")?.handle()
        }
    }
}
command("list", "{tr command.config.list.desc}".with(), commands = configCommands) {
    usage = "[[page]"
    body {
        val page = arg.getOrNull(0)?.toIntOrNull() ?: 1
        reply(menu("{tr config.menu.title}".with(), ConfigBuilder.all.values.sortedBy { it.path }, page, 15) {
            "[green]{key} [blue]{desc}".with(
                "key" to it.path,
                "desc" to (it.desc.firstOrNull() ?: "")
            )
        })
    }
}
command("reload", "{tr command.config.reload.desc}".with(), commands = configCommands) {
    requirePermission("scriptAgent.config.$name")
    body {
        ConfigBuilder.reloadFile()
        reply("{tr config.reply.reloadSuccess}".with())
    }
}

@CommandInfo.CommandBuilder
inline fun CommandInfo.subCommand(
    usage: String,
    crossinline block: suspend CommandContext.(ConfigBuilder.ConfigKey<*>) -> Unit
) {
    this.usage = "<key> $usage"
    onComplete {
        onComplete(0) { ConfigBuilder.all.keys.toList() }
    }
    body {
        val config = arg.firstOrNull()?.let { ConfigBuilder.all[it] } ?: returnReply("{tr config.reply.notFound}".with())
        if (!hasPermission("scriptAgent.config." + config.path))
            returnReply("{tr config.reply.noPermission}".with("config" to config.path))
        block(context, config)
    }
}
command("get", "{tr command.config.get.desc}".with(), commands = configCommands) {
    subCommand("") { config ->
        reply(
            """
                        |[yellow]==== [light_yellow]{tr config.reply.getConfigTitle} {name}[yellow] ====
                        |[purple]{desc|joinLines}
                        |[cyan]{tr config.reply.currentValue} [yellow]{value}
                        |[cyan]{tr config.reply.defaultValue} [yellow]{default}
                        |[yellow]{tr config.reply.usageHint}
                    """.trimMargin().with(
                "name" to config.path, "desc" to config.desc,
                "value" to config.getString(), "default" to config.default,
            )
        )
    }
}
command("set", "{tr command.config.set.desc}".with(), commands = configCommands) {
    subCommand("<value>") { config ->
        if (arg.size <= 1) returnReply("{tr config.reply.setValueMissing}".with())
        val value = arg.subList(1, arg.size).joinToString(" ")
        reply("{tr config.reply.setSuccess}".with("value" to config.setString(value)))
    }
}
command("reset", "{tr command.config.reset.desc}".with(), commands = configCommands) {
    subCommand("") { config ->
        config.reset()
        reply("{tr config.reply.resetSuccess}".with("value" to config.getString()))
    }
}
command("write", "{tr command.config.write.desc}".with(), commands = configCommands) {
    subCommand("") { config ->
        if (config.get() != config.default)
            config.writeDefault()
        reply("{tr config.reply.writeSuccess}".with())
    }
}
