package coreMindustry

import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.Vars
import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.getContextScript
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.util.ReceivedEvent
import coreLibrary.lib.util.calPage
import coreLibrary.lib.util.nextEvent
import kotlinx.coroutines.withTimeoutOrNull
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.ui.builder.MenuResult
import mindustry.ui.builder.UiBuilder
import mindustry.ui.builder.UiBuilder.NodeBuilder
import kotlin.random.Random


data class MenuChooseEvent(
    val player: Player, val menuId: Int, val value: Int
) : Event, ReceivedEvent {
    override var received: Boolean = false

    companion object : Event.Handler()
}

/**
 * 160 新菜单协议(MenuBuilder)的传输层。
 *
 * 原版 160 起 [mindustry.ui.builder.UiBuilder] + [mindustry.ui.Menus.menuBuilder] 取代了旧的
 * Call.menu / Call.followUpMenu(官方标记 soft-deprecated)，支持富布局、按玩家下发与实时替换。
 *
 * 本层保持 MenuBuilder/MenuV2 的既有语义不变，仅替换传输:
 *  - 第 i 个可点选项回传 clicked 串 "o<i>"，与 callback 下标一一对应;
 *  - 玩家关闭/取消 -> MenuResult.wasCancelled() -> 下标 -1;
 *  - **每次发送生成新 token**: 同 id 菜单被替换时，被替换的旧对话框会回传一个"取消"
 *    (原版 MenuDialog 的 hidden 回调)，用 token 过滤可避免它误触发新菜单的等待。
 */
object MenuProtocol {
    private const val OPTION_PREFIX = "o"

    /** 选项标签里的"图标"前缀: "\u0001<图标名>\u0001<文本>"。
     *  按钮本来就支持 icon(原版 [mindustry.gen.Icon] 名字, 32 单位画在文字前), 但选项在传输层是纯字符串,
     *  所以用不可见字符把图标名带过去, 由 render 解出来。图标名必须是 Icon 里真实存在的
     *  (left/right/info/list/...), 写错会退化成 nomap 占位图。 */
    private const val ICON_MARK = '\u0001'

    /** 选项标签里的"宽度权重"前缀: "\u0002<占几列>\u0002<其余标签>"。
     *  分页行用它排成 1:2:1(箭头键窄、页码键宽), 默认 1 = 与同行其它格等宽。 */
    private const val WEIGHT_MARK = '\u0002'

    /** 给选项标签挂一个原版图标 */
    fun iconLabel(icon: String, text: String) = "$ICON_MARK$icon$ICON_MARK$text"

    /** 给选项标签挂"占几列"的宽度权重(1..12), 只对 render 里的加权行(分页行)有意义 */
    fun weighted(units: Int, label: String) = "$WEIGHT_MARK${units.coerceIn(1, 12)}$WEIGHT_MARK$label"

    /** 一格选项: 宽度权重 + 图标名 + 文本; [raw] 是去掉权重前缀后的原始标签(用于判断空占位) */
    private data class Cell(val units: Int, val icon: String?, val text: String, val raw: String)

    /** 解出选项标签里的宽度权重与图标前缀 */
    private fun parseCell(label: String): Cell {
        var raw = label
        var units = 1
        if (raw.length >= 3 && raw[0] == WEIGHT_MARK) {
            val end = raw.indexOf(WEIGHT_MARK, 1)
            if (end > 1) {
                units = raw.substring(1, end).toIntOrNull()?.coerceIn(1, 12) ?: 1
                raw = raw.substring(end + 1)
            }
        }
        val (icon, text) = splitIcon(raw)
        return Cell(units, icon, text, raw)
    }

    /** 旧协议(没有图标能力)下的可读标签: 纯图标键(文本为空白)换成箭头文字 */
    fun legacyLabel(raw: String): String {
        val cell = parseCell(raw)
        if (cell.text.isNotBlank()) return cell.text
        return when (cell.icon) {
            "left" -> "<-"
            "right" -> "->"
            null -> cell.text
            else -> "•"
        }
    }

