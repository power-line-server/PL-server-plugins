@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package coreLibrary.lib

/**
 * 配置Api
 * 用于定义脚本的配置项
 * 配置项可在文件中或者使用指令修改
 * @sample
 * val welcomeMsg by config.key("Hello Steve","The message show when player join")
 * println(welcomeMsg)
 */
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.events.ScriptStateChangeEvent
import cf.wayzer.scriptAgent.getContextScript
import cf.wayzer.scriptAgent.listenTo
import cf.wayzer.scriptAgent.util.DSLBuilder
import com.typesafe.config.*
import io.github.config4k.ClassContainer
import io.github.config4k.TypeReference
import io.github.config4k.readers.SelectReader
import io.github.config4k.toConfig
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.properties.PropertyDelegateProvider
import kotlin.reflect.KProperty

open class ConfigBuilder(private val path: String, val script: Script?) {
    /**
     * @param desc only display the first line using command
     */
    data class ConfigKey<T : Any>(
        val path: String,
        val cls: ClassContainer,
        val default: T,
        val desc: List<String>,
        private val onChange: ((T) -> Unit)?
    ) {
        private lateinit var cache: T
        private var cacheTime = 0L
        private fun cache(v: T): T {
            val changed = cacheTime == 0L || cache != v
            cache = v
            cacheTime = System.currentTimeMillis()
            if (changed)
                onChange?.invoke(v)
            return v
        }

        fun get(): T {
            if (cacheTime > lastLoad) return cache
            val v = fileConfig.extract(cls, path) ?: return cache(default)
            @Suppress("UNCHECKED_CAST")
            if (cls.mapperClass.isInstance(v))
                return cache(v as T)
            error("Wrong config type: $path get $v")
        }

        fun set(v: T, saveDefault: Boolean = false) {
            cache(v)
            val write = if (!saveDefault && v == default) null else v.toConfigValue()
            modifyFile(path, write?.withOrigin(ConfigOriginFactory.newSimple().withComments(desc)))
        }

        /**
         * 清除设定值
         */
        fun reset() {
            if (!fileConfig.hasPath(path)) return
            set(default)
        }

        /**
         * 写入默认值到文件中
         */
        fun writeDefault() = set(default, saveDefault = true)

        fun getString(): String {
            return get().toConfigValue().render(renderConfig)
        }

        /**
         * @return format like [getString]
         * @throws IllegalArgumentException when parse fail
         */
        fun setString(strV: String): String {
            val str = "$path = $strV"
            val v = ConfigFactory.parseString(str).extract(cls, path)
            if (cls.mapperClass.isInstance(v)) {
                @Suppress("UNCHECKED_CAST")
                set(v as T)
                return str
            }
            throw IllegalArgumentException("Parse \"$str\" fail: get $v")
        }

        operator fun getValue(thisRef: Any?, prop: KProperty<*>) = get()
        operator fun setValue(thisRef: Any?, prop: KProperty<*>, v: T) = set(v)

        companion object {
            /**
             * Copy from config4k as can't use reified param
             */
            fun Config.extract(cls: ClassContainer, path: String): Any? {
                if (!hasPath(path)) return null
                return SelectReader.getReader(cls).invoke(this, path)
            }

            fun Any.toConfigValue(): ConfigValue {
                return if (this is Map<*, *> && this.keys.all { it is String }) {//修复issue #7
                    ConfigValueFactory.fromMap(this.mapKeys {
                        it.key as String
                    }.mapValues { it.value?.toConfigValue() })
                } else this.toConfig("root").root()["root"]!!
            }
        }
    }

    fun child(sub: String) = ConfigBuilder("$path.$sub", script)

    //internal
    fun <T : Any> key(
        script: Script, name: String,
        cls: ClassContainer, default: T, vararg desc: String,
        onChange: ((T) -> Unit)?
    ): ConfigKey<T> {
        val key = ConfigKey("$path.$name", cls, default, desc.toList(), onChange)
        script.configs.add(key)
        all[key.path] = key
        if (onChange != null) key.get()//ensure onChange get the init value
        return key
    }

