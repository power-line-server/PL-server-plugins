@file:Depends("coreLibrary/extApi/rpcService", "远程调用")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("coreMindustry/menu", "菜单选人")
@file:Depends("coreMindustry/util/textInput", "输入时长")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("wayzer/user/lang", "PlayerData.timezone")
@file:Depends("wayzer/user/shortID", "数字UID")
@file:Implement(MuteService::class)

package wayzer.user

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.get
import coreLibrary.lib.parseTimeZone
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.util.textInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coreMindustry.lib.MsgType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import wayzer.VoteEvent
import wayzer.lib.PlayerData
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

val rpcService by lazy { Services.get<coreLib.extApi.RpcService>().get() }
val muteStore get() = rpcService.get<PlayerMuteStore>()

// 禁言时长限制(分钟), 普通玩家禁言时长不能超过此值, 管理员和终端可无视
val muteMaxTime by config.key(60, "普通玩家禁言时长上限(分钟), 0表示不限制, 管理员和终端可无视")

// 禁言白名单文件: 每行一个 uid/uuid/ip, #开头为注释
private val muteWhitelistFile: File get() = File(Config.dataDir, "mute_whitelist.txt")

fun loadMuteWhitelist(): Set<String> {
    if (!muteWhitelistFile.exists()) {
        muteWhitelistFile.parentFile?.mkdirs()
        muteWhitelistFile.writeText(
            """
            # 禁言白名单
            # 每行一个 uid(数字)/uuid/ip, 以#开头的行将被忽略
            # 白名单内的玩家无法被普通玩家禁言, 管理员和终端可无视白名单
            """.trimIndent() + "\n"
        )
    }
    return muteWhitelistFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()
}

/** 检查目标是否在禁言白名单中(支持 uid/uuid/ip) */
fun isMuteWhitelisted(uid: String?, uuid: String?, ip: String?): Boolean {
    val whitelist = loadMuteWhitelist()
    if (uid != null && whitelist.contains(uid)) return true
    if (uuid != null && whitelist.contains(uuid)) return true
    if (ip != null && whitelist.contains(ip)) return true
    return false
}

/** 获取离线玩家名字 */
fun muteTargetName(uuid: String): String = netServer.admins.getInfoOptional(uuid)?.lastName ?: uuid

private fun formatMuteTime(instant: Instant, receiver: Player): String {
    val tz = PlayerData[receiver].timezone
    val zone = parseTimeZone(tz)
    return instant.atZone(zone).format(DateTimeFormatter.ofPattern("M.d-HH:mm:ss"))
}

// ===== 生效禁言内存缓存 (ChatFilter 高频查询用) =====
private val muteById = ConcurrentHashMap<String, PlayerMute>() // uuid -> 生效禁言
private val muteByIp = ConcurrentHashMap<String, PlayerMute>() // ip -> 生效禁言
private val lastWarnAt = ConcurrentHashMap<String, Long>() // 弹窗冷却: uuid -> 上次弹窗时间

private fun cacheMute(mute: PlayerMute) {
    mute.ids.forEach { muteById[it] = mute }
    mute.ip?.let { muteByIp[it] = mute }
}

private fun uncacheMute(mute: PlayerMute) {
    mute.ids.forEach { muteById.remove(it) }
    mute.ip?.let { muteByIp.remove(it) }
}

private fun refreshMuteCache() {
    muteById.clear()
    muteByIp.clear()
    val now = Instant.now()
    muteStore.listAll().filter { it.endTime.isAfter(now) }.forEach { cacheMute(it) }
}

/** 查询玩家当前生效禁言(uuid 优先, 其次 ip), 过期时惰性清理缓存 */
private fun activeMuteOf(player: Player): PlayerMute? {
    val now = Instant.now()
    val id = PlayerData[player].id
    var mute = muteById[id]
    if (mute != null && !mute.endTime.isAfter(now)) {
        muteById.remove(id)
        mute = null
    }
    if (mute == null) {
        val ip = player.con?.address
        mute = ip?.let { muteByIp[it] }
        if (mute != null && !mute.endTime.isAfter(now)) {
            ip?.let { muteByIp.remove(it) }
            mute = null
        }
    }
    return mute
}