    /** 解出 (图标名, 文本); 没有图标前缀时图标名为 null */
    fun splitIcon(raw: String): Pair<String?, String> {
        if (raw.length < 3 || raw[0] != ICON_MARK) return null to raw
        val end = raw.indexOf(ICON_MARK, 1)
        if (end <= 1) return null to raw
        return raw.substring(1, end) to raw.substring(end + 1)
    }

    /** 版式基准宽度(UI 单位): 桌面与手机一致。
     *  实测 300 会把 /banX <uid|uuid|idp> <时长> [理由] [--ip] 这类长标签折成 2~3 行, 更难看;
     *  宽度固定, 手机端只减少每页条目数(见 renderPaged)。 */
    private const val MENU_WIDTH = 400f
    private const val CELL_PAD = 4f
    /** smallPane 的竖向滚动条宽度(源图 12 单位), 从网格宽度里预留:
     *  否则内容与 pane 同宽, ScrollPane 会连横向滚动条一起打开(内容比可视区宽 12)。 */
    private const val SCROLLBAR_W = 12f
    private const val GRID_WIDTH = MENU_WIDTH - SCROLLBAR_W

    /** 估算内容高度用的行高与每行附加内边距(UI 单位) */
    private const val LINE_H = 30f
    private const val ROW_EXTRA = 14f
    /** 滚动区(pane)的高度上限(UI 单位) = 选项网格最多这么高, 超出就在框内滑动。
     *  面板本身始终按内容自适应(fillScreen=false, 有多少内容延伸到哪里), 不铺满整屏;
     *  桌面取 520、手机取 360(手机横屏 UI 单位高度只有 ~540, 再大面板会顶出屏幕)。 */
    private const val DESKTOP_PANE_MAX = 520f
    private const val MOBILE_PANE_MAX = 360f
    /** 滚动区上限的分档。面板总高 ≈ 上限 + 252(标题栏 42 + 正文 + 分页行 ~58 + 内边距 12 + 返回键行 ~80),
     *  所以上限必须 ≤ 屏幕高 - 252 才不顶出屏幕:
     *  - [SHORT_PANE_MAX] 矮屏兜底(总高 ~512, 540 高的窗口也放得下);
     *  - [PORTRAIT_PANE_MAX] 竖屏高屏(总高 ~1052): 一次显示明显更多条目 —— 用户诉求"竖屏时自动显示更多内容";
     *  condition 由客户端求值(height = 场景高 ÷ UI 缩放)、只能单次比较, 所以用嵌套 guard 分区,
     *  保证任何屏幕下只有一个 pane 被构建(否则内容会重复)。 */
    private const val SHORT_PANE_MAX = 260f
    private const val PORTRAIT_PANE_MAX = 800f
    /** 分档阈值(客户端逻辑高): >= [TALL_SCREEN_H] 才敢用竖屏 800; >= [SHORT_SCREEN_H] 用桌面/手机常规上限 */
    private const val SHORT_SCREEN_H = 800
    private const val TALL_SCREEN_H = 1100

    /** 面板底板的内边距(面板边缘与内容之间) */
    private const val PANEL_PAD = 6f
    /** 标题栏(底栏): 亮底 + 深字, 与面板的深色底形成对比, 让"标题属于这块面板"一眼可见 */
    private const val TITLE_BAR_COLOR = "#ffd37f"
    private const val TITLE_TEXT_COLOR = "#222222"
    private const val TITLE_PAD = 6f

    private class Session(val token: Long, val legacy: Boolean)

    private val sessions = mutableMapOf<Int, Session>()

    /** false 时回退旧 Call.menu 协议(soft-deprecated，仅作应急开关) */
    var enabled = true

