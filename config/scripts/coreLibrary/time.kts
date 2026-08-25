package coreLibrary

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.VarString
import coreLibrary.lib.parseTimeZone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

name = "时间与时区变量"

// 默认时间格式
val DEFAULT_TIME_FORMAT = "M.d-HH:mm:ss"

// 从当前 PlaceHold 上下文获取 receiver 的时区偏移
// 必须是 VarString 扩展函数: receiver 是 .with("receiver" to ...) 注入的局部变量,
// 需通过 VarString 成员方法 VarToken(...) 访问, GlobalContext 无法访问
fun VarString.getReceiverTimeZone(): String {
    return runCatching {
        VarToken("receiver.timezone").get()?.toString() ?: "+08:00"
    }.getOrNull() ?: "+08:00"
}

// 注册 time.now：当前时刻按 receiver 时区格式化
registerVar("time.now", "当前时间(按receiver时区)", DynamicVar {
    val tz = getReceiverTimeZone()
    val zone = parseTimeZone(tz)
    val now = Instant.now().atZone(zone)
    now.format(DateTimeFormatter.ofPattern(DEFAULT_TIME_FORMAT))
})

// 注册 time.format：按 receiver 时区格式化当前时间，{time.format pattern}
registerVar("time.format", "格式化当前时间(按receiver时区),{time.format pattern}", DynamicVar {
    val pattern = it.getOrNull<VarString.VarToken>(0)?.name ?: DEFAULT_TIME_FORMAT
    val tz = getReceiverTimeZone()
    val zone = parseTimeZone(tz)
    val now = Instant.now().atZone(zone)
    now.format(DateTimeFormatter.ofPattern(pattern))
})

// 注册 time.timestamp：Unix 时间戳（无时区）
registerVar("time.timestamp", "Unix时间戳", DynamicVar {
    Instant.now().epochSecond.toString()
})

// 控制台时区用服务器默认
registerVarForType<CommandContext.ConsoleReceiver>()
    .registerChild("timezone", "控制台时区") { "+08:00" }
