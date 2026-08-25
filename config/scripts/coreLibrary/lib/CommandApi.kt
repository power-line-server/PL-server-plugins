@file:Suppress("DuplicatedCode", "MemberVisibilityCanBePrivate", "unused")

package coreLibrary.lib

import cf.wayzer.placehold.VarString
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.define.ScriptDsl
import cf.wayzer.scriptAgent.events.ScriptDisableEvent
import cf.wayzer.scriptAgent.listenTo
import cf.wayzer.scriptAgent.thisContextScript
import cf.wayzer.scriptAgent.util.DSLBuilder
import coreLibrary.lib.util.menu
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

sealed class CommandContext : DSLBuilder(), Cloneable {
    interface IReceiver {
        suspend fun hasPermission(node: String): Boolean
    }

    object ConsoleReceiver : IReceiver {
        override suspend fun hasPermission(node: String): Boolean = true
    }

    var receiver: IReceiver = ConsoleReceiver

    // Should init if not empty
    var prefix: String = ""

    // Should init if not empty
    var arg = emptyList<String>()

    /** 快捷指令(菜单点击/搜索选中触发, 非玩家文本输入): onCommandExecuted 仅记录此类 */
    var shortcut: Boolean = false

    /** use for arg like '-v' */
    fun checkArg(p: String): Boolean {
        if (p !in arg) return false
        arg = arg.filterNot { it == p }
        return true
    }

    inline fun <T> resolveArg(name: String, default: T, block: (String) -> T): T {
        if (arg.isEmpty()) return default
        try {
            val value = block(arg.first())
            arg = arg.drop(1)
            return value
        } catch (e: Exception) {
            returnReply("{tr command.error.parse}".with("name" to name, "e" to e))
        }
    }

    /**
     * message callback
     * should support async, otherwise set to {} after use
     * should support call from other thread, switch thread when need
     */
    var reply: (msg: VarString) -> Unit = {}

    // Should not null if doing TabComplete
    @Deprecated("use TabComplete type", level = DeprecationLevel.ERROR)
    var replyTabComplete: ((list: List<String>) -> Nothing)? = null

    // Should init in RootCommand
    @set:Deprecated("implement IReceiver.hasPermission")
    var hasPermission: suspend (node: String) -> Boolean = { receiver.hasPermission(it) }

    fun subContext(): CommandContext {
        return (clone() as CommandContext).apply {
            if (arg.isEmpty()) return@apply
            prefix += arg[0] + " "
            arg = arg.subList(1, arg.size)
        }
    }

    class Command : CommandContext()
    class TabComplete : CommandContext() {
        var result = mutableListOf<String>()
    }
}

typealias CommandHandlerOld = suspend CommandContext.() -> Unit

fun interface CommandHandler {
    //Default only handle Command
    fun CommandContext.canHandle() = context is CommandContext.Command

    suspend fun CommandContext.handle()
}

@Deprecated("use CommandHandler.canHandle logic")
interface TabCompleter {
    suspend fun onComplete(context: CommandContext)
    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    @Deprecated("move to CommandContext", level = DeprecationLevel.HIDDEN)
    fun CommandContext.onComplete(index: Int, body: () -> List<String>) = onComplete(index, body)
}

