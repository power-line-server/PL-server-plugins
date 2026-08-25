package wayzer.lib

import cf.wayzer.scriptAgent.Event
import mindustry.net.NetConnection
import mindustry.net.Packets.ConnectPacket

/**
 * Call when before [ConnectPacket]
 */
class ConnectAsyncEvent(
    val con: NetConnection,
    val packet: ConnectPacket,
) : Event, Event.Cancellable {
    var reason: String? = null
        private set
    override var cancelled: Boolean
        get() = reason != null || con.kicked
        set(@Suppress("UNUSED_PARAMETER") value) {
            error("Can't cancel,please use kick")
        }

    fun reject(reason: String) {
        this.reason = reason
    }

    companion object : Event.Handler()
}