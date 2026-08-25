package coreLibrary.lib

enum class ConsoleColor(val code: String) : ColorApi.Color {
    RESET("\u001b[0m"),
    BOLD("\u001b[1m"),
    ITALIC("\u001b[3m"),
    UNDERLINED("\u001b[4m"),

    BLACK("\u001b[30m"),
    RED("\u001b[31m"),
    GREEN("\u001b[32m"),
    YELLOW("\u001b[33m"),
    BLUE("\u001b[34m"),
    PURPLE("\u001b[35m"),
    CYAN("\u001b[36m"),
    LIGHT_RED("\u001b[91m"),
    LIGHT_GREEN("\u001b[92m"),
    LIGHT_YELLOW("\u001b[93m"),
    LIGHT_BLUE("\u001b[94m"),
    LIGHT_PURPLE("\u001b[95m"),
    LIGHT_CYAN("\u001b[96m"),
    WHITE("\u001b[37m"),
    BACK_DEFAULT("\u001b[49m"),
    BACK_RED("\u001b[41m"),
    BACK_GREEN("\u001b[42m"),
    BACK_YELLOW("\u001b[43m"),
    BACK_BLUE("\u001b[44m");

    override fun toString(): String {
        return "[$name]"
    }
}

object ColorApi {
    interface Color

    private val map = mutableMapOf<String, Color>()//name(Upper)->source
    val all get() = map as Map<String, Color>
    fun register(name: String, color: Color) {
        map[name.uppercase()] = color
    }

    init {
        ConsoleColor.entries.forEach {
            register(it.name, it)
        }
    }

    fun consoleColorHandler(color: Color): String {
        return if (color is ConsoleColor) color.code else ""
    }

    /** 把 ConsoleColor 转成 arc 的 &xx 颜色码, 让 ServerControl 的 addColors/removeColors 正确处理 */
    fun arcColorHandler(color: Color): String {
        return when (color) {
            is ConsoleColor -> when (color) {
                ConsoleColor.RESET -> "&fr"
                ConsoleColor.BOLD -> "" // arc 无对应 bold 码, &b 是 blue
                ConsoleColor.ITALIC -> "&fi"
                ConsoleColor.UNDERLINED -> "&fu"
                ConsoleColor.BLACK -> "&k"
                ConsoleColor.RED -> "&r"
                ConsoleColor.GREEN -> "&g"
                ConsoleColor.YELLOW -> "&y"
                ConsoleColor.BLUE -> "&b"
                ConsoleColor.PURPLE -> "&p"
                ConsoleColor.CYAN -> "&c"
                ConsoleColor.LIGHT_RED -> "&lr"
                ConsoleColor.LIGHT_GREEN -> "&lg"
                ConsoleColor.LIGHT_YELLOW -> "&ly"
                ConsoleColor.LIGHT_BLUE -> "&lb"
                ConsoleColor.LIGHT_PURPLE -> "&lm"
                ConsoleColor.LIGHT_CYAN -> "&lc"
                ConsoleColor.WHITE -> "&w"
                ConsoleColor.BACK_DEFAULT -> "&bd"
                ConsoleColor.BACK_RED -> "&br"
                ConsoleColor.BACK_GREEN -> "&bg"
                ConsoleColor.BACK_YELLOW -> "&by"
                ConsoleColor.BACK_BLUE -> "&bb"
            }
            else -> ""
        }
    }

    fun handle(raw: String, colorHandler: (Color) -> String): String {
        return raw.replace(Regex("\\[([!a-zA-Z_]+)]")) {
            val matched = it.groupValues[1]
            if (matched.startsWith("!")) return@replace "[${matched.substring(1)}]"
            val color = all[matched.uppercase()] ?: return@replace it.value
            return@replace colorHandler(color)
        }
    }
}
