@file:Depends("coreMindustry/menu", "调用菜单")
package mapScript

import arc.func.Func
import arc.graphics.Color
import arc.graphics.Colors
import arc.math.Angles
import arc.math.Mathf
import arc.math.Mathf.ceil
import arc.math.geom.Position
import arc.math.geom.Vec2
import arc.struct.ObjectIntMap
import arc.util.Time
import arc.util.Tmp
import coreLibrary.lib.config
import coreLibrary.lib.util.loop
import coreMindustry.lib.game
import coreMindustry.lib.listen
import coreMindustry.lib.listenPacket2Server
import coreMindustry.lib.nextTick
import coreMindustry.*
import mapScript.lib.modeIntroduce
import mindustry.Vars.*
import mindustry.ai.types.CommandAI
import mindustry.ai.types.MissileAI
import mindustry.content.*
import mindustry.core.World
import mindustry.entities.Units
import mindustry.entities.units.UnitController
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.game.Teams.TeamData
import mindustry.gen.*
import mindustry.type.Item
import mindustry.type.StatusEffect
import mindustry.type.UnitType
import mindustry.world.Tile
import mindustry.world.blocks.campaign.Accelerator
import mindustry.world.blocks.campaign.Accelerator.AcceleratorBuild
import mindustry.world.blocks.campaign.LaunchPad
import mindustry.world.blocks.campaign.LaunchPad.LaunchPadBuild
import mindustry.world.blocks.power.VariableReactor
import mindustry.world.blocks.power.VariableReactor.*
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import java.lang.Float.min
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random


/**@author xkldklp
 * https://mdt.wayzer.top/v2/map/14668/latest
 */
name = "Lord of War"
//low...?
//错啦 luck of war啦
//屎山战争 开堆！

modeIntroduce(
    "LordOfWar".with(), buildString {
    }.with()
)

val gameSeed by autoInit { Random.nextInt(0, 10000) }

class TechNode(
    private val child: MutableList<TechNode> = mutableListOf(),
    private var parent: TechNode? = null,
    private var activeTeams: MutableMap<Team, Boolean> = mutableMapOf(),
    val alwaysActive: Boolean = false,
    var tech: Player.() -> Pair<() -> Unit, String>,
    var level: Int = 1,
) {
    fun child(): MutableList<TechNode>? {
        return child.ifEmpty { null }
    }

    fun addChild(node: TechNode) {
        child.add(node)
        node.parent = this
    }

    fun addChild(nodes: List<TechNode>) {
        nodes.forEach {
            addChild(it)
        }
    }

    fun parent(): TechNode? {
        return parent
    }

    private fun setActive(team: Team) {
        activeTeams[team] = true
    }

    fun active(team: Team): Boolean {
        return activeTeams.getOrDefault(team, false) || alwaysActive
    }

    fun build(player: Player, core: CoreBuild, team: Team = player.team()): Pair<String, suspend () -> Unit> {
        return if (active(team)) "[cyan]已经研究\n${tech.invoke(player).second}" to {
            player.techMenu(core)
        } else tech.invoke(player).second to {
            if (team.techData().techPoint > 0) {
                team.techData().techPoint--
                tech.invoke(player).first.invoke()
                setActive(team)
                player.techMenu(core)
            } else player.techMenu(core)
        }
    }

    fun buildDesc(player: Player, core: CoreBuild, team: Team = player.team()): Pair<String, suspend () -> Unit> {
        return if (active(team)) "[cyan]已经研究\n[white]${tech.invoke(player).second}" to {
            player.techTreeMenu(core, this)
        } else tech.invoke(player).second to {
            player.techTreeMenu(core, this)
        }
    }

    fun buildName(player: Player): String {
        return tech.invoke(player).second
    }
}

val techLevelMap by autoInit { mutableMapOf<Int, MutableList<TechNode>>() }

fun MutableMap<Int, MutableList<TechNode>>.add(node: TechNode, level: Int = node.level) {
    if (get(level).isNullOrEmpty()) {
        put(level, mutableListOf(node))
    } else {
        get(level)!!.add(node)
    }
}

val techTree by autoInit {
    TechNode(
        tech = fun Player.(): Pair<() -> Unit, String> { return ({} to "[green]科技树") },
        alwaysActive = true
    )
}

val sunsetModeP by config.key(10, "落日计划启动概率")

val sunsetMode by autoInit { Random.nextFloat() <= sunsetModeP / 100f }

val T1UnitCost by autoInit { state.rules.tags.getInt("@T1UC", 8) }
val T1Units = arrayOf(
    UnitTypes.dagger,
    UnitTypes.nova,
    UnitTypes.merui,
    UnitTypes.elude,
    UnitTypes.stell
)
val T2UnitCost by autoInit { state.rules.tags.getInt("@T2UC", 32) }
val T2Units = arrayOf(
    UnitTypes.pulsar,
    UnitTypes.poly,
    UnitTypes.atrax,
    UnitTypes.avert,
    UnitTypes.locus
)
val T3UnitCost by autoInit { state.rules.tags.getInt("@T3UC", 128) }
val T3Units = arrayOf(
    UnitTypes.mace,
    UnitTypes.mega,
    UnitTypes.cleroi,
    UnitTypes.zenith,
    UnitTypes.precept
)
val T4UnitCost by autoInit { state.rules.tags.getInt("@T4UC", 512) }
val T4Units = arrayOf(
    UnitTypes.spiroct,
    UnitTypes.cyerce,
    UnitTypes.anthicus,
    UnitTypes.antumbra,
    UnitTypes.vanquish
)
val T5UnitCost by autoInit { state.rules.tags.getInt("@T5UC", 2048) }
val T5Units = arrayOf(
    UnitTypes.arkyid,
    UnitTypes.vela,
    UnitTypes.tecta,
    UnitTypes.sei,
    UnitTypes.scepter
)
val LordUnitCost by autoInit { state.rules.tags.getInt("@LUC", 65536) }
val LordUnits = arrayOf(
    UnitTypes.toxopid,
    UnitTypes.aegires,
    UnitTypes.collaris,
    UnitTypes.eclipse,
    UnitTypes.conquer,
    UnitTypes.disrupt
)

fun randomColor(): String {
    return "[${Colors.getColors().keys().toList().random()}]"
}

var TechDifficult = state.rules.tags.getFloat("@TechDifficult", 1f).coerceAtLeast(0f)

fun UnitType?.cost(): Int {
    return when (this) {
        in T1Units -> T1UnitCost
        in T2Units -> T2UnitCost
        in T3Units -> T3UnitCost
        in T4Units -> T4UnitCost
        in T5Units -> T5UnitCost
        in LordUnits -> LordUnitCost
        else -> 0
    }
}

fun UnitType?.levelUnits(): Array<UnitType>? {
    return when (this) {
        in T1Units -> T1Units
        in T2Units -> T2Units
        in T3Units -> T3Units
        in T4Units -> T4Units
        in T5Units -> T5Units
        in LordUnits -> LordUnits
        else -> null
    }
}

fun Int.levelUnits(): Array<UnitType>? {
    return when (this) {
        1 -> T1Units
        2 -> T2Units
        3 -> T3Units
        4 -> T4Units
        5 -> T5Units
        6 -> LordUnits
        else -> null
    }
}

fun UnitType?.level(): Int {
    return when (this) {
        in T1Units -> 1
        in T2Units -> 2
        in T3Units -> 3
        in T4Units -> 4
        in T5Units -> 5
        in LordUnits -> 6
        else -> 0
    }
}

fun Float.format(i: Int = 2): String {
    return "%.${i}f".format(this)
}

val teamCoins: ObjectIntMap<Team> by autoInit { ObjectIntMap() }//队伍银行的金钱
fun Team.coins(): Int {
    return teamCoins[this]
}

fun Team.removeCoin(amount: Int) {
    teamCoins.put(this, coins() - amount)
}

fun Team.addCoin(amount: Int) {
    teamCoins.put(this, coins() + amount)
}

fun Team.setCoin(amount: Int) {
    teamCoins.put(this, amount)
}

val playerInputing: MutableMap<String, Boolean> by autoInit { mutableMapOf() }
val playerLastSendText: MutableMap<String, String?> by autoInit { mutableMapOf() }

val playerTapping: MutableMap<String, Boolean> by autoInit { mutableMapOf() }
val playerLastTapTile: MutableMap<String, Pair<Tile, Int>> by autoInit { mutableMapOf() }

val playerCoins: ObjectIntMap<String> by autoInit { ObjectIntMap() }//玩家的金钱
fun Player.coins(): Int {
    return playerCoins[uuid()]
}

fun Player.removeCoin(amount: Int) {
    playerCoins.put(uuid(), coins() - amount)
    sendMessage("[red]失去 $amount 金币")
}

fun Player.addCoin(amount: Int, quiet: Boolean = false) {
    playerCoins.put(uuid(), coins() + amount)
    if (!quiet) sendMessage("[green]得到 $amount 金币")
}

fun Player.setCoin(amount: Int) {
    playerCoins.put(uuid(), amount)
    sendMessage("[green]金币被设置为 $amount")
}

val playerUnitCap: ObjectIntMap<String> by autoInit { ObjectIntMap() } //玩家的军团单位上限
fun Player.unitCap(): Int {
    return playerUnitCap[uuid()]
}

fun Player.unitCap(unitCap: Int) {
    playerUnitCap.put(uuid(), unitCap)
}

val unitOwner: MutableMap<mindustry.gen.Unit, String?> by autoInit { mutableMapOf() }//单位的领主
fun mindustry.gen.Unit.owner(): String? {
    return unitOwner.getOrDefault(this, null)
}

val playerUnit: MutableMap<String, UnitType?> by autoInit { mutableMapOf() }//玩家统领单位类型
fun Player.unitType(): UnitType? {
    return playerUnit[uuid()]
}

fun Player.unitType(unitType: UnitType) {
    playerUnit[uuid()] = unitType
}

val playerLastTeam: MutableMap<String, Team> by autoInit { mutableMapOf() }//玩家队伍
fun Player.lastTeam(): Team {
    return playerLastTeam.getOrDefault(uuid(), team())
}

fun Player.lastTeam(lastTeam: Team) {
    playerLastTeam[uuid()] = lastTeam
}

val playerLordUnitType: MutableMap<String, UnitType?> by autoInit { mutableMapOf() }//玩家领主级单位类型
fun Player.lordUnitType(): UnitType? {
    return playerLordUnitType[uuid()]
}

fun Player.lordUnitType(unitType: UnitType) {
    playerLordUnitType[uuid()] = unitType
}

val playerLordUnit: MutableMap<String, mindustry.gen.Unit?> by autoInit { mutableMapOf() }//玩家领主级单位
fun Player.lordUnit(): mindustry.gen.Unit? {
    return playerLordUnit[uuid()]
}

fun Player.lordUnit(unit: mindustry.gen.Unit?) {
    playerLordUnit[uuid()] = unit
}

val playerLordCooldown: ObjectIntMap<String> by autoInit { ObjectIntMap() }//玩家领主级单位召唤冷却
fun Player.checkLordCooldown(): Boolean {
    return playerLordCooldown.get(uuid()) <= Time.timeSinceMillis(startTime)
}

fun Player.setLordCooldown(time: Float) {
    playerLordCooldown.put(uuid(), (Time.timeSinceMillis(startTime) + time * 1000).toInt())
}

fun Player.achievement(name: String, exp: Int, b: Boolean = false) {
    // 成就系统为本服未移植功能, no-op 保留调用点
}

val cooldown: ObjectIntMap<String> by autoInit { ObjectIntMap() }//玩家收获城市资源冷却
val startTime by autoInit { Time.millis() }
fun Player.checkCooldown(): Boolean {
    return cooldown.get(uuid()) <= Time.timeSinceMillis(startTime)
}

fun Player.setCooldown(time: Float) {
    cooldown.put(uuid(), (Time.timeSinceMillis(startTime) + time * 1000).toInt())
}

data class PadData(
    var targetPad: LaunchPad.LaunchPadBuild? = null,
    var name: String = "",
    var showInfo: Boolean = false,
)

val padData by autoInit { mutableMapOf<LaunchPad.LaunchPadBuild, PadData?>() }
fun LaunchPad.LaunchPadBuild.padData(): PadData {
    if (padData[this] == null) {
        val data = PadData()
        padData[this] = data
    }
    return padData[this]!!
}

val launching by autoInit { mutableMapOf<VariableReactorBuild, Boolean?>() }
fun VariableReactorBuild.launching(): Boolean {
    return launching[this] ?: false
}

@Suppress("unused", "unused", "unused", "unused", "unused")
data class TeamTech(
    val team: Team,
    var exp: Float = 0f,
    var level: Int = 1,
    var techPoint: Int = 0,
    var unitCostMultiplier: Float = 1f,
    val bulletStatusEffect: MutableMap<StatusEffect, Float> = mutableMapOf(),
    var bulletStatusEffectProbability: Float = 0.05f,
    val unitStatusEffect: MutableMap<StatusEffect, Float> = mutableMapOf(),
    var unitStatusEffectProbability: Float = 0.25f,
    var lordUnitHealthMultiplier: Float = 1f,
    var lordUnitSpawnInvincibleTime: Float = 0f,
    var lordUnitExplosionMultiplier: Float = 1f,
    var lordUnitSpawnTimeMultiplier: Float = 1f,
    var lordUnitCostMultiplier: Float = 1f,
    var upgradeAndRollMultiplier: Float = 1f,
    var allCoinsAreUnits: Boolean = false,
    var betterBuyUnits: Boolean = false,
    var quicklySetCityType: Boolean = false,
    var autoSetCityType: Boolean = false,
    var falseVoid: Boolean = false,
    var void: Boolean = false,
    var voidAnnihilation: Boolean = false,
    var voidPotential: Boolean = false,
    var voidOutbreak: Boolean = false,
    var voidDeath: Boolean = false,
    var voidChaos: Boolean = false,
    var deadIsntDead: Long = -1,
    var deadIsntDeadProbability: Boolean = false,
    var knowOtherTeamInfo: Long = -1,
    var chooseUnitTypes: Boolean = false,
    var verdict: Boolean = false,
    var WLECheaper: Boolean = false,
    var cityUpgradeMultiplier: Float = 1f,
    var bankNoRemoveCoins: Boolean = false,
    var controlOtherPlayerUnits: Boolean = false,
    var chooseLordTypes: Boolean = false,
    var moreCityLords: Boolean = false,
    var fogKiller: Boolean = false,
    var teamRespawn: Boolean = false,
    var teamRespawnUsed: Boolean = false,
    var quickTech: Boolean = false,
    var alphaMoreCoins: Boolean = false,
    var deadBoom: Boolean = false,
    var maxHealthDamage: Boolean = false,
    var tpNoDeBuff: Boolean = false,
    var buffedLords: Boolean = false,
    val friendlyTeams: MutableList<Team> = mutableListOf(),
    var upgradeMS: Boolean = false,
    val megaStructures: MutableList<MegaStructure> = mutableListOf(),
    var megaStructureQueue: MutableList<MegaStructure> = mutableListOf(),
    var efficientSetOff: Boolean = false,
    var cycloneEngine: Boolean = false,
    var hedgeFund: Boolean = false,
    var infMS: Boolean = false
) {
    fun checkEffectBulletHit(float: Float = 1f): Boolean {
        return Random.nextFloat() <= bulletStatusEffectProbability * float
    }

    fun checkUnitEffectApply(float: Float = 1f): Boolean {
        return Random.nextFloat() <= unitStatusEffectProbability * float
    }

    fun techCost(): Int {
        if (team.cores().size == 0) return 1919810
        var cores = 0f
        state.teams.getActive().forEach {
            cores += it.cores.size
        }
        return (((level + 1) * 3.5f + 5f).pow(if (quickTech) 2.4f else 2.5f) * (team.cores().size / cores) * 9 * TechDifficult).toInt()
    }

    fun void(): Boolean {
        return void || falseVoid
    }
}

val teamTech by autoInit { mutableMapOf<Team, TeamTech?>() }
fun Team.techData(): TeamTech {
    if (teamTech[this] == null) {
        val data = TeamTech(this)
        teamTech[this] = data
    }
    return teamTech[this]!!
}

class CityType(
    val name: String,
    val coinsMultiplier: Float = 1f,
    val healMultiplier: Float = 1f,
    val unitCostMultiplier: Float = 1f,
    val researchMultiplier: Float = 1f,
    val cost: Float = 1.2f,
)

class CityTypes(
    val default: CityType = CityType("[white]均衡城市", cost = 0.1f),
    val Economic: CityType = CityType("[yellow]经济城市", 1.3f, 0.5f, 1.15f, 1f, 1.2f),
    val Logistics: CityType = CityType("[acid]补给城市", 0.75f, 2f, 1.15f, 1f, 0.8f),
    val War: CityType = CityType("[crimson]军工城市", 0.5f, 1f, 0.95f, 1f, 1.8f),
    val WarEconomic: CityType = CityType("[goldenrod]军工经济城市", 1.3f, 0.25f, 0.95f, 1f, 2.9f),
    val LogisticsEconomic: CityType = CityType("[olive]补给经济城市", 1.3f, 2f, 1.25f, 1f, 2.1f),
    val WarLogistics: CityType = CityType("[tan]军工补给城市", 0.45f, 2f, 0.95f, 1f, 2.4f),
    val WarLogisticsEconomic: CityType = CityType("[cyan]全能城市", 1.5f, 3f, 0.9f, 1f, 5.2f),
    val ResearchI: CityType = CityType("[sky]科研城市", 0.75f, 0.75f, 1.15f, 3f, 0.4f),
    val ResearchII: CityType = CityType("[sky]科研中心", 0.5f, 0.5f, 1.25f, 8f, 1.2f),
    val ResearchIII: CityType = CityType("[sky]科研基地", 0.25f, 0.25f, 1.5f, 14f, 3.2f),
    val Respawn: CityType = CityType("[red]涅槃之城", 5f, 5f, 0.85f, 10f, 999f),
    val VoidSpawn: CityType = CityType("[navy]虚空传送门", 0f, 0f, 0.8f, 1f, 999f),
    val Sunset: CityType = CityType("[orange]落日余晖", 2.5f, 2f, 0.9f, 10f, 999f),
    val SunsetSuper: CityType = CityType("[orange]落日之光", 20f, 10f, 0.8f, 100f, 999f),
    val SunsetMoonI: CityType = CityType("[lightgray]无光之域", 1f, -2f, 1f, 1f, 999f),
    val SunsetMoonII: CityType = CityType("[lightgray]无光之域", -0.25f, 1f, 1f, 1f, 999f),
    val SunsetMoonIII: CityType = CityType("[lightgray]无光之域", 1f, 1f, 1.5f, 1f, 999f),
    val Void: CityType = CityType("[lightgray]虚寂之城", 0.2f, 0.2f, 1.2f, 1f, 999f),
) {
    fun toArray(): Array<Array<CityType>> {
        return arrayOf(
            arrayOf(default),
            arrayOf(Economic, Logistics, War),
            arrayOf(WarEconomic, LogisticsEconomic, WarLogistics),
            arrayOf(WarLogisticsEconomic),
            arrayOf(ResearchI, ResearchII, ResearchIII)
        )
    }

    fun all(): Array<CityType> {
        return arrayOf(
            default,
            Economic,
            Logistics,
            War,
            WarEconomic,
            LogisticsEconomic,
            WarLogistics,
            ResearchI,
            ResearchII,
            ResearchIII,
            Respawn,
            VoidSpawn,
            Sunset
        )
    }

    fun moon(): Array<CityType> {
        return arrayOf(
            SunsetMoonI,
            SunsetMoonII,
            SunsetMoonIII
        )
    }
}

val CityTypes by autoInit { CityTypes() }

data class CityData(
    var coins: Int = 0,
    var lord: MutableList<String> = mutableListOf(),
    var cityType: CityType = CityTypes().default,
    var population: Int = 0,
)

val cityData by autoInit { mutableMapOf<CoreBuild, CityData?>() }
fun CoreBuild.cityData(): CityData {
    if (cityData[this] == null) {
        val data = CityData()
        cityData[this] = data
    }
    return cityData[this]!!
}

fun CoreBuild.coins(): Int {
    return cityData().coins
}

fun CoreBuild.removeCoin(amount: Int) {
    cityData().coins -= amount
}

fun CoreBuild.addCoin(amount: Int) {
    cityData().coins += amount
}

fun CoreBuild.setCoin(amount: Int) {
    cityData().coins = amount
}

fun CoreBuild.lord(): List<String> {
    return cityData().lord
}

fun CoreBuild.lord(uuid: String) {
    if (!lord().contains(uuid)) cityData().lord.add(uuid)
}

class MegaStructure(
    val name: String,
    val desc: String,

    val requirements: MutableMap<Item, Int> = mutableMapOf(),

    val activeEffect: (Team.() -> Unit)? = null,
    val precondition: (Team.() -> Boolean)? = null,
) {
    fun cost(): Float {
        var cost = 0f
        requirements.forEach {
            cost += it.key.cost * it.value * 6
        }
        return cost
    }

    fun canBuild(team: Team): Boolean {
        return precondition?.invoke(team) ?: true && requirements.all { team.core().items.get(it.key) >= it.value }
    }

    fun status(team: Team): String {
        return "${
            if (this in team.techData().megaStructureQueue) "[yellow]" else (if (this in team.techData().megaStructures) "[cyan]" else if (this.canBuild(
                    team
                )
            ) "[green]" else "[red]")
        }■"
    }
}

class MegaStructures(
    val airDrop: MegaStructure = MegaStructure(
        "[cyan]轨道空降", "[green]发射台可以花10s传送至任意地点", mutableMapOf(
            Items.copper to 80000,
            Items.lead to 40000
        )
    ),
    val superComputer: MegaStructure = MegaStructure(
        "[cyan]星空算机", "[green]每秒提供大量科技经验\n建成后获得2点科技点", mutableMapOf(
            Items.copper to 40000,
            Items.lead to 80000
        ), fun Team.() {
            techData().techPoint += 2
            loop(Dispatchers.game) {
                techData().exp += techData().level * if (techData().quickTech) 60 else 40
                delay(1000L)
            }
        }),
    val satellite: MegaStructure = MegaStructure(
        "[cyan]间谍卫星",
        "[green]永久开启虚空全知超武\n敌方影之猎杀科技对你无效\n你的影之猎杀科技不需要迷雾即可触发",
        mutableMapOf(
            Items.copper to 60000,
            Items.lead to 60000
        ),
        fun Team.() {
            techData().knowOtherTeamInfo = Long.MAX_VALUE
        }),

    val withdrawal: MegaStructure = MegaStructure(
        "[purple]虚空抽取",
        "[green]为队伍银行提供相当于5个首都城市的收入",
        mutableMapOf(
            Items.copper to 80000,
            Items.lead to 80000
        ),
        fun Team.() {
            loop(Dispatchers.game) {
                addCoin(6 * max((Time.timeSinceMillis(startTime) / 1000 / 60 / 3).toInt(), 1) * 5)
                delay(1000L)
            }
        },
        fun Team.(): Boolean {
            return techData().void()
        }),
    val contract: MegaStructure = MegaStructure(
        "[purple]虚空契约",
        "[green]获得10点科技点\n[red]但清算终将到来",
        mutableMapOf(
            Items.copper to 80000,
            Items.lead to 80000
        ),
        fun Team.() {
            val team = this
            techData().techPoint += 10
            launch(Dispatchers.game) {
                delay(300_000L)
                data().players.filter { it.team() == team }.forEach {
                    it.removeCoin(it.coins())
                }
                data().cores.forEach {
                    it.removeCoin(it.coins())
                    it.cityData().cityType = CityTypes.Void
                }
                data().units.forEach {
                    if (Random.nextFloat() < 0.25f) Call.label("[navy]虚寂！", 3f, it.x, it.y)
                    Call.effect(Fx.reactorExplosion, it.x, it.y, 0f, color)
                    it.kill()
                }
                removeCoin(coins())
            }
        },
        fun Team.(): Boolean {
            return techData().void()
        }),

    val godStick: MegaStructure = MegaStructure(
        "[yellow]上帝权杖",
        "[green]每5秒对一个敌对玩家造成5000点伤害,受50倍护甲削减",
        mutableMapOf(
            Items.copper to 120000,
            Items.lead to 80000
        ),
        fun Team.() {
            val team = this
            loop(Dispatchers.game) {
                Groups.player.filter { it.team() != team && !it.dead() }.randomOrNull()?.apply {
                    unit().health -= (5000 - unit().armor * 50f).coerceAtLeast(1f)
                    sendMessage("[red]哦不..你被上帝权杖击中!")
                    Call.label(listOf("啪", "嘣", "叮", "当", "咚", "咔", "嘭", "砰").random(), 2f, x, y)
                }
                delay(5_000L)
            }
        },
        fun Team.(): Boolean {
            return techData().upgradeMS
        }),
    val nanoMachine: MegaStructure = MegaStructure(
        "[yellow]纳米重铸", "[green]永久开启回生超武", mutableMapOf(
            Items.copper to 60000,
            Items.lead to 60000
        ), fun Team.() {
            techData().deadIsntDead = Long.MAX_VALUE
        }, fun Team.(): Boolean {
            return techData().upgradeMS
        }),
) {
    fun all(): List<MegaStructure> {
        return listOf(
            airDrop,
            superComputer,
            satellite,
            withdrawal,
            contract,
            godStick,
            nanoMachine
        )
    }
}

