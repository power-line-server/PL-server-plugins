@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/cmds/share", "菜单选人与输入工具")
@file:Depends("wayzer/map/betterTeam", "强制观察者")

package wayzer.cmds

import arc.util.Log
import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.emitAsync
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.lib.util.loop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.PlayerSpawnCallPacket
import wayzer.ForcedObEvent
import wayzer.VoteEvent
import wayzer.cmds.getInput
import wayzer.cmds.getTarget
import wayzer.map.AssignTeamEvent
import wayzer.map.TeamService
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

// 通过 depends().import<>() 获取 ban.kts 导出的投票阈值函数(目标为风纪委员时提高阈值)
val voteThresholdFor: (Player) -> Double by lazy {
    depends("wayzer/user/ban")?.import<(Player) -> Double>("voteThresholdFor") ?: { 0.6 }
}

val teams by lazy { Services.get<TeamService>().get() }

private val globalProps by lazy {
    val props = java.util.Properties()
    val file = cf.wayzer.scriptAgent.Config.dataDir.resolve("global.properties")
    if (file.exists()) file.bufferedReader().use { props.load(it) }
    props
}
fun globalLink(key: String): String = globalProps.getProperty(key) ?: ""

// ═══════════════════════════════════════════
// 强制观战限制(与 banX 同风格): 有时长/缘由/操作者, 进服弹窗告知, 到期自动解除, 持久化
// ═══════════════════════════════════════════
data class ObRestriction(
    val reason: String,
    val operator: String,   // 操作者(投票发起者/管理员)
    val start: Instant,
    val end: Instant?       // null = 永久
)

@Savable(false)
val limitPlayers = mutableMapOf<String, ObRestriction>() // profile -> 限制

private val obFile: File get() = File(Config.dataDir, "ob_restrictions.txt")

/** 持久化: 每行 profile|reason|operator|startEpoch|endEpoch */
fun saveObRestrictions() {
    try {
        val lines = limitPlayers.map { (profile, r) ->
            "${profile}|${r.reason}|${r.operator}|${r.start.toEpochMilli()}|${r.end?.toEpochMilli() ?: ""}"
        }
        obFile.parentFile?.mkdirs()
        obFile.writeText("# 强制观战限制(重启恢复): profile|reason|operator|startMs|endMs(空=永久)\n" + lines.joinToString("\n") + "\n")
    } catch (e: Exception) {
        Log.err("[voteOb] 保存观战限制失败", e)
    }
}

fun loadObRestrictions() {
    limitPlayers.clear()
    if (!obFile.exists()) return
    try {
        obFile.readLines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val parts = t.split("|", limit = 5)
            if (parts.size < 5) return@forEach
            val start = parts[3].toLongOrNull() ?: return@forEach
            val end = parts[4].toLongOrNull()?.let { Instant.ofEpochMilli(it) }
            limitPlayers[parts[0]] = ObRestriction(
                reason = parts[1],
                operator = parts[2],
                start = Instant.ofEpochMilli(start),
                end = end
            )
        }
    } catch (e: Exception) {
        Log.err("[voteOb] 加载观战限制失败", e)
    }
}

/** 格式化时间(本地时区) */
private val obTimeFmt = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")
private fun formatObTime(t: Instant): String = t.atZone(java.time.ZoneId.systemDefault()).format(obTimeFmt)

/** 检查限制是否生效; 过期则清理并返回 false */
fun isObActive(id: String): Boolean {
    val r = limitPlayers[id] ?: return false
    val end = r.end
    if (end != null && end.isBefore(Instant.now())) {
        limitPlayers.remove(id)
        saveObRestrictions()
        return false
    }
    return true
}

/** 进服/换队弹窗: banX 风格告知 */
fun sendObRestrictionMessage(player: Player, r: ObRestriction) {
    val now = Instant.now()
    val endText = r.end?.let { formatObTime(it) } ?: "{tr voteOb.value.permanent}".with("receiver" to player).toString()
    val remain = r.end?.let { Duration.between(now, it) }
    val remainText = if (remain != null && remain.isPositive)
        "{tr voteOb.value.remain}".with("receiver" to player, "remain" to remain).toString()
    else ""
    player.sendMessage(
        "{tr voteOb.reply.restricted}".with(
            "name" to player.name,
            "operator" to r.operator,
            "reason" to r.reason,
            "startTime" to formatObTime(r.start),
            "endTime" to endText,
            "remain" to remainText,
            "qqGroup" to globalLink("qq.group.number"),
            "discord" to globalLink("discord.link")
        ).toString(),
        MsgType.InfoMessage
    )
}

