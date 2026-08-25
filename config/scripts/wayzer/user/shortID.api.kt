package wayzer.user

import mindustry.gen.Player
import java.util.concurrent.ConcurrentHashMap

// 内存缓存(线程安全),数据库就绪后预加载
// 定义在 .api.kt 中以便其他脚本(ban.kts 等)可跨脚本访问
val uidCache = ConcurrentHashMap<String, Int>()  // uuid -> uid
val uuidCache = ConcurrentHashMap<Int, String>()  // uid -> uuid

fun Player.uid(): Int? = uidCache[uuid()]
fun uidToUuid(uid: Int): String? = uuidCache[uid]