val MegaStructures by autoInit { MegaStructures() }

fun CoreBuild.level(): Int {
    return when (block) {
        Blocks.coreShard -> 1
        Blocks.coreFoundation -> 2
        Blocks.coreBastion -> 3
        Blocks.coreNucleus -> 4
        Blocks.coreCitadel -> 5
        Blocks.coreAcropolis -> 6
        else -> 0
    }
}

fun CoreBuild.levelText(noColor: Boolean = false): String {
    return "[white]${if (!noColor && coins() >= maxHealth * team().techData().cityUpgradeMultiplier && block != Blocks.coreAcropolis) "${Iconc.up}[cyan]" else "${if (!noColor) Iconc.upload else ""}"}${
        when (level()) {
            1 -> "村庄"
            2 -> "乡镇"
            3 -> "庄园"
            4 -> "城市"
            5 -> "大城市"
            6 -> "首都"
            else -> ""
        }
    }[white]"
}

fun Int.levelText(): Char {
    return when {
        this >= 4096 -> Iconc.blockItemSource
        this >= 2048 -> Iconc.blockFluxReactor
        this >= 1024 -> Iconc.blockNeoplasiaReactor
        this >= 512 -> Iconc.blockImpactReactor
        this >= 256 -> Iconc.blockThoriumReactor
        this >= 128 -> Iconc.blockEruptionDrill
        this >= 64 -> Iconc.blockImpactDrill
        this >= 48 -> Iconc.blockBlastDrill
        this >= 32 -> Iconc.blockLaserDrill
        this >= 12 -> Iconc.blockPneumaticDrill
        else -> Iconc.blockMechanicalDrill
    }
}

fun Player.createUnit(core: Posc, unitType: UnitType? = unitType(), team: Team = team()): Boolean {
    if (unitType == null || Groups.unit.filter { u -> u.owner() == uuid() }.size >= unitCap()) return false
    var times = 0
    val spawnRadius = 5
    val unit = unitType.create(team)
    while (true) {
        Tmp.v1.rnd(spawnRadius.toFloat() * tilesize)

        val sx = core.x + Tmp.v1.x
        val sy = core.y + Tmp.v1.y

        if (unit.canPass(World.toTile(sx), World.toTile(sy))) {
            unit.set(sx, sy)
            unitOwner[unit] = uuid()
            break
        }

        if (++times > 20) {
            return false
        }
    }
    unit.apply {
        unitOwner[this] = uuid()
        if (team.techData().unitStatusEffect.isNotEmpty()) {
            var float = 1f
            val leftStatusEffects = team.techData().unitStatusEffect.keys.toMutableList()
            fun check() {
                if (team.techData().checkUnitEffectApply(float)) {
                    float *= 0.5f
                    val statusEffect = leftStatusEffects.random()
                    leftStatusEffects.remove(statusEffect)
                    unit.apply(statusEffect, Float.POSITIVE_INFINITY)
                    if (leftStatusEffects.isNotEmpty()) check()
                }
            }
            check()
        }
        add()
    }
    return true
}

fun Player.createLordUnit(core: CoreBuild, unitType: UnitType? = lordUnitType(), cost: Int): Boolean {
    if (unitType == null) return false
    var times = 0
    val spawnRadius = 5
    val unit = unitType.create(team())
    unit.apply {
        while (true) {
            Tmp.v1.rnd(spawnRadius.toFloat() * tilesize)

            val sx = core.x + Tmp.v1.x
            val sy = core.y + Tmp.v1.y

            if (canPass(World.toTile(sx), World.toTile(sy))) {
                set(sx, sy)
                break
            }

            if (++times > 20) {
                return false
            }
        }
        launch(Dispatchers.game) {
            unitOwner[unit] = uuid()
            lordUnit(unit)
            var spawnTime = Time.millis()
            while (Time.timeSinceMillis(spawnTime) / 1000f <= 120f * team.techData().lordUnitSpawnTimeMultiplier) {
                Call.label(
                    "${unit.type.emoji()}[#${team().color}]领主级单位降临[white]${unit.type.emoji()}${if (core.tile.build !is CoreBuild || core.tile.build.team != this@createLordUnit.team()) "  [red]被打断！[white]" else ""}\n$name [white]${
                        (120f * team.techData().lordUnitSpawnTimeMultiplier - Time.timeSinceMillis(
                            spawnTime
                        ) / 1000f).format()
                    }", 0.2026f, x, y
                )
                if (core.tile.build !is CoreBuild || core.tile.build.team != this@createLordUnit.team())
                    Call.effect(Fx.unitEnvKill, x, y, 0f, team().color)
                else {
                    Call.effect(Fx.spawnShockwave, x, y, 0f, team().color)
                    repeat((team.techData().lordUnitExplosionMultiplier * 1.5f).toInt()) {
                        Tmp.v1.rnd(Random.nextFloat() * 48f * 8f * team.techData().lordUnitExplosionMultiplier)
                        val sx = x + Tmp.v1.x
                        val sy = y + Tmp.v1.y
                        Call.effect(Fx.unitCapKill, sx, sy, 0f, team().color)
                    }
                }
                if (core.tile.build !is CoreBuild || core.tile.build.team != this@createLordUnit.team())
                    spawnTime += 1200
                if (120f * team.techData().lordUnitSpawnTimeMultiplier - Time.timeSinceMillis(spawnTime) / 1000f >= 120f * team.techData().lordUnitSpawnTimeMultiplier * 1.5f) {
                    sendMessage("[red]领主降临被强制打断！")
                    Call.announce(
                        con(),
                        "[red]领主降临被强制打断！\n[cyan]冷却时间已经返还\n[#${team().color}]${Iconc.blockCliff}${cost * 3 / 4}[cyan]已经返还"
                    )
                    achievement("[green][放个屁都能被打断]", 50)
                    setLordCooldown(0f)
                    addCoin(cost * 3 / 4)
                    lordUnit(null)
                    return@launch
                }
                delay(200)
            }
            apply(StatusEffects.boss, Float.MAX_VALUE)
            apply(StatusEffects.overdrive, Float.MAX_VALUE)
            apply(StatusEffects.overclock, Float.MAX_VALUE)
            apply(StatusEffects.shielded, Float.MAX_VALUE)
            if (unit.team().techData().cycloneEngine)
                apply(StatusEffects.fast, Float.MAX_VALUE)
            apply(StatusEffects.invincible, team.techData().lordUnitSpawnInvincibleTime * 60f)
            maxHealth *= team.techData().lordUnitHealthMultiplier
            health = maxHealth
            add()
            unit(unit)
            achievement("[green][放了个屁]", 50)
            Call.announce("$name [#${team().color}]领主级单位\n!${unit.type.emoji()}出征${unit.type.emoji()}!")
            Call.sendMessage("$name [#${team().color}]领主级单位${unit.type.emoji()}出征${unit.type.emoji()}!")
            Call.logicExplosion(
                team,
                x,
                y,
                40f * 8f * team.techData().lordUnitExplosionMultiplier,
                7500f * team.techData().lordUnitExplosionMultiplier,
                true,
                true,
                true,
                false
            )
            Call.soundAt(Sounds.wind3, x, y, 114514f, 0f)
            Call.effect(Fx.impactReactorExplosion, x, y, 0f, team().color)
            if (team.techData().buffedLords) {
                when (unit.type) {
                    UnitTypes.toxopid -> {
                        repeat(16) {
                            createUnit(unit, UnitTypes.arkyid)
                        }
                        Groups.unit.filter { it.team() == team && it.within(unit, 128 * 8f) }.forEach {
                            it.heal((it.maxHealth * 0.5f).coerceAtMost(3000f))
                        }
                    }

                    UnitTypes.eclipse -> {
                        repeat(8) {
                            createUnit(unit, UnitTypes.sei)
                        }
                        Groups.unit.filter { it.team() == team && it.within(unit, 64 * 8f) }.forEach {
                            it.shield += it.maxHealth * 0.25f
                        }
                    }

                    UnitTypes.conquer -> {
                        Groups.unit.filter { it.team() == team && it.within(unit, 48 * 8f) }.forEach {
                            it.apply(StatusEffects.shielded, 32 * 60f)
                        }
                    }

                    UnitTypes.collaris -> {
                        repeat(8) {
                            createUnit(unit, UnitTypes.tecta)
                        }
                        Groups.unit.filter { it.team() != team && it.within(unit, 128 * 8f) }.forEach {
                            it.apply(StatusEffects.slow, 12 * 60f)
                        }
                    }

                    UnitTypes.aegires -> {
                        repeat(12) {
                            createUnit(unit, UnitTypes.vela)
                        }
                        Groups.unit.filter { it.team() != team && it.within(unit, 128 * 8f) }.forEach {
                            it.apply(StatusEffects.electrified, 60 * 60f)
                        }
                    }

                    UnitTypes.disrupt -> {
                        repeat(12) {
                            createUnit(unit, UnitTypes.quell)
                        }
                        launch(Dispatchers.game) {
                            repeat(60) {
                                val spawnX = x
                                val spawnY = y
                                UnitTypes.disrupt.weapons[0].bullet.spawnUnit.create(team).apply {
                                    set(spawnX + Random.nextInt(-32, 32) * 8f, spawnY + Random.nextInt(-32, 32) * 8f)
                                    rotation(360f * Random.nextFloat())
                                    add()
                                }
                            }
                        }
                    }
                }
            }
            var damageStartTime: Long
            val bakHealth = maxHealth
            while (!dead) {
                if (!isPlayer) {
                    damageStartTime = Time.millis()
                    while (!isPlayer) {
                        apply(StatusEffects.unmoving, 4f * 60f)
                        apply(StatusEffects.disarmed, 4f * 60f)
                        unapply(StatusEffects.shielded)
                        maxHealth = bakHealth * (1f - (Time.timeSinceMillis(damageStartTime) / 1000f / 180f))
                        Call.label(
                            "[#${team().color}]未被操控的领主级单位\n${health.format(1)}/${maxHealth.format(1)}\n[red]距离直接死亡还剩${
                                (180f - (Time.timeSinceMillis(damageStartTime) / 1000f)).format()
                            }s", 0.2026f, x, y
                        )
                        clampHealth()
                        if (maxHealth <= 0) kill()
                        delay(200)
                        if (dead) break
                    }
                    maxHealth = bakHealth
                }
                delay(200)
            }
        }
    }
    return true
}

suspend fun Player.cityMenu(core: CoreBuild) {
    @Suppress("KotlinDeprecation")
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]城市页面\n[cyan]City-level[yellow]${core.level()}",
        """
            [cyan]当前城市拥有金币[#${team().color}]${Iconc.blockCliff}${core.coins()}
            [cyan]你当前拥有金币[#${team().color}]${Iconc.blockCliff}${coins()}
            [cyan]升级城市将会变为此城市领主！会自动收集一半的金币
            [red]核心机收取资源将减少！
        """.trimIndent()
    ) {
        if (checkCooldown()) {
            this += listOf("[green]收取资源" to {
                if (core.dead || !core.isValid || core.team() != team()) {
                    sendMessage("[red]查无此城")
                } else {
                    if (checkCooldown()) {
                        val amount: Int
                        if (!unit().spawnedByCore)
                            amount = (core.coins() * Random.nextDouble(
                                if (team().techData().alphaMoreCoins) 0.50 else 0.00,
                                1.00
                            )).toInt()
                        else
                            amount = (core.coins() * Random.nextDouble(
                                if (team().techData().alphaMoreCoins) 0.25 else 0.00,
                                if (team().techData().alphaMoreCoins) 0.50 else 0.25
                            )).toInt()
                        addCoin(amount)
                        core.removeCoin(amount)
                        setCooldown(
                            (amount / max(
                                Time.timeSinceMillis(startTime) / 1000 / 30,
                                1L
                            ) / core.level()).toFloat()
                        )
                        Call.transferItemEffect(Items.copper, core.x, core.y, unit())
                    }
                }
            })
        } else {
            this += listOf("[red]收取冷却！${cooldown[uuid()] / 1000 - Time.timeSinceMillis(startTime) / 1000}s Left!" to {
                cityMenu(core)
            })
        }
        if (core.level() < 6) {

            add(buildList {
                if (core.coins() >= core.maxHealth * team().techData().cityUpgradeMultiplier && core.block != Blocks.coreAcropolis) {
                    add("[cyan]可升级城市!\n[#${team().color}]${Iconc.blockCliff}${(core.maxHealth * team().techData().cityUpgradeMultiplier).toInt()}" to {
                        if (!core.isValid || core.team() != team()) {
                            sendMessage("[red]查无此城")
                        } else {
                            if (core.coins() >= (core.maxHealth * team().techData().cityUpgradeMultiplier) && core == (core.tile.build as CoreBuild)) {
                                val cost = (core.maxHealth * team().techData().cityUpgradeMultiplier).toInt()
                                val coins = core.coins()
                                val tile = core.tile
                                val lords = core.lord()
                                val target = when (core.block) {
                                    Blocks.coreShard -> Blocks.coreFoundation
                                    Blocks.coreFoundation -> Blocks.coreBastion
                                    Blocks.coreBastion -> Blocks.coreNucleus
                                    Blocks.coreNucleus -> Blocks.coreCitadel
                                    Blocks.coreCitadel -> Blocks.coreAcropolis
                                    else -> Blocks.coreShard
                                }
                                tile.setNet(target, core.team, 0)
                                (tile.build as CoreBuild).setCoin(coins - cost)
                                if (team().techData().moreCityLords) {
                                    (tile.build as CoreBuild).cityData().lord = lords.toMutableList()
                                    (tile.build as CoreBuild).lord(uuid())
                                } else
                                    (tile.build as CoreBuild).lord(uuid())
                                Groups.player.forEach {
                                    if (it.team() == team() || fogControl.isVisible(
                                            it.team(),
                                            tile.worldx(),
                                            tile.worldy()
                                        )
                                    )
                                        it.sendMessage(
                                            "[#${core.team.color}]位于[${World.toTile(core.x)},${World.toTile(core.y)}]的 [white]${core.block.emoji()} [#${core.team.color}]已经被[white] $name [#${core.team.color}]升级为 [white]${tile.build.block.emoji()}[white]${
                                                (tile.build as CoreBuild).levelText(
                                                    true
                                                )
                                            }"
                                        )
                                    else
                                        it.sendMessage(
                                            "[#${core.team.color}]位于[${
                                                World.toTile(core.x) + Random.nextInt(
                                                    -60,
                                                    60
                                                )
                                            },${
                                                World.toTile(core.y) + Random.nextInt(
                                                    -60,
                                                    60
                                                )
                                            }]附近的 ??? 已经被[white] $name [#${core.team.color}]升级为 ???"
                                        )
                                }
                            }
                        }
                    })
                } else {
                    this += listOf("[lightgray]城市金币不足以升级城市！\n${Iconc.blockCliff}${(core.maxHealth * team().techData().cityUpgradeMultiplier).toInt()}" to {
                        cityMenu(core)
                    })
                }
                if (team().techData().moreCityLords && uuid() !in core.cityData().lord && core.lord().isNotEmpty())
                    if (coins() >= core.maxHealth * team().techData().cityUpgradeMultiplier / 4f) {
                        add("[cyan]加入城池领主!\n[#${team().color}]${Iconc.blockCliff}${(core.maxHealth * team().techData().cityUpgradeMultiplier / 4f).toInt()}" to {
                            if (coins() >= (core.maxHealth * team().techData().cityUpgradeMultiplier / 4f) && core == (core.tile.build as CoreBuild)) {
                                core.lord(uuid())
                                removeCoin((core.maxHealth * team().techData().cityUpgradeMultiplier / 4f).toInt())
                                Groups.player.forEach {
                                    if (it.team() == team() || fogControl.isVisible(
                                            it.team(),
                                            core.x,
                                            core.y
                                        )
                                    )
                                        it.sendMessage(
                                            "$name [#${core.team.color}] 已经加入位于[${World.toTile(core.x)},${
                                                World.toTile(
                                                    core.y
                                                )
                                            }]的 [white]${core.block.emoji()} [#${core.team.color}]的领主"
                                        )
                                }
                            }
                        })
                    } else {
                        add("[lightgray]金币不足以成为领主!\n${Iconc.blockCliff}${(core.maxHealth * team().techData().cityUpgradeMultiplier / 4f).toInt()}" to {
                            cityMenu(core)
                        })
                    }
            })
        }
        this += listOf(
            "[green]城市" to { lordMenu(core) },
            "转型" to { cityTypeMenu(core) }
        )
        this += listOf(
            "军团" to { warMenu(core) },
            "[green]城市" to { cityMenu(core) },
            "银行" to { bankMenu(core) },
            "领主" to { lordMenu(core) }
        )
        this += listOf(
            "取消" to {}
        )
        if (admin && Groups.player.size() <= 8) {
            this += listOf(
                "[red]<ADMIN>自己加1000000金币" to { addCoin(1000000) },
                "[red]<ADMIN>城市加1000000金币" to { core.addCoin(1000000) },
                "[red]<ADMIN>随机领主" to {
                    if (lordUnitType() != null) lordUnitType(
                        lordUnitType().levelUnits()!!.random()
                    )
                }
            )
        }
    }
}

suspend fun Player.cityTypeMenu(core: CoreBuild) {
    @Suppress("KotlinDeprecation")
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]城市页面\n[cyan]City-level[yellow]${core.level()}",
        """
            [cyan]你当前拥有金币[#${team().color}]${Iconc.blockCliff}${coins()}
            [cyan]转型城市将改变此城市类型!
            [cyan]不同的城市类型将会提供不同的强力增强!
            [lightgray]当前城市类型加成：
            [lightgray]金钱增长倍率：${core.cityData().cityType.coinsMultiplier}
            [lightgray]单位治疗倍率：${core.cityData().cityType.healMultiplier}
            [lightgray]单位花费倍率：${(1 - (1 - core.cityData().cityType.unitCostMultiplier) * core.level() / 3).format()}
            [lightgray]科研倍率：${core.cityData().cityType.researchMultiplier}
        """.trimIndent()
    ) {
        if (core.cityData().cityType.name == "[white]均衡城市" && core.cityData().cityType != CityTypes.default) core.cityData().cityType =
            CityTypes.default
        fun cityType(type: CityType, costMultiplier: Float = 1f): Pair<String, suspend () -> Unit> {
            val finalCostMultiplier = if (team().techData().quicklySetCityType) costMultiplier / 2 else costMultiplier
            return if (core.cityData().cityType == type) "[lightgray]目前城市类型\n${type.name}" to suspend {
                cityTypeMenu(core)
            } else if (type.name == "[white]均衡城市" && core.cityData().cityType.name != "[white]均衡城市")
                "${type.name}\n" +
                        "[red]不允许转型至此类型！" to {
                    cityTypeMenu(core)
                }
            else if (coins() >= type.cost * core.maxHealth * finalCostMultiplier) "${type.name}\n" +
                    "[#${team().color}]${Iconc.blockCliff}${(type.cost * core.maxHealth * finalCostMultiplier).toInt()}" to suspend {
                if (coins() >= type.cost * core.maxHealth * finalCostMultiplier && core.isValid) {
                    removeCoin((type.cost * core.maxHealth * finalCostMultiplier).toInt())
                    val lastCoreType = core.cityData().cityType
                    core.cityData().cityType = type
                    Groups.player.forEach {
                        if (it.team() == team() || fogControl.isVisible(
                                it.team(),
                                core.tile.worldx(),
                                core.tile.worldy()
                            )
                        )
                            it.sendMessage(
                                "[#${core.team.color}]位于[${World.toTile(core.x)},${World.toTile(core.y)}]的 [white]${core.block.emoji()}${lastCoreType.name}[white] 已经被[white] $name [#${core.team.color}]转型为 [white]${core.block.emoji()}[white]${core.cityData().cityType.name}"
                            )
                        else
                            it.sendMessage(
                                "[#${core.team.color}]位于[${
                                    World.toTile(core.x) + Random.nextInt(
                                        -60,
                                        60
                                    )
                                },${
                                    World.toTile(core.y) + Random.nextInt(
                                        -60,
                                        60
                                    )
                                }]附近的 ??? 已经被[white] $name [#${core.team.color}]转型为 ???"
                            )
                    }
                    if (type == CityTypes.WarLogisticsEconomic)
                        achievement("[purple][全能！]", 200)
                } else {
                    sendMessage("[red]转型失败！")
                }
            } else "[lightgray]${type.name}\n[lightgray]${Iconc.blockCliff}${(type.cost * core.maxHealth * finalCostMultiplier).toInt()}" to suspend {
                cityTypeMenu(core)
            }
        }
        CityTypes.toArray().forEach { typesArray ->
            val list = mutableListOf<Pair<String, suspend () -> Unit>>()
            typesArray.forEach {
                if (core.cityData().cityType.name == "[white]均衡城市" && (it.name != "[cyan]军工补给经济城市" || team().techData().WLECheaper)) list.add(
                    cityType(it, 0.45f)
                ) else list.add(cityType(it))
            }
            add(list)
        }

        this += listOf(
            "城市" to { lordMenu(core) },
            "[green]转型" to { cityTypeMenu(core) }
        )
        this += listOf(
            "军团" to { warMenu(core) },
            "[green]城市" to { cityMenu(core) },
            "银行" to { bankMenu(core) },
            "领主" to { lordMenu(core) }
        )
        this += listOf(
            "取消" to {}
        )
    }
}