    fun send(player: Player, menuId: Int, title: String, msg: String, options: List<List<String>>, followup: Boolean) {
        val legacy = !enabled
        val token = if (legacy) 0L else Random.nextLong()
        sessions[menuId] = Session(token, legacy)
        if (legacy) {
            // 旧协议(Call.menu)的按钮高度由客户端写死, 两行标签(命令名+描述)会溢出压到下一个按钮上。
            // 回退到旧协议时把标签压成单行, 保证「退回老样子」时版式是干净的;
            // 图标前缀在旧协议里没有意义(会显示成不可见字符), 纯图标键还原成可读的 <- / ->.
            val opts = options.map { row ->
                row.map { legacyLabel(it).replace("\n", "  ") }.toTypedArray()
            }.toTypedArray()
            if (followup) Call.followUpMenu(player.con, menuId, title, msg, opts)
            else Call.menu(player.con, menuId, title, msg, opts)
        } else {
            // 面板始终按内容自适应(fillScreen=false): 标题栏+正文+按钮有多高, 底板就延伸到哪里, 不铺满整屏。
            // 选项网格超过下面的高度上限时在框内滚动(见 render)。
            val maxPaneHeight = if (player.con.mobile) MOBILE_PANE_MAX else DESKTOP_PANE_MAX
            // title 传 null: 去掉客户端画在屏幕顶端、与内容分离的原版标题栏,
            // 标题改成画进面板自己的标题栏里(见 render)
            Call.menuBuilder(
                player.con, menuId, token, null, true, true, false,
                render(title, msg, options, maxPaneHeight)
            )
        }
    }

    /** 回包是否属于当前这一次发送(旧对话框的迟到回包会被丢弃) */
    fun accept(menuId: Int, token: Long): Boolean {
        val s = sessions[menuId] ?: return false
        return !s.legacy && s.token == token
    }

    fun optionIndex(result: MenuResult): Int {
        if (result.wasCancelled()) return -1
        val raw = result.result ?: return -1
        if (!raw.startsWith(OPTION_PREFIX)) return -1
        return raw.substring(OPTION_PREFIX.length).toIntOrNull() ?: -1
    }

    fun close(menuId: Int, followup: Boolean) {
        val s = sessions.remove(menuId)
        if (!followup) return
        if (s != null && s.legacy) Call.hideFollowUpMenu(menuId) else Call.hideMenuBuilder(menuId)
    }