onEnable {
    loadObRestrictions()
    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "ob", "{tr command.voteOb.desc}".with()) {
        aliases = listOf("观战")
        usage = "{tr usage.voteOb}"
        permission = "wayzer.vote.ob"
        body {
            val player = player!!
            val target = getTarget()
            val reason = getInput("{tr voteOb.input.reasonName}".with("receiver" to player).toString(), "{tr voteOb.reply.needReason}".with())
            // 时长(分钟, 0=永久), 与 banX 一致
            val timeInput = getInput("{tr voteOb.input.durationName}".with("receiver" to player).toString(), "{tr voteOb.reply.needDuration}".with()) ?: return@body
            val duration = timeInput.toIntOrNull()
            if (duration == null || duration < 0) {
                returnReply("{tr voteOb.reply.durationInvalid}".with())
            }
            // 目标为风纪委员时提高通过阈值(默认 80%)
            val threshold = voteThresholdFor(target)
            val event = VoteEvent(
                script, player,
                voteDesc = "{tr voteOb.voteDesc.forceOb}".with("target" to target),
                extDesc = "{tr voteOb.extDesc.forceOb}".with("receiver" to player, "reason" to reason, "duration" to (if (duration == 0) "{tr voteOb.value.permanent}".with("receiver" to player).toString() else "${duration}m")).toString(),
                requireNum = { all -> ceil(all * threshold).toInt() }
            )
            val ids = PlayerData[target].ids
            if (event.awaitResult()) {
                if (target.hasPermission("wayzer.admin.skipKick"))
                    return@body broadcast(
                        "{tr voteOb.broadcast.targetIsAdmin}".with("target" to target)
                    )
                // 解除附身
                Call.unitClear(target)
                val now = Instant.now()
                val end = if (duration > 0) now.plus(Duration.ofMinutes(duration.toLong())) else null
                ids.forEach {
                    limitPlayers[it] = ObRestriction(reason, player.name, now, end)
                }
                saveObRestrictions()
                teams.changeTeam(target, teams.spectateTeam)
                ForcedObEvent(target.uuid()).emitAsync()
                broadcast(
                    "{tr voteOb.broadcast.kickHint}".with("player" to target)
                )
            }
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "quitOb", "{tr command.quitOb.desc}".with()) {
        aliases = listOf("解除观战")
        body {
            val player = player!!
            val id = PlayerData[player].id
            val r = limitPlayers[id]
                ?: returnReply("{tr voteOb.reply.notRestricted}".with())
            val delta = Duration.between(r.start, Instant.now())
            val event = VoteEvent(
                script, player,
                voteDesc = "{tr voteOb.voteDesc.quitOb}".with("delta" to delta),
                extDesc = "{tr voteOb.extDesc.quitOb}".with("receiver" to player, "reason" to r.reason).toString()
            )
            if (event.awaitResult()) {
                limitPlayers.remove(id)
                saveObRestrictions()
                // 解除附身（安全处理）
                Call.unitClear(player)
                teams.changeTeam(player)
            }
        }
    }

    // 每分钟检查在线受限玩家是否到期(到期自动解除)
    loop(Dispatchers.Default) {
        delay(60_000)
        val now = Instant.now()
        val hasExpired = limitPlayers.values.any { it.end != null && it.end!!.isBefore(now) }
        if (!hasExpired) return@loop
        withContext(Dispatchers.game) {
            Groups.player.forEach { p ->
                val id = PlayerData[p].id
                val r = limitPlayers[id]
                if (r != null && r.end != null && r.end!!.isBefore(now)) {
                    limitPlayers.remove(id)
                    Call.unitClear(p)
                    teams.changeTeam(p)
                    p.sendMessage("{tr voteOb.reply.expired}".with().toString(), MsgType.InfoMessage)
                }
            }
            limitPlayers.entries.removeIf { (_, r) -> r.end != null && r.end!!.isBefore(now) }
            saveObRestrictions()
        }
    }
}

listenTo<AssignTeamEvent>(Event.Priority.Intercept) {
    val id = PlayerData[player].id
    val r = limitPlayers[id]
    if (r != null) {
        // 已到期: 自动解除, 放行正常队伍
        if (r.end != null && r.end!!.isBefore(Instant.now())) {
            limitPlayers.remove(id)
            saveObRestrictions()
            return@listenTo
        }
        sendObRestrictionMessage(player, r)
        team = teams.spectateTeam
    }
}

// 禁止受限玩家附身单位/建造（受限玩家无单位可用，无法建造）
listenPacket2Server<PlayerSpawnCallPacket> { con, _ ->
    val player = con.player ?: return@listenPacket2Server true
    val id = PlayerData[player].id
    isObActive(id)
}

command("forceOB", "{tr command.forceOB.desc}".with()) {
    usage = "{tr usage.forceOB}"
    permission = "wayzer.admin.forceOb"
    body {
        val target = getTarget()
        val id = PlayerData[target].id
        if (id in limitPlayers) {
            limitPlayers.remove(id)
            saveObRestrictions()
            // 解除附身后再恢复队伍
            Call.unitClear(target)
            teams.changeTeam(target)
            returnReply("{tr voteOb.reply.restrictionLifted}".with())
        }
        val reason = getInput("{tr voteOb.input.reasonName}".with("receiver" to (player ?: CommandContext.ConsoleReceiver)).toString(), "{tr voteOb.reply.needReason}".with())
        val now = Instant.now()
        limitPlayers[id] = ObRestriction(reason, player?.name ?: "Server", now, null)
        saveObRestrictions()
        // 强制观战前先解除附身
        Call.unitClear(target)
        teams.changeTeam(target, teams.spectateTeam)
        ForcedObEvent(target.uuid()).emitAsync()
        broadcast(
            "{tr voteOb.broadcast.adminForceOb}".with("target" to target, "reason" to reason)
        )
    }
}