suspend fun Player.warMenu(core: CoreBuild) {
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]战争页面\n[cyan]City-level[yellow]${core.level()}",
        """
            [cyan]你当前拥有金币[#${team().color}]${Iconc.blockCliff}${coins()}
            [cyan]当前城市可充军人口${Iconc.players}${core.cityData().population}/${(core.level() + 1f).pow(3).toInt()}
            [lightgray]当前军团等级:${unitType().level()}
        """.trimIndent()
    ) {
        fun buyUnitCost(): Int {
            return (unitType().cost() * (team().techData().unitCostMultiplier - (1 - core.cityData().cityType.unitCostMultiplier) * core.level() / 3)).toInt()
        }
        if (unitType() == null) {
            this += listOf(
                "[red]你还没有军团！\n点击抽取你的军团单位！" to {
                    val randomUnit = T1Units.random()
                    unitType(randomUnit)
                    Call.announce(con, "[cyan]抽取新军团单位:[white]${randomUnit.emoji()}")
                    unitCap(16)
                    warMenu(core)
                }
            )
        } else {
            this += listOf(
                (if (coins() >= (unitType().cost() * 4 * team().techData().upgradeAndRollMultiplier).toInt()) "[red]军团不满意？\n" + "重新抽取军团单位！\n" + "[#${team().color}]${Iconc.blockCliff}${(unitType().cost() * 4 * team().techData().upgradeAndRollMultiplier).toInt()}"
                else "[lightgray]你需要${Iconc.blockCliff}${(unitType().cost() * 4 * team().techData().upgradeAndRollMultiplier).toInt()}来重新抽取军团单位") to {
                    if (coins() >= (unitType().cost() * 4 * team().techData().upgradeAndRollMultiplier).toInt()) {
                        if (team().techData().chooseUnitTypes) {
                            sendMenuBuilder<Unit>(
                                this@warMenu, 900_000, "[green]命意指定!",
                                """
                                        [cyan]选择你的目标兵种
                                    """.trimIndent()
                            ) {
                                unitType().levelUnits()!!.forEach {
                                    add(listOf(it.emoji() to {
                                        unitType(it)
                                        Call.announce(con, "[cyan]抽取新军团单位:[white]${it.emoji()}")
                                        removeCoin((unitType().cost() * 4 * team().techData().upgradeAndRollMultiplier).toInt())
                                    }))
                                }
                                add(listOf("[lightgray]但是我拒绝" to { }))
                            }
                            warMenu(core)
                        } else {
                            val lastUnit = unitType()
                            var randomUnit = unitType().levelUnits()!!.random()
                            var times = 0
                            while (times <= 10) {
                                if (lastUnit != randomUnit) break
                                randomUnit = unitType().levelUnits()!!.random()
                                times++
                            }
                            unitType(randomUnit)
                            Call.announce(con, "[cyan]抽取新军团单位:[white]${randomUnit.emoji()}")
                            removeCoin((unitType().cost() * 4 * team().techData().upgradeAndRollMultiplier).toInt())
                            warMenu(core)
                        }
                    } else {
                        sendMessage("[red]金钱不足！")
                    }
                },
                if (coins() >= (unitType().cost() * 35 * team().techData().upgradeAndRollMultiplier).toInt() && unitType().level() < 5)
                    "[cyan]军团可升级!\n[#${team().color}]${Iconc.blockCliff}${(unitType().cost() * 35 * team().techData().upgradeAndRollMultiplier).toInt()}" to {
                        if (coins() >= (unitType().cost() * 35 * team().techData().upgradeAndRollMultiplier).toInt() && unitType().level() < 5) {
                            removeCoin((unitType().cost() * 35 * team().techData().upgradeAndRollMultiplier).toInt())
                            val randomUnit = (unitType().level() + 1).levelUnits()!!.random()
                            unitType(randomUnit)
                            Call.announce(con, "[cyan]抽取新军团单位:[white]${randomUnit.emoji()}")
                            warMenu(core)
                        } else {
                            sendMessage("[red]金钱不足！")
                        }
                    } else if (unitType().level() < 5)
                    "[cyan]你需要金币[#${team().color}]${Iconc.blockCliff}${(unitType().cost() * 35 * team().techData().upgradeAndRollMultiplier).toInt()}[cyan]来升级军团等级" to {
                        warMenu(core)
                    } else
                    "[cyan]军团等级已满！" to {
                        warMenu(core)
                    }
            )
            add(buildList {
                add("${unitType()!!.emoji()}[#${team().color}]${Iconc.blockCliff}${(unitType().cost() * (team().techData().unitCostMultiplier - (1 - core.cityData().cityType.unitCostMultiplier) * core.level() / 3)).toInt()}${if (team().techData().allCoinsAreUnits) "(${buyUnitCost() / 2})" else ""}[white]${unitType()!!.emoji()}" to {
                    if (!core.isValid || core.team() != team()) {
                        sendMessage("[red]查无此城")
                    } else {
                        if (coins() >= buyUnitCost() - (if (team().techData().allCoinsAreUnits) buyUnitCost() / 2 else 0) && core.cityData().population > unitType().level() - 1) {
                            if (createUnit(core)) {
                                if (team().techData().allCoinsAreUnits) {
                                    removeCoin(buyUnitCost() / 2)
                                } else
                                    removeCoin(buyUnitCost())
                                core.cityData().population -= Random.nextInt(unitType().level() * 4)
                                    .coerceAtMost(core.cityData().population)
                                warMenu(core)
                            } else {
                                sendMessage("[red]金钱或城市人口不足！")
                            }
                        }
                    }
                })
                if (team().techData().betterBuyUnits) {
                    add("5x ${unitType()!!.emoji()}[#${team().color}]${Iconc.blockCliff}${(unitType().cost() * (team().techData().unitCostMultiplier - (1 - core.cityData().cityType.unitCostMultiplier) * core.level() / 3)).toInt() * 5}${if (team().techData().allCoinsAreUnits) "(${buyUnitCost() / 2})" else ""}[white]${unitType()!!.emoji()}" to {
                        if (!core.isValid || core.team() != team()) {
                            sendMessage("[red]查无此城")
                        } else {
                            var fail = false
                            repeat(5) {
                                if (coins() >= buyUnitCost() - (if (team().techData().allCoinsAreUnits) buyUnitCost() / 2 else 0) && core.cityData().population > unitType().level() - 1) {
                                    if (createUnit(core)) {
                                        if (team().techData().allCoinsAreUnits) {
                                            removeCoin(buyUnitCost() / 2)
                                        } else
                                            removeCoin(buyUnitCost())
                                        core.cityData().population -= Random.nextInt(unitType().level() * 4)
                                            .coerceAtMost(core.cityData().population)
                                    } else fail = true
                                } else fail = true
                            }
                            if (!fail) warMenu(core)
                        }
                    })
                }
            })
            if (unitType().level() == 5) {
                if (lordUnit()?.dead == false) {
                    if (lordUnit() != unit())
                        this += listOf(
                            "[cyan]领主级单位未死亡\n附身你的领主级单位！" to {
                                unit(lordUnit())
                            }
                        )
                } else {
                    if (lordUnitType() == null) {
                        this += listOf(
                            "[cyan]领主级单位解锁！\n点击抽取你的领主级单位！" to {
                                val randomUnit = LordUnits.random()
                                lordUnitType(randomUnit)
                                Call.announce(con, "[cyan]抽取领主级单位:[white]${randomUnit.emoji()}")
                                Call.sendMessage("[white]$name [#${team().color}]抽取了领主级单位${randomUnit.emoji()}!")
                                warMenu(core)
                            }
                        )
                    }
                    if (checkLordCooldown() && lordUnitType() != null) {
                        add(buildList {
                            add("[cyan]领主级单位冷却完毕！\n点击出征！\n${(lordUnitType().cost() * team().techData().lordUnitCostMultiplier).toInt()}${lordUnitType()!!.emoji()}" to {
                                if (!core.isValid || core.team() != team()) {
                                    sendMessage("[red]查无此城")
                                } else {
                                    if (coins() >= (lordUnitType().cost() * team().techData().lordUnitCostMultiplier).toInt()) {
                                        if (createLordUnit(
                                                core,
                                                cost = (lordUnitType().cost() * team().techData().lordUnitCostMultiplier).toInt()
                                            )
                                        ) {
                                            removeCoin((lordUnitType().cost() * team().techData().lordUnitCostMultiplier).toInt())
                                            Call.sendMessage("$name [#${team().color}]领主级单位${lordUnitType()!!.emoji()}准备出征!")
                                            val bakHealthMultiplier = team().techData().lordUnitHealthMultiplier
                                            val a1 =
                                                team().techData().lordUnitHealthMultiplier * (1f + Random.nextFloat() * 0.2f)
                                            val a2 =
                                                team().techData().lordUnitSpawnTimeMultiplier * (1f - (0.9f + Random.nextFloat() * 0.1f))
                                            val a3 = 0.1f + Random.nextFloat() * 0.2f
                                            val a4 = 5f + Random.nextFloat() * 5f
                                            val a5 = 0.1f + Random.nextFloat() * 0.1f
                                            team().techData().lordUnitHealthMultiplier = a1
                                            team().techData().lordUnitSpawnTimeMultiplier -= a2
                                            team().techData().lordUnitExplosionMultiplier += a3
                                            team().techData().lordUnitSpawnInvincibleTime += a4
                                            team().techData().lordUnitCostMultiplier += a5
                                            Call.sendMessage(buildString {
                                                appendLine("[#${team().color}]现在的领主增幅：")
                                                appendLine("[#${team().color}]领主生命倍率：[white]${(team().techData().lordUnitHealthMultiplier * 100f).format()}% [green](+${((a1 - bakHealthMultiplier) * 100f).format()}%)")
                                                appendLine("[lightgray]会导致不同步，但是血量确实加了")
                                                appendLine("[#${team().color}]领主降临时间倍率：[white]${(team().techData().lordUnitSpawnTimeMultiplier * 100f).format()}% [green](-${(a2 * 100f).format()}%)")
                                                appendLine("[#${team().color}]领主降临后爆炸倍率：[white]${(team().techData().lordUnitExplosionMultiplier * 100f).format()}% [green](+${(a3 * 100f).format()}%)")
                                                appendLine(
                                                    "[#${team().color}]领主降临后临时无敌时间：[white]${
                                                        (team().techData().lordUnitSpawnInvincibleTime).format(
                                                            1
                                                        )
                                                    }s [green](+${a4.format(1)}s)"
                                                )
                                                appendLine("[#${team().color}]领主降临花费倍率：[white]${(team().techData().lordUnitCostMultiplier * 100f).format()}% [red](+${(a5 * 100f).format()}%)")
                                            })
                                            setLordCooldown(840f * team().techData().lordUnitSpawnTimeMultiplier)
                                            lordUnitType(lordUnitType().levelUnits()!!.random())
                                            warMenu(core)
                                        } else {
                                            sendMessage("[red]生成失败！")
                                        }
                                    } else {
                                        sendMessage("[red]金钱不足！")
                                    }
                                }
                            })
                            if (team().techData().chooseLordTypes)
                                add("[cyan]自选领主类型" to {
                                    sendMenuBuilder<Unit>(
                                        this@warMenu, 900_000, "[green]出征号令!",
                                        """
                                        [cyan]选择你的目标领主
                                    """.trimIndent()
                                    ) {
                                        LordUnits.forEach {
                                            add(listOf(it.emoji() to {
                                                lordUnitType(it)
                                                Call.announce(con, "[cyan]指定领主级单位:[white]${it.emoji()}")
                                                Call.sendMessage("[white]$name [#${team().color}]指定了领主级单位${it.emoji()}!")
                                            }))
                                        }
                                        add(listOf("[lightgray]但是我拒绝" to { }))
                                    }
                                    warMenu(core)
                                })
                        })
                    }
                }
            }
        }

        this += listOf(
            "[green]军团" to { warMenu(core) },
            "城市" to { cityMenu(core) },
            "银行" to { bankMenu(core) },
            "领主" to { lordMenu(core) }
        )
        this += listOf(
            "取消" to {}
        )
    }
}

suspend fun Player.bankMenu(core: CoreBuild) {
    var cores = 0f
    state.teams.getActive().forEach {
        cores += it.cores.size
    }
    val rate = 0.001f * (0.5f - team().cores().size / cores) * 2f
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]银行页面\n[cyan]City-level[yellow]${core.level()}",
        """
            [cyan]你当前拥有金币[#${team().color}]${Iconc.blockCliff}${coins()}
            [cyan]银行当前拥有金币[#${team().color}]${Iconc.blockCliff}${team().coins()}
            [cyan]银行金币可供所有队友存取！
            [yellow]银行现在每秒利息：${if (team().techData().hedgeFund) abs(team().coins() * rate).toInt() * 5 else team().coins() * rate.toInt()}
            [lightgray]利率：{队伍金钱(${team().coins()}) * 基础利率(0.1%) * 变动利率[0.5 - 队伍核心数(${team().cores().size}) / 全场核心数(${cores.toInt()})] * 2(${((0.5f - team().cores().size / cores) * 2f * 100f).format()}%)}(${(rate * 100f).format()}%)
        """.trimIndent()
    ) {
        playerInputing[uuid()] = false
        this += listOf(
            "存金币[#${team().color}]${Iconc.blockCliff}" to {
                val playerLastText = playerLastSendText[uuid()]
                sendMessage("------------\n[yellow]请输入所存数量\n[white]------------")
                val startInputTime = Time.millis()
                var fail = true
                var coin = 0
                playerInputing[uuid()] = true
                while (Time.timeSinceMillis(startInputTime) / 1000 <= 15) {
                    if (playerInputing[uuid()] == false) break
                    if (playerLastText != playerLastSendText[uuid()]) {
                        val amount = playerLastSendText[uuid()]?.toIntOrNull()
                        if (amount == null || amount <= 0 || amount > coins()) break
                        team().addCoin(amount)
                        removeCoin(amount)
                        coin = amount
                        fail = false
                        break
                    }
                    delay(50L)
                }
                playerLastSendText[uuid()] = ""
                if (fail) {
                    sendMessage("存钱失败！")
                } else {
                    Call.sendMessage("$name [#${team().color}]往队伍银行存储${Iconc.blockCliff}$coin")
                }

            },
            "取金币[#${team().color}]${Iconc.blockCliff}" to {
                if (team().coins() > 0) {
                    val playerLastText = playerLastSendText[uuid()]
                    sendMessage("------------\n[yellow]请输入所取数量\n[white]------------")
                    val startInputTime = Time.millis()
                    var fail = true
                    var coin = 0
                    playerInputing[uuid()] = true
                    while (Time.timeSinceMillis(startInputTime) / 1000 <= 15) {
                        if (playerInputing[uuid()] == false) break
                        if (playerLastText != playerLastSendText[uuid()]) {
                            val amount = playerLastSendText[uuid()]?.toIntOrNull()
                            if (amount == null || amount > team().coins() || amount <= 0) break
                            team().removeCoin(amount)
                            addCoin(amount)
                            coin = amount
                            fail = false
                            break
                        }
                        delay(50L)
                    }
                    playerLastSendText[uuid()] = ""
                    if (fail) {
                        sendMessage("取钱失败！")
                    } else {
                        Call.sendMessage("$name [#${team().color}]往队伍银行取出${Iconc.blockCliff}$coin")
                    }
                } else {
                    sendMessage("[red]队伍银行没钱给你取！")
                }
            }
        )
        this += listOf(
            "[yellow]科伦坡富豪榜" to {
                sendMessage(buildString {
                    appendLine("[cyan]谁是最有钱的资本家?")
                    var target = Groups.player.maxByOrNull { it.coins() }!!
                    appendLine("[yellow]是坐拥[#${target.team().color}]${Iconc.blockCliff}${target.coins()}[yellow]的[white]${target.name}[yellow]哒!")
                    appendLine("[cyan]谁是你家里最有钱的资本家?")
                    target = Groups.player.filter { it.team() == team() }.maxByOrNull { it.coins() }!!
                    appendLine("[yellow]是坐拥[#${target.team().color}]${Iconc.blockCliff}${target.coins()}[yellow]的[white]${target.name}[yellow]哒!")
                    append("[cyan]看看你队伍里资本家们的财富和军团！")
                    Groups.player.filter { it.team() == team() }.sortedBy { it.coins() }.reversed().forEach {
                        append("\n[white]${it.name} ")
                        append("[#${it.team().color}]${Iconc.blockCliff}${it.coins()}")
                        if (it.unitType() != null)
                            append("[white]  ${it.unitType()!!.emoji()}")
                        if (it.lordUnitType() != null)
                            append(
                                "|${
                                    it.lordUnitType()!!.emoji()
                                } ${
                                    if (it.checkLordCooldown()) "[green]准备就绪" else "[red]${
                                        playerLordCooldown[it.uuid()] / 1000 - Time.timeSinceMillis(
                                            startTime
                                        ) / 1000
                                    }s"
                                }"
                            )
                    }
                })
            }
        )
        this += listOf(
            "军团" to { warMenu(core) },
            "城市" to { cityMenu(core) },
            "[green]银行" to { bankMenu(core) },
            "领主" to { lordMenu(core) }
        )
        this += listOf(
            "取消" to {}
        )
    }
}

suspend fun Player.lordMenu(core: CoreBuild) {
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]领主页面\n[cyan]City-level[yellow]${core.level()}",
        """
            [cyan]你当前拥有金币[#${team().color}]${Iconc.blockCliff}${coins()}
            [cyan]升级属性！
        """.trimIndent()
    ) {
        fun Float.getRulesCost(cost1: Int, cost2: Int = 1): Float {
            return (this.pow(cost2) * cost1).pow(2)
        }

        fun Int.getRulesCost(cost: Int): Int {
            return (this * cost).toFloat().pow(2).toInt()
        }

        if (unitType().level() > 0) {
            this += listOf(
                "单位上限\n${unitCap()}->${unitCap() + 4}\n[#${team().color}]${Iconc.blockCliff}${
                    unitCap().getRulesCost(
                        2
                    )
                }" to {
                    if (coins() >= unitCap().getRulesCost(2)) {
                        removeCoin(unitCap().getRulesCost(2))
                        unitCap(unitCap() + 4)
                        lordMenu(core)
                    } else {
                        sendMessage("[red]金钱不足！")
                    }
                }
            )
        }
        this += listOf(
            "建筑血量\n${team().rules().blockHealthMultiplier.format()}->${(team().rules().blockHealthMultiplier + 0.05f).format()}\n[#${team().color}]${Iconc.blockCliff}${
                team().rules().blockHealthMultiplier.getRulesCost(
                    40
                ).toInt()
            }" to {
                if (coins() >= team().rules().blockHealthMultiplier.getRulesCost(40)) {
                    removeCoin(team().rules().blockHealthMultiplier.getRulesCost(40).toInt())
                    team().rules().blockHealthMultiplier += 0.05f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]购买了建筑血量(${(team().rules().blockHealthMultiplier - 0.05f).format()} -> ${team().rules().blockHealthMultiplier.format()})")
                    lordMenu(core)
                } else {
                    sendMessage("[red]金钱不足！")
                }
            },
            "建筑攻击\n${team().rules().blockDamageMultiplier.format()}->${(team().rules().blockDamageMultiplier + 0.05f).format()}\n[#${team().color}]${Iconc.blockCliff}${
                team().rules().blockDamageMultiplier.getRulesCost(
                    40
                ).toInt()
            }" to {
                if (coins() >= team().rules().blockDamageMultiplier.getRulesCost(40)) {
                    removeCoin(team().rules().blockDamageMultiplier.getRulesCost(40).toInt())
                    team().rules().blockDamageMultiplier += 0.05f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]购买了建筑攻击(${(team().rules().blockDamageMultiplier - 0.05f).format()} -> ${team().rules().blockDamageMultiplier.format()})")
                    lordMenu(core)
                } else {
                    sendMessage("[red]金钱不足！")
                }
            }
        )
        this += listOf(
            "建筑速度\n${team().rules().buildSpeedMultiplier.format()}->${(team().rules().buildSpeedMultiplier + 0.05f).format()}\n[#${team().color}]${Iconc.blockCliff}${
                team().rules().buildSpeedMultiplier.getRulesCost(
                    25
                ).toInt()
            }" to {
                if (coins() >= team().rules().buildSpeedMultiplier.getRulesCost(25)) {
                    removeCoin(team().rules().buildSpeedMultiplier.getRulesCost(25).toInt())
                    team().rules().buildSpeedMultiplier += 0.05f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]购买了建筑速度(${(team().rules().buildSpeedMultiplier - 0.05f).format()} -> ${team().rules().buildSpeedMultiplier.format()})")
                    lordMenu(core)
                } else {
                    sendMessage("[red]金钱不足！")
                }
            },
            "单位攻击\n${team().rules().unitDamageMultiplier.format()}->${(team().rules().unitDamageMultiplier + 0.05f).format()}\n[#${team().color}]${Iconc.blockCliff}${
                team().rules().unitDamageMultiplier.getRulesCost(
                    45
                ).toInt()
            }" to {
                if (coins() >= team().rules().unitDamageMultiplier.getRulesCost(45)) {
                    removeCoin(team().rules().unitDamageMultiplier.getRulesCost(45).toInt())
                    team().rules().unitDamageMultiplier += 0.05f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]购买了单位攻击(${(team().rules().unitDamageMultiplier - 0.05f).format()} -> ${team().rules().unitDamageMultiplier.format()})")
                    lordMenu(core)
                } else {
                    sendMessage("[red]金钱不足！")
                }
            }
        )
        this += listOf(
            "单位花费\n${team().techData().unitCostMultiplier.format()}->${(team().techData().unitCostMultiplier - 0.05f).format()}\n[#${team().color}]${Iconc.blockCliff}${
                (2f - team().techData().unitCostMultiplier).getRulesCost(
                    200,
                    2
                ).format()
            }" to {
                if (coins() >= (2f - team().techData().unitCostMultiplier).getRulesCost(200, 2)) {
                    removeCoin((2f - team().techData().unitCostMultiplier).getRulesCost(200, 2).toInt())
                    team().techData().unitCostMultiplier -= 0.05f
                    Call.sendMessage("[white]$name [#${team().color}]购买了单位花费(${(team().techData().unitCostMultiplier + 0.05f).format()} -> ${team().techData().unitCostMultiplier.format()})")
                    lordMenu(core)
                } else {
                    sendMessage("[red]金钱不足！")
                }
            }
        )
        this += listOf(
            "[green]属性" to { lordMenu(core) },
            "科技" to { techMenu(core) }
        )
        this += listOf(
            "军团" to { warMenu(core) },
            "城市" to { cityMenu(core) },
            "银行" to { bankMenu(core) },
            "[green]领主" to { lordMenu(core) }
        )
        this += listOf(
            "取消" to {}
        )
    }
}

