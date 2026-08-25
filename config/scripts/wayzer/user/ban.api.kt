package wayzer.user

import mindustry.gen.Player
import wayzer.lib.PlayerData
import java.io.Serializable
import java.rmi.Remote
import java.rmi.RemoteException
import java.time.Duration
import java.time.Instant

// 封禁记录数据类
data class PlayerBan(
    val recordId: Int,
    val ids: Set<String>,
    val targetName: String?,
    val reason: String,
    val operator: String?,
    val operatorName: String?,
    val ip: String?,
    val createTime: Instant,
    val endTime: Instant
) : Serializable

// 封禁存储远程接口, banStore.kts 实现
interface PlayerBanStore : Remote {
    @Throws(RemoteException::class)
    fun findNotEnd(id: String): PlayerBan?
    @Throws(RemoteException::class)
    fun findByIp(ip: String): PlayerBan?
    @Throws(RemoteException::class)
    fun getById(record: Int): PlayerBan?
    @Throws(RemoteException::class)
    fun create(
        ids: Set<String>,
        targetName: String,
        duration: Duration,
        reason: String,
        operator: String?,
        operatorName: String?,
        ip: String?
    ): PlayerBan
    @Throws(RemoteException::class)
    fun delete(record: Int): PlayerBan?
    @Throws(RemoteException::class)
    fun listAll(): List<PlayerBan>
}

// 封禁服务接口, ban.kts 实现, antiLogicVirus.kts 等脚本通过 Services.get<BanService>().get() 调用
interface BanService {
    suspend fun ban(player: PlayerData, time: Int, reason: String, operate: Player?, banIp: Boolean = false)
}
