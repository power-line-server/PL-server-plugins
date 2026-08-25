@file:Depends("coreLibrary/db", "数据库存储")

package wayzer.user

import coreLib.db.DBApi
import coreLib.db.DBApi.WithUpgrade
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

name = "玩家数字UID(短ID)"

// 数字 uid 表: 按玩家首次入服顺序分配,从 1 开始递增
// 原版 PlayerInfo 不记录首次入服时间,此处自行记录
object PlayerUidTable : IdTable<Int>("PlayerUid"), WithUpgrade {
    override val id: Column<EntityID<Int>> = integer("uid").entityId()
    override val primaryKey = PrimaryKey(id)
    val uuid = varchar("uuid", 64).uniqueIndex()
    val firstJoin = timestamp("firstJoin")
    override val version = 1
}
DBApi.registerTable(PlayerUidTable)

// uidCache/uuidCache/Player.uid()/uidToUuid() 定义在 shortID.api.kt 中,供其他脚本跨脚本访问

/** 分配 uid(同步,若已分配则返回已有值) */
fun assignUid(uuid: String): Int {
    uidCache[uuid]?.let { return it }
    return transaction {
        // 再次检查(防止并发重复分配)
        PlayerUidTable.select(PlayerUidTable.id).where { PlayerUidTable.uuid eq uuid }
            .firstOrNull()?.let { it[PlayerUidTable.id].value }
            ?: run {
                val maxUid = PlayerUidTable.selectAll().maxOfOrNull { it[PlayerUidTable.id].value } ?: 0
                val newUid = maxUid + 1
                PlayerUidTable.insert {
                    it[PlayerUidTable.uuid] = uuid
                    it[PlayerUidTable.firstJoin] = Instant.now()
                    it[PlayerUidTable.id] = newUid
                }
                newUid
            }
    }.also {
        uidCache[uuid] = it
        uuidCache[it] = uuid
    }
}

// 数据库就绪后预加载全部缓存,并为在线玩家分配 uid
launch {
    DBApi.db.observe().collect { dbs ->
        val db = dbs.firstOrNull() ?: return@collect
        transaction(db) {
            PlayerUidTable.selectAll().forEach { row ->
                val uid = row[PlayerUidTable.id].value
                val u = row[PlayerUidTable.uuid]
                uidCache[u] = uid
                uuidCache[uid] = u
            }
        }
        Groups.player.forEach { p ->
            if (!uidCache.containsKey(p.uuid())) assignUid(p.uuid())
        }
    }
}

// 玩家加入时分配 uid
listen<EventType.PlayerJoin> {
    assignUid(it.player.uuid())
}

// shortID 改为返回数字 uid(按入服顺序分配,从 1 开始)
// 若缓存未命中则现场分配
fun Player.shortID(): String = (uid() ?: assignUid(uuid())).toString()

onEnable {
    PlayerData.IGetUidByShortId.provide(this, object : PlayerData.IGetUidByShortId {
        override fun getShortId(data: PlayerData): String =
            uidCache[data.uuid]?.toString() ?: data.uuid.takeLast(4)

        override fun getUidByShortId(id: String): String? =
            id.toIntOrNull()?.let { uidToUuid(it) }
                ?: Groups.player.find { it.uuid() == id }?.uuid()
    })
}

registerVarForType<Player>().apply {
    registerChild("shortID", "数字UID(按入服顺序)") { it.shortID() }
    registerChild("suffix.9shortID", "名字后缀:数字UID") { " [gray]${it.shortID()}[]" }
}
registerVarForType<Administration.PlayerInfo>().apply {
    registerChild("shortID", "数字UID(按入服顺序)") {
        uidCache[it.id]?.toString() ?: it.id.takeLast(4)
    }
}
