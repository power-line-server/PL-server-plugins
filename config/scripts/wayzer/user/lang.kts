@file:Depends("coreLibrary/lang", "多语言支持-核心")
@file:Depends("coreLibrary/extApi/KVStore", "储存语言设置")
@file:Depends("coreMindustry/menu", "菜单系统")

package wayzer.user

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.LangService
import coreMindustry.MenuV2
import coreMindustry.lib.PlayerCommandReceiver
import coreMindustry.renderPaged
import mindustry.gen.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

name = "玩家语言设置"

val langApi by lazy { Services.get<LangService>().get() }

registerVarForType<Player>()
    .registerChild("lang", "多语言支持",  {
        kotlin.runCatching { PlayerData[it].lang }.getOrNull()
    })

// === 时区设置 ===

registerVarForType<Player>()
    .registerChild("timezone", "玩家时区偏移") {
        kotlin.runCatching { PlayerData[it].timezone }.getOrNull() ?: "+08:00"
    }

// 玩家语言选择菜单
suspend fun showLangMenu(player: Player) {
    val currentLang = PlayerData[player].lang
    val currentLangName = langApi.supportedLangs[currentLang] ?: currentLang
    MenuV2(player) {
        title = "{tr lang.menu.title}".with("receiver" to player).toString()
        msg = "{tr lang.menu.msg}".with("receiver" to player, "currentLangName" to currentLangName).toString()
        renderPaged(langApi.supportedLangs.entries.toList(), prePage = 9, key = "langMenuSelect") { (code, name) ->
            val mark = if (code == currentLang) "[green]✓ " else ""
            option("$mark$name") {
                PlayerData[player].let { p -> p.lang = code }
                player.sendMessage("{tr lang.reply.langSet}".with("receiver" to player, "v" to code).toString())
                close()
            }
        }
    }.send().await()
}

command("lang", "{tr command.lang.desc}".with()) {
    permission = "wayzer.lang.set"
    body {
        // 仅玩家可用(控制台语言管理用 sa lang set)
        if (receiver !is PlayerCommandReceiver) {
            returnReply("{tr lang.reply.playerOnly}".with())
        }
        if (arg.isEmpty()) {
            // 玩家无参数: 弹出菜单选择
            launch(Dispatchers.game) {
                showLangMenu(player!!)
            }
        } else {
            // 玩家有参数: 直接设置
            val data = PlayerData[player!!]
            data.lang = arg[0]
            reply("{tr lang.reply.langSet}".with("v" to data.lang))
        }
    }
}