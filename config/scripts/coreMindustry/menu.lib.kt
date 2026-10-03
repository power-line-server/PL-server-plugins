package coreMindustry

import cf.wayzer.placehold.PlaceHoldApi.with
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

    /** 与原版旧菜单一致的版式基准(400 宽/50 高/pad 4)，保证迁移后观感不突变 */
    private const val MENU_WIDTH = 400f
    private const val BUTTON_HEIGHT = 50f
    private const val CELL_PAD = 4f

    private class Session(val token: Long, val legacy: Boolean)

    private val sessions = mutableMapOf<Int, Session>()

    /** false 时回退旧 Call.menu 协议(soft-deprecated，仅作应急开关) */
    var enabled = true

    fun send(player: Player, menuId: Int, title: String, msg: String, options: List<List<String>>, followup: Boolean) {
        val legacy = !enabled
        val token = if (legacy) 0L else Random.nextLong()
        sessions[menuId] = Session(token, legacy)
        if (legacy) {
            val opts = options.map { it.toTypedArray() }.toTypedArray()
            if (followup) Call.followUpMenu(player.con, menuId, title, msg, opts)
            else Call.menu(player.con, menuId, title, msg, opts)
        } else {
            Call.menuBuilder(player.con, menuId, token, title, true, true, false, render(msg, options))
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

    /** 选项网格 -> MenuBuilder 节点树(标题由对话框自带, msg 作为正文标签) */
    fun render(msg: String, options: List<List<String>>): NodeBuilder<*> {
        val root = UiBuilder.table()
        if (msg.isNotEmpty()) {
            root.add(UiBuilder.label(msg).wrap().width(MENU_WIDTH).align("center").pad(CELL_PAD))
            root.row()
        }
        var index = 0
        options.forEach { row ->
            if (row.isEmpty()) return@forEach
            val count = row.size
            val full = MENU_WIDTH - (count - 1) * CELL_PAD * 2
            val each = full / count
            row.forEachIndexed { i, name ->
                val idx = index++
                val w = if (i == count - 1) full - each * (count - 1) else each
                if (name.isEmpty()) {
                    root.add(UiBuilder.space().width(w).height(BUTTON_HEIGHT).pad(CELL_PAD))
                } else {
                    root.add(UiBuilder.button(name).clicked(OPTION_PREFIX + idx).width(w).height(BUTTON_HEIGHT).pad(CELL_PAD))
                }
            }
            root.row()
        }
        return root
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
        if (autoCloseButton) {
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
        option("<-") { selectedPage = page - 1;refresh() }
        option("$page/$totalPage") { refresh() }
        option("->") { selectedPage = page + 1;refresh() }
        newRow()
        option("{tr coreMenu.close}".with("receiver" to currentPlayer!!).toString()) {}
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