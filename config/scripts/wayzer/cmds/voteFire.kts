@file:Depends("wayzer/vote", "投票实现")

package wayzer.cmds

import arc.struct.ObjectMap
import cf.wayzer.placehold.PlaceHoldApi.with
import kotlinx.coroutines.withContext
import mindustry.entities.bullet.BulletType
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.type.Item
import mindustry.type.Liquid
import mindustry.Vars.state
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.Turret
import wayzer.VoteEvent
import wayzer.VoteService
import kotlin.math.ceil

name = "无限火力投票"

// 投票开启无限火力: 本局有效(换图自动恢复), PVP 禁用, 通过阈值 80%
// 实现: 所有活跃队伍 cheat = true(TeamRule.cheat) + 维护循环每 250ms 兜底补弹药/功率
// 原版字段区别(infiniteResources 只免建造消耗, 与火力无关):
//   - infiniteResources: 建造不扣物品/核心满物品(TeamRule.java / BuilderComp.java)
//   - cheat: 炮塔弹药无限(Turret.useAmmo/hasAmmo 走 cheating())、液体/功率/热需求全免(BuildingComp/PowerGraph/LiquidTurret/LaserTurret)
// 正确开启无限火力的原版 js 等价: /js Team.sharded.rules().cheat = true
// 换图 WorldLoad 重建 teams 自动恢复

private var fireEnabled = false

private fun enableFire() {
    fireEnabled = true
    // 队伍 cheat(炮塔无限弹药/功率, 原版字段, 换图自动恢复); 保留 infiniteResources 兜底(建造免费)
    Team.all.forEach { team ->
        if (team != Team.derelict) {
            state.rules.teams.get(team).cheat = true
            state.rules.teams.get(team).infiniteResources = true
        }
    }
    // 广播规则: 直接改服务端 rules 客户端不会感知, 必须 Call.setRules 同步, 否则客户端本地炮塔仍耗弹药(不同步)
    Call.setRules(state.rules)
}

private fun bestItemAmmo(ammoTypes: ObjectMap<Item, BulletType>, current: Item?): Item? {
    if (current != null && ammoTypes.containsKey(current)) return current
    return ammoTypes.entries().firstOrNull()?.key
}

private fun bestLiquidAmmo(ammoTypes: ObjectMap<Liquid, BulletType>, current: Liquid?): Liquid? {
    if (current != null && ammoTypes.containsKey(current)) return current
    return ammoTypes.entries().firstOrNull()?.key
}

private fun maintainFire() {
    Groups.build.forEach { build ->
        if (!build.isValid) return@forEach
        when (build) {
            is ItemTurret.ItemTurretBuild -> {
                val turret = build.block as? ItemTurret ?: return@forEach
                val item = bestItemAmmo(turret.ammoTypes, build.getAmmoContent() as? Item) ?: return@forEach
                val maxAdds = (turret.maxAmmo + turret.ammoPerShot + 4).coerceIn(1, 240)
                var added = 0
                while (added < maxAdds && build.acceptItem(null, item)) {
                    build.handleItem(null, item)
                    added++
                }
                if (build.power != null) build.power.status = 1f
            }
            is LiquidTurret.LiquidTurretBuild -> {
                val turret = build.block as? LiquidTurret ?: return@forEach
                val liquid = bestLiquidAmmo(turret.ammoTypes, build.getAmmoContent() as? Liquid) ?: return@forEach
                if (build.liquids.get(liquid) < turret.liquidCapacity) build.liquids.add(liquid, turret.liquidCapacity * 60f)
                if (build.power != null) build.power.status = 1f
            }
            is Turret.TurretBuild -> {
                if (build.power != null) build.power.status = 1f
            }
        }
    }
}

fun voteFire(player: Player) {
    launch(Dispatchers.game) {
        if (fireEnabled) {
            player.sendMessage("{tr voteFire.reply.alreadyOn}".with())
            return@launch
        }
        val event = VoteEvent(
            this, player,
            voteDesc = "{tr voteFire.voteDesc.fire}".with(),
            requireNum = { ceil(it * 0.8).toInt() } // 通过阈值 80%
        )
        if (!event.awaitResult()) return@launch
        enableFire()
        broadcast("{tr voteFire.broadcast.enabled}".with())
    }
}

fun VoteService.register() {
    // usage 传字面量(addSubVote 不做 {tr} 替换, 传键名会在帮助里显示原文)
    addSubVote("{tr voteFire.subVote.fire.desc}", "", "fire", "无限火力") {
        if (state.rules.pvp)
            returnReply("{tr voteFire.reply.pvpDisabled}".with())
        voteFire(player!!)
    }
}

onEnable {
    VoteService.register()
    // 维护循环: 每 250ms 给炮塔补弹药/功率
    loop(Dispatchers.Default) {
        delay(250)
        if (fireEnabled) withContext(Dispatchers.game) { maintainFire() }
    }
}

listen<EventType.ResetEvent> {
    // 换图自动关闭(teamRules 重建, 维护循环停止)
    fireEnabled = false
}
