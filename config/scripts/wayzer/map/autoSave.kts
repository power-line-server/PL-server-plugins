@file:Depends("wayzer/maps", "获取当前地图ID")

package wayzer.map

import arc.files.Fi
import arc.struct.StringMap
import coreLibrary.lib.util.loop
import mindustry.core.GameState
import mindustry.io.SaveIO
import mindustry.io.SaveOptions
import mindustry.io.JsonIO
import wayzer.MapManager
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Level

name = "自动存档"
// 修改为100-199，共100个槽位
val autoSaveRange = 100 until 200

val nextSaveTime: Date
    get() {//Every 5 minutes
        val t = Calendar.getInstance()
        t.set(Calendar.SECOND, 0)
        val mNow = t.get(Calendar.MINUTE)
        t.add(Calendar.MINUTE, (mNow + 5) / 5 * 5 - mNow)
        return t.time
    }

onEnable {
    loop {
        val nextTime = nextSaveTime.time
        delay(nextTime - System.currentTimeMillis())
        if (state.`is`(GameState.State.playing)) {
            val minute = ((nextTime / TimeUnit.MINUTES.toMillis(1)) % 60).toInt() //Get the minute
            Core.app.post {
                // 每5分钟一个槽位，每小时12个槽位，在100个槽位中循环
                val minuteInHour = minute % 60
                val slotIndex = minuteInHour / 5  // 0-11
                val hourCycle = ((nextTime / TimeUnit.HOURS.toMillis(1)) % 24).toInt() * 12
                val totalCycle = hourCycle + slotIndex
                val id = autoSaveRange.first + (totalCycle % 100)  // 在100个槽位中循环
                
                val tmp = Fi.tempFile("save")
                try {
                    val extTag = StringMap.of(
                        "name", "{tr autoSave.saveTagPrefix}".with("id" to id).toString() + state.map.name(),
                        "description", state.map.description(),
                        "author", state.map.author(),
                        "mapId", MapManager.current.id.toString(),
                        "mapTags", JsonIO.write(state.rules.tags),
                    )
                    SaveIO.write(tmp, SaveOptions().apply { extraTags = extTag })
                    tmp.moveTo(SaveIO.fileFor(id))
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "存档存档出错", e)
                    tmp.delete()
                }
                broadcast("{tr autoSave.broadcast.saved}".with("id" to id))
            }
        }
    }
}