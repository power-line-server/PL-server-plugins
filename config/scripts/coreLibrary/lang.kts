@file:Implement(LangService::class)
package coreLibrary

import arc.files.Fi
import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.VarString
import java.io.File
import java.util.*
import kotlin.math.max
import mindustry.Vars

name = "国际化多语言"

val fallbackMap by config.key(
    mapOf(
        "overwrite" to "@raw", "*" to "overwrite"
    ), "备用语言映射表,@raw表示内部语言,*代表所有未匹配"
)

var console by config.key("@raw", "控制台语言(不发给玩家的语句)")

val mindustrySourceDir by config.key("", "Mindustry 源码目录路径（指向包含 core/ 的顶层目录，如 ../Mindustry-159.6 或 D:/Mindustry-159.7）。留空则回退到 Vars.tree")

fun fallbackLang(lang: String): String {
    return (fallbackMap[lang] ?: fallbackMap["*"] ?: "@raw")
}

data class NewSentenceEvent(val sentence: Sentence) : Event {
    companion object : Event.Handler()
}

inner class Sentence(val raw: String) {
    val translated = mutableMapOf<String, String>()
    var lastUse: Long = -1

    fun get(lang0: String): String {
        lastUse = System.currentTimeMillis()
        var lang: String = lang0
        while (lang != "@raw") {
            translated[lang]?.let { return it }
            lang = fallbackLang(lang)
        }
        return raw
    }
}

private val file: File get() = Config.dataDir.resolve("lang.ini")
val data = mutableMapOf<String, Sentence>()
var lastSave = 0L
var needSave = false

// === bundle.properties 加载 ===
private val bundleDir: File get() = Config.dataDir.resolve("lang")

// key -> langCode -> translation
val bundleData = mutableMapOf<String, MutableMap<String, String>>()

// 所有已加载的语言代码列表（langCode -> language.name 显示名）
val supportedLangs = mutableMapOf<String, String>()

// 所有翻译值的集合，用于 templateHandler 判断是否跳过 raw 收集
val bundleValues = mutableSetOf<String>()

// 全局属性（global.properties，不含语言切换）
val globalProperties = mutableMapOf<String, String>()
val globalKeys = linkedMapOf<String, String>() // key -> desc

fun registerGlobalKey(key: String, desc: String = "") {
    if (key !in globalKeys) globalKeys[key] = desc
}

fun loadBundles() {
    bundleData.clear()
    supportedLangs.clear()
    bundleValues.clear()
    if (!bundleDir.exists()) return
    bundleDir.listFiles { f -> f.name.endsWith(".properties") }?.forEach { file ->
        val langCode = if (file.name == "bundle.properties") "en"
        else file.name.removePrefix("bundle_").removeSuffix(".properties")
        val props = Properties()
        file.bufferedReader().use { props.load(it) }
        val langName = props.getProperty("language.name") ?: langCode
        supportedLangs[langCode] = langName
        props.forEach { k, v ->
            val key = k.toString()
            val value = v.toString()
            if (value.isNotEmpty()) {
                bundleData.getOrPut(key) { mutableMapOf() }[langCode] = value
                bundleValues.add(value)
            }
        }
    }
}

/** 获取 bundleData（key -> langCode -> translation），供 WebUI 等外部模块批量读取 */
fun getBundleMap(): MutableMap<String, MutableMap<String, String>> = bundleData

fun loadGlobalProperties() {
    globalProperties.clear()
    val file = Config.dataDir.resolve("global.properties")
    if (!file.exists()) return
    val props = java.util.Properties()
    file.bufferedReader().use { props.load(it) }
    props.forEach { k, v -> globalProperties[k.toString()] = v.toString() }
}

/** 静态扫描脚本目录, 提取所有 globalLink("key") / registerGlobalKey("key","desc") 调用 */
fun scanGlobalKeys() {
    val scriptsDir = Config.dataDir.parentFile ?: return
    val pattern1 = Regex("""globalLink\(\s*["']([^"']+)["']\s*\)""")
    val pattern2 = Regex("""registerGlobalKey\(\s*["']([^"']+)["']\s*,\s*["']([^"']*)["']\s*\)""")
    val commentBlock = Regex("""/\*[\s\S]*?\*/""")
    val commentLine = Regex("""//[^\n]*""")
    scriptsDir.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
        .forEach { file ->
            val text = file.readText()
                .replace(commentBlock, "")
                .replace(commentLine, "")
            pattern1.findAll(text).forEach { m ->
                if (m.groupValues[1] !in globalKeys) globalKeys[m.groupValues[1]] = ""
            }
            pattern2.findAll(text).forEach { m ->
                registerGlobalKey(m.groupValues[1], m.groupValues[2])
            }
        }
}

