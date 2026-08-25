@file:Depends("coreLibrary/extApi/rpcService", "远程调用")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("coreMindustry/menu", "菜单选人")
@file:Depends("coreMindustry/util/textInput", "输入时长")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("wayzer/user/lang", "PlayerData.timezone")
@file:Depends("wayzer/user/shortID", "数字UID")
@file:Implement(BanService::class)

package wayzer.user

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.get
import coreLibrary.lib.parseTimeZone
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.util.textInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wayzer.VoteEvent
import wayzer.user.BanService
import wayzer.user.timezone
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.ceil

val rpcService by lazy { Services.get<coreLib.extApi.RpcService>().get() }
val store get() = rpcService.get<PlayerBanStore>()

private val globalProps by lazy {
    val props = java.util.Properties()
    val file = Config.dataDir.resolve("global.properties")
    if (file.exists()) file.bufferedReader().use { props.load(it) }
    props
}
fun globalLink(key: String): String = globalProps.getProperty(key) ?: ""

// 封禁时长限制(分钟), 普通玩家封禁时长不能超过此值, 管理员和终端可无视
val banMaxTime by config.key(60, "普通玩家封禁时长上限(分钟), 0表示不限制, 管理员和终端可无视")

// 风纪委员封禁时长上限(分钟), 0表示不限制, 管理员和终端可无视
val moderatorBanMaxTime by config.key(10080, "风纪委员封禁时长上限(分钟), 0表示不限制, 管理员和终端可无视")
// 针对风纪委员的投票通过阈值(0-1), 达到该比例赞成才可通过
val moderatorVoteThreshold by config.key(0.8, "针对风纪委员的投票通过阈值(0-1)")

// 是否为风纪委员
suspend fun Player.isModerator(): Boolean = hasPermission("wayzer.moderator")

// 是否为管理角色(管理员或风纪委员)
suspend fun Player.isOpRole(): Boolean = admin || isModerator()

// 针对某目标的投票通过阈值(目标为风纪委员时用配置阈值, 否则默认 0.6)
suspend fun voteThresholdFor(target: Player): Double =
    if (target.hasPermission("wayzer.moderator")) moderatorVoteThreshold else 0.6
export(::voteThresholdFor)

// 封禁白名单文件: 每行一个 uid/uuid/ip, #开头为注释
private val banWhitelistFile: File get() = File(Config.dataDir, "ban_whitelist.txt")

fun loadBanWhitelist(): Set<String> {
    if (!banWhitelistFile.exists()) {
        banWhitelistFile.parentFile?.mkdirs()
        banWhitelistFile.writeText(
            """
            # 封禁白名单
            # 每行一个 uid(数字)/uuid/ip, 以#开头的行将被忽略
            # 白名单内的玩家无法被普通玩家封禁, 管理员和终端可无视白名单
            """.trimIndent() + "\n"
        )
    }
    return banWhitelistFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()
}

/** 检查目标是否在封禁白名单中(支持 uid/uuid/ip) */
fun isBanWhitelisted(uid: String?, uuid: String?, ip: String?): Boolean {
    val whitelist = loadBanWhitelist()
    if (uid != null && whitelist.contains(uid)) return true
    if (uuid != null && whitelist.contains(uuid)) return true
    if (ip != null && whitelist.contains(ip)) return true
    return false
}

/** 获取离线玩家名字 */
fun lastNameByUuid(uuid: String): String = netServer.admins.getInfoOptional(uuid)?.lastName ?: uuid

fun Player.kick(ban: PlayerBan) {
    val player = this
    val tz = PlayerData[player].timezone
    val zone = parseTimeZone(tz)
    val formatter = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")
    fun format(instant: Instant) = instant.atZone(zone).format(formatter)
    val operatorDisplay = ban.operatorName ?: "Server"
    val targetDisplay = ban.targetName ?: "{tr ban.reply.unknown}".with("receiver" to this).toString()
    val ipBanStatus = if (ban.ip != null)
        "{tr ban.kick.ipBanned}".with("receiver" to this).toString()
    else
        "{tr ban.kick.ipNotBanned}".with("receiver" to this).toString()
    kick(
        "{tr ban.kick.message}".with(
            "receiver" to this,
            "targetDisplay" to targetDisplay,
            "operatorDisplay" to operatorDisplay,
            "reason" to ban.reason,
            "recordId" to ban.recordId,
            "ipBanStatus" to ipBanStatus,
            "createTime" to format(ban.createTime),
            "endTime" to format(ban.endTime),
            "qqGroup" to globalLink("qq.group.number"),
            "discord" to globalLink("discord.link")
        ).toString(), 0
    )
}

