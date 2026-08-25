import mindustry.game.EventType
import mindustry.gen.Groups

val notifyCooldown = 5_000L  // 提示间隔可以自己调 建议5秒！

val builderMap = HashMap<Int, String>()
val lastNotifyTime = HashMap<String, Long>()

fun mindustry.world.Tile.key() = this.array()

listen<EventType.BlockBuildEndEvent> { e ->
    val p = e.unit?.player ?: return@listen
    val pos = e.tile.key()

    if (!e.breaking) {
    
        builderMap[pos] = p.uuid()
    } else {
        val builderUid = builderMap[pos] ?: return@listen
        if (builderUid == p.uuid()) return@listen  // 不会自己骚扰自己

        val builder = Groups.player.find { it.uuid() == builderUid } ?: return@listen
        
        val now = System.currentTimeMillis()
        val last = lastNotifyTime[builderUid] ?: 0L
        if (now - last < notifyCooldown) return@listen

        lastNotifyTime[builderUid] = now

        builder.sendMessage("{tr demolition.notify.blockDemolished}".with("player" to p, "x" to e.tile.x, "y" to e.tile.y))
    }
}