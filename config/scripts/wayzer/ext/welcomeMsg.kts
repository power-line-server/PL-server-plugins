@file:Depends("coreLibrary/lang", "多语言服务")
@file:Depends("coreLibrary/extApi/KVStore", "存储不再提示标记")
@file:Depends("coreMindustry/menu", "入服欢迎菜单")

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.KVStore
import coreLibrary.LangService
import coreMindustry.MenuV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.net.Administration
import org.h2.mvstore.type.StringDataType

val customWelcome by config.key("customWelcome", true, "是否开启自定义进服信息(中文)") {
    if (dataDirectory != null)
        Administration.Config.showConnectMessages.set(!it)
}
val template by config.key(
    "{tr welcome.message.template}", "欢迎信息模板"
)

val langApi by lazy { Services.get<LangService>().get() }
val welcomeDismissed by lazy { Services.get<KVStore>().get().open("welcomeMsgDismissed", StringDataType.INSTANCE) }

// 本地读取 global.properties (与 coreLibrary/lang.kts 路径一致)
private val globalProps by lazy {
    val props = java.util.Properties()
    val file = cf.wayzer.scriptAgent.Config.dataDir.resolve("global.properties")
    if (file.exists()) file.bufferedReader().use { props.load(it) }
    props
}
fun globalLink(key: String): String = globalProps.getProperty(key) ?: ""

// 导出：供 langSetup.kts 调用
fun broadcastJoin(player: Player) {
    if (customWelcome)
        broadcast("{tr welcome.broadcast.join}".with("player" to player))
}

fun sendWelcome(player: Player) {
    val playerId = player.uuid()
    if (welcomeDismissed[playerId] == "true") return
    val qqLink = globalLink("qq.group.link")
    val discordLink = globalLink("discord.link")
    val welcomeText = template.with(
        "receiver" to player,
        "qqGroup" to globalLink("qq.group.number"),
        "discord" to globalLink("discord.link")
    ).toString()
    launch(Dispatchers.game) {
        try {
            MenuV2(player) {
                title = "{tr welcome.menu.title}".with("receiver" to player).toString()
                msg = welcomeText
                if (qqLink.isNotEmpty()) {
                    option("{tr welcome.menu.qqGroup}".with("receiver" to player).toString()) {
                        Call.openURI(player.con, qqLink)
                    }
                }
                if (discordLink.isNotEmpty()) {
                    option("{tr welcome.menu.discord}".with("receiver" to player).toString()) {
                        Call.openURI(player.con, discordLink)
                    }
                }
                option("{tr welcome.menu.dismiss}".with("receiver" to player).toString()) {
                    welcomeDismissed[playerId] = "true"
                }
            }.send().awaitWithTimeout()
        } catch (e: Exception) {
            logger.warning("[welcomeMsg] 菜单异常: ${e.message}")
        }
    }
}
export(::sendWelcome)
export(::broadcastJoin)

command("welcome-reload", "{tr welcome.command.reload}".with()) {
    requirePermission("wayzer.ext.welcomeReload")
    body {
        val p = player!!
        welcomeDismissed.remove(p.uuid())
        reply("{tr welcome.reply.reload}".with("receiver" to p))
        // 与 /tutorial 一致: 立即重新显示欢迎菜单(不等待下次入服)
        sendWelcome(p)
    }
}

// 离场监听保留
listen<EventType.PlayerLeave> {
    if (customWelcome && it.player.lastText != "[Silent_Leave]")
        broadcast("{tr welcome.broadcast.leave}".with("player" to it.player))
}
