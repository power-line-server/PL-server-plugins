package wayzer.user

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mindustry.game.EventType

customLoad(::realName) { realName.putAll(it) }

registerVarForType<Player>().apply {
    registerChild("prefix", "名字前缀,可通过prefix.xxx变量注册") { p ->
        PlaceHoldApi.typeBinder<Player>().run {
            val keys = tree.keys.filter { it.startsWith("prefix.") }.sorted()
            keys.joinToString("") { k ->
                resolve(this@registerChild, p, k)?.let { resolveVarForString(it) }.orEmpty()
            }
        }
    }
    registerChild("suffix", "名字后缀,可通过suffix.xxx变量注册") { p ->
        PlaceHoldApi.typeBinder<Player>().run {
            val keys = tree.keys.filter { it.startsWith("suffix.") }.sorted()
            keys.joinToString("") { k ->
                resolve(this@registerChild, p, k)?.let { resolveVarForString(it) }.orEmpty()
            }
        }
    }
}

// 在 fixName 截断之前获取原始名字, 绕过原版 maxNameLength=40 字节限制
// fixName 会按 40 字节截断名字, 导致包含 Iconc 字符(3字节PUA)的长名字被截断
// ConnectPacketEvent 在 fixName 之前触发, 此时 packet.name 是原始名字
listen<EventType.ConnectPacketEvent> {
    val packet = it.packet
    if (packet.uuid == null) return@listen
    // 与 fixName 一致的前置清理, 但不截断
    val rawName = packet.name.trim().replace("\n", "").replace("\t", "")
    // 防御回传污染: 部分客户端会把服务器拼接后的名字(前缀+原名+后缀)回传,
    // 以"服务器拼接标记"判定: 回传名必然含 [white](前缀/后缀保底色) 或 [] 或 多个色码(头衔);
    // 客户端原始名(如 [#2CABFEFF]test)只有 0-1 个色码, 不会被误判
    val serverPatched = rawName.contains("[white]") || rawName.contains("[]") ||
        (rawName.count { it == '[' } > 1 && rawName.contains("[#"))
    val prev = realName[packet.uuid]
    realName[packet.uuid] = if (prev != null && prev.isNotEmpty() && serverPatched && rawName.contains(prev)) prev else rawName
}

listen<EventType.PlayerConnect> {
    val p = it.player
    // 若 ConnectPacketEvent 已存入原始名字则保留, 否则回退到 fixName 处理后的名字
    if (p.uuid() !in realName) {
        realName[p.uuid()] = p.name
    }
    p.updateName()
}
onEnable {
    // 用 Dispatchers.Default 运行循环, 见 scoreboard.kts 的说明
    loop(Dispatchers.Default) {
        withContext(Dispatchers.game) {
            Groups.player.forEach {
                if (it.uuid() !in realName)
                    realName[it.uuid()] = it.name
            }
        }
        delay(5000)
        withContext(Dispatchers.game) {
            Groups.player.forEach { it.updateName() }
        }
    }
}