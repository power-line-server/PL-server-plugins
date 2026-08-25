@file:Depends("wayzer/user/ban", "banX 实现")

package wayzer.ext

import arc.Core
import coreLibrary.lib.Commands
import mindustry.game.EventType
import mindustry.net.Packets.AdminAction

name = "禁用原版封禁与踢出，重定向到 banX"

// 标记由原版管理员菜单触发的 ban，用于事后撤销
private var pendingVanillaBan = false

// 自定义 Hidden attr：帮助菜单不可见，但允许执行 body
// 覆盖 Commands.Hidden.handle()，不拦截执行
private object HiddenInHelpOnly : Commands.Hidden {
    override suspend fun CommandContext.visible(): Boolean = false
    override suspend fun CommandContext.handle() {} // 不拦截，允许 body 执行
}

// ===== 拦截管理员菜单的 ban/kick，重定向到 /banX =====

listen<EventType.AdminRequestEvent> {
    if (it.player == null) return@listen
    when (it.action) {
        AdminAction.ban, AdminAction.kick -> {
            val other = it.other
            // 阻止原版 kick/ban 的踢出：提前置 kicked=true 让 other.kick() 短路
            // 这样 ban 的 other.kick(KickReason.banned) 和 kick 的 other.kick(KickReason.kick) 都不会执行
            if (other != null) {
                val con = other.con
                if (con != null) {
                    con.kicked = true
                    Core.app.post { con.kicked = false }
                }
            }
            // ban 拦截：标记，等 PlayerBanEvent/PlayerIpBanEvent 触发时撤销封禁+清理kick时间
            if (it.action == AdminAction.ban) pendingVanillaBan = true
            // 弹窗提示操作者使用 banX
            val actionName = if (it.action == AdminAction.ban)
                "{tr disableVanillaBan.popup.actionBan}".with("receiver" to it.player).toString()
            else
                "{tr disableVanillaBan.popup.actionKick}".with("receiver" to it.player).toString()
            Call.infoMessage(it.player.con, "{tr disableVanillaBan.popup.banRedirect}".with("receiver" to it.player, "actionName" to actionName).toString())
        }
        else -> {}
    }
}

// ban 事后撤销：banPlayerID 内部 fire PlayerBanEvent
listen<EventType.PlayerBanEvent> {
    if (pendingVanillaBan) {
        netServer.admins.unbanPlayerID(it.uuid)
        // 清理 kick 时间，防止 30s 内无法重连
        val info = netServer.admins.getInfo(it.uuid)
        info.lastKicked = 0
        info.lastIP?.let { ip -> netServer.admins.kickedIPs.remove(ip) }
    }
}

listen<EventType.PlayerIpBanEvent> {
    if (pendingVanillaBan) {
        netServer.admins.unbanPlayerIP(it.ip)
        netServer.admins.kickedIPs.remove(it.ip)
        pendingVanillaBan = false
    }
}

// ===== 覆盖原版 /votekick 命令：帮助菜单不可见，执行时弹窗提示 banX =====

command("votekick", "{tr command.votekick.desc}".with()) {
    attr(HiddenInHelpOnly)
    body {
        val p = player ?: return@body
        Call.infoMessage(p.con, "{tr disableVanillaBan.popup.votekickRedirect}".with("receiver" to p).toString())
    }
}
