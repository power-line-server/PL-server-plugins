package wayzer.cmds

import mindustry.Vars

// 危险操作模式列表（仅对玩家生效，控制台无限制）
val DANGEROUS_PATTERNS = listOf(
    // === 进程终止 ===
    Regex("""System\s*\.\s*exit\s*\(""") to "进程终止 System.exit()",
    Regex("""exitProcess\s*\(""") to "进程终止 exitProcess()",
    Regex("""Core\s*\.\s*app\s*\.\s*exit\s*\(""") to "进程终止 Core.app.exit()",

    // === 系统命令执行 ===
    Regex("""Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(""") to "执行系统命令 Runtime.exec()",
    Regex("""ProcessBuilder\s*\(""") to "执行系统命令 ProcessBuilder",

    // === 网络关闭 ===
    Regex("""Vars\s*\.\s*net\s*\.\s*dispose\s*\(""") to "关闭网络 Vars.net.dispose()",
    Regex("""net\s*\.\s*dispose\s*\(""") to "关闭网络 net.dispose()",
    Regex("""net\s*\.\s*closeServer\s*\(""") to "关闭服务器 net.closeServer()",

    // === 日志遮蔽 ===
    Regex("""Log\s*\.\s*logger\s*=""") to "替换日志处理器 Log.logger",
    Regex("""Log\s*\.\s*level\s*=""") to "修改日志等级 Log.level",
    Regex("""Log\s*\.\s*setLogger\s*\(""") to "替换日志处理器 Log.setLogger()",

    // === 文件系统（完全禁止，含读/写/删/列表） ===
    Regex("""\bFile\s*\(""") to "文件操作 new File()",
    Regex("""java\s*\.\s*io\s*\.\s*File""") to "文件操作 java.io.File",
    Regex("""\bFi\s*\(""") to "文件操作 new Fi()",
    Regex("""arc\s*\.\s*files\s*\.\s*Fi""") to "文件操作 arc.files.Fi",
    Regex("""saveDirectory""") to "文件操作 saveDirectory",
    Regex("""dataDirectory""") to "文件操作 dataDirectory",
    Regex("""customMapDirectory""") to "文件操作 customMapDirectory",
    Regex("""modDirectory""") to "文件操作 modDirectory",

    // === 反射攻击 ===
    Regex("""Class\s*\.\s*forName\s*\(""") to "动态加载类 Class.forName()",
    Regex("""getDeclaredField\s*\(""") to "反射访问私有字段 getDeclaredField()",
    Regex("""setAccessible\s*\(\s*true\s*\)""") to "反射修改私有字段 setAccessible(true)",

    // === 线程阻塞 ===
    Regex("""Thread\s*\.\s*sleep\s*\(\s*\d{4,}""") to "长时间阻塞线程 Thread.sleep(>999ms)",

    // === Mod 加载 ===
    Regex("""loadMod\s*\(""") to "动态加载 Mod loadMod()",
    Regex("""getScripts\s*\(\s*\)\s*\.\s*run\s*\(""") to "执行外部脚本 getScripts().run()",

    // === 权限修改 ===
    Regex("""adminPlayer\s*\(""") to "修改管理员权限 adminPlayer()",
    Regex("""banPlayerID\s*\(""") to "永久封禁 banPlayerID()",
    Regex("""banPlayerIP\s*\(""") to "IP封禁 banPlayerIP()",

    // === Java 互操作（Nashorn 特有） ===
    Regex("""Java\s*\.\s*type\s*\(""") to "调用任意Java类 Java.type()",
)

fun checkDangerous(js: String): List<String> {
    return DANGEROUS_PATTERNS.mapNotNull { (regex, desc) ->
        if (regex.containsMatchIn(js)) desc else null
    }
}

command("js", "{tr command.js.desc}".with()) {
    permission = dotId
    body {
        val js = arg.joinToString(" ")

        // 仅管理员玩家需要安全检查，控制台无限制
        if (player != null) {
            val dangerous = checkDangerous(js)
            if (dangerous.isNotEmpty()) {
                val list = dangerous.joinToString(", ")
                player?.sendMessage("{tr jsCmd.reply.dangerous}".with("list" to list))
                return@body
            }
        }

        reply("{tr jsCmd.reply.jsPrefix}".with("cmd" to js))
        Vars.player = player
        try {
            reply(mods.scripts.runConsole(js).asPlaceHoldString())
        } finally {
            Vars.player = null
        }
    }
}