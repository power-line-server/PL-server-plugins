@file:Depends("wayzer/user/mute")
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

object MuteTable : IntIdTable("PlayerMuteV2"), WithUpgrade {
    override val version = 1
    val ids = text("ids", eagerLoading = true)
    val targetName = text("targetName", eagerLoading = true).nullable()
    val reason = text("reason", eagerLoading = true)
    val operator = text("operator").nullable()
    val operatorName = text("operatorName").nullable()
    val ip = text("ip").nullable()
    val createTime = timestamp("createTime").defaultExpression(CurrentTimestamp)
    val endTime = timestamp("endTime").defaultExpression(CurrentTimestamp)
}
DBApi.registerTable(MuteTable)

object MuteServiceImpl : UnicastRemoteObject(), PlayerMuteStore {
    private fun readResolve(): Any = MuteServiceImpl
    private fun ResultRow.toMute() = PlayerMute(
        get(MuteTable.id).value,
        ids = get(MuteTable.ids).split("$").toSet(),
        targetName = get(MuteTable.targetName),
        reason = get(MuteTable.reason),
        operator = get(MuteTable.operator),
        operatorName = get(MuteTable.operatorName),
        ip = get(MuteTable.ip),
        createTime = get(MuteTable.createTime),
        endTime = get(MuteTable.endTime)
    )

    override fun getById(id: Int): PlayerMute? = transaction {
        MuteTable.selectAll().where { MuteTable.id eq id }.firstOrNull()?.toMute()
    }

    override fun create(
        ids: Set<String>, targetName: String, duration: Duration,
        reason: String, operator: String?, operatorName: String?, ip: String?
    ): PlayerMute =
        transaction {
            // 替换语义: 先删除同目标(ids 任一匹配或 ip 相同)的未过期记录, 防止重复禁言累积
            if (ids.isNotEmpty()) {
                val idCond = ids.map { v -> Op.build { MuteTable.ids like ("%$" + v + "$%") } }.reduce { a, b -> a or b }
                val del = Op.build { idCond and MuteTable.endTime.greater(CurrentTimestamp) }
                MuteTable.deleteWhere { del }
            }
            if (ip != null) {
                val del = Op.build { (MuteTable.ip eq ip) and MuteTable.endTime.greater(CurrentTimestamp) }
                MuteTable.deleteWhere { del }
            }
            MuteTable.insertAndGetId {
                it[MuteTable.ids] = ids.joinToString("$", "$", "$")
                it[MuteTable.targetName] = targetName
                it[MuteTable.endTime] = Instant.now() + duration
                it[MuteTable.operator] = operator
                it[MuteTable.operatorName] = operatorName
                it[MuteTable.ip] = ip
                it[MuteTable.reason] = reason
            }.let {
                getById(it.value)!!
            }
        }

    override fun findNotEnd(id: String): PlayerMute? = transaction {
        MuteTable.selectAll().where { (MuteTable.ids like "%$${id}$%") and MuteTable.endTime.greater(CurrentTimestamp) }
            .firstOrNull()?.toMute()
    }

    override fun findByIp(ip: String): PlayerMute? = transaction {
        MuteTable.selectAll().where { (MuteTable.ip eq ip) and MuteTable.endTime.greater(CurrentTimestamp) }
            .firstOrNull()?.toMute()
    }

    override fun delete(record: Int): PlayerMute? = transaction {
        getById(record)?.also {
            MuteTable.deleteWhere { id eq record }
        }
    }

    override fun listAll(): List<PlayerMute> = transaction {
        MuteTable.selectAll().orderBy(MuteTable.endTime, SortOrder.DESC).map { it.toMute() }
    }
}

val rpcService by lazy { Services.get<coreLib.extApi.RpcService>().get() }

onEnable {
    rpcService.register<PlayerMuteStore> { MuteServiceImpl }
}