fun generateGlobalFiles() {
    // 静态扫描所有脚本文件, 提取 globalLink/registerGlobalKey 调用
    scanGlobalKeys()
    val dir = Config.dataDir
    // 生成 global.base.properties (覆盖)
    val baseFile = dir.resolve("global.base.properties")
    val sb = StringBuilder()
    sb.appendLine("# Global properties base file (auto-generated)")
    sb.appendLine("# Copy needed keys to global.properties and fill in values")
    sb.appendLine("# This file is regenerated on every startup")
    if (globalKeys.isEmpty()) {
        sb.appendLine("# No global keys registered yet")
    } else {
        globalKeys.forEach { (key, desc) ->
            if (desc.isNotEmpty()) sb.appendLine("# $desc")
            sb.appendLine("$key=")
        }
    }
    baseFile.writeText(sb.toString())
    // 生成 global.properties (仅不存在时创建空文件)
    val propsFile = dir.resolve("global.properties")
    if (!propsFile.exists()) {
        propsFile.writeText("# Global properties\n# Fill in values based on global.base.properties\n")
    }
}

// 按 lang 查 bundle 翻译，含 fallback：玩家语言 -> zh_CN -> null
fun translateBundle(key: String, lang: String): String? {
    val translations = bundleData[key] ?: return null
    translations[lang]?.let { return it }
    if (lang != "zh_CN") translations["zh_CN"]?.let { return it }
    return null
}

/** 获取游戏 bundle 的基础 Fi（不含 locale 后缀），供 I18NBundle.createBundle() 使用。
 *  优先从 mindustrySourceDir/core/assets/bundles/bundle 加载，回退到 Vars.tree.get("bundles/bundle")
 */
fun getGameBundleBase(): Fi {
    val dir = mindustrySourceDir.trim()
    if (dir.isNotEmpty()) {
        val bundleDir = Fi(dir).child("core/assets/bundles")
        val bundleProps = bundleDir.child("bundle.properties")
        if (bundleProps.exists()) return bundleDir.child("bundle")
        logger.warning("[Lang] mindustrySourceDir 配置的 bundles 目录不存在: ${bundleProps.path()}，回退到 Vars.tree")
    }
    return Vars.tree.get("bundles/bundle")
}

/** 读取 global.properties 中的全局链接/配置 */
fun globalLink(key: String): String {
    registerGlobalKey(key)
    return globalProperties[key] ?: ""
}

// 注册 {tr key} 翻译函数
// DynamicVar lambda 签名: VarString.(VarString.Parameters) -> Any?
// this=VarString 上下文, params=Parameters;用 this.createChild 返回 VarString 使翻译值中的 {var} 能被递归解析
// 注意: 必须用 VarToken(...) (VarString 成员方法) 而非 PlaceHoldApi.GlobalContext.VarToken(...)
// 因为 receiver 是 .with("receiver" to ...) 注入的局部变量, GlobalContext 无法访问
registerVar("tr", "翻译函数,用法: tr(键名)", DynamicVar { params ->
    val key = params.getOrNull<VarString.VarToken>(0)?.name ?: return@DynamicVar null
    val lang = runCatching { VarToken("receiver.lang").get()?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && !it.startsWith("{ERR") }
        ?: "zh_CN"
    val translated = translateBundle(key, lang) ?: key
    this.createChild(translated, emptyMap())
})

fun save() {
    file.bufferedWriter().use { writer ->
        writer.appendLine("# ScriptAgent Lang File")
        writer.appendLine("# field starting with '_' should not modify")
        writer.appendLine("# use '\\n' for newline in value")
        writer.appendLine("# Save in ${Date()}")
        writer.appendLine("# WARNING: this is not completed ini file, some format may not support")
        fun writeKV(key: String, value: String) {
            writer.append(key)
            writer.append("=\"")
            var start = 0
            while (true) {
                val end = value.indexOf('\n', startIndex = start)
                if (end == -1) break
                writer.append(value.subSequence(start, end))
                writer.append("\\n")
                start = end + 1
            }
            writer.append(value.subSequence(start, value.length))
            writer.appendLine("\"")
        }
        data.values.sortedBy { it.raw }.forEach {
            writer.appendLine("[[sentence]]")
            writeKV("_raw", it.raw)
            writer.appendLine("_lastUse=${it.lastUse}")
            it.translated.forEach(::writeKV)
        }
    }
    lastSave = file.lastModified()
    needSave = false
}

