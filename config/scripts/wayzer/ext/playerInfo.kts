@file:Depends("coreLibrary/db", "数据库存储")
@file:Depends("wayzer/user/shortID", "数字UID")
@file:Depends("wayzer/user/ban", "banX系统")
@file:Depends("wayzer/user/suffix", "客户端类型检测")
@file:Depends("wayzer/user/nameExt", "原始名字")
@file:Depends("wayzer/cmds/voteOb", "强制观战事件")
@file:Depends("coreMindustry/util/textInput", "文本输入复制")
@file:Import("org.json:json:20231013", mavenDepends = true)

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.listenTo
import coreLib.db.DBApi
import coreMindustry.MenuV2
import coreMindustry.util.textInput
import coreMindustry.renderPaged
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import wayzer.ForcedObEvent
import wayzer.lib.PlayerData
import wayzer.user.clientType
import wayzer.user.lang
import wayzer.user.settings
import wayzer.user.timezone
import wayzer.user.tzSettings
import java.time.Instant

name = "玩家信息查询"

// 注册数据表(api.kt 中定义)
DBApi.registerTable(PlayerRecordTable)
DBApi.registerTable(PlayerNameHistoryTable)
DBApi.registerTable(PlayerIpHistoryTable)

// ===== 通用复制 =====
suspend fun copyToPlayer(p: Player, title: String, content: String) {
    textInput(
        p,
        title,
        "{tr playerInfo.copy.msg}".with("receiver" to p).toString(),
        content,
        Int.MAX_VALUE
    )
}

// ===== 数据收集: 玩家加入 =====
listen<EventType.PlayerJoin> {
    val p = it.player
    val uuid = p.uuid()
    val now = Instant.now().epochSecond
    val uid = getUidByUuid(uuid)
    val name = p.plainName()
    val ip = p.con?.address ?: "unknown"

    joinTimeCache[uuid] = now

    transaction {
        // 更新或创建 PlayerRecord
        val existing = PlayerRecordTable.selectAll().where { PlayerRecordTable.id eq uuid }.firstOrNull()
        if (existing != null) {
            PlayerRecordTable.update({ PlayerRecordTable.id eq uuid }) { row ->
                row[PlayerRecordTable.joinCount] = existing[PlayerRecordTable.joinCount] + 1
                if (uid != null) row[PlayerRecordTable.uid] = uid
                if (now < existing[PlayerRecordTable.firstJoinTime]) row[PlayerRecordTable.firstJoinTime] = now
            }
        } else {
            PlayerRecordTable.insert { row ->
                row[PlayerRecordTable.id] = uuid
                row[PlayerRecordTable.uid] = uid
                row[PlayerRecordTable.joinCount] = 1
                row[PlayerRecordTable.totalOnlineSeconds] = 0
                row[PlayerRecordTable.firstJoinTime] = now
                row[PlayerRecordTable.lastLeaveTime] = null
                row[PlayerRecordTable.forcedObCount] = 0
            }
        }
        // 记录名称历史(不存在才插入)
        val nameKey = "$uuid:$name"
        if (PlayerNameHistoryTable.selectAll().where { PlayerNameHistoryTable.id eq nameKey }.firstOrNull() == null) {
            PlayerNameHistoryTable.insert { row ->
                row[PlayerNameHistoryTable.id] = nameKey
                row[PlayerNameHistoryTable.uuid] = uuid
                row[PlayerNameHistoryTable.name] = name
                row[PlayerNameHistoryTable.firstSeenTime] = now
            }
        }
        // 记录 IP 历史(不存在才插入)
        val ipKey = "$uuid:$ip"
        if (PlayerIpHistoryTable.selectAll().where { PlayerIpHistoryTable.id eq ipKey }.firstOrNull() == null) {
            PlayerIpHistoryTable.insert { row ->
                row[PlayerIpHistoryTable.id] = ipKey
                row[PlayerIpHistoryTable.uuid] = uuid
                row[PlayerIpHistoryTable.ip] = ip
                row[PlayerIpHistoryTable.firstSeenTime] = now
            }
        }
    }
}

