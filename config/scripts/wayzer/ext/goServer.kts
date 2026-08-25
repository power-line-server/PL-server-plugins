@file:Depends("coreMindustry/menu")
@file:Depends("coreMindustry/util/textInput", "输入文本")
package wayzer.ext
//WayZer 版权所有(请勿删除版权注解)

import coreMindustry.MenuV2
import coreMindustry.util.textInput
name = "跨服传送"

var servers by config.key(mapOf<String, String>(), "服务器传送列表", "格式: {名字: \"介绍;地址\"} (;作为分割符, 地址可带端口如 ip:port 或 域名)")

data class Info(val name: String, val desc: String, val address: String, val port: Int?) {
    /** 用于 Call.connect 的地址（不含端口） */
    val host get() = address.substringBeforeLast(":")
    /** 用于 Call.connect 的端口，null 表示不指定（使用默认） */
    val connectPort get() = address.substringAfterLast(":", "").toIntOrNull()
}

val infos: Map<String, Info>
    get() = servers.mapValues { (k, v) ->
        val sp1 = v.split(";", limit = 2)
        val desc = sp1.getOrNull(0) ?: ""
        val addr = sp1.getOrNull(1) ?: ""
        // 地址可能带端口 (ip:port 或 域名:port)，也可能不带 (ip 或 域名)
        val port = if (addr.contains(":")) addr.substringAfterLast(":").toIntOrNull() else null
        Info(k, desc, addr, port)
    }

fun saveServers(newMap: Map<String, String>) {
    servers = newMap
}

suspend fun showServerDetail(p: mindustry.gen.Player, info: Info, isAdmin: Boolean) {
    MenuV2(p) {
        title = info.name
        msg = "{tr goServer.menu.detail.msg}".with("receiver" to player, "name" to info.name, "desc" to info.desc, "address" to info.address).toString()
        column(1) {
            option("{tr goServer.menu.detail.option.teleport}".with("receiver" to player).toString()) {
                // 直接使用管理员输入的地址:端口
                val host = info.address.substringBeforeLast(":")
                val port = info.address.substringAfterLast(":", "").toIntOrNull() ?: 6567
                Call.connect(p.con, host, port)
                broadcast("{tr goServer.broadcast.teleport}".with("player" to p, "name" to info.name))
            }
            if (isAdmin) {
                option("{tr goServer.menu.detail.option.edit}".with("receiver" to player).toString()) {
                    showEditServer(p, info)
                }
                option("{tr goServer.menu.detail.option.delete}".with("receiver" to player).toString()) {
                    val newMap = servers.toMutableMap()
                    newMap.remove(info.name)
                    saveServers(newMap)
                    p.sendMessage("{tr goServer.reply.deleted}".with("name" to info.name))
                    showServerList(p, p.admin)
                }
            }
            option("{tr goServer.menu.option.back}".with("receiver" to player).toString()) {
                showServerList(p, isAdmin)
            }
        }
        autoCloseButton = false
    }.send().awaitWithTimeout()
}

suspend fun showEditServer(p: mindustry.gen.Player, info: Info) {
    var name = info.name
    var desc = info.desc
    var address = info.address
    MenuV2(p) {
        title = "{tr goServer.menu.edit.title}".with("receiver" to player, "name" to name).toString()
        msg = "{tr goServer.menu.edit.msg}".with("receiver" to player, "name" to name, "desc" to desc, "address" to address).toString()
        column(1) {
            option("{tr goServer.menu.edit.option.editName}".with("receiver" to player).toString()) {
                val input = textInput(p, "{tr goServer.textInput.editName.title}".with("receiver" to p).toString(), "{tr goServer.textInput.editName.hint}".with("receiver" to p, "name" to name).toString(), default = name) ?: return@option
                if (input.isBlank()) return@option
                name = input
                refresh()
            }
            option("{tr goServer.menu.edit.option.editDesc}".with("receiver" to player).toString()) {
                val input = textInput(p, "{tr goServer.textInput.editDesc.title}".with("receiver" to p).toString(), "{tr goServer.textInput.editDesc.hint}".with("receiver" to p, "desc" to desc).toString(), default = desc) ?: return@option
                desc = input
                refresh()
            }
            option("{tr goServer.menu.edit.option.editAddress}".with("receiver" to player).toString()) {
                val input = textInput(p, "{tr goServer.textInput.editAddress.title}".with("receiver" to p).toString(), "{tr goServer.textInput.addressHint}".with("receiver" to p).toString(), default = address) ?: return@option
                if (input.isBlank()) return@option
                address = input
                refresh()
            }
            option("{tr goServer.menu.option.save}".with("receiver" to player).toString()) {
                val newMap = servers.toMutableMap()
                if (info.name != name) newMap.remove(info.name)
                newMap[name] = "$desc;$address"
                saveServers(newMap)
                p.sendMessage("{tr goServer.reply.saved}".with("name" to name))
                showServerList(p, p.admin)
            }
            option("{tr goServer.menu.option.cancel}".with("receiver" to player).toString()) {
                showServerList(p, p.admin)
            }
        }
        autoCloseButton = false
    }.send().awaitWithTimeout()
}