// ===== 聊天拦截: 禁言玩家无法发送普通消息 =====
onEnable {
    // 预创建白名单文件, 避免首次调用时才生成
    loadMuteWhitelist()
    // 等 RPC 服务就绪后加载生效禁言到缓存
    launch {
        delay(2_000)
        refreshMuteCache()
    }
    val filter = Administration.ChatFilter { player, message ->
        val mute = activeMuteOf(player)
        if (mute == null) {
            message
        } else {
            // 弹窗提示(10秒冷却防刷屏), 并拦截消息
            val id = PlayerData[player].id
            val now = System.currentTimeMillis()
            if (now - (lastWarnAt[id] ?: 0L) > 10_000) {
                lastWarnAt[id] = now
                val operatorDisplay = mute.operatorName ?: "Server"
                val targetDisplay = mute.targetName ?: "{tr mute.reply.unknown}".with("receiver" to player).toString()
                player.sendMessage(
                    "{tr mute.chat.blocked}".with(
                        "receiver" to player,
                        "targetDisplay" to targetDisplay,
                        "operatorDisplay" to operatorDisplay,
                        "reason" to mute.reason,
                        "recordId" to mute.recordId,
                        "createTime" to formatMuteTime(mute.createTime, player),
                        "endTime" to formatMuteTime(mute.endTime, player)
                    ).toString(),
                    MsgType.InfoMessage
                )
            }
            null // 阻止消息发送
        }
    }
    // 插入到过滤链最前, 确保在 pvpChat 等转发型 filter 之前拦截
    netServer.admins.chatFilters.insert(0, filter)
    onDisable {
        netServer.admins.chatFilters.remove(filter)
    }
}

// ===== 禁言核心逻辑 =====

// MuteService 实现: 按签名自动匹配 @file:Implement
suspend fun mute(player: PlayerData, time: Int, reason: String, operate: Player?) {
    muteByUuid(player.uuid, time, reason, operate)
}

/** 通过 uuid 禁言(支持离线), 返回创建的禁言记录 */
suspend fun muteByUuid(uuid: String, time: Int, reason: String, operate: Player?): PlayerMute {
    val operatorName = operate?.name ?: null
    val targetName = muteTargetName(uuid)
    val mute = withContext(Dispatchers.IO) {
        muteStore.create(
            setOf(uuid), targetName,
            Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id },
            operatorName, null
        )
    }
    cacheMute(mute)
    Groups.player.find { it.uuid() == uuid }?.let { target ->
        val operatorDisplay = operatorName ?: "Server"
        target.sendMessage(
            "{tr mute.notify}".with(
                "receiver" to target,
                "operatorDisplay" to operatorDisplay,
                "reason" to reason,
                "endTime" to formatMuteTime(mute.endTime, target)
            ).toString(),
            MsgType.InfoMessage
        )
        broadcast("{tr mute.broadcast.mute}".with("operator" to operatorDisplay, "target" to target, "reason" to reason))
    }
    return mute
}

/** 通过 IP 禁言 */
suspend fun muteByIp(ip: String, time: Int, reason: String, operate: Player?): PlayerMute {
    val operatorName = operate?.name ?: null
    val target = Groups.player.find { it.con?.address == ip }
    val targetName = target?.name ?: ip
    val ids = target?.let { setOf(it.uuid()) } ?: emptySet()
    val mute = withContext(Dispatchers.IO) {
        muteStore.create(
            ids, targetName,
            Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id },
            operatorName, ip
        )
    }
    cacheMute(mute)
    target?.let { t ->
        val operatorDisplay = operatorName ?: "Server"
        t.sendMessage(
            "{tr mute.notify}".with(
                "receiver" to t,
                "operatorDisplay" to operatorDisplay,
                "reason" to reason,
                "endTime" to formatMuteTime(mute.endTime, t)
            ).toString(),
            MsgType.InfoMessage
        )
        broadcast("{tr mute.broadcast.mute}".with("operator" to operatorDisplay, "target" to t, "reason" to reason))
    }
    return mute
}

