package coreMindustry

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.thisContextScript
import cf.wayzer.scriptAgent.util.DSLBuilder
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.util.calPage
import coreLibrary.lib.util.nextEvent
import kotlinx.coroutines.withTimeoutOrNull
import mindustry.gen.Call
import mindustry.gen.Player
import kotlin.properties.ReadWriteProperty
import kotlin.random.Random
import kotlin.reflect.KProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Suppress("unused", "MemberVisibilityCanBePrivate")
@MenuV2.MenuBuilderDsl
open class MenuV2(
    val player: Player,
    val followup: Boolean = false,
    private val block: suspend MenuV2.() -> Unit = { }
) {
    @DslMarker
    annotation class MenuBuilderDsl
    object RefreshReturn : Throwable("This method should only call in callback", null, false, false) {
        private fun readResolve(): Any = RefreshReturn
    }

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
    private val callback = mutableListOf<suspend () -> Unit>()
    var onCancel: suspend () -> Unit = {}
    private var closed = false

    @MenuBuilderDsl
    var title = ""

    @MenuBuilderDsl
    var msg = ""

    val sessionState = mutableMapOf<String, Any?>()
    @MenuBuilderDsl
    inline fun <reified T : Any?> stateKey(
        default: T,
        keyPrefix: String = ""
    ): DSLBuilder.NameGet<ReadWriteProperty<Any?, T>> =
        DSLBuilder.NameGet { name ->
            val key = keyPrefix + name
            return@NameGet object : ReadWriteProperty<Any?, T> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): T {
                    return sessionState.getOrPut(key) { default } as T
                }

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                    sessionState[key] = value
                }
            }
        }

    @MenuBuilderDsl
    var columnPreRow = 1

    /** 是否自动在菜单底部追加"关闭"按钮,默认true */
    @MenuBuilderDsl
    var autoCloseButton: Boolean = true

    protected open suspend fun build() = block()

    @MenuBuilderDsl
    inline fun column(num: Int, body: () -> Unit) {
        newRow()
        val bak = columnPreRow
        columnPreRow = num
        body()
        columnPreRow = bak
        newRow()
    }

    @MenuBuilderDsl
    fun newRow() {
        if (menu.isNotEmpty()) {
            if (menu.last().size == 0) return
            while (menu.last().size < columnPreRow) {
                option("", ::refresh)
            }
        }
        menu.add(mutableListOf())
    }

    @MenuBuilderDsl
    fun option(name: String, body: suspend () -> Unit) {
        if (menu.isEmpty() || menu.last().size >= columnPreRow)
            newRow()
        menu.last().add(name)
        callback.add(body)
    }

    /** 带原版图标的选项: 图标名用 [mindustry.gen.Icon] 里的名字(如 left/right/info/list), 画在文字前 */
    @MenuBuilderDsl
    fun option(name: String, icon: String, body: suspend () -> Unit) =
        option(MenuProtocol.iconLabel(icon, name), body)

    @MenuBuilderDsl
    fun subMenu(title: String, chooseTimeout: Duration? = 60.seconds, builder: suspend MenuV2.() -> Unit) {
        option(title) {
            this.title = title
            menu.clear(); callback.clear()
            builder.invoke(this)
            var back = false
            newRow(); option("{tr coreMenu.back}".with("receiver" to player).toString()) { back = true }

            send(rebuild = false)
            if (chooseTimeout == null) await()
            else awaitWithTimeout(chooseTimeout)

            if (back) refresh()
        }
    }

    @MenuBuilderDsl
    suspend fun lazyOption(body: suspend FlagOptionBuilder.() -> Unit) {
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

    suspend fun send(rebuild: Boolean = true): MenuV2 {
        closed = false
        if (rebuild) {
            menu.clear(); callback.clear()
            build()
        }
        // 新菜单协议下客户端对话框自带关闭按钮(Menus.menuBuilder 里的 dialog.addCloseButton()),
        // 再追加一个就会出现两个关闭/一个多余的"返回", 所以只在旧协议下才补。
        if (autoCloseButton && !MenuProtocol.enabled) {
            newRow()
            option("{tr coreMenu.close}".with("receiver" to player).toString()) { close() }
        }
        while (menu.isNotEmpty() && menu.last().isEmpty()) menu.removeLast()
        if (menu.isEmpty()) error("Menu is Empty")
        MenuProtocol.send(player, _menuId, title, msg, menu, followup)
        return this
    }

    suspend fun await() {
        while (true) {
            val callback = script.nextEvent<MenuChooseEvent> { it.player == player && it.menuId == _menuId }.value
                .let { callback.getOrNull(it) } ?: onCancel
            try {
                callback.invoke()
                if (closed || !followup) break
            } catch (e: RefreshReturn) {
                send()
            }
        }
    }

    /** @param chooseTimeout note this is only timeout for player select, not timeout for this function (due to callback and refresh)*/
    suspend fun awaitWithTimeout(chooseTimeout: Duration = 60.seconds) {
        while (true) {
            val callback = withTimeoutOrNull(chooseTimeout) {
                script.nextEvent<MenuChooseEvent> { it.player == player && it.menuId == _menuId }.value
            }?.let { callback.getOrNull(it) } ?: onCancel
            try {
                callback.invoke()
                if (closed || !followup) break
            } catch (e: RefreshReturn) {
                send()
            }
        }
    }

    fun close() {
        closed = true
        MenuProtocol.close(_menuId, followup)
    }

    companion object {
        private val script = thisContextScript()
    }
}

@MenuV2.MenuBuilderDsl
inline fun <T> MenuV2.renderPaged(
    list: List<T>,
    initialPage: Int = 1,
    // 手机竖屏高度有限, 每页少放几条, 否则菜单几乎顶满整屏
    prePage: Int = if (player.con != null && player.con.mobile) 6 else 9,
    key: String = "",
    itemRender: (T) -> Unit
) {
    var selectedPage by stateKey(initialPage, keyPrefix = "renderPaged@$key-")
    val (page, totalPage) = calPage(selectedPage, prePage, list.size)
    // 只渲染本页真实条目。原来用 option("") 把不足一页的空位补齐, 但旧协议(Call.menu)会把空串
    // 原样画成一个空按钮 —— 条目少的分页菜单(如 /skill 帮助只有 1 条)会显示成一串空框, 玩家看起来
    // 就是"菜单错位"。补空位只是为了让对话框高度稳定, 不值得用可用性换。
    for (i in (page - 1) * prePage until minOf(page * prePage, list.size)) {
        itemRender(list[i])
    }
    column(3) {
        // 翻页循环: 第一页点左跳到最后一页, 最后一页点右跳到第一页。
        // 只显示原版 left/right 箭头图标: 标签用一个空格占位(空串会被当成占位空格, 按钮会消失);
        // 三格按 1:2:1 分宽(箭头键窄、页码键宽), 旧协议回退时 legacyLabel 会还原成 <- / -> 文字。
        option(MenuProtocol.weighted(1, MenuProtocol.iconLabel("left", " "))) {
            selectedPage = if (page <= 1) totalPage.coerceAtLeast(1) else page - 1; refresh()
        }
        option(MenuProtocol.weighted(2, "$page/$totalPage"), this::refresh)
        option(MenuProtocol.weighted(1, MenuProtocol.iconLabel("right", " "))) {
            selectedPage = if (page >= totalPage) 1 else page + 1; refresh()
        }
    }
}
