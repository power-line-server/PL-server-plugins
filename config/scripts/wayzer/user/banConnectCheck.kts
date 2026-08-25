@file:Depends("coreLibrary/extApi/rpcService", "远程调用")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("wayzer/user/ban", "封禁服务(PlayerBanStore)")

package wayzer.user

import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.get
import coreLibrary.lib.parseTimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mindustry.net.Packets
import java.time.format.DateTimeFormatter

// ═══════════════════════════════════════════
// 连接阶段封禁检查
// 封禁中的玩家在 ConnectPacket 阶段直接拒绝(不加载世界数据, 原版行为)
// (独立脚本: ban.kts 是 @file:Implement 脚本, 顶层无 Script receiver)
// ═══════════════════════════════════════════

val banCheckStore get() = Services.get<coreLib.extApi.RpcService>().get().get<PlayerBanStore>()

listenPacket2ServerAsync<Packets.ConnectPacket> { con, packet ->
    val ban = withContext(Dispatchers.IO) {
        banCheckStore.findNotEnd(packet.uuid) ?: con.address?.let { banCheckStore.findByIp(it) }
    } ?: return@listenPacket2ServerAsync true
    // 连接阶段无玩家时区, 用 UTC 格式化
    val zone = parseTimeZone("UTC")
    val formatter = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")
    fun format(instant: java.time.Instant) = instant.atZone(zone).format(formatter)
    val ipBanStatus = if (ban.ip != null)
        "{tr ban.kick.ipBanned}".with().toString()
    else
        "{tr ban.kick.ipNotBanned}".with().toString()
    val text = "{tr ban.kick.message}".with(
        "targetDisplay" to packet.name,
        "operatorDisplay" to (ban.operatorName ?: "Server"),
        "reason" to ban.reason,
        "recordId" to ban.recordId,
        "ipBanStatus" to ipBanStatus,
        "createTime" to format(ban.createTime),
        "endTime" to format(ban.endTime),
        "qqGroup" to "", // globalLink 在 ban.kts, 此处不依赖
        "discord" to ""
    ).toString()
    con.kick(text)
    logger.info("[ban] 连接阶段拒绝封禁玩家: ${packet.name} (${packet.uuid}), 原因: ${ban.reason}")
    return@listenPacket2ServerAsync false
}
