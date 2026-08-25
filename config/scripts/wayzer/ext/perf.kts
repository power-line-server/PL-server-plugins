@file:Import("tools.profiler:async-profiler:4.1", mavenDepends = true)
@file:Depends("coreMindustry", "命令框架与NotForClient")

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coreMindustry.lib.NotForClient
import one.profiler.AsyncProfiler
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

name = "服务器性能分析"

private val profDir get() = Config.cacheDir.resolve("perf").apply { mkdirs() }
private val running = java.util.concurrent.atomic.AtomicBoolean(false)
private var profStart = Instant.now()

// 网络统计(发送方向包计数)
@Volatile var netPktsTotal = 0L
@Volatile var netPktsLastMin = 0L
// 采样会话期间按玩家统计发包
@Volatile var netSessionActive = false
val netSessionByPlayer = ConcurrentHashMap<String, AtomicLong>()

listen<mindustryX.events.SendPacketEvent> {
    netPktsTotal++
    if (netSessionActive) {
        val p = it.con?.player
        val n = if (p != null) arc.util.Strings.stripColors(p.name()) else "unknown"
        netSessionByPlayer.computeIfAbsent(n) { AtomicLong() }.incrementAndGet()
    }
}

var lastTickSample = 0
@Volatile var tpsSample = 0

onEnable {
    launch(Dispatchers.Default) {
        while (true) {
            delay(1000)
            try {
                val t = mindustry.Vars.state.tick.toInt()
                tpsSample = t - lastTickSample
                lastTickSample = t
            } catch (_: Exception) {
            }
        }
    }
    launch(Dispatchers.Default) {
        var last = 0L
        while (true) {
            delay(60_000)
            val now = netPktsTotal
            netPktsLastMin = now - last
            last = now
        }
    }
}

fun startProf(cmd: String): File {
    val profiler = AsyncProfiler.getInstance()
    val file = profDir.resolve("${Instant.now()}.jfr")
    profStart = Instant.now()
    profiler.execute(cmd.replace("FILE", file.absolutePath))
    return file
}

fun stopProf(file: File): File {
    AsyncProfiler.getInstance().execute("stop")
    return file
}

// ═══════════════ 游戏逻辑归因 ═══════════════
/** 逻辑子系统 -> 特征包路径 */
val systemKeywords = linkedMapOf(
    "插件脚本" to listOf("wayzer.", "coreMindustry.", "coreLibrary.", "mapScript."),
    "单位AI与行为" to listOf("mindustry.ai.types", "mindustry.entities.comp"),
    "寻路" to listOf("PathFinder", "Astar", "pathfind"),
    "方块与工厂生产" to listOf("mindustry.world.blocks", "mindustry.world.consumers", "Building"),
    "子弹与伤害" to listOf("entities.bullet", "Damage", "BulletType"),
    "火焰/液体/环境" to listOf("Fire", "Puddle", "Weather"),
    "地图与地形" to listOf("mindustry.world.modules", "Floor"),
    "网络同步" to listOf("mindustry.net", "NetworkIO"),
    "世界主循环" to listOf("mindustry.core.Logic")
)

/** 解析 async-profiler collapsed 输出, 按"距叶子最近的特征帧"归类, 返回 (分类->样本数) */
fun attributeCollapsed(file: File): LinkedHashMap<String, Int> {
    val result = LinkedHashMap<String, Int>()
    var total = 0
    file.forEachLine { line ->
        val sp = line.lastIndexOf(' ')
        if (sp <= 0) return@forEachLine
        val count = line.substring(sp + 1).toIntOrNull() ?: return@forEachLine
        val frames = line.substring(0, sp).split(';')
        if (frames.isEmpty()) return@forEachLine
        total += count
        // 从叶子(最后帧)向根部找第一个匹配的系统
        for (i in frames.indices.reversed()) {
            val frame = frames[i]
            val matched = systemKeywords.entries.firstOrNull { (_, keys) -> keys.any { frame.contains(it) } }
            if (matched != null) {
                result[matched.key] = (result[matched.key] ?: 0) + count
                return@forEachLine
            }
        }
        result["JVM/其他"] = (result["JVM/其他"] ?: 0) + count
    }
    if (total > 0) result["__total"] = total
    return result
}

