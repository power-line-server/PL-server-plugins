@file:Depends("coreMindustry/menu", "菜单构建(MenuBuilder)")

package coreMindustry
//WayZer 版权所有(请勿删除版权注解)
import arc.util.Align
import cf.wayzer.scriptAgent.listenTo
import coreMindustry.MenuBuilder
import coreMindustry.MenuChooseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mindustry.gen.FollowUpMenuCallPacket
import mindustry.gen.HideFollowUpMenuCallPacket
import mindustry.gen.HideMenuBuilderCallPacket
import mindustry.gen.MenuBuilderCallPacket
import mindustry.gen.MenuCallPacket
import mindustryX.events.SendPacketEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

val defaultTemplate = """
{magic}[sky]欢迎 {cV}{player.name} [sky]来到[yellow]Power [sky]line
{listPrefix scoreboard.ext|joinLines}
{listPrefix scoreBroad.ext|joinLines}
[green]服务器状态
[accent]|[]   [green]地图: [ [yellow]{map.id} [green]][yellow]{map.name}[green] 模式: [yellow]{map.mode} [green]波数[yellow]{state.wave}
[accent]|[]   {cK}本局游戏时间: {cV}{state.gameTime 分钟}
[accent]|[]   [green]{heapUse} MB已用，已开服 {state.uptime}[]
[accent]|[]   [green]总单位数: {state.allUnit}

[gold]欢迎加入服务器QQ群：1034687528
{cA}输入 /broad 可以开关该显示
""".trimIndent()

val template by config.key(
    defaultTemplate, "积分榜模板",
    "其中{cK}{cV}{cA}为颜色变量，{listPrefix xx}行供其他插件动态扩展。",
    "开头{magic}会被替换特殊颜色，供MDTX客户端识别",
)
//Color变量 cK - KEY, cV - VALUE, cA - ACTION
val msg
    get() = template.with(
        "magic" to "[#FEBBEF][]",//供MDTX识别
        "cK" to "[gray]", "cV" to "[lightgray]", "cA" to "[slate]",
    )

val disabled = mutableSetOf<String>()

// ══ 自适应层级: 玩家打开服务器菜单期间计分板自动淡出让位 ══
/** 加入集合=关闭自适应(计分板恒定显示) */
val adaptiveOff = mutableSetOf<String>()
val adaptiveWindow by config.key(
    30, "计分板自适应隐藏窗口(秒)",
    "开启自适应时: 玩家打开服务器菜单后计分板淡出, 在该时长内保持隐藏, 之后自动恢复。",
    "菜单/UI 打开期间计分板会与其抢夺视觉层级, 自适应让计分板在菜单活跃时让位, 避免遮挡。",
)
/** 各玩家最近一次收到菜单数据包的时间戳与菜单ID: (时间, menuId) */
val lastMenuAt = ConcurrentHashMap<String, Pair<Long, Int>>()

// 菜单发送/隐藏时记录时间(逐连接级), 自适应开启的玩家在窗口期内跳过计分板刷新, 2秒后自然淡出让位
// 服务端主动隐藏菜单没有客户端回包可依赖, 监听到且 menuId 匹配时立即恢复计分板, 不等满隐藏窗口
// 协议: 160 起菜单走 MenuBuilder(MenuBuilderCallPacket/HideMenuBuilderCallPacket);
//       MenuCallPacket/FollowUpMenuCallPacket/HideFollowUpMenuCallPacket 只在旧协议应急回退
//       (menu.useMenuBuilderProtocol=false)时才出现。两套都要认, 否则自适应收不到任何菜单事件。
listen<SendPacketEvent> {
    val con = it.con ?: return@listen
    val uuid = con.player?.uuid() ?: return@listen
    val packet = it.packet
    when (packet) {
        // 160 新协议: MenuBuilderCallPacket 的菜单ID字段是 id(不是 menuId)
        is MenuBuilderCallPacket -> lastMenuAt[uuid] = System.currentTimeMillis() to packet.id
        is HideMenuBuilderCallPacket -> {
            val cur = lastMenuAt[uuid] ?: return@listen
            if (cur.second == packet.menuId) lastMenuAt.remove(uuid)
        }
        // 旧协议(<=159): 应急回退开关关闭新协议时使用
        is MenuCallPacket -> lastMenuAt[uuid] = System.currentTimeMillis() to packet.menuId
        is FollowUpMenuCallPacket -> lastMenuAt[uuid] = System.currentTimeMillis() to packet.menuId
        is HideFollowUpMenuCallPacket -> {
            val cur = lastMenuAt[uuid] ?: return@listen
            if (cur.second == packet.menuId) lastMenuAt.remove(uuid)
        }
    }
}