    /**
     * 选项网格 -> MenuBuilder 节点树。
     *
     * 对齐: arc 的 Table 是**全局列宽共享**的 —— 所有行都进同一张表, 单格行会把第 1 列撑到满宽,
     * 后续多格行(如分页的 column(3))就会被挤到第 2/3 列上, 表现为"分页按钮偏右、跟上面的按钮对不齐"。
     * 因此这里统一按"最大列数"布局: 每行都占满 maxCols 列(单格行 colspan=maxCols), 网格自然对齐。
     *
     * 滚动与竖向排布: 网格包在 pane(ScrollPane) 里, pane 只占内容高度(不 growY), 高度上限是 maxPaneHeight,
     * 超出的部分在框内滚动 —— 面板底板因此**始终贴着内容收边**, 不会铺满屏幕。
     * 网格末尾还放了一行可撑高的空行(没有多余空间时高度为 0), 万一表格被撑高, 多余高度也只会落在最后一行下面。
     *
     * @param title 画进面板顶部标题栏(底栏)的标题; 颜色码会被去掉换成统一的"亮底深字"
     * @param maxPaneHeight 滚动区(pane)的高度上限; 估算内容超过它就启用可见滚动条(smallPane)并预留 12 单位
     */
    fun render(title: String, msg: String, options: List<List<String>>, maxPaneHeight: Float): NodeBuilder<*> {
        val rows = options.map { row -> row.dropLastWhile { it.isEmpty() } }.filter { it.isNotEmpty() }
        val maxCols = rows.maxOfOrNull { it.size } ?: 1
        // 每格解出 (宽度权重, 图标, 文本); 只有分页行带权重(1:2:1)
        val cells = rows.map { row -> row.map { parseCell(it) } }

        // 需要滚动(估算超过上限)时: smallPane 画可见细滚动条, 网格按 400-12 排版给滚动条让位;
        // 不需要滚动时: noBarPane, 网格用满 400 保证居中, 不画多余的滚动条。
        val scrolls = estimateHeight(title, msg, options) > maxPaneHeight
        val gridWidth = if (scrolls) GRID_WIDTH else MENU_WIDTH
        // align("top"): 网格若被撑高, 行从上往下排, 不要垂直居中
        val grid = UiBuilder.table().align("top")
        // 加权行(目前只有分页行)不进滚动区: 收集起来放到 pane 下面, 翻页键永远可见可点
        val pinnedRows = mutableListOf<NodeBuilder<*>>()
        var index = 0
        for (row in cells) {
            if (row.any { it.units > 1 }) {
                // 单独包一层子表按权重分宽, 避免影响外层表格的全局列宽;
                // 行宽取网格同宽, 行内按钮各自 pad(CELL_PAD) -> 左右边缘与上面按钮完全对齐。
                val totalUnits = row.sumOf { it.units }
                val rowTable = UiBuilder.table().width(gridWidth)
                row.forEach { cell ->
                    val w = (gridWidth - CELL_PAD * 2 * row.size) * cell.units / totalUnits
                    val idx = index++
                    val btn = UiBuilder.button(cell.text).clicked(OPTION_PREFIX + idx).width(w).pad(CELL_PAD)
                    if (cell.icon != null) btn.icon(cell.icon)
                    rowTable.add(btn)
                }
                pinnedRows.add(rowTable)
                continue
            }
            val span = maxOf(1, maxCols / row.size)      // 每格跨几列
            row.forEachIndexed { i, cell ->
                // 最后一格补齐剩余列, 保证每行正好占满 maxCols 列
                val cols = if (i == row.size - 1) maxCols - span * (row.size - 1) else span
                val w = gridWidth / maxCols * cols - CELL_PAD * 2
                val idx = index++   // 空位也要占一个下标, 保证回调下标与按钮一一对应
                if (cell.raw.isEmpty()) {
                    grid.add(UiBuilder.space().width(w).colspan(cols).pad(CELL_PAD))
                } else {
                    // 不设 height: 两行标签(名字+描述)需要更高, 写死高度会溢出重叠
                    val btn = UiBuilder.button(cell.text).clicked(OPTION_PREFIX + idx).width(w).colspan(cols).pad(CELL_PAD)
                    // 图标由客户端插在文字前(32 单位), 未指定图标名的选项保持原样
                    if (cell.icon != null) btn.icon(cell.icon)
                    grid.add(btn)
                }
            }
            grid.row()
        }

        // 末尾一个可撑高的空行: 没有多余高度时它是 0 高, 有的话也不会把按钮之间拉开
        grid.add(UiBuilder.space().growY().colspan(maxCols))
        grid.row()

        // 面板底板: 只占内容高度(不 growY), pane 封顶上限 -> 内容超过上限才在框内滚动。
        // pane 出两份、各带一个 condition(客户端求值): 屏幕高的用完整上限, 屏幕矮的用较小上限,
        // 保证整块面板在矮屏也不顶出屏幕。两份共用同一棵网格(条件不成立的那个节点不会被构建)。
        val paneStyle = if (scrolls) "smallPane" else "noBarPane"
        fun paneVariant(limit: Float, cond: String? = null): UiBuilder.PaneBuilder {
            val p = UiBuilder.pane().style(paneStyle).add(grid).width(MENU_WIDTH).pad(CELL_PAD)
                .maxHeight(limit).align("top")
            if (cond != null) p.condition(cond)
            return p
        }

        // ===== 整体面板: 标题栏 + 正文 + 选项网格 全部放进同一块底板 =====
        // 客户端对话框的背景是 window-empty(整块透明), 不自己铺底板的话, 标题/正文/按钮会像三块
        // 各自浮在游戏画面上("文字在上面, 按钮在下面, 本应该是一个整体")。
        // 底板用 pane(深色圆角面板), 标题栏用 bar(纯白底, 由 cell.color 染成 accent 色)。
        val panel = UiBuilder.table().background("pane")
        val titleText = title.replace(Regex("\\[[^]]*]"), "").trim()
        if (titleText.isNotEmpty()) {
            // 标题栏通栏(growX), 文字居中; 颜色码在这里被去掉, 保证"亮底深字"这一种样式
            val bar = UiBuilder.table().background("bar").color(TITLE_BAR_COLOR).growX()
            bar.add(
                // 限宽 + wrap: 长标题在栏内换行, 不会把整块面板撑得比 400 基准还宽
                UiBuilder.label(titleText).wrap().width(MENU_WIDTH - TITLE_PAD * 2)
                    .labelAlign("center").align("center").color(TITLE_TEXT_COLOR).pad(TITLE_PAD)
            )
            panel.add(bar)
            panel.row()
        }
        if (msg.isNotEmpty()) {
            // 正文按整宽换行, 否则长文本会把对话框撑得极宽; 放在滚动区外面, 滚动时保持可见
            panel.add(
                UiBuilder.label(msg).wrap().width(gridWidth).labelAlign("center").align("center").pad(CELL_PAD)
            )
            panel.row()
        }
        // 自适应分档(所有分支共用同一棵网格节点, 且条件互斥/嵌套 -> 任何屏幕下只构建一个 pane):
        //   竖屏(portrait): 高屏(>= 1100)给 800 的大滚动区, 一次显示更多条目; 中屏给常规上限; 矮屏兜底
        //   横屏(landscape): 高屏给常规上限(桌面 520 / 手机 360), 矮屏兜底
        // guard 是一层无背景的子表, 只用来承载 condition(UiTreeBuilder 对 condition 不成立的节点直接
        // continue, 其子树也不会被构建, 所以嵌套是安全的)。这里全部用显式语句拼, 不用 lambda 接收者写法。
        val portraitBranch = UiBuilder.table().condition("portrait")
        val portraitTall = UiBuilder.table().condition("height >= $TALL_SCREEN_H")
        portraitTall.add(paneVariant(PORTRAIT_PANE_MAX))
        val portraitMidOuter = UiBuilder.table().condition("height < $TALL_SCREEN_H")
        val portraitMid = UiBuilder.table().condition("height >= $SHORT_SCREEN_H")
        portraitMid.add(paneVariant(maxPaneHeight))
        val portraitLow = UiBuilder.table().condition("height < $SHORT_SCREEN_H")
        portraitLow.add(paneVariant(SHORT_PANE_MAX))
        portraitMidOuter.add(portraitMid)
        portraitMidOuter.add(portraitLow)
        portraitBranch.add(portraitTall)
        portraitBranch.add(portraitMidOuter)
        panel.add(portraitBranch)
        panel.row()

        val landscapeBranch = UiBuilder.table().condition("landscape")
        val landscapeTall = UiBuilder.table().condition("height >= $SHORT_SCREEN_H")
        landscapeTall.add(paneVariant(maxPaneHeight))
        val landscapeLow = UiBuilder.table().condition("height < $SHORT_SCREEN_H")
        landscapeLow.add(paneVariant(SHORT_PANE_MAX))
        landscapeBranch.add(landscapeTall)
        landscapeBranch.add(landscapeLow)
        panel.add(landscapeBranch)
        // 分页行固定在滚动区下方(不进 pane): 长菜单滚到一半也能直接翻页。
        // align("left") + 左内边距 = CELL_PAD: 网格在 pane 里是左对齐的, 这样分页行的按钮
        // 与列表按钮的左右边缘完全对齐(居中会让它整体右移半个滚动条宽)。
        pinnedRows.forEach { rowTable ->
            panel.row()
            // 外面套一层"与 pane 单元格同宽"的壳(400 + 2*CELL_PAD)并左对齐, 抵掉面板对单元格的居中;
            // 壳内左内边距 = pane 自身内边距 + 网格在 pane 里居中留下的偏移(滚动时 pane 内可视宽 400、
            // 网格 388, 居中后左右各留 6), 这样分页按钮与列表按钮的左右边缘完全对齐。
            val leftInset = CELL_PAD + (MENU_WIDTH - gridWidth) / 2f
            val wrap = UiBuilder.table().width(MENU_WIDTH + CELL_PAD * 2).align("left")
            wrap.add(rowTable.align("left").padLeft(leftInset))
            panel.add(wrap)
        }
        panel.row()

        // 根节点的属性只有 align/background/margin 会生效(其余被客户端丢弃), 所以底板必须是"子表";
        // 底板不 growY: 标题栏 + 正文 + 按钮有多高, 面板就延伸到哪里(fillScreen=false 下对话框 pack 到内容大小)。
        return UiBuilder.table().align("center").add(panel.pad(PANEL_PAD))
    }