/** 查找目标当前生效禁言(uuid/ip), 用于 toggle */
suspend fun findActiveMute(uuid: String?, ip: String?): PlayerMute? = withContext(Dispatchers.IO) {
    var mute = uuid?.let { muteStore.findNotEnd(it) }
    if (mute == null && ip != null) mute = muteStore.findByIp(ip)
    mute
}

// mute 命令解析结果
private data class MuteTarget(
    val uuid: String?,      // 目标uuid(uid/uuid格式时)
    val uid: String?,       // 目标数字uid
    val ip: String?,        // 目标IP(ip格式时)
    val player: Player?,    // 在线玩家(可能为null, 离线禁言)
    val reasonArgs: List<String> // 剩余参数(原因)
)

command("mute", "{tr command.mute.desc}".with()) {
    aliases = listOf("禁言")
    usage = "{tr usage.mute}"
    body {
        val operator = player

        // ===== 菜单流程: 玩家无参数执行, 弹菜单选在线玩家 =====
        if (operator != null && arg.isEmpty()) {
            // 1. 选目标玩家
            var selected: Player? = null
            PagedMenuBuilder(Groups.player.toList().filter { it != operator }) {
                option(it.name) { selected = it }
            }.apply {
                title = "{tr mute.menu.selectTarget.title}".with("receiver" to operator).toString()
                sendTo(operator, 60_000)
            }
            val target = selected ?: returnReply("{tr mute.reply.cancelledSelect}".with())

            // 2. 管理员豁免检查
            if (target.hasPermission("wayzer.admin.skipKick")) {
                returnReply("{tr mute.reply.targetIsAdmin}".with("target" to target))
            }

            // 3. 已禁言提示 (解除禁言请使用 /unmute)
            val existing = findActiveMute(target.uuid(), target.con?.address)
            if (existing != null) {
                returnReply("{tr mute.reply.alreadyMuted}".with("target" to target))
            }

            // 4. 输入时长
            val time = textInput(operator, "{tr mute.reply.inputDuration}".with("receiver" to operator).toString(), isNumeric = true)
                ?.toIntOrNull() ?: returnReply("{tr mute.reply.cancelledSelect}".with())
            if (time <= 0) returnReply("{tr mute.reply.timeMustPositive}".with())

            // 5. 时长限制 (非管理员)
            if (!operator.admin && muteMaxTime > 0 && time > muteMaxTime) {
                returnReply("{tr mute.reply.exceedMaxTime}".with("max" to muteMaxTime))
            }

            // 6. 白名单检查 (非管理员)
            if (!operator.admin && isMuteWhitelisted(null, target.uuid(), target.con?.address)) {
                returnReply("{tr mute.reply.targetWhitelisted}".with())
            }

            // 7. 输入原因
            val reason = textInput(operator, "{tr mute.reply.inputReason}".with("receiver" to operator).toString())
                ?.ifBlank { null } ?: returnReply("{tr mute.reply.cancelledReason}".with())

            // 8. 执行: 管理员直接禁言, 普通玩家投票
            if (operator.admin) {
                muteByUuid(target.uuid(), time, reason, operator)
                reply("{tr mute.reply.success}".with(
                    "receiver" to operator, "target" to target.name, "time" to time, "reason" to reason
                ))
            } else {
                val event = VoteEvent(
                    thisScript, operator,
                    voteDesc = "{tr mute.reply.voteDesc}".with("target" to target),
                    extDesc = "{tr mute.reply.voteExt}".with("receiver" to operator, "reason" to reason).toString()
                )
                if (event.awaitResult()) {
                    muteByUuid(target.uuid(), time, "{tr mute.voteReason}".with("receiver" to operator, "reason" to reason).toString(), operator)
                }
            }
            return@body
        }

        // ===== 命令行流程: /mute <uid|uuid|ip> <时长> [理由] =====
        if (operator == null && arg.isEmpty()) {
            returnReply("{tr mute.reply.consoleNeedPlayer}".with())
        }
        val value = arg.getOrNull(0) ?: returnReply("{tr mute.reply.newFormatUsage}".with())

        // 1. 解析时长 (arg[1])
        val time = arg.getOrNull(1)?.toIntOrNull()
            ?: operator?.let { textInput(it, "{tr mute.reply.inputDuration}".with("receiver" to it).toString(), isNumeric = true)?.toIntOrNull() }
            ?: returnReply("{tr mute.reply.consoleNeedTime}".with())
        if (time <= 0) returnReply("{tr mute.reply.timeMustPositive}".with())

        val reasonArgs = arg.drop(2)

        // 2. 自动识别目标类型 (IP > uid > uuid)
        val muteTarget: MuteTarget = when {
            // IPv4 格式: 仅终端可用
            value.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) -> {
                if (operator != null) returnReply("{tr mute.reply.ipBanNoPermission}".with())
                MuteTarget(null, null, value, Groups.player.find { it.con?.address == value }, reasonArgs)
            }
            // 纯数字 → uid
            value.toIntOrNull() != null -> {
                val uid = value.toInt()
                val uuid = uidToUuid(uid) ?: returnReply("{tr mute.reply.uidNotFound}".with("uid" to uid))
                MuteTarget(uuid, value, null, Groups.player.find { it.uuid() == uuid }, reasonArgs)
            }
            // UUID 格式 (32位hex 或 8-4-4-4-12 带连字符)
            value.replace("-", "").matches(Regex("""^[0-9a-fA-F]{32}$""")) -> {
                val normalized = value.replace("-", "")
                MuteTarget(normalized, uidCache[normalized]?.toString(), null, Groups.player.find { it.uuid() == normalized }, reasonArgs)
            }
            else -> returnReply("{tr mute.reply.unrecognizedTarget}".with())
        }

        // 3. 检查目标是否为管理员 (仅在线玩家可检查)
        if (muteTarget.player != null && muteTarget.player.hasPermission("wayzer.admin.skipKick")) {
            returnReply("{tr mute.reply.targetIsAdmin}".with("target" to muteTarget.player))
        }

        // 4. 时长限制检查 (普通玩家受限, 管理员和终端可无视)
        if (operator != null && !operator.admin && muteMaxTime > 0 && time > muteMaxTime) {
            returnReply("{tr mute.reply.exceedMaxTime}".with("max" to muteMaxTime))
        }

        // 5. 白名单检查 (普通玩家受限, 管理员和终端可无视)
        if (operator != null && !operator.admin && isMuteWhitelisted(muteTarget.uid, muteTarget.uuid, muteTarget.ip)) {
            returnReply("{tr mute.reply.targetWhitelisted}".with())
        }

        // 6. 原因
        val reason = muteTarget.reasonArgs.joinToString(" ").ifBlank {
            operator?.let {
                textInput(it, "{tr mute.reply.inputReason}".with("receiver" to it).toString()) ?: returnReply("{tr mute.reply.cancelledReason}".with())
            } ?: returnReply("{tr mute.reply.consoleNeedReason}".with())
        }.ifBlank { returnReply("{tr mute.reply.mustHaveReason}".with()) }

        // 7. 目标已禁言则提示 (解除禁言请使用 /unmute)
        val targetDisplay = muteTarget.player?.name
            ?: muteTarget.uuid?.let { muteTargetName(it) }
            ?: muteTarget.ip ?: "{tr mute.reply.unknown}".with().toString()

        val existing = findActiveMute(muteTarget.uuid, muteTarget.ip)
        if (existing != null) {
            returnReply("{tr mute.reply.alreadyMuted}".with("target" to targetDisplay))
        }

        // 8. 执行禁言
        if (operator == null || operator.admin) {
            // 管理员/终端: 直接禁言
            when {
                muteTarget.ip != null -> muteByIp(muteTarget.ip, time, reason, operator)
                muteTarget.uuid != null -> muteByUuid(muteTarget.uuid, time, reason, operator)
                else -> returnReply("{tr mute.reply.targetNotFound}".with())
            }
            reply("{tr mute.reply.success}".with(
                "receiver" to (operator ?: CommandContext.ConsoleReceiver),
                "target" to targetDisplay, "time" to time, "reason" to reason
            ))
        } else {
            // 普通玩家: 投票禁言 (仅支持在线玩家)
            if (muteTarget.player == null) {
                returnReply("{tr mute.reply.offlineVoteNotAllowed}".with())
            }
            val event = VoteEvent(
                thisScript, operator,
                voteDesc = "{tr mute.reply.voteDesc}".with("target" to muteTarget.player),
                extDesc = "{tr mute.reply.voteExt}".with("receiver" to operator, "reason" to reason).toString()
            )
            if (event.awaitResult()) {
                muteByUuid(muteTarget.player.uuid(), time, "{tr mute.voteReason}".with("receiver" to operator, "reason" to reason).toString(), operator)
            }
        }
    }
}

