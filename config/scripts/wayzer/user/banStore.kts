@file:Depends("wayzer/user/ban")
@file:Depends("coreLibrary/db", "数据库存储")

package wayzer.user

import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.RpcService
import coreLib.extApi.register
import coreLib.db.DBApi
import coreLib.db.DBApi.WithUpgrade
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.rmi.server.UnicastRemoteObject
import java.time.Duration
import java.time.Instant

object Table : IntIdTable("PlayerBanV2"), WithUpgrade {
    override val version = 3
    val ids = text("ids", eagerLoading = true)
    val targetName = text("targetName", eagerLoading = true).nullable()
    val reason = text("reason", eagerLoading = true)
    val operator = text("operator").nullable()
    val operatorName = text("operatorName").nullable()
    val ip = text("ip").nullable()
    val createTime = timestamp("createTime").defaultExpression(CurrentTimestamp)
    val endTime = timestamp("endTime").defaultExpression(CurrentTimestamp)
}
DBApi.registerTable(Table)

object ServiceImpl : UnicastRemoteObject(), PlayerBanStore {
    private fun readResolve(): Any = ServiceImpl
    private fun ResultRow.toBan() = PlayerBan(
        get(Table.id).value,
        ids = get(Table.ids).split("$").toSet(),
        targetName = get(Table.targetName),
        reason = get(Table.reason),
        operator = get(Table.operator),
        operatorName = get(Table.operatorName),
        ip = get(Table.ip),
        createTime = get(Table.createTime),
        endTime = get(Table.endTime)
    )

    override fun getById(id: Int): PlayerBan? = transaction {
        Table.selectAll().where { Table.id eq id }.firstOrNull()?.toBan()
    }

    override fun create(
        ids: Set<String>, targetName: String, duration: Duration,
        reason: String, operator: String?, operatorName: String?, ip: String?
    ): PlayerBan =
        transaction {
            // 替换语义: 先删除同目标(ids 任一匹配或 ip 相同)的未过期记录, 防止重复封禁累积
            if (ids.isNotEmpty()) {
                val idCond = ids.map { v -> Op.build { Table.ids like ("%$" + v + "$%") } }.reduce { a, b -> a or b }
                val del = Op.build { idCond and Table.endTime.greater(CurrentTimestamp) }
                Table.deleteWhere { del }
            }
            if (ip != null) {
                val del = Op.build { (Table.ip eq ip) and Table.endTime.greater(CurrentTimestamp) }
                Table.deleteWhere { del }
            }
            Table.insertAndGetId {
                it[Table.ids] = ids.joinToString("$", "$", "$")
                it[Table.targetName] = targetName
                it[Table.endTime] = Instant.now() + duration
                it[Table.operator] = operator
                it[Table.operatorName] = operatorName
                it[Table.ip] = ip
                it[Table.reason] = reason
            }.let {
                getById(it.value)!!
            }
        }

    override fun findNotEnd(id: String): PlayerBan? = transaction {
        Table.selectAll().where { (Table.ids like "%$${id}$%") and Table.endTime.greater(CurrentTimestamp) }
            .firstOrNull()?.toBan()
    }

    override fun findByIp(ip: String): PlayerBan? = transaction {
        Table.selectAll().where { (Table.ip eq ip) and Table.endTime.greater(CurrentTimestamp) }
            .firstOrNull()?.toBan()
    }

    override fun delete(record: Int): PlayerBan? = transaction {
        getById(record)?.also {
            Table.deleteWhere { id eq record }
        }
    }

    override fun listAll(): List<PlayerBan> = transaction {
        Table.selectAll().orderBy(Table.endTime, SortOrder.DESC).map { it.toBan() }
    }
}

val rpcService by lazy { Services.get<coreLib.extApi.RpcService>().get() }

onEnable {
    rpcService.register<PlayerBanStore> { ServiceImpl }
}
