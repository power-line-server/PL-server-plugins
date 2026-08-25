package mapScript.tags

import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.util.loop
import mindustry.Vars.state
import mindustry.Vars.world
import mindustry.content.Blocks
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Unit
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.defense.Wall
import mindustry.world.blocks.environment.Cliff
import mindustry.world.blocks.environment.Prop
import mindustry.world.blocks.environment.StaticWall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

name = "洪水(CreeperWorld)"

// ============================================================
// CreeperWorld 式粘稠流体引擎
//
// 流体模型(格子自动机, 粘稠液):
//  - 每格存 fluid 量(FloatArray 0~CAP), 泉源持续注入
//  - 每 tick 流体向 4 邻域中"总高度更低"的格流动: 流量 = 高度差 × 粘度系数
//  - 单格总流出 ≤ 自身流体的 MAX_OUT(默认35%), 保证驻留聚积(不脉冲/不流空)
//  - 守恒: 转出的量从自身扣, 只有泉源增加总量
//  - 粘度低 -> 流得慢、液面缓聚 -> Creeper World 粘稠感
//  - 蒸发可选(默认0=封闭空间灌满)
//
// 可视化: 12 档墙(按生命值升序), 档位滞回(升档需超边界+5/降档需低边界-5)防鬼畜
// 伤害: 建筑邻域 fluid>1 即每tick持续扣(线性+保底), 水驻留=持续掉血
//
// 性能/兼容:
//  - FloatArray + 活跃集(只遍历有流体的格) + 并行分段转出(段间写槽不重叠无竞态)
//  - 墙变更用原版 SetTileBlocksCallPacket 批量分片(≤2000位置/包), 稳态档位不变 0 发包
//  - 本地 tile.setBlock + build.add()(不依赖 X 特有包), disabling 换图安全
// ============================================================

registerMapTag("@CreeperWorld")

// ===== 地图标签配置 =====
val creeperMode by mapTag("@CreeperWorld")
val creeperTeamRaw by mapTag("@creeperTeam")
val creeperSourceRaw by mapTag("@creeperSource")

// ===== 配置(全部地图标签, 玩家在地图介绍里写) =====
//   [@CreeperWorld]              开启(值 false/off 停用)
//   [@creeperTeam=blue]          水源队(逗号/花括号/空格分隔)
//   [@creeperSource=core-nucleus] 泉源建筑(逗号分隔)
//   [@creeperEmit=20]             源头深度(0-200; 20=源头满级碳化物, 10=源头50%钍区, 4=源头20%钛区)
//   [@creeperViscosity=0.15]     粘度系数(0.01-0.9; 越小越粘稠流得越慢, CreeperWorld 感)
//   [@creeperEvaporation=0]      蒸发(每tick每格固定减, 0=封闭空间灌满; >0 拆泉眼后慢慢退水)
//   [@creeperTps=2]              每秒 tick 数(1-60, 服务器上限60)
//   [@creeperThreads=0]          并行线程数(0=自动=min(核数,4), 1=单线程)
//   [@creeperMaxTiles=0]         最大格子数(0=无限蔓延)
//   [@creeperTiers=12]           墙分档数(1-12)
//   [@creeperBuildDamage=10]     建筑满流体每tick伤害
//   [@creeperUnitDamage=5]       单位满流体每tick伤害
//   [@creeperMinWallDamage=0.5]  放墙所需最低伤害/格/tick(有墙处必有≥此伤害; 保底伤害为游戏内置固定值)
private val emitAmount get() = state.rules.tags.getFloat("@creeperEmit", 20f).coerceAtLeast(0f)
private val viscosity get() = state.rules.tags.getFloat("@creeperViscosity", 0.15f).coerceIn(0.01f, 0.9f)
private val evaporation get() = state.rules.tags.getFloat("@creeperEvaporation", 0f).coerceAtLeast(0f)
private val buildDamage get() = state.rules.tags.getFloat("@creeperBuildDamage", 10f).coerceAtLeast(0f)
private val unitDamage get() = state.rules.tags.getFloat("@creeperUnitDamage", 5f).coerceAtLeast(0f)
private val minWallDamage get() = state.rules.tags.getFloat("@creeperMinWallDamage", 0.5f).coerceAtLeast(0f)
private val maxTiles get() = state.rules.tags.getInt("@creeperMaxTiles", 0).coerceAtLeast(0)
private val wallTierCount get() = state.rules.tags.getInt("@creeperTiers", 12).coerceIn(1, 12)
private val ticksPerSecond get() = state.rules.tags.getInt("@creeperTps", 2).coerceIn(1, 60)
private val spreadThreads get() = state.rules.tags.getInt("@creeperThreads", 0).coerceIn(0, 16)