@Suppress("DEPRECATION")
class CommandInfo(
    val script: Script?,
    val name: String,
    val description: VarString,
    var aliases: List<String> = emptyList(),
) : DSLBuilder(), CommandHandler, TabCompleter {
    constructor(script: Script?, name: String, description: VarString, init: CommandInfo.() -> Unit)
            : this(script, name, description) {
        init()
    }

    constructor(script: Script?, name: String, description: String, init: CommandInfo.() -> Unit = {})
            : this(script, name, description.with(), init)
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    constructor(script: Script?, name: String, description: VarString) : this(script, name, description)

    val attrs: List<CommandHandler> = mutableListOf()
    var usage: String = ""

    @Deprecated("use requirePermission(permission)")
    var permission: String = ""
    private var onComplete: CommandHandler? = null
    private var body: CommandHandler = CommandHandler {}
    private var frozen = false

    fun freeze() {
        if (frozen) return
        @Suppress("DEPRECATION")
        if (permission.isNotEmpty())
            attr(Commands.Permission(permission))
        frozen = true
    }

    /**
     * Add an attr to this command, will run before body
     */
    @CommandBuilder
    fun attr(beforeBody: CommandHandler) {
        if (frozen) error("This command is already frozen, you must add attr before body")
        (attrs as MutableList).add(beforeBody)
    }

    inline fun <reified T> attr() = attrs.filterIsInstance<T>()

    @Deprecated("replace CommandHandler", level = DeprecationLevel.HIDDEN)
    fun onComplete(block: CommandHandlerOld) = onComplete {
        block.invoke(context)
    }
    @CommandBuilder
    fun onComplete(body: CommandHandler) {
        this.onComplete = body
    }

    @Deprecated("replace CommandHandler", level = DeprecationLevel.HIDDEN)
    fun body(block: CommandHandlerOld) {
        if (block is CommandHandler) return body(block)
        body {
            block.invoke(context)
        }
    }

    @CommandBuilder
    fun body(body: CommandHandler) {
        if (frozen) error("This command is already frozen")
        this.body = body
        freeze()
    }

    override fun CommandContext.canHandle(): Boolean =
        context is CommandContext.TabComplete || body.canHandle()

    override suspend fun onComplete(context: CommandContext) {
        //1. explicit first
        onComplete?.let {
            return context.run { it.handle() }
        }

        //2. New TabComplete logic
        with(context) {
            if (context is CommandContext.TabComplete && body.canHandle()) {
                return body.handle()
            }
        }

        //3. fallback to old logic
        (body as? TabCompleter)?.onComplete(context)
    }

    override suspend fun CommandContext.handle() {
        if (context is CommandContext.TabComplete)
            return onComplete(context)
        try {
            attrs.forEach { it.handle() }
            // 叶子命令(非子命令容器)执行钩子: coreMindustry 用它记录玩家快捷指令到终端, 容器如 /skill 的菜单由子命令各自触发
            // runCatching: 日志失败不影响命令执行
            // 仅记录快捷指令(shortcut): 玩家直接输入的命令终端有原版回显, 无需再打日志
            if (body !is Commands && shortcut) runCatching { Commands.onCommandExecuted?.invoke(this, this@CommandInfo) }
            body.handle()
        } catch (e: CancellationException) {
            if (e !is Return)
                this.thisContextScript().logger.log(
                    Level.WARNING, "You should not cancel command. If you need exit, using CommandInfo.Return()", e
                )
        } catch (e: Exception) {
            reply("{tr command.error.exception}".with("msg" to (e.message ?: "")))
            e.printStackTrace()
        }
    }

    @CommandBuilder
    @Deprecated("use requirePermission(permission)")
    fun CommandContext.replyNoPermission(): Nothing {
        reply("{tr command.error.noPermission}".with())
        Return()
    }

    @CommandBuilder
    fun CommandContext.replyUsage(): Nothing {
        reply("{tr command.error.usage}".with("prefix" to prefix, "usage" to usage.with()))
        Return()
    }

    override fun toString(): String {
        return "CommandInfo(name='$name', script=$script, description=$description)"
    }

    @Suppress("ObjectInheritsException")
    data object Return : CancellationException("Direct return command") {
        private fun readResolve(): Any = Return
        @CommandBuilder
        operator fun invoke(): Nothing {
            throw this
        }
    }

    @DslMarker
    annotation class CommandBuilder
}

@Suppress("DEPRECATION")
open class Commands : CommandHandler, TabCompleter {
    fun interface Hidden : CommandHandler {
        /** 当前命令是否可用, 用于[Commands.helpCommand]处理 */
        suspend fun CommandContext.visible(): Boolean
        override suspend fun CommandContext.handle() {
            if (!visible()) returnReply("{tr command.error.unavailable}".with())
        }
    }

    data class Permission(val permission: String) : Hidden {
        override suspend fun CommandContext.visible(): Boolean = hasPermission(permission)
        override suspend fun CommandContext.handle() {
            if (!visible()) returnReply("{tr command.error.noPermission}".with())
        }
    }

    private val watchers = mutableListOf<CommandsWatcher>()
    protected val nameMap = LinkedHashMap<String, CommandInfo>()
    open fun subCommands(): Map<String, CommandInfo> = nameMap
    fun getSub(name: String): CommandInfo? = subCommands()[name.lowercase()]