// 菜单关闭/点选后的恢复策略。
// MenuChooseEvent 是 SA 事件(menu.kts 用 launchEmit 发射), 必须用 listenTo 走 SA 事件链;
// 用 listen 会经 coreMindustry.lib.ListenExt 注册进 arc 的 Events.events 表, 而 SA 的 launchEmit
// 不经过 arc.Events.fire -> 永远收不到(实测确认)。
listenTo<MenuChooseEvent> {
    val uuid = this.player.uuid()
    if (this.value < 0) {
        // 取消/关闭(menu.kts 对取消回包给 -1): 之后不会再有菜单, 立即恢复计分板
        lastMenuAt.remove(uuid)
    } else {
        // 点选: 可能触发 followup/翻页而重发菜单, 3 秒内没有新菜单包才恢复, 避免计分板闪一下
        launch(Dispatchers.Default) {
            delay(3000)
            val last = lastMenuAt[uuid]?.first ?: return@launch
            if (System.currentTimeMillis() - last >= 3000) lastMenuAt.remove(uuid)
        }
    }
}

command("board", "{tr scoreboard.command.board.desc}".with()) {
    aliases = listOf("broad", "scoreboard")
    attr(ClientOnly)
    usage = "[adaptive true|false]"
    body {
        when (arg.getOrNull(0)?.lowercase()) {
            "adaptive" -> when (arg.getOrNull(1)?.lowercase()) {
                "true" -> {
                    adaptiveOff.remove(player!!.uuid())
                    reply("{tr scoreboard.reply.adaptiveOn}".with())
                }
                "false" -> {
                    adaptiveOff.add(player!!.uuid())
                    reply("{tr scoreboard.reply.adaptiveOff}".with())
                }
                else -> reply("{tr scoreboard.reply.adaptiveUsage}".with())
            }
            null -> launch { openBoardMenu(player!!) }
            else -> reply("{tr scoreboard.reply.boardUsage}".with())
        }
    }
}

/** 计分板设置菜单: 开关计分板显示与自适应层级 */
suspend fun openBoardMenu(p: Player) {
    MenuBuilder<Unit> {
        title = "{tr scoreboard.menu.title}".with("receiver" to p).toString()
        msg = "{tr scoreboard.menu.msg}".with("receiver" to p).toString()
        newRow()
        option(
            (if (disabled.contains(p.uuid())) "{tr scoreboard.menu.scoreboard.off}" else "{tr scoreboard.menu.scoreboard.on}")
                .with("receiver" to p).toString()
        ) {
            if (!disabled.remove(p.uuid())) disabled.add(p.uuid())
            refresh()
        }
        newRow()
        option(
            (if (adaptiveOff.contains(p.uuid())) "{tr scoreboard.menu.adaptive.off}" else "{tr scoreboard.menu.adaptive.on}")
                .with("receiver" to p).toString()
        ) {
            if (!adaptiveOff.remove(p.uuid())) adaptiveOff.add(p.uuid())
            refresh()
        }
    }.sendTo(p)
}

//避免找不到 scoreboard.ext.* 变量
registerVar("scoreboard.ext.null", "空占位", null)
registerVar("scoreBroad.ext.null", "空占位(兼容旧插件)", null)

registerVar("scoreboard.ext.patches-count", "Patcher状态显示", DynamicVar {
    if (state.data.patches.isEmpty) return@DynamicVar null
    "{tr scoreboard.var.patchesCount}".with("count" to state.data.patches.size)
})

onEnable {
    // 用 Dispatchers.Default 运行循环, delay 在线程池上响应取消, 不依赖主线程调度.
    // 关闭时 SA4 的 disableAll 可能在主线程上 runBlocking 等待协程取消,
    // 如果协程在 Dispatchers.game(主线程)上 delay, 取消需要主线程调度 -> 死锁 -> 超时.
    // Dispatchers.Default 的线程池不受主线程阻塞影响, 协程可快速取消.
    loop(Dispatchers.Default) {
        delay(Duration.ofSeconds(2).toMillis())
        withContext(Dispatchers.game) {
            Groups.player.forEach {
                val uuid = it.uuid()
                if (disabled.contains(uuid)) return@forEach
                // 自适应: 菜单活跃期间跳过刷新, 计分板 2 秒后自然淡出让位
                if (uuid !in adaptiveOff && adaptiveWindow > 0 &&
                    System.currentTimeMillis() - (lastMenuAt[uuid]?.first ?: 0L) < adaptiveWindow * 1000L
                ) return@forEach
                val mobile = it.con?.mobile == true
                Call.infoPopup(
                    it.con, msg.with().toPlayer(it), 2.013f,
                    Align.topLeft, if (mobile) 210 else 155, 0, 0, 0
                )
            }
        }
    }
}