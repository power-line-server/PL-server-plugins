package coreLibrary.lib

import arc.graphics.Color
import arc.graphics.Colors as ArcColors
import java.io.File
import java.util.LinkedHashMap

/**
 * 从 Mindustry 源码目录 + arc 运行时反射 提取完整的颜色映射数据。
 *
 * 颜色来源（优先级从低到高）：
 * 1. arc.graphics.Color 静态字段（22 个标准色，如 white/gray/red/green/blue）
 * 2. arc.graphics.Colors 注册表（41 个 arc Colors.put + 5 个 UI.loadColors = 46 命名色）
 * 3. Mindustry 源码 UI.java loadColors() 中注册的名称解析
 *
 * config.conf 中的 mindustrySourceDir 决定是否启用源码解析。
 * 若源码目录不可用，自动回退到 arc 运行时数据（仍能获取全部 46 命名色）。
 */
object ColorExtractor {

    /** Mindustry 源码目录路径（由 config 配置） */
    var sourceDir: String = ""

    /** 终端 &xx 码 → WebUI 显示色值（arc 设计映射，非 ANSI 标准色） */
    private val termColors2Map = mapOf(
        "&k" to "#7f7f7f", "&w" to "#ffffff", "&r" to "#e55454", "&g" to "#38d667",
        "&y" to "#ffff00", "&b" to "#4169e1", "&m" to "#aa00ff", "&c" to "#00ffff",
        "&p" to "#aa00ff",
        "&K" to "#bfbfbf", "&W" to "#ffffff", "&R" to "#fa8072", "&G" to "#38d667",
        "&Y" to "#ffd700", "&B" to "#87ceeb", "&M" to "#ff80c0", "&C" to "#00ffff",
        "&P" to "#ff80c0"
    )

    /** 终端 &l 前缀亮色码 → WebUI 显示色值 */
    private val termColors3Map = mapOf(
        "&lc" to "#00ffff", "&lb" to "#87ceeb", "&ly" to "#ffd700", "&lr" to "#fa8072",
        "&lk" to "#bfbfbf", "&lw" to "#ffffff", "&lg" to "#38d667", "&lm" to "#ff80c0"
    )

    /** 背景色码 → WebUI 显示色值 */
    private val bgColorsMap = mapOf(
        "&br" to "#e55454", "&bg" to "#38d667", "&by" to "#ffff00", "&bb" to "#4169e1"
    )

    /** ANSI 数字码 → WebUI 显示色值 */
    private val ansiColorsMap = mapOf(
        30 to "#7f7f7f", 31 to "#e55454", 32 to "#38d667", 33 to "#ffff00",
        34 to "#4169e1", 35 to "#aa00ff", 36 to "#00ffff", 37 to "#ffffff",
        90 to "#bfbfbf", 91 to "#fa8072", 92 to "#38d667", 93 to "#ffd700",
        94 to "#87ceeb", 95 to "#ff80c0", 96 to "#00ffff", 97 to "#ffffff"
    )

    /** 样式码 → CSS 属性 */
    val styleCodes = mapOf(
        "&fb" to "bold",
        "&fd" to "dim",
        "&fu" to "underline",
        "&fi" to "italic",
        "&fr" to "reset"
    )

    // ============================================================
    //  1. 源码目录解析
    // ============================================================

    /** 解析并返回 Mindustry 源码目录的 File 对象 */
    fun resolveSourceDir(): File? {
        val dir = sourceDir.trim()
        if (dir.isEmpty()) return null
        var file = File(dir)
        if (!file.isAbsolute) {
            file = File(System.getProperty("user.dir"), dir)
        }
        return if (file.exists() && file.isDirectory) file else null
    }

    // ============================================================
    //  2. Pal.java 解析
    // ============================================================