    /**
     * 粗估"整块面板"的高度(UI 单位: 标题栏 + 正文 + 选项网格 + 面板内边距), 用于决定 pane 要不要滚动条。
     *
     * 估算按"显式换行 + 按宽度折行"粗算; 估错只影响观感: 估高只是提前启用 smallPane(内容不够高时
     * ScrollPane 不会画滚动条), 估低则由 render 的 maxHeight 兜底(pane 内滚动, 不会顶出屏幕)。
     */
    private fun estimateHeight(title: String, msg: String, options: List<List<String>>): Float {
        val rows = options.map { row -> row.dropLastWhile { it.isEmpty() } }.filter { it.isNotEmpty() }
        val maxCols = rows.maxOfOrNull { it.size } ?: 1
        // 面板底板: 标题栏(一行高 + 上下内边距) + 面板自身的上下内边距
        var height = PANEL_PAD * 2
        if (title.isNotBlank()) height += LINE_H + TITLE_PAD * 2
        if (msg.isNotEmpty()) height += msg.lines().size * LINE_H
        rows.forEach { row ->
            val span = maxOf(1, maxCols / row.size)
            var lines = 1
            row.forEachIndexed { i, name ->
                val cell = parseCell(name)
                if (cell.raw.isEmpty()) return@forEachIndexed
                val cols = if (i == row.size - 1) maxCols - span * (row.size - 1) else span
                // 按钮自身还有约 20 单位左右内边距, 折行宽度按它扣掉
                val textW = GRID_WIDTH / maxCols * cols - CELL_PAD * 2 - 20f
                // 高度只跟文字有关, 图标/宽度前缀(都是不可见字符)要先去掉再算
                lines = maxOf(lines, wrappedLines(cell.text, textW))
            }
            height += lines * LINE_H + ROW_EXTRA
        }
        return height
    }

