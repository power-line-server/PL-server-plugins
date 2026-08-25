@file:Depends("coreMindustry/menu", "蓝图菜单")
@file:Depends("coreMindustry/util/textInput", "搜索输入")

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.MenuV2
import coreMindustry.util.textInput
import coreMindustry.renderPaged
import mindustry.game.Schematic
import mindustry.game.Schematics
import arc.util.Http
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

name = "蓝图分享"

// 蓝图存放目录(相对服务器工作目录)
val schematicDir = File("config/scripts/schematics").apply { mkdirs() }
val prePage by config.key(8, "/schematic 每页显示数")
// Pastebin API Key (MindustryX 内置的公共 key, 和客户端分享蓝图用同一个)
private val PASTEBIN_API_KEY = "sdBDjI5mWBnHl9vBEDMNiYQ3IZe0LFEk"

/** 蓝图数据: 文件名(无扩展名) -> Schematic 对象 */
data class SchematicEntry(val fileName: String, val schematic: Schematic)

/** 加载所有 .msch 蓝图文件 */
fun loadSchematics(): List<SchematicEntry> {
    return schematicDir.listFiles { f -> f.extension.equals("msch", true) }
        ?.mapNotNull { file ->
            try {
                val fi = arc.files.Fi(file)
                SchematicEntry(file.nameWithoutExtension, Schematics.read(fi))
            } catch (e: Exception) {
                logger.warning("加载蓝图失败: ${file.name}: ${e.message}")
                null
            }
        }?.sortedBy { it.fileName } ?: emptyList()
}

/** 按关键词过滤蓝图(匹配文件名或蓝图标签的 name) */
fun filterSchematics(list: List<SchematicEntry>, filter: String): List<SchematicEntry> {
    if (filter.isBlank()) return list
    val f = filter.lowercase()
    return list.filter { entry ->
        entry.fileName.lowercase().contains(f) ||
                entry.schematic.name()?.lowercase()?.contains(f) == true
    }
}

/** 序列化蓝图为 base64 (Vars.schematics 在 headless 服务器为 null, 用静态方法) */
fun encodeSchematic(schematic: Schematic): String {
    val baos = java.io.ByteArrayOutputStream()
    Schematics.write(schematic, baos)
    return java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
}

/** 上传 base64 蓝图到 Pastebin, 返回 paste ID (MindustryX 客户端会从 pastebin.com/raw/{id} 下载)
 *  完全模仿 MindustryX ShareFeature 的上传方式: 不 URL 编码 base64
 *  客户端下载后会做 .replace(" ", "+") 来恢复 base64 的 + 字符,
 *  所以服务器端必须不 URLEncoder, 让 + 在表单提交时变成空格, 客户端再替换回来 */
suspend fun uploadToPastebin(base64: String): String? = suspendCoroutine { cb ->
    val body = "api_dev_key=$PASTEBIN_API_KEY" +
            "&api_option=paste" +
            "&api_paste_expire_date=10M" +
            "&api_paste_code=$base64"
    Http.request(Http.HttpMethod.POST, "https://pastebin.com/api/api_post.php")
        .timeout(30_000)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .content(body)
        .error { e ->
            logger.warning("上传 Pastebin 失败: ${e.message}")
            cb.resume(null)
        }
        .submit {
            val url = it.resultAsString.trim()
            // 成功返回形如 https://pastebin.com/abc123XY
            val id = url.substringAfterLast('/')
            if (url.startsWith("http") && id.isNotEmpty()) {
                cb.resume(id)
            } else {
                logger.warning("Pastebin 返回异常: $url")
                cb.resume(null)
            }
        }
}