fun load() {
    if (file.exists().not()) return
    file.bufferedReader().use { reader ->
        val map = mutableMapOf<String, String>()
        fun handle() {
            if (map.isEmpty()) return
            val raw = map.remove("_raw") ?: error("no _raw")
            val lastUse = map.remove("_lastUse")?.toLongOrNull() ?: error("no _lastUse")
            data.getOrPut(raw) { Sentence(raw) }.apply {
                this.lastUse = max(this.lastUse, lastUse)
                translated.putAll(map)
            }
        }
        for (line in reader.lineSequence()) {
            if (line.firstOrNull() == '#') continue
            if (line == "[[sentence]]") {
                handle()
                map.clear()
            } else {
                var start = line.indexOf('=')
                if (start == -1) error("Bad ini line: $line")
                val key = line.substring(0, start)

                start += 1
                val wrapped = line[start] == '"'
                if (wrapped) start++
                val value = StringBuffer(line.length)
                while (true) {
                    val end = line.indexOf('\\', startIndex = start)
                    if (end == -1) break
                    val next = line.getOrNull(end + 1) ?: break
                    value.append(line.subSequence(start, end))
                    start = when (next) {
                        '\\' -> end + 2
                        'n' -> (end + 2).also { value.append('\n') }
                        else -> end + 1
                    }
                }
                value.append(line.subSequence(start, line.length + if (wrapped) -1 else 0))
                map[key] = value.toString()
            }
        }
    }
    lastSave = file.lastModified()
}

registerVarForType<CommandContext.ConsoleReceiver>().registerChild("lang", "控制台语言") { console }

onEnable {
    loadBundles()
    loadGlobalProperties()
    val bak = PlaceHold.templateHandler
    PlaceHold.templateHandler = h@{
        val str = bak(it)
        // 如果是 bundle 翻译值，不再收集为 raw 句子
        if (str in bundleValues) return@h str
        val lang = VarToken("receiver.lang").get()?.toString() ?: return@h str
        data.getOrPut(str) {
            needSave = true
            Sentence(str).also { sentence ->
                launch { NewSentenceEvent(sentence).emitAsync() }
            }
        }.get(lang)
    }
    // 静态扫描脚本文件生成 global.base.properties 和 global.properties
    generateGlobalFiles()
    onDisable {
        PlaceHold.templateHandler = bak
    }
}

val commands = Commands()
commands += CommandInfo(null, "load", "{tr saLang.command.load.desc}".with()) {
    body {
        load()
        reply("[green]{tr saLang.reply.loadSuccess}".with())
    }
}
commands += CommandInfo(null, "save", "{tr saLang.command.save.desc}".with()) {
    body {
        launch {
            save()
            reply("[green]{tr saLang.reply.saveSuccess}".with())
        }
    }
}
commands += CommandInfo(null, "set", "{tr saLang.command.set.desc}".with()) {
    body {
        if (arg.isEmpty()) {
            val langs = supportedLangs.entries.joinToString(", ") { "${it.value}(${it.key})" }
            returnReply("[yellow]{tr saLang.reply.currentLang}".with("v" to console, "available" to langs))
        }
        console = arg[0]
        reply("[green]{tr saLang.reply.consoleLangSet}".with("v" to console))
    }
}
commands += CommandInfo(null, "reload", "{tr saLang.command.reload.desc}".with()) {
    body {
        loadBundles()
        reply("[green]{tr saLang.reply.reloadSuccess}".with())
    }
}
command("lang", "{tr saLang.command.lang.desc}".with(), commands = Commands.controlCommand) {
    requirePermission(dotId)
    body(commands)
}
onEnable {
    launch {
        loadBundles()
        load()
        while (isActive) {
            delay(60_000)
            if (!needSave) continue
            if (lastSave < file.lastModified()) {
                logger.warning("自动保存失败: 语言文件已改动，请使用/sa lang save手动保存")
                continue
            }
            save()
        }
    }
}