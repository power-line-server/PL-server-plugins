package wayzer.user

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.placehold.TypeBinder
import coreLibrary.lib.util.reflectDelegate
import mindustry.gen.Player

// realName: 玩家 uuid -> 原始名字(不含前缀后缀), 供 updateName 和其他脚本使用
val realName = mutableMapOf<String, String>()

// 匿名集合: uuid 在集合中的玩家显示纯匿名名(跳过 prefix/suffix 拼接), 由匿名脚本(pvpAnonymous)维护
val anonymousUuids = mutableSetOf<String>()

val TypeBinder<*>.tree: Map<String, Any> by reflectDelegate()

/** 修复被 fixName 截断的不完整颜色代码 (如 "[white" 缺少 "]" -> 去掉不完整部分) */
fun fixTruncatedName(name: String): String {
    val lastBracket = name.lastIndexOf('[')
    if (lastBracket < 0) return name
    // 如果 [ 后面到字符串结尾没有 ], 说明是截断的不完整颜色代码
    if (name.indexOf(']', lastBracket) < 0) {
        return name.substring(0, lastBracket)
    }
    return name
}

// 刷新玩家名字(聚合 prefix/suffix), 供 title.kts 等脚本调用
fun Player.updateName() {
    val raw = fixTruncatedName(realName[uuid()] ?: "NotInit")
    // 匿名时显示纯名字(不拼 prefix/suffix): 头衔、客户端标、uid 后缀全部隐藏
    if (uuid() in anonymousUuids) {
        name = raw
        return
    }
    // 分离名字颜色与文本: 客户端设置的彩色名形如 [#2CABFEFF]test, 颜色码与文本拆开,
    // 排列为 头衔 + 颜色码 + 名字文本, 保证名字文本前紧跟它自己的颜色码(不被头衔 markup 覆盖)
    val colorMatch = Regex("""^(\[#[0-9a-fA-F]{6,8}\]|\[[a-zA-Z]+\])""").find(raw)
    val color = colorMatch?.groupValues?.get(1) ?: ""
    val nameText = raw.removePrefix(color)
    // 通过已注册的 player.prefix / player.suffix 变量获取聚合结果
    val prefix = "{player.prefix}".with("player" to this).toString()
    val suffix = "{player.suffix}".with("player" to this).toString()
    name = buildString {
        // prefix 前的 [white] 仅作无色前缀的保底色; 头衔渲染末尾不带重置码, 交给下面颜色/重置接管
        if (prefix.isNotEmpty()) append("[white]$prefix")
        // 名字颜色: 优先用客户端设置的 packet.color(原版 coloredName 同源), 其次是 raw 里带的色码
        // 颜色码紧贴名字文本前, 保证 test 不被头衔/前缀的 markup 覆盖(所有客户端都显示玩家设置的名字颜色)
        val nameColor = if (color.isNotEmpty()) color else String.format("[#%06x]", color().rgb888())
        append(nameColor).append(nameText)
        // suffix 前用 [white] 收尾: 后缀内的 [] 重置时回到白色(uid后缀/X端标志/管理员标志显示白色)
        if (suffix.isNotEmpty()) append("[white]$suffix")
    }
}
