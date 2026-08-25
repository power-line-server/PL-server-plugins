@file:Depends("wayzer/user/ban", "封禁服务")

package wayzer.ext

import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.util.Services
import mindustry.gen.Call
import mindustry.gen.SendChatMessageCallPacket
import wayzer.lib.PlayerData
import wayzer.user.BanService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

// ═══════════════════════════════════════════
// 消息过滤: 禁止发送特定消息/指令
// 规则文件: data/message_filter.txt(每行一个正则, # 开头为注释)
// 命中规则的消息/指令被拦截并弹窗提示; 违规达到阈值自动 banX
// ═══════════════════════════════════════════

val mfMaxViolations by config.key(3, "违规次数达到该值触发 banX(指令同样计数)")
val mfBanMinutes by config.key(60, "触发 banX 的时长(分钟)")
val mfViolationResetMinutes by config.key(0, "违规计数重置间隔(分钟), 0=不重置")
val mfCountCommands by config.key(true, "指令(以/开头)命中正则是否计入违规次数, 否则仅弹窗禁用提示")

val mfRuleFile: File get() = File(Config.dataDir, "message_filter.txt")

// 规则缓存(按文件修改时间失效)
var mfRulesCache: List<Pair<Pattern, String>>? = null
var mfRulesCacheTime = 0L

fun parseMfRules(): List<Pair<Pattern, String>> {
    if (!mfRuleFile.exists()) {
        mfRuleFile.parentFile?.mkdirs()
        mfRuleFile.writeText(
            """
            # 消息过滤规则(Java 正则表达式)
            # 每行一个正则表达式, 匹配到的消息/指令会被拦截(命中任意一条即拦截)
            # 正则语法: https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
            # 以 # 开头的行是注释, 空行忽略
            # 示例:
            #   广告|加群|discord\.gg    匹配包含"广告"或"加群"或"discord.gg"的消息
            #   ^\Q/vote\E$              精确匹配 /vote 指令
            #   ^\Q/v\E$                 精确匹配 /v 指令
            """.trimIndent() + "\n"
        )
    }
    return mfRuleFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { raw ->
            try {
                Pattern.compile(raw) to raw
            } catch (e: Exception) {
                logger.warning("[messageFilter] 无效正则, 已忽略: $raw (${e.message})")
                null
            }
        }
}

fun mfRules(): List<Pair<Pattern, String>> {
    val mt = mfRuleFile.lastModified()
    if (mfRulesCache == null || mt != mfRulesCacheTime) {
        mfRulesCache = parseMfRules()
        mfRulesCacheTime = mt
    }
    return mfRulesCache ?: emptyList()
}

// 违规计数: uuid -> 次数
val mfViolations = ConcurrentHashMap<String, Int>()
var mfLastReset = System.currentTimeMillis()

private val banImpl by lazy { Services.get<BanService>().get() }

listenPacket2ServerAsync<SendChatMessageCallPacket> { con, packet ->
    val player = con.player ?: return@listenPacket2ServerAsync true
    val msg = packet.message
    if (msg.isBlank()) return@listenPacket2ServerAsync true
    val isCommand = msg.trimStart().startsWith("/")

    // 计数重置
    if (mfViolationResetMinutes > 0 &&
        System.currentTimeMillis() - mfLastReset > mfViolationResetMinutes * 60_000L
    ) {
        mfViolations.clear()
        mfLastReset = System.currentTimeMillis()
    }

    for ((pattern, raw) in mfRules()) {
        if (pattern.matcher(msg).find()) {
            if (isCommand) {
                // 指令: 弹窗说明服务器暂时禁用此指令
                Call.infoMessage(con, "{tr messageFilter.notify.commandDisabled}".with("raw" to raw).toString())
                if (!mfCountCommands) return@listenPacket2ServerAsync false
            } else {
                // 消息: 弹窗说明违反了哪条正则 + 剩余次数
                val count = mfViolations.merge(player.uuid(), 1, Int::plus) ?: 1
                if (count >= mfMaxViolations) {
                    mfViolations.remove(player.uuid())
                    Call.infoMessage(con, "{tr messageFilter.notify.banned}".with("minutes" to mfBanMinutes, "raw" to raw).toString())
                    banImpl.ban(PlayerData[player], mfBanMinutes, "违规消息(命中规则: $raw)", null)
                } else {
                    Call.infoMessage(con, "{tr messageFilter.notify.violation}".with("raw" to raw, "left" to (mfMaxViolations - count), "minutes" to mfBanMinutes).toString())
                }
                return@listenPacket2ServerAsync false
            }
            // 指令且计数: 统计违规次数(不弹剩余次数提示)
            val count = mfViolations.merge(player.uuid(), 1, Int::plus) ?: 1
            if (count >= mfMaxViolations) {
                mfViolations.remove(player.uuid())
                Call.infoMessage(con, "{tr messageFilter.notify.commandBanned}".with("minutes" to mfBanMinutes, "raw" to raw).toString())
                banImpl.ban(PlayerData[player], mfBanMinutes, "违规指令(命中规则: $raw)", null)
            }
            return@listenPacket2ServerAsync false
        }
    }
    true
}
