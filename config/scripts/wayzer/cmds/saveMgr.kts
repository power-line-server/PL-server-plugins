@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("coreMindustry/menu", "菜单系统")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("wayzer/user/lang", "PlayerData.timezone")

package cmds

import arc.files.Fi
import arc.struct.StringMap
import arc.util.Log
import coreLibrary.lib.parseTimeZone
import coreMindustry.*
import kotlinx.coroutines.launch
import mindustry.gen.Player
import mindustry.io.SaveIO
import mindustry.io.SaveOptions
import mindustry.io.JsonIO
import wayzer.MapManager
import wayzer.VoteService
import wayzer.lib.PlayerData
import wayzer.user.timezone
import java.time.format.DateTimeFormatter
import java.util.*

name = "存档管理系统"

// 存档范围配置 - 修改为100-199（与自动存档一致）
val saveSlots = 100 until 200   // 存档槽位范围，共100个槽位
val PAGE_SIZE by config.key(10, "每页显示存档数量")// 每页显示存档数量

// 获取存档信息
data class SaveInfo(val id: Int, val file: Fi, val exists: Boolean, val time: Date? = null)

fun getSaveInfo(id: Int): SaveInfo {
    val file = SaveIO.fileFor(id)
    val exists = file.exists()
    return SaveInfo(
        id, file, exists,
        if (exists) Date(file.lastModified()) else null
    )
}

// 获取存档列表（根据操作类型过滤）
fun getSaves(forSave: Boolean): List<SaveInfo> {
    return saveSlots.map(::getSaveInfo).filter {
        // 存档操作显示所有槽位，读档操作只显示非空槽位
        forSave || it.exists
    }.sortedWith(compareByDescending<SaveInfo> { it.time != null }.thenByDescending { it.time?.time ?: 0L })
    // 排序: 已有存档在前(按修改时间倒序, 新→旧), 空槽位在后
}

// 查找空槽位
fun findEmptySlot(): Int? {
    return saveSlots.firstOrNull { id ->
        !SaveIO.fileFor(id).exists()
    }
}

// 显示主菜单
suspend fun showMainMenu(player: Player) {
    MenuV2(player) {
        columnPreRow = 1
        title = "{tr saveMgr.menu.main.title}".with("receiver" to player).toString()
        msg = "{tr saveMgr.menu.main.msg}".with("receiver" to player).toString()

        // 读档选项（只显示非空存档）
        option("{tr saveMgr.menu.main.optionLoad}".with("receiver" to player).toString()) {
            showSlotMenu(player, forSave = false)
        }

        // 保存存档选项（所有玩家可见）
        option("{tr saveMgr.menu.main.optionSave}".with("receiver" to player).toString()) {
            showSlotMenu(player, forSave = true)
        }

        // 管理员删除存档选项
        if (player.admin) {
            option("{tr saveMgr.menu.main.optionDelete}".with("receiver" to player).toString()) {
                showDeleteMenu(player)
            }
        }
    }.send().await()
}

// 显示删除菜单
suspend fun showDeleteMenu(player: Player) {
    MenuV2(player) {
        columnPreRow = 1
        title = "{tr saveMgr.menu.delete.title}".with("receiver" to player).toString()
        msg = "{tr saveMgr.menu.delete.msg}".with("receiver" to player).toString()

        // 删除单个存档
        option("{tr saveMgr.menu.delete.optionOne}".with("receiver" to player).toString()) {
            showSlotMenu(player, forSave = false, deleteMode = true)
        }

        // 删除所有存档
        option("{tr saveMgr.menu.delete.optionAll}".with("receiver" to player).toString()) {
            deleteAllSaves(player)
        }

        option("{tr saveMgr.menu.common.back}".with("receiver" to player).toString()) {
            showMainMenu(player)
        }
    }.send().await()
}

