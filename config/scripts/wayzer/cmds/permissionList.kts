@file:Depends("wayzer")

package wayzer.cmds

import cf.wayzer.scriptAgent.Config
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.CommandContext
import coreLibrary.lib.PermissionApi
import java.io.File

name = "权限节点清单生成与查询"

val docFile = File(Config.rootDir, "data/permissions.txt")

val literalPatterns = listOf(
    Regex("""requirePermission\s*\(\s*"([^"]+)"\s*\)"""),
    Regex("""permission\s*=\s*"([^"]+)"\s*"""),
    Regex("""hasPermission\s*\(\s*"([^"]+)"\s*\)""")
)

fun collectFromSource(): Set<String> {
    val nodes = mutableSetOf<String>()
    Config.rootDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kts") }.forEach { f ->
        val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
        literalPatterns.forEach { p ->
            p.findAll(text).forEach { nodes.add(it.groupValues[1]) }
        }
        Regex("""registerDefault\s*\(([^)]*)\)""").findAll(text).forEach { m ->
            Regex(""""([^"]+)"""").findAll(m.groupValues[1]).forEach {
                val s = it.groupValues[1]
                if (!s.startsWith("@")) nodes.add(s)
            }
        }
    }
    return nodes
}

fun resolveGroups(node: String): List<String> {
    return try {
        PermissionApi.Global.allKnownGroup
            .filter { it.startsWith("@") }
            .filter { g ->
                runCatching {
                    PermissionApi.Global.ByGroup.find(g, node).firstOrNull {
                        val expire = it.expire
                        expire == null || expire.isAfter(java.time.Instant.now())
                    }?.value == true
                }.getOrDefault(false)
            }
    } catch (e: Throwable) {
        emptyList()
    }
}

fun generateDoc(receiver: CommandContext.IReceiver = CommandContext.ConsoleReceiver) {
    val nodes = runCatching { collectFromSource() }.getOrDefault(emptySet()).toSortedSet()
    val sb = StringBuilder()
    sb.appendLine("# {tr permissionList.doc.title}".with("receiver" to receiver).toString())
    sb.appendLine("# {tr permissionList.doc.format}".with("receiver" to receiver).toString())
    sb.appendLine("# {tr permissionList.doc.note}".with("receiver" to receiver).toString())
    sb.appendLine()
    nodes.forEach { node ->
        val groups = resolveGroups(node)
        val gStr = if (groups.isEmpty()) "{tr permissionList.doc.noGroup}".with("receiver" to receiver).toString()
            else groups.joinToString(", ")
        sb.appendLine("$node -- $gStr")
    }
    docFile.parentFile?.mkdirs()
    docFile.writeText(sb.toString())
    logger.info("[permissionList] {tr permissionList.log.generated}".with("receiver" to receiver, "count" to nodes.size, "path" to docFile.path).toString())
}

command("permissionList", "{tr command.permissionList.desc}".with()) {
    requirePermission("scriptAgent.admin")
    aliases = listOf("权限清单")
    body {
        runCatching { generateDoc(receiver) }.onFailure { e ->
            logger.warning("[permissionList] {tr permissionList.log.genFailed}".with("receiver" to receiver, "msg" to (e.message ?: "")).toString())
            returnReply("{tr permissionList.reply.genFailed}".with("msg" to (e.message ?: "")))
        }
        reply("{tr permissionList.reply.header}".with())
        docFile.readLines().forEach { reply(it.with("receiver" to receiver)) }
    }
}

onEnable {
    launch {
        delay(2000)
        runCatching { generateDoc() }.onFailure { e ->
            logger.warning("[permissionList] {tr permissionList.log.autoGenFailed}".with("msg" to (e.message ?: "")).toString())
        }
    }
}