/** 发送蓝图给玩家: 弹出菜单让玩家选择发送方式 */
suspend fun sendSchematic(player: mindustry.gen.Player, entry: SchematicEntry) {
    val base64 = try {
        encodeSchematic(entry.schematic)
    } catch (e: Exception) {
        logger.warning("序列化蓝图失败: ${entry.fileName}: ${e.message}")
        textInput(
            player,
            "{tr schematicShare.popup.errorTitle}".with("receiver" to player).toString(),
            "{tr schematicShare.reply.serializeFailed}".with("receiver" to player).toString(),
            "", Int.MAX_VALUE
        )
        return
    }

    // 蓝图内部名字优先, 无效则回退到文件名
    val displayName = entry.schematic.name()?.takeIf { it.isNotBlank() && it != "unknown" }
        ?: entry.fileName

    // 弹出选择菜单 (MenuV2 的 msg 不解析颜色码, 纯文本)
    MenuV2(player) {
        title = "{tr schematicShare.sendMenu.title}".with("receiver" to player, "name" to displayName).toString()
        msg = "{tr schematicShare.sendMenu.msg}".with("receiver" to player).toString()
        autoCloseButton = true

        // 选项1: MindustryX 蓝图分享 (通过 Pastebin, X端自动弹出导入提示)
        option("{tr schematicShare.option.shareX}".with("receiver" to player).toString()) {
            player.sendMessage("{tr schematicShare.reply.uploading}".with("receiver" to player).toString())
            val pasteId = uploadToPastebin(base64)
            if (pasteId == null) {
                player.sendMessage("{tr schematicShare.reply.uploadFailed}".with("receiver" to player).toString())
            } else {
                // MindustryX 协议: <ARCxMDTX><Schem>描述 $code
                // 客户端 resolveSchematicShare 用 substringAfterLast(' ') 提取 pastebin code
                val msg = "<ARCxMDTX><Schem>蓝图分享 $pasteId"
                player.sendMessage(msg)
                player.sendMessage("{tr schematicShare.reply.sharedX}".with("receiver" to player, "name" to displayName).toString())
            }
        }

        // 选项2: 复制蓝图代码 (弹出文本框, 玩家可手动复制 base64, 兼容原版客户端)
        option("{tr schematicShare.option.copyCode}".with("receiver" to player).toString()) {
            textInput(
                player,
                "{tr schematicShare.popup.title}".with("receiver" to player, "name" to displayName).toString(),
                "{tr schematicShare.popup.msg}".with("receiver" to player).toString(),
                base64,
                Int.MAX_VALUE
            )
        }
    }.send().await()
}

command("schematic", "{tr command.schematic.desc}".with()) {
    usage = "{tr usage.schematic}"
    aliases = listOf("蓝图", "sc")
    body {
        val p = player ?: returnReply("{tr schematicShare.reply.playerOnly}".with())

        // 遵守地图规则: 禁用蓝图的地图不允许使用本指令
        if (!state.rules.schematicsAllowed) {
            returnReply("{tr schematicShare.reply.disabled}".with("receiver" to p))
        }

        // 加载蓝图
        var schematics = loadSchematics()
        if (schematics.isEmpty()) {
            returnReply("{tr schematicShare.reply.empty}".with("receiver" to p, "dir" to schematicDir.absolutePath))
        }

        MenuV2(p) {
            var filter by stateKey("")

            title = "{tr schematicShare.menu.title}".with("receiver" to p).toString()
            msg = "{tr schematicShare.menu.msg}".with("receiver" to p, "count" to schematics.size, "filter" to filter).toString()

            // 搜索选项
            option("{tr schematicShare.option.search}".with("receiver" to p).toString()) {
                val input = textInput(
                    p,
                    "{tr schematicShare.input.search.title}".with("receiver" to p).toString(),
                    "{tr schematicShare.input.search.hint}".with("receiver" to p).toString(),
                    filter,
                    50
                )
                if (input != null) filter = input.trim()
                refresh()
            }
            newRow()

            // 过滤后的蓝图列表
            val filtered = filterSchematics(schematics, filter)
            if (filtered.isEmpty()) {
                option("{tr schematicShare.option.noResult}".with("receiver" to p).toString()) {}
                newRow()
            } else {
                renderPaged(filtered, prePage = prePage, key = "schematics") { entry ->
                    val displayName = entry.schematic.name()?.takeIf { it.isNotBlank() && it != "unknown" }
                        ?: entry.fileName
                    val size = "${entry.schematic.width}x${entry.schematic.height}"
                    option("{tr schematicShare.option.entry}".with("receiver" to p, "name" to displayName, "size" to size).toString()) {
                        sendSchematic(p, entry)
                    }
                }
            }
        }.send().awaitWithTimeout()
    }
}
