@file:Import("org.jline:jline-terminal-jansi:3.21.0", mavenDependsSingle = true)
@file:Import("org.jline:jline-terminal:3.21.0", mavenDependsSingle = true)
@file:Import("org.fusesource.jansi:jansi:2.4.0", mavenDependsSingle = true)
@file:Import("org.jline:jline-reader:3.21.0", mavenDependsSingle = true)
@file:OptIn(LoaderApi::class)

package coreMindustry

import arc.util.Log
import arc.util.Strings
import org.jline.reader.*
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.logging.Level
import kotlin.system.exitProcess
import arc.Core
import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.ScriptManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import coreMindustry.lib.NotForClient

class MyPrintStream(private val block: (String) -> Unit) : PrintStream(ByteArrayOutputStream()) {
    private val bufOut = out as ByteArrayOutputStream

    var last = -1
    override fun write(b: Int) {
        if (last == 13 && b == 10) {// \r\n
            last = -1
            return
        }
        last = b
        if (b == 13 || b == 10) flush()
        else super.write(b)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (len < 0) throw ArrayIndexOutOfBoundsException(len)
        for (i in 0 until len)
            write(buf[off + i].toInt())
    }

    @Synchronized
    override fun flush() {
        val str = try {
            bufOut.toString()
        } finally {
            bufOut.reset()
        }
        block(str)
    }
}

object MyCompleter : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val cmd = line.line().substring(0, line.cursor()).split(' ')
        val res = runBlocking(Dispatchers.game) {
            Commands.Root.tabComplete {
                arg = cmd
            }
        }
        candidates += res.map {
            Candidate(it)
        }
    }
}

@OptIn(LoaderApi::class)
suspend fun handleInput(reader: LineReader) {
    // 命令执行过程可以通过ctrl-c取消
    // 使用SupervisorJob切断和脚本的连接，因为脚本可能通过命令重载自己
    val commandScope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineName("command-scope"))
    reader.terminal.handle(Terminal.Signal.INT) {
        if (commandScope.coroutineContext.job.children.none()) return@handle
        reader.printAbove("Cancel current job...")
        commandScope.coroutineContext.cancelChildren(CancellationException("User Interrupted"))
    }

    var last = 0
    while (isActive) {
        val line = try {
            runInterruptible {
                reader.readLine("> ").let(RootCommands::trimInput)
            }
        } catch (_: InterruptedIOException) {
            return
        } catch (_: UserInterruptException) {
            if (!enabled) break//script disable
            if (last != 1) {
                reader.printAbove("Interrupt again to force exit application")
                last = 1
                continue
            }
            reader.printAbove("force exit")
            exitProcess(255)
        } catch (_: EndOfFileException) {
            if (last != 2) {
                reader.printAbove("Catch EndOfFile, again to exit application")
                last = 2
                continue
            }
            reader.printAbove("exit")
            // NonCancellable: disableAll() 会取消 console 脚本的协程作用域, 导致本协程被取消
            // 必须用 NonCancellable 防止取消, 确保 exitProcess 能执行
            withContext(Dispatchers.IO + NonCancellable) {
                // disableAll 在启动早期(如 boot 事务进行中)可能报 Nest Transaction 嵌套错误, 容错保证退出
                runCatching { ScriptManager.disableAll() }.onFailure { it.printStackTrace() }
                exitProcess(1)
            }
        }
        last = 0
        if (line.isEmpty()) continue
        commandScope.launch {
            try {
                RootCommands.handleInput(line, null)
            } catch (e: Throwable) {
                logger.log(Level.SEVERE, "error when handle input", e)
            }
        }.join()
    }
}

