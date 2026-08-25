package coreLibrary.commands

import cf.wayzer.placehold.PlaceHoldApi.with

val handler = PermissionApi.StringPermissionHandler()
onEnable { PermissionApi.Global.ByGroup.add(0, handler) }
onDisable { PermissionApi.Global.ByGroup.remove(handler) }

var groups by config.key(
    "groups", mapOf("@default" to emptyList<String>()),
    "权限设置", "值为权限，@开头为组,支持末尾通配符.*"
) {
    handler.clear()
    it.forEach { (g, list) ->
        handler.registerPermission(g, list)
    }
}

command("permission", "{tr command.permission.desc}".with(), commands = Commands.controlCommand) {
    aliases = listOf("pm")
    usage = "<group> <add/list/remove/delGroup> [[permission]"
    onComplete {
        onComplete(0) { PermissionApi.allKnownGroup.toList() }
        onComplete(1) { listOf("add", "list", "remove", "delGroup") }
    }
    body {
        if (arg.isEmpty()) returnReply("{tr permission.reply.existingGroups}".with("list" to PermissionApi.allKnownGroup))
        val group = arg[0]
        when (arg.getOrNull(1)?.lowercase() ?: "") {
            "add" -> {
                if (arg.size < 3) returnReply("{tr permission.reply.missingPermissionArg}".with())
                val now = groups[group].orEmpty()
                if (arg[2] !in now)
                    groups = groups + (group to (now + arg[2]))
                returnReply(
                    "{tr permission.reply.addSuccess}".with(
                        "permission" to arg[2], "group" to group
                    )
                )
            }

            "remove" -> {
                if (arg.size < 3) returnReply("{tr permission.reply.missingPermissionArg}".with())
                if (group in groups) {
                    val newList = groups[group].orEmpty() - arg[2]
                    groups = if (newList.isEmpty()) groups - group else groups + (group to newList)
                }
                returnReply(
                    "{tr permission.reply.removeSuccess}".with(
                        "permission" to arg[2], "group" to group
                    )
                )
            }

            "", "list" -> {
                val now = groups[group].orEmpty()
                val defaults = PermissionApi.default.groups[group]?.allNodes().orEmpty()
                reply(
                    "{tr permission.reply.groupPermissions}".with(
                        "group" to group, "list" to now.toString(), "defaults" to defaults.toString()
                    )
                )
            }

            "delGroup".lowercase() -> {
                val now = groups[group].orEmpty()
                if (group in groups)
                    groups = groups - group
                returnReply(
                    "{tr permission.reply.delGroupSuccess}".with(
                        "group" to group, "list" to now.toString()
                    )
                )
            }

            else -> replyUsage()
        }
    }
}

val debug by config.key(false, "调试输出,如果开启,则会在后台打印权限请求")
listenTo<RequestPermissionEvent>(Event.Priority.Watch) {
    if (debug)
        logger.info("$permission $directReturn -- $group")
}
