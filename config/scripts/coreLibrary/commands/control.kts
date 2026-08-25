package coreLibrary.commands

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.state.ConditionState
import coreLibrary.lib.ConfigBuilder

suspend inline fun runIgnoreCancel(sync: Boolean, crossinline body: suspend () -> Unit) {
    //Need new Job, as it may restart this script.
    @Suppress("CoroutineContextWithJob")
    val job = launch(Job()) { body() }
    if (sync) job.join()
}

command("scan", "{tr command.scan.desc}".with(), commands = Commands.controlCommand) {
    requirePermission("scriptAgent.control.scan")
    aliases = listOf("扫描")
    body {
        val old = ScriptRegistry.allScripts { true }.toSet()
        ScriptRegistry.scanRoot()
        val now = ScriptRegistry.allScripts { true }.toSet()
        reply(
            "{tr control.reply.scanComplete}".with(
                "count" to (now.size - old.size),
                "added" to (now - old).size,
                "removed" to (old - now).size,
            )
        )
    }
}
command("listFailed", "{tr command.listFailed.desc}".with(), commands = Commands.controlCommand) {
    usage = "[[prefix]"
    requirePermission("scriptAgent.control.list")
    aliases = listOf("fail", "failed")
    onComplete {
        onComplete(0) {
            ScriptRegistry.allScripts().map { it.id.substringBefore(Config.idSeparator) }
                .toSet().sortedBy { it }
        }
    }
    body {
        val prefix = arg.firstOrNull().orEmpty()
        val scripts = ScriptRegistry.allScripts {
            it.id.startsWith(prefix) && !it.ready()
        }
        for (info in scripts) {
            reply(buildString {
                appendLine("[${info.scriptState}] ${info.id}")
                info.lastConditions.filter { it.status != ConditionState.Status.Success }.forEach { c ->
                    c.display().forEach {
                        append("  ")
                        appendLine(it)
                    }
                }
                deleteAt(length - 1)
            }.asPlaceHoldString())
        }
    }
}
command("list", "{tr command.list.desc}".with(), commands = Commands.controlCommand) {
    usage = "[[module]"
    requirePermission("scriptAgent.control.list")
    aliases = listOf("ls", "列出")
    onComplete {
        onComplete(0) {
            ScriptRegistry.allScripts().map { it.id.substringBefore(Config.idSeparator) }
                .toSet().sortedBy { it }
        }
    }
    body {
        val module = arg.getOrNull(0) ?: kotlin.run {
            val counts = ScriptRegistry.allScripts().map { it.id.substringBefore(Config.idSeparator) }
                .groupBy { it }.mapValues { it.value.size }
            val list = counts.entries.sortedBy { it.key }
                .map { "[purple]${it.key.padEnd(20)} [blue]${it.value}" }
            returnReply("{tr control.reply.moduleList}".with("list" to list))
        }
        if (module.equals("fail", true)) returnReply("{tr control.reply.useSaFail}".with())
        val list = ScriptRegistry.allScripts {
            it.id.startsWith(module + Config.idSeparator)
        }.map { script ->
            val color = if (script.ready()) "green" else "red"
            val conditions = script.lastConditions.filter { it.status != ConditionState.Status.Success }
                .joinToString("") { "${it.type}${it.status}" }
            "[$color]${script.id.padEnd(30)}[reset] [${script.scriptState}] $conditions"
        }
        reply(
            "{tr control.reply.scriptList}".with(
                "module" to module, "list" to list
            )
        )
    }
}
command("load", "{tr command.load.desc}".with(), commands = Commands.controlCommand) {
    usage = "<module[[/script]> [[--noCache] [[--noEnable] [[--async]"
    requirePermission("scriptAgent.control.load")
    aliases = listOf("reload", "加载", "重载")
    onComplete {
        onComplete(0) { ScriptRegistry.allScripts { true }.map { it.id } }
    }
    body {
        var noEnable = checkArg("--noEnable")
        val async = checkArg("--async")

        if (arg.isEmpty()) replyUsage()
        val script = ScriptRegistry.getScriptInfo(arg[0])
            ?: returnReply("{tr control.reply.notFound}".with())
        runIgnoreCancel(!async) {
            // reload/load/enable 前先重新读取 config.conf,使手动修改的配置实时生效
            runCatching { ConfigBuilder.reloadFile() }
            ScriptManager.transactionV2 {
                if (script.scriptState.loaded) {
                    reload(script)
                } else if (!noEnable) {
                    enable(script)
                } else {
                    load(script)
                }
            }.printResult()
        }
    }
}
command("compile", "{tr command.compile.desc}".with(), commands = Commands.controlCommand) {
    usage = "<module[[/script]> [[--async]"
    requirePermission("scriptAgent.control.compile")
    aliases = listOf("编译")
    onComplete {
        onComplete(0) { ScriptRegistry.allScripts { true }.map { it.id } }
    }
    body {
        val async = checkArg("--async")

        if (arg.isEmpty()) replyUsage()
        val script = ScriptRegistry.getScriptInfo(arg[0])
            ?: returnReply("{tr control.reply.notFound}".with())
        runIgnoreCancel(!async) {
            ScriptManager.transactionV2 {
                compile(script)
            }.printResult()
        }
    }
}
command("retry", "{tr command.retry.desc}".with(), commands = Commands.controlCommand) {
    usage = "[[--async]"
    requirePermission("scriptAgent.control.retry")
    aliases = listOf("重试")
    body {
        val async = checkArg("--async")
        runIgnoreCancel(!async) {
            ScriptManager.transactionV2 {
                ScriptRegistry.allScripts { !it.ready() }.forEach {
                    compile(it)
                }
            }.printResult()
        }
    }
}
command("enable", "{tr command.enable.desc}".with(), commands = Commands.controlCommand) {
    usage = "<module[[/script]> [[--async]"
    requirePermission("scriptAgent.control.enable")
    aliases = listOf("启用")
    onComplete {
        onComplete(0) { ScriptRegistry.allScripts { it.scriptState.loaded }.map { it.id } }
    }
    body {
        val async = checkArg("--async")
        if (arg.isEmpty()) replyUsage()
        val script = ScriptRegistry.getScriptInfo(arg[0])
            ?: returnReply("{tr control.reply.notFound}".with())
        runIgnoreCancel(!async) {
            ScriptManager.transactionV2 {
                disable(script)
                execute()
                enable(script)
            }.printResult()
        }
    }
}
command("unload", "{tr command.unload.desc}".with(), commands = Commands.controlCommand) {
    usage = "<module[[/script]> [[--async]"
    requirePermission("scriptAgent.control.unload")
    aliases = listOf("卸载")
    onComplete {
        onComplete(0) { ScriptRegistry.allScripts { it.scriptState.loaded }.map { it.id } }
    }
    body {
        val async = checkArg("--async")
        if (arg.isEmpty()) replyUsage()
        val script = ScriptRegistry.getScriptInfo(arg[0]) ?: returnReply("{tr control.reply.notFound}".with())

        runIgnoreCancel(!async) {
            ScriptManager.unloadScript(script)
            reply("{tr control.reply.unloadSuccess}".with())
        }
    }
}
command("disable", "{tr command.disable.desc}".with(), commands = Commands.controlCommand) {
    usage = "<module[[/script]> [[--async]"
    requirePermission("scriptAgent.control.disable")
    aliases = listOf("关闭")
    onComplete {
        onComplete(0) { ScriptRegistry.allScripts { it.scriptState.enabled }.map { it.id } }
    }
    body {
        val async = checkArg("--async")
        if (arg.isEmpty()) replyUsage()
        val script = ScriptRegistry.getScriptInfo(arg[0]) ?: returnReply("{tr control.reply.notFound}".with())

        runIgnoreCancel(!async) {
            ScriptManager.disableScript(script)
            reply("{tr control.reply.unloadSuccess}".with())
        }
    }
}
