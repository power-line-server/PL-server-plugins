@file:Depends("coreLibrary/lang", "语言包数据")

package coreLibrary

import cf.wayzer.scriptAgent.Config
import coreLibrary.lib.ConfigBuilder
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.LangService
import java.io.File

name = "语言包键自动同步"

// 保护列表：不被删除的特殊 key 前缀
val protectedKeyPrefixes = listOf("language.")

// 动态 key 白名单（正则无法捕获的动态拼接 key，如 "{tr command.${name}.desc}"）
// trChat.trChatMenu 使用 "{tr $statusKey}" 动态引用启用状态
// trChat.providerTypeMenu 使用 "{tr trChat.menu.providerType.${type.name.lowercase()}}" 动态引用Provider类型
val manualKeys = mutableSetOf<String>(
    "trChat.menu.main.enabled",
    "trChat.menu.main.disabled",
    "trChat.menu.providerType.openai",
    "trChat.menu.providerType.claude",
    "trChat.menu.providerType.google",
    "ipLimit.kick.message",
    "vip.expired",
    "moderator.expired",
    // renderMap.kts 回复用 replyTr() 动态拼接 "{tr $key}"，静态正则无法捕获
    "renderMap.reply.start", "renderMap.reply.done", "renderMap.reply.fail", "renderMap.reply.error",
)

// 扫描所有脚本，提取 {tr key} 引用的 key
fun scanLangKeys(): Set<String> {
    val keys = mutableSetOf<String>()
    val scriptDir = Config.dataDir.parentFile // config/scripts/
    if (scriptDir == null || !scriptDir.exists()) return keys

    val regex = Regex("""\{tr\s+([\w.\-]+)""")
    scriptDir.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        val ext = file.extension.lowercase()
        if (ext != "kts" && ext != "kt") return@forEach
        // 跳过 langSync 自身
        if (file.name == "langSync.kts") return@forEach
        // 跳过 data/ 和 cache/ 目录（运行时数据与编译缓存）
        if (file.path.contains(File.separator + "data" + File.separator) ||
            file.path.contains(File.separator + "cache" + File.separator)
        ) return@forEach
        runCatching {
            file.readLines().forEach { line ->
                // 跳过单行注释
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEach
                regex.findAll(line).forEach { m ->
                    keys.add(m.groupValues[1])
                }
            }
        }
    }
    keys.addAll(manualKeys)
    // 过滤掉不完整的 key（如动态拼接 trChat.menu.providerType.\${...} 提取的前缀）
    val filtered = keys.filter { !it.endsWith(".") }.toMutableSet()
    return filtered
}

// 同步单个 .properties 文件，保留注释和已有 key 顺序
// 返回 Pair(addedCount, removedCount)
// 修改前自动备份到 lang_backups/ 目录（仅当有变更时）
fun syncBundleFile(file: File, referencedKeys: Set<String>): Pair<Int, Int> {
    val lines = if (file.exists()) file.readLines() else emptyList()
    val existingKeys = mutableSetOf<String>()

    // 解析现有 key
    lines.forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@forEach
        val key = trimmed.substring(0, eq).trim()
        existingKeys.add(key)
    }

    val keysToAdd = referencedKeys.filter { it !in existingKeys }
    val keysToRemove = existingKeys.filter { key ->
        key !in referencedKeys && protectedKeyPrefixes.none { key.startsWith(it) }
    }

    if (keysToAdd.isEmpty() && keysToRemove.isEmpty()) return 0 to 0

    // 有变更时，先备份到 lang_backups/
    try {
        val backupDir = file.parentFile.parentFile.resolve("lang_backups")
        if (!backupDir.exists()) backupDir.mkdirs()
        val backupFile = backupDir.resolve(file.name)
        if (file.exists()) {
            file.copyTo(backupFile, overwrite = true)
        }
    } catch (e: Exception) {
        logger.warning("[langSync] 备份 ${file.name} 失败: ${e.message}")
    }

    // 构建新内容：保留原行（跳过被删除的 key 行），末尾追加新 key
    val result = mutableListOf<String>()
    lines.forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            result.add(line)
            return@forEach
        }
        val eq = trimmed.indexOf('=')
        if (eq <= 0) {
            result.add(line)
            return@forEach
        }
        val key = trimmed.substring(0, eq).trim()
        if (key in keysToRemove) return@forEach // 跳过被删除的行
        result.add(line)
    }

    // 追加新 key
    if (keysToAdd.isNotEmpty()) {
        result.add("")
        result.add("# 以下 key 由 langSync 自动添加，请填充翻译")
        keysToAdd.sorted().forEach { key ->
            result.add("$key=")
        }
    }

    file.writeText(result.joinToString("\n", postfix = "\n"))
    return keysToAdd.size to keysToRemove.size
}

