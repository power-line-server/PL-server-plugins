@file:Depends("wayzer/user/lang", "PlayerData.timezone")
@file:Depends("coreLibrary/time", "parseTimeZone")
@file:Depends("coreMindustry/menu", "MenuV2菜单")

package wayzer.reGrief

import arc.graphics.Color
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.parseTimeZone
import coreMindustry.MenuV2
import coreMindustry.renderPaged
import mindustry.gen.Groups
import mindustry.type.Item
import mindustry.world.Block
import mindustry.world.blocks.logic.LogicBlock
import mindustry.world.blocks.storage.CoreBlock
import wayzer.lib.PlayerData
import wayzer.user.timezone
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

val timeFormatter = DateTimeFormatter.ofPattern("M.d-HH:mm:ss")

// 按查看者时区格式化时间戳(p为null时使用服务器默认时区 +08:00)
fun formatTimestamp(timestamp: Long, p: mindustry.gen.Player?): String {
    val tz = if (p != null) PlayerData[p].timezone else "+08:00"
    val zone = parseTimeZone(tz)
    return Instant.ofEpochSecond(timestamp).atZone(zone).format(timeFormatter)
}

@Suppress("MemberVisibilityCanBePrivate", "CanBeParameter")
sealed class Log(val uid: String?, val desc: PlaceHoldString) {
    val timestamp = Instant.now().epochSecond

    class Place(uid: String?, val type: Block) : Log(uid, "{tr history.log.place}".with("block" to type))
    class Break(uid: String?) : Log(uid, "{tr history.log.break}".with())
    class Config(uid: String?, val value: String) : Log(uid, "{tr history.log.config}".with("config" to value))
    class Deposit(uid: String?, val item: Item, val amount: Int) :
        Log(uid, "{tr history.log.deposit}".with("item" to item, "amount" to amount))

    class Destroy : Log(null, "{tr history.log.destroy}".with())
    class PickUp(uid: String?) : Log(uid, "{tr history.log.pickup}".with())
    class PickDown(uid: String?, val type: Block) : Log(uid, "{tr history.log.pickdown}".with("block" to type))

    // timeStr 由调用方通过 formatTimestamp(timestamp, p) 计算后传入,
    // 避免 Log 类直接引用脚本顶层函数 formatTimestamp 导致捕获脚本实例,
    // 否则嵌套子类(Place/Break 等)无法继承(Kotlin 脚本嵌套类限制).
    fun descLog(descPrefix: PlaceHoldString = "".with(), timeStr: String): PlaceHoldString {
        return if (uid == null) {
            "{tr history.log.unknownUnit}".with(
                "time" to timeStr, "descPrefix" to descPrefix, "desc" to desc
            )
        } else {
            val info = netServer.admins.getInfo(uid)
            "[red]{time}[]-[yellow]{info.name}[yellow]({info.shortID})[white]{descPrefix}{desc}"
                .with("time" to timeStr, "descPrefix" to descPrefix, "desc" to desc, "info" to info)
        }
    }
}

val historyLimit by config.key(1000, "单格最长日记记录")
private val logs = HashMap<Int, MutableList<Log>>()

//初始化
fun initData() {
    logs.clear()
}
onEnable {
    if (net.server())
        initData()
}
listen<EventType.WorldLoadEvent> {
    initData()
}

//记录
fun log(pos: Int, log: Log) {
    if (historyLimit <= 0) return
    val list = logs.getOrPut(pos) { mutableListOf() }
    while (list.size >= historyLimit)
        list.removeAt(0)
    list.add(log)
}
listen<EventType.BlockBuildEndEvent> {
    val player = it.unit?.player ?: return@listen
    if (it.breaking)
        log(it.tile.array(), Log.Break(player.uuid()))
    else
        log(it.tile.array(), Log.Place(player.uuid(), it.tile.block()))
}
listen<EventType.ConfigEvent> {
    val log = Log.Config(it.player?.uuid(), it.value?.toString() ?: "null")
    log(it.tile.tile.array(), log)
}
listen<EventType.DepositEvent> {
    log(it.tile.tile.array(), Log.Deposit(it.player?.uuid(), it.item, it.amount))
}
listen<EventType.BlockDestroyEvent> {
    if (it.tile == emptyTile) return@listen
    log(it.tile.array(), Log.Destroy())
}
listen<EventType.PickupEvent> {
    val build = it.build ?: return@listen
    //As the build has removed when pickup, use tileOn instead
    log(build.tileOn().array(), Log.PickUp(it.carrier.player?.uuid()))
}
listen<EventType.PayloadDropEvent> {
    val build = it.build ?: return@listen
    log(build.tile.array(), Log.PickDown(it.carrier.player?.uuid(), build.block))
}