// ===== 防御墙(1x1, 按生命值升序) =====
// 来源: core/src/mindustry/content/Blocks.java 防御(Category.defense)一栏全部 1x1 建筑墙, health 基准:
// scrap 60 < copper 80 < door 100 < titanium 110 < plastanium 125 < beryllium 130
// < phase 150 < tungsten 180 < thorium 200 < surge 230 < reinforcedSurge 250 < carbide 270
private val defenseWalls = listOf(
    Blocks.scrapWall,
    Blocks.copperWall,
    Blocks.door,
    Blocks.titaniumWall,
    Blocks.plastaniumWall,
    Blocks.berylliumWall,
    Blocks.phaseWall,
    Blocks.tungstenWall,
    Blocks.thoriumWall,
    Blocks.surgeWall,
    Blocks.reinforcedSurgeWall,
    Blocks.carbideWall,
)

// ===== 流体网格 =====
private var fluid = FloatArray(0) // 每格流体量 0~CAP
private var tierMap = ByteArray(0) // 每格当前档位(-1=无墙), 滞回用
private var reachable = ByteArray(0) // 1=可驻水, 0=挡水
private var floorLiquid = ByteArray(0) // 1=液体地板(不能放墙但水可流过)
private var activeQueue = IntArray(0)
private var activeSize = 0
private var visitedMark = IntArray(0)
private var markGen = 0
// 并行转出表(每格4槽: 邻居key或-1, 对应delta)
private var outTarget = IntArray(0)
private var outDelta = FloatArray(0)
private val floodWalls = mutableSetOf<Int>() // 已放置洪水墙的 tile.array()
private val backupBlocks = mutableMapOf<Int, Block>() // 被覆盖的装饰块, 还原恢复
private val upgradingKeys = mutableSetOf<Int>() // 本 tick 升降档的 key, 降级监听忽略其 remove
@Volatile private var disabling = false
private val sources = mutableListOf<Building>()
private var creeperTeams: Set<Team> = setOf(Team.blue)
private var sourceBlocks: Set<Block> = emptySet()
private var frame = 0

// ===== 调试状态(终端命令 creeperDebug 控制) =====
@Volatile private var simPaused = false // 模拟暂停(独立于游戏暂停)
@Volatile private var simTpsOverride = 0 // 调试覆盖的 tps(0=用地图标签)
@Volatile private var stepRequested = 0 // 步进请求次数(>0 时执行指定次数后自动暂停)

private fun effectiveTps(): Int = if (simTpsOverride > 0) simTpsOverride else ticksPerSecond

private val CAP = 100f // 满流体(对应最高档碳化物)
private val MAX_OUT = 0.35f // 单格总流出 ≤ 自身35%(粘稠驻留, 不脉冲)
private val FLOW_THRESHOLD = 0.05f // 高度差小于此不流
private val MIN_KEEP = 0.01f
private val MIN_DAMAGE_SCALE = 0.15f // 保底伤害比例(游戏内置固定, 任何有流体处至少15%满额伤害) // 低于此视为干涸出队
private val TIER_HYSTERESIS = 5f // 档位滞回(升档需超边界+5, 降档需低边界-5)

// ===== 队伍/泉源解析 =====
private fun parseTeamList(raw: String): Set<Team> {
    val cleaned = raw.trim().removeSurrounding("{", "}").replace("\"", "").replace("'", "")
    if (cleaned.isBlank()) return setOf(Team.blue)
    return cleaned.split(',', ' ', '，', '、').mapNotNull { t ->
        when (t.trim().lowercase()) {
            "blue" -> Team.blue; "sharded" -> Team.sharded; "green" -> Team.green
            "crux" -> Team.crux; "malis" -> Team.malis; "purple" -> Team.malis
            "derelict" -> Team.derelict; "wave" -> state.rules.waveTeam
            else -> t.trim().toIntOrNull()?.let { Team.get(it) }
        }
    }.toSet().ifEmpty { setOf(Team.blue) }
}

