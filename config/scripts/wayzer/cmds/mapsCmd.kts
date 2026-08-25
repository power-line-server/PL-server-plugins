@file:Depends("wayzer/maps")
@file:Depends("coreMindustry/menu", "maps菜单")
@file:Depends("wayzer/cmds/voteMap", "发起投票换图", soft = true)

package wayzer.cmds

import coreMindustry.MenuV2
import coreMindustry.renderPaged
import wayzer.MapInfo
import wayzer.MapRegistry

val mapsPrePage by config.key(6, "/maps每页显示数")

command("maps", "{tr command.maps.desc}".with()) {
    usage = "[[page/filter] [[page]"
    aliases = listOf("地图")
    body {
        // 最后一个参数若为数字则为页码,其余参数拼接为 filter(支持多词搜索)
        val argList = arg.toList()
        val lastIsPage = argList.lastOrNull()?.toIntOrNull() != null
        val page = if (lastIsPage) argList.last().toInt() else 1
        val filterList = if (lastIsPage) argList.dropLast(1) else argList
        val filter = filterList.joinToString(" ").ifBlank { null }
        val maps = MapRegistry.searchMaps(filter)/*.sortedBy { it.id }*/
        val template = "[red]{info.id}  [green]{info.name}[blue] | {info.mode}"
        val player = player ?: returnReply(menu("{tr mapsCmd.menu.consoleTitle}".with(), maps, page, mapsPrePage) { info ->
            template.with("info" to info)
        })
        MenuV2(player) {
            title = "{tr mapsCmd.menu.main.title}".with("receiver" to player, "filter" to (filter ?: "")).toString()
            msg = "{tr mapsCmd.menu.main.msg}".with("receiver" to player).toString()
            val url = "https://www.mindustry.top"
            option("{tr mapsCmd.menu.main.openUrl}".with("receiver" to player, "url" to url).toString()) {
                Call.openURI(player.con, url)
            }
            renderPaged(maps, page, mapsPrePage) {
                option(template.with("info" to it).toPlayer(player)) {
                    if (!player.hasPermission("wayzer.vote.map")) {
                        player.sendMessage("{tr mapsCmd.reply.noVoteMapPermission}".with())
                        return@option
                    }
                    depends("wayzer/cmds/voteMap")?.import<(Player, MapInfo) -> Unit>("voteMap")
                        ?.invoke(player, it)
                        ?: returnReply("{tr mapsCmd.reply.voteMapCallFailed}".with("info" to it))
                }
            }
        }.send().awaitWithTimeout()
    }
}