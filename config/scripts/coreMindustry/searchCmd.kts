@file:Depends("coreMindustry/menu", "菜单系统")

package coreMindustry

import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.Commands
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands.Hidden

name = "命令搜索"

/** 终端搜索: 不过滤 Hidden/接收者(客户端命令也列出), 描述按服务端默认语言渲染 */
fun searchAll(keyword: String): List<Triple<String, CommandInfo, String>> {
    val kw = keyword.lowercase()
    return Commands.Root.subCommands().values.toSet().mapNotNull { info ->
        val desc = info.description.with().toString()
        val hit = info.name.lowercase().contains(kw) ||
            info.aliases.joinToString(" ").lowercase().contains(kw) ||
            desc.lowercase().contains(kw)
        if (hit) Triple("", info, desc) else null
    }
}

// /search <关键字>: 搜索所有命令的名称/别名/描述中包含关键字的指令
// 玩家: 弹出菜单点击执行; 终端/控制台: 列出所有命中命令的文本列表(含客户端命令)
command("search", "{tr command.search.desc}".with()) {
    aliases = listOf("搜索")
    usage = "<关键字>"
    body {
        val keyword = arg.joinToString(" ").trim()
        if (keyword.isEmpty()) returnReply("{tr search.reply.empty}".with())
        val p = player
        if (p == null) {
            // 终端: 不过滤 Hidden/接收者, 列出全部命中命令, 描述按服务端默认语言渲染
            val results = searchAll(keyword)
            if (results.isEmpty()) returnReply("{tr search.reply.noResult}".with("keyword" to keyword))
            reply(buildString {
                append("{tr search.reply.consoleTitle}".with("keyword" to keyword, "count" to results.size).toString())
                append("\n")
                results.forEach { (_, info, desc) ->
                    val alias = if (info.aliases.isEmpty()) "" else info.aliases.joinToString(prefix = "(", postfix = ")")
                    append("[lightgray]${info.name}[gray]$alias [sky]$desc\n")
                }
            }.with())
            return@body
        }

        // 收集所有可见命令(含子命令) — 内联遍历, 避免非inline函数内调suspend visible()
        val allCommands = mutableListOf<Pair<String, CommandInfo>>()
        Commands.Root.subCommands().values.toSet().forEach { info ->
            if (info.attrs.all { it !is Hidden || it.visible() }) {
                allCommands.add("" to info)
            }
        }

        val kw = keyword.lowercase()
        val results = allCommands.mapNotNull { (prefix, info) ->
            val cmdName = info.name.lowercase()
            val aliases = info.aliases.joinToString(" ").lowercase()
            // 描述按玩家语言渲染后搜索
            val desc = info.description.with("receiver" to p).toString()
            val descLower = desc.lowercase()
            if (cmdName.contains(kw) || aliases.contains(kw) || descLower.contains(kw)) {
                Triple(prefix, info, desc)
            } else null
        }

        if (results.isEmpty()) returnReply("{tr search.reply.noResult}".with("keyword" to keyword))

        // 弹出菜单展示搜索结果
        MenuV2(p) {
            title = "{tr search.menu.title}".with("receiver" to p, "keyword" to keyword).toString()
            msg = "{tr search.menu.msg}".with("receiver" to p, "count" to results.size).toString()
            renderPaged(results, prePage = 9) { (prefix, info, desc) ->
                val alias = if (info.aliases.isEmpty()) "" else info.aliases.joinToString(prefix = "(", postfix = ")")
                option(buildString {
                    append("[lightgray]${prefix}[gold]${info.name}")
                    if (info.aliases.isNotEmpty()) append("[gray]$alias")
                    append(" [sky]$desc")
                }) {
                    // 点击执行该命令
                    shortcut = true
                    arg = listOf(info.name)
                    reply("{tr coreMenu.help.quickInput}".with("command" to (prefix + info.name)))
                    Commands.Root.handle()
                }
            }
        }.send().awaitWithTimeout()
    }
}