// ===== 数据收集: 玩家离开 =====
listen<EventType.PlayerLeave> {
    val uuid = it.player.uuid()
    val joinTime = joinTimeCache.remove(uuid) ?: return@listen
    val now = Instant.now().epochSecond
    val delta = now - joinTime
    if (delta < 0) return@listen

    transaction {
        val existing = PlayerRecordTable.selectAll().where { PlayerRecordTable.id eq uuid }.firstOrNull()
        if (existing != null) {
            PlayerRecordTable.update({ PlayerRecordTable.id eq uuid }) { row ->
                row[PlayerRecordTable.totalOnlineSeconds] = existing[PlayerRecordTable.totalOnlineSeconds] + delta
                row[PlayerRecordTable.lastLeaveTime] = now
            }
        } else {
            PlayerRecordTable.insert { row ->
                row[PlayerRecordTable.id] = uuid
                row[PlayerRecordTable.uid] = null
                row[PlayerRecordTable.joinCount] = 0
                row[PlayerRecordTable.totalOnlineSeconds] = delta
                row[PlayerRecordTable.firstJoinTime] = joinTime
                row[PlayerRecordTable.lastLeaveTime] = now
                row[PlayerRecordTable.forcedObCount] = 0
            }
        }
    }
}

// ===== 数据收集: 强制观战 =====
listenTo<ForcedObEvent> {
    val targetUuid = this.targetUuid
    transaction {
        val existing = PlayerRecordTable.selectAll().where { PlayerRecordTable.id eq targetUuid }.firstOrNull()
        if (existing != null) {
            PlayerRecordTable.update({ PlayerRecordTable.id eq targetUuid }) { row ->
                row[PlayerRecordTable.forcedObCount] = existing[PlayerRecordTable.forcedObCount] + 1
            }
        } else {
            PlayerRecordTable.insert { row ->
                row[PlayerRecordTable.id] = targetUuid
                row[PlayerRecordTable.uid] = null
                row[PlayerRecordTable.joinCount] = 0
                row[PlayerRecordTable.totalOnlineSeconds] = 0
                row[PlayerRecordTable.firstJoinTime] = Instant.now().epochSecond
                row[PlayerRecordTable.lastLeaveTime] = null
                row[PlayerRecordTable.forcedObCount] = 1
            }
        }
    }
}

// ===== 菜单函数 =====