/** 解析 collapsed 文件并生成归因报告文本 */
fun buildAttributionReport(file: File): String {
    val result = attributeCollapsed(file)
    val total = result["__total"] ?: return "[red]采样文件为空或解析失败"
    val sb = StringBuilder()
    sb.append("[yellow]== 游戏逻辑 CPU 归因 (样本 $total) ==\n")
    result.entries.sortedByDescending { it.value }
        .filter { it.key != "__total" && it.value > 0 }
        .forEach { (k, v) ->
            val bar = "■".repeat(((v * 20.0 / total).toInt()).coerceAtLeast(1))
            sb.append("[light_gray]$k: [white]${v * 100 / total}% [accent]$bar\n")
        }
    return sb.toString()
}

/** 采样会话状态 */
private class SampleSession {
    var profFile: File? = null
    var profKind = "" // cpu / alloc / game
    var netStart = 0L
    val netByPlayer = LinkedHashMap<String, AtomicLong>()
}

@Volatile private var session: SampleSession? = null

/** 网络会话收尾报告 */
private fun buildNetReport(session: SampleSession): String {
    val diff = netPktsTotal - session.netStart
    val sb = StringBuilder()
    sb.append("[light_gray]期间发包: [white]$diff 个\n")
    val top = session.netByPlayer.entries.sortedByDescending { it.value.get() }.take(5)
    if (top.isNotEmpty()) {
        sb.append("[light_gray]Top发包玩家:\n")
        for ((name, cnt) in top) sb.append("  [white]$name: [gray]${cnt.get()} 个\n")
    }
    return sb.toString()
}

