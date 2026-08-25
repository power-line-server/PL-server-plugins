package wayzer.ext

import coreLib.db.DBApi.WithUpgrade
import coreLibrary.lib.parseTimeZone
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import wayzer.lib.PlayerData
import wayzer.user.timezone
import java.time.Instant
import java.time.format.DateTimeFormatter

// ===== H2 表定义 =====
object PlayerRecordTable : IdTable<String>("PlayerRecord"), WithUpgrade {
    override val id: Column<EntityID<String>> = varchar("uuid", 64).entityId()
    override val primaryKey = PrimaryKey(id)
    val uid = integer("uid").nullable()
    val joinCount = integer("joinCount").default(0)
    val totalOnlineSeconds = long("totalOnlineSeconds").default(0)
    val firstJoinTime = long("firstJoinTime")
    val lastLeaveTime = long("lastLeaveTime").nullable()
    val forcedObCount = integer("forcedObCount").default(0)
    override val version = 1
}

object PlayerNameHistoryTable : IdTable<String>("PlayerNameHistory"), WithUpgrade {
    override val id: Column<EntityID<String>> = varchar("id", 255).entityId()
    override val primaryKey = PrimaryKey(id)
    val uuid = varchar("uuid", 64).index()
    val name = text("name")
    val firstSeenTime = long("firstSeenTime")
    override val version = 1
}

object PlayerIpHistoryTable : IdTable<String>("PlayerIpHistory"), WithUpgrade {
    override val id: Column<EntityID<String>> = varchar("id", 255).entityId()
    override val primaryKey = PrimaryKey(id)
    val uuid = varchar("uuid", 64).index()
    val ip = varchar("ip", 64)
    val firstSeenTime = long("firstSeenTime")
    override val version = 1
}

// ===== 缓存 =====
val joinTimeCache = java.util.concurrent.ConcurrentHashMap<String, Long>() // uuid -> join epoch second

// ===== 外部表查询引用(不创建实际表,仅用于查询) =====
// banX 记录表(wayzer/user/banStore.kts 中定义)
private object BanQueryTable : Table("PlayerBanV2") {
    val ids = text("ids")
}
// 玩家UID表(wayzer/user/shortID.kts 中定义)
private object PlayerUidQuery : Table("PlayerUid") {
    val uid = integer("uid")
    val uuid = varchar("uuid", 64)
}

/** 查询 uuid 对应的数字 uid */
fun getUidByUuid(uuid: String): Int? = transaction {
    PlayerUidQuery.select(PlayerUidQuery.uid).where { PlayerUidQuery.uuid eq uuid }
        .firstOrNull()?.let { it[PlayerUidQuery.uid] }
}

/** 查询数字 uid 对应的 uuid */
fun getUuidByUid(uid: Int): String? = transaction {
    PlayerUidQuery.select(PlayerUidQuery.uuid).where { PlayerUidQuery.uid eq uid }
        .firstOrNull()?.let { it[PlayerUidQuery.uuid] }
}

// ===== 时间格式化 =====
val detailTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm:ss")

fun formatTimestamp(timestamp: Long, p: mindustry.gen.Player): String {
    val tz = PlayerData[p].timezone
    val zone = parseTimeZone(tz)
    return Instant.ofEpochSecond(timestamp).atZone(zone).format(detailTimeFormatter)
}

// ===== banX 次数统计 =====
fun countBans(uuid: String): Int = transaction {
    BanQueryTable.selectAll().where { BanQueryTable.ids like "%$${uuid}$%" }.count().toInt()
}

// ===== 数据查询辅助 =====
data class PlayerNameEntry(val name: String, val firstSeenTime: Long)
data class PlayerIpEntry(val ip: String, val firstSeenTime: Long)

fun getLastName(uuid: String): String? = transaction {
    PlayerNameHistoryTable.selectAll()
        .where { PlayerNameHistoryTable.uuid eq uuid }
        .orderBy(PlayerNameHistoryTable.firstSeenTime, SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.get(PlayerNameHistoryTable.name)
}

fun getNameHistory(uuid: String): List<PlayerNameEntry> = transaction {
    PlayerNameHistoryTable.selectAll()
        .where { PlayerNameHistoryTable.uuid eq uuid }
        .orderBy(PlayerNameHistoryTable.firstSeenTime, SortOrder.DESC)
        .map { PlayerNameEntry(it[PlayerNameHistoryTable.name], it[PlayerNameHistoryTable.firstSeenTime]) }
}

fun getIpHistory(uuid: String): List<PlayerIpEntry> = transaction {
    PlayerIpHistoryTable.selectAll()
        .where { PlayerIpHistoryTable.uuid eq uuid }
        .orderBy(PlayerIpHistoryTable.firstSeenTime, SortOrder.DESC)
        .map { PlayerIpEntry(it[PlayerIpHistoryTable.ip], it[PlayerIpHistoryTable.firstSeenTime]) }
}

data class PlayerRecordData(
    val uuid: String,
    val uid: Int?,
    val joinCount: Int,
    val totalOnlineSeconds: Long,
    val firstJoinTime: Long,
    val lastLeaveTime: Long?,
    val forcedObCount: Int
)

fun getRecord(uuid: String): PlayerRecordData? = transaction {
    PlayerRecordTable.selectAll().where { PlayerRecordTable.id eq uuid }.firstOrNull()?.let {
        PlayerRecordData(
            uuid = it[PlayerRecordTable.id].value,
            uid = it[PlayerRecordTable.uid],
            joinCount = it[PlayerRecordTable.joinCount],
            totalOnlineSeconds = it[PlayerRecordTable.totalOnlineSeconds],
            firstJoinTime = it[PlayerRecordTable.firstJoinTime],
            lastLeaveTime = it[PlayerRecordTable.lastLeaveTime],
            forcedObCount = it[PlayerRecordTable.forcedObCount]
        )
    }
}

/** 搜索玩家: 匹配名称(模糊)/uid(精确)/uuid(前缀) */
fun searchPlayers(query: String): List<String> = transaction {
    val uuids = mutableSetOf<String>()
    // 1. uid 精确匹配
    query.toIntOrNull()?.let { uid ->
        getUuidByUid(uid)?.let { uuids.add(it) }
    }
    // 2. uuid 前缀匹配
    if (query.length >= 4) {
        PlayerRecordTable.selectAll().where { PlayerRecordTable.id like "${query}%" }
            .map { it[PlayerRecordTable.id].value }
            .forEach { uuids.add(it) }
    }
    // 3. 名称模糊匹配
    PlayerNameHistoryTable.selectAll()
        .where { PlayerNameHistoryTable.name like "%${query}%" }
        .map { it[PlayerNameHistoryTable.uuid] }
        .forEach { uuids.add(it) }
    uuids.toList()
}
