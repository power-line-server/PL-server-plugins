@file:Depends("coreMindustry/menu", "菜单系统")
@file:Depends("wayzer/user/lang", "玩家语言")

package wayzer.user

import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.MenuV2
import mindustry.gen.Player
import kotlin.time.Duration.Companion.seconds

name = "新人服务器教程"

// 多页教程(每页一条 {tr tutorial.menu.pageN} 键, 顺序展示), 面向 @default 新玩家:
// 页1 基本指令与 /help | 页2 UID 与 @提及 | 页3 投票系统 | 页4 服务器插件/安全
// 触发: 首次入服(langSetup.kts) 或手动 /tutorial(/教学)
// 导出：供 langSetup.kts 调用
// 注意: key 必须静态字面量(langSync 正则只扫描字面量 {tr key}, 动态拼接会被误删)
suspend fun showTutorial(player: Player) {
    val pageKeys = listOf(
        "{tr tutorial.menu.page1}",
        "{tr tutorial.menu.page2}",
        "{tr tutorial.menu.page3}",
        "{tr tutorial.menu.page4}",
    )
    for ((idx, key) in pageKeys.withIndex()) {
        val isLast = idx == pageKeys.lastIndex
        MenuV2(player) {
            title = "{tr tutorial.menu.title}".with("receiver" to player).toString()
            msg = key.with("receiver" to player).toString()
            autoCloseButton = false // 关闭 MenuBuilder 自动追加的关闭按钮, 只用显式的下一页/关闭, 避免重复
            option(
                (if (isLast) "{tr coreMenu.close}" else "{tr tutorial.menu.next}")
                    .with("receiver" to player).toString()
            ) {}
        }.send().awaitWithTimeout(120.seconds)
    }
}
export(::showTutorial)

// 手动重载入服教学
command("tutorial", "{tr tutorial.command.desc}".with()) {
    aliases = listOf("教学")
    body {
        val p = player ?: returnReply("{tr tutorial.reply.playerOnly}".with())
        showTutorial(p)
    }
}