// 显示分页槽位菜单
suspend fun showSlotMenu(player: Player, forSave: Boolean, deleteMode: Boolean = false, page: Int = 1) {
    val allSaves = getSaves(forSave)
    val totalPages = maxOf(1, (allSaves.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val currentPage = page.coerceIn(1, totalPages)

    val startIndex = (currentPage - 1) * PAGE_SIZE
    val endIndex = minOf(startIndex + PAGE_SIZE, allSaves.size)
    val pageSaves = allSaves.subList(startIndex, endIndex)

    val menuTitle = when {
        deleteMode -> "{tr saveMgr.menu.slot.deleteTitle}".with("receiver" to player, "current" to currentPage, "total" to totalPages)
        forSave -> "{tr saveMgr.menu.slot.saveTitle}".with("receiver" to player, "current" to currentPage, "total" to totalPages)
        else -> "{tr saveMgr.menu.slot.loadTitle}".with("receiver" to player, "current" to currentPage, "total" to totalPages)
    }

    MenuV2(player) {
        columnPreRow = 1
        title = menuTitle.toString()
        msg = buildString {
            // 修改槽位范围显示
            appendLine("{tr saveMgr.menu.slot.slotRange}".with("receiver" to player).toString())
            if (deleteMode) {
                appendLine("{tr saveMgr.menu.slot.deleteWarning}".with("receiver" to player).toString())
            } else {
                appendLine((if (forSave) "{tr saveMgr.menu.slot.showAll}" else "{tr saveMgr.menu.slot.showNonEmpty}").with("receiver" to player).toString())
                appendLine((if (forSave) "{tr saveMgr.menu.slot.clickToSave}" else "{tr saveMgr.menu.slot.clickToLoad}").with("receiver" to player).toString())
            }
            appendLine("=========================")
        }

        // 自动分配槽位选项（仅保存操作）
        if (forSave && !deleteMode) {
            option("{tr saveMgr.menu.slot.optionAuto}".with("receiver" to player).toString()) {
                val emptySlot = findEmptySlot()

                if (emptySlot != null) {
                    saveGame(player, emptySlot)
                    showMainMenu(player)
                } else {
                    player.sendMessage("{tr saveMgr.reply.noEmptySlot}".with())
                }
            }
        }

        // 显示当前页的存档
        pageSaves.forEach { save ->
            val displayText = if (save.exists) {
                val tz = PlayerData[player].timezone
                val zone = parseTimeZone(tz)
                val timeStr = save.time!!.toInstant().atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                "[${save.id}] ${timeStr}"
            } else {
                "{tr saveMgr.menu.slot.emptySlot}".with("receiver" to player, "id" to save.id).toString()
            }

            option(displayText) {
                if (deleteMode) {
                    // 删除操作
                    deleteSave(player, save.id)
                    showMainMenu(player) // 返回主菜单
                } else if (forSave) {
                    // 保存操作
                    saveGame(player, save.id)
                    showMainMenu(player) // 返回主菜单
                } else {
                    // 读取操作
                    loadGame(player, save.id)
                }
            }
        }

        // 分页导航
        if (currentPage > 1) {
            option("{tr saveMgr.menu.common.prevPage}".with("receiver" to player).toString()) {
                showSlotMenu(player, forSave, deleteMode, currentPage - 1)
            }
        }

        if (currentPage < totalPages) {
            option("{tr saveMgr.menu.common.nextPage}".with("receiver" to player).toString()) {
                showSlotMenu(player, forSave, deleteMode, currentPage + 1)
            }
            columnPreRow = 2
        }
        option("{tr saveMgr.menu.common.back}".with("receiver" to player).toString()) {
            showMainMenu(player)
        }
    }.send().await()
}

// 保存游戏（管理员直接保存，普通玩家投票保存）
fun saveGame(player: Player, slot: Int) {
    if (player.admin) {
        // 管理员直接保存
        manualSave(player, slot)
    } else {
        // 普通玩家发起投票保存
        launch(Dispatchers.game) {
            startVoteSave(player, slot)
        }
    }
}

// 加载游戏（管理员直接加载，普通玩家投票加载）
fun loadGame(player: Player, slot: Int) {
    val save = getSaveInfo(slot)
    if (!save.exists) {
        player.sendMessage("{tr saveMgr.reply.slotEmpty}".with())
        return
    }

    if (player.admin) {
        // 管理员直接加载
        MapManager.loadSave(save.file)
        broadcast("{tr saveMgr.broadcast.adminLoad}".with("player" to player, "slot" to slot), quite = true)
    } else {
        // 普通玩家发起投票加载
        launch(Dispatchers.game) {
            startVoteLoad(player, slot)
        }
    }
}

// 手动保存存档
fun manualSave(player: Player, slot: Int) {
    val tmp = Fi.tempFile("save")
    try {
        // 添加存档元数据
        val extTag = StringMap.of(
            "name", "[存档${slot}]" + state.map.name(),
            "description", state.map.description(),
            "author", state.map.author(),
            "mapId", MapManager.current.id.toString(),
            "mapTags", JsonIO.write(state.rules.tags),
        )
        SaveIO.write(tmp, SaveOptions().apply { extraTags = extTag })
        tmp.moveTo(SaveIO.fileFor(slot))
        player.sendMessage("{tr saveMgr.reply.saved}".with("slot" to slot))
        Log.info("${player.name} 保存存档到槽位 ${slot}")
    } catch (e: Exception) {
        player.sendMessage("{tr saveMgr.reply.saveFailed}".with("msg" to (e.message ?: "")))
        Log.err("存档保存失败", e)
        tmp.delete()
    }
}

// 删除存档
fun deleteSave(player: Player, slot: Int) {
    val saveFile = SaveIO.fileFor(slot)
    if (saveFile.exists()) {
        saveFile.delete()
        player.sendMessage("{tr saveMgr.reply.deleted}".with("slot" to slot))
        Log.info("${player.name} 删除存档 #${slot}")
    } else {
        player.sendMessage("{tr saveMgr.reply.notExist}".with("slot" to slot))
    }
}

// 删除所有存档
fun deleteAllSaves(player: Player) {
    var count = 0
    saveSlots.forEach { slot ->
        val file = SaveIO.fileFor(slot)
        if (file.exists()) {
            file.delete()
            count++
        }
    }

    player.sendMessage("{tr saveMgr.reply.deletedAll}".with("count" to count))
    Log.info("${player.name} 删除所有存档 (${count}个)")
}

// 发起投票读档
suspend fun startVoteLoad(player: Player, slot: Int) {
    val save = getSaveInfo(slot)
    if (!save.exists) {
        player.sendMessage("{tr saveMgr.reply.slotEmpty}".with())
        return
    }

    VoteService.start(
        player,
        "{tr saveMgr.vote.loadDesc}".with("slot" to slot),
        "{tr saveMgr.vote.needAgree}".with("receiver" to player).toString(),
        supportSingle = true
    ) {
        MapManager.loadSave(save.file)
        broadcast("{tr saveMgr.broadcast.voteLoadPass}".with("slot" to slot), quite = true)
    }
}

// 发起投票保存
suspend fun startVoteSave(player: Player, slot: Int) {
    VoteService.start(
        player,
        "{tr saveMgr.vote.saveDesc}".with("slot" to slot),
        "{tr saveMgr.vote.needAgree}".with("receiver" to player).toString(),
        supportSingle = true
    ) {
        manualSave(player, slot)
        broadcast("{tr saveMgr.broadcast.voteSavePass}".with("slot" to slot), quite = true)
    }
}

// 注册命令
command("savemgr", "{tr command.saveMgr.desc}".with()) {
    body {
        launch(Dispatchers.game) {
            showMainMenu(player ?: returnReply("{tr saveMgr.reply.needPlayer}".with()))
        }
    }
}
