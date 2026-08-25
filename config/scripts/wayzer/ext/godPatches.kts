@file:Depends("wayzer/vote", "投票实现")
package wayzer.ext
//WayZer 版权所有(请勿删除版权注解)

import arc.struct.Seq
import arc.util.Log
import arc.Core
import arc.util.serialization.Jval
import arc.util.serialization.Jval.Jformat
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.mod.data.PatchAsset
import wayzer.VoteEvent
import kotlin.math.ceil

name = "仙古数据包投票"

// 运行时状态(持久化,重启后自动恢复)
@Savable(false)
var godPatchesEnabled = false
customLoad(::godPatchesEnabled) { godPatchesEnabled = it }

// 记录已应用的 godpatch 字符串(plain 格式),用于 disablePatches 精确过滤
// 服务器重启后会丢失, 但 onEnable 会重置 godPatchesEnabled=false, 所以不会出现状态不一致
var appliedGodPatches: Set<String> = emptySet()

// 源文件夹: godpatches/patches (存放从客户端导出的原始数据包 json)
val godPatchesDir get() = dataDirectory.child("scripts/godpatches/patches")

// 收集 godpatch 文件的 name 字段(用于识别 PatchSet)
fun godPatchNames(): Set<String> {
    if (!godPatchesDir.exists()) return emptySet()
    return godPatchesDir.list().mapNotNull { file ->
        try {
            val jval = Jval.read(file.readString())
            jval.get("name")?.asString()?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            null
        }
    }.toSet()
}

// 收集 godpatch 文件内容(plain 格式, 用于创建 PatchAsset 和精确匹配)
fun godPatchStrings(): Set<String> {
    if (!godPatchesDir.exists()) return emptySet()
    return godPatchesDir.list().map { file ->
        try {
            Jval.read(file.readString()).toString(Jformat.plain)
        } catch (e: Throwable) {
            Log.err("godPatches: Failed to parse godpatch file: ${file.name()}", e)
            file.readString()
        }
    }.toSet()
}

// 重新同步所有在线玩家的世界数据
// 服务器 apply/unapply 后, state.data.patches 已更新
// 通过 Call.worldDataBegin + sendWorldData 让客户端重新加载世界(含新的 patches 列表)
// 客户端会短暂看到"连接中"加载界面, 但不会断开连接
fun resyncAllPlayers() {
    Core.app.post {
        Groups.player.forEach { p ->
            if (p.con == null || p.isLocal) return@forEach
            try {
                Call.worldDataBegin(p.con)
                Vars.netServer.sendWorldData(p)
            } catch (e: Throwable) {
                Log.err("godPatches: resync failed for ${p.name}", e)
            }
        }
    }
}

// 启用: 创建 PatchAsset + 合并到现有 patches + reloadPatches
// 159.2 直接操作内存中的 PatchAsset, 不再需要文件复制和 ServerControl 反射
fun enablePatches(): Boolean {
    if (!godPatchesDir.exists()) return false
    val sourceFiles = godPatchesDir.list()
    if (sourceFiles.isEmpty()) return false

    val godStrings = godPatchStrings()
    val godNames = godPatchNames()
    val newPatches = Seq<PatchAsset>()

    // 保留现有非 godpatch
    try {
        Vars.state.data.patches.forEach { ps ->
            val isGodByString = ps.patch in godStrings
            val isGodByName = ps.name in godNames
            if (!isGodByString && !isGodByName) {
                newPatches.add(ps)
            }
        }
    } catch (e: Throwable) {
        Log.err("godPatches: Failed to read current patches", e)
    }

    // 添加 godpatch
    godStrings.forEach { s ->
        newPatches.add(PatchAsset(s))
    }

    try {
        Vars.state.data.reloadPatches(newPatches)
    } catch (e: Throwable) {
        Log.err("godPatches: enablePatches apply failed", e)
        return false
    }
    appliedGodPatches = godStrings
    godPatchesEnabled = true
    resyncAllPlayers()
    return true
}

