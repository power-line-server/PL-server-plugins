package mapScript.tags

import coreLibrary.lib.CommandContext
import coreLibrary.lib.Commands
import mindustry.game.EventType.Trigger
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.type.Item
import mindustry.world.blocks.storage.CoreBlock
import org.intellij.lang.annotations.Language

registerMapTag("@autoExchange")
modeIntroduce(
    "{tr mapScript.autoExchange.title}".with(), "{tr mapScript.autoExchange.introduce}".with()
)

val cores = content?.run {
    blocks().filterIsInstance<CoreBlock>().map {
        """block.${it.name}.itemCapacity: ${it.itemCapacity * 10},"""
    }
}.orEmpty()
@Language("JSON5")
val patch = """
{
    "name": "CoreWar",
    ${cores.joinToString("\n")}
}
""".trimIndent()
mapPatches = listOf(patch)

val score = IntArray(Team.all.size)

// 原版 items.set 仅修改服务端,依赖 blockSnapshot 周期同步(约6秒),客户端核心物品显示会长时间不一致。
// 这里降频(约每60 tick/1秒)用 Call.setItem 主动同步,且仅同步发生变化的物品。
var syncCounter = 0
val SYNC_INTERVAL = 60
val lastSynced = Array(Team.all.size) { IntArray(content.items().size) }

onEnableForGame {
    score.fill(0)
    state.teams.getActive().forEach {
        score[it.team.id] = it.team.items().get(Items.copper)
    }
}
onDisable {
    score.fill(0)
}
val Item.score: Int
    get() = when (this) {
        Items.sand, Items.fissileMatter, Items.dormantCyst -> 0
        Items.titanium, Items.graphite, Items.silicon, Items.pyratite -> 2
        Items.thorium, Items.plastanium, Items.oxide, Items.blastCompound -> 3
        Items.tungsten, Items.carbide, Items.phaseFabric, Items.surgeAlloy -> 4
        else -> 1
    }

fun Int.toCount(item: Item): Int = if (item.score == 0) 0 else (this / item.score).coerceAtLeast(0)
fun Int.toScore(item: Item): Int = this * item.score
fun syncItems(team: Team) {
    val old = score[team.id]
    val items = team.data().core()?.items ?: return
    val delta = content.items().sumOf { item ->
        (items.get(item) - old.toCount(item)).toScore(item)
    }
    val new = old + delta
    score[team.id] = new
    content.items().forEach {
        if (it.score != 0)
            items.set(it, new.toCount(it))
    }
}

fun syncToClients() {
    state.teams.getActive().forEach { teamData ->
        val core = teamData.core() ?: return@forEach
        val items = core.items
        val teamLast = lastSynced[teamData.team.id]
        // 仅同步自上次同步后发生变化的物品,避免不必要的网络包
        val changed = content.items().filter { item ->
            items.get(item) != teamLast[item.id.toInt()]
        }
        if (changed.isEmpty()) return@forEach
        // 对队伍的每个核心广播变化物品,确保客户端核心物品显示与服务端一致
        teamData.cores.forEach { c ->
            changed.forEach { item ->
                Call.setItem(c, item, items.get(item))
            }
        }
        changed.forEach { teamLast[it.id.toInt()] = items.get(it) }
    }
}

listen(Trigger.update) {
    Team.all.forEach {
        if (it.data().noCores()) score[it.id] = 0
        else syncItems(it)
    }
    syncCounter++
    if (syncCounter >= SYNC_INTERVAL) {
        syncCounter = 0
        syncToClients()
    }
}

// 调试指令: 帮助菜单不可见,但保留可执行性
private object HiddenInHelpOnly : Commands.Hidden {
    override suspend fun CommandContext.visible(): Boolean = false
    override suspend fun CommandContext.handle() {} // 不拦截,允许 body 执行
}

command("debugScore", "{tr command.debugScore.desc}".with()) {
    attr(HiddenInHelpOnly)
    body {
        reply(score.toList().toString().asPlaceHoldString())
    }
}