    override fun CommandContext.canHandle(): Boolean = true
    override suspend fun onComplete(context: CommandContext) = context.run { handle() }

    override suspend fun CommandContext.handle() {
        onComplete(0) { subCommands().keys.toList() }
        if (arg.isEmpty()) return helpCommand.handle()

        val name = arg.first()
        getSub(name)?.let {
            return subContext().run { it.handle() }
        }
        // 根命令(prefix为空或为通配符占位)提示用 /help; 子命令(prefix非空)提示用该命令(无参数显示帮助)
        // 终端(ConsoleReceiver)无需斜杠, 提示用 help; 玩家保留 /help 引导
        // 不自动打印指令列表: 玩家聊天区渲染无用且易误导, 引导用户自行输入 /help
        val console = receiver is CommandContext.ConsoleReceiver
        if (prefix.isEmpty() || prefix.trim() == "*") {
            reply(
                if (console) "{tr command.error.invalidRoot.console}".with("name" to name)
                else "{tr command.error.invalidRoot}".with("name" to name)
            )
        } else {
            reply(
                if (console) "{tr command.error.invalid.console}".with("name" to name, "prefix" to prefix.trim())
                else "{tr command.error.invalid}".with("name" to name, "prefix" to prefix.trim())
            )
        }
    }

    protected fun addSub(name: String, command: CommandInfo, isAliases: Boolean) {
        synchronized(nameMap) {
            val existed = nameMap[name.lowercase()]?.takeIf { it.script?.enabled == true } ?: let {
                nameMap[name.lowercase()] = command
                return
            }
            if (existed == command) return
            if (isAliases) {
                Logger.getLogger("[CommandApi]").warning("duplicate aliases $name($command) with $existed")
            } else {
                Logger.getLogger("[CommandApi]").warning("replace command $name: NOW:$command OLD:$existed")
                nameMap[name.lowercase()] = command //name is more important
            }
        }
    }

    fun addSub(command: CommandInfo) {
        addSub(command.name, command, false)
        command.aliases.forEach {
            addSub(it, command, true)
        }
        watchers.forEach { it.onAdd(command) }
    }

    fun removeSub(command: CommandInfo) {
        watchers.forEach { it.onRemove(command) }
        synchronized(nameMap) {
            nameMap.remove(command.name.lowercase(), command)
            command.aliases.forEach {
                nameMap.remove(it.lowercase(), command)
            }
        }
    }

    fun removeAll(script: Script) {
        // 并发停止脚本时, nameMap (LinkedHashMap) 非线程安全:
        // removeSub 的 nameMap.remove 会与这里的 nameMap.values.toList 并发, 产生 null 元素.
        // synchronized(nameMap) 保证快照期间无并发修改.
        val toRemove = synchronized(nameMap) {
            nameMap.values.toList().filter { it.script == script }
        }
        toRemove.forEach { removeSub(it) }
    }

    operator fun plusAssign(command: CommandInfo) = addSub(command)
    @Deprecated(
        "recommend listenTo<ScriptDisableEvent> { removeAll(script) }",
        ReplaceWith("script.onDisable { removeAll(script) }")
    )
    fun autoRemove(script: Script) {
        script.onDisable {
            removeAll(script)
        }
    }

    interface CommandsWatcher {
        fun onAdd(command: CommandInfo)
        fun onRemove(command: CommandInfo)
    }

    fun addWatcher(script: Script, watcher: CommandsWatcher, fireOnRegister: Boolean = true) {
        synchronized(watchers) {
            watchers.add(watcher)
            script.onDisable {
                synchronized(watchers) {
                    watchers.remove(watcher)
                }
                if (fireOnRegister)
                    synchronized(nameMap) { nameMap.values.toSet() }.forEach { watcher.onRemove(it) }
            }
        }
        if (fireOnRegister)
            synchronized(nameMap) { nameMap.values.toSet() }.forEach { watcher.onAdd(it) }
    }