suspend fun showAddServer(p: mindustry.gen.Player) {
    var name = ""
    var desc = ""
    var address = ""
    MenuV2(p) {
        title = "{tr goServer.menu.add.title}".with("receiver" to player).toString()
        msg = "{tr goServer.menu.add.msg}".with("receiver" to player, "name" to if (name.isBlank()) "{tr goServer.label.unset}".with("receiver" to player).toString() else name, "desc" to if (desc.isBlank()) "{tr goServer.label.unset}".with("receiver" to player).toString() else desc, "address" to if (address.isBlank()) "{tr goServer.label.unset}".with("receiver" to player).toString() else address).toString()
        column(1) {
            option("{tr goServer.menu.add.option.setName}".with("receiver" to player).toString()) {
                val input = textInput(p, "{tr goServer.textInput.setName.title}".with("receiver" to p).toString(), "{tr goServer.textInput.setName.hint}".with("receiver" to p).toString()) ?: return@option
                if (input.isBlank()) return@option
                name = input
                refresh()
            }
            option("{tr goServer.menu.add.option.setDesc}".with("receiver" to player).toString()) {
                val input = textInput(p, "{tr goServer.textInput.setDesc.title}".with("receiver" to p).toString(), "{tr goServer.textInput.setDesc.hint}".with("receiver" to p).toString()) ?: return@option
                desc = input
                refresh()
            }
            option("{tr goServer.menu.add.option.setAddress}".with("receiver" to player).toString()) {
                val input = textInput(p, "{tr goServer.textInput.setAddress.title}".with("receiver" to p).toString(), "{tr goServer.textInput.addressHint}".with("receiver" to p).toString()) ?: return@option
                if (input.isBlank()) return@option
                address = input
                refresh()
            }
            option("{tr goServer.menu.option.save}".with("receiver" to player).toString()) {
                if (name.isBlank() || address.isBlank()) {
                    p.sendMessage("{tr goServer.reply.nameAddressRequired}".with())
                    return@option
                }
                if (name in servers) {
                    p.sendMessage("{tr goServer.reply.nameExists}".with())
                    return@option
                }
                val newMap = servers.toMutableMap()
                newMap[name] = "$desc;$address"
                saveServers(newMap)
                p.sendMessage("{tr goServer.reply.added}".with("name" to name))
                showServerList(p, p.admin)
            }
            option("{tr goServer.menu.option.cancel}".with("receiver" to player).toString()) {
                showServerList(p, p.admin)
            }
        }
        autoCloseButton = false
    }.send().awaitWithTimeout()
}

suspend fun showServerList(p: mindustry.gen.Player, isAdmin: Boolean) {
    MenuV2(p) {
        title = "{tr goServer.menu.list.title}".with("receiver" to player).toString()
        msg = "{tr goServer.menu.list.msg}".with("receiver" to player).toString()
        column(1) {
            if (infos.isEmpty()) {
                option("{tr goServer.menu.list.option.empty}".with("receiver" to player).toString()) {}
            }
            for (info in infos.values) {
                option("[gold]${info.name}") {
                    showServerDetail(p, info, isAdmin)
                }
            }
            if (isAdmin) {
                newRow()
                option("{tr goServer.menu.list.option.add}".with("receiver" to player).toString()) {
                    showAddServer(p)
                }
                option("{tr goServer.menu.list.option.edit}".with("receiver" to player).toString()) {
                    if (infos.isEmpty()) {
                        p.sendMessage("{tr goServer.reply.noServerToEdit}".with())
                        return@option
                    }
                    MenuV2(p) {
                        title = "{tr goServer.menu.editSelect.title}".with("receiver" to player).toString()
                        msg = ""
                        column(1) {
                            for (info in infos.values) {
                                option("[gold]${info.name}") {
                                    showEditServer(p, info)
                                }
                            }
                            option("{tr goServer.menu.option.back}".with("receiver" to player).toString()) {
                                showServerList(p, isAdmin)
                            }
                        }
                        autoCloseButton = false
                    }.send().awaitWithTimeout()
                }
            }
        }
        autoCloseButton = true
    }.send().awaitWithTimeout()
}

command("go", "{tr command.go.desc}".with()) {
    usage = ""
    type = CommandType.Client
    aliases = listOf("前往")
    body {
        val p = player ?: returnReply("{tr goServer.reply.playerOnly}".with())
        showServerList(p, p.admin)
    }
}