// 连接阶段封禁检查: 封禁中的玩家在 ConnectPacket 阶段直接拒绝(不加载世界数据)
listen<EventType.PlayerConnect> {
    launch(Dispatchers.IO) {
        val ban = store.findNotEnd(PlayerData[it.player].id)
            ?: it.player.con?.address?.let { store.findByIp(it) }
            ?: return@launch
        withContext(Dispatchers.game) {
            it.player.kick(ban)
        }
    }
}

suspend fun ban(player: PlayerData, time: Int, reason: String, operate: Player?, banIp: Boolean = false) {
    val operatorName = operate?.name ?: null
    val ip = if (banIp) player.player?.con?.address else null
    val ban = withContext(Dispatchers.IO) {
        store.create(
            player.ids,
            player.name,
            Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id },
            operatorName,
            ip
        )
    }
    Groups.player.filter { PlayerData[it].id in player.ids }.forEach {
        it.kick(ban)
        val opDisplay = operatorName ?: "Server"
        broadcast("{tr ban.broadcast.ban}".with("operator" to opDisplay, "target" to it, "reason" to reason))
    }
}

/**
 * 通过 uuid 封禁(支持离线玩家)
 * @param uuid 目标玩家uuid
 * @param time 封禁时长(分钟)
 * @param reason 封禁原因
 * @param operate 操作者(null表示终端)
 * @param banIp 是否封禁IP(仅对在线玩家有效)
 */
suspend fun banByUuid(uuid: String, time: Int, reason: String, operate: Player?, banIp: Boolean = false) {
    val operatorName = operate?.name ?: null
    val targetName = lastNameByUuid(uuid)
    val ip = if (banIp) Groups.player.find { it.uuid() == uuid }?.con?.address else null
    val ban = withContext(Dispatchers.IO) {
        store.create(
            setOf(uuid),
            targetName,
            Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id },
            operatorName,
            ip
        )
    }
    Groups.player.find { it.uuid() == uuid }?.let {
        it.kick(ban)
        val opDisplay = operatorName ?: "Server"
        broadcast("{tr ban.broadcast.ban}".with("operator" to opDisplay, "target" to it, "reason" to reason))
    }
}

/**
 * 通过 IP 封禁
 * @param ip 目标IP
 * @param time 封禁时长(分钟)
 * @param reason 封禁原因
 * @param operate 操作者(null表示终端)
 */
suspend fun banByIp(ip: String, time: Int, reason: String, operate: Player?) {
    val operatorName = operate?.name ?: null
    val target = Groups.player.find { it.con?.address == ip }
    val targetName = target?.name ?: ip
    val ids = target?.let { setOf(it.uuid()) } ?: emptySet()
    val ban = withContext(Dispatchers.IO) {
        store.create(
            ids,
            targetName,
            Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id },
            operatorName,
            ip
        )
    }
    target?.let {
        it.kick(ban)
        val opDisplay = operatorName ?: "Server"
        broadcast("{tr ban.broadcast.ban}".with("operator" to opDisplay, "target" to it, "reason" to reason))
    }
}

// banX 命令解析结果
private data class BanTarget(
    val uuid: String?,      // 目标uuid(uid/uuid格式时)
    val uid: String?,       // 目标数字uid
    val ip: String?,        // 目标IP(ip格式时)
    val player: Player?,    // 在线玩家(可能为null, 离线封禁)
    val reasonArgs: List<String> // 剩余参数(原因)
)