    val helpCommand = CommandInfo(null, "help", "{tr command.help.desc}".with()).apply {
        usage = "[-v] [page=1]"
        aliases = listOf("帮助")
        body {
            val showAll = checkArg("-v")
            val page = resolveArg("page", 1) { it.toIntOrNull() ?: 1 }
            prefix = prefix.removeSuffix("help ").removeSuffix("帮助 ")
            if (showAll && !hasPermission("command.detail"))
                return@body reply("{tr command.help.detailPermission}".with())

            helpOverwrite?.invoke(context, this@Commands, showAll, page)

            val title = if (prefix.isEmpty()) "{tr command.help.title}".with()
            else "{tr command.help.titlePrefix}".with("prefix" to prefix)
            var commands = subCommands().let { cmds ->
                //Try to keep order if possible
                if (cmds is LinkedHashMap) {
                    val set = mutableSetOf<CommandInfo>()
                    cmds.values.mapNotNull { if (set.add(it)) it else null }
                } else {
                    cmds.values.toSet().sortedBy { it.name }
                }
            }
            if (!showAll) commands = commands.filter { info ->
                info.attrs.all { it !is Hidden || it.visible() }
            }
            reply(menu(title, commands, page, 10) {
                helpInfo(it, showAll)
            })
        }
        addSub(this)
    }

    object Root : Commands() {
        init {
            this += CommandInfo(thisContextScript(), "ScriptAgent", "{tr command.scriptAgent.desc}".with(), listOf("sa")).apply {
                requirePermission("scriptAgent.admin")
                body(controlCommand)
            }
            thisContextScript().listenTo<ScriptDisableEvent> {
                removeAll(script)
            }
        }

        var subCommandOverwrite: ((Map<String, CommandInfo>) -> Map<String, CommandInfo>)? = null
        override fun subCommands(): Map<String, CommandInfo> {
            val ret = super.subCommands()
            return subCommandOverwrite?.invoke(ret) ?: ret
        }

        suspend fun tabComplete(block: CommandContext.TabComplete.() -> Unit): List<String> {
            val ctx = CommandContext.TabComplete().apply(block)
            try {
                ctx.run { handle() }
            } catch (_: CommandInfo.Return) {
            }
            return ctx.result
        }
    }

    companion object {
        val controlCommand = Commands()

        fun CommandContext.helpInfo(it: CommandInfo, showDetail: Boolean): VarString {
            val alias = if (it.aliases.isEmpty()) "" else it.aliases.joinToString(prefix = "(", postfix = ")")
            val detail = buildString {
                if (!showDetail) return@buildString
                if (it.script != null) append(" | ${it.script.id}")
                it.attr<Permission>().firstOrNull()?.let { append(" | ${it.permission}") }
            }
            // usage 为空时使用紧凑模板, 避免 aliases 后出现多余空格
            val usageStr = it.usage
            val template = if (usageStr.isEmpty()) {
                "[light_gray]{prefix}[light_yellow]{name}[gray]{aliases}  [light_cyan]{desc}[gray]{detail}"
            } else {
                "[light_gray]{prefix}[light_yellow]{name}[gray]{aliases} [light_gray]{usage}  [light_cyan]{desc}[gray]{detail}"
            }
            return template.with(
                "prefix" to prefix, "name" to it.name, "aliases" to alias,
                "usage" to usageStr.with(), "desc" to it.description, "detail" to detail
            )
        }

        var helpOverwrite: (suspend CommandContext.(cmds: Commands, showAll: Boolean, page: Int) -> Unit)? = null

        /** 玩家命令(快捷指令)执行钩子: coreMindustry 注册, 叶子命令执行前触发, 用于终端记录 */
        var onCommandExecuted: (suspend CommandContext.(command: CommandInfo) -> Unit)? = null
    }
}

@ScriptDsl
inline fun Script.command(
    name: String,
    description: VarString,
    commands: Commands = Commands.Root,
    init: CommandInfo.() -> Unit
) {
    val command = CommandInfo(this, name, description).apply(init)
    onEnable {
        commands.addSub(command)
    }
}

@ScriptDsl
inline fun Script.command(name: String, description: String, init: CommandInfo.() -> Unit) {
    command(name, description.with()) { init() }
}

@CommandInfo.CommandBuilder
fun CommandInfo.requirePermission(permission: String) {
    attr(Commands.Permission(permission))
}