/** 主菜单: 在线玩家列表 + 搜索按钮 */
suspend fun showPlayerInfoMenu(p: Player) {
    MenuV2(p) {
        var view by stateKey("LIST", "view")
        var selectedUuid by stateKey("", "selectedUuid")
        var searchQuery by stateKey("", "searchQuery")

        when (view) {
            "LIST" -> {
                title = "{tr playerInfo.menu.title}".with("receiver" to p).toString()
                msg = "{tr playerInfo.menu.selectPlayer}".with("receiver" to p).toString()
                option("{tr playerInfo.menu.search}".with("receiver" to p).toString()) {
                    val query = textInput(
                        p,
                        "{tr playerInfo.menu.searchInput}".with("receiver" to p).toString(),
                        "{tr playerInfo.menu.searchHint}".with("receiver" to p).toString(),
                        "", 100
                    )
                    if (!query.isNullOrBlank()) {
                        searchQuery = query
                        view = "SEARCH"
                    }
                    refresh()
                }
                val players = Groups.player.toList()
                if (players.isEmpty()) {
                    option("{tr playerInfo.menu.noOnline}".with("receiver" to p).toString()) { refresh() }
                } else {
                    renderPaged(players, prePage = 9, key = "playerList") { target ->
                        val uid = getUidByUuid(target.uuid()) ?: 0
                        option("${target.name} [gray]$uid[]") {
                            selectedUuid = target.uuid()
                            view = "DETAIL"
                            refresh()
                        }
                    }
                }
            }

            "SEARCH" -> {
                title = "{tr playerInfo.menu.searchResult}".with("receiver" to p).toString()
                val results = searchPlayers(searchQuery)
                if (results.isEmpty()) {
                    msg = "{tr playerInfo.menu.noResult}".with("receiver" to p, "query" to searchQuery).toString()
                } else {
                    msg = "{tr playerInfo.menu.searchResultCount}".with("receiver" to p, "count" to results.size).toString()
                    renderPaged(results, prePage = 9, key = "searchResults") { uuid ->
                        val name = getLastName(uuid) ?: uuid.takeLast(8)
                        val uid = getRecord(uuid)?.uid
                        val uidStr = uid?.toString() ?: "?"
                        option("$name [gray]$uidStr[]") {
                            selectedUuid = uuid
                            view = "DETAIL"
                            refresh()
                        }
                    }
                }
                newRow()
                option("{tr playerInfo.menu.back}".with("receiver" to p).toString()) {
                    view = "LIST"
                    refresh()
                }
            }

            "DETAIL" -> {
                val uuid = selectedUuid
                val record = getRecord(uuid)
                val lastName = getLastName(uuid)
                val onlinePlayer = Groups.player.find { it.uuid() == uuid }
                val isAdmin = p.admin
                val isSelf = p.uuid() == uuid
                val canSeeSensitive = isAdmin || isSelf
                val banCount = countBans(uuid)

                title = lastName ?: "{tr playerInfo.menu.title}".with("receiver" to p).toString()

                val lines = mutableListOf<String>()
                lines.add("{tr playerInfo.detail.name}".with("receiver" to p, "name" to (lastName ?: "-")).toString())
                val uidStr = (record?.uid ?: onlinePlayer?.let { getUidByUuid(it.uuid()) })?.toString() ?: "-"
                lines.add("{tr playerInfo.detail.uid}".with("receiver" to p, "uid" to uidStr).toString())
                lines.add("{tr playerInfo.detail.status}".with(
                    "receiver" to p,
                    "status" to if (onlinePlayer != null) "{tr playerInfo.detail.online}".with("receiver" to p).toString()
                                else "{tr playerInfo.detail.offline}".with("receiver" to p).toString()
                ).toString())

                if (onlinePlayer != null) {
                    // 原始名: 客户端设置的未经服务器拼接修改的名字
                    val rawName = wayzer.user.realName[uuid]
                    if (rawName != null && rawName != lastName) {
                        lines.add("{tr playerInfo.detail.rawName}".with("receiver" to p, "name" to rawName).toString())
                    }
                    lines.add("{tr playerInfo.detail.clientLang}".with("receiver" to p, "lang" to (onlinePlayer.locale ?: "-")).toString())
                    val serverLang = PlayerData[onlinePlayer].lang
                    lines.add("{tr playerInfo.detail.serverLang}".with("receiver" to p, "lang" to serverLang).toString())
                    val tz = PlayerData[onlinePlayer].timezone
                    lines.add("{tr playerInfo.detail.timezone}".with("receiver" to p, "tz" to tz).toString())
                    val clientType = clientType[uuid]
                    val ctStr = clientType?.toString() ?: "-"
                    lines.add("{tr playerInfo.detail.clientType}".with("receiver" to p, "type" to ctStr).toString())
                    lines.add("{tr playerInfo.detail.mobile}".with(
                        "receiver" to p,
                        "mobile" to if (onlinePlayer.con?.mobile == true) "{tr playerInfo.detail.yes}".with("receiver" to p).toString()
                                     else "{tr playerInfo.detail.no}".with("receiver" to p).toString()
                    ).toString())
                } else {
                    // 离线玩家：从 KVStore 直接查询持久化的 lang 和 timezone
                    val serverLang = runCatching { settings[uuid] }.getOrNull() ?: "zh_CN"
                    val tz = runCatching { tzSettings[uuid] }.getOrNull() ?: "+08:00"
                    lines.add("{tr playerInfo.detail.serverLang}".with("receiver" to p, "lang" to serverLang).toString())
                    lines.add("{tr playerInfo.detail.timezone}".with("receiver" to p, "tz" to tz).toString())
                }

                if (record != null) {
                    lines.add("{tr playerInfo.detail.joinCount}".with("receiver" to p, "count" to record.joinCount).toString())
                    val days = record.totalOnlineSeconds / 86400
                    lines.add("{tr playerInfo.detail.totalDays}".with("receiver" to p, "days" to days).toString())
                    lines.add("{tr playerInfo.detail.firstJoin}".with("receiver" to p, "time" to formatTimestamp(record.firstJoinTime, p)).toString())
                    record.lastLeaveTime?.let {
                        lines.add("{tr playerInfo.detail.lastLeave}".with("receiver" to p, "time" to formatTimestamp(it, p)).toString())
                    }
                    lines.add("{tr playerInfo.detail.obCount}".with("receiver" to p, "count" to record.forcedObCount).toString())
                }

                lines.add("{tr playerInfo.detail.banCount}".with("receiver" to p, "count" to banCount).toString())

                if (canSeeSensitive) {
                    val ip = onlinePlayer?.con?.address
                    if (ip != null) {
                        lines.add("{tr playerInfo.detail.currentIP}".with("receiver" to p, "ip" to ip).toString())
                    }
                    lines.add("{tr playerInfo.detail.uuid}".with("receiver" to p, "uuid" to uuid).toString())
                }

                msg = lines.joinToString("\n")

                // 复制按钮
                column(2) {
                    option("{tr playerInfo.menu.copyName}".with("receiver" to p).toString()) {
                        copyToPlayer(p, "{tr playerInfo.copy.name}".with("receiver" to p).toString(), lastName ?: "")
                        refresh()
                    }
                    option("{tr playerInfo.menu.copyUid}".with("receiver" to p).toString()) {
                        copyToPlayer(p, "{tr playerInfo.copy.uid}".with("receiver" to p).toString(), uidStr)
                        refresh()
                    }
                    // 复制原始名(仅在线玩家且存在时显示)
                    val rawName = wayzer.user.realName[uuid]
                    if (onlinePlayer != null && rawName != null && rawName != lastName) {
                        option("{tr playerInfo.menu.copyRawName}".with("receiver" to p).toString()) {
                            copyToPlayer(p, "{tr playerInfo.copy.rawName}".with("receiver" to p).toString(), rawName)
                            refresh()
                        }
                    }
                }
                option("{tr playerInfo.menu.nameHistory}".with("receiver" to p).toString()) {
                    view = "NAME_HISTORY"
                    refresh()
                }
                if (canSeeSensitive) {
                    column(2) {
                        if (onlinePlayer?.con?.address != null) {
                            option("{tr playerInfo.menu.copyIP}".with("receiver" to p).toString()) {
                                copyToPlayer(p, "{tr playerInfo.copy.ip}".with("receiver" to p).toString(), onlinePlayer.con.address)
                                refresh()
                            }
                        } else {
                            option("") { refresh() }
                        }
                        option("{tr playerInfo.menu.copyUuid}".with("receiver" to p).toString()) {
                            copyToPlayer(p, "{tr playerInfo.copy.uuid}".with("receiver" to p).toString(), uuid)
                            refresh()
                        }
                    }
                    option("{tr playerInfo.menu.ipHistory}".with("receiver" to p).toString()) {
                        view = "IP_HISTORY"
                        refresh()
                    }
                }
                newRow()
                option("{tr playerInfo.menu.back}".with("receiver" to p).toString()) {
                    view = "LIST"
                    refresh()
                }
            }

            "NAME_HISTORY" -> {
                val uuid = selectedUuid
                val history = getNameHistory(uuid)
                title = "{tr playerInfo.menu.nameHistory}".with("receiver" to p).toString()
                if (history.isEmpty()) {
                    msg = "{tr playerInfo.menu.noHistory}".with("receiver" to p).toString()
                } else {
                    msg = ""
                    renderPaged(history, prePage = 9, key = "nameHistory") { entry ->
                        val timeStr = formatTimestamp(entry.firstSeenTime, p)
                        option("{tr playerInfo.menu.nameEntry}".with("receiver" to p, "name" to entry.name, "time" to timeStr).toString()) {
                            copyToPlayer(p, "{tr playerInfo.copy.name}".with("receiver" to p).toString(), entry.name)
                            refresh()
                        }
                    }
                }
                newRow()
                option("{tr playerInfo.menu.back}".with("receiver" to p).toString()) {
                    view = "DETAIL"
                    refresh()
                }
            }

            "IP_HISTORY" -> {
                val uuid = selectedUuid
                val history = getIpHistory(uuid)
                title = "{tr playerInfo.menu.ipHistory}".with("receiver" to p).toString()
                if (history.isEmpty()) {
                    msg = "{tr playerInfo.menu.noHistory}".with("receiver" to p).toString()
                } else {
                    msg = ""
                    renderPaged(history, prePage = 9, key = "ipHistory") { entry ->
                        val timeStr = formatTimestamp(entry.firstSeenTime, p)
                        option("{tr playerInfo.menu.ipEntry}".with("receiver" to p, "ip" to entry.ip, "time" to timeStr).toString()) {
                            copyToPlayer(p, "{tr playerInfo.copy.ip}".with("receiver" to p).toString(), entry.ip)
                            refresh()
                        }
                    }
                }
                newRow()
                option("{tr playerInfo.menu.back}".with("receiver" to p).toString()) {
                    view = "DETAIL"
                    refresh()
                }
            }
        }
    }.send().await()
}

// ===== 命令注册 =====
command("playerInfo", "{tr command.playerInfo.desc}".with()) {
    aliases = listOf("玩家信息")
    body {
        val p = player ?: returnReply("{tr playerInfo.reply.playerOnly}".with())
        launch { showPlayerInfoMenu(p) }
    }
}
