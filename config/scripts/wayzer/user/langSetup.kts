@file:Depends("coreLibrary/lang", "多语言支持")
@file:Depends("wayzer/user/lang", "玩家语言")
@file:Depends("wayzer/user/timezone", "时区设置菜单")
@file:Depends("wayzer/user/tutorial", "新人服务器教程")
@file:Depends("wayzer/ext/welcomeMsg", "入服欢迎")
@file:Depends("coreMindustry/menu", "菜单系统")
@file:Depends("coreLibrary/extApi/KVStore", "存储设置状态")

package wayzer.user

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.LangService
import coreMindustry.MenuV2
import coreMindustry.renderPaged
import coreLib.extApi.KVStore
import mindustry.game.EventType
import mindustry.gen.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.h2.mvstore.type.StringDataType
import wayzer.user.lang
import wayzer.user.timezone

name = "首次入服语言时区设置"

val langApi by lazy { Services.get<coreLibrary.LangService>().get() }

val setupDone by autoInit { Services.get<coreLib.extApi.KVStore>().get().open("langSetupDone", StringDataType.INSTANCE) }

// 通过 depends().import<>() 获取 welcomeMsg.kts 导出的 sendWelcome 函数
val sendWelcome: (Player) -> Unit by lazy {
    depends("wayzer/ext/welcomeMsg")?.import<(Player) -> Unit>("sendWelcome") ?: {}
}
// 广播加入消息(与菜单流程解耦,避免首次入服未完成设置时丢失)
val broadcastJoin: (Player) -> Unit by lazy {
    depends("wayzer/ext/welcomeMsg")?.import<(Player) -> Unit>("broadcastJoin")
        ?: { p -> broadcast("{tr welcome.broadcast.join}".with("player" to p)) }
}

// 通过 depends().import<>() 获取 tutorial.kts 导出的 showTutorial 函数
val showTutorial: suspend (Player) -> Unit by lazy {
    depends("wayzer/user/tutorial")?.import<suspend (Player) -> Unit>("showTutorial") ?: {}
}

// 判断是否首次
fun isFirstTime(player: Player): Boolean {
    return setupDone[PlayerData[player].id] == null
}

// 语言选择菜单
suspend fun showLanguageMenu(player: Player) {
    val currentLocale = player.locale ?: "zh_CN"
    val currentLangName = langApi.supportedLangs[currentLocale] ?: langApi.supportedLangs["zh_CN"] ?: currentLocale

    MenuV2(player) {
        title = "{tr langSetup.menu.language.title}".with("receiver" to player).toString()
        msg = "{tr langSetup.menu.language.msg}".with("receiver" to player, "currentLangName" to currentLangName).toString()
        // 确认按钮：保持当前语言
        option("{tr langSetup.menu.language.confirm}".with("receiver" to player).toString()) {
            PlayerData[player].let { p -> p.lang = currentLocale }
            player.sendMessage("{tr langSetup.reply.langChangedCurrent}".with("receiver" to player).toString())
            showTimezoneHourMenu(player)
        }
        // 列出所有可用语言
        renderPaged(langApi.supportedLangs.entries.toList(), prePage = 9, key = "langSelect") { (code, name) ->
            option(name) {
                PlayerData[player].let { p -> p.lang = code }
                player.sendMessage("{tr langSetup.reply.langSet}".with("receiver" to player, "name" to name).toString())
                player.sendMessage("{tr langSetup.reply.langChanged}".with("receiver" to player).toString())
                showTimezoneHourMenu(player)
            }
        }
    }.send().await()
}

// 时区小时选择菜单（首次入服版）
suspend fun showTimezoneHourMenu(player: Player) {
    val hours = (-12..14).toList()
    MenuV2(player) {
        title = "{tr langSetup.menu.timezone.title}".with("receiver" to player).toString()
        msg = "{tr langSetup.menu.timezone.hourMsg}".with("receiver" to player).toString()
        renderPaged(hours, prePage = 9, key = "setupUtcHour") { h ->
            val sign = if (h >= 0) "+" else ""
            option("UTC$sign$h") {
                showTimezoneMinuteMenu(player, h)
            }
        }
        option("{tr langSetup.menu.timezone.skipDefault}".with("receiver" to player).toString()) {
            PlayerData[player].let { p -> p.timezone = "+08:00" }
            player.sendMessage("{tr langSetup.reply.tzChanged}".with("receiver" to player).toString())
            finishSetup(player)
        }
    }.send().await()
}

// 时区分钟选择菜单（首次入服版）
suspend fun showTimezoneMinuteMenu(player: Player, hour: Int) {
    val minutes = (0..59).toList()
    val utcHour = "UTC${if (hour >= 0) "+" else ""}$hour"
    MenuV2(player) {
        title = "{tr langSetup.menu.timezone.title}".with("receiver" to player).toString()
        msg = "{tr langSetup.menu.timezone.minMsg}".with("receiver" to player, "utcHour" to utcHour).toString()
        renderPaged(minutes, prePage = 10, key = "setupUtcMin") { m ->
            option("{tr langSetup.menu.timezone.minuteOption}".with("receiver" to player, "m" to m).toString()) {
                val sign = if (hour >= 0) "+" else ""
                val tz = String.format("%s%02d:%02d", sign, hour, m)
                PlayerData[player].let { p -> p.timezone = tz }
                player.sendMessage("{tr langSetup.reply.tzSet}".with("receiver" to player, "tz" to tz).toString())
                player.sendMessage("{tr langSetup.reply.tzChanged}".with("receiver" to player).toString())
                finishSetup(player)
            }
        }
        option("{tr langSetup.menu.back}".with("receiver" to player).toString()) {
            showTimezoneHourMenu(player)
        }
        option("{tr langSetup.menu.timezone.skipDefault}".with("receiver" to player).toString()) {
            PlayerData[player].let { p -> p.timezone = "+08:00" }
            finishSetup(player)
        }
    }.send().await()
}

// 完成设置，标记并触发教程和欢迎
suspend fun finishSetup(player: Player) {
    setupDone[PlayerData[player].id] = "true"
    showTutorial(player)
    sendWelcome(player)
}

// 监听 PlayerJoin：先广播加入消息(与菜单流程解耦),首次走弹窗,非首次直接欢迎
listen<EventType.PlayerJoin> {
    broadcastJoin(it.player)
    if (isFirstTime(it.player)) {
        launch(Dispatchers.game) {
            showLanguageMenu(it.player)
        }
    } else {
        sendWelcome(it.player)
    }
}
