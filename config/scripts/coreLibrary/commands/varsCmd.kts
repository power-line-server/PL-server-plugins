package coreLibrary.commands

import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.PlaceHold.registeredVars

data class VarInfo(val script: ScriptInfo, val key: String, val desc: String)

command("vars", "{tr command.vars.desc}".with(), commands = Commands.controlCommand) {
    usage = "[[-v] [[page]"
    requirePermission("scriptAgent.vars")
    body {
        val detail = checkArg("-v")
        val page = arg.firstOrNull()?.toIntOrNull() ?: 1
        val all = mutableListOf<VarInfo>()
        ScriptRegistry.allScripts().sortedBy { it.id }.forEach { script ->
            script.inst?.registeredVars?.mapTo(all) { (key, desc) ->
                VarInfo(script, key, desc)
            }
        }
        returnReply(menu("{tr vars.menu.title}".with(), all, page, 15) {
            "[green]{key} [blue]{desc} [purple]{from}".with(
                "key" to it.key, "desc" to it.desc,
                "from" to (if (detail) it.script.id else "")
            )
        })
    }
}