// ============ exit 命令覆盖 ============
// 覆盖原版 ServerControl.exit: 先在 IO 线程 disableAll, 避免主线程 runBlocking 与 Dispatchers.game 协程死锁
// 关服序列放到独立作用域(不依赖 console 脚本作用域)执行, 解决关服超时:
//   1) ScriptManager.disableAll() 会取消并等待 console 脚本作用域(3000ms 上限);
//      若 exit 命令本体就在该作用域内(NonCancellable 取消不掉), 作用域会等不完自己 -> 必然超时(3000ms).
//      独立作用域使命令立即返回, 消除自我等待.
//   2) NonCancellable 防止本协程被 disableAll 取消, 确保 exitProcess 执行.
// attr(NotForClient) 确保仅终端可用, 不显示在游戏内 /help
command("exit", "{tr vanilla.command.exit.desc}".with()) {
    attr(NotForClient)
    body {
        // 命令立即返回, 关服序列在独立作用域异步执行
        CoroutineScope(Dispatchers.IO + NonCancellable + CoroutineName("server-shutdown")).launch {
            Log.info("Shutting down server.")
            // 正常关服=停止意图: 删除重启标记, 外部 watchdog 不会重新拉起
            runCatching { File(Config.rootDir, "data/restart.flag").delete() }
            mindustry.Vars.net.dispose()
            ScriptManager.disableAll()
            Core.app.exit()
            exitProcess(0)
        }
    }
}

// ============ 本地命令管道 ============
// 用途: 无损注入 UTF-8 命令(含中文)。
// 背景: WriteConsoleInput 注入中文会被 Windows 控制台截断为低字节(实测注入 U+4E2D 读回 U+002D),
//       控制台路径无法注入中文(手动 IME 输入正常, 但代理注入无解); 本管道绕过控制台直接走命令处理。
// 配置: coreMindustry.consoleCmdPipe.port (0=禁用); 多开服务器时各实例需使用不同端口
val cmdPipePort by config.key(
    6568, "本地命令管道监听端口(0=禁用)",
    "仅监听 127.0.0.1, 每行一条 UTF-8 命令; 多开时各实例需不同端口",
)
var cmdPipeStarted = false