    /** 粗算文本折行数: 中文/全角按 1 字宽、其余按 0.5 字宽, 字宽取默认字体 30 单位 */
    private fun wrappedLines(text: String, width: Float): Int {
        val plain = text.replace(Regex("\\[[^]]*]"), "")
        return plain.lines().sumOf { line ->
            var units = 0f
            line.forEach { c -> units += if (c.code >= 0x2E80) 1f else 0.5f }
            maxOf(1, kotlin.math.ceil(units * LINE_H / width).toInt())
        }
    }
}

@Suppress("unused", "MemberVisibilityCanBePrivate")
open class MenuBuilder<T : Any>(
    open val followup: Boolean,
    private val block: suspend MenuBuilder<T>.() -> Unit = { }
) {
    protected constructor() : this(false, {})
    constructor(block: suspend MenuBuilder<T>.() -> Unit = {}) : this(false, block)
    constructor(title: String, block: suspend MenuBuilder<T>.() -> Unit) : this(block) {
        this.title = title
    }

    /** sendTo 时设置, 供 build() 中的 {tr} 解析玩家语言 */
    @PublishedApi
    internal var currentPlayer: Player? = null

    @DslMarker
    annotation class MenuBuilderDsl
    object RefreshReturn : Throwable("This method should only call in callback", null, false, false)
    open class FlagOptionBuilder {
        lateinit var name: String

        @MenuBuilderDsl
        @Throws(CommandInfo.Return::class)
        open fun option(name: String) {
            this.name = name
            CommandInfo.Return()
        }

        /** This option will always call [MenuBuilder.refresh] when selected*/
        @MenuBuilderDsl
        fun refreshOption(name: String): Nothing {
            option(name)
            throw RefreshReturn
        }

        object Dummy : FlagOptionBuilder() {
            override fun option(name: String) = Unit
        }
    }

    private val menu = mutableListOf<MutableList<String>>()
    private val callback = mutableListOf<suspend () -> T>()

    @MenuBuilderDsl
    var title = ""

    @MenuBuilderDsl
    var msg = ""

    /** 是否自动在菜单底部追加"关闭"按钮,默认true */
    @MenuBuilderDsl
    var autoCloseButton: Boolean = true

    protected open suspend fun build() {
        block()
    }

    @MenuBuilderDsl
    fun newRow() = menu.add(mutableListOf())

    @MenuBuilderDsl
    fun option(name: String, body: suspend () -> T) {
        menu.last().add(name)
        callback.add(body)
    }

    @MenuBuilderDsl
    suspend fun lazyOption(body: suspend FlagOptionBuilder.() -> T) {
        val name = FlagOptionBuilder().let {
            try {
                it.body()
                error("You must call option in body")
            } catch (e: CommandInfo.Return) {
                it.name
            }
        }
        option(name) {
            FlagOptionBuilder.Dummy.body()
        }
    }

    ///api for callback
    /** mark to send refreshed menu again*/
    @MenuBuilderDsl
    fun refresh(): Nothing {
        throw RefreshReturn
    }

    private val _menuId = Random.nextInt()

    /** @param timeoutMillis note this is only timeout for player select, not timeout for this function (due to callback and refresh)*/
    suspend fun sendTo(player: Player, timeoutMillis: Int = 60_000): T? {
        currentPlayer = player
        menu.clear();callback.clear()
        newRow();build()
        // 同上: 新协议下客户端自带关闭按钮, 不再追加, 避免重复
        if (autoCloseButton && !MenuProtocol.enabled) {
            newRow(); option("{tr coreMenu.close}".with("receiver" to player).toString()) { throw CommandInfo.Return }
        }

        try {
            return withTimeoutOrNull(timeoutMillis.toLong()) {
                MenuProtocol.send(player, _menuId, title, msg, menu, followup)
                //原版返回值，代表选中n个选项，可能 -1 代表主动关闭
                val ret = MenuBuilder::class.java.getContextScript().nextEvent<MenuChooseEvent> {
                    it.player == player && it.menuId == _menuId
                }.value
                callback.getOrNull(ret)
            }?.let {
                try {
                    it.invoke()
                } catch (e: RefreshReturn) {
                    return sendTo(player, timeoutMillis)
                } catch (e: CommandInfo.Return) {
                    null
                }
            }
        } finally {
            close()
        }
    }

    fun close() {
        MenuProtocol.close(_menuId, followup)
    }
}