suspend fun Player.techMenu(core: CoreBuild) {
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]科技页面\n[cyan]City-level[yellow]${core.level()}",
        """
            [cyan]队伍当前拥有科技经验[sky]${Iconc.teamSharded}${team().techData().exp.format(1)}
            [cyan]研究科技！当前科技等级：[#${team().color}] ${team().techData().level} [cyan]科技点： [#${team().color}] ${team().techData().techPoint}
        """.trimIndent()
    ) {
        suspend fun textOrMenu(text: String) {
            if (con.mobile) {
                sendMenuBuilder(
                    this@techMenu, 900_000, "", text
                ) {
                    add(
                        listOf(
                        "退出" to {},
                        "返回" to { techMenu(core) }
                    ))
                }
            } else {
                sendMessage(text)
            }
        }
        this += listOf(
            if (team().techData().exp >= team().techData().techCost()) {
                "升级队伍科技等级\n[sky]${Iconc.teamSharded}${team().techData().techCost()}" to {
                    if (team().techData().exp >= team().techData().techCost()) {
                        team().techData().exp -= team().techData().techCost()
                        team().techData().level++
                        team().techData().techPoint++
                        Call.sendMessage("[white]$name [#${team().color}]获得了新的灵感！队伍科技等级(${team().techData().level - 1} -> ${team().techData().level})")
                        if (team().techData().level == 31)
                            achievement("[purple][科教兴国]", 200)
                        techMenu(core)
                    } else {
                        sendMessage("[red]金钱不足！")
                    }
                }

            } else {
                "[lightgray]需要${Iconc.teamSharded}${team().techData().techCost()}\n来升级队伍科技等级!" to {
                    techMenu(core)
                }
            })
        this += listOf(
            "[cyan]效果弹头 [white]${
                buildString {
                    team().techData().bulletStatusEffect.keys.forEach { append(it.emoji()) }
                    if (team().techData().bulletStatusEffect.keys.any { it != StatusEffects.none })
                        append("\n${(team().techData().bulletStatusEffectProbability * 100f).format()}%")
                }
            }" to {
                textOrMenu(buildString {
                    appendLine("[cyan]单位攻击有概率射出附带负面buff的子弹")
                    appendLine(
                        "[white]目前的效果弹头：${
                            buildString {
                                team().techData().bulletStatusEffect.keys.forEach {
                                    append(
                                        it.emoji()
                                    )
                                }
                            }
                        }"
                    )
                    append("[yellow]目前触发概率${(team().techData().bulletStatusEffectProbability * 100f).format()}%")
                })
            }
        )
        this += listOf(
            "[cyan]兵营效果 [white]${
                buildString {
                    team().techData().unitStatusEffect.keys.forEach { append(it.emoji()) }
                    if (team().techData().unitStatusEffect.keys.any { it != StatusEffects.none })
                        append("\n${(team().techData().unitStatusEffectProbability * 100f).format()}%")
                }
            }" to {
                textOrMenu(buildString {
                    appendLine("[cyan]招募单位有概率附带buff")
                    appendLine(
                        "[white]目前的兵营效果：${
                            buildString {
                                team().techData().unitStatusEffect.keys.forEach {
                                    append(
                                        it.emoji()
                                    )
                                }
                            }
                        }"
                    )
                    append("目前触发概率${(team().techData().unitStatusEffectProbability * 100f).format()}%")
                })
            }
        )
        this += listOf(
            "[cyan]领主增幅" to {
                textOrMenu(buildString {
                    appendLine("[cyan]领主增幅 每次领主降临自动加强")
                    appendLine("[white]目前的领主增幅：")
                    appendLine("[#${team().color}]领主生命倍率：[white]${(team().techData().lordUnitHealthMultiplier * 100f).format()}%")
                    appendLine("[lightgray]会导致不同步，但是血量确实加了")
                    appendLine("[#${team().color}]领主降临时间倍率：[white]${(team().techData().lordUnitSpawnTimeMultiplier * 100f).format()}%")
                    appendLine("[#${team().color}]领主降临后爆炸倍率：[white]${(team().techData().lordUnitExplosionMultiplier * 100f).format()}%")
                    appendLine(
                        "[#${team().color}]领主降临后临时无敌时间：[white]${
                            (team().techData().lordUnitSpawnInvincibleTime).format(
                                1
                            )
                        }s"
                    )
                    appendLine("[#${team().color}]领主降临花费倍率：[white]${(team().techData().lordUnitCostMultiplier * 100f).format()}%")
                })
            }
        )

        this += listOf(
            "[cyan]查看科技" to {
                textOrMenu(buildString {
                    appendLine("[cyan]特殊科技 拥有一些特殊效果")
                    appendLine("")

                    appendLine("[white]普通科技")
                    appendLine("[#${team().color}]单位升级与刷新花费倍率：[white]${(team().techData().upgradeAndRollMultiplier * 100f).format()}%")
                    appendLine("[#${team().color}]效果弹头概率：[white]${(team().techData().bulletStatusEffectProbability * 100f).format()}%")
                    appendLine("[lightgray]每次触发一个,中毒可传染效果(10%原来概率),每2个效果弹头可多触发一次")
                    appendLine("[#${team().color}]效果兵营概率：[white]${(team().techData().unitStatusEffectProbability * 100f).format()}%")
                    appendLine("[lightgray]每次触发一个,触发后有原概率/2再次触发")
                    appendLine("")

                    appendLine("[cyan]稀有科技")
                    appendLine("${if (team().techData().betterBuyUnits) "[green]激活！" else "[red]未激活"} 高效募兵-可一次至多招募5个单位")
                    appendLine("${if (StatusEffects.slow in team().techData().bulletStatusEffect) "[green]激活！" else "[red]未激活"} 效果弹头(缓慢)-效果弹头(${Iconc.statusSlow})")
                    appendLine("${if (team().techData().chooseUnitTypes) "[green]激活！" else "[red]未激活"} 命意指定-可指定抽取军团级单位类型")
                    appendLine("${if (team().techData().WLECheaper) "[green]激活！" else "[red]未激活"} 全能狂热-全能城转型打四五折")
                    appendLine("[#${team().color}]城市升级花费倍率：[white]${(team().techData().cityUpgradeMultiplier * 100f).format()}%")
                    appendLine("${if (team().techData().bankNoRemoveCoins) "[green]激活！" else "[red]未激活"} 零险银行-银行不再在你核心数超50%时扣钱")
                    appendLine("${if (team().techData().controlOtherPlayerUnits) "[green]激活！" else "[red]未激活"} 蜂群意识-可操控队友单位 但不提供任何buff")
                    appendLine("${if (team().techData().moreCityLords) "[green]激活！" else "[red]未激活"} 天下为公-一个城市可拥有多个领主")
                    appendLine("${if (team().techData().quickTech) "[green]激活！" else "[red]未激活"} 科教兴国-队伍科研等级提升需要的经验减少")
                    appendLine("${if (team().techData().alphaMoreCoins) "[green]激活！" else "[red]未激活"} 高量抽取-收集金币百分比变为核心机(25%-50%)其他(50%-100%)")
                    appendLine("${if (team().techData().falseVoid) "[green]激活！" else "[red]未激活"} 虚空使徒-你的队伍被虚空腐蚀..但虚空摇了摇头")
                    appendLine("${if (team().techData().efficientSetOff) "[green]激活！" else "[red]未激活"} 高效引爆-领主降临爆炸效果+50%")
                    appendLine("")

                    appendLine("[purple]史诗科技")
                    appendLine("${if (team().techData().quicklySetCityType) "[green]激活！" else "[red]未激活"} 快速转型-城市转型费用减半(最终乘算)")
                    appendLine("${if (StatusEffects.shielded in team().techData().unitStatusEffect) "[green]激活！" else "[red]未激活"} 效果兵营(保护)-效果兵营(${Iconc.statusShielded})")
                    appendLine("${if (team().techData().void) "[green]激活！" else "[red]未激活"} 遁入虚空-你的队伍虚空化,可在虚空侵蚀后获得强力虚空科技")
                    appendLine("${if (team().techData().verdict) "[green]激活！" else "[red]未激活"} 真实裁决-攻击附带10%真实伤害")
                    appendLine("${if (team().techData().chooseLordTypes) "[green]激活！" else "[red]未激活"} 出征号令-可选择领主类型,降临cd减半")
                    appendLine("${if (team().techData().teamRespawn) if (team().techData().teamRespawnUsed) "[yellow]已激活！" else "[green]激活！" else "[red]未激活"} 涅槃重生-战败后重生并大幅加强,金钱翻倍,队伍建筑暂时大幅加固")
                    appendLine("${if (team().techData().deadIsntDeadProbability) "[green]激活！" else "[red]未激活"} 英雄不朽-领主外单位死亡后有25%概率触发回生特效")
                    appendLine("${if (team().techData().deadBoom) "[green]激活！" else "[red]未激活"} 尸体炸弹-单位死亡延时造成最大生命10%伤害")
                    appendLine("${if (team().techData().maxHealthDamage) "[green]激活！" else "[red]未激活"} 虚空之噬-攻击50%概率削减单位50%伤害的血上限")
                    appendLine("${if (team().techData().buffedLords) "[green]激活！" else "[red]未激活"} 界限突破-领主拥有特殊降临特效")
                    appendLine("${if (team().techData().autoSetCityType) "[green]激活！" else "[red]未激活"} 自动转型-未转型的城市每秒0.1%概率自动转型")
                    appendLine("${if (team().techData().upgradeMS) "[green]激活！" else "[red]未激活"} 奇观大师-可建造高级轨道巨构")
                    appendLine("${if (team().techData().cycloneEngine) "[green]激活！" else "[red]未激活"} 气旋引擎-玩家控制单位获得加速,持续24秒")
                    appendLine("")

                    appendLine("[yellow]传说科技")
                    appendLine("${if (StatusEffects.invincible in team().techData().unitStatusEffect) "[green]激活！" else "[red]未激活"} 效果兵营(无敌)-效果兵营(${Iconc.statusInvincible})")
                    appendLine("${if (StatusEffects.disarmed in team().techData().bulletStatusEffect) "[green]激活！" else "[red]未激活"} 效果弹头(缴械)-效果弹头(${Iconc.statusDisarmed})")
                    appendLine("${if (team().techData().allCoinsAreUnits) "[green]激活！" else "[red]未激活"} 全民皆兵-减免购买单位费用的一半")
                    appendLine("${if (team().techData().fogKiller) "[green]激活！" else "[red]未激活"} 影之猎杀-迷雾内攻击造成额外100%真实伤害")
                    appendLine("${if (team().techData().bulletStatusEffectProbability > 2f && team().techData().unitStatusEffectProbability > 2f) "[green]激活！" else "[red]未激活"} 群星闪耀-效果弹头概率,效果兵营概率提升200%,单位血量提升100%")
                    appendLine("${if (team().techData().infMS) "[green]激活！" else "[red]未激活"} 奇观误国-可无限建造轨道巨构")
                    appendLine("${if (team().techData().hedgeFund) "[green]激活！" else "[red]未激活"} 对冲基金-银行利息变为原先绝对值的5倍")

                    appendLine("")
                    appendLine("[navy]虚空科技")
                    appendLine("[lightgray]拥抱虚空...")

                    appendLine("${if (team().techData().voidAnnihilation) "[green]激活！" else "[red]未激活"} 湮灭-你有0.2%概率减半你的敌人最大生命,你的敌人也有0.1%概率减半你")
                    appendLine("${if (team().techData().voidPotential) "[green]激活！" else "[red]未激活"} 潜能-你队伍所有城市转化为全能城,效果兵营添加中毒(${Iconc.statusCorroded})")
                    appendLine("${if (team().techData().voidDeath) "[green]激活！" else "[red]未激活"} 死亡-你队伍将自爆核心至仅剩一个,每自爆一个增加20%属性")
                    appendLine("${if (team().techData().voidChaos) "[green]激活！" else "[red]未激活"} 混乱-随机所有人的军团类型与领主类型,你队伍的所有核心类型")
                })
            }
        )
        suspend fun treeBuild(node: TechNode): List<Pair<String, suspend () -> Unit>> {
            return buildList {
                add(
                    "${
                        when (node.level) {
                            in 0..9 -> "[white]"
                            in 10..19 -> "[cyan]"
                            in 20..29 -> "[purple]"
                            in 30..39 -> "[yellow]"
                            else -> ""
                        }
                    }${node.level}级科技\n[green]点击查看对应科技树" to { techTreeMenu(core, node) })
                add(node.build(this@techMenu, core))
            }
        }

        fun findTech(node: TechNode): List<TechNode>? {
            return buildList {
                if (node.active(team()) && node.child() != null)
                    node.child()!!.forEach { techNode ->
                        findTech(techNode)?.forEach {
                            add(it)
                        }
                    }
                if (!node.active(team()))
                    add(node)
            }.ifEmpty { null }
        }
        findTech(techTree)?.forEach {
            add(treeBuild(it))
        }
        add(buildList {
            add("[green]查看科技树" to { techTreeMenu(core, techTree) })
        })

        this += listOf(
            "属性" to { lordMenu(core) },
            "[green]科技" to { techMenu(core) }
        )
        this += listOf(
            "军团" to { warMenu(core) },
            "城市" to { cityMenu(core) },
            "银行" to { bankMenu(core) },
            "[green]领主" to { lordMenu(core) }
        )
        this += listOf(
            "取消" to {}
        )
        if (admin && Groups.player.size() <= 8) {
            playerInputing[uuid()] = false
            this += listOf(
                "[red]<ADMIN>加10000000科技" to { team().techData().techPoint += 10000000;techMenu(core) }
            )
        }
    }
}

suspend fun Player.techTreeMenu(core: CoreBuild, node: TechNode) {
    fun whileBuildName(node: TechNode): String {
        return buildString {
            appendLine(
                "${
                    when (node.level) {
                        in 0..9 -> "[white]"
                        in 10..19 -> "[cyan]"
                        in 20..29 -> "[purple]"
                        in 30..39 -> "[yellow]"
                        else -> ""
                    }
                }Level ${node.level}[white]\n${node.buildName(this@techTreeMenu)}"
            )
            if (node.parent() != null) {
                append("\n[cyan]" + whileBuildName(node.parent()!!))
            }
        }
    }
    sendMenuBuilder<Unit>(
        this,
        900_000,
        "[green]科技页面\n[cyan]City-level[yellow]${core.level()}",
        "[cyan]科技树[white]\n" + whileBuildName(node)
    ) {
        playerInputing[uuid()] = false
        if (node.parent() != null)
            add(listOf(node.parent()!!.buildDesc(this@techTreeMenu, core)))
        add(buildList {
            add("${if (node.parent() != null) "[cyan]------↑父科技------\n" else ""}[yellow]------↓此科技------" to {
                techTreeMenu(
                    core,
                    node
                )
            })
        })
        add(listOf(node.buildDesc(this@techTreeMenu, core)))
        add(buildList {
            add(
                "[yellow]------↑此科技------${
                    if (!node.child().isNullOrEmpty()) "\n[green]------↓子科技------" else ""
                }" to { techTreeMenu(core, node) })
        })
        if (!node.child().isNullOrEmpty())
            add(buildList {
                node.child()?.forEach { add(it.buildDesc(this@techTreeMenu, core)) }
            })
        add(listOf("返回" to { techMenu(core) }))
        add(listOf("等级查找" to {
            val playerLastText = playerLastSendText[uuid()]
            sendMessage("------------\n${techLevelMap.keys.minOrNull()!!}[yellow]请输入查询等级[white]${techLevelMap.keys.maxOrNull()!!}\n------------")
            val startInputTime = Time.millis()
            var fail = true
            var level = 1
            playerInputing[uuid()] = true
            while (Time.timeSinceMillis(startInputTime) / 1000 <= 15) {
                if (playerInputing[uuid()] == false) break
                if (playerLastText != playerLastSendText[uuid()]) {
                    val input = playerLastSendText[uuid()]?.toIntOrNull()
                    if (input == null) {
                        fail = true
                        break
                    }
                    level = input.coerceAtLeast(techLevelMap.keys.minOrNull()!!)
                        .coerceAtMost(techLevelMap.keys.maxOrNull()!!)
                    fail = false
                    break
                }
                delay(50L)
            }
            playerLastSendText[uuid()] = ""
            if (fail) {
                sendMessage("查询失败！")
            } else {
                achievement("[green][百科全书！]", 50)
                techLevelFindMenu(core, node, level)
            }
        }))
    }
}

suspend fun Player.techLevelFindMenu(core: CoreBuild, node: TechNode, level: Int) {
    sendMenuBuilder<Unit>(
        this, 900_000, "[green]科技页面\n[cyan]City-level[yellow]${core.level()}", "查询科技-等级${level}"
    ) {
        suspend fun treeBuild(node: TechNode): List<Pair<String, suspend () -> Unit>> {
            return buildList {
                add(
                    "${
                        when (node.level) {
                            in 0..9 -> "[white]"
                            in 10..19 -> "[cyan]"
                            in 20..29 -> "[purple]"
                            in 30..39 -> "[yellow]"
                            else -> ""
                        }
                    }${node.level}级科技\n[green]点击查看对应科技树" to { techTreeMenu(core, node) })
                add(node.buildDesc(this@techLevelFindMenu, core))
            }
        }
        techLevelMap[level]?.forEach {
            add(treeBuild(it))
        }
        add(
            listOf(
            "返回科技" to { techMenu(core) },
            "再次查询" to {
                val playerLastText = playerLastSendText[uuid()]
                sendMessage("------------\n${techLevelMap.keys.minOrNull()!!}[yellow]请输入查询等级[white]${techLevelMap.keys.maxOrNull()!!}\n------------")
                val startInputTime = Time.millis()
                var fail = true
                var levelF = 1
                playerInputing[uuid()] = true
                while (Time.timeSinceMillis(startInputTime) / 1000 <= 15) {
                    if (playerInputing[uuid()] == false) break
                    if (playerLastText != playerLastSendText[uuid()]) {
                        val input = playerLastSendText[uuid()]?.toIntOrNull()
                        if (input == null) {
                            fail = true
                            break
                        }
                        levelF = input.coerceAtLeast(techLevelMap.keys.minOrNull()!!)
                            .coerceAtMost(techLevelMap.keys.maxOrNull()!!)
                        fail = false
                        break
                    }
                    delay(50L)
                }
                playerLastSendText[uuid()] = ""
                if (fail) {
                    sendMessage("查询失败！")
                } else {
                    techLevelFindMenu(core, node, levelF)
                }
            },
            "返回科技树" to { techTreeMenu(core, node) }
        ))
    }
}

class DisruptMissileAi : MissileAI() {

    override fun updateWeapons() {
    }

    private fun circle2(target: Position?, circleLength: Float, speed: Float) {
        if (target == null) return
        vec.set(target).sub(unit)
        if (vec.len() < circleLength) {
            vec.rotate(-(circleLength - vec.len()) / circleLength * 180f)
        }
        vec.setLength(speed)
        unit.moveAt(vec)
    }

    override fun updateMovement() {
        unloadPayloads()
        val time = if (unit is TimedKillc) (unit as TimedKillc).time() else 1000000f
        if (shooter != null) {
            val dst = Mathf.dst(shooter.x, shooter.y, shooter.aimX, shooter.aimY) * 1.25f
            val uDst = dst - Mathf.dst(unit.x, unit.y, shooter.aimX, shooter.aimY)

            val circle = Random(unit.id).nextBoolean()

            if (shooter.isShooting && unit.within(shooter.aimX, shooter.aimY, dst / 2.2f)) {
                unit.apply(StatusEffects.boss, Float.POSITIVE_INFINITY)

                if (circle)
                    circle(
                        Vec2(shooter.aimX, shooter.aimY),
                        dst * max(Random.nextFloat(), 0.25f),
                        unit.speed() * min(max((uDst / 408), 0.2f), 1.5f)
                    )
                else
                    circle2(
                        Vec2(shooter.aimX, shooter.aimY),
                        dst * max(Random.nextFloat(), 0.25f),
                        unit.speed() * min(max((uDst / 408), 0.2f), 1.5f)
                    )
                unit.rotation(Angles.angle(unit.x, unit.y, shooter.aimX, shooter.aimY))
            } else {
                if (time >= unit.type.homingDelay && shooter != null) {
                    unit.lookAt(shooter.aimX, shooter.aimY)
                }

                unit.unapply(StatusEffects.boss)

                unit.moveAt(
                    vec.trns(
                        unit.rotation, if (unit.type.missileAccelTime <= 0f) unit.speed() else Mathf.pow(
                            (time / unit.type.missileAccelTime).coerceAtMost(1f), 2f
                        ) * unit.speed()
                    )
                )
            }
        } else {
            unit.moveAt(
                vec.trns(
                    unit.rotation, if (unit.type.missileAccelTime <= 0f) unit.speed() else Mathf.pow(
                        (time / unit.type.missileAccelTime).coerceAtMost(1f), 2f
                    ) * unit.speed()
                )
            )
        }
        val build = unit.buildOn()

        if (build != null && build.team != unit.team && (build == target || !build.block.underBullets)) {
            unit.kill()
        }
    }
}

class VelaAi : CommandAI() {
    override fun updateUnit() {
        super.updateUnit()
        if (attackTarget != null) {
            if (!unit.within(attackTarget, unit.range() * 1.5f)) {
                unit.elevation = 1f
            } else if (!unit.onSolid()) {
                unit.elevation = 0f
            }
        } else if (targetPos != null) {
            if (!unit.within(targetPos, unit.range() * 1.5f)) {
                unit.elevation = 1f
            } else if (!unit.onSolid()) {
                unit.elevation = 0f
            }
        }
    }
}

fun TeamData.pads(): List<Building> {
    return Groups.build.toList().filter { it.block is LaunchPad && it.team == team }
}

val lordOfWarPatch = """
{
  "block": {
    "launch-pad": {
      "health": 5000,
      "requirements": [
        "copper/800",
        "lead/600"
      ],
      "solid": false,
      "underBullets": true,
      "armor": 10
    },
    "interplanetary-accelerator": {
      "health": 96000,
      "requirements": [
        "copper/12000",
        "lead/8000"
      ],
      "solid": false,
      "underBullets": true,
      "buildCostMultiplier": 0.5,
      "armor": 25,
      "capacities": [48000,12800,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
      "itemCapacity": 999999
    },
    "flux-reactor": {
      "health": 96000,
      "requirements": [
        "copper/18000",
        "lead/12000"
      ],
      "solid": false,
      "underBullets": true,
      "buildCostMultiplier": 0.5,
      "armor": 25,
    },
    "core-shard": {
      "health": 2500,
      "fogRadius": 12,
      "requirements": [
        "copper/4000",
        "lead/800"
      ],
      "itemCapacity": 999999,
      "armor": 5
    },
    "core-foundation": {
      "unitType": "alpha",
      "health": 4500,
      "fogRadius": 13,
      "requirements": [
        "copper/8000",
        "lead/1600"
      ],
      "itemCapacity": 999999,
      "armor": 10
    },
    "core-bastion": {
      "unitType": "alpha",
      "health": 8000,
      "fogRadius": 13,
      "requirements": [
        "copper/16000",
        "lead/3200"
      ],
      "itemCapacity": 999999,
      "armor": 15,
      "incinerateNonBuildable": false
    },
    "core-nucleus": {
      "unitType": "beta",
      "health": 12000,
      "fogRadius": 16,
      "requirements": [
        "copper/26000",
        "lead/6400"
      ],
      "itemCapacity": 999999,
      "armor": 20
    },
    "core-citadel": {
      "unitType": "beta",
      "health": 32000,
      "fogRadius": 16,
      "requirements": [
        "copper/48000",
        "lead/12800"
      ],
      "itemCapacity": 999999,
      "armor": 25,
      "incinerateNonBuildable": false
    },
    "core-acropolis": {
      "unitType": "gamma",
      "health": 80000,
      "fogRadius": 20,
      "requirements": [
        "copper/72000",
        "lead/25600"
      ],
      "itemCapacity": 999999,
      "armor": 30,
      "incinerateNonBuildable": false
    },
    "micro-processor":{
      "instructionsPerTick": 1,
      "range": 160,
      "requirements": [
        "copper/80",
        "lead/60"
      ]
    },
    "logic-processor":{
      "instructionsPerTick": 2,
      "range": 480,
      "requirements": [
        "copper/960",
        "lead/840"
      ]
    },
    "hyper-processor":{
      "instructionsPerTick": 3,
      "range": 1200,
      "requirements": [
        "copper/3200",
        "lead/2800"
      ]
    },
    "shock-mine":{
      "requirements": [
        "copper/70",
        "lead/25"
      ],
      "underBullets": true
    },
    "radar":{
      "discoveryTime": 4800,
      "requirements": [
        "copper/150",
        "lead/75"
      ],
      "fogRadius": 72
    }
  },
  "unit": {
    "renale.hidden": false,
    "latum.hidden": false,
    "manifold.speed": 7,
    "alpha": {
      "fogRadius": 8,
      "mineSpeed": 8,
      "buildSpeed": 1
    },
    "beta": {
      "fogRadius": 8,
      "mineTier": 2,
      "mineSpeed": 12,
      "buildSpeed": 1.5
    },
    "gamma": {
      "fogRadius": 8,
      "mineSpeed": 16,
      "mineTier": 3,
      "mineWalls": true,
      "buildSpeed": 2
    },
    "dagger": {
      "fogRadius": 16,
      "armor": 0,
      "buildSpeed": 0.25
    },
    "nova": {
      "armor": 0,
      "fogRadius": 16,
      "weapons.0.bullet.healPercent": 0,
      "weapons.0.bullet.healAmount": 50,
      "buildSpeed": 0.25
    },
    "merui": {
      "health": 80,
      "fogRadius": 16,
      "weapons.0.bullet.splashDamage": 18,
      "armor": 0,
      "buildSpeed": 0.25
    },
    "elude": {
      "health": 120,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 7,
      "armor": 0,
      "buildSpeed": 0.25
    },
    "stell": {
      "health": 120,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 18,
      "armor": 4,
      "buildSpeed": 0.25
    },
    "pulsar": {
      "health": 360,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 9,
      "weapons.0.bullet.healPercent": 0,
      "weapons.0.bullet.healAmount": 35,
      "weapons.0.bullet.lightningType.healPercent": 0,
      "weapons.0.bullet.lightningType.healAmount": 21,
      "armor": 3,
      "buildSpeed": 1
    },
    "poly": {
      "health": 360,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 12,
      "weapons.0.bullet.healPercent": 0,
      "weapons.0.bullet.healAmount": 55,
      "armor": 3,
      "buildSpeed": 1
    },
    "atrax": {
      "health": 360,
      "fogRadius": 18,
      "weapons.0.bullet.damage": 18,
      "weapons.0.bullet.collidesAir": true,
      "targetAir": true,
      "armor": 3,
      "buildSpeed": 0.5
    },
    "avert": {
      "health": 360,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 14,
      "armor": 3,
      "buildSpeed": 0.5
    },
    "locus": {
      "health": 360,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 12,
      "armor": 8,
      "buildSpeed": 0.5
    },
    "mace": {
      "health": 620,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 38,
      "armor": 6,
      "buildSpeed": 0.75
    },
    "mega": {
      "health": 320,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 12,
      "weapons.2.bullet.damage": 6,
      "weapons.0.bullet.healPercent": 0,
      "weapons.0.bullet.healAmount": 35,
      "weapons.2.bullet.healPercent": 0,
      "weapons.2.bullet.healAmount": 15,
      "armor": 6,
      "buildSpeed": 1.5
    },
    "cleroi": {
      "health": 460,
      "fogRadius": 18,
      "weapons.2.bullet.damage": 12,
      "armor": 6,
      "buildSpeed": 0.75
    },
    "zenith": {
      "health": 420,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 32,
      "armor": 6,
      "buildSpeed": 0.75
    },
    "precept": {
      "health": 840,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 36,
      "weapons.0.bullet.splashDamage": 20,
      "weapons.0.bullet.fragBullet.damage": 12,
      "armor": 15,
      "buildSpeed": 0.75
    },
    "spiroct": {
      "health": 460,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 37,
      "weapons.0.bullet.sapStrength": 0,
      "weapons.2.bullet.damage": 33,
      "weapons.2.bullet.sapStrength": 0,
      "armor": 12,
      "buildSpeed": 1
    },
    "cyerce": {
      "health": 860,
      "flying": true,
      "fogRadius": 16,
      "weapons.0.bullet.maxRange": 65,
      "weapons.0.repairSpeed": 0.1,
      "weapons.1.repairSpeed": 0.1,
      "weapons.2.bullet.fragBullet.healPercent": 0,
      "weapons.2.bullet.fragBullet.healAmount": 28,
      "armor": 12,
      "buildSpeed": 2
    },
    "anthicus": {
      "health": 880,
      "fogRadius": 20,
      "weapons.0.bullet.spawnUnit.weapons.0.bullet.splashDamage": 80,
      "weapons.0.shootStatus": "slow",
      "weapons.0.shootStatusDuration": 131,
      "weapons.1.shootStatus": "slow",
      "weapons.1.shootStatusDuration": 131,
      "armor": 12,
      "buildSpeed": 1
    },
    "antumbra": {
      "health": 820,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 11,
      "weapons.0.bullet.splashDamage": 23,
      "weapons.5.bullet.damage": 25,
      "armor": 12,
      "buildSpeed": 1
    },
    "vanquish": {
      "health": 1560,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 85,
      "weapons.0.bullet.splashDamage": 35,
      "armor": 22,
      "buildSpeed": 1
    },
    "arkyid": {
      "health": 2140,
      "fogRadius": 16,
      "weapons.0.bullet.sapStrength": 0,
      "armor": 18,
      "buildSpeed": 1.25
    },
    "vela": {
      "health": 1860,
      "armor": 18,
      "fogRadius": 16,
      "weapons.0.bullet.healPercent": 0,
      "weapons.0.shootStatus": "muddy",
      "weapons.0.bullet.healAmount": 55,
      "buildSpeed": 2.5
    },
    "tecta": {
      "health": 1260,
      "armor": 18,
      "fogRadius": 20,
      "weapons.0.bullet.lifetime": 70,
      "buildSpeed": 1.25
    },
    "sei": {
      "health": 1540,
      "range": 196,
      "fogRadius": 16,
      "weapons.0.bullet.damage": 18,
      "weapons.0.bullet.splashDamage": 20,
      "weapons.0.bullet.lifetime": 50,
      "weapons.2.bullet.damage": 31,
      "weapons.2.bullet.lifetime": 30,
      "flying": true,
      "armor": 18,
      "buildSpeed": 1.25
    },
    "scepter": {
      "health": 2540,
      "fogRadius": 16,
      "weapons.0.shoot.shots": 2,
      "weapons.0.bullet.damage": 50,
      "weapons.0.bullet.lightningType.lightningDamage": 14,
      "weapons.1.shoot.shots": 2,
      "weapons.2.shoot.shots": 3,
      "weapons.2.bullet.damage": 15,
      "weapons.3.shoot.shots": 3,
      "weapons.1.shoot.shotDelay": 2,
      "weapons.4.shoot.shots": 3,
      "weapons.5.shoot.shots": 3,
      "weapons.5.shoot.shotDelay": 2,
      "armor": 24,
      "buildSpeed": 1.25
    },
    "toxopid": {
      "health": 9800,
      "weapons.0.shoot.shots": 3,
      "weapons.1.shoot.shots": 3,
      "weapons.0.bullet.damage": 150,
      "weapons.2.bullet.collidesAir": true,
      "weapons.2.bullet.splashDamage": 25,
      "weapons.2.bullet.fragBullet.collidesAir": true,
      "weapons.2.bullet.fragBullet.damage": 15,
      "abilities.+=": [
        {
          "type": "RegenAbility",
          "percentAmount": 0.027
        },
        {
          "type": "SuppressionFieldAbility",
          "range": 320
        }
      ],
      "armor": 36,
      "buildSpeed": 2
    },
    "aegires": {
      "health": 7800,
      "abilities.0": {
        "damage": 80,
        "maxTargets": 80,
        "healPercent": 4
      },
      "flying": true,
      "armor": 36,
      "buildSpeed": 4
    },
    "collaris": {
      "health": 8400,
      "targetAir": true,
      "weapons.0.bullet.collidesAir": true,
      "weapons.0.bullet.fragBullet.collidesAir": true,
      "weapons.0.bullet.damage": 250,
      "weapons.0.bullet.splashDamage": 32,
      "weapons.0.bullet.fragBullet.damage": 24,
      "weapons.0.bullet.fragBullet.splashDamage": 17,
      "abilities.+=": [{
        "type": "UnitSpawnAbility",
        "spawnTime": 900,
        "unit": "flare",
        "spawnX": 0,
        "spawnY": -8
      }
      ],
      "armor": 36,
      "buildSpeed": 2
    },
    "flare": {
      "health": 10,
      "fogRadius": 64,
      "speed": 5.2
    },
    "eclipse": {
      "health": 10600,
      "abilities.+=": [
        {
          "type": "ShieldRegenFieldAbility",
          "amount": 180,
          "max": 360,
          "reload": 240,
          "range": 240
        }
      ],
      "armor": 36,
      "buildSpeed": 2
    },
    "conquer": {
      "health": 12800,
      "flying": true,
      "abilities.+=": [
        {
          "type": "ForceFieldAbility",
          "radius": 200,
          "regen": 4.8,
          "max": 9600,
          "cooldown": 400
        }
      ],
      "armor": 42,
      "buildSpeed": 2
    },
    "disrupt": {
      "health": 8800,
      "flying": true,
      "abilities.0.range": 960,
      "weapons.0.reload": 3200,
      "weapons.0.shoot.shots": 26,
      "weapons.0.shoot.shotDelay": 8,
      "weapons.0.shoot.firstShotDelay": 12,
      "weapons.0.inaccuracy": 120,
      "weapons.0.shootStatus": "slow",
      "weapons.0.shootStatusDuration": 1601,
      "weapons.1.reload": 3200,
      "weapons.1.shoot.shots": 26,
      "weapons.1.shoot.shotDelay": 8,
      "weapons.1.shoot.firstShotDelay": 12,
      "weapons.1.inaccuracy": 120,
      "weapons.1.shootStatus": "slow",
      "weapons.1.shootStatusDuration": 1601,
      "weapons.0.bullet.spawnUnit.lifetime": 350,
      "weapons.0.bullet.spawnUnit.health": 320,
      "weapons.0.bullet.spawnUnit.armor": 6,
      "weapons.0.bullet.spawnUnit.rotateSpeed": 0.8,
      "weapons.0.bullet.spawnUnit.speed": 2.8,
      "weapons.0.bullet.spawnUnit.weapons.0.shoot.shots": 2,
      "weapons.0.bullet.spawnUnit.weapons.0.inaccuracy": 45,
      "weapons.0.bullet.spawnUnit.fogRadius": 9,
      "weapons.0.bullet.spawnUnit.targetAir": true,
      "targetAir": true,
      "armor": 36,
      "buildSpeed": 2
    },
    "quell": {
      "health": 1660,
      "weapons.0.reload": 900,
      "weapons.0.shootStatus": "slow",
      "weapons.0.shootStatusDuration": 901,
      "weapons.1.reload": 900,
      "weapons.1.shootStatus": "slow",
      "weapons.1.shootStatusDuration": 901,
      "targetAir": true
    },
    "obviate": {
      "health": 960
    }
  }
}
"""
mapPatches = listOf(lordOfWarPatch)