fun startCmdPipe() {
    if (cmdPipeStarted || cmdPipePort <= 0) return
    cmdPipeStarted = true
    launch(Dispatchers.IO + CoroutineName("CmdPipe")) {
        try {
            ServerSocket(cmdPipePort, 50, InetAddress.getByName("127.0.0.1")).use { server ->
                logger.info("本地命令管道已监听 127.0.0.1:${cmdPipePort} (UTF-8, 每行一条命令)")
                // accept 是阻塞调用, 不响应协程取消: 用 soTimeout 周期性醒来检查 isActive,
                // 否则关服时 disableAll 取消本作用域会等到 accept 返回才生效(拖满 3000ms 超时)
                server.soTimeout = 1000
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    launch(Dispatchers.IO) {
                        try {
                            socket.use { s ->
                                // readLine 同样是阻塞调用, 同 accept 处理方式
                                s.soTimeout = 1000
                                val br = s.getInputStream().bufferedReader(Charsets.UTF_8)
                                while (isActive) {
                                    val line = try {
                                        br.readLine()
                                    } catch (e: java.net.SocketTimeoutException) {
                                        continue
                                    } ?: break
                                    if (line.isBlank()) continue
                                    logger.info("CmdPipe> ${line.trim()}")
                                    runCatching { RootCommands.handleInput(line.trim(), null) }
                                        .onFailure { logger.log(Level.SEVERE, "error when handle cmdpipe input", it) }
                                }
                            }
                        } catch (e: Throwable) {
                            // 客户端异常断开(kill/强杀)时 readLine/close 都会抛异常;
                            // try 必须包住整个 use(含 close), 否则异常逃逸传播取消父协程(accept 循环), 管道死
                            if (isActive) logger.log(Level.WARNING, "cmdpipe client closed abnormally", e)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (isActive) logger.log(Level.SEVERE, "命令管道异常", e)
        }
    }
}

// ============ 颜色转换函数 ============
// 替换 Log.formatter 后, 所有颜色码统一在此处理:
// useColors=true: &xx 和 Mindustry 颜色码 -> ANSI 码 (终端显示)
// useColors=false: 剥离所有颜色码 (日志文件干净)

/** arc &xx 码到 ANSI 码的映射 */
private val arcColorToAnsiMap = mapOf(
    "&fr" to "\u001b[0m", "&fb" to "\u001b[1m", "&fd" to "\u001b[2m",
    "&fu" to "\u001b[4m", "&fi" to "\u001b[3m",
    "&k" to "\u001b[30m", "&K" to "\u001b[90m", "&w" to "\u001b[37m", "&W" to "\u001b[97m",
    "&r" to "\u001b[31m", "&R" to "\u001b[91m", "&g" to "\u001b[32m", "&G" to "\u001b[92m",
    "&y" to "\u001b[33m", "&Y" to "\u001b[93m", "&b" to "\u001b[34m", "&B" to "\u001b[94m",
    "&m" to "\u001b[35m", "&M" to "\u001b[95m", "&c" to "\u001b[36m", "&C" to "\u001b[96m",
    "&lc" to "\u001b[96m", "&lb" to "\u001b[94m", "&ly" to "\u001b[93m", "&lr" to "\u001b[91m",
    "&lg" to "\u001b[92m", "&lm" to "\u001b[95m", "&lk" to "\u001b[90m", "&lw" to "\u001b[97m",
    "&p" to "\u001b[35m", "&P" to "\u001b[95m",
    "&br" to "\u001b[41m", "&bg" to "\u001b[42m", "&by" to "\u001b[43m", "&bb" to "\u001b[44m",
    "&bd" to "\u001b[49m"
)

/** Mindustry 颜色码到 arc &xx 码的映射：多层自动发现，覆盖所有可能出现的颜色
 *  Layer 1: 手动精调 base (确保已知颜色正确)
 *  Layer 2: 从 ColorApi.all 同步 (ConsoleColor 枚举: light_yellow, light_cyan 等)
 *  Layer 3: 从 arc Colors 注册表补充 (Pal 颜色: scarlet, accent 等)
 *  兜底: mindustryColorToArc 中未知颜色静默移除, 不残留字面量破坏 JLine */
private val mindustryColorToArcMap: Map<String, String> by lazy {
    // step 1: 手动精调 base（确保已知颜色正确）
    val base = mutableMapOf(
        "red" to "&r", "green" to "&g", "yellow" to "&y", "blue" to "&b",
        "purple" to "&m", "cyan" to "&c", "white" to "&w",
        // gray(127,127,127) -> &K(ANSI 90 亮黑=灰色, 可见), 非 &k(ANSI 30 纯黑, 黑色终端不可见)
        "gray" to "&K", "lightgray" to "&K", "darkgray" to "&k",
        "light_gray" to "&K", "dark_gray" to "&k",
        "scarlet" to "&r", "pink" to "&M", "orange" to "&y", "gold" to "&Y",
        "accent" to "&Y", "sky" to "&B", "acid" to "&G", "tan" to "&y",
        "salmon" to "&R", "coral" to "&R", "violet" to "&m", "magenta" to "&M",
        "olive" to "&y", "goldenrod" to "&Y", "black" to "&k",
        "unlaunched" to "&fr",
        // light_* 变体: 模板中直接使用 [light_yellow] 等, 非 arc 标准颜色名, 需手动映射
        "light_yellow" to "&Y", "light_cyan" to "&C", "light_red" to "&R",
        "light_green" to "&G", "light_purple" to "&M", "light_blue" to "&B"
    )
    // step 2: 从 ColorApi.all 同步 ConsoleColor 枚举 (覆盖 light_* 变体和脚本注册的自定义颜色)
    // ColorApi.arcColorHandler 已定义 ConsoleColor -> &xx 的完整映射, 直接复用避免重复维护
    try {
        val known = base.keys.map { it.lowercase() }.toSet()
        for ((name, color) in coreLibrary.lib.ColorApi.all) {
            val lowerName = name.lowercase()
            if (lowerName in known) continue
            val arcCode = coreLibrary.lib.ColorApi.arcColorHandler(color)
            if (arcCode.isNotEmpty()) {
                base[lowerName] = arcCode
            }
        }
    } catch (_: Exception) {}
    // step 3: 从 arc Colors 注册表补充 base 未覆盖的新颜色，用欧几里得距离找最近 &xx
    try {
        val known = base.keys.map { it.lowercase() }.toSet()
        for (entry in arc.graphics.Colors.getColors()) {
            val name = (entry.key as? String)?.lowercase() ?: continue
            if (name in known) continue
            val color = entry.value as? arc.graphics.Color ?: continue
            val r = (color.r * 255).toInt().coerceIn(0, 255)
            val g = (color.g * 255).toInt().coerceIn(0, 255)
            val b = (color.b * 255).toInt().coerceIn(0, 255)
            base[name] = closestArcColorEuclidean(r, g, b)
        }
    } catch (_: Exception) {}
    // clear（全透明黑）映射为重置
    base["clear"] = "&fr"
    base
}

/** 把 Mindustry 颜色码 ([name], [#hex], [], [[ 转义) 转为 arc &xx 码, 统一格式后由 arcColorToAnsi 处理
 *  关键: 未知颜色码静默移除 (不残留字面量), 避免 [light_yellow] 等未映射颜色破坏 JLine 渲染
 *  [] 语义: Mindustry 的 [] 是 pop color stack (恢复上一个颜色), 不是完全重置
 *  例如 [red]红[blue]蓝[]蓝 -> 红蓝蓝 (第二个蓝恢复为蓝色, 而非默认白色)
 *  实现: 单次遍历按文本顺序处理所有颜色码, 正确维护 color stack (之前分步 Regex.replace 导致 [] 处理时栈为空) */
fun mindustryColorToArc(text: String): String {
    // step 1: 处理 [[ 转义 (Mindustry 字面量 [ 语法), 用占位符保护, 避免被颜色码正则误匹配
    val protected = text.replace("[[", "\u0000")
    // step 2: 单次遍历, 按文本顺序处理所有颜色码, 维护 color stack
    val colorStack = ArrayDeque<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < protected.length) {
        if (protected[i] == '[') {
            // [] pop color stack, 恢复上一个颜色
            if (i + 1 < protected.length && protected[i + 1] == ']') {
                if (colorStack.isNotEmpty()) colorStack.removeLast()
                sb.append(colorStack.lastOrNull() ?: "&fr")
                i += 2
                continue
            }
            // [#hex] push 到栈
            if (i + 1 < protected.length && protected[i + 1] == '#') {
                val close = protected.indexOf(']', i + 2)
                if (close != -1) {
                    val hex = protected.substring(i + 2, close)
                    if (hex.length == 6 || hex.length == 8) {
                        try {
                            val arcCode = closestArcColor(
                                hex.substring(0, 2).toInt(16),
                                hex.substring(2, 4).toInt(16),
                                hex.substring(4, 6).toInt(16)
                            )
                            colorStack.addLast(arcCode)
                            sb.append(arcCode)
                            i = close + 1
                            continue
                        } catch (_: Exception) {}
                    }
                }
            }
            // [name] push 到栈, 未知颜色静默移除
            val close = protected.indexOf(']', i + 1)
            if (close != -1) {
                val name = protected.substring(i + 1, close)
                if (name.matches(Regex("[a-zA-Z_]+"))) {
                    val arcCode = mindustryColorToArcMap[name]
                    if (arcCode != null) {
                        colorStack.addLast(arcCode)
                        sb.append(arcCode)
                    }
                    // 未知颜色码: 静默移除 (不 push, 不输出)
                    i = close + 1
                    continue
                }
            }
            // 不是颜色码, 保留 [
            sb.append(protected[i])
            i++
        } else {
            sb.append(protected[i])
            i++
        }
    }
    // step 3: 恢复 [[ 转义占位符为字面量 [
    return sb.toString().replace("\u0000", "[")
}

/** 估算最接近的 arc 颜色码 (用于 [#RRGGBB] 转换, 欧几里得距离) */
fun closestArcColor(r: Int, g: Int, b: Int): String {
    return closestArcColorEuclidean(r, g, b)
}

/** 用欧几里得距离找最接近的 &xx 码（RGB 参考值取自 ANSI 码在终端的实际渲染色, 非 arc Color 对象值）
 *  注意: &k 是 ANSI 30 (纯黑 0,0,0), &K 是 ANSI 90 (亮黑/灰 127,127,127)
 *  之前 &k 参考值写成 (127,127,127) 导致 gray 误匹配 &k, 在黑色终端上不可见 */
private val euclideanColorTargets = listOf(
    "&r" to intArrayOf(229, 84, 84),   // #e55454  red
    "&g" to intArrayOf(56, 214, 103),  // #38d667  green
    "&y" to intArrayOf(255, 255, 0),   // #ffff00  yellow
    "&b" to intArrayOf(65, 105, 225),  // #4169e1  blue/royal
    "&m" to intArrayOf(170, 0, 255),   // #aa00ff  purple
    "&c" to intArrayOf(0, 255, 255),   // #00ffff  cyan
    "&p" to intArrayOf(170, 0, 255),   // #aa00ff  purple(alt)
    "&k" to intArrayOf(0, 0, 0),       // ANSI 30 纯黑 (非 arc Color.gray 127,127,127)
    "&w" to intArrayOf(255, 255, 255), // #ffffff  white
    "&R" to intArrayOf(250, 128, 114), // #fa8072  salmon
    "&G" to intArrayOf(56, 214, 103),  // #38d667  green(同&g)
    "&Y" to intArrayOf(255, 215, 0),   // #ffd700  gold
    "&B" to intArrayOf(135, 206, 235), // #87ceeb  sky
    "&M" to intArrayOf(255, 128, 192), // #ff80c0  pink
    "&C" to intArrayOf(0, 255, 255),   // #00ffff  cyan(同&c)
    "&K" to intArrayOf(127, 127, 127), // ANSI 90 亮黑/灰 (实际渲染为 127,127,127)
    "&W" to intArrayOf(255, 255, 255), // #ffffff  white(同&w)
    "&P" to intArrayOf(255, 128, 192), // #ff80c0  pink(同&M)
)
private fun closestArcColorEuclidean(r: Int, g: Int, b: Int): String {
    return euclideanColorTargets.minByOrNull { (_, rgb) ->
        val dr = r - rgb[0]; val dg = g - rgb[1]; val db = b - rgb[2]
        dr * dr + dg * dg + db * db
    }?.first ?: "&w"
}

/** 把 arc &xx 码转为 ANSI 码
 *  注意: 必须按 key 长度降序替换, 避免短码 (&c) 子串匹配长码 (&lc) 导致 &l 残留为文本 */
fun arcColorToAnsi(text: String): String {
    var result = text
    for ((arc, ansi) in arcColorToAnsiMap.entries.sortedByDescending { it.key.length }) {
        result = result.replace(arc, ansi)
    }
    return result
}

/** 剥离所有颜色码 (arc &xx, ANSI, Mindustry), 用于日志文件, 与原版 removeColors 行为一致
 *  注意: [a-z_]* 正则只匹配小写颜色名, 排除 [I] [W] [E] [D] 日志级别标记 */
fun stripAllColors(text: String): String {
    // 1. 保护 [[ 转义 (Mindustry 字面量 [ 语法), 避免被颜色码正则误匹配
    var result = text.replace("[[", "\u0000")
    // 2. arc &xx 码 -> ANSI (通过已知映射), 再剥离 ANSI
    result = arcColorToAnsi(result)
    result = Regex("""\u001b\[[0-9;]*m""").replace(result, "")
    // 3. 剥离 Mindustry 颜色码: [name] (仅小写, 排除 [I][W][E][D] 日志级别), [#hex], []
    result = Regex("""\[[a-z_]*]""").replace(result, "")
    result = Regex("""\[#[0-9a-fA-F]{6,8}]""").replace(result, "")
    // 4. 恢复 [[ 为字面量 [
    return result.replace("\u0000", "[")
}

// 日志钩子: 外部模块(如 WebUI)可注册回调, 获取带 Mindustry 颜色码的格式化文本
// level: arc Log 级别, unifiedText: 经 mindustryColorToArc 统一后的文本(含 &xx 和残留的 [#hex] [name])
typealias LogHook = (level: arc.util.Log.LogLevel, unifiedText: String) -> Unit
val logHooks = mutableListOf<LogHook>()

// ThreadLocal 用于在 Log.formatter 和 Log.logger 之间传递 unified 文本
// formatter 先执行, 把 unified 存入; logger 后执行, 取出传给钩子
val unifiedTextHolder = ThreadLocal<String?>()

var started = false
lateinit var reader: LineReader
fun start() {
    if (started) return
    started = true
    launch(Dispatchers.IO + CoroutineName("Console Reader")) {
        // MindustryX B470 的 ServerControl.setup() 在构造函数创建了 lineReader (system terminal).
        // 复用它, 避免创建第二个 LineReader 导致 dumb terminal (banner/日志被吞).
        val scReader = Core.app.listeners.find { it.javaClass.simpleName == "ServerControl" }?.let { sc ->
            sc.javaClass.getDeclaredField("lineReader").apply { isAccessible = true }.get(sc) as? LineReader
        }
        val isOwnTerminal = scReader == null
        reader = scReader ?: withContextClassloader {
            LineReaderBuilder.builder()
                .completer(MyCompleter)
                .variable(LineReader.HISTORY_FILE, Config.cacheDir.resolve("console.history"))
                .build()
        }
        val bakOut = System.out
        System.setOut(MyPrintStream {
            reader.printAbove(AttributedString.fromAnsi(it))
        })
        try {
            handleInput(reader)
        } finally {
            System.setOut(bakOut)
            // 只关闭自己创建的终端, 复用 ServerControl 的不关闭
            if (isOwnTerminal) reader.terminal.close()
        }
    }
}

onEnable {
    // 本地命令管道: 无损注入 UTF-8 命令(含中文)
    startCmdPipe()
    // 替换 Log.formatter, 统一处理颜色码
    // useColors=true: &xx 和 Mindustry 颜色码 -> ANSI 码 (终端显示颜色)
    // useColors=false: 剥离所有颜色码 (日志文件纯文本, 与原版 removeColors 行为一致)
    Log.useColors = true
    Log.formatter = Log.LogFormatter { text, useColors, arg ->
        var formatted = text.replace("@", "&fb&lb@&fr")
        if (arg != null && arg.isNotEmpty()) {
            formatted = Strings.format(formatted, *arg)
        }
        val unified = mindustryColorToArc(formatted)
        // 存入 ThreadLocal 供 Log.logger wrapper (WebUI) 读取带颜色码的文本
        // WebUI 实时日志通过 unifiedTextHolder 获取颜色码, 历史日志从文件读取(纯文本)
        unifiedTextHolder.set(formatted)
        if (useColors) {
            arcColorToAnsi(unified)
        } else {
            // 日志文件: 剥离所有颜色码, 纯文本不干扰阅读 (与原版 Mindustry 一致)
            stripAllColors(formatted)
        }
    }

    Core.app.listeners.find { it.javaClass.simpleName == "ServerControl" }?.apply {
        javaClass.getDeclaredField("serverInput")
            .set(this, Runnable {
                logger.info("Overwrite ServerControl.serverInput")
                start()
            })
    }
    start()
}