command("banX", "{tr command.banX.desc}".with()) {
    usage = "{tr usage.banX}"
    body {
        val operator = player

        // ===== 菜单流程: 玩家无参数执行, 弹菜单选在线玩家 =====
        // ===== 菜单流程: 玩家无参数执行, 管理角色弹封禁类型菜单, 普通玩家弹人选 =====
        if (operator != null && arg.isEmpty()) {
            val isOp = operator.isOpRole()

            // 目标解析结果: 优先在线玩家, 其次 uuid/ip(离线/类型封禁)
            var target: Player? = null
            var offlineUuid: String? = null
            var offlineIp: String? = null

            if (isOp) {
                // 1. 管理角色: 选择封禁类型
                var type: String? = null
                MenuBuilder<String> {
                    title = "{tr ban.menu.type.title}".with("receiver" to operator).toString()
                    option("{tr ban.menu.type.player}".with("receiver" to operator).toString()) { "player" }
                    option("{tr ban.menu.type.uid}".with("receiver" to operator).toString()) { "uid" }
                    option("{tr ban.menu.type.uuid}".with("receiver" to operator).toString()) { "uuid" }
                    option("{tr ban.menu.type.ip}".with("receiver" to operator).toString()) { "ip" }
                }.sendTo(operator, 60_000)?.let { type = it }
                when (type ?: returnReply("{tr ban.reply.cancelledSelect}".with())) {
                    "player" -> {
                        PagedMenuBuilder(Groups.player.toList().filter { it != operator }) {
                            option(it.name) { target = it }
                        }.apply {
                            title = "{tr ban.menu.selectTarget.title}".with("receiver" to operator).toString()
                            sendTo(operator, 60_000)
                        }
                    }
                    "uid" -> {
                        val uid = textInput(operator, "{tr ban.menu.type.uidInput}".with("receiver" to operator).toString(), isNumeric = true)
                            ?.toIntOrNull() ?: returnReply("{tr ban.reply.cancelledSelect}".with())
                        val uuid = uidToUuid(uid) ?: returnReply("{tr ban.reply.uidNotFound}".with("uid" to uid))
                        offlineUuid = uuid
                        target = Groups.player.find { it.uuid() == uuid }
                    }
                    "uuid" -> {
                        val uuid = textInput(operator, "{tr ban.menu.type.uuidInput}".with("receiver" to operator).toString())
                            ?.trim()?.lowercase() ?: returnReply("{tr ban.reply.cancelledSelect}".with())
                        val normalized = uuid.replace("-", "")
                        if (!normalized.matches(Regex("""^[0-9a-f]{32}$""")))
                            returnReply("{tr ban.reply.unrecognizedTarget}".with())
                        offlineUuid = normalized
                        target = Groups.player.find { it.uuid() == normalized }
                    }
                    "ip" -> {
                        val ip = textInput(operator, "{tr ban.menu.type.ipInput}".with("receiver" to operator).toString())
                            ?.trim() ?: returnReply("{tr ban.reply.cancelledSelect}".with())
                        if (!ip.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")))
                            returnReply("{tr ban.reply.unrecognizedTarget}".with())
                        offlineIp = ip
                        target = Groups.player.find { it.con?.address == ip }
                    }
                }
            } else {
                // 普通玩家: 弹在线玩家菜单
                PagedMenuBuilder(Groups.player.toList().filter { it != operator }) {
                    option(it.name) { target = it }
                }.apply {
                    title = "{tr ban.menu.selectTarget.title}".with("receiver" to operator).toString()
                    sendTo(operator, 60_000)
                }
            }
            if (target == null && offlineUuid == null && offlineIp == null)
                returnReply("{tr ban.reply.cancelledSelect}".with())

            // 2. 管理员豁免检查 (仅在线目标)
            if (target != null && target.hasPermission("wayzer.admin.skipKick")) {
                returnReply("{tr ban.reply.targetIsAdmin}".with("target" to target))
            }

            // 3. 输入时长
            val timeInput = textInput(operator, "{tr ban.reply.inputDuration}".with("receiver" to operator).toString(), isNumeric = true)
                ?: returnReply("{tr ban.reply.cancelledSelect}".with())
            val time = timeInput.toIntOrNull() ?: returnReply("{tr ban.reply.timeInvalid}".with())
            if (time <= 0) returnReply("{tr ban.reply.timeMustPositive}".with())

            // 4. 时长限制: 管理员不限; 风纪委员受 moderatorBanMaxTime; 普通玩家受 banMaxTime
            when {
                operator.admin -> {}
                operator.isModerator() -> if (moderatorBanMaxTime > 0 && time > moderatorBanMaxTime)
                    returnReply("{tr ban.reply.exceedMaxTime}".with("max" to moderatorBanMaxTime))
                else -> if (banMaxTime > 0 && time > banMaxTime)
                    returnReply("{tr ban.reply.exceedMaxTime}".with("max" to banMaxTime))
            }

            // 5. 白名单检查 (管理角色豁免)
            if (!isOp && isBanWhitelisted(null, target?.uuid(), target?.con?.address)) {
                returnReply("{tr ban.reply.targetWhitelisted}".with())
            }

            // 6. 输入原因
            val reason = textInput(operator, "{tr ban.reply.inputReason}".with("receiver" to operator).toString())
                ?.ifBlank { null } ?: returnReply("{tr ban.reply.cancelledReason}".with())

            // 7. IP 封禁: 管理角色弹菜单询问
            val banIp = if (isOp) {
                var choice: Boolean? = null
                MenuBuilder<Boolean> {
                    title = "{tr ban.menu.ipBan.title}".with("receiver" to operator).toString()
                    msg = "{tr ban.menu.ipBan.msg}".with("receiver" to operator, "target" to (target ?: offlineUuid ?: offlineIp ?: "?")).toString()
                    option("{tr ban.menu.ipBan.yes}".with("receiver" to operator).toString()) { true }
                    option("{tr ban.menu.ipBan.no}".with("receiver" to operator).toString()) { false }
                }.sendTo(operator, 60_000)?.let { choice = it }
                choice ?: returnReply("{tr ban.reply.cancelledSelect}".with())
            } else {
                false
            }

            // 8. 执行
            if (isOp) {
                when {
                    offlineIp != null -> {
                        banByIp(offlineIp, time, reason, operator)
                        reply("{tr ban.reply.success}".with(
                            "receiver" to operator, "target" to offlineIp, "time" to time, "reason" to reason
                        ))
                    }
                    offlineUuid != null -> {
                        banByUuid(offlineUuid, time, reason, operator, banIp)
                        reply("{tr ban.reply.success}".with(
                            "receiver" to operator, "target" to lastNameByUuid(offlineUuid), "time" to time, "reason" to reason
                        ))
                    }
                    target != null -> {
                        ban(PlayerData[target], time, reason, operator, banIp)
                        reply("{tr ban.reply.success}".with(
                            "receiver" to operator, "target" to target.name, "time" to time, "reason" to reason
                        ))
                    }
                    else -> returnReply("{tr ban.reply.targetNotFound}".with())
                }
                if (banIp) reply("{tr ban.reply.ipBanEnabled}".with())
            } else {
                // 普通玩家发起投票 (仅支持在线玩家, banIp 强制 false)
                if (target == null) {
                    returnReply("{tr ban.reply.offlineVoteNotAllowed}".with())
                }
                val snapshot = PlayerData[target]
                // 目标为风纪委员时提高通过阈值(默认 80%)
                val targetIsMod = target.isModerator()
                val event = VoteEvent(
                    thisScript, operator,
                    voteDesc = "{tr ban.reply.voteKickDesc}".with("target" to target),
                    extDesc = "{tr ban.reply.voteKickExt}".with("receiver" to operator, "reason" to reason).toString(),
                    requireNum = { all ->
                        val threshold = if (targetIsMod) moderatorVoteThreshold else 0.6
                        ceil(all * threshold).toInt()
                    }
                )
                if (event.awaitResult()) {
                    ban(snapshot, time, "{tr ban.voteKickReason}".with("receiver" to operator, "reason" to reason).toString(), operator, false)
                }
            }
            return@body
        }

        // ===== 命令行流程: banX <值> <时长> [原因] [--ip] (自动识别 uid/uuid/ip) =====
        if (operator == null && arg.isEmpty()) {
            returnReply("{tr ban.reply.consoleNeedPlayer}".with())
        }
        val ipFlag = arg.any { it.equals("--ip", ignoreCase = true) }
        // 先移除 --ip 标志, 避免其在中间位置打断参数索引解析
        arg = arg.filterNot { it.equals("--ip", ignoreCase = true) }
        val value = arg.getOrNull(0) ?: returnReply("{tr ban.reply.newFormatUsage}".with())

        // 1. 解析时长 (arg[1])
        val timeRaw = arg.getOrNull(1)
            ?: operator?.let { textInput(it, "{tr ban.reply.inputDuration}".with("receiver" to it).toString(), isNumeric = true) }
            ?: returnReply("{tr ban.reply.consoleNeedTime}".with())
        val time = timeRaw.toIntOrNull() ?: returnReply("{tr ban.reply.timeInvalid}".with())
        if (time <= 0) returnReply("{tr ban.reply.timeMustPositive}".with())

        val reasonArgs = arg.drop(2)

        // 2. 自动识别目标类型 (IP > uid > uuid)
        val banTarget: BanTarget = when {
            // IPv4 格式: 仅终端可用
            value.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) -> {
                if (operator != null) returnReply("{tr ban.reply.ipBanNoPermission}".with())
                BanTarget(null, null, value, Groups.player.find { it.con?.address == value }, reasonArgs)
            }
            // 纯数字 → uid
            value.toIntOrNull() != null -> {
                val uid = value.toInt()
                val uuid = uidToUuid(uid) ?: returnReply("{tr ban.reply.uidNotFound}".with("uid" to uid))
                BanTarget(uuid, value, null, Groups.player.find { it.uuid() == uuid }, reasonArgs)
            }
            // UUID 格式 (32位hex 或 8-4-4-4-12 带连字符)
            value.replace("-", "").matches(Regex("""^[0-9a-fA-F]{32}$""")) -> {
                val normalized = value.replace("-", "")
                BanTarget(normalized, uidCache[normalized]?.toString(), null, Groups.player.find { it.uuid() == normalized }, reasonArgs)
            }
            else -> returnReply("{tr ban.reply.unrecognizedTarget}".with())
        }

        // 3. 检查目标是否为管理员 (仅在线玩家可检查)
        if (banTarget.player != null && banTarget.player.hasPermission("wayzer.admin.skipKick")) {
            returnReply("{tr ban.reply.targetIsAdmin}".with("target" to banTarget.player))
        }

        // 4. 时长限制检查: 管理员不限; 风纪委员受 moderatorBanMaxTime; 普通玩家受 banMaxTime
        if (operator != null) {
            when {
                operator.admin -> {}
                operator.isModerator() -> if (moderatorBanMaxTime > 0 && time > moderatorBanMaxTime)
                    returnReply("{tr ban.reply.exceedMaxTime}".with("max" to moderatorBanMaxTime))
                else -> if (banMaxTime > 0 && time > banMaxTime)
                    returnReply("{tr ban.reply.exceedMaxTime}".with("max" to banMaxTime))
            }
        }

        // 5. 白名单检查 (管理角色豁免, 管理员和终端可无视)
        if (operator != null && !operator.isOpRole() && isBanWhitelisted(banTarget.uid, banTarget.uuid, banTarget.ip)) {
            returnReply("{tr ban.reply.targetWhitelisted}".with())
        }

        // 6. 输入原因 (--ip 已在解析前移除, reasonArgs 不含 --ip)
        val reason = banTarget.reasonArgs.joinToString(" ").ifBlank {
            operator?.let {
                textInput(it, "{tr ban.reply.inputReason}".with("receiver" to it).toString()) ?: returnReply("{tr ban.reply.cancelledReason}".with())
            } ?: returnReply("{tr ban.reply.consoleNeedReason}".with())
        }.ifBlank { returnReply("{tr ban.reply.mustHaveReason}".with()) }

        // 7. 确定 banIp (普通玩家不可 IP 封禁; 管理角色弹菜单确认或 --ip 标志)
        val banIp = when {
            operator != null && !operator.isOpRole() -> false
            ipFlag -> true
            operator != null && banTarget.player != null -> {
                // 管理角色 + 在线目标: 弹菜单询问是否同时封禁 IP
                var choice: Boolean? = null
                MenuBuilder<Boolean> {
                    title = "{tr ban.menu.ipBan.title}".with("receiver" to operator).toString()
                    msg = "{tr ban.menu.ipBan.msg}".with("receiver" to operator, "target" to banTarget.player).toString()
                    option("{tr ban.menu.ipBan.yes}".with("receiver" to operator).toString()) { true }
                    option("{tr ban.menu.ipBan.no}".with("receiver" to operator).toString()) { false }
                }.sendTo(operator, 60_000)?.let { choice = it }
                choice ?: returnReply("{tr ban.reply.cancelledSelect}".with())
            }
            else -> false
        }

        // 8. 执行封禁
        if (operator == null || operator.isOpRole()) {
            // 控制台或管理角色(管理员/风纪委员): 直接执行
            val targetDisplay = banTarget.player?.name
                ?: banTarget.uuid?.let { lastNameByUuid(it) }
                ?: banTarget.ip ?: "{tr ban.reply.unknown}".with().toString()
            when {
                banTarget.ip != null -> banByIp(banTarget.ip, time, reason, operator)
                banTarget.uuid != null -> banByUuid(banTarget.uuid, time, reason, operator, banIp)
                else -> returnReply("{tr ban.reply.targetNotFound}".with())
            }
            reply("{tr ban.reply.success}".with(
                "receiver" to (operator ?: CommandContext.ConsoleReceiver),
                "target" to targetDisplay,
                "time" to time,
                "reason" to reason
            ))
            if (banIp) reply("{tr ban.reply.ipBanEnabled}".with())
        } else {
            // 普通玩家发起投票 (仅支持在线玩家)
            if (banTarget.player == null) {
                returnReply("{tr ban.reply.offlineVoteNotAllowed}".with())
            }
            val snapshot = PlayerData[banTarget.player]
            // 目标为风纪委员时提高通过阈值(默认 80%)
            val targetIsMod = banTarget.player.isModerator()
            val event = VoteEvent(
                thisScript, operator,
                voteDesc = "{tr ban.reply.voteKickDesc}".with("target" to banTarget.player),
                extDesc = "{tr ban.reply.voteKickExt}".with("receiver" to operator, "reason" to reason).toString(),
                requireNum = { all ->
                    val threshold = if (targetIsMod) moderatorVoteThreshold else 0.6
                    ceil(all * threshold).toInt()
                }
            )
            if (event.awaitResult()) {
                ban(snapshot, time, "{tr ban.voteKickReason}".with("receiver" to operator, "reason" to reason).toString(), operator, false)
            }
        }
    }
}