fun Player.showLog(xf: Float, yf: Float) {
    val x = xf.toInt() / 8
    val y = yf.toInt() / 8
    if (x < 0 || x >= world.width()) return
    if (y < 0 || y >= world.height()) return
    val logs = logs[x + y * world.width()] ?: emptyList()
    if (logs.isEmpty()) Call.label(
        con,
        "{tr history.label.noRecord}".with("x" to x, "y" to y).toPlayer(this),
        3f, xf, yf
    )
    else {
        val list = logs.map { log ->
            val timeStr = formatTimestamp(log.timestamp, this)
            log.descLog(timeStr = timeStr)
        }
        Call.label(
            con,
            "{tr history.label.recordHeader}"
                .with("x" to x, "y" to y, "list" to list)
                .toPlayer(this),
            10f, xf, yf
        )
    }
}

//扫描所有包含 ucontrol build/deconstruct 的逻辑块,返回 tile 坐标列表 (x, y)
fun scanLogicUnits(): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()
    Groups.build.forEach { build ->
        if (build is LogicBlock.LogicBuild) {
            val code = build.code
            if (code.isEmpty()) return@forEach
            val hasMatch = code.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .any { line ->
                    line.contains("ucontrol build", ignoreCase = true) ||
                        line.contains("ucontrol deconstruct", ignoreCase = true)
                }
            if (hasMatch) {
                result.add(build.tileX() to build.tileY())
            }
        }
    }
    return result
}

//查询
val enabledPlayer = mutableSetOf<String>()
command("history", "{tr command.history.desc}".with()) {
    requirePermission("wayzer.ext.history")
    usage = "{tr usage.history}"
    aliases = listOf("历史")
    body {
        when (arg.getOrElse(0) { "" }) {
            "core" -> returnReply(
                "{tr history.reply.coreDamage}".with("list" to lastCoreLog.map { (log, prefix) ->
                    val timeStr = formatTimestamp(log.timestamp, player)
                    log.descLog(prefix, timeStr)
                })
            )
            "logicUnit", "逻辑块" -> {
                val logicUnits = scanLogicUnits()
                if (logicUnits.isEmpty()) {
                    returnReply("{tr history.reply.noLogicUnit}".with())
                }
                val p = player
                if (p == null) {
                    val listText = logicUnits.joinToString("\n") { "[${it.first}, ${it.second}]" }
                    returnReply(
                        "{tr history.reply.logicUnitList}".with(
                            "count" to logicUnits.size, "list" to listText
                        )
                    )
                }
                MenuV2(p) {
                    title = "{tr history.menu.logicUnit.title}".with("receiver" to p).toString()
                    msg = "{tr history.menu.logicUnit.msg}".with("receiver" to p).toString()
                    renderPaged(logicUnits, prePage = 9) { (x, y) ->
                        option(
                            "{tr history.menu.logicUnit.item}".with(
                                "receiver" to p, "x" to x, "y" to y
                            ).toString()
                        ) {
                            val tile = world.tile(x, y)
                            if (tile != null) {
                                Call.setCameraPosition(p.con, tile.worldx(), tile.worldy())
                            }
                        }
                    }
                }.send().awaitWithTimeout()
                return@body
            }
        }
        if (player == null) returnReply("{tr history.reply.consoleOnly}".with())
        if (player!!.uuid() in enabledPlayer) {
            enabledPlayer.remove(player!!.uuid())
            reply("{tr history.reply.disabled}".with())
        } else {
            enabledPlayer.add(player!!.uuid())
            reply("{tr history.reply.enabled}".with())
        }
    }
}

listen<EventType.TapEvent> {
    val p = it.player
    if (p.uuid() !in enabledPlayer) return@listen
    Call.effect(p.con, Fx.placeBlock, it.tile.worldx(), it.tile.worldy(), 0.5f, Color.green)
    p.showLog(it.tile.worldx(), it.tile.worldy())
}

// 自动保留破坏核心的可疑行为
var lastCoreLog = emptyList<Pair<Log, PlaceHoldString>>()
var lastTime = 0L
val dangerBlock = arrayOf(
    Blocks.thoriumReactor,
    Blocks.liquidTank, Blocks.liquidRouter, Blocks.bridgeConduit, Blocks.phaseConduit,
    Blocks.conduit, Blocks.platedConduit, Blocks.pulseConduit
)

listen<EventType.BlockDestroyEvent> { event ->
    if (event.tile.block() is CoreBlock) {
        if (System.currentTimeMillis() - lastTime > 5000) { //防止核心连环爆炸,仅记录第一个被炸核心
            val list = mutableListOf<Pair<Log, PlaceHoldString>>()
            for (x in event.tile.x.let { it - 10..it + 10 })
                for (y in event.tile.y.let { it - 10..it + 10 })
                    logs[x + y * world.width()]?.lastOrNull { it is Log.Place }?.let { log ->
                        if (log is Log.Place && log.type in dangerBlock)
                            list.add(log to "{tr history.log.nearCore}".with("dx" to x - event.tile.x, "dy" to y - event.tile.y))
                    }
            lastCoreLog = list
        }
        lastTime = System.currentTimeMillis()
    }
}

