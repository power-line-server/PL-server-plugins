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
import kotlin.random.Random


data class MenuChooseEvent(
    val player: Player, val menuId: Int, val value: Int
) : Event, ReceivedEvent {
    override var received: Boolean = false

    companion object : Event.Handler()
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
                val options = menu.map { it.toTypedArray() }.toTypedArray()
                if (followup)
                    Call.followUpMenu(player.con, _menuId, title, msg, options)
                else
                    Call.menu(player.con, _menuId, title, msg, options)
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
        if (!followup) return
        Call.hideFollowUpMenu(_menuId)
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