    inner class KeyProvider<T : Any>(val type: ClassContainer, val default: T, val desc: Array<out String>) :
        PropertyDelegateProvider<Any?, ConfigKey<T>> {
        override fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigKey<T> {
            val script: Script = when {
                thisRef is Script -> thisRef
                this@ConfigBuilder.script != null -> this@ConfigBuilder.script
                else -> error("Can't get script in context")
            }
            return key(script, property.name, type, default, *desc, onChange = null)
        }
    }

    /**
     * The most commonly used api
     * Example(in script)
     * val port by config.key(8080,"示例配置项")
     */
    inline fun <reified T : Any> key(default: T, vararg desc: String) =
        KeyProvider(ClassContainer<T>(), default, desc)

    /**
     * commonly only use [onChange] not return
     * @param onChange hook when value change, and when first time.
     */
    inline fun <reified T : Any> key(
        name: String, default: T, vararg desc: String,
        noinline onChange: (T) -> Unit
    ): ConfigKey<T> {
        return key(
            script ?: error("Can't get script in context"), name,
            ClassContainer<T>(), default, *desc, onChange = onChange
        )
    }

    companion object {
        private val renderConfig = ConfigRenderOptions.defaults().setOriginComments(false)
        private val key_configs = DSLBuilder.DataKeyWithDefault("configs") { mutableSetOf<ConfigKey<*>>() }
        val Script.configs by key_configs
        val all = mutableMapOf<String, ConfigKey<*>>()
        var configBaseFile: File = cf.wayzer.scriptAgent.Config.dataDir.resolve("config.base.conf")
        var configFile: File = cf.wayzer.scriptAgent.Config.dataDir.resolve("config.conf")
        private lateinit var fileConfig: Config
        private lateinit var rawConfig: Config
        fun setRawConfig(raw: Config) {
            rawConfig = raw
            val base =
                if (configBaseFile.exists()) ConfigFactory.parseFile(configBaseFile) else ConfigFactory.empty()
            fileConfig = raw
                .withFallback(ConfigFactory.systemProperties())
                .withFallback(ConfigFactory.systemEnvironment())
                .withFallback(base)
        }

        private var lastLoad: Long = -1

        init {
            ConfigBuilder::class.java.getContextScript().listenTo<ScriptStateChangeEvent> {
                //when unload
                if (script.scriptState.loaded && !next.loaded)
                    key_configs.apply {
                        script.inst?.get()?.forEach { all.remove(it.path) }
                    }
            }
            reloadFile()
        }

        fun reloadFile() {
            // config.conf 不存在时，从 config.base.conf 复制（带注释的默认配置）
            if (!configFile.exists() && configBaseFile.exists()) {
                configBaseFile.copyTo(configFile, overwrite = false)
                Logger.getLogger("ConfigApi").info("[ConfigApi] config.conf 不存在，已从 config.base.conf 复制")
            }
            // 如果 config.conf 仍不存在（base.conf 也没有），创建空文件
            if (!configFile.exists()) {
                configFile.parentFile.mkdirs()
                configFile.writeText("")
            }
            setRawConfig(ConfigFactory.parseFile(configFile))
            lastLoad = System.currentTimeMillis()
            all.values.forEach {
                try {
                    it.get()
                } catch (e: Exception) {
                    Logger.getLogger("ConfigApi").log(Level.WARNING, "Fail to parse config ${it.path}", e)
                }
            }
        }

        fun modifyFile(path: String, value: ConfigValue?, save: Boolean = true) {
            setRawConfig(rawConfig.run {
                if (value != null) withValue(path, value) else withoutPath(path)
            })
            if (save) saveFile()
        }

        fun saveFile() {
            if (!configFile.exists()) {
                configFile.writeText(rawConfig.root().render(renderConfig))
                return
            }
            val existingText = configFile.readText()
            // 如果现有文件没有注释，直接用 render 结果
            if (!existingText.contains("#")) {
                configFile.writeText(rawConfig.root().render(renderConfig))
                return
            }
            // 保留注释的增量更新：逐行扫描，只更新 key-value 行的值
            val lines = existingText.lines().toMutableList()
            val pathStack = ArrayDeque<String>()

            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trimStart()

                // 跳过注释和空行
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) { i++; continue }

                // 处理闭合括号
                if (trimmed.startsWith("}")) {
                    if (pathStack.isNotEmpty()) pathStack.removeLast()
                    i++; continue
                }

                val indent = line.takeWhile { it.isWhitespace() }

                // 匹配嵌套块开始：key { 且 { 是行末（仅后跟空白或注释）
                // 注意：key = { 或 key : { 是 inline map，不压入 pathStack，单独处理
                val blockMatch = Regex("""^["']?([\w-]+)["']?\s*\{\s*(#.*)?$""").find(trimmed)
                if (blockMatch != null) {
                    pathStack.addLast(blockMatch.groupValues[1])
                    i++; continue
                }

                // 匹配 inline map 开始：key = { 或 key : {，且 { 是行末
                // 不压入 pathStack，用括号计数器跳过整个 inline map 内容
                val inlineMapMatch = Regex("""^["']?([\w-]+)["']?\s*[:=]\s*\{\s*(#.*)?$""").find(trimmed)
                if (inlineMapMatch != null) {
                    var braceCount = 1
                    var j = i + 1
                    while (j < lines.size && braceCount > 0) {
                        val innerLine = lines[j]
                        braceCount += innerLine.count { it == '{' }
                        braceCount -= innerLine.count { it == '}' }
                        j++
                    }
                    i = j
                    continue
                }

                // 匹配 key = value 或 key : value（排除 key { 的情况）
                val kvMatch = Regex("""^["']?([\w-]+)["']?\s*[:=]\s*(.*)""").find(trimmed)
                if (kvMatch != null) {
                    val key = kvMatch.groupValues[1]
                    val valuePart = kvMatch.groupValues[2]
                    val currentPath = if (pathStack.isEmpty()) key else "${pathStack.joinToString(".")}.$key"

                    if (!rawConfig.hasPath(currentPath)) {
                        // rawConfig 中没有该 path（被 reset 或来自 fallback）：保留原行不动
                        i++; continue
                    }

                    var newValue = rawConfig.getValue(currentPath).render(renderConfig).trim()
                    if (newValue.contains("\n")) {
                        // 多行值(如 inline map): 用单行 render 重试, 保证能整行更新(修复 map 配置持久化不生效)
                        val singleLine = ConfigRenderOptions.defaults().setOriginComments(false).setFormatted(false)
                        newValue = rawConfig.getValue(currentPath).render(singleLine).trim()
                    }

                    // 检测多行三引号字符串（""" 开始但未在同一行结束）
                    val firstTriple = valuePart.indexOf("\"\"\"")
                    val isCurrentMultiLine = firstTriple >= 0 &&
                        valuePart.indexOf("\"\"\"", firstTriple + 3) < 0

                    if (isCurrentMultiLine) {
                        // 找到结束 """ 所在的行
                        var endLine = i + 1
                        while (endLine < lines.size && !lines[endLine].contains("\"\"\"")) {
                            endLine++
                        }
                        // 删除多行值的后续行（从 i+1 到 endLine, 含 endLine 的 """)
                        repeat(endLine - i) { lines.removeAt(i + 1) }
                        // newValue 可能是多行, 拆分后插入
                        val newLines = "$indent$key = $newValue".split("\n")
                        lines[i] = newLines[0]
                        if (newLines.size > 1) {
                            newLines.drop(1).reversed().forEach { extraLine ->
                                lines.add(i + 1, extraLine)
                            }
                        }
                        // 跳过所有插入的多行内容, 避免内部行被当作 config 行扫描
                        i += newLines.size
                        continue
                    }

                    // 如果 render 结果是多行的（如复杂嵌套对象），跳过单行替换
                    // 避免把多行 HOCON 塞进一行破坏文件结构
                    if (newValue.contains("\n")) { i++; continue }

                    lines[i] = "$indent$key = $newValue"
                    i++; continue
                }
                i++
            }

            configFile.writeText(lines.joinToString("\n", postfix = "\n"))
        }

        inline fun <reified T : Any> ClassContainer(): ClassContainer {
            val genericType = object : TypeReference<T>() {}.genericType()
            return ClassContainer(T::class, genericType)
        }
    }
}

val globalConfig = ConfigBuilder("global", null)
val Script.config get() = ConfigBuilder(id.replace('/', '.'), this)