open class PagedMenuBuilder<T>(
    val items: List<T>,
    var selectedPage: Int = 1,
    val prePage: Int = 10,
    val itemRender: suspend PagedMenuBuilder<T>.(T) -> Unit = {},
) : MenuBuilder<Unit>(true) {
    init { autoCloseButton = false }
    protected open suspend fun renderItem(item: T) = itemRender(item)
    override suspend fun build() {
        val (page, totalPage) = calPage(selectedPage, prePage, items.size)
        items.subList((page - 1) * prePage, (page * prePage).coerceAtMost(items.size))
            .forEach { renderItem(it);newRow() }
        repeat(page * prePage - items.size) {
            option("") { refresh() };newRow()
        }
        // 翻页键只显示原版 left/right 箭头图标(标签用空格占位, 空串会被当成占位空格),
        // 并按 1:2:1 分宽: 箭头键窄、页码键宽(见 MenuProtocol.weighted)。
        // 旧协议回退时 legacyLabel 会还原成 <- / -> 文字
        option(MenuProtocol.weighted(1, MenuProtocol.iconLabel("left", " "))) { selectedPage = page - 1;refresh() }
        option(MenuProtocol.weighted(2, "$page/$totalPage")) { refresh() }
        option(MenuProtocol.weighted(1, MenuProtocol.iconLabel("right", " "))) { selectedPage = page + 1;refresh() }
        // 新协议下客户端对话框自带关闭按钮(Menus.menuBuilder 的 dialog.addCloseButton()), 再追加会多一个返回键
        if (!MenuProtocol.enabled) {
            newRow()
            option("{tr coreMenu.close}".with("receiver" to currentPlayer!!).toString()) {}
        }
    }
}

/** 按行构造菜单并发送(来自 MDT Lord of War 移植依赖), 每行多个选项 */
suspend fun <T : Any> sendMenuBuilder(
    player: Player,
    timeoutMillis: Int,
    title: String,
    msg: String,
    builder: suspend MutableList<List<Pair<String, suspend () -> T>>>.() -> Unit
): T? {
    return MenuBuilder<T> {
        this.title = title
        this.msg = msg
        buildList { builder() }.forEachIndexed { i, l ->
            if (i != 0) newRow()
            l.forEach { option(it.first, it.second) }
        }
    }.sendTo(player, timeoutMillis)
}