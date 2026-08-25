package coreLibrary.lib

import java.time.ZoneId
import java.time.ZoneOffset

// 解析时区偏移字符串（如 "+08:30"、"-05:00"、"-12:05"）为 ZoneId
fun parseTimeZone(offsetStr: String): ZoneId {
    return runCatching {
        // 支持 "+08:30"、"-05:00"、"8"、"+8" 等格式
        val s = offsetStr.trim()
        val parts = s.split(":")
        val hourStr = parts[0]
        // 去除前导 + 号, toIntOrNull 不支持 "+08" 格式
        val h = hourStr.removePrefix("+").toIntOrNull()
            ?: throw IllegalArgumentException("invalid hour: $hourStr")
        // ZoneOffset.ofHoursMinutes 要求分钟与小时同号(或为0)
        // 用户输入 -12:05 实际表示 -12小时05分, 需转换为 (-12, -5)
        val mRaw = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val m = if (h < 0 && mRaw > 0) -mRaw else mRaw
        ZoneId.ofOffset("UTC", ZoneOffset.ofHoursMinutes(h, m))
    }.getOrElse { ZoneId.ofOffset("UTC", ZoneOffset.ofHours(8)) }
}
