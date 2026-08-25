package wayzer

import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.define.ScriptDsl
import cf.wayzer.scriptAgent.emitAsync
import cf.wayzer.scriptAgent.thisContextScript
import coreLibrary.lib.*
import coreMindustry.MenuBuilder
import coreMindustry.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import mindustry.gen.Groups
import mindustry.gen.Player
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
class VoteEvent(
    scope: CoroutineScope,
    val starter: Player,
    val voteDesc: PlaceHoldContext,
    val extDesc: String = "",
    val supportSingle: Boolean = false,
    val canVote: (Player) -> Boolean = { !it.dead() },
    val requireNum: (all: Int) -> Int = { ceil(it * 0.6).toInt() },
    val fastSuccess: Boolean = true,
    val isTextChannel: Boolean = false,
    val isMusicChannel: Boolean = false,
) : Event, Event.Cancellable {
    enum class Action { Agree, Disagree, Ignore, Quit, Join }

    val voted = mutableMapOf<Player, Boolean?>()
    var succeed = false
    val endTime: Instant = Instant.now() + voteTime
    override var cancelled
        get() = !mainJob.isActive
        set(value) {
            if (value) mainJob.cancel()
        }

    suspend fun awaitResult(): Boolean {
        mainJob.join()
        return succeed
    }

    /** 当前频道使用的 active 引用 */
    private fun activeRef() = when {
        isMusicChannel -> activeMusic
        isTextChannel -> activeText
        else -> active
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mainJob = scope.launch(Dispatchers.game + CoroutineName("Vote Service"), CoroutineStart.LAZY) main@{
        val coolDownEnd = coolDowns[starter.uuid()] ?: 0
        if (coolDownEnd > System.currentTimeMillis()) {
            val remaining = Duration.ofMillis(coolDownEnd - System.currentTimeMillis())
            val minutes = remaining.toMinutes()
            val seconds = remaining.seconds % 60
            starter.sendMessage("{tr voteLib.reply.cooldown}".with("minutes" to minutes, "seconds" to seconds))
            return@main
        }
        emitAsync {
            if (supportSingle && allCanVote().run { isEmpty() || singleOrNull() == starter }) {
                if (System.currentTimeMillis() - lastAction > 60_000) {
                    broadcast("{tr voteLib.broadcast.singleFastSuccess}".with("type" to voteDesc))
                    lastAction = System.currentTimeMillis()
                    succeed = true
                    return@emitAsync
                } else broadcast("{tr voteLib.broadcast.fastVoteFailed}".with())
            }
            if (!activeRef().compareAndSet(null, this@VoteEvent)) {
                starter.sendMessage("{tr voteLib.reply.voteInProgress}".with())
                return@emitAsync cancel()
            }
            launch {
                try {
                    awaitCancellation()
                } finally {
                    activeRef().compareAndSet(this@VoteEvent, null)
                }
            }
            //文字投票
            val delayTip = if (menuDelay <= 0) "".asPlaceHoldString()
            else "{tr voteLib.msg.delayTip}".with("delay" to menuDelay)
            val voteKeys = if (isMusicChannel)
                "{tr voteLib.label.voteKeysMusic}".with()
            else if (isTextChannel)
                "{tr voteLib.label.voteKeysText}".with()
            else
                "{tr voteLib.label.voteKeysMenu}".with()
            val tip = "{tr voteLib.broadcast.voteTip}".with(
                "starter" to starter, "type" to voteDesc, "ext" to extDesc,
                "voteKeys" to voteKeys, "delayTip" to delayTip,
                "status" to status()
            )
            broadcast(tip)

            broadcast(tip, type = MsgType.Announce, players = allCanVote(), quite = true)
            vote(starter, Action.Agree)
            //弹窗投票
            if (menuDelay >= 0) allCanVote().forEach {
                launch(Dispatchers.game) {
                    delay(menuDelay * 1000L)
                    if (it in voted) return@launch
                    openMenu(it)
                }
            }
            //投票超时处理
            val actionHandler = launch { actionHandler() }
            select {
                actionHandler.onJoin {}
                onTimeout(voteTime.toMillis()) {
                    actionHandler.cancel()
                    withCheckVoted {
                        val minVoteNum = allCanVote().size / 2
                        if (voted.size < minVoteNum && agree() < requireNum(minVoteNum)) {
                            broadcast("{tr voteLib.broadcast.tooFewVoters}".with())
                        } else {
                            succeed = agree() >= requireNum(agree() + disagree()).coerceAtLeast(1)
                        }
                    }
                }
            }

            if (succeed) {
                consecutiveFailures[starter.uuid()] = 0
            } else {
                val failures = (consecutiveFailures[starter.uuid()] ?: 0) + 1
                consecutiveFailures[starter.uuid()] = failures
                val coolDownMinutes = min(failures.toLong(), maxCoolDown.toMinutes())
                coolDowns[starter.uuid()] = System.currentTimeMillis() + coolDownMinutes * 60_000
            }
            val t = (if (succeed) "{tr voteLib.broadcast.voteSucceed}" else "{tr voteLib.broadcast.voteFailed}")
                .with("starter" to starter, "type" to voteDesc, "status" to status())
            broadcast(t)
        }
        coroutineContext.cancelChildren()
    }

    fun allCanVote() = Groups.player.filter(canVote)
    fun agree() = voted.count { it.value == true }
    fun middle() = voted.count { it.value == null }
    fun disagree() = voted.count { it.value == false }
    fun notVote() = allCanVote().size - voted.size
    fun status() = withCheckVoted {
        val all = allCanVote().size
        val need = if (all > 0) requireNum(all) else 0
        "[green]\uE804${agree()} [yellow]\uE853${middle()} [red]\uE805${disagree()} [gray]\uE88F${notVote()}[lightgray] 需${need}票通过"
    }

    inline fun <T> withCheckVoted(body: () -> T): T {
        voted.entries.removeIf { !canVote(it.key) }
        return body()
    }

    fun vote(p: Player, action: Action) {
        handleAction.trySend(p to action)
    }

    suspend fun openMenu(p: Player) {
        MenuBuilder<Unit>("{tr voteLib.menu.title}".with("receiver" to p).toString()) {
            msg = "{tr voteLib.menu.msg}".with(
                "starter" to starter,
                "type" to voteDesc,
                "ext" to extDesc
            ).toPlayer(p)
            option("{tr voteLib.option.agree}".with("receiver" to p).toString()) { vote(p, Action.Agree) }
            option("{tr voteLib.option.neutral}".with("receiver" to p).toString()) { vote(p, Action.Ignore) }
            option("{tr voteLib.option.disagree}".with("receiver" to p).toString()) { vote(p, Action.Disagree) }
            newRow()
            option("{tr voteLib.option.pending}".with("receiver" to p).toString()) {
                p.sendMessage("{tr voteLib.reply.remindLater}".with())
                script.launch(Dispatchers.game) {
                    delay(20_000)
                    if (activeRef().get() == this@VoteEvent && p !in voted) openMenu(p)
                }
            }
        }.sendTo(p, 60_000)
    }

    private val handleAction = Channel<Pair<Player, Action>>(Channel.UNLIMITED)
    private suspend fun actionHandler() = coroutineScope {
        for ((player, event) in handleAction) {
            when (event) {
                Action.Join -> if (this@VoteEvent.canVote(player)) {
                    launch(Dispatchers.gamePost) { openMenu(player) }
                }

                Action.Quit -> voted.remove(player)
                Action.Agree, Action.Disagree, Action.Ignore -> {
                    if (!this@VoteEvent.canVote(player)) {
                        player.sendMessage("{tr voteLib.reply.cannotVote}".with())
                        continue
                    }
                    voted[player] = when (event) {
                        Action.Agree -> true
                        Action.Disagree -> false
                        else -> null
                    }
                    player.sendMessage("{tr voteLib.reply.voteSuccess}".with())
                }
            }

            //fast path
            withCheckVoted {
                val all = allCanVote().size - middle()
                when {
                    fastSuccess && agree() >= this@VoteEvent.requireNum(all) -> {
                        succeed = true;return@coroutineScope
                    }

                    all - disagree() < this@VoteEvent.requireNum(all) -> {
                        succeed = false;return@coroutineScope
                    }

                    else -> {}
                }
            }
        }
        handleAction.close()
    }

    init {
        mainJob.start()
    }

    object VoteCommands : Commands()

    companion object : Event.Handler() {
        internal val script = thisContextScript()
        private val voteTime by script.config.key(Duration.ofSeconds(60)!!, "投票时间")
        private val maxCoolDown by script.config.key(Duration.ofMinutes(10)!!, "投票失败最大冷却时间")
        private val menuDelay by script.config.key(20, "弹窗投票显示时间,单位秒", "0为立即显示，-1纯文字投票")

        internal val active = AtomicReference<VoteEvent?>(null)
        internal val activeText = AtomicReference<VoteEvent?>(null)
        internal val activeMusic = AtomicReference<VoteEvent?>(null)
        internal var lastAction = 0L //最后一次玩家退出或投票成功时间,用于处理单人投票
        internal val coolDowns = mutableMapOf<String, Long>()
        internal val consecutiveFailures = mutableMapOf<String, Int>()
    }
}

@Deprecated("use VoteEvent")
object VoteService {
    @ScriptDsl
    fun Script.addSubVote(
        desc: String, usage: String, vararg aliases: String, body: suspend CommandContext.() -> Unit
    ) {
        VoteEvent.VoteCommands += CommandInfo(this, aliases.first(), desc.with()) {
            this.usage = usage
            this.aliases = aliases.toList()
            body(body)
            if (permission.isEmpty()) permission = "wayzer.vote." + aliases.first().lowercase()
        }
    }

    fun start(
        starter: Player,
        voteDesc: PlaceHoldContext,
        extDesc: String = "",
        supportSingle: Boolean = false,
        canVote: (Player) -> Boolean = { !it.dead() },
        requireNum: (all: Int) -> Int = { ceil(it * .6).toInt() },
        fastSuccess: Boolean = true,
        isTextChannel: Boolean = false,
        isMusicChannel: Boolean = false,
        onSuccess: suspend (Map<Player, Boolean?>) -> Unit
    ) {
        VoteEvent.script.launch(Dispatchers.game) {
            val event = VoteEvent(this, starter, voteDesc, extDesc, supportSingle, canVote, requireNum, fastSuccess, isTextChannel, isMusicChannel)
            if (event.awaitResult()) onSuccess(event.voted)
        }
    }
}

/** 强制观战事件: 在 /forceOB 和投票通过强制观战时触发,用于 playerInfo 统计观战次数 */
data class ForcedObEvent(val targetUuid: String) : Event {
    companion object : Event.Handler()
}
