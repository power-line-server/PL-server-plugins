@file:Depends("coreMindustry")
@file:Depends("wayzer/maps", "获取地图信息")
@file:Depends("wayzer/map/mapInfo", "显示地图信息", soft = true)
@file:Import("mapScript.lib.*", defaultImport = true)

/**
 * 该模块定义了一种特殊的kts：kts的生命周期与地图关联。
 * 当地图满足特定条件时(id/tag)，关联的kts会被enable，而一局游戏结束后，所有的kts会被disable。
 * */
package mapScript

import mindustry.mod.data.PatchAsset
import wayzer.MapManager
import wayzer.MapRegistry

val children get() = ScriptRegistry.allScripts { it != scriptInfo && it.dependsOn(scriptInfo) }

/** transactionV2 容错: 事件处理期间外层事务(如启动 boot)未结束时调用会报 Nest Transaction 嵌套错误(JDK26 下脚本加载慢更易触发)。
 *  捕获后通过 afterTransaction 等外层事务结束再重试, 保持地图脚本加载/卸载语义。
 *  注: ScriptManager.transactionV2 的签名是 block: suspend TransactionV2.() -> Unit, 返回 SATransaction(事务对象)。 */
private suspend fun transactionV2Safe(block: suspend ScriptManager.TransactionV2.() -> Unit): cf.wayzer.scriptAgent.state.SATransaction? {
    try {
        return ScriptManager.transactionV2(block)
    } catch (e: IllegalStateException) {
        if (e.message?.contains("Nest Transaction") != true) throw e
        logger.warning("[mapScript] 检测到事务嵌套(外层事务进行中), 等待其结束后重试")
        ScriptManager.afterTransaction {
            MindustryDispatcher.safeBlocking {
                runCatching { ScriptManager.transactionV2(block).printResult() }
                    .onFailure { it.printStackTrace() }
            }
        }
        return null
    }
}

listen<EventType.ResetEvent> { _ ->
    MindustryDispatcher.safeBlocking {
        transactionV2Safe {
            disable(children.filter { it.enabled })
            execute().printResult()
            load(keys.toList())
        }?.printResult()
    }
}

fun getToLoadMapScripts(): List<ScriptInfo> {
    //匹配所有mapScript子脚本，且名字与id匹配的
    val children = children
    val byId = children.find { it.id.endsWith("/${MapManager.current.id}") }
    val byTag = state.rules.tags.get("@mapScript")?.let { tag ->
        val tagId = tag.toIntOrNull() ?: MapManager.current.id
        children.find { it.id.endsWith("/$tagId") } ?: null.also {
            delayBroadcast("{tr mapScript.module.scriptNotFound}".with("id" to tagId))
        }
    }
    return buildList {
        if (byId != null) add(byId)
        if (byTag != null && byTag != byId) add(byTag)
        addAll(TagSupport.findTags(state.rules).values)
    }.flatMap {
        listOf(it) + ScriptRegistry.allScripts { dep ->
            !dep.enabled && it.dependsOn(dep, includeSoft = true)
        }
    }.toSet().toList()
}

listen<EventType.DataPatchLoadEvent> { e ->
    val patches = getToLoadMapScripts().flatMap {
        it.inst?.mapAssets.orEmpty() +
                it.inst?.mapPatches.orEmpty().map { p -> PatchAsset(p) }
    }
    if (patches.isEmpty()) return@listen
    logger.info("DataPatches loaded: ${patches.size}")
    e.assets.addAll(patches)
}

listen<EventType.WorldLoadEvent> {
    //load scripts
    val toLoad = getToLoadMapScripts()
    if (toLoad.isEmpty()) return@listen
    MindustryDispatcher.safeBlocking {
        transactionV2Safe {
            enable(toLoad)
        }
    }
    toLoad.forEach { checkEnabled(it) }
}

onEnable {
    MapRegistry.register(this, ScriptMapGenerator.Provider)
}