// 禁用: 过滤掉 godpatch + reloadPatches
// 双重过滤: appliedGodPatches(字符串精确匹配) + godPatchNames(name 字段匹配)
// 关键: reloadPatches() 会先 unapply() 回滚所有补丁, 再应用 remaining, 确保 godpatch 被撤销
fun disablePatches() {
    val appliedSet = appliedGodPatches
    val nameSet = godPatchNames()
    val remaining = Seq<PatchAsset>()
    try {
        Vars.state.data.patches.forEach { ps ->
            val isGodByString = ps.patch in appliedSet
            val isGodByName = ps.name in nameSet
            if (!isGodByString && !isGodByName) {
                remaining.add(ps)
            }
        }
    } catch (e: Throwable) {
        Log.err("godPatches: Failed to read current patches", e)
    }
    try {
        // 总是走 reloadPatches(remaining) 分支, 即使 remaining 为空
        // 关键原因: reloadPatches(emptySeq) 会 patches.clear(), 确保 /sync 时不发送 godpatch
        Vars.state.data.reloadPatches(remaining)
    } catch (e: Throwable) {
        Log.err("godPatches: disablePatches() apply failed", e)
    }
    appliedGodPatches = emptySet()
    godPatchesEnabled = false
    resyncAllPlayers()
}

// 换图时自动关闭: ResetEvent 在 Logic.reset() 中触发, 此时 Groups.player 已被清空
// 无法立即 broadcast(玩家列表为空), 需延迟到 WorldLoadEvent 后广播
@Savable(false)
var needBroadcastAutoDisabled = false
customLoad(::needBroadcastAutoDisabled) { needBroadcastAutoDisabled = it }

listen<EventType.ResetEvent> {
    if (godPatchesEnabled) {
        appliedGodPatches = emptySet()
        godPatchesEnabled = false
        needBroadcastAutoDisabled = true
    }
}

listen<EventType.WorldLoadEvent> {
    if (needBroadcastAutoDisabled) {
        needBroadcastAutoDisabled = false
        Core.app.post {
            broadcast("{tr godPatches.broadcast.autoDisabled}".with())
        }
    }
}

onEnable {
    // 服务器启动时重置状态
    // 用户要求换图后自动关闭, 服务器重启相当于换图
    appliedGodPatches = emptySet()
    godPatchesEnabled = false
}

command("godpatches", "{tr command.godpatches.desc}".with()) {
    aliases = listOf("仙古数据包")
    body {
        val p = player
        // 控制台: 直接执行
        if (p == null) {
            if (!godPatchesEnabled) {
                if (enablePatches()) {
                    broadcast("{tr godPatches.broadcast.enabled}".with())
                } else {
                    reply("{tr godPatches.reply.enableFailed}".with())
                }
            } else {
                disablePatches()
                broadcast("{tr godPatches.broadcast.disabled}".with())
            }
            return@body
        }
        // 玩家: 投票
        if (!godPatchesEnabled) {
            // 启用投票 (90%同意)
            val voteEvent = VoteEvent(
                thisScript, p,
                voteDesc = "{tr godPatches.vote.enableDesc}".with(),
                extDesc = "{tr godPatches.vote.enableExt}".with("receiver" to p).toString(),
                requireNum = { ceil(it * 0.9).toInt() }
            )
            if (voteEvent.awaitResult()) {
                if (enablePatches()) {
                    broadcast("{tr godPatches.broadcast.enabled}".with())
                } else {
                    broadcast("{tr godPatches.broadcast.enableFailed}".with())
                }
            }
        } else {
            // 关闭投票 (50%同意)
            val voteEvent = VoteEvent(
                thisScript, p,
                voteDesc = "{tr godPatches.vote.disableDesc}".with(),
                extDesc = "{tr godPatches.vote.disableExt}".with("receiver" to p).toString(),
                requireNum = { ceil(it * 0.5).toInt() }
            )
            if (voteEvent.awaitResult()) {
                disablePatches()
                broadcast("{tr godPatches.broadcast.disabled}".with())
            }
        }
    }
}