command("unbanX", "{tr command.unbanX.desc}".with()) {
    usage = "<id>"
    requirePermission("wayzer.admin.unban")
    body {
        if (arg.isEmpty()) returnReply("{tr ban.reply.unbanUsage}".with())
        val id = arg[0].toIntOrNull() ?: returnReply("{tr ban.reply.unbanUsage}".with())
        // 只允许解除生效中的封禁; 已过期的记录保留在数据库中(id 递增, 不删除)
        val ban = withContext(Dispatchers.IO) { store.getById(id) }
            ?: returnReply("{tr ban.reply.banNotFound}".with())
        if (!ban.endTime.isAfter(Instant.now())) {
            returnReply("{tr ban.reply.banExpired}".with("recordId" to id))
        }
        val removed = withContext(Dispatchers.IO) { store.delete(id) }
            ?: returnReply("{tr ban.reply.banNotFound}".with())
        logger.info("unban ${removed.ids} ${removed.endTime} ${removed.reason}")
        reply("{tr ban.reply.unbanSuccess}".with("reason" to removed.reason))
    }
}

command("bansX", "{tr command.bansX.desc}".with()) {
    requirePermission("wayzer.admin.unban")
    body {
        val now = Instant.now()
        val bans = withContext(Dispatchers.IO) { store.listAll() }
            .filter { it.endTime.isAfter(now) }
        if (bans.isEmpty()) returnReply("{tr ban.reply.noBans}".with())
        val text = bans.joinToString("\n") { ban ->
            val operator = ban.operatorName ?: "Server"
            val target = ban.targetName ?: "{tr ban.reply.unknown}".with("receiver" to (player ?: CommandContext.ConsoleReceiver)).toString()
            val minutes = maxOf(1L, Duration.between(now, ban.endTime).toMinutes()) // 不足1分钟显示1
            "{tr ban.reply.bansX.row}".with(
                "receiver" to (player ?: CommandContext.ConsoleReceiver),
                "recordId" to ban.recordId, "target" to target,
                "operator" to operator, "reason" to ban.reason, "minutes" to minutes
            ).toString()
        }
        reply("{tr ban.reply.bansX.header}".with("count" to bans.size, "text" to text))
    }
}
