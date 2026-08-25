@file:Depends("coreMindustry/menu", "菜单选人")
@file:Depends("coreMindustry/util/textInput", "输入时长")

package wayzer.ext

import cf.wayzer.scriptAgent.Config
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.util.textInput
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.gen.SendChatMessageCallPacket
import wayzer.lib.PlayerData
import java.io.File
import java.time.Duration
import java.time.Instant

// ═══════════════════════════════════════════
// 阻止指定玩家执行命令(管理员/风纪委员)
// 可阻止的命令名单: data/blockable_commands.txt(每行一个命令名, 不带 /)
// 风纪委员最多阻止 moderatorBlockMaxTime 分钟, 管理员不限
// ═══════════════════════════════════════════

val moderatorBlockMaxTime by config.key(60, "风纪委员阻止命令的最大时长(分钟), 0=不限制, 管理员不限")

// ===== 可阻止命令名单 =====
val blockableFile: File get() = File(Config.dataDir, "blockable_commands.txt")

fun loadBlockableCommands(): List<String> {
    if (!blockableFile.exists()) {
        blockableFile.parentFile?.mkdirs()
        blockableFile.writeText(
            """
            # 可被阻止的命令名单
            # 每行一个命令名(不带 /), 管理员/风纪委员只能从名单中选择要阻止的命令
            # 以 # 开头的行是注释, 空行忽略
            # 示例:
            #   vote
            #   ob
            #   music
            """.trimIndent() + "\n"
        )
    }
    return blockableFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .distinct()
}

// ===== 阻止记录: playerId -> (command -> expireTime) =====
@Savable(false)
val blockRecords = mutableMapOf<String, MutableMap<String, Instant>>()
customLoad(::blockRecords) { blockRecords.putAll(it) }

fun isBlocked(playerId: String, command: String): Instant? {
    val rec = blockRecords[playerId] ?: return null
    val exp = rec[command] ?: return null
    if (exp.isBefore(Instant.now())) {
        rec.remove(command)
        if (rec.isEmpty()) blockRecords.remove(playerId)
        return null
    }
    return exp
}

// ===== 拦截被阻止的命令 =====
listenPacket2ServerAsync<SendChatMessageCallPacket> { con, packet ->
    val player = con.player ?: return@listenPacket2ServerAsync true
    val msg = packet.message.trimStart()
    if (!msg.startsWith("/")) return@listenPacket2ServerAsync true
    val command = msg.substring(1).substringBefore(' ').substringBefore('\n').lowercase()
    if (command.isBlank()) return@listenPacket2ServerAsync true
    val exp = isBlocked(PlayerData[player].id, command)
    if (exp != null) {
        val left = Duration.between(Instant.now(), exp).toMinutes().coerceAtLeast(1)
        logger.info("[cmdBlock] 拦截: player=${player.name} cmd=$command 剩余=${left}分钟")
        Call.infoMessage(con, "{tr commandBlock.notify.blocked}".with("command" to command, "left" to left).toString())
        return@listenPacket2ServerAsync false
    }
    true
}

// ===== 阻止命令 =====
command("blockcmd", "{tr command.blockCmd.desc}".with()) {
    permission = "wayzer.admin.blockCmd"
    usage = "菜单操作"
    body {
        val operator = player ?: returnReply("{tr commandBlock.reply.playerOnly}".with())
        if (!operator.admin && !operator.hasPermission("wayzer.moderator"))
            returnReply("{tr commandBlock.reply.noPermission}".with())

        // 1. 选目标玩家
        var target: Player? = null
        PagedMenuBuilder(Groups.player.toList().filter { it != operator }) {
            option(it.name) { target = it }
        }.apply {
            title = "{tr commandBlock.menu.selectTarget.title}".with("receiver" to operator).toString()
            sendTo(operator, 60_000)
        }
        val targetPlayer = target ?: returnReply("{tr commandBlock.reply.cancelled}".with())

        // 2. 从允许名单选命令
        val cmds = loadBlockableCommands()
        if (cmds.isEmpty()) returnReply("{tr commandBlock.reply.noBlockable}".with())
        var chosen: String? = null
        MenuBuilder<String> {
            title = "{tr commandBlock.menu.selectCommand.title}".with("receiver" to operator).toString()
            cmds.forEach { c -> option("[white]/$c") { c } }
        }.sendTo(operator, 60_000)?.let { chosen = it }
        val command = chosen ?: returnReply("{tr commandBlock.reply.cancelled}".with())

        // 3. 输入时长
        val time = textInput(operator, "{tr commandBlock.input.duration.title}".with("receiver" to operator).toString())
            ?.toIntOrNull() ?: returnReply("{tr commandBlock.reply.cancelled}".with())
        if (time <= 0) returnReply("{tr commandBlock.reply.durationInvalid}".with())

        // 4. 风纪委员时长上限
        if (!operator.admin && moderatorBlockMaxTime > 0 && time > moderatorBlockMaxTime)
            returnReply("{tr commandBlock.reply.moderatorTimeLimit}".with("max" to moderatorBlockMaxTime))

        // 5. 记录
        val id = PlayerData[targetPlayer].id
        blockRecords.getOrPut(id) { mutableMapOf() }[command] = Instant.now().plus(Duration.ofMinutes(time.toLong()))
        logger.info("[cmdBlock] 记录: playerId=$id cmd=$command ${time}分钟, 当前记录=${blockRecords}")
        reply("{tr commandBlock.reply.blocked}".with("target" to targetPlayer.name, "command" to command, "time" to time))
    }
}

// ===== 解除阻止 =====
command("unblockcmd", "{tr command.unblockCmd.desc}".with()) {
    permission = "wayzer.admin.blockCmd"
    usage = "菜单操作"
    body {
        val operator = player ?: returnReply("{tr commandBlock.reply.playerOnly}".with())
        if (!operator.admin && !operator.hasPermission("wayzer.moderator"))
            returnReply("{tr commandBlock.reply.noPermission}".with())

        var target: Player? = null
        PagedMenuBuilder(Groups.player.toList().filter { it != operator }) {
            option(it.name) { target = it }
        }.apply {
            title = "{tr commandBlock.menu.selectPlayer.title}".with("receiver" to operator).toString()
            sendTo(operator, 60_000)
        }
        val targetPlayer = target ?: returnReply("{tr commandBlock.reply.cancelled}".with())

        val rec = blockRecords[PlayerData[targetPlayer].id]
        if (rec.isNullOrEmpty()) returnReply("{tr commandBlock.reply.noRecords}".with())

        var chosen: String? = null
        MenuBuilder<String> {
            title = "{tr commandBlock.menu.selectUnblock.title}".with("receiver" to operator).toString()
            rec.keys.forEach { c -> option("[white]/$c") { c } }
        }.sendTo(operator, 60_000)?.let { chosen = it }
        val command = chosen ?: returnReply("{tr commandBlock.reply.cancelled}".with())

        rec.remove(command)
        if (rec.isEmpty()) blockRecords.remove(PlayerData[targetPlayer].id)
        reply("{tr commandBlock.reply.unblocked}".with("target" to targetPlayer.name, "command" to command))
    }
}
