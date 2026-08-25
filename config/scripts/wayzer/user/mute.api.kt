package wayzer.user

import mindustry.gen.Player
import wayzer.lib.PlayerData
import java.io.Serializable
import java.rmi.Remote
import java.rmi.RemoteException
import java.time.Duration
import java.time.Instant

// 禁言记录数据类
data class PlayerMute(
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

// 禁言存储远程接口, muteStore.kts 实现
interface PlayerMuteStore : Remote {
    @Throws(RemoteException::class)
    fun findNotEnd(id: String): PlayerMute?
    @Throws(RemoteException::class)
    fun findByIp(ip: String): PlayerMute?
    @Throws(RemoteException::class)
    fun getById(record: Int): PlayerMute?
    @Throws(RemoteException::class)
    fun create(
        ids: Set<String>,
        targetName: String,
        duration: Duration,
        reason: String,
        operator: String?,
        operatorName: String?,
        ip: String?
    ): PlayerMute
    @Throws(RemoteException::class)
    fun delete(record: Int): PlayerMute?
    @Throws(RemoteException::class)
    fun listAll(): List<PlayerMute>
}

// 禁言服务接口, mute.kts 实现
interface MuteService {
    suspend fun mute(player: PlayerData, time: Int, reason: String, operate: Player?)
}
