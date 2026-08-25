package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.Config
import java.io.File
import java.time.Duration

name = "小提示轮播"

// 从 data/tips.txt 定时随机广播一条小提示到聊天区(每次发送前重新读文件, 修改即时生效)
// 文件格式: 每行一条, # 开头为注释, 空行忽略; 支持 Mindustry 颜色码; 字面 \n 表示换行
// 建议单条不超过 150 字符(原版聊天消息长度上限 Vars.maxTextLength)
private val tipsFile: File get() = Config.dataDir.resolve("tips.txt")
private val tipsInterval by config.key(Duration.ofMinutes(10), "小提示轮播间隔")
private val recentTips = mutableListOf<String>() // 最近 N 条记忆, 避免连续重复

private fun loadTips(): List<String> {
    if (!tipsFile.exists()) return emptyList()
    return runCatching {
        tipsFile.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }.getOrDefault(emptyList())
}

private fun pickTip(): String? {
    val tips = loadTips().filter { it !in recentTips }
    if (tips.isEmpty()) return null
    val tip = tips.random()
    recentTips += tip
    if (recentTips.size > 5) recentTips.removeAt(0)
    return tip.replace("\\n", "\n")
}

onEnable {
    loop(Dispatchers.Default) {
        delay(tipsInterval.toMillis())
        if (Groups.player.size() > 0) {
            pickTip()?.let { broadcast(it.with()) }
        }
    }
}

command("tip", "{tr tips.command.desc}".with()) {
    aliases = listOf("小提示")
    body {
        pickTip()?.let { reply(it.with()) } ?: returnReply("{tr tips.reply.empty}".with())
    }
}
