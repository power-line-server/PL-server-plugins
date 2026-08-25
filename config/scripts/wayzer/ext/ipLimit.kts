@file:Depends("wayzer")
@file:Depends("wayzer/user/lang")

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.LangService
import mindustry.gen.Groups
import wayzer.user.lang
import java.io.File

name = "IP限制: 防止同IP多账号"

val langApi by lazy { Services.get<coreLibrary.LangService>().get() }

private val globalProps by lazy {
    val props = java.util.Properties()
    val file = Config.dataDir.resolve("global.properties")
    if (file.exists()) file.bufferedReader().use { props.load(it) }
    props
}
fun globalLink(key: String): String = globalProps.getProperty(key) ?: ""

fun kickMsg(lang: String): String {
    val raw = langApi.translateBundle("ipLimit.kick.message", lang) ?: return "ipLimit.kick.message"
    return raw.with(
        "qqGroup" to globalLink("qq.group.number"),
        "discord" to globalLink("discord.link")
    ).toString()
}

val whitelistFile = File(Config.rootDir, "data/ipLimit_whitelist.txt")

fun loadWhitelist(): Set<String> {
    if (!whitelistFile.exists()) {
        whitelistFile.parentFile?.mkdirs()
        whitelistFile.writeText(
            """
            # IP限制白名单
            # 每行一个UUID(以#开头的行将被忽略)
            # 白名单内的玩家不受同IP限制
            """.trimIndent() + "\n"
        )
    }
    return whitelistFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()
}

listenTo<wayzer.lib.ConnectAsyncEvent> {
    if (con.address.startsWith("steam:")) return@listenTo

    val uuid = packet.uuid
    if (loadWhitelist().contains(uuid)) return@listenTo

    val ip = con.address
    val duplicate = Groups.player.find { it.con?.address == ip }
    if (duplicate != null) {
        try {
            val playerData = PlayerData.forAuth(packet)
            val lang = playerData.lang
            reject(kickMsg(lang))
            logger.info("[ipLimit] 拒绝同IP连接: ip=$ip, name=${packet.name}, uuid=$uuid, 已在线玩家=${duplicate.plainName()}")
        } catch (e: Exception) {
            logger.severe("[ipLimit] 拒绝失败,使用默认原因: ${e.message}")
            reject("ipLimit.kick.message")
        }
    }
}

// ConnectAsyncEvent 只检查已在线玩家: 同一IP玩家同时连接时双方都未加入 Groups, 双双放行.
// 这里在玩家加入后复查, 同一IP多余者踢出(保留先加入者).
listen<EventType.PlayerJoin> {
    val p = it.player
    val ip = p.con?.address ?: return@listen
    if (ip.startsWith("steam:")) return@listen
    if (loadWhitelist().contains(p.uuid())) return@listen

    val sameIp = Groups.player.filter { it.con?.address == ip }
    if (sameIp.size > 1) {
        sameIp.drop(1).forEach { late ->
            val lang = runCatching { PlayerData[late].lang }.getOrNull() ?: "zh_CN"
            late.con?.kick(kickMsg(lang))
            logger.info("[ipLimit] 复查拒绝同IP: ip=$ip, 保留=${sameIp.first().plainName()}, 踢出=${late.plainName()}")
        }
    }
}