// 同步所有 .properties 文件
fun syncBundles() {
    val bundleDir = Config.dataDir.resolve("lang")
    if (!bundleDir.exists()) bundleDir.mkdirs()

    val referencedKeys = scanLangKeys()
    val existingFiles = bundleDir.listFiles { f -> f.name.endsWith(".properties") }?.toList() ?: emptyList()

    var totalAdded = 0
    var totalRemoved = 0
    var backedUpFiles = 0
    existingFiles.forEach { file ->
        val (added, removed) = syncBundleFile(file, referencedKeys)
        totalAdded += added
        totalRemoved += removed
        if (added > 0 || removed > 0) backedUpFiles++
    }

    logger.info("[langSync] 扫描到 ${referencedKeys.size} 个 key, 添加 $totalAdded 个, 删除 $totalRemoved 个" +
        (if (backedUpFiles > 0) ", 已备份 $backedUpFiles 个文件到 lang_backups/" else ""))
    if (referencedKeys.any { it in manualKeys }) {
        logger.info("[langSync] 注意: 部分动态 key 来自白名单,请定期检查是否仍需保留")
    }
}

// 从已注册的 config.key 生成带注释的 config.base.conf
fun generateConfigBase() {
    val baseFile = Config.dataDir.resolve("config.base.conf")
    val keys = ConfigBuilder.all.values.sortedBy { it.path }
    if (keys.isEmpty()) return

    val sb = StringBuilder()
    sb.append("# ============================================================\n")
    sb.append("# 配置参考文件（带注释，自动生成）\n")
    sb.append("# 实际配置请修改 config.conf（config.conf 会 fallback 到此文件）\n")
    sb.append("# 修改 config.conf 后执行 sa reload 重载生效\n")
    sb.append("# ============================================================\n\n")

    // 按顶层模块分组
    val grouped = keys.groupBy { it.path.substringBefore('.') }
    grouped.forEach { (topLevel, groupKeys) ->
        sb.append("# ===== $topLevel =====\n\n")
        // 构建嵌套结构（跳过第一级，因为 topLevel 已作为外层块名）
        val root = mutableMapOf<String, Any>()
        for (key in groupKeys) {
            val parts = key.path.split('.').drop(1)
            var node = root
            for (i in parts.indices) {
                if (i == parts.lastIndex) {
                    node[parts[i]] = key
                } else {
                    @Suppress("UNCHECKED_CAST")
                    node = node.getOrPut(parts[i]) { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                }
            }
        }
        renderConfigNode(sb, topLevel, root, 0)
        sb.append("\n")
    }

    baseFile.writeText(sb.toString())
    logger.info("[langSync] 已生成 config.base.conf（${keys.size} 个配置项）")
}

// 递归渲染配置节点
@Suppress("UNCHECKED_CAST")
fun renderConfigNode(sb: StringBuilder, name: String, node: Map<String, Any>, indent: Int) {
    val pad = "  ".repeat(indent)
    val hasNested = node.values.any { it is Map<*, *> }
    if (hasNested) {
        sb.append("$pad$name {\n")
        node.forEach { (key, value) ->
            if (value is Map<*, *>) {
                renderConfigNode(sb, key, value as Map<String, Any>, indent + 1)
            } else {
                val ck = value as ConfigBuilder.ConfigKey<*>
                ck.desc.forEach { d -> sb.append("${pad}  # $d\n") }
                sb.append("${pad}  $key = ${formatConfigDefault(ck.default)}\n")
            }
        }
        sb.append("$pad}\n")
    } else {
        // 简单块（只有 key-value，无嵌套）
        sb.append("$pad$name {\n")
        node.forEach { (key, value) ->
            val ck = value as ConfigBuilder.ConfigKey<*>
            ck.desc.forEach { d -> sb.append("${pad}  # $d\n") }
            sb.append("${pad}  $key = ${formatConfigDefault(ck.default)}\n")
        }
        sb.append("$pad}\n")
    }
}

// 格式化配置默认值为 HOCON 字符串
fun formatConfigDefault(default: Any?): String {
    return when (default) {
        is String -> when {
            default.isEmpty() -> "\"\""
            default.contains("\n") -> "\"\"\"$default\"\"\""
            else -> "\"$default\""
        }
        is Boolean -> default.toString()
        is Number -> default.toString()
        is Enum<*> -> default.name
        is Map<*, *> -> {
            if (default.isEmpty()) "{}"
            else default.entries.joinToString(prefix = "{ ", postfix = " }") { "\"${it.key}\" = ${formatConfigDefault(it.value)}" }
        }
        is List<*> -> {
            if (default.isEmpty()) "[]"
            else default.joinToString(prefix = "[", postfix = "]") { formatConfigDefault(it) }
        }
        is java.time.Duration -> "\"${default.seconds}s\""
        else -> "\"$default\""
    }
}

// 服务器启动时执行同步，然后重新加载 bundle
onEnable {
    // 如果 config.base.conf 不存在，从已注册的 config.key 自动生成
    val baseFile = Config.dataDir.resolve("config.base.conf")
    if (!baseFile.exists()) {
        generateConfigBase()
        // 如果 config.conf 也不存在，复制 base.conf 作为初始配置
        val confFile = Config.dataDir.resolve("config.conf")
        if (!confFile.exists()) {
            baseFile.copyTo(confFile, overwrite = false)
            logger.info("[langSync] config.conf 不存在，已从 config.base.conf 复制初始配置")
        }
    }
    syncBundles()
    Services.get<coreLibrary.LangService>().get().loadBundles()
}
