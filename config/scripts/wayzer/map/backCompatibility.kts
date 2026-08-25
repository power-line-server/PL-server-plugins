@file:Depends("wayzer/maps")

package wayzer.map

import mindustry.ctype.ContentType
import mindustry.type.UnitType
import wayzer.MapChangeEvent
import java.time.Instant
import java.util.*

listenTo<MapChangeEvent>(Event.Priority.Before) {
    val oldBanUnit = rules.tags["@banUnit"].orEmpty()
        .split(';')
        .filterNot { it.isEmpty() }
        .mapNotNull { content.getByName<UnitType>(ContentType.unit, it) }
    if (oldBanUnit.isNotEmpty()) rules.bannedUnits.addAll(*oldBanUnit.toTypedArray())
    val time = map.tag("saved")?.toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return@listenTo

    if (!rules.tags.containsKey("@banTeam") &&
        Calendar.getInstance().apply { set(2020, 3 - 1, 3) }.toInstant() < time &&//too old, may no pvp protect
        time < Calendar.getInstance().apply { set(2022, 3 - 1, 3) }.toInstant()
    ) {
        modifyRule { tags.put("@banTeam", "2") }
    }
}