// ═══════════════ 命令 ═══════════════
command("perf", "服务器性能分析: perf <项目...> [秒], 项目可组合(cpu alloc game net)") {
    aliases = listOf("性能")
    usage = "<项目...> [秒]  例: perf cpu net 60 | perf game | perf mem | perf stop"
    attr(NotForClient) // 仅终端: 玩家 help 不显示且不可调用
    body {
        if (player != null && !player!!.admin) {
            returnReply("[red]仅管理员可用".with("receiver" to player!!))
        }
        // 解析: 兼容 "[cpu game]" 括号写法与空格分隔; 收集已知项目词 + 秒数
        val tokens = arg.flatMap { it.removeSurrounding("[", "]").split(' ') }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        val items = tokens.filter { it in setOf("cpu", "alloc", "game", "net") }
        val seconds = tokens.filter { it.toIntOrNull() != null }
            .firstOrNull()?.toIntOrNull()?.coerceIn(5, 600) ?: 30

        when (tokens.getOrElse(0) { "" }) {
            "mem" -> {
                val mx = ManagementFactory.getMemoryMXBean()
                val rt = Runtime.getRuntime()
                val pools = ManagementFactory.getMemoryPoolMXBeans()
                    .filter { it.type == java.lang.management.MemoryType.HEAP && it.usage.max > 0 }
                    .joinToString("\n") { "[light_gray]${it.name}: [white]${it.usage.used / 1048576}MB" }
                val text = buildString {
                    append("[yellow]== JVM 内存 ==\n")
                    append("[light_gray]堆使用: [white]${mx.heapMemoryUsage.used / 1048576}MB / ${mx.heapMemoryUsage.max / 1048576}MB\n")
                    append("[light_gray]非堆: [white]${mx.nonHeapMemoryUsage.used / 1048576}MB\n")
                    append("[light_gray]Runtime used/max: [white]${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB / ${rt.maxMemory() / 1048576}MB\n")
                    append("[yellow]== 内存池(堆) ==\n")
                    append(pools)
                    append("\n[gray]对象分布详情用终端执行: jcmd <pid> GC.class_histogram")
                }
                reply(text.with())
                return@body
            }
            "net" -> {
                val players = mindustry.gen.Groups.player.size()
                val text = buildString {
                    append("[yellow]== 网络(发送方向, 包计数) ==\n")
                    append("[light_gray]在线玩家: [white]$players\n")
                    append("[light_gray]近一分钟发包: [white]${netPktsLastMin} 个\n")
                    append("[gray]注: 字节级统计需网络层抓包; 此处仅速率参考")
                }
                reply(text.with())
                return@body
            }
            "stop" -> {
                val s = session
                if (!running.compareAndSet(true, false)) returnReply("[red]没有进行中的采样".with())
                netSessionActive = false
                var msg = "[green]采样已停止"
                s?.profFile?.let { f ->
                    msg += "\n[light_gray]输出: ${f.absolutePath}"
                    if (s.profKind == "game") msg += "\n${buildAttributionReport(f)}"
                }
                if (s != null) {
                    netSessionByPlayer.forEach { (n, v) -> s.netByPlayer[n] = v }
                    msg += "\n${buildNetReport(s)}"
                }
                reply(msg.with())
                return@body
            }
        }
        // ══ 组合采样: cpu/alloc/game/net 任意组合 ══
        if (items.isEmpty()) {
            reply(
                buildString {
                    append("[yellow]用法: perf <项目...> [秒]\n")
                    append("[light_gray]项目: [white]cpu(CPU采样) alloc(内存分配) game(逻辑归因) net(网络统计)\n")
                    append("[light_gray]例: [white]perf cpu net 60 | perf game | perf mem | perf stop")
                }.with()
            )
            return@body
        }
        if ("cpu" in items && "alloc" in items) {
            returnReply("[red]cpu 与 alloc 不能同时采样(分析器限制), 请分开跑".with())
        }
        if (running.get()) returnReply("[red]采样进行中, /perf stop 结束".with())
        running.set(true)

        val sess = SampleSession()
        session = sess
        val wantGame = "game" in items
        val wantAlloc = "alloc" in items
        val wantCpu = "cpu" in items
        val wantNet = "net" in items

        thisContextScript().launch(Dispatchers.Default) {
            try {
                // 启动剖析(cpu/game/alloc 三选一, 分析器限制)
                if (wantGame) {
                    sess.profFile = startProf("start,cpu,interval=1000000us,output=collapsed,file=FILE")
                    sess.profKind = "game"
                } else if (wantCpu) {
                    sess.profFile = startProf("start,cpu,interval=1000000us,file=FILE")
                    sess.profKind = "cpu"
                } else if (wantAlloc) {
                    sess.profFile = startProf("start,alloc,interval=1000000us,file=FILE")
                    sess.profKind = "alloc"
                }
                if (wantNet) {
                    sess.netStart = netPktsTotal
                    netSessionActive = true
                    netSessionByPlayer.clear()
                }

                delay(seconds * 1000L)

                val report = StringBuilder()
                report.append("[green]== 采样完成 ($seconds s: ${items.joinToString("+")}) ==\n")
                // 剖析收尾
                if (wantGame) {
                    val out = stopProf(sess.profFile!!)
                    report.append("[light_gray]剖析文件: [white]${out.absolutePath}\n")
                    if (sess.profKind == "game") report.append(buildAttributionReport(out))
                } else if (wantCpu || wantAlloc) {
                    val out = stopProf(sess.profFile!!)
                    report.append("[light_gray]剖析文件: [white]${out.absolutePath}\n")
                }
                // 网络会话收尾
                if (wantNet) {
                    netSessionActive = false
                    netSessionByPlayer.forEach { (n, v) -> sess.netByPlayer[n] = v }
                    report.append(buildNetReport(sess))
                }
                arc.util.Log.info("[Perf] ${items.joinToString("+")} 采样结束:\n$report")
            } catch (e: Throwable) {
                arc.util.Log.err("[Perf] 采样异常", e)
            } finally {
                netSessionActive = false
                running.set(false)
            }
        }

        reply(
            buildString {
                append("[green]采样已启动(${seconds}s): [white]${items.joinToString("+")}\n")
                append("[gray]完成后结果将输出到本控制台")
            }.with()
        )
    }
}