private fun parseSourceBlocks(raw: String): Set<Block> {
    val cleaned = raw.trim().removeSurrounding("{", "}").replace("\"", "").replace("'", "")
    if (cleaned.isBlank()) return setOf(Blocks.coreNucleus)
    return cleaned.split(',', '，', '、').mapNotNull { name ->
        mindustry.Vars.content.getByName(mindustry.ctype.ContentType.block, name.trim()) as? Block
    }.toSet()
}

// ===== 掩码构建 =====
private fun computeReachable(key: Int): Boolean {
    val tile = world.tile(key % world.width(), key / world.width()) ?: return false
    val block = tile.block()
    // 环境墙/悬崖是挡水的地形障碍
    if (block is Cliff || block is StaticWall) return false
    // 玩家/敌人建造的建筑墙(Wall 类)挡水(像堤坝): 水不流过墙格
    // 但墙被水浸泡(邻域有流体)持续掉血 -> 挡水不免疫伤害
    if (block is Wall) {
        val build = tile.build
        if (build != null && build.team !in creeperTeams) return false
    }
    return true
}

private fun refreshMasks() {
    val w = world.width()
    for (i in reachable.indices) {
        val tile = world.tile(i % w, i / w) ?: continue
        reachable[i] = if (computeReachable(i)) 1 else 0
        floorLiquid[i] = if (tile.floor().isLiquid) 1 else 0
        tierMap[i] = -1
    }
}

private fun updateReachableTile(tile: Tile) {
    val k = tile.array()
    if (k < reachable.size) {
        reachable[k] = if (computeReachable(k)) 1 else 0
        floorLiquid[k] = if (tile.floor().isLiquid) 1 else 0
        tierMap[k] = -1 // 玩家建/拆墙后该格不再视为洪水墙, 档位重置
    }
}

/** 动态刷新活跃格 4 邻域的可达性掩码(game 线程, 每 tick):
 *  墙被洪水摧毁后 tile 变 air, 但 BlockDestroyEvent fire 时 tile 还是墙(掩码记 0 不更新)
 *  -> 水永远流不进被摧毁的墙格(用户: '摧毁后不会扩散到那里')
 *  每 tick 对 activeQueue 邻域重算, 墙毁/新建后掩码自动自愈 */
private fun refreshDynamicReachable() {
    if (disabling) return
    val w = world.width()
    val h = world.height()
    val n0 = activeSize
    for (i in 0 until n0) {
        val k = activeQueue[i]
        val x = k % w
        val y = k / w
        if (x + 1 < w) reachable[k + 1] = if (computeReachable(k + 1)) 1 else 0
        if (x - 1 >= 0) reachable[k - 1] = if (computeReachable(k - 1)) 1 else 0
        if (y + 1 < h) reachable[k + w] = if (computeReachable(k + w)) 1 else 0
        if (y - 1 >= 0) reachable[k - w] = if (computeReachable(k - w)) 1 else 0
    }
}

// ===== 主循环 =====
onEnable {
    if (creeperMode == "false" || creeperMode == "off")
        return@onEnable ScriptManager.disableScript(this, "@CreeperWorld=$creeperMode")

    creeperTeams = parseTeamList(creeperTeamRaw)
    sourceBlocks = parseSourceBlocks(creeperSourceRaw)

    val size = world.width() * world.height()
    fluid = FloatArray(size)
    tierMap = ByteArray(size) { -1 }
    reachable = ByteArray(size)
    floorLiquid = ByteArray(size)
    activeQueue = IntArray(size)
    visitedMark = IntArray(size)
    outTarget = IntArray(0)
    outDelta = FloatArray(0)
    activeSize = 0
    markGen = 0
    frame = 0
    floodWalls.clear()
    backupBlocks.clear()
    upgradingKeys.clear()
    disabling = false
    refreshMasks()

    val tickDelay = { (1000L / effectiveTps()).coerceAtLeast(16L) }
    loop(Dispatchers.Default) {
        delay(tickDelay())
        if (disabling) return@loop
        // 尊重游戏暂停(终端 pause on / 关卡暂停/结束): 模拟同步暂停
        if (!state.isPlaying || state.isPaused) return@loop
        // 调试暂停/步进: simPaused 时只允许 stepRequested 计数执行
        if (simPaused && stepRequested <= 0) return@loop
        if (stepRequested > 0) stepRequested--
        runCatching {
            withContext(Dispatchers.game) {
                if (frame++ % 4 == 0) collectSources()
                refreshDynamicReachable() // 动态刷新活跃格邻域可达性(墙摧毁/新建后掩码自愈)
            }
            flow() // Default 线程: 纯数组并行流体, 不碰世界
            withContext(Dispatchers.game) { applyChanges() }
        }.onFailure { logger.warning("[creeper] 主循环异常: ${it.stackTraceToString()}") }
    }
}