    /**
     * 解析 Pal.java，提取所有可静态求值的 Color 常量 → 6 位 hex。
     *
     * 支持的格式：
     * - `field = Color.valueOf("hex")` → direct
     * - `field = new Color(r, g, b, a)` → float → hex
     * - `field = Pal.otherField` → alias（仅在已解析的映射中存在时解析）
     *
     * 不解析的格式（回退到运行时反射）：
     * - `field = Color.staticField.cpy()`
     * - `field = Pal.field.cpy().mul(x)`
     * - `field = expr.lerp(...)`
     */
    fun parsePalColors(sourceDir: File): Map<String, String> {
        val palFile = File(sourceDir, "core/src/mindustry/graphics/Pal.java")
        if (!palFile.exists()) return emptyMap()

        val text = palFile.readText()
        val result = LinkedHashMap<String, String>()

        // 提取 class body（public static Color ... ;）
        val bodyMatch = Regex(
            """public\s+static\s+Color\s+(.*?);""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(text) ?: return emptyMap()
        val body = bodyMatch.groupValues[1]

        // 逐行解析
        val lines = body.split("\n")
        for (line in lines) {
            val trimmed = line.trim()

            // Color.valueOf("hex")
            val hexMatch = Regex("""(\w+)\s*=\s*Color\.valueOf\("([0-9a-fA-F]{6,8})"\)""").find(trimmed)
            if (hexMatch != null) {
                val name = hexMatch.groupValues[1]
                val hex = hexMatch.groupValues[2].substring(0, 6)
                result[name] = "#$hex"
                continue
            }

            // new Color(r, g, b, a) — float 格式
            val newCMatch = Regex("""(\w+)\s*=\s*new Color\(([^)]+)\)""").find(trimmed)
            if (newCMatch != null) {
                val name = newCMatch.groupValues[1]
                val args = newCMatch.groupValues[2].split(",").map {
                    it.trim().toFloatOrNull() ?: 0f
                }
                if (args.size >= 3) {
                    val r = (args[0] * 255).toInt().coerceIn(0, 255)
                    val g = (args[1] * 255).toInt().coerceIn(0, 255)
                    val b = (args[2] * 255).toInt().coerceIn(0, 255)
                    result[name] = "#%02x%02x%02x".format(r, g, b)
                    continue
                }
            }

            // Pal.field alias
            val palRefMatch = Regex("""(\w+)\s*=\s*Pal\.(\w+)""").find(trimmed)
            if (palRefMatch != null) {
                val name = palRefMatch.groupValues[1]
                val ref = palRefMatch.groupValues[2]
                result[ref]?.let { result[name] = it }
                continue
            }
        }

        return result
    }

    // ============================================================
    //  3. UI.java loadColors() 解析
    // ============================================================

    /**
     * 解析 UI.java 中 loadColors() 方法，提取已注册的命名色 → hex。
     *
     * 支持的格式：
     * - `Colors.put("name", Color.valueOf("hex"))` → direct
     * - `Colors.put("name", Pal.field)` → 从 palColors 查询
     *
     * 不解析的格式（回退到运行时 arc Colors）：
     * - `Colors.put("name", Pal.field.cpy().lerp(...))`
     */
    fun parseUIColorNames(sourceDir: File, palColors: Map<String, String>): Map<String, String> {
        val uiFile = File(sourceDir, "core/src/mindustry/core/UI.java")
        if (!uiFile.exists()) return emptyMap()

        val text = uiFile.readText()
        val result = LinkedHashMap<String, String>()

        // 找到 loadColors() 方法体
        val methodMatch = Regex(
            """(?:public\s+)?static\s+void\s+loadColors\s*\(\s*\)\s*\{(.*?)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(text) ?: return emptyMap()
        val body = methodMatch.groupValues[1]

        // Colors.put("name", VALUE)
        val putPattern = Regex("""Colors\.put\s*\(\s*"(\w+)"\s*,\s*([^)]+?)\s*\)""")
        for (match in putPattern.findAll(body)) {
            val name = match.groupValues[1]
            val valueExpr = match.groupValues[2].trim()

            when {
                // Color.valueOf("hex")
                valueExpr.matches(Regex("""Color\.valueOf\("([0-9a-fA-F]{6,8})"\)""")) -> {
                    val h = Regex("""Color\.valueOf\("([0-9a-fA-F]{6,8})"\)""").find(
                        valueExpr
                    )!!.groupValues[1].substring(0, 6)
                    result[name] = "#$h"
                }
                // Pal.fieldRef
                valueExpr.startsWith("Pal.") -> {
                    val field = valueExpr.removePrefix("Pal.").split(Regex("""[.\s(]""")).first()
                    palColors[field]?.let { result[name] = it }
                }
                // Color.valueOf directly (no quotes in pattern above)
                valueExpr.matches(Regex("""Color\.valueOf\("([0-9a-fA-F]{6,8})"\)""")) -> {
                    // Already handled above
                }
            }
        }

        return result
    }

    // ============================================================
    //  4. arc 运行时反射
    // ============================================================

    /**
     * 获取 arc Colors 注册表中所有颜色（41 arc defaults + 5 Mindustry = 46）。
     * 这是 [name] 方括号颜色码的权威来源。
     */
    fun getArcColors(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        try {
            val entries = ArcColors.getColors()
            for (entry in entries) {
                val name = (entry.key as? String)?.lowercase() ?: continue
                val color = entry.value as? Color ?: continue
                val r = (color.r * 255).toInt().coerceIn(0, 255)
                val g = (color.g * 255).toInt().coerceIn(0, 255)
                val b = (color.b * 255).toInt().coerceIn(0, 255)
                result[name] = "#%02x%02x%02x".format(r, g, b)
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * 获取 arc Color 类的所有 public static Color 字段（22 个标准命名色）。
     * 优先级低于 Colors 注册表——当 Colors 注册了同名颜色时（如 red/green），
     * 注册表值会覆盖字段值。
     */
    fun getColorStatics(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        try {
            for (field in Color::class.java.declaredFields) {
                val mod = field.modifiers
                if (java.lang.reflect.Modifier.isStatic(mod) &&
                    field.type == Color::class.java
                ) {
                    field.isAccessible = true
                    val color = field.get(null) as? Color ?: continue
                    val r = (color.r * 255).toInt().coerceIn(0, 255)
                    val g = (color.g * 255).toInt().coerceIn(0, 255)
                    val b = (color.b * 255).toInt().coerceIn(0, 255)
                    result[field.name.lowercase()] = "#%02x%02x%02x".format(r, g, b)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    // ============================================================
    //  5. 综合生成
    // ============================================================

    /**
     * 生成完整的 命名色 → hex 映射。
     *
     * 合并来源（后覆盖前）：
     * 1. arc Color 静态字段（22 色）
     * 2. arc Colors 注册表（46 色）
     * 3. 源码 UI.java loadColors() 解析（补全动态值）
     */
    fun generateColorNameMap(): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        // 1. arc Color 静态字段（基础）
        result.putAll(getColorStatics())

        // 2. arc Colors 注册表（覆盖：red/green 等被 Colors.put 覆盖）
        result.putAll(getArcColors())

        // 3. 源码解析（补全：highlight 等动态计算的值）
        val sourceDirFile = resolveSourceDir()
        if (sourceDirFile != null) {
            try {
                val palColors = parsePalColors(sourceDirFile)
                val uiColors = parseUIColorNames(sourceDirFile, palColors)
                result.putAll(uiColors)
            } catch (_: Exception) {}
        }

        return result
    }

    /**
     * 生成后端 mindustryColorToArcMap（颜色名 → &xx 码）。
     *
     * 为每个命名色找到最接近的 arc 终端色码，用于后端日志格式转换。
     */
    fun generateMindustryColorToArcMap(): Map<String, String> {
        val colorMap = generateColorNameMap()
        return colorMap.mapValues { (_, hex) ->
            val r = Integer.parseInt(hex.substring(1, 3), 16)
            val g = Integer.parseInt(hex.substring(3, 5), 16)
            val b = Integer.parseInt(hex.substring(5, 7), 16)
            closestArcColor(r, g, b)
        }
    }

    /** 用欧几里得距离找最接近的 arc &xx 码 */
    fun closestArcColor(r: Int, g: Int, b: Int): String {
        // 各 &xx 码的 RGB 参考值，与 console.kts 保持一致
        val targets = listOf(
            "&r" to intArrayOf(229, 84, 84), "&g" to intArrayOf(56, 214, 103),
            "&y" to intArrayOf(255, 255, 0), "&b" to intArrayOf(65, 105, 225),
            "&m" to intArrayOf(170, 0, 255), "&c" to intArrayOf(0, 255, 255),
            "&p" to intArrayOf(170, 0, 255), "&k" to intArrayOf(127, 127, 127),
            "&w" to intArrayOf(255, 255, 255),
            "&R" to intArrayOf(250, 128, 114), "&G" to intArrayOf(56, 214, 103),
            "&Y" to intArrayOf(255, 215, 0),   "&B" to intArrayOf(135, 206, 235),
            "&M" to intArrayOf(255, 128, 192), "&C" to intArrayOf(0, 255, 255),
            "&K" to intArrayOf(191, 191, 191), "&W" to intArrayOf(255, 255, 255),
            "&P" to intArrayOf(255, 128, 192),
        )
        return targets.minByOrNull { (_, rgb) ->
            val dr = r - rgb[0]; val dg = g - rgb[1]; val db = b - rgb[2]
            dr * dr + dg * dg + db * db
        }?.first ?: "&w"
    }

    // ============================================================
    //  6. 前端 JSON 数据生成
    // ============================================================

    /**
     * 生成 WebUI 前端所需的完整颜色数据（Map 格式，供 webui.kts 转 JSON）。
     */
    fun generateFrontendColors(): Map<String, Any> {
        val colorMap = generateColorNameMap()

        return linkedMapOf(
            "colorMap" to colorMap,
            "termColors2" to termColors2Map,
            "termColors3" to termColors3Map,
            "bgColors" to bgColorsMap,
            "ansiColors" to ansiColorsMap.mapKeys { it.key.toString() },
            "styleCodes" to styleCodes,
            "_generated" to System.currentTimeMillis(),
            "_sourceCount" to colorMap.size,
            "_sourceDir" to (sourceDir.ifEmpty { "runtime-only" })
        )
    }
}
