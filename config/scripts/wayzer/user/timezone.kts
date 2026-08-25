@file:Depends("coreLibrary/lang", "多语言支持")
@file:Depends("wayzer/user/lang", "时区存储")
@file:Depends("coreMindustry/menu", "菜单系统")

package wayzer.user

import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.MenuV2
import coreMindustry.renderPaged
import mindustry.gen.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wayzer.user.timezone

name = "玩家时区设置"

// timezone 扩展属性已移到 lang.api.kt, 直接 import wayzer.user.timezone 访问
// 注意: this@getXxx 显式引用外层 PlayerData receiver
fun PlayerData.getTimezone(): String = this@getTimezone.timezone
fun PlayerData.setTimezone(v: String) { this@setTimezone.timezone = v }

// 显示 UTC 小时偏移选择菜单
suspend fun showHourMenu(player: Player) {
    val hours = (-12..14).toList()
    MenuV2(player) {
        title = "{tr timezone.menu.hour.title}".with("receiver" to player).toString()
        msg = "{tr timezone.menu.hour.msg}".with("receiver" to player, "current" to PlayerData[player].getTimezone()).toString()
        renderPaged(hours, prePage = 9, key = "utcHour") { h ->
            val sign = if (h >= 0) "+" else ""
            option("UTC$sign$h") {
                showMinuteMenu(player, h)
            }
        }
    }.send().await()
}

// 显示分钟偏移选择菜单
suspend fun showMinuteMenu(player: Player, hour: Int) {
    val minutes = (0..59).toList()
    val utcHour = "UTC${if (hour >= 0) "+" else ""}$hour"
    MenuV2(player) {
        title = "{tr timezone.menu.minute.title}".with("receiver" to player).toString()
        msg = "{tr timezone.menu.minute.msg}".with("receiver" to player, "utcHour" to utcHour).toString()
        renderPaged(minutes, prePage = 10, key = "utcMin") { m ->
            option("{tr timezone.menu.minuteOption}".with("receiver" to player, "m" to m).toString()) {
                val sign = if (hour >= 0) "+" else ""
                val tz = String.format("%s%02d:%02d", sign, hour, m)
                PlayerData[player].setTimezone(tz)
                player.sendMessage("{tr timezone.reply.tzSet}".with("receiver" to player, "tz" to tz).toString())
                player.sendMessage("{tr timezone.reply.tzChanged}".with("receiver" to player).toString())
                close()
            }
        }
        option("{tr timezone.menu.back}".with("receiver" to player).toString()) {
            showHourMenu(player)
        }
    }.send().await()
}

command("UTC", "{tr command.UTC.desc}".with()) {
    type = CommandType.Client
    body {
        val p = player ?: returnReply("{tr command.error.playerOnly}".with())
        launch(Dispatchers.game) {
            showHourMenu(p)
        }
    }
}