// ===== 内联 registerMapRule (原 utilMapRule, 本服无跨脚本调用支持) =====
private val bakMap = mutableMapOf<kotlin.reflect.KMutableProperty0<*>, Any?>()

private fun <T> registerMapRule(field: kotlin.reflect.KMutableProperty0<T>, checkRef: Boolean = true, valueFactory: (T) -> T) {
    synchronized(bakMap) {
        @Suppress("UNCHECKED_CAST")
        val old = (bakMap[field] as T?) ?: field.get()
        val new = valueFactory(old)
        if (field !in bakMap && checkRef && new is Any && new === old)
            error("valueFactory can't return the same instance for $field")
        field.set(new)
        bakMap[field] = old
    }
}

listen<EventType.ResetEvent> {
    synchronized(bakMap) {
        bakMap.forEach { (field, bakValue) ->
            @Suppress("UNCHECKED_CAST")
            (field as kotlin.reflect.KMutableProperty0<Any?>).set(bakValue)
        }
        bakMap.clear()
    }
}


onEnable {
    //反正不影响同步 我也不会用ct改 (((
    if (UnitTypes.disrupt.weapons.get(0).bullet.spawnUnit.controller != Func<mindustry.gen.Unit, UnitController> { DisruptMissileAi() })
        registerMapRule(
            UnitTypes.disrupt.weapons.get(0).bullet.spawnUnit::controller
        ) { Func<mindustry.gen.Unit, UnitController> { DisruptMissileAi() } }
    if (UnitTypes.disrupt.weapons.get(0).bullet.spawnUnit.weapons.get(0).bullet.spawnUnit != UnitTypes.quell.weapons.get(
            0
        ).bullet.spawnUnit
    )
        registerMapRule(
            UnitTypes.disrupt.weapons.get(0).bullet.spawnUnit.weapons.get(0).bullet::spawnUnit
        ) { UnitTypes.quell.weapons.get(0).bullet.spawnUnit }
    if (UnitTypes.vela.controller != Func<mindustry.gen.Unit, UnitController> { VelaAi() })
        registerMapRule(
            UnitTypes.vela::controller
        ) { Func<mindustry.gen.Unit, UnitController> { VelaAi() } }
    if (UnitTypes.nova.controller != Func<mindustry.gen.Unit, UnitController> { VelaAi() })
        registerMapRule(
            UnitTypes.nova::controller
        ) { Func<mindustry.gen.Unit, UnitController> { VelaAi() } }
    if (UnitTypes.pulsar.controller != Func<mindustry.gen.Unit, UnitController> { VelaAi() })
        registerMapRule(
            UnitTypes.pulsar::controller
        ) { Func<mindustry.gen.Unit, UnitController> { VelaAi() } }
    //contextScript<coreMindustry.ContentsTweaker>().addPatch("LordOfWar", dataDirectory.child("contents-patch").child("14668.json").readString())

    launch(Dispatchers.game) {
        Groups.build.forEach {
            it.maxHealth = it.block.health.toFloat()
            it.health = it.maxHealth
        }
        state.rules.apply {
            modeName = "LordOfWar"
            //I like colorful mode name
            unitCap = 9999
            revealedBlocks.apply {
                add(Blocks.launchPad)
                add(Blocks.interplanetaryAccelerator)
                add(Blocks.coreShard)
            }
            if (!state.rules.tags.getBool("@noSpecialFog")) {
                dynamicColor = Color.valueOf("000000")
                staticColor = Color.valueOf("000000")
            }
            TechDifficult = tags.getFloat("@TechDifficult", 1f).coerceAtLeast(0f)
        }
        val statusEffectListU = listOf(
            StatusEffects.boss,
            StatusEffects.overclock,
            StatusEffects.overdrive,
        )
        val statusEffectListB = mapOf(
            StatusEffects.wet to 1.25f * 60f,
            StatusEffects.corroded to 5f * 60f,
            StatusEffects.shocked to 1f * 60f,
            StatusEffects.blasted to 1f * 60f,
            StatusEffects.tarred to 5f * 60f,
            StatusEffects.freezing to 1.25f * 60f,
            StatusEffects.muddy to 20f * 60f,
            StatusEffects.sporeSlowed to 8f * 60f,
            StatusEffects.melting to 2.5f * 60f,
            StatusEffects.burning to 5f * 60f
        )
        val commonTech = buildList {
            repeat(5) {
                add(fun Player.(): Pair<() -> Unit, String> {
                    return fun() {
                        team().techData().unitStatusEffectProbability += 0.10f
                        Call.sendMessage("[white]$name [#${team().color}]提高了兵营效果触发概率！(${((team().techData().unitStatusEffectProbability - 0.10f) * 100f).format()}% -> ${(team().techData().unitStatusEffectProbability * 100f).format()}%)")
                    } to "[white]兵营效果触发概率"
                })
            }
            repeat(7) {
                add(fun Player.(): Pair<() -> Unit, String> {
                    return fun() {
                        team().techData().bulletStatusEffectProbability += 0.05f
                        Call.sendMessage("[white]$name [#${team().color}]改良了队伍的效果弹头！(${((team().techData().bulletStatusEffectProbability - 0.05f) * 100f).format()}% -> ${(team().techData().bulletStatusEffectProbability * 100f).format()}%)")
                    } to "[white]效果弹头概率提升"
                })
            }
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    val last = team().techData().upgradeAndRollMultiplier
                    team().techData().upgradeAndRollMultiplier *= 0.8f
                    Call.sendMessage("[white]$name [#${team().color}]减少了队伍升级和刷新兵花费！(${(last * 100f).format()}% -> ${(team().techData().upgradeAndRollMultiplier * 100f).format()}%)")
                } to "[white]单位升级费用降低"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().rules().blockHealthMultiplier += 0.2f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]加固了队伍建筑结构！(建筑血量)(${((team().rules().blockHealthMultiplier - 0.2f) * 100f).format()}% -> ${(team().rules().blockHealthMultiplier * 100f).format()}%)")
                } to "[white]建筑加固(建筑血量)"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().rules().unitDamageMultiplier += 0.15f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]锋利了队伍单位武器！(单位攻击)(${((team().rules().unitDamageMultiplier - 0.15f) * 100f).format()}% -> ${(team().rules().unitDamageMultiplier * 100f).format()}%)")
                } to "[white]锋利武器(单位攻击)"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().rules().buildSpeedMultiplier += 0.3f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]改进了队伍建筑结构！(建筑速度)(${((team().rules().buildSpeedMultiplier - 0.3f) * 100f).format()}% -> ${(team().rules().buildSpeedMultiplier * 100f).format()}%)")
                } to "[white]快速构建(建筑速度)"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().rules().blockDamageMultiplier += 0.2f
                    Call.setRules(state.rules)
                    Call.sendMessage("[white]$name [#${team().color}]加强了队伍建筑武器！(建筑伤害)(${((team().rules().blockDamageMultiplier - 0.2f) * 100f).format()}% -> ${(team().rules().blockDamageMultiplier * 100f).format()}%)")
                } to "[white]先进炮台(建筑伤害)"
            })
        }
        val unitStatusTech = buildList {
            statusEffectListU.forEach {
                add(fun Player.(): Pair<() -> Unit, String> {
                    return fun() {
                        team().techData().unitStatusEffect[it] = Float.POSITIVE_INFINITY
                        Call.sendMessage("[white]$name [#${team().color}]给队伍添加了新的兵营效果！(${it.emoji()})")
                    } to "[white]兵营效果${it.emoji()}"
                })
            }
        }
        val bulletStatusTech = buildList {
            statusEffectListB.forEach {
                add(fun Player.(): Pair<() -> Unit, String> {
                    return fun() {
                        team().techData().bulletStatusEffect[it.key] = it.value
                        Call.sendMessage("[white]$name [#${team().color}]给队伍添加了新的效果弹头！[white](${it.key.emoji()})")
                    } to "[white]效果弹头${it.key.emoji()}"
                })
            }
        }
        val rareTech = buildList {
            add(fun Player.(): Pair<() -> Unit, String> {
                val it = StatusEffects.slow to 1.5f * 60f
                return fun() {
                    team().techData().bulletStatusEffect[it.first] = it.second
                    Call.sendMessage("[white]$name [#${team().color}]给队伍添加了新的[cyan]稀有[#${team().color}] 效果弹头！[white](${it.first.emoji()})")
                } to "[cyan]!稀有! \n[white]效果弹头${it.first.emoji()}"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().betterBuyUnits = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 高效募兵")
                } to "[cyan]!稀有! \n[white]高效募兵"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().chooseUnitTypes = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 命意指定")
                } to "[cyan]!稀有! \n[white]命意指定"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().chooseUnitTypes = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 全能狂热")
                } to "[cyan]!稀有! \n[white]全能狂热"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().cityUpgradeMultiplier -= 0.1f
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 高速发展")
                } to "[cyan]!稀有! \n[white]高速发展"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().bankNoRemoveCoins = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 零险银行")
                } to "[cyan]!稀有! \n[white]零险银行"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().controlOtherPlayerUnits = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 蜂群意识")
                } to "[cyan]!稀有! \n[white]蜂群意识"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().moreCityLords = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 天下为公")
                } to "[cyan]!稀有! \n[white]天下为公"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().quickTech = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 科教兴国")
                } to "[cyan]!稀有! \n[white]科教兴国"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().alphaMoreCoins = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 高量抽取")
                } to "[cyan]!稀有! \n[white]高量抽取"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().tpNoDeBuff = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 传送适应")
                } to "[cyan]!稀有! \n[white]传送适应"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().falseVoid = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 虚空使徒")
                } to "[cyan]!稀有! \n[white]虚空使徒"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().efficientSetOff = true
                    team().techData().lordUnitExplosionMultiplier += 1
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[cyan]稀有科技[#${team().color}] 虚空引爆")
                } to "[cyan]!稀有! \n[white]高效引爆"
            })
        }
        val epicTech = buildList {
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().quicklySetCityType = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 快速转型")
                } to "[purple]!史诗! \n[white]快速转型"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().void = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 遁入虚空")
                } to "[purple]!史诗! \n[white]遁入虚空"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().autoSetCityType = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 自动转型")
                } to "[purple]!史诗! \n[white]自动转型"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                val it = StatusEffects.shielded
                return fun() {
                    team().techData().unitStatusEffect[it] = Float.POSITIVE_INFINITY
                    Call.sendMessage("[white]$name [#${team().color}]给队伍添加了新的[cyan]史诗[#${team().color}] 效果兵营！[white](${it.emoji()})")
                } to "[purple]!史诗! \n[white]兵营效果${it.emoji()}"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().verdict = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 真实裁决")
                } to "[purple]!史诗! \n[white]真实裁决"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().chooseLordTypes = true
                    team().techData().lordUnitSpawnTimeMultiplier /= 2
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 出征号令")
                } to "[purple]!史诗! \n[white]出征号令"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().teamRespawn = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 涅槃重生")
                } to "[purple]!史诗! \n[white]涅槃重生"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().deadIsntDeadProbability = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 英雄不朽")
                } to "[purple]!史诗! \n[white]英雄不朽"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().deadBoom = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 尸体炸弹")
                } to "[purple]!史诗! \n[white]尸体炸弹"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().maxHealthDamage = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 虚空之噬")
                } to "[purple]!史诗! \n[white]虚空之噬"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().buffedLords = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 界限突破")
                } to "[purple]!史诗! \n[white]界限突破"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().upgradeMS = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 奇观大师")
                } to "[purple]!史诗! \n[white]奇观大师"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().cycloneEngine = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[purple]史诗科技[#${team().color}] 气旋引擎")
                } to "[purple]!史诗! \n[white]气旋引擎"
            })
        }
        val legendTech = buildList {
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().allCoinsAreUnits = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[yellow]传说科技[#${team().color}] 全民皆兵")
                } to "[yellow]!传说! \n[white]全民皆兵"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                val it = StatusEffects.invincible
                return fun() {
                    team().techData().unitStatusEffect[it] = 120 * 60f
                    Call.sendMessage("[white]$name [#${team().color}]给队伍添加了新的[yellow]传说[#${team().color}] 效果兵营！[white](${it.emoji()})")
                } to "[yellow]!传说! \n[white]兵营效果${it.emoji()}"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                val it = StatusEffects.disarmed to 1.5f * 60f
                return fun() {
                    team().techData().bulletStatusEffect[it.first] = it.second
                    Call.sendMessage("[white]$name [#${team().color}]给队伍添加了新的[yellow]传说[#${team().color}] 效果弹头！[white](${it.first.emoji()})")
                } to "[yellow]!传说! \n[white]效果弹头${it.first.emoji()}"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().fogKiller = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[yellow]传说科技[#${team().color}] 影之猎杀")
                } to "[yellow]!传说! \n[white]影之猎杀"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().unitStatusEffectProbability += 2f
                    team().techData().bulletStatusEffectProbability += 2f
                    team().rules().unitHealthMultiplier *= 2f
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[yellow]传说科技[#${team().color}] 群星闪耀")
                } to "[yellow]!传说! \n[white]群星闪耀"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().infMS = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[yellow]传说科技[#${team().color}] 奇观误国")
                } to "[yellow]!传说! \n[white]奇观误国"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().hedgeFund = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[yellow]传说科技[#${team().color}] 对冲基金")
                } to "[yellow]!传说! \n[white]对冲基金"
            })
        }
        val voidTech = buildList {
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().voidAnnihilation = true
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[navy]虚空科技[#${team().color}] 湮灭")
                } to "[navy]!虚空! \n[white]湮灭"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().voidPotential = true
                    team().data().cores.forEach {
                        it.cityData().cityType = CityTypes.WarLogisticsEconomic
                    }
                    team().techData().unitStatusEffect[StatusEffects.corroded] = Float.POSITIVE_INFINITY
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[navy]虚空科技[#${team().color}] 潜能")
                } to "[navy]!虚空! \n[white]潜能"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().voidDeath = true
                    val lastCore: CoreBuild =
                        Groups.build.toList().filter { it.team == team() && it.block is CoreBlock }
                            .random() as CoreBuild
                    Groups.build.toList().filter { it.team == team() && it.block is CoreBlock && it != lastCore }
                        .forEach {
                            team().rules().unitDamageMultiplier += 0.2f
                            team().rules().buildSpeedMultiplier += 0.2f
                            team().rules().blockDamageMultiplier += 0.2f
                            team().rules().blockHealthMultiplier += 0.2f
                            it.tile.setNet(Blocks.air)
                        }
                    lastCore.maxHealth *= 2
                    lastCore.health *= 2
                    lastCore.cityData().cityType = CityTypes.WarLogisticsEconomic
                    Call.setRules(state.rules)
                    Call.announce("[#${team().color}]${team().name}[red]自爆了核心\n全属性大幅增加,尽快扼杀!")
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[navy]虚空科技[#${team().color}] 死亡")
                } to "[navy]!虚空! \n[white]死亡"
            })
            add(fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().voidChaos = true
                    Groups.player.forEach {
                        if (it.unitType() == null) return@forEach
                        it.unitType(Random.nextInt(1, 5).levelUnits()!!.random())
                        if (it.lordUnitType() == null) return@forEach
                        it.lordUnitType(it.lordUnitType().levelUnits()!!.random())
                    }
                    team().data().cores.forEach {
                        it.cityData().cityType = CityTypes.all().random()
                    }
                    Call.sendMessage("[white]$name [#${team().color}]研究了新的[navy]虚空科技[#${team().color}] 混乱")
                } to "[navy]!虚空! \n[white]混乱"
            })
        }
        val voidEmptyVoid =
            fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    //only do it
                    Call.sendMessage("[white]$name [#${team().color}]被虚空侵蚀...")
                } to "[red]虚空侵蚀"
            }

        /**
         *
         * 科技树
         * F2 - 效果弹头 效果兵营
         * F3 - 普通科技
         * F4 - 普通科技
         * F5 - 效果弹头 效果兵营
         * F6 - 稀有科技
         * F7 - 普通科技
         * F8 - 普通科技
         * F9 - 史诗科技
         * F10- 效果弹头 效果兵营    虚空侵蚀
         * F11- 普通科技            虚空科技
         * F12- 普通科技            虚空侵蚀
         * F13- 普通科技            虚空侵蚀
         * F14- 普通科技            虚空侵蚀
         * F15- 稀有科技            虚空侵蚀
         * F16- 效果弹头 效果兵营    虚空侵蚀
         * F17- 史诗科技            虚空科技
         * F18- 普通科技            虚空侵蚀
         * F19- 普通科技            虚空侵蚀
         * F20- 稀有科技            虚空侵蚀
         * F21- 效果弹头 效果兵营    虚空科技
         * F22~-普通科技
         * F30- 传说科技
         **/
        val commonLeft = commonTech.toMutableList()
        val rareLeft = rareTech.toMutableList()
        val epicLeft = epicTech.toMutableList()
        val legendLeft = legendTech.toMutableList()
        val bulletLeft = bulletStatusTech.toMutableList()
        val unitLeft = unitStatusTech.toMutableList()
        val voidLeft = voidTech.toMutableList()
        val empty =
            fun Player.(): Pair<() -> Unit, String> {
                return fun() {
                    team().techData().techPoint++
                } to "空科技 点击免费跳过"
            }

        fun randomBulletUnitTech(): Player.() -> Pair<() -> Unit, String> {
            val tech: Player.() -> Pair<() -> Unit, String>
            if (Random.nextFloat() <= 0.5f) {
                if (unitLeft.isEmpty()) {
                    if (bulletLeft.isEmpty())
                        tech = empty
                    else {
                        tech = bulletLeft.random()
                        bulletLeft.remove(tech)
                    }
                } else {
                    tech = unitLeft.random()
                    unitLeft.remove(tech)
                }
            } else {
                if (bulletLeft.isEmpty()) {
                    if (unitLeft.isEmpty())
                        tech = empty
                    else {
                        tech = unitLeft.random()
                        unitLeft.remove(tech)
                    }
                } else {
                    tech = bulletLeft.random()
                    bulletLeft.remove(tech)
                }
            }
            return tech
        }
        techTree.addChild(buildList {
            repeat(2) {
                val node2 = TechNode(tech = randomBulletUnitTech())
                add(node2)
                node2.addChild(buildList {
                    val tech3 = commonLeft.random()
                    val node3 = TechNode(tech = tech3)
                    add(node3)
                    node3.addChild(buildList {
                        val tech4 = commonLeft.random()
                        val node4 = TechNode(tech = tech4)
                        add(node4)
                        node4.addChild(buildList {
                            val node5 = TechNode(tech = randomBulletUnitTech())
                            add(node5)
                            node5.addChild(buildList {
                                val tech6: Player.() -> Pair<() -> Unit, String>
                                if (rareLeft.isEmpty())
                                    tech6 = commonLeft.random()
                                else {
                                    tech6 = rareLeft.random()
                                    rareLeft.remove(tech6)
                                }
                                val node6 = TechNode(tech = tech6)
                                add(node6)
                                node6.addChild(buildList {
                                    repeat(2) {
                                        val tech7 = commonLeft.random()
                                        val node7 = TechNode(tech = tech7)
                                        add(node7)
                                        node7.addChild(buildList {
                                            val tech8 = commonLeft.random()
                                            val node8 = TechNode(tech = tech8)
                                            add(node8)
                                            node8.addChild(buildList {
                                                val tech9: Player.() -> Pair<() -> Unit, String>
                                                if (epicLeft.isEmpty())
                                                    tech9 = commonLeft.random()
                                                else {
                                                    tech9 = epicLeft.random()
                                                    epicLeft.remove(tech9)
                                                }
                                                val node9 = TechNode(tech = tech9)
                                                if (tech9 == epicTech[1])
                                                    node9.addChild(buildList {
                                                        val vTech10 = voidEmptyVoid
                                                        val vNode10 = TechNode(tech = voidEmptyVoid)
                                                        add(vNode10)
                                                        vNode10.addChild(buildList {
                                                            val vTech11 = voidLeft.random()
                                                            voidLeft.remove(vTech11)
                                                            val vNode11 = TechNode(tech = vTech11)
                                                            add(vNode11)
                                                            vNode11.addChild(buildList {
                                                                val vTech12 = voidEmptyVoid
                                                                val vNode12 = TechNode(tech = vTech12)
                                                                add(vNode12)
                                                                vNode12.addChild(buildList {
                                                                    val vTech13 = voidEmptyVoid
                                                                    val vNode13 = TechNode(tech = vTech13)
                                                                    add(vNode13)
                                                                    vNode13.addChild(buildList {
                                                                        val vTech14 = voidEmptyVoid
                                                                        val vNode14 = TechNode(tech = vTech14)
                                                                        add(vNode14)
                                                                        vNode14.addChild(buildList {
                                                                            val vTech15 = voidEmptyVoid
                                                                            val vNode15 = TechNode(tech = vTech15)
                                                                            add(vNode15)
                                                                            vNode15.addChild(buildList {
                                                                                val vTech16 = voidEmptyVoid
                                                                                val vNode16 =
                                                                                    TechNode(tech = vTech16)
                                                                                add(vNode16)
                                                                                vNode16.addChild(buildList {
                                                                                    val vTech17 = voidLeft.random()
                                                                                    voidLeft.remove(vTech17)
                                                                                    val vNode17 =
                                                                                        TechNode(tech = vTech17)
                                                                                    add(vNode17)
                                                                                    vNode17.addChild(buildList {
                                                                                        val vTech18 = voidEmptyVoid
                                                                                        val vNode18 =
                                                                                            TechNode(tech = vTech18)
                                                                                        add(vNode18)
                                                                                        vNode18.addChild(buildList {
                                                                                            val vTech19 =
                                                                                                voidEmptyVoid
                                                                                            val vNode19 =
                                                                                                TechNode(tech = vTech19)
                                                                                            add(vNode19)
                                                                                            vNode19.addChild(
                                                                                                buildList {
                                                                                                    val vTech20 =
                                                                                                        voidEmptyVoid
                                                                                                    val vNode20 =
                                                                                                        TechNode(
                                                                                                            tech = vTech20
                                                                                                        )
                                                                                                    add(vNode20)
                                                                                                    vNode20.addChild(
                                                                                                        buildList {
                                                                                                            val vTech21 =
                                                                                                                voidLeft.random()
                                                                                                            voidLeft.remove(
                                                                                                                vTech21
                                                                                                            )
                                                                                                            val vNode21 =
                                                                                                                TechNode(
                                                                                                                    tech = vTech21
                                                                                                                )
                                                                                                            add(vNode21)
                                                                                                        })
                                                                                                })
                                                                                        })
                                                                                    })
                                                                                })
                                                                            })
                                                                        })
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                add(node9)
                                                node9.addChild(buildList {
                                                    val node10 = TechNode(tech = randomBulletUnitTech())
                                                    add(node10)
                                                    node10.addChild(buildList {
                                                        val tech11 = commonLeft.random()
                                                        val node11 = TechNode(tech = tech11)
                                                        add(node11)
                                                        node11.addChild(buildList {
                                                            val tech12 = commonLeft.random()
                                                            val node12 = TechNode(tech = tech12)
                                                            add(node12)
                                                            node12.addChild(buildList {
                                                                val tech13 = commonLeft.random()
                                                                val node13 = TechNode(tech = tech13)
                                                                add(node13)
                                                                node13.addChild(buildList {
                                                                    val tech14 = commonLeft.random()
                                                                    val node14 = TechNode(tech = tech14)
                                                                    add(node14)
                                                                    node14.addChild(buildList {
                                                                        val tech15: Player.() -> Pair<() -> Unit, String>
                                                                        if (rareLeft.isEmpty())
                                                                            tech15 = commonLeft.random()
                                                                        else {
                                                                            tech15 = rareLeft.random()
                                                                            rareLeft.remove(tech15)
                                                                        }
                                                                        val node15 = TechNode(tech = tech15)
                                                                        add(node15)
                                                                        node15.addChild(buildList {
                                                                            val node16 =
                                                                                TechNode(tech = randomBulletUnitTech())
                                                                            add(node16)
                                                                            node16.addChild(buildList {
                                                                                val tech17: Player.() -> Pair<() -> Unit, String>
                                                                                var bakVoid = false
                                                                                if (epicTech[1] in epicLeft) {
                                                                                    epicLeft.remove(epicTech[1])
                                                                                    bakVoid = true
                                                                                }
                                                                                if (epicLeft.isEmpty())
                                                                                    tech17 = commonLeft.random()
                                                                                else {
                                                                                    tech17 = epicLeft.random()
                                                                                    epicLeft.remove(tech17)
                                                                                }
                                                                                if (bakVoid) epicLeft.add(epicTech[1])
                                                                                val node17 = TechNode(tech = tech17)
                                                                                add(node17)
                                                                                node17.addChild(buildList {
                                                                                    val tech18 = commonLeft.random()
                                                                                    val node18 =
                                                                                        TechNode(tech = tech18)
                                                                                    add(node18)
                                                                                    node18.addChild(buildList {
                                                                                        val tech19 =
                                                                                            commonLeft.random()
                                                                                        val node19 =
                                                                                            TechNode(tech = tech19)
                                                                                        add(node19)
                                                                                        node19.addChild(buildList {
                                                                                            val tech20: Player.() -> Pair<() -> Unit, String>
                                                                                            if (rareLeft.isEmpty())
                                                                                                tech20 =
                                                                                                    commonLeft.random()
                                                                                            else {
                                                                                                tech20 =
                                                                                                    rareLeft.random()
                                                                                                rareLeft.remove(
                                                                                                    tech20
                                                                                                )
                                                                                            }
                                                                                            val node20 =
                                                                                                TechNode(tech = tech20)
                                                                                            add(node20)
                                                                                            node20.addChild(
                                                                                                buildList {
                                                                                                    val node21 =
                                                                                                        TechNode(
                                                                                                            tech = randomBulletUnitTech()
                                                                                                        )
                                                                                                    add(node21)
                                                                                                    node21.addChild(
                                                                                                        buildList {
                                                                                                            val tech22 =
                                                                                                                commonLeft.random()
                                                                                                            val node22 =
                                                                                                                TechNode(
                                                                                                                    tech = tech22
                                                                                                                )
                                                                                                            add(node22)
                                                                                                            node22.addChild(
                                                                                                                buildList {
                                                                                                                    val tech23 =
                                                                                                                        commonLeft.random()
                                                                                                                    val node23 =
                                                                                                                        TechNode(
                                                                                                                            tech = tech23
                                                                                                                        )
                                                                                                                    add(node23)
                                                                                                                    node23.addChild(
                                                                                                                        buildList {
                                                                                                                            val tech24 =
                                                                                                                                commonLeft.random()
                                                                                                                            val node24 =
                                                                                                                                TechNode(
                                                                                                                                    tech = tech24
                                                                                                                                )
                                                                                                                            add(node24)
                                                                                                                            node24.addChild(
                                                                                                                                buildList {
                                                                                                                                    val tech25 =
                                                                                                                                        commonLeft.random()
                                                                                                                                    val node25 =
                                                                                                                                        TechNode(
                                                                                                                                            tech = tech25
                                                                                                                                        )
                                                                                                                                    add(node25)
                                                                                                                                    node25.addChild(
                                                                                                                                        buildList {
                                                                                                                                            val tech26 =
                                                                                                                                                commonLeft.random()
                                                                                                                                            val node26 =
                                                                                                                                                TechNode(
                                                                                                                                                    tech = tech26
                                                                                                                                                )
                                                                                                                                            add(node26)
                                                                                                                                            node26.addChild(
                                                                                                                                                buildList {
                                                                                                                                                    val tech27 =
                                                                                                                                                        commonLeft.random()
                                                                                                                                                    val node27 =
                                                                                                                                                        TechNode(
                                                                                                                                                            tech = tech27
                                                                                                                                                        )
                                                                                                                                                    add(node27)
                                                                                                                                                    node27.addChild(
                                                                                                                                                        buildList {
                                                                                                                                                            val tech28 =
                                                                                                                                                                commonLeft.random()
                                                                                                                                                            val node28 =
                                                                                                                                                                TechNode(
                                                                                                                                                                    tech = tech28
                                                                                                                                                                )
                                                                                                                                                            add(node28)
                                                                                                                                                            node28.addChild(
                                                                                                                                                                buildList {
                                                                                                                                                                    val tech29 =
                                                                                                                                                                        commonLeft.random()
                                                                                                                                                                    val node29 =
                                                                                                                                                                        TechNode(
                                                                                                                                                                            tech = tech29
                                                                                                                                                                        )
                                                                                                                                                                    add(node29)
                                                                                                                                                                    node29.addChild(
                                                                                                                                                                        buildList {
                                                                                                                                                                            val tech30: Player.() -> Pair<() -> Unit, String>
                                                                                                                                                                            if (legendLeft.isEmpty())
                                                                                                                                                                                tech30 =
                                                                                                                                                                                    commonLeft.random()
                                                                                                                                                                            else {
                                                                                                                                                                                tech30 =
                                                                                                                                                                                    legendLeft.random()
                                                                                                                                                                                legendLeft.remove(
                                                                                                                                                                                    tech30
                                                                                                                                                                                )
                                                                                                                                                                            }
                                                                                                                                                                            val node30 =
                                                                                                                                                                                TechNode(
                                                                                                                                                                                    tech = tech30
                                                                                                                                                                                )
                                                                                                                                                                            add(node30)
                                                                                                                                                                        })
                                                                                                                                                                })
                                                                                                                                                        })
                                                                                                                                                })
                                                                                                                                        })
                                                                                                                                })
                                                                                                                        })
                                                                                                                })
                                                                                                        })
                                                                                                })
                                                                                        })
                                                                                    })
                                                                                })
                                                                            })
                                                                        })
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    }
                                })
                            })
                        })
                    })
                })
            }
        })
        techLevelMap.add(techTree)
        fun TechNode.whileSetLevel(level: Int = this.level + 1) {
            child()?.forEach {
                it.level = level
                techLevelMap.add(it)
                it.whileSetLevel()
            }
        }
        techTree.whileSetLevel()
    }
    launch(Dispatchers.game) {
        delay(30_000L)
        if (sunsetMode) {
            val sunsetTeam = Team.all.filter { it.cores().size > 0 }.random()
            fun sunsetCores(): List<CoreBuild> {
                return sunsetTeam.cores().toList().filter { it.cityData().cityType == CityTypes.Sunset }
            }
            Call.sendMessage(
                """
                [white]\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
                [#${sunsetTeam.color}]${sunsetTeam.name} 被选中启动了落日计划！
                
                [white]直至此队伍被消灭 或 落日计划成功
                [white]每隔30s此队伍其中一个核心将会变为落日余晖
                [white]当落日之辉闪耀到一半的核心之时 落日计划正式启动
                [white]本局科研难度减半！
                [white]\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
            """.trimIndent()
            )
            TechDifficult /= 2
            val teams = Team.all.toList().filter { it != sunsetTeam }
            var boolean = false
            teams.forEach {
                it.techData().friendlyTeams.addAll(teams)
                it.techData().knowOtherTeamInfo = Long.MAX_VALUE
            }
            delay(30_000L)
            while (true) {
                var cores = 0f
                state.teams.getActive().forEach {
                    cores += it.cores.size
                }
                if (sunsetTeam.cores().size > 0) {
                    sunsetTeam.cores().random().cityData().cityType = CityTypes.Sunset
                    Call.sendMessage(
                        """
                    [white]\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
                    [orange]出现了一处新的落日余晖
                    ${sunsetCores().size}/${cores.toInt()}核心已在落日之辉闪耀之下
                    [white]\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
                """.trimIndent()
                    )
                } else {
                    break
                }
                if (sunsetTeam.cores().size > cores / 2) {
                    Call.sendMessage(
                        """
                    [white]\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
                    [#${sunsetTeam.color}]${sunsetTeam.name} 的落日计划正式启动！
                    
                    [white]所有城市变为随机一种负面效果城市
                    [white]此队伍随机一座城市成为落日之光！
                    [white]\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
                    """.trimIndent()
                    )
                    state.teams.getActive().forEach {
                        it.cores.forEach {
                            it.cityData().cityType = CityTypes.moon().random()
                        }
                    }
                    sunsetTeam.cores().random().apply {
                        cityData().cityType = CityTypes.SunsetSuper
                        maxHealth *= 10
                        health *= 10
                    }
                    Groups.player.forEach {
                        it.achievement("[green][落日 - 启动成功]", 50)
                    }
                    boolean = true
                    break
                }
                delay(30_000)
            }
            teams.forEach {
                it.techData().friendlyTeams.removeAll(teams)
            }
            if (!boolean) {
                Call.sendMessage(
                    """
                    [white]\\\\\\\\\\\\\\\\\\\
                    [#${sunsetTeam.color}]${sunsetTeam.name} 的落日计划失败！
                    [white]\\\\\\\\\\\\\\\\\\\
                """.trimIndent()
                )
                Groups.player.forEach {
                    it.achievement("[green][落日 - 启动失败]", 50)
                }
            }
        }
    }
    loop(Dispatchers.game) {
        val cappedUnits = buildList {
            add(UnitTypes.mono)
            add(UnitTypes.flare)
            add(UnitTypes.poly)
            add(UnitTypes.mega)
            add(UnitTypes.pulsar)
            add(UnitTypes.quasar)
        }
        state.teams.getActive().forEach { t ->
            cappedUnits.forEach a@{
                if (t.getUnits(it) == null) return@a
                val units = t.getUnits(it).toMutableList().filter { it.owner() == null }
                if (units.size >= 80) {
                    val u = units.filter { !it.isPlayer }.random()
                    u.kill()
                    u.elevation = 0f
                }
            }
        }
        delay(50L)
    }
    loop(Dispatchers.game) {
        var cores = 0f
        state.teams.getActive().forEach {
            cores += it.cores.size
        }
        state.teams.getActive().forEach { it ->
            val rate = it.team.coins() * 0.001f * (0.5f - it.cores.size / cores) * 2f
            it.team.addCoin(
                if (it.team.techData().hedgeFund) abs(rate.toInt()) * 5 else rate.toInt()
                    .coerceAtLeast(if (it.team.techData().bankNoRemoveCoins) 0 else Int.MIN_VALUE)
            )
            if (it.team.coins() < 0) it.team.setCoin(0)
            it.cores.forEach { c ->
                if (c.cityData().cityType.name == "[white]均衡城市" && Random.nextFloat() <= 0.001 && it.team.techData().autoSetCityType) {
                    val type =
                        arrayOf(CityTypes.War, CityTypes.Economic, CityTypes.Logistics, CityTypes.ResearchI).random()
                    c.cityData().cityType = type
                }
                val lord = Groups.player.filter { p -> p.uuid() in c.lord() && p.team() == c.team }
                var amount = c.level() * max(
                    (Time.timeSinceMillis(startTime) / 1000 / 60 / 3 * c.cityData().cityType.coinsMultiplier).toInt(),
                    1
                )
                val baseAmount = c.level() * max((Time.timeSinceMillis(startTime) / 1000 / 60 / 3).toInt(), 1)
                if (lord.isNotEmpty()) {
                    amount /= 2
                    val lordAmount = amount / lord.size
                    lord.forEach { p ->
                        p.addCoin(lordAmount, true)
                    }
                }
                val expAmount =
                    (c.cityData().cityType.researchMultiplier - 1f) * it.team.techData().level * c.level() / 3f + c.level()
                it.team.techData().exp += expAmount
                c.addCoin(amount)
                c.cityData().population += (c.level() * Random.nextFloat() * (1f + c.coins() / (c.maxHealth * 0.4f)) * (1f + (1f - c.cityData().cityType.unitCostMultiplier) * 5f).coerceAtLeast(
                    1f
                )).toInt().coerceAtLeast(1).coerceAtMost(
                    (((c.level() + 1f).pow(3)) * (1f + (1f - c.cityData().cityType.unitCostMultiplier) * 5f).coerceAtLeast(
                        1f
                    )).toInt() - c.cityData().population
                )
                val allyText = buildString {
                    appendLine(
                        "[#${c.team.color}]${Iconc.blockCliff}${c.coins()} ${Iconc.players}${c.cityData().population}/${
                            ((c.level() + 1f).pow(
                                3
                            ) * (1f + (1f - c.cityData().cityType.unitCostMultiplier) * 5f).coerceAtLeast(1f)).toInt()
                        }"
                    )
                    append("[white]${amount.levelText()}[#${c.team.color}]${Iconc.blockCliff}$amount($baseAmount)/s")
                    appendLine(" [white]| [sky]${Iconc.teamSharded}${expAmount.format(1)}/s")
                    lord.forEach {
                        appendLine("[white]${it.name}")
                    }
                    append("[white]")
                    append(c.levelText())
                    append("[white] | ")
                    append(c.cityData().cityType.name)
                }
                val enemyText = buildString {
                    append("[#${c.team.color}]${Iconc.blockCliff}${"?".repeat(c.coins().toString().length)} ")
                    repeat((c.health / c.maxHealth * 10).toInt()) {
                        append("|")
                    }
                    appendLine("")
                    appendLine("[white]${amount.levelText()}[#${c.team.color}]$amount/s")
                    append("[white]")
                    append(c.levelText(true))
                }
                Groups.player.filter { p ->
                    (p.within(c.x, c.y, 60f * 8f)
                            || (world.tileWorld(p.mouseX, p.mouseY) != null
                            && world.tileWorld(p.mouseX, p.mouseY).within(c.x, c.y, 30f * 8f)))
                            && fogControl.isVisible(p.team(), c.x, c.y)
                            || p in lord
                }.forEach { p ->
                    Call.label(
                        p.con,
                        if (p.team() == it.team || p.team()
                                .techData().knowOtherTeamInfo >= Time.millis()
                        ) allyText else enemyText,
                        1.013f,
                        c.x,
                        c.y
                    )
                }
                Units.nearby(null, c.x, c.y, 20 * 8f) { u ->
                    if (u.team == c.team && u.health < u.maxHealth) {
                        u.health += u.maxHealth / 100 * c.cityData().cityType.healMultiplier * c.level() / 6
                        u.clampHealth()
                        Call.transferItemEffect(Items.plastanium, c.x, c.y, u)
                    }
                }
            }
        }
        delay(1000)
    }
    loop(Dispatchers.game) {
        Team.all.forEach { team ->
            if (team.techData().teamRespawn && !team.techData().teamRespawnUsed && team.data()
                    .noCores() && !state.gameOver
            ) {
                while (!team.cores().isEmpty) {
                    delay(50L)
                }
                team.rules().blockHealthMultiplier *= 5f
                Call.setRules(state.rules)
                launch(Dispatchers.game) {
                    delay(120_000)
                    team.rules().blockHealthMultiplier /= 5f
                    Call.setRules(state.rules)
                    Call.sendMessage("\n[#${team.color}]${team.name}[white]的涅槃重生血量增幅已经结束\n")
                }
                Call.sendMessage(
                    """
                                            
                                            ------------------------------------------------
                                            [#${team.color}]${team.name} [purple]史诗科技[#${team.color}] 涅槃重生 触发!
                                            
                                            [white]全场随机挑选核心重生!
                                            全队成员和队伍银行金钱翻倍!
                                            
                                            队伍建筑血量 ${(team.rules().blockHealthMultiplier / 5f).format()} -> ${team.rules().blockHealthMultiplier.format()}
                                            持续120s!
                                            ------------------------------------------------
                                            
                                        """.trimIndent()
                )
                Groups.player.forEach {
                    if (it.team() == team)
                        it.addCoin(it.coins())
                }
                team.addCoin(team.coins())
                val core = Groups.build.toList().filter { it.block is CoreBlock }.random()
                val target = when (core.block) {
                    Blocks.coreShard -> Blocks.coreFoundation
                    Blocks.coreFoundation -> Blocks.coreBastion
                    Blocks.coreBastion -> Blocks.coreNucleus
                    Blocks.coreNucleus -> Blocks.coreCitadel
                    Blocks.coreCitadel -> Blocks.coreAcropolis
                    Blocks.coreAcropolis -> Blocks.coreAcropolis
                    else -> Blocks.coreShard
                }
                val tile = core.tile
                tile.setNet(target, team, 0)
                (tile.build as CoreBuild).cityData().cityType = CityTypes.Respawn
                team.techData().teamRespawnUsed = true
                Call.logicExplosion(team, tile.worldx(), tile.worldy(), 40f * 8f, 1500f, true, true, true, false)
                Call.soundAt(Sounds.wind3, tile.worldx(), tile.worldy(), 114514f, 0f)
                Call.effect(Fx.impactReactorExplosion, tile.worldx(), tile.worldy(), 0f, team.color)
            }
        }
        state.teams.getActive().forEach { t ->
            val pads = t.pads()
            val accelerators = Groups.build.toList().filter { it.block is Accelerator && it.team == t.team }
            accelerators.forEach {
                (it as AcceleratorBuild)
                if (it.full()) {
                    Call.effect(Fx.shieldWave, it.x, it.y, 0f, t.team.color)
                    Call.effect(Fx.padlaunch, it.x, it.y, 0f, t.team.color)
                }
                Call.label(
                    """
                    [#${it.team.color}]${if (it.full()) "超级武器充能完毕！" else "超级武器充能..."}
                    [white]${Iconc.itemCopper} ${it.items.get(Items.copper)}/${(it.block as Accelerator).launchBlock.requirements[0].amount}
                    [white]${Iconc.itemLead} ${it.items.get(Items.lead)}/${(it.block as Accelerator).launchBlock.requirements[1].amount}
                """.trimIndent(), 1.013f, it.x, it.y
                )
            }
            pads.forEach padsForEach@{
                val pad = (it as LaunchPad.LaunchPadBuild)
                var accelerator = accelerators.toList().firstOrNull { !(it as AcceleratorBuild).full(Items.copper) }
                if (pad.items.has(Items.copper) && accelerator != null) {
                    val amount = pad.items.get(Items.copper)
                    Call.setItem(pad, Items.copper, 0)
                    Call.setItem(accelerator, Items.copper, accelerator.items.get(Items.copper) + amount)
                }
                accelerator = accelerators.toList().firstOrNull { !(it as AcceleratorBuild).full(Items.lead) }
                if (pad.items.has(Items.lead) && accelerator != null) {
                    val amount = pad.items.get(Items.lead)
                    Call.setItem(pad, Items.lead, 0)
                    Call.setItem(accelerator, Items.lead, accelerator.items.get(Items.lead) + amount)
                }
                if (!pad.padData().showInfo) return@padsForEach
                val allyText = buildString {
                    appendLine("[#${t.team.color}]${if (pad.padData().name != "") pad.padData().name else "(${pad.tileX()},${pad.tileY()})"}")
                    val targetPad = pad.padData().targetPad
                    appendLine("[#${t.team.color}]传送目标 ${targetPad?.padData()?.name ?: ""}")
                    if (targetPad == null) {
                        append("[lightgray]未配置")
                    } else if (!targetPad.isValid) {
                        append("[lightgray]失效")
                    } else {
                        append("[white]${Iconc.blockLaunchPad}(${targetPad.tileX()},${targetPad.tileY()})${Iconc.blockLaunchPad}")
                    }
                }
                val enemyText = buildString {
                    appendLine("${Iconc.blockLaunchPad}[#${t.team.color}][white](${pad.tileX()},${pad.tileY()})[#${t.team.color}]${Iconc.blockLaunchPad}")
                    appendLine("[#${t.team.color}]传送目标")
                    append("[lightgray]未知")
                }
                Groups.player.filter { p ->
                    (p.within(pad.x, pad.y, 60f * 8f)
                            || (world.tileWorld(p.mouseX, p.mouseY) != null
                            && world.tileWorld(p.mouseX, p.mouseY).within(pad.x, pad.y, 30f * 8f)))
                            && fogControl.isVisible(p.team(), pad.x, pad.y)
                }.forEach { p ->
                    Call.label(
                        p.con,
                        if (p.team() == it.team || p.team()
                                .techData().knowOtherTeamInfo >= Time.millis()
                        ) allyText else enemyText,
                        1.013f,
                        pad.x,
                        pad.y
                    )
                }
            }
        }
        delay(1000)
    }
    loop(Dispatchers.game) {
        Groups.player.forEach {
            val text = buildString {
                appendLine("[#${it.team().color}]金币:${Iconc.blockCliff}${it.coins()}")
                appendLine("军团等级:${it.unitType().level()}")
                appendLine(
                    "单位上限:${
                        Groups.unit.toList().filter { u -> u.owner() == it.uuid() }.size
                    }/${it.unitCap()}"
                )
                if (!it.checkCooldown())
                    append("[red]收取资源冷却时间:${cooldown[it.uuid()] / 1000 - Time.timeSinceMillis(startTime) / 1000}s")
                else
                    append("[green]收取资源冷却完毕")
                if (it.unitType().level() == 5)
                    if (!it.checkLordCooldown())
                        append(
                            "\n[red]领主降临冷却时间:${
                                playerLordCooldown[it.uuid()] / 1000 - Time.timeSinceMillis(
                                    startTime
                                ) / 1000
                            }s"
                        )
                    else
                        append("\n[green]领主降临冷却完毕")
                if (it.unitType().level() > 0) {
                    appendLine("[white]")
                    append("军团类型:${it.unitType()?.emoji()}")
                    if (it.unitType().level() >= 5 && it.lordUnitType() != null)
                        append("领主类型:${it.lordUnitType()?.emoji()}")
                }
            }
            Call.setHudText(it.con, text)

            if (it.lastTeam() != it.team() && it.lastTeam().data().hasCore()) {
                it.lastTeam().addCoin(it.coins())
                it.removeCoin(it.coins())
            }
            it.lastTeam(it.team())
        }
        delay(100)
    }
}

onDisable {
    coroutineContext.cancelChildren()
}

suspend fun Player.megaStructuresMenu(launcher: VariableReactorBuild) {
    sendMenuBuilder<Unit>(
        this, 900_000, "[cyan]巨构建造器",
        """
            [cyan]■ - 已升空
            [green]■ - 可建造
            [yellow]■ - 发射中
            [red]■ - 材料/前置不足
        """.trimIndent()
    ) {
        MegaStructures.all().forEach {
            if (team().techData().megaStructures.size + team().techData().megaStructureQueue.size >= (if (team().techData().infMS) 999 else 1) && it !in team().techData().megaStructures && it !in team().techData().megaStructureQueue) {
                add(listOf("[red]巨构到达上限！" to {}))
            } else {
                add(listOf("${it.status(team())}${it.name}" to { megaStructuresInfoMenu(launcher, it) }))
            }
        }
    }
}

suspend fun Player.megaStructuresInfoMenu(launcher: VariableReactorBuild, megaStructure: MegaStructure) {
    sendMenuBuilder<Unit>(
        this, 900_000, megaStructure.name,
        buildString {
            appendLine(megaStructure.desc)
            appendLine("[white]")
            megaStructure.requirements.forEach { (t, u) ->
                appendLine("${t.emoji()} ${core().items.get(t)}/$u")
            }
            append("[cyan]构建并发射需要${(megaStructure.cost() / 1000f).format()}s!")
        }
    ) {
        add(listOf("${megaStructure.status(team())}发射！" to {
            if (megaStructure.status(team()) == "[green]■" && launcher.isValid && !launcher.launching()) {
                val team = team()
                launch(Dispatchers.game) {
                    Call.sendMessage(
                        """
                    [white]------------------------------------------------
                    ${name()}[#${team.color}]正在发射${megaStructure.name}
                        
                    [white]${megaStructure.desc}
                        
                    [red]将会在${(megaStructure.cost() / 1000f).format()}s内彻底升空！
                    击碎在那的通量反应堆停止发射！
                    [white]------------------------------------------------    
                    """.trimIndent().replace(" ", "")
                    )
                    Call.sendMessage("[#eab678ff]<Mark>[white](${launcher.tileX()},${launcher.tileY()})")

                    megaStructure.requirements.forEach { (t, u) ->
                        team.core().items.remove(t, u)
                    }

                    launching[launcher] = true

                    team.techData().megaStructureQueue.add(megaStructure)
                    val launchTime = Time.millis()
                    var result = true
                    while (Time.timeSinceMillis(launchTime) < megaStructure.cost()) {
                        if (!launcher.isValid) {
                            result = false
                            break
                        }
                        Call.label(
                            "${megaStructure.name}[#${team.color}]正在发射！\n${(Time.timeSinceMillis(launchTime) / 1000f).format()}s/${(megaStructure.cost() / 1000f).format()}s",
                            0.7026f,
                            launcher.x,
                            launcher.y
                        )
                        Call.effect(Fx.spawnShockwave, launcher.x, launcher.y, 0f, team().color)
                        delay(500L)
                    }
                    if (result) {
                        Call.sendMessage(
                            """
                        [white]------------------------------------------------
                        ${name()}[#${team.color}]发射${megaStructure.name}成功！
                            
                        [white]${megaStructure.desc}
                            
                        [green]此效果将会永久持续！
                        [white]------------------------------------------------    
                        """.trimIndent().replace(" ", "")
                        )
                        achievement("[purple][轨道巨构发射成功！]", 200, true)
                        team.techData().megaStructures.add(megaStructure)
                        megaStructure.activeEffect?.invoke(team)
                    } else {
                        Call.sendMessage("[#${team.color}]正在发射的${megaStructure.name}确定发射失败！")
                    }
                    team.techData().megaStructureQueue.remove(megaStructure)
                    launching[launcher] = false
                }
            } else {
                sendMessage("[red]发射失败")
            }
        }))
        add(listOf("取消" to {}))
    }
}

suspend fun Player.launchPadMenu(pad: LaunchPad.LaunchPadBuild) {
    val pads = team().data().pads()
    sendMenuBuilder<Unit>(
        this,
        900_000,
        "[green]发射台页面 [#${pad.team.color}]${pad.padData().name}\n[cyan]发射台总数[yellow]${pads.size}",
        """
            [cyan]当前拥有金币[#${team().color}]${Iconc.blockCliff}${coins()}
            [cyan]当前科技等级[#${team().color}]${team().techData().level}
            [cyan]各种发射台连锁效果！
        """.trimIndent()
    ) {
        val targetPad = pad.padData().targetPad
        this += listOf(
            if (pads.size >= 2) {
                if (targetPad != null) {
                    if (targetPad.isValid) {
                        "[cyan]目标已经选定！\n[white]${Iconc.blockLaunchPad}(${targetPad.tileX()},${targetPad.tileY()})${Iconc.blockLaunchPad} [#${targetPad.team.color}]${targetPad.padData().name}"
                    } else {
                        "[lightgray]目标失效！点击重新选择目标"
                    }
                } else {
                    "[lightgray]未配置！点击重新选择目标"
                } to {
                    sendMenuBuilder<Unit>(
                        this@launchPadMenu, 900_000, "[green]发射台页面\n[cyan]发射台总数[yellow]${pads.size}",
                        """
                           [cyan]选择发射台目标
                        """.trimIndent()
                    ) {
                        pads.filter { it != pad }
                            .sortedBy { if ((it as LaunchPad.LaunchPadBuild).padData().showInfo) 0 else 1 }.forEach {
                            add(listOf("${if ((it as LaunchPad.LaunchPadBuild).padData().showInfo) "[white]" else "[lightgray]"}${Iconc.blockLaunchPad}(${it.tileX()},${it.tileY()})${Iconc.blockLaunchPad} ${if (it.padData().showInfo) "[#${it.team.color}]" else "[lightgray]"}${it.padData().name}" to {
                                pad.padData().targetPad = it
                            }))
                        }
                        this += listOf(
                            "取消" to {}
                        )
                    }
                    launchPadMenu(pad)
                }
            } else {
                "[lightgray]队伍发射台数量不足！" to { launchPadMenu(pad) }
            })
        playerInputing[uuid()] = false
        this += listOf(
            "[cyan]命名发射台！" to {
                val playerLastText = playerLastSendText[uuid()]
                sendMessage("------------\n[yellow]请输入此发射台改名名称(最大七个字)\n[white]------------")
                val startInputTime = Time.millis()
                var fail = true
                var newName = "- "
                playerInputing[uuid()] = true
                while (Time.timeSinceMillis(startInputTime) / 1000 <= 15) {
                    if (playerInputing[uuid()] == false) break
                    if (playerLastText != playerLastSendText[uuid()] && playerLastSendText[uuid()] != null) {
                        if (playerLastSendText[uuid()] == pad.padData().name || playerLastSendText[uuid()]!!.length > 7) {
                            break
                        }
                        newName += playerLastSendText[uuid()]
                        pad.padData().name = newName
                        fail = false
                        break
                    }
                    delay(50L)
                }
                if (fail) {
                    sendMessage("命名失败！")
                } else {
                    sendMessage("命名成功！现在此发射台名为：${playerLastSendText[uuid()]}")
                }
                playerLastSendText[uuid()] = ""
            },
            "${if (pad.padData().showInfo) "[green]" else "[red]"}发射台信息显示" to {
                pad.padData().showInfo = !pad.padData().showInfo
                launchPadMenu(pad)
            }
        )
        if (targetPad != null && targetPad.isValid) {
            this += listOf("[cyan]传送你附近的军团单位至目标发射台！\n[red]可能会有副作用！" to {
                Call.effect(Fx.teleport, pad.x, pad.y, 0f, team().color)
                Units.nearby(team(), pad.x, pad.y, itemTransferRange) {
                    if (it.owner() == uuid() || it == unit())
                        launch(Dispatchers.game) {
                            var times = 0
                            while (true) {
                                Tmp.v1.rnd(Random.nextFloat() * itemTransferRange / 2)

                                var sx = targetPad.x + Tmp.v1.x
                                var sy = targetPad.y + Tmp.v1.y

                                if (it.canPass(World.toTile(sx), World.toTile(sy))) {
                                    while (!it.within(targetPad.x, targetPad.y, itemTransferRange)) {
                                        it.set(sx, sy)
                                        it.snapInterpolation()
                                        delay(50L)
                                    }
                                    Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                    break
                                }
                                if (++times > 20) {
                                    sx = targetPad.x
                                    sy = targetPad.y
                                    while (!it.within(targetPad.x, targetPad.y, itemTransferRange)) {
                                        it.set(sx, sy)
                                        it.snapInterpolation()
                                        delay(50L)
                                    }
                                    Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                    break
                                }
                            }
                            if (!it.team.techData().tpNoDeBuff) {
                                it.apply(StatusEffects.unmoving, (it.speed() / 2f) * 5f * 60f)
                                it.apply(StatusEffects.disarmed, (it.speed() / 2f) * 3.5f * 60f)
                                it.apply(StatusEffects.slow, (it.speed() / 2f) * 10.5f * 60f)
                            }
                            if (it == unit()) Call.setCameraPosition(con, targetPad.x, targetPad.y)
                        }
                }
                Call.effect(Fx.teleportActivate, targetPad.x, targetPad.y, 0f, team().color)
            })
            if (MegaStructures.airDrop in team().techData().megaStructures) {
                this += listOf("[cyan]传送你附近的军团单位至指定目标点！\n[red]会有强大的副作用" to {
                    launch(Dispatchers.game) {
                        val team = team()
                        playerTapping[uuid()] = true
                        val startTappingTime = Time.millis()
                        var fail = false
                        sendMessage("[yellow]对目标点点击4次来传送至目标点\n[lightgray]再次点击发射台取消")
                        while ((playerLastTapTile[uuid()]?.second ?: 0) < 4) {
                            if (Time.timeSinceMillis(startTappingTime) >= 30_000) {
                                sendMessage("[red]选择超时")
                                fail = true
                                break
                            }
                            if (playerTapping[uuid()] == false || !pad.isValid || pad.team != team()) {
                                sendMessage("[red]选择取消")
                                fail = true
                                break
                            }
                            delay(50L)
                        }
                        playerTapping[uuid()] = false
                        val tile = playerLastTapTile[uuid()]!!.first
                        if (!fogControl.isVisibleTile(team(), tile.x.toInt(), tile.y.toInt())) {
                            sendMessage("[red]指向性传送需要拥有视野！")
                            fail = true
                        }
                        if (fail) return@launch
                        sendMessage("[green]选定完成..正在传送 再次点击发射台取消传送")
                        launch(Dispatchers.game) b@{
                            val units = buildList {
                                Units.nearby(team(), pad.x, pad.y, itemTransferRange) {
                                    add(it)
                                    if (it.owner() == uuid() || it == unit())
                                        it.apply(StatusEffects.unmoving, 10 * 60f)
                                }
                            }
                            repeat(10) {
                                Call.effect(Fx.teleport, pad.x, pad.y, 0f, team().color)
                                Call.effect(Fx.teleport, tile.worldx(), tile.worldy(), 0f, team().color)
                                if (playerTapping[uuid()] == true || playerLastTapTile[uuid()]?.first?.build is LaunchPadBuild || team != team()) {
                                    sendMessage("[red]取消传送！")
                                    return@b
                                }
                                delay(1_000)
                            }
                            units.forEach {
                                if (it.owner() == uuid() || it == unit())
                                    launch(Dispatchers.game) {
                                        var times = 0
                                        while (true) {
                                            Tmp.v1.rnd(Random.nextFloat() * itemTransferRange / 2)

                                            var sx = tile.worldx() + Tmp.v1.x
                                            var sy = tile.worldy() + Tmp.v1.y

                                            if (it.canPass(World.toTile(sx), World.toTile(sy))) {
                                                while (!it.within(tile.worldx(), tile.worldy(), itemTransferRange)) {
                                                    it.set(sx, sy)
                                                    it.snapInterpolation()
                                                    delay(50L)
                                                }
                                                Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                                break
                                            }
                                            if (++times > 20) {
                                                sx = tile.worldx()
                                                sy = tile.worldy()
                                                while (!it.within(tile.worldx(), tile.worldy(), itemTransferRange)) {
                                                    it.set(sx, sy)
                                                    it.snapInterpolation()
                                                    delay(50L)
                                                }
                                                Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                                break
                                            }
                                        }

                                        val multiplier = if (it.team.techData().tpNoDeBuff) 1 else 4
                                        it.apply(StatusEffects.unmoving, (it.speed() / 2f) * 5f * 60f * multiplier)
                                        it.apply(StatusEffects.disarmed, (it.speed() / 2f) * 3.5f * 60f * multiplier)
                                        it.apply(StatusEffects.slow, (it.speed() / 2f) * 10.5f * 60f * multiplier)
                                        Call.effect(Fx.teleportActivate, tile.worldx(), tile.worldy(), 0f, team().color)
                                        if (it == unit()) Call.setCameraPosition(con, tile.worldx(), tile.worldy())
                                    }
                            }
                        }
                    }
                })
            }
        }
        this += listOf(
            "取消" to {}
        )
    }
}

fun AcceleratorBuild.full(): Boolean {
    return (block as Accelerator).launchBlock.requirements.all { items.get(it.item) >= it.amount }
}

fun AcceleratorBuild.full(item: Item): Boolean {
    return items.get(item) >= (block as Accelerator).launchBlock.requirements[item.id.toInt()].amount
}

suspend fun Player.superWeaponsMenu(accelerator: AcceleratorBuild) {
    sendMenuBuilder<Unit>(
        this, 900_000, "[cyan]超级武器",
        """
            [red]指向性超级武器需要视野！
            [white]${Iconc.itemCopper} ${accelerator.items.get(Items.copper)}/${(accelerator.block as Accelerator).launchBlock.requirements[0].amount}
            [white]${Iconc.itemLead} ${accelerator.items.get(Items.lead)}/${(accelerator.block as Accelerator).launchBlock.requirements[1].amount}
        """.trimIndent()
    ) {
        playerTapping[uuid()] = false
        if (accelerator.full()) {
            add(buildList {
                if (Random(gameSeed + 1).nextFloat() <= 0.5f && team().techData().void()) {
                    add(
                        "[navy]虚空全知\n[white]可查看其他队伍的城市与传送台情报！\n持续1200s" to {
                            if (accelerator.isValid && accelerator.team == team()) {
                                Call.sendMessage(
                                    """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-虚空全知
                                    
                                    [white]可查看其他队伍的城市与传送台情报！
                                    持续1200s
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                                )
                                team().techData().knowOtherTeamInfo =
                                    if (team().techData().knowOtherTeamInfo <= Time.millis()) Time.millis() + 1200_000 else team().techData().knowOtherTeamInfo + 1200_000
                                accelerator.kill()
                                achievement("[green][虚空全知！]", 100, true)
                            }
                        }
                    )
                } else {
                    if (state.rules.fog) {
                        add(
                            "[cyan]雾散\n[white]关闭迷雾,对所有人生效\n[green]仅消耗一半充能" to {
                                if (accelerator.isValid && accelerator.team == team()) {
                                    state.rules.fog = false
                                    Call.sendMessage(
                                        """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-雾散
                                    
                                    [white]战争迷雾散去！
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                                    )
                                    Call.setRules(state.rules)
                                    Call.setItem(accelerator, Items.copper, accelerator.items.get(Items.copper) / 2)
                                    Call.setItem(accelerator, Items.lead, accelerator.items.get(Items.lead) / 2)
                                }
                            }
                        )
                    } else {
                        add(
                            "[cyan]雾起\n[white]开启迷雾,对所有人生效\n[green]仅消耗一半充能" to {
                                if (accelerator.isValid && accelerator.team == team()) {
                                    state.rules.fog = true
                                    Call.sendMessage(
                                        """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-雾起
                                    
                                    [white]战争迷雾笼罩！
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                                    )
                                    Call.setRules(state.rules)
                                    Call.setItem(accelerator, Items.copper, accelerator.items.get(Items.copper) / 2)
                                    Call.setItem(accelerator, Items.lead, accelerator.items.get(Items.lead) / 2)
                                }
                            }
                        )
                    }
                }
            })
            add(buildList {
                if (Random(gameSeed + 2).nextFloat() <= 0.5f && team().techData().void()) {
                    add(
                        "[navy]虚空放逐\n[white]所有敌方队伍单位全图随机传送\n无敌10s缴械45s" to {
                            if (accelerator.isValid && accelerator.team == team()) {
                                state.teams.getActive().forEach {
                                    if (it.team == team()) return@forEach
                                    it.units.forEach {
                                        launch(Dispatchers.game) {
                                            var times = 0
                                            var sx: Float
                                            var sy: Float
                                            while (true) {
                                                sx = Random.nextInt(0, world.width()) * 8f
                                                sy = Random.nextInt(0, world.height()) * 8f

                                                if (it.canPass(World.toTile(sx), World.toTile(sy))) {
                                                    while (!it.within(sx, sy, itemTransferRange)) {
                                                        it.set(sx, sy)
                                                        delay(50L)
                                                    }
                                                    Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                                    break
                                                }
                                                if (++times > 20) {
                                                    break
                                                }
                                            }
                                            it.apply(StatusEffects.invincible, 10f * 60f)
                                            it.apply(StatusEffects.disarmed, 45f * 60f)
                                            if (it.isPlayer) Call.setCameraPosition(it.player.con, sx, sy)
                                        }
                                    }
                                }
                                Call.sendMessage(
                                    """
                                            
                                            ------------------------------------------------
                                            ${name()}[#${team().color}]使用了超级武器-虚空放逐
                                            
                                            [white]所有敌方队伍单位全图随机传送
                                            无敌10s
                                            缴械45s
                                            ------------------------------------------------
                                            
                                        """.trimIndent()
                                )
                                accelerator.kill()
                                achievement("[green][虚空放逐！]", 100, true)
                            }
                        }
                    )
                } else {
                    add(
                        "[cyan]挪移\n[white]所有敌方队伍单位回到最近核心附近\n无法移动并缴械60s" to {
                            if (accelerator.isValid && accelerator.team == team()) {
                                state.teams.getActive().forEach {
                                    if (it.team != team()) {
                                        it.units.forEach {
                                            launch(Dispatchers.game) {
                                                var times = 0
                                                val core = it.closestCore()
                                                while (true) {
                                                    Tmp.v1.rnd(Random.nextFloat() * itemTransferRange / 2)

                                                    var sx = core.x + Tmp.v1.x
                                                    var sy = core.y + Tmp.v1.y

                                                    if (it.canPass(World.toTile(sx), World.toTile(sy))) {
                                                        while (!it.within(core.x, core.y, itemTransferRange)) {
                                                            it.set(sx, sy)
                                                            delay(50L)
                                                        }
                                                        Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                                        break
                                                    }
                                                    if (++times > 20) {
                                                        sx = core.x
                                                        sy = core.y
                                                        while (!it.within(core.x, core.y, itemTransferRange)) {
                                                            it.set(sx, sy)
                                                            delay(50L)
                                                        }
                                                        Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                                        break
                                                    }
                                                }
                                                it.apply(StatusEffects.unmoving, 60f * 60f)
                                                it.apply(StatusEffects.disarmed, 60f * 60f)
                                                if (it.isPlayer) Call.setCameraPosition(it.player.con, core.x, core.y)
                                            }
                                        }
                                    }
                                }
                                Call.sendMessage(
                                    """
                                            
                                            ------------------------------------------------
                                            ${name()}[#${team().color}]使用了超级武器-挪移
                                            
                                            [white]所有敌方队伍单位回到最近核心附近
                                            无法移动并缴械60s
                                            ------------------------------------------------
                                            
                                        """.trimIndent()
                                )
                                accelerator.kill()
                                achievement("[green][挪移！]", 100, true)
                            }
                        }
                    )
                }
            })
            add(buildList {
                if (Random(gameSeed + 3).nextFloat() <= 0.5f && team().techData().void()) {
                    add(
                        "[navy]虚空领域\n[white]进入你队伍的核心禁造区将会每秒受到最大生命12%伤害\n持续240s" to {
                            if (accelerator.isValid && accelerator.team == team()) {
                                Call.sendMessage(
                                    """
                                        
                                        ------------------------------------------------
                                        ${name()}[#${team().color}]使用了超级武器-虚空领域
                                        
                                        [white]进入此队伍的核心禁造区将会每秒受到最大生命12%伤害
                                        持续240s
                                        ------------------------------------------------
                                        
                                    """.trimIndent()
                                )
                                launch(Dispatchers.game) {
                                    //防止玩家换队..然后下面的显示就错啦
                                    val team = team()

                                    //love from pvpProtect(
                                    fun mindustry.gen.Unit.inEnemyArea(): Boolean {
                                        val closestCore = state.teams.active
                                            .mapNotNull { it.cores.minByOrNull(this::dst2) }
                                            .minByOrNull(this::dst2) ?: return false
                                        return team != team() && closestCore.team == team && (state.rules.polygonCoreProtection || dst(
                                            closestCore
                                        ) < state.rules.enemyCoreBuildRadius)

                                    }

                                    val lockDownStartTime = Time.millis()
                                    while (Time.timeSinceMillis(lockDownStartTime) <= 240_000) {
                                        Groups.unit.forEach {
                                            if (it.inEnemyArea()) {
                                                if (it.isPlayer)
                                                    Call.infoToast(
                                                        it.player.con,
                                                        "[red]你已经进入虚空领域！\n在你离开之前每秒受到最大生命12%伤害",
                                                        1.013f
                                                    )
                                                it.health -= it.maxHealth * 0.12f
                                            }
                                        }
                                        delay(1000)
                                    }
                                    Call.sendMessage("\n[#${team.color}]${team.name}[white]的虚空领域已经失效\n")
                                }
                                accelerator.kill()
                                achievement("[green][虚空领域！]", 100, true)
                            }
                        }
                    )
                } else {
                    add(
                        "[cyan]封锁\n[white]进入你队伍的核心禁造区将会获得获得大量debuff10s\n持续240s" to {
                            if (accelerator.isValid && accelerator.team == team()) {
                                Call.sendMessage(
                                    """
                                        
                                        ------------------------------------------------
                                        ${name()}[#${team().color}]使用了超级武器-封锁
                                        
                                        [white]进入此队伍的核心禁造区将会获得大量debuff10s,持续240s
                                        ------------------------------------------------
                                        
                                    """.trimIndent()
                                )
                                launch(Dispatchers.game) {
                                    //防止玩家换队..然后下面的显示就错啦
                                    val team = team()

                                    //love from pvpProtect(
                                    fun mindustry.gen.Unit.inEnemyArea(): Boolean {
                                        val closestCore = state.teams.active
                                            .mapNotNull { it.cores.minByOrNull(this::dst2) }
                                            .minByOrNull(this::dst2) ?: return false
                                        return team != team() && closestCore.team == team && (state.rules.polygonCoreProtection || dst(
                                            closestCore
                                        ) < state.rules.enemyCoreBuildRadius)

                                    }

                                    val lockDownStartTime = Time.millis()
                                    while (Time.timeSinceMillis(lockDownStartTime) <= 240_000) {
                                        Groups.unit.forEach {
                                            if (it.inEnemyArea()) {
                                                if (it.hasEffect(StatusEffects.sporeSlowed)) {
                                                    it.apply(StatusEffects.sapped, 10f * 60f)
                                                }
                                                if (it.hasEffect(StatusEffects.electrified)) {
                                                    it.apply(StatusEffects.sporeSlowed, 10f * 60f)
                                                }
                                                if (it.hasEffect(StatusEffects.slow)) {
                                                    it.apply(StatusEffects.electrified, 10f * 60f)
                                                }
                                                if (!it.hasEffect(StatusEffects.slow) && it.isPlayer)
                                                    Call.announce(
                                                        it.player.con,
                                                        "[red]你已经进入封锁区！\n在你离开之前获得大量debuff"
                                                    )
                                                it.apply(StatusEffects.slow, 10f * 60f)
                                            }
                                        }
                                        delay(1000)
                                    }
                                    Call.sendMessage("\n[#${team.color}]${team.name}[white]的核心禁造区封锁已经结束\n")
                                }
                                accelerator.kill()
                                achievement("[green][封锁！]", 100, true)
                            }
                        }
                    )
                }
            })
            add(buildList {
                if (Random(gameSeed + 4).nextFloat() <= 0.5f && team().techData().void()) {
                    add("[navy]虚空闪烁\n[white]你的军团单位和你全部传送至目标点\n无敌45s并大幅加强" to suspend {
                        if (accelerator.isValid && accelerator.team == team()) {
                            playerTapping[uuid()] = true
                            val startTappingTime = Time.millis()
                            var fail = false
                            sendMessage("[yellow]对目标点点击4次来闪烁至目标点\n[lightgray]再次点击行星际发射台取消")
                            while ((playerLastTapTile[uuid()]?.second ?: 0) < 4) {
                                if (Time.timeSinceMillis(startTappingTime) >= 30_000) {
                                    sendMessage("[red]选择超时")
                                    fail = true
                                    break
                                }
                                if (playerTapping[uuid()] == false || !accelerator.isValid || accelerator.team != team()) {
                                    sendMessage("[red]选择取消")
                                    fail = true
                                    break
                                }
                                delay(50L)
                            }
                            playerTapping[uuid()] = false
                            val tile = playerLastTapTile[uuid()]!!.first
                            if (!fogControl.isVisibleTile(team(), tile.x.toInt(), tile.y.toInt())) {
                                sendMessage("[red]指向性超武需要拥有视野！")
                                fail = true
                            }
                            if (!fail) {
                                Call.sendMessage(
                                    """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-虚空闪烁
                                    
                                    [white]他和他的军团单位全部传送至目标点(${tile.x},${tile.y})
                                    无敌45s并大幅加强
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                                )
                                accelerator.kill()
                                achievement("[green][虚空闪烁！]", 100, true)
                                team().data().units.forEach {
                                    if (it.owner() == uuid() || it == unit())
                                        launch(Dispatchers.game) {
                                            var times = 0
                                            while (true) {
                                                Tmp.v1.rnd(Random.nextFloat() * itemTransferRange / 2)

                                                val sx = tile.worldx() + Tmp.v1.x
                                                val sy = tile.worldy() + Tmp.v1.y

                                                if (it.canPass(World.toTile(sx), World.toTile(sy))) {
                                                    while (!it.within(
                                                            tile.worldx(),
                                                            tile.worldy(),
                                                            itemTransferRange
                                                        )
                                                    ) {
                                                        it.set(sx, sy)
                                                        it.snapInterpolation()
                                                        delay(50L)
                                                    }
                                                    Call.effect(Fx.teleportOut, sx, sy, 0f, team().color)
                                                    break
                                                }
                                                if (++times > 20) {
                                                    break
                                                }
                                            }
                                            it.apply(StatusEffects.invincible, 45f * 60f)
                                            it.apply(StatusEffects.boss, Float.POSITIVE_INFINITY)
                                            it.apply(StatusEffects.overclock, Float.POSITIVE_INFINITY)
                                            it.apply(StatusEffects.overdrive, Float.POSITIVE_INFINITY)
                                            it.apply(StatusEffects.shielded, Float.POSITIVE_INFINITY)
                                            it.apply(StatusEffects.burning, Float.POSITIVE_INFINITY)
                                            if (it == unit()) Call.setCameraPosition(con, tile.worldx(), tile.worldy())
                                        }
                                }
                            }
                        }
                    })
                } else {
                    add("[cyan]轰击\n[white]延时对目标点造成巨大破坏" to suspend {
                        if (accelerator.isValid && accelerator.team == team()) {
                            playerTapping[uuid()] = true
                            val startTappingTime = Time.millis()
                            var fail = false
                            sendMessage("[yellow]对目标点点击4次来轰击目标点\n[lightgray]再次点击行星际发射台取消")
                            while ((playerLastTapTile[uuid()]?.second ?: 0) < 4) {
                                if (Time.timeSinceMillis(startTappingTime) >= 30_000) {
                                    sendMessage("[red]选择超时")
                                    fail = true
                                    break
                                }
                                if (playerTapping[uuid()] == false || !accelerator.isValid || accelerator.team != team()) {
                                    sendMessage("[red]选择取消")
                                    fail = true
                                    break
                                }
                                delay(50L)
                            }
                            playerTapping[uuid()] = false
                            val tile = playerLastTapTile[uuid()]!!.first
                            if (!fogControl.isVisibleTile(team(), tile.x.toInt(), tile.y.toInt())) {
                                sendMessage("[red]指向性超武需要拥有视野！")
                                fail = true
                            }
                            if (!fail) {
                                val team = team()
                                val startTickingTime = Time.millis()
                                Call.sendMessage(
                                    """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-轰击
                                    
                                    [white]延时对目标点(${tile.x},${tile.y})造成巨大破坏！
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                                )
                                accelerator.kill()
                                achievement("[green][轰击！]", 100, true)
                                while (Time.timeSinceMillis(startTickingTime) <= 10_000) {
                                    Call.label(
                                        "[#${team.color}]检测到在途的轰击打击!\n[white]${
                                            (10f - Time.timeSinceMillis(
                                                startTickingTime
                                            ) / 1000f).format()
                                        }", 0.513f, tile.worldx(), tile.worldy()
                                    )
                                    Call.effect(Fx.unitCapKill, tile.worldx(), tile.worldy(), 0f, Color.red)
                                    Call.effect(Fx.dooropenlarge, tile.worldx(), tile.worldy(), 0f, Color.red)
                                    repeat(8) {
                                        Tmp.v1.rnd(Random.nextFloat() * 96f * 8f)
                                        val sx = tile.worldx() + Tmp.v1.x
                                        val sy = tile.worldy() + Tmp.v1.y
                                        Call.effect(Fx.regenParticle, sx, sy, 0f, team.color)
                                    }
                                    repeat(6) {
                                        Tmp.v1.rnd(Random.nextFloat() * 48f * 8f)
                                        val sx = tile.worldx() + Tmp.v1.x
                                        val sy = tile.worldy() + Tmp.v1.y
                                        Call.effect(Fx.regenParticle, sx, sy, 0f, team.color)
                                    }
                                    repeat(4) {
                                        Tmp.v1.rnd(Random.nextFloat() * 32f * 8f)
                                        val sx = tile.worldx() + Tmp.v1.x
                                        val sy = tile.worldy() + Tmp.v1.y
                                        Call.effect(Fx.regenParticle, sx, sy, 0f, team.color)
                                    }
                                    delay(500)
                                }
                                var bak = false
                                if (state.rules.ghostBlocks) {
                                    state.rules.ghostBlocks = false
                                    Call.setRules(state.rules)
                                    bak = true
                                }
                                Call.logicExplosion(
                                    team,
                                    tile.worldx(),
                                    tile.worldy(),
                                    96f * 8f,
                                    50f,
                                    true,
                                    true,
                                    true,
                                    false
                                )
                                Call.logicExplosion(
                                    Team.derelict,
                                    tile.worldx(),
                                    tile.worldy(),
                                    48f * 8f,
                                    500f,
                                    true,
                                    true,
                                    false,
                                    false
                                )
                                Call.logicExplosion(
                                    Team.derelict,
                                    tile.worldx(),
                                    tile.worldy(),
                                    32f * 8f,
                                    2000f,
                                    true,
                                    true,
                                    false,
                                    false
                                )
                                Call.logicExplosion(
                                    Team.derelict,
                                    tile.worldx(),
                                    tile.worldy(),
                                    12f * 8f,
                                    5000f,
                                    true,
                                    true,
                                    false,
                                    false
                                )
                                Call.logicExplosion(
                                    Team.derelict,
                                    tile.worldx(),
                                    tile.worldy(),
                                    6f * 8f,
                                    32000f,
                                    true,
                                    true,
                                    false,
                                    false
                                )
                                Call.soundAt(Sounds.explosion, tile.worldx(), tile.worldy(), 114514f, 0f)
                                Call.effect(Fx.impactReactorExplosion, tile.worldx(), tile.worldy(), 0f, team.color)
                                var delayTime: Long
                                delayTime = Random.nextLong(20, 100)
                                Call.label(
                                    "[#${team.color}]检测到在途的轰击打击!\n[white]200/200",
                                    delayTime / 1000f * 1.2f,
                                    tile.worldx(),
                                    tile.worldy()
                                )
                                repeat(200) {
                                    delay(delayTime)
                                    launch(Dispatchers.game) {
                                        Tmp.v1.rnd(Random.nextFloat() * 96f * 8f)
                                        val sx = tile.worldx() + Tmp.v1.x
                                        val sy = tile.worldy() + Tmp.v1.y
                                        Call.effect(Fx.instBomb, sx, sy, 0f, team.color)
                                        Call.soundAt(Sounds.blockPlace1, sx, sy, 3f, 0f)
                                        delay(Random.nextLong(1_000, 2_000))
                                        Call.logicExplosion(
                                            Team.derelict,
                                            sx,
                                            sy,
                                            32f * 8f,
                                            200f,
                                            true,
                                            true,
                                            false,
                                            false
                                        )
                                        Call.logicExplosion(
                                            Team.derelict,
                                            sx,
                                            sy,
                                            12f * 8f,
                                            500f,
                                            true,
                                            true,
                                            false,
                                            false
                                        )
                                        Call.logicExplosion(
                                            Team.derelict,
                                            sx,
                                            sy,
                                            6f * 8f,
                                            1500f,
                                            true,
                                            true,
                                            false,
                                            false
                                        )
                                        Call.soundAt(Sounds.explosion, tile.worldx(), tile.worldy(), 1.5f, 0f)
                                        Call.effect(Fx.greenBomb, sx, sy, 0f, team.color)
                                    }
                                    delayTime = Random.nextLong(20, 100)
                                    Call.label(
                                        "[#${team.color}]检测到在途的轰击打击!\n[white]${200 - it}/200",
                                        delayTime / 1000f * 1.2f,
                                        tile.worldx(),
                                        tile.worldy()
                                    )
                                }
                                if (bak) {
                                    state.rules.ghostBlocks = true
                                    Call.setRules(state.rules)
                                }
                            }
                        }
                    })
                }
            })
            add(buildList {
                if (Random(gameSeed + 5).nextFloat() <= 0.5f && team().techData().void()) {
                    add("[navy]虚空转化\n[white]所有敌方队伍领主外单位有50%概率被虚空转化" to suspend {
                        if (accelerator.isValid && accelerator.team == team()) {
                            Call.sendMessage(
                                """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-虚空转化
                                    
                                    [white]所有此队伍外除领主外单位有50%概率被虚空转化
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                            )
                            accelerator.kill()
                            achievement("[green][虚空转化！]", 100, true)
                            Groups.unit.forEach {
                                if (it.team == team() || it.type in LordUnits) return@forEach
                                if (Random.nextFloat() <= 0.50f) {
                                    if (it.isPlayer) {
                                        it.kill()
                                        Call.label("[navy]虚寂！", 3f, it.x, it.y)
                                        Call.effect(Fx.reactorExplosion, it.x, it.y, 0f, Color.red)
                                    } else {
                                        it.team(team())
                                        it.apply(StatusEffects.invincible, 5f * 60f)
                                        it.apply(StatusEffects.shielded, 20f * 60f)
                                        Call.label("[navy]转化！", 3f, it.x, it.y)
                                        Call.effect(Fx.commandSend, it.x, it.y, 0f, team().color)
                                    }
                                }
                            }
                        }
                    })
                } else {
                    add("[cyan]回生\n[white]120s内,领主外死亡单位回到核心,最大生命值减半\n最多可以减到生命值1/8" to suspend {
                        if (accelerator.isValid && accelerator.team == team()) {
                            Call.sendMessage(
                                """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-回生
                                    
                                    [white]120s内,此队伍领主外死亡单位回到核心,最大生命值减半
                                    最多可以减到生命值1/8
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                            )
                            accelerator.kill()
                            achievement("[green][回生！]", 100, true)
                            team().techData().deadIsntDead =
                                if (team().techData().deadIsntDead <= Time.millis()) Time.millis() + 120_000 else team().techData().deadIsntDead + 120_000
                        }
                    })
                }
            })
            add(buildList {
                if (Random(gameSeed + 6).nextFloat() <= 0.5f && team().techData().void()) {
                    add("[navy]虚空劫掠\n[white]敌方队伍城市金币人口变成负数" to {
                        if (accelerator.isValid && accelerator.team == team()) {
                            Call.sendMessage(
                                """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-虚空劫掠
                                    
                                    [white]除此队伍外城市金币人口变成负数
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                            )
                            accelerator.kill()
                            achievement("[green][虚空劫掠！]", 100, true)
                            state.teams.getActive().forEach {
                                if (it.team != team()) {
                                    it.cores.forEach {
                                        it.cityData().apply {
                                            coins *= -1
                                            population *= -1
                                        }
                                    }
                                }
                            }
                        }
                    })
                } else {
                    add("[cyan]偷盗\n[white]除你队伍外玩家的金币归0" to {
                        if (accelerator.isValid && accelerator.team == team()) {
                            Call.sendMessage(
                                """
                                    
                                    ------------------------------------------------
                                    ${name()}[#${team().color}]使用了超级武器-偷盗
                                    
                                    [white]除此队伍外玩家的金币归0
                                    ------------------------------------------------
                                    
                                """.trimIndent()
                            )
                            accelerator.kill()
                            achievement("[green][偷盗！]", 100, true)
                            Groups.player.forEach {
                                if (it.team() != team()) {
                                    if (it.coins() >= 88888) {
                                        achievement("[purple][神偷！]", 200, true)
                                    }
                                    it.removeCoin(it.coins())
                                }
                            }
                        }
                    })
                }
            })
            if (state.rules.fog || team().techData().void())
                add(buildList {
                    if (state.rules.fog)
                        add("[cyan]影鬼\n[white]队伍攻击翻倍\n持续180s" to suspend {
                            if (accelerator.isValid && accelerator.team == team() && state.rules.fog) {
                                team().rules().unitDamageMultiplier *= 2f
                                team().rules().blockDamageMultiplier *= 2f
                                Call.setRules(state.rules)
                                Call.sendMessage(
                                    """
                                            
                                            ------------------------------------------------
                                            ${name()}[#${team().color}]使用了超级武器-影鬼
                                            
                                            [white]队伍单位攻击 ${(team().rules().unitDamageMultiplier / 2f).format()} -> ${team().rules().unitDamageMultiplier.format()}
                                            队伍建筑攻击 ${(team().rules().blockDamageMultiplier / 2f).format()} -> ${team().rules().blockDamageMultiplier.format()}
                                            持续180s!
                                            ------------------------------------------------
                                            
                                        """.trimIndent()
                                )
                                accelerator.kill()
                                achievement("[green][影鬼！]", 100, true)
                                val team = team()
                                delay(180_000)
                                team.rules().unitDamageMultiplier /= 2f
                                team.rules().blockDamageMultiplier /= 2f
                                Call.sendMessage("\n[#${team.color}]${team.name}[white]的影鬼攻击增幅已经结束\n")
                            }
                        })
                    else if (team().techData().void())
                        add("[navy]虚空之灵\n[white]队伍建筑血量翻5倍120s" to {
                            if (accelerator.isValid && accelerator.team == team()) {
                                team().rules().blockHealthMultiplier *= 5f
                                Call.setRules(state.rules)
                                Call.sendMessage(
                                    """
                                            
                                            ------------------------------------------------
                                            ${name()}[#${team().color}]使用了超级武器-虚空之灵
                                            
                                            队伍建筑血量 ${(team().rules().blockHealthMultiplier / 5f).format()} -> ${team().rules().blockHealthMultiplier.format()}
                                            持续120s!
                                            ------------------------------------------------
                                            
                                        """.trimIndent()
                                )
                                accelerator.kill()
                                achievement("[green][虚空之灵！]", 100, true)
                                val team = team()
                                delay(120_000)
                                team.rules().blockHealthMultiplier /= 5f
                                Call.setRules(state.rules)
                                Call.sendMessage("\n[#${team.color}]${team.name}[white]的虚空之灵血量增幅已经结束\n")
                            }
                        })
                })
        } else {
            add(
                listOf(
                "[red]未装满铜铅,无法使用" to { superWeaponsMenu(accelerator) }
            ))
        }
        if (admin && Groups.player.size() <= 8) {
            this += listOf(
                "[red]<ADMIN>FULL" to { accelerator.items.add((accelerator.block as Accelerator).launchBlock.requirements.toMutableList()) }
            )
        }
        this += listOf(
            "取消" to {}
        )
    }
}

listen<EventType.TapEvent> {
    val player = it.player
    if (player.dead()) return@listen
    playerLastTapTile[player.uuid()] = it.tile to if (it.tile == (playerLastTapTile[player.uuid()]?.first
            ?: false)
    ) (playerLastTapTile[player.uuid()]?.second?.plus(1) ?: 1) else 1
    if (it.tile.team() == player.team() &&
        it.tile.within(player.x, player.y, itemTransferRange)
    ) {
        launch(Dispatchers.game) {
            if (it.tile.block() is CoreBlock) player.cityMenu(it.tile.build as CoreBuild)
            if (it.tile.block() is LaunchPad) player.launchPadMenu(it.tile.build as LaunchPad.LaunchPadBuild)
            if (it.tile.block() is Accelerator) player.superWeaponsMenu(it.tile.build as AcceleratorBuild)
            if (it.tile.block() is VariableReactor) player.megaStructuresMenu(it.tile.build as VariableReactorBuild)
        }
    }
}

listen<EventType.PlayerChatEvent> {
    playerLastSendText[it.player.uuid()] = it.message
}

listen<EventType.PlayerLeave> {
    launch(Dispatchers.game) {
        val leaveTime = Time.millis()
        val uuid = it.player.uuid()
        val team = it.player.team()
        while (!Groups.player.any { p -> p.uuid() == uuid }) {
            if (Time.timeSinceMillis(leaveTime) >= 30_000) {
                if (playerCoins[uuid] > 0) {
                    team.addCoin(playerCoins[uuid])
                    playerCoins.put(uuid, 0)
                }
                break
            }
            delay(1_000L)
        }
    }
}

listen<EventType.UnitDamageEvent> { e ->
    val unit: mindustry.gen.Unit = e.unit
    val bullet: Bullet = e.bullet
    if (bullet.owner !is mindustry.gen.Unit) return@listen
    val float =
        ((bullet.type.damage / ((bullet.owner as mindustry.gen.Unit).type?.level() ?: 1) * 10f)).coerceAtLeast(0.1f)
            .coerceAtMost(1f)

    fun checkEffectBullet(unit: mindustry.gen.Unit, team: Team, corroded: Boolean = false) {
        if (team.techData().checkEffectBulletHit((if (corroded) 0.2f else 1f) * float)) {
            val tu = team.techData().bulletStatusEffect.toList().random()
            val t = tu.first
            val u = tu.second
            if (unit.hasEffect(StatusEffects.corroded) && t == StatusEffects.corroded && !corroded)
                Units.nearby(unit.team, unit.x, unit.y, itemTransferRange) {
                    checkEffectBullet(it, team, corroded = true)
                }
            unit.apply(t, u)
        }
    }
    if ((unit.team().techData().voidAnnihilation && Random.nextFloat() <= 0.01f) || (bullet.team()
            .techData().voidAnnihilation && Random.nextFloat() <= 0.02f)
    ) {
        unit.maxHealth /= 2f
        unit.clampHealth()
        Call.label("[navy]湮灭！", 3f, unit.x, unit.y)
        Call.effect(Fx.reactorExplosion, unit.x, unit.y, 0f, Color.red)
        return@listen
    }
    if (bullet.team.techData().bulletStatusEffect.isNotEmpty())
        repeat(ceil(bullet.team.techData().bulletStatusEffect.size / 2f)) {
            checkEffectBullet(unit, bullet.team)
        }
    if (bullet.team.techData().verdict) unit.health -= bullet.damage * 0.1f
    if (
        ((bullet.team.techData().fogKiller && !fogControl.isVisible(
            unit.team,
            (bullet.owner as mindustry.gen.Unit).x,
            (bullet.owner as mindustry.gen.Unit).y
        )) || MegaStructures.satellite in bullet.team.techData().megaStructures) && MegaStructures.satellite !in bullet.team.techData().megaStructures
    ) unit.health -= bullet.damage * 1f
    if (bullet.team.techData().maxHealthDamage && Random.nextFloat() <= 0.5f) unit.maxHealth -= bullet.damage * 0.5f
}

listen<EventType.UnitDestroyEvent> {
    if (it.unit.team.techData().deadIsntDead > Time.millis() || (it.unit.team.techData().deadIsntDeadProbability && Random.nextFloat() <= 0.25f) && it.unit.type !in LordUnits && it.unit.type.playerControllable && !it.unit.spawnedByCore) {
        var times = 0
        val spawnRadius = 5
        val core = it.unit.closestCore()
        val unit = it.unit.type.create(it.unit.team)
        val uuid = it.unit.owner()
        while (true) {
            Tmp.v1.rnd(spawnRadius.toFloat() * tilesize)

            val sx = core.x + Tmp.v1.x
            val sy = core.y + Tmp.v1.y

            if (unit.canPass(World.toTile(sx), World.toTile(sy))) {
                unit.set(sx, sy)
                val maxHealth = it.unit.maxHealth / 2
                unit.maxHealth = maxHealth
                if (maxHealth < it.unit.type.health / 8) return@listen
                Call.label("[green]回生!", 3f, sx, sy)
                unitOwner[unit] = uuid
                break
            }

            if (++times > 20) {
                return@listen
            }
        }
        unit.apply {
            apply(StatusEffects.invincible, 5f * 60f)
            add()
            unit.clampHealth()
        }
    }
    if (it.unit.team.techData().deadBoom) {
        launch(Dispatchers.game) {
            val unit = it.unit
            repeat(Random.nextInt(3, 10)) {
                delay(Random.nextLong(500, 800))
                Call.effect(Fx.explosion, unit.x, unit.y, 0f, Color.red)
            }
            Call.logicExplosion(unit.team, unit.x, unit.y, 8f * 8f, unit.maxHealth / 10, true, true, true, false)
        }
    }
}

listenPacket2Server<UnitControlCallPacket> { con, packet ->
    val unit: mindustry.gen.Unit = packet.unit
    val owner = unit.owner()
    if (owner == null) {
        true
    } else {
        if (con.player.uuid() != owner) {
            if (!unit.team.techData().controlOtherPlayerUnits && unit.type !in LordUnits) Call.announce(
                con,
                "[red]你不是该单位的领主！无法控制"
            )
            unit.team.techData().controlOtherPlayerUnits && unit.type !in LordUnits
        } else {
            launch(Dispatchers.game) {
                while (!unit.isPlayer) {
                    delay(50L)
                }
                val effects = listOf(
                    StatusEffects.boss,
                    StatusEffects.shielded,
                    StatusEffects.overclock,
                    StatusEffects.overdrive,
                )
                val hadEffects = effects.toMutableList()
                while (unit.isPlayer) {
                    hadEffects.removeIf { !unit.hasEffect(it) }
                    effects.filter { it !in hadEffects }.forEach {
                        unit.apply(it, 2 * 60f)
                    }
                    if (unit.team().techData().cycloneEngine)
                        unit.apply(StatusEffects.fast, 24 * 60f)
                    delay(50L)
                }
                effects.filter { it !in hadEffects }.forEach {
                    unit.unapply(it)
                }
            }
            true
        }
    }
}