private fun collectSources() {
    if (disabling) return
    sources.clear()
    Groups.build.forEach {
        if (it.team in creeperTeams && it.block in sourceBlocks) sources.add(it)
    }
}

// ===== 粘稠流体流动(并行: 每段协程只读 fluid 算转出, 主协程统一写回; 守恒, 无竞态) =====
private suspend fun flow() {
    val width = world.width()
    val height = world.height()
    markGen++
    val visc = viscosity
    val evap = evaporation

    // 1. 泉源每 tick 钳到目标水位(CreeperWorld 泉眼: 恒定高水位, 水持续流向邻域)
    //    emit=20 -> 源头满100(碳化物), 10 -> 50, 4 -> 20
    //    (钳回是补满到目标, 配合 MAX_OUT 严格缩放 -> 源头恒满、水持续外流, 无脉冲)
    val sourceCap = CAP * (emitAmount / 20f).coerceIn(0f, 1f)
    val sourceKeys = HashSet<Int>()
    for (src in sources) {
        val srcKey = src.tile.array()
        if (srcKey < fluid.size) {
            fluid[srcKey] = sourceCap
            sourceKeys.add(srcKey)
            activate(srcKey)
        }
    }
    if (activeSize == 0) return

    // 2. 并行计算转出表: 每格向 4 邻域中"流体更低"的格流动
    //    流量 = 高度差 × 粘度系数(越小越粘稠); 不在此截断, 写回阶段按总流出上限等比缩放
    val cap = activeSize * 4
    if (outTarget.size < cap) {
        outTarget = IntArray(cap)
        outDelta = FloatArray(cap)
    }
    val cores = Runtime.getRuntime().availableProcessors()
    val threads = if (spreadThreads > 0) spreadThreads else cores.coerceIn(1, 4)
    val segSize = (activeSize + threads - 1) / threads
    coroutineScope {
        val jobs = ArrayList<Job>(threads)
        for (s in 0 until threads) {
            val from = s * segSize
            if (from >= activeSize) break
            val to = minOf(from + segSize, activeSize)
            jobs += launch(Dispatchers.Default) {
                for (i in from until to) {
                    val k = activeQueue[i]
                    val f = fluid[k]
                    val base = i * 4
                    outTarget[base] = -1; outTarget[base + 1] = -1; outTarget[base + 2] = -1; outTarget[base + 3] = -1
                    outDelta[base] = 0f; outDelta[base + 1] = 0f; outDelta[base + 2] = 0f; outDelta[base + 3] = 0f
                    if (f <= 0f) continue
                    val x = k % width
                    val y = k / width
                    var o = 0
                    // 右
                    if (x + 1 < width) {
                        val nk = k + 1
                        if (reachable[nk] == 1.toByte()) {
                            val diff = f - fluid[nk]
                            if (diff > FLOW_THRESHOLD) {
                                val d = diff * visc
                                if (d > 0f) { outTarget[base + o] = nk; outDelta[base + o] = d; o++ }
                            }
                        }
                    }
                    // 左
                    if (x - 1 >= 0) {
                        val nk = k - 1
                        if (reachable[nk] == 1.toByte()) {
                            val diff = f - fluid[nk]
                            if (diff > FLOW_THRESHOLD) {
                                val d = diff * visc
                                if (d > 0f) { outTarget[base + o] = nk; outDelta[base + o] = d; o++ }
                            }
                        }
                    }
                    // 下
                    if (y + 1 < height) {
                        val nk = k + width
                        if (reachable[nk] == 1.toByte()) {
                            val diff = f - fluid[nk]
                            if (diff > FLOW_THRESHOLD) {
                                val d = diff * visc
                                if (d > 0f) { outTarget[base + o] = nk; outDelta[base + o] = d; o++ }
                            }
                        }
                    }
                    // 上
                    if (y - 1 >= 0) {
                        val nk = k - width
                        if (reachable[nk] == 1.toByte()) {
                            val diff = f - fluid[nk]
                            if (diff > FLOW_THRESHOLD) {
                                val d = diff * visc
                                if (d > 0f) { outTarget[base + o] = nk; outDelta[base + o] = d; o++ }
                            }
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    // 3. 主协程统一写回(守恒, 严格缩放不超上限)
    val n0 = activeSize
    for (i in 0 until n0) {
        visitedMark[activeQueue[i]] = markGen // 防重复入队
    }
    for (i in 0 until n0) {
        val k = activeQueue[i]
        val base = i * 4
        val f = fluid[k]
        var idealTotal = 0f
        for (j in 0 until 4) {
            if (outTarget[base + j] >= 0) idealTotal += outDelta[base + j]
        }
        if (idealTotal <= 0f) continue
        // 总流出上限 = 自身35%(粘稠驻留), 超出等比缩放(不单方向倾斜, 不超上限)
        val outCap = f * MAX_OUT
        val scale = if (idealTotal > outCap) outCap / idealTotal else 1f
        var out = 0f
        for (j in 0 until 4) {
            val nk = outTarget[base + j]
            if (nk < 0) continue
            val d = outDelta[base + j] * scale
            out += d
            val nf = (fluid[nk] + d).coerceAtMost(CAP)
            fluid[nk] = nf
            if (nf > 0f) activate(nk)
        }
        fluid[k] = (f - out).coerceAtLeast(0f)
    }

    // 4. 蒸发(可选, 每格每tick固定减)
    if (evap > 0f) {
        for (i in 0 until activeSize) {
            val k = activeQueue[i]
            val f = fluid[k]
            if (f <= 0f) continue
            fluid[k] = (f - evap / ticksPerSecond).coerceAtLeast(0f)
        }
    }

    // 5. 清理干涸格出队
    var w = 0
    for (i in 0 until activeSize) {
        val k = activeQueue[i]
        if (fluid[k] > MIN_KEEP) activeQueue[w++] = k
    }
    activeSize = w

    // 6. 上限裁剪
    if (maxTiles > 0 && activeSize > maxTiles) trimLowest(activeSize - maxTiles)
}

private fun activate(key: Int) {
    if (visitedMark[key] == markGen) return
    visitedMark[key] = markGen
    if (activeSize < activeQueue.size) activeQueue[activeSize++] = key
}

private fun trimLowest(over: Int) {
    repeat(over.coerceAtMost(activeSize)) {
        var minIdx = 0
        for (i in 1 until activeSize) {
            if (fluid[activeQueue[i]] < fluid[activeQueue[minIdx]]) minIdx = i
        }
        fluid[activeQueue[minIdx]] = 0f
        activeQueue[minIdx] = activeQueue[--activeSize]
    }
}

// ===== 档位(生命值越大的墙级别越高), 带滞回防鬼畜 =====
private fun activeTiers(): List<Block> = defenseWalls.take(wallTierCount.coerceIn(1, defenseWalls.size))

private fun tierIndex(block: Block): Int = activeTiers().indexOf(block)

/** 计算目标档位(滞回): 升档需超上边界+滞回, 降档需低下边界-滞回 */
private fun tierForFluid(k: Int, f: Float): Int {
    val tiers = activeTiers()
    val cur = tierMap[k].toInt()
    val want = (f * tiers.size / CAP).toInt().coerceIn(0, tiers.size - 1)
    if (cur < 0) return want // 无墙直接按当前流体设档
    if (want > cur) {
        // 升档: 需超过边界 + 滞回
        val upBoundary = (cur + 1) * CAP / tiers.size + TIER_HYSTERESIS
        return if (f >= upBoundary) want else cur
    }
    if (want < cur) {
        // 降档: 需低于边界 - 滞回
        val downBoundary = cur * CAP / tiers.size - TIER_HYSTERESIS
        return if (f < downBoundary) want else cur
    }
    return cur
}

// ===== 应用世界修改(game 线程) =====
private fun applyChanges() {
    if (disabling) return
    val w = world.width()
    val team = creeperTeams.first()
    val tiers = activeTiers()
    val tierPos = Array(tiers.size) { IntArray(64) }
    val tierSizes = IntArray(tiers.size)
    var newWalls = 0

    // 单位位置索引: 放墙跳过有单位(非水源队)的格, 避免墙生成在单位脚下把单位挤爆
    // (单位在水里受 unitDamage 伤害, 而不是被墙压死)
    val unitTiles = HashMap<Int, Unit>()
    Groups.unit.forEach { u ->
        if (u.dead || u.team in creeperTeams || u.type.flying) return@forEach
        val uk = u.tileOn()?.array() ?: return@forEach
        unitTiles[uk] = u
    }

    for (i in 0 until activeSize) {
        val k = activeQueue[i]
        val f = fluid[k]
        // 放墙阈值由伤害决定: 该格伤害(线性+保底)必须 ≥ @creeperMinWallDamage
        // 否则最边缘'放墙却不掉血'(玩家调低伤害时尤其明显)
        if (f <= MIN_KEEP) continue
        val wallDmg = buildDamage * maxOf(f / CAP, MIN_DAMAGE_SCALE)
        if (wallDmg < minWallDamage) continue
        val tile = world.tile(k % w, k / w) ?: continue
        val wantTier = tierForFluid(k, f)
        val wantBlock = tiers[wantTier]
        val curBlock = tile.block()
        val curTier = tierMap[k].toInt()

        if (floorLiquid[k] == 0.toByte()) {
            // 硬跳过: 已有非水源队建筑的格(玩家墙/任何玩家建筑)绝不被洪水墙覆盖
            val existing = tile.build
            if (existing != null && existing.team !in creeperTeams) continue
            // 有单位站着的格不放墙(避免墙生成在单位脚下把单位挤爆)
            if (unitTiles.containsKey(k)) continue
            when {
                curTier == wantTier -> {} // 档位未变, 无需发包(稳态0发包)
                curBlock == Blocks.air -> {
                    addTier(tierPos, tierSizes, wantTier, tile.x.toInt(), tile.y.toInt())
                    floodWalls.add(k)
                    tierMap[k] = wantTier.toByte()
                    newWalls++
                }
                curBlock is Prop -> {
                    backupBlocks.getOrPut(k) { curBlock }
                    addTier(tierPos, tierSizes, wantTier, tile.x.toInt(), tile.y.toInt())
                    floodWalls.add(k)
                    tierMap[k] = wantTier.toByte()
                    newWalls++
                }
                curBlock is Wall && tile.build?.team in creeperTeams -> {
                    // 已有洪水墙, 升降档
                    upgradingKeys.add(k)
                    addTier(tierPos, tierSizes, wantTier, tile.x.toInt(), tile.y.toInt())
                    tierMap[k] = wantTier.toByte()
                    newWalls++
                }
            }
        }
    }

    // 批量广播墙变更(原版 SetTileBlocksCallPacket, 分片 ≤2000 位置/包, 防客户端读缓冲溢出)
    val MAX_POS_PER_PACKET = 2000
    for (ti in tiers.indices) {
        val n = tierSizes[ti]
        if (n <= 0) continue
        val pos = if (n == tierPos[ti].size) tierPos[ti] else tierPos[ti].copyOf(n)
        var offset = 0
        while (offset < n) {
            val len = minOf(MAX_POS_PER_PACKET, n - offset)
            val chunk = pos.copyOfRange(offset, offset + len)
            Call.setTileBlocks(tiers[ti], team, chunk)
            for (p in chunk) {
                world.tile(p)?.build?.add() // 建实体+注册, 使墙可被伤害
            }
            offset += len
        }
    }
    upgradingKeys.clear()

    // 伤害: 遍历所有有流体的格, 检查该格自身+4邻域的 tile.build
    // - 水格上的建筑(传送带等): 自身格命中(不依赖 Groups.build 注册)
    // - 挡水墙(墙格无水, 但邻域有水): 邻域格命中(墙挡水但被水浸泡即掉血)
    // 多格建筑(2x2+)多个覆盖格有水时只伤害一次(Set 去重实体)
    val damagedBuilds = HashSet<Building>()
    fun damageAt(x: Int, y: Int, f: Float) {
        if (x < 0 || y < 0 || x >= w || y >= world.height()) return
        val build = world.tile(x, y)?.build ?: return
        if (disabling || build.team in creeperTeams || build.dead || !build.isValid) return
        if (!damagedBuilds.add(build)) return // 本 tick 已伤害过该实体(多格去重)
        build.damage(buildDamage * maxOf(f / CAP, MIN_DAMAGE_SCALE))
    }
    for (i in 0 until activeSize) {
        val k = activeQueue[i]
        val f = fluid[k]
        if (f <= MIN_KEEP) continue
        val x = k % w
        val y = k / w
        damageAt(x, y, f) // 自身格(水格上的建筑)
        damageAt(x + 1, y, f) // 4邻域(挡水墙被水浸泡)
        damageAt(x - 1, y, f)
        damageAt(x, y + 1, f)
        damageAt(x, y - 1, f)
    }

    // 伤害单位
    Groups.unit.forEach { u ->
        if (u.team in creeperTeams || u.dead || u.type.flying) return@forEach
        val ukey = u.tileOn()?.array() ?: return@forEach
        val uf = if (ukey < fluid.size) fluid[ukey] else 0f
        if (uf > MIN_KEEP) u.damage(unitDamage * maxOf(uf / CAP, MIN_DAMAGE_SCALE))
    }
}

private fun addTier(tierPos: Array<IntArray>, tierSizes: IntArray, ti: Int, x: Int, y: Int) {
    if (tierSizes[ti] >= tierPos[ti].size) tierPos[ti] = tierPos[ti].copyOf(tierPos[ti].size * 2)
    tierPos[ti][tierSizes[ti]++] = (x shl 16) or (y and 0xFFFF)
}

// ===== 摧毁洪水墙 -> 降级 =====
listen<EventType.BlockDestroyEvent> { e ->
    if (disabling) return@listen
    val k = e.tile.array()
    // 泉源被摧毁: 源格流体立即清零(泉眼消失水柱塌陷, 不再驱动扩散)
    // (根因: 之前源格残留满水位继续外推, 拆泉源后水还在蔓延)
    if (k < fluid.size && e.tile.block() in sourceBlocks) {
        val build = e.tile.build
        if (build != null && build.team in creeperTeams) {
            fluid[k] = 0f
            sources.removeAll { it.tile.array() == k }
            return@listen
        }
    }
    if (k in upgradingKeys) return@listen
    if (k !in floodWalls) return@listen
    val block = e.tile.block()
    val idx = tierIndex(block)
    // 注意: 不用 setNet(会广播单发 SetTileCallPacket, 某些客户端状态会崩 'Invalid packet type')
    // 改本地 setBlock(建实体) + Call.setTileBlocks 批量广播(原版包, 安全)
    val team = creeperTeams.first()
    when {
        idx > 0 -> {
            val tiers = activeTiers()
            val lower = tiers[idx - 1]
            e.tile.setBlock(lower, team, 0)
            e.tile.build?.add()
            Call.setTileBlocks(lower, team, intArrayOf((e.tile.x.toInt() shl 16) or (e.tile.y.toInt() and 0xFFFF)))
            fluid[k] = idx * CAP / tiers.size
            tierMap[k] = (idx - 1).toByte()
        }
        else -> {
            val orig = backupBlocks.remove(k)
            val restore = orig ?: Blocks.air
            e.tile.setBlock(restore, team, 0)
            e.tile.build?.add()
            Call.setTileBlocks(restore, team, intArrayOf((e.tile.x.toInt() shl 16) or (e.tile.y.toInt() and 0xFFFF)))
            fluid[k] = 0f
            tierMap[k] = -1
            floodWalls.remove(k)
        }
    }
}

// ===== 玩家建墙/拆墙 -> 更新可达性 =====
listen<EventType.BlockBuildEndEvent> { e ->
    if (disabling) return@listen
    if (!e.breaking) {
        // 玩家建墙(挡水): 清空墙格流体(墙占位不留水), 否则建墙前格内残留的水会继续外流 = '水穿过墙'
        val k = e.tile.array()
        if (k < fluid.size) fluid[k] = 0f
    }
    updateReachableTile(e.tile)
}
listen<EventType.BlockDestroyEvent> { e ->
    updateReachableTile(e.tile)
}

// ===== 换图清理 =====
onDisable {
    disabling = true
    // 还原所有洪水墙: 不用 setNet(单发 SetTileCallPacket 有崩客户端风险),
    // 改本地 setBlock + 按恢复块分组批量广播(原版 SetTileBlocksCallPacket, 安全)
    val restoreMap = HashMap<Block, MutableList<Int>>()
    val team = if (creeperTeams.isNotEmpty()) creeperTeams.first() else Team.blue
    floodWalls.toList().forEach { k ->
        val tile = world.tile(k % world.width(), k / world.width())
        if (tile != null && tile.block() is Wall && tile.build?.team in creeperTeams) {
            val orig = backupBlocks.remove(k)
            val restore = orig ?: Blocks.air
            tile.setBlock(restore, team, 0)
            tile.build?.add()
            restoreMap.getOrPut(restore) { mutableListOf() }.add((tile.x.toInt() shl 16) or (tile.y.toInt() and 0xFFFF))
        }
    }
    restoreMap.forEach { (block, positions) ->
        positions.chunked(2000).forEach { chunk ->
            Call.setTileBlocks(block, team, chunk.toIntArray())
        }
    }
    floodWalls.clear()
    backupBlocks.clear()
    upgradingKeys.clear()
    fluid = FloatArray(0)
    tierMap = ByteArray(0)
    reachable = ByteArray(0)
    floorLiquid = ByteArray(0)
    activeQueue = IntArray(0)
    visitedMark = IntArray(0)
    outTarget = IntArray(0)
    outDelta = FloatArray(0)
    activeSize = 0
    sources.clear()
    disabling = false
}

// ===== 调试命令(终端/控制台): creeperDebug =====
// creeperDebug            显示当前模拟状态
// creeperDebug pause      暂停模拟(独立于游戏暂停)
// creeperDebug resume     恢复模拟
// creeperDebug step [N]   步进 N 帧(暂停状态下执行N帧后自动暂停, 默认1)
// creeperDebug tps N      覆盖模拟 tps(0=恢复地图标签值)
// creeperDebug fluid x y  打印某格流体值
command("creeperDebug", "洪水模拟调试(暂停/步进/tps覆盖/状态)") {
    body {
        val cmd = arg.firstOrNull()
        when (cmd) {
            null -> {
                var maxF = 0f
                for (i in 0 until activeSize) {
                    val f = fluid[activeQueue[i]]
                    if (f > maxF) maxF = f
                }
                reply(
                    ("[creeper] 帧=$frame 活跃=$activeSize 墙=${floodWalls.size} 最高流体=${maxF.toInt()}" +
                        " | 模拟${if (simPaused) "[red]暂停[]" else "[green]运行[]"} tps=${effectiveTps()} 源=${sources.size}").with()
                )
            }
            "pause" -> {
                simPaused = true
                stepRequested = 0
                reply("[creeper] 模拟已暂停(帧=$frame)".with())
            }
            "resume" -> {
                simPaused = false
                stepRequested = 0
                reply("[creeper] 模拟已恢复".with())
            }
            "step" -> {
                val n = arg.getOrNull(1)?.toIntOrNull() ?: 1
                simPaused = true
                stepRequested = n.coerceAtLeast(1)
                reply("[creeper] 步进 $n 帧后暂停".with())
            }
            "tps" -> {
                val n = arg.getOrNull(1)?.toIntOrNull() ?: 0
                simTpsOverride = n.coerceIn(0, 60)
                reply(("[creeper] tps=${effectiveTps()}" + if (simTpsOverride == 0) " (地图标签值)" else " (调试覆盖)").with())
            }
            "fluid" -> {
                val x = arg.getOrNull(1)?.toIntOrNull()
                val y = arg.getOrNull(2)?.toIntOrNull()
                if (x == null || y == null) {
                    reply("[creeper] 用法: creeperDebug fluid <x> <y>".with())
                } else {
                    val k = y * world.width() + x
                    val f = if (k in fluid.indices) fluid[k] else 0f
                    val dmg = buildDamage * maxOf(f / CAP, MIN_DAMAGE_SCALE)
                    val msg = "[creeper] ($x,$y) 流体=${"%.2f".format(f)} 伤害=${"%.2f".format(dmg)}/tick 档=${tierMap.getOrElse(k) { -1 }}"
                    reply(msg.with())
                    // 同时写文件供管道回读(终端 reply 走玩家聊天不可见)
                    runCatching {
                        java.nio.file.Files.writeString(java.nio.file.Paths.get("_creeper_debug.txt"), msg)
                    }
                }
            }
            else -> reply("[creeper] 未知子命令: $cmd (支持: pause/resume/step/tps/fluid)".with())
        }
    }
}