command("unmute", "{tr command.unmute.desc}".with()) {
    usage = "{tr usage.unmute}"
    requirePermission("wayzer.admin.unban")
    body {
        if (arg.isEmpty()) returnReply("{tr mute.reply.unmuteUsage}".with())
        val id = arg[0].toIntOrNull() ?: returnReply("{tr mute.reply.unmuteUsage}".with())
        // 只允许解除生效中的禁言; 已过期的记录保留在数据库中(id 递增, 不删除)
        val mute = withContext(Dispatchers.IO) { muteStore.getById(id) }
            ?: returnReply("{tr mute.reply.muteNotFound}".with())
        if (!mute.endTime.isAfter(Instant.now())) {
            returnReply("{tr mute.reply.muteExpired}".with("recordId" to id))
        }
        val removed = withContext(Dispatchers.IO) { muteStore.delete(id) }
            ?: returnReply("{tr mute.reply.muteNotFound}".with())
        uncacheMute(removed)
        logger.info("unmute ${removed.ids} ${removed.endTime} ${removed.reason}")
        reply("{tr mute.reply.unmuteSuccess}".with("reason" to removed.reason))
    }
}

command("mutes", "{tr command.mutes.desc}".with()) {
    requirePermission("wayzer.admin.unban")
    body {
        val now = Instant.now()
        val mutes = withContext(Dispatchers.IO) { muteStore.listAll() }
            .filter { it.endTime.isAfter(now) }
        if (mutes.isEmpty()) returnReply("{tr mute.reply.noMutes}".with())
        val text = mutes.joinToString("\n") { mute ->
            val operator = mute.operatorName ?: "Server"
            val target = mute.targetName ?: "{tr mute.reply.unknown}".with("receiver" to (player ?: CommandContext.ConsoleReceiver)).toString()
            val minutes = maxOf(1L, Duration.between(now, mute.endTime).toMinutes()) // 不足1分钟显示1
            "{tr mute.reply.mutes.row}".with(
                "receiver" to (player ?: CommandContext.ConsoleReceiver),
                "recordId" to mute.recordId, "target" to target,
                "operator" to operator, "reason" to mute.reason, "minutes" to minutes
            ).toString()
        }
        reply("{tr mute.reply.mutes.header}".with("count" to mutes.size, "text" to text))
    }
}
