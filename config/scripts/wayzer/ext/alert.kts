package wayzer.ext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Duration

val type by config.key(MsgType.InfoMessage, "发送方式")
val time by config.key(Duration.ofMinutes(10)!!,"公告间隔")
val list by config.key(emptyList<String>(),"公告列表,支持颜色和变量")

var i = 0
fun broadcast(){
    if(list.isEmpty())return
    i %= list.size
    broadcast(list[i].with(),type,15f)
    i++
}

onEnable{
    // 用 Dispatchers.Default 运行循环, 见 scoreboard.kts 的说明
    launch(Dispatchers.Default) {
        while (isActive) {
            delay(time.toMillis())
            withContext(Dispatchers.game) { broadcast() }
        }
    }
}