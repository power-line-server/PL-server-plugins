@file:Depends("wayzer/user/ban", "banX 封禁管理")
@file:Depends("wayzer/user/banStore", "封禁数据存储")
@file:Depends("wayzer/user/lang", "PlayerData")
@file:Depends("wayzer/ext/playerInfo", "玩家信息数据层")
@file:Depends("coreLibrary/time", "时区解析")
@file:Depends("coreLibrary/lang", "多语言")
@file:Depends("wayzer/maps", "地图管理")
@file:Depends("coreMindustry/console", "控制台和日志")
@file:Depends("wayzer/ext/announcements", "公告系统")
@file:Import("org.json:json:20231013", mavenDepends = true)

package wayzer

import arc.Core
import arc.files.Fi
import arc.graphics.Pixmap
import arc.graphics.PixmapIO
import arc.math.Mathf
import arc.util.serialization.Jval
import cf.wayzer.scriptAgent.Config
import cf.wayzer.scriptAgent.util.Services
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import coreLib.extApi.RpcService
import coreLib.extApi.get
import coreLibrary.LangService
import coreLibrary.lib.ColorExtractor
import coreLibrary.lib.CommandContext
import coreLibrary.lib.Commands
import coreLibrary.lib.config
import coreLibrary.lib.with
import coreMindustry.lib.game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.io.MapIO
import org.json.JSONArray
import org.json.JSONObject
import wayzer.lib.PlayerData
import wayzer.user.BanService
import wayzer.user.PlayerBanStore
import wayzer.user.clientType
import wayzer.user.lang
import wayzer.user.timezone
import wayzer.ext.countBans
import wayzer.ext.getIpHistory
import wayzer.ext.getLastName
import wayzer.ext.getNameHistory
import wayzer.ext.getRecord
import wayzer.ext.getUidByUuid
import wayzer.ext.getUuidByUid
import wayzer.ext.searchPlayers
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// ==================== 配置 ====================

val webuiEnabled by config.key(true, "是否启用 WebUI")
val host by config.key("0.0.0.0", "监听地址")
val port by config.key(8080, "监听端口")
var token by config.key("auto", "管理 token，auto 表示自动生成")
val tokenExpire by config.key(86400, "session token 有效期(秒)")
val statusSnapshotIntervalMs by config.key(1000, "状态快照更新间隔(毫秒)")
val snapshotCacheSec by config.key(30, "地图缩略图缓存时间(秒)")
val sslEnabled by config.key(false, "是否启用 HTTPS(自签证书, 流量加密防抓包)")
val sslKeystore by config.key("webui/keystore.p12", "HTTPS 证书库路径(PKCS12, 相对于 data 目录)")
val sslKeystorePass by config.key("webui-ssl", "证书库密码")
val adminUser by config.key("admin", "超级管理员用户名")
// ==================== 速率限制与并发(可在 WebUI 设置页修改) ====================
var rateLimitEnabled by config.key(false, "是否启用 API 速率限制")
var rateLimitPerSec by config.key(10.0, "整体请求限速(每 user@IP 每秒请求数)")
var rateLimitBurst by config.key(40, "整体请求令牌桶容量")
var rateOpPerSec by config.key(3.0, "写操作(POST/PUT/DELETE)限速(每 user@IP 每秒)")
var rateOpBurst by config.key(10, "写操作令牌桶容量")
var rateLimitPerNode by config.key(emptyMap<String, Double>(), "节点级限速覆盖: 权限节点名 -> 每秒(0=不限), 如 { \"webui.api.console\" = 1 }")
var rateLimitPerApi by config.key(emptyMap<String, Double>(), "API级限速覆盖: 端点路径 -> 每秒(0=不限), 如 { \"/api/command\" = 1 }")
var maxConcurrentRequests by config.key(32, "全局并发请求数上限(超过返回 429)")

// ==================== 状态 ====================

private var httpServer: HttpServer? = null
private var executor = ThreadPoolExecutor(
    8, 32, 60L, TimeUnit.SECONDS,
    LinkedBlockingQueue(128),
    { r -> Thread(r, "WebUI-HTTP").apply { isDaemon = true } },
    ThreadPoolExecutor.CallerRunsPolicy()
)
private val serverStartTime = Instant.now()

// session token -> 会话信息(用户名/角色/权限/过期时间)
private data class SessionInfo(val username: String, val role: Int, val permissions: Set<String>, val expire: Instant)
private val sessions = ConcurrentHashMap<String, SessionInfo>()

// 登录失败信息 (按用户名记录, 参考 OpenList 的 DefaultMaxAuthRetries/DefaultLockDuration)
private data class FailureInfo(var count: Int, var lockUntil: Instant?)
private val failures = ConcurrentHashMap<String, FailureInfo>()

// ==================== 用户系统 ====================
// 角色: 0=普通用户 1=游客(唯一, 默认禁用) 2=超级管理员(虚拟, 凭据在 config)
val ROLE_USER = 0
val ROLE_GUEST = 1
val ROLE_ADMIN = 2

// 权限节点: 细粒度(每 API 一节点), 名称 -> 描述键
val PERMISSION_NODES = listOf(
    "webui.api.status" to "webui.perm.status",
    "webui.api.players" to "webui.perm.players",
    "webui.api.bans" to "webui.perm.bans",
    "webui.api.ban" to "webui.perm.ban",
    "webui.api.unban" to "webui.perm.unban",
    "webui.api.maps" to "webui.perm.maps",
    "webui.api.mapSwitch" to "webui.perm.mapSwitch",
    "webui.api.saves" to "webui.perm.saves",
    "webui.api.saveLoad" to "webui.perm.saveLoad",
    "webui.api.saveDelete" to "webui.perm.saveDelete",
    "webui.api.console" to "webui.perm.console",
    "webui.api.logs" to "webui.perm.logs",
    "webui.api.backgroundUpload" to "webui.perm.backgroundUpload",
    "webui.api.logsChat" to "webui.perm.logsChat",
    "webui.api.logsCommand" to "webui.perm.logsCommand",
    "webui.api.announcements" to "webui.perm.announcements",
    "webui.api.announceSave" to "webui.perm.announceSave",
    "webui.api.announceNotify" to "webui.perm.announceNotify",
    "webui.api.snapshot" to "webui.perm.snapshot",
)
// 管理员专属节点(不可分配给普通用户)
val ADMIN_ONLY_PERMS = setOf("webui.api.users", "webui.api.settings")
// 游客固定权限: 仅仪表盘
val GUEST_PERMS = setOf("webui.api.status")

data class WebUser(
    val username: String,
    var pwdHash: String,          // "salt$hash" 十六进制
    var role: Int,                // ROLE_USER / ROLE_GUEST / ROLE_ADMIN(虚拟, 仅用于存背景)
    var disabled: Boolean,
    var permissions: MutableSet<String>,
    var background: String = ""   // 用户个人背景图文件名(存 webuiBackground 目录, 空=默认)
)

private val webUsers = ConcurrentHashMap<String, WebUser>()
private val usersFile: File get() = Config.dataDir.resolve("webui/users.json")

fun loadUsers() {
    webUsers.clear()
    val f = usersFile
    if (f.exists()) {
        try {
            val arr = Jval.read(f.readText()).asArray()
            arr.forEach { j ->
                val u = WebUser(
                    username = j.get("username").asString(),
                    pwdHash = if (j.has("pwdHash")) j.get("pwdHash").asString() else "",
                    role = if (j.has("role")) j.get("role").asInt() else ROLE_USER,
                    disabled = if (j.has("disabled")) j.get("disabled").asBool() else false,
                    permissions = (if (j.has("permissions")) j.get("permissions").asArray().map { it.asString() } else emptyList()).toMutableSet(),
                    background = if (j.has("background")) j.get("background").asString() else ""
                )
                webUsers[u.username] = u
            }
        } catch (e: Exception) {
            logger.warning("[webui] 用户文件解析失败: ${e.message}")
        }
    }
    // 内置游客用户(默认禁用)
    if (!webUsers.containsKey("guest")) {
        webUsers["guest"] = WebUser("guest", "", ROLE_GUEST, disabled = true, permissions = GUEST_PERMS.toMutableSet())
    } else {
        val g = webUsers["guest"]!!
        g.role = ROLE_GUEST
        g.permissions.clear()
        g.permissions.addAll(GUEST_PERMS)
    }
    // 内置管理员虚拟记录(凭据在 config, 此处仅用于存储个人背景)
    if (!webUsers.containsKey(adminUser)) {
        webUsers[adminUser] = WebUser(adminUser, "", ROLE_ADMIN, disabled = false, permissions = mutableSetOf())
    }
    saveUsers()
}

fun saveUsers() {
    val arr = Jval.newArray()
    webUsers.values.forEach { u ->
        val obj = Jval.newObject()
        obj.put("username", u.username)
        obj.put("pwdHash", u.pwdHash)
        obj.put("role", u.role.toLong())
        obj.put("disabled", u.disabled)
        obj.put("background", u.background)
        val perms = Jval.newArray()
        u.permissions.forEach { perms.add(it) }
        obj.put("permissions", perms)
        arr.add(obj)
    }
    usersFile.parentFile?.mkdirs()
    usersFile.writeText(arr.toString())
}

/** 游客账号是否可登录(禁用状态为唯一开关来源, 登录后固定 status 权限) */
private fun guestAvailable(): Boolean =
    webUsers["guest"]?.disabled == false

// ==================== API 密钥 ====================
// 独立密钥: 仅超级管理员在设置页生成, 自带权限节点, 用于外部脚本/第三方调用
// 密钥不绑定用户账号, 删除即撤销; secret 只在生成时返回一次, 存储仅保留 SHA-256 哈希
data class ApiKey(
    val id: String,
    var name: String,
    var secretHash: String,   // SHA-256(secret) hex
    var permissions: MutableSet<String>,
    var createdAt: Long,      // epoch millis
    var expiresAt: Long?,     // epoch millis, null=永不过期
    var rateLimit: Double = 0.0  // 独立限速(每秒), 0=跟随全局限速(不单独限速)
)
private val apiKeys = ConcurrentHashMap<String, ApiKey>()
private val keysFile: File get() = Config.dataDir.resolve("webui/keys.json")

fun loadKeys() {
    apiKeys.clear()
    val f = keysFile
    if (f.exists()) {
        try {
            val arr = Jval.read(f.readText()).asArray()
            arr.forEach { j ->
                val k = ApiKey(
                    id = j.get("id").asString(),
                    name = if (j.has("name")) j.get("name").asString() else j.get("id").asString(),
                    secretHash = if (j.has("secretHash")) j.get("secretHash").asString() else "",
                    permissions = (if (j.has("permissions")) j.get("permissions").asArray().map { it.asString() } else emptyList()).toMutableSet(),
                    createdAt = if (j.has("createdAt")) j.get("createdAt").asLong() else System.currentTimeMillis(),
                    expiresAt = if (j.has("expiresAt")) j.get("expiresAt").asLong() else null,
                    rateLimit = if (j.has("rateLimit")) j.get("rateLimit").asDouble() else 0.0
                )
                apiKeys[k.id] = k
            }
        } catch (e: Exception) {
            logger.warning("[webui] API 密钥文件解析失败: ${e.message}")
        }
    }
}

fun saveKeys() {
    val arr = Jval.newArray()
    apiKeys.values.forEach { k ->
        val obj = Jval.newObject()
        obj.put("id", k.id)
        obj.put("name", k.name)
        obj.put("secretHash", k.secretHash)
        obj.put("createdAt", k.createdAt)
        k.expiresAt?.let { obj.put("expiresAt", it) }
        if (k.rateLimit > 0) obj.put("rateLimit", k.rateLimit)
        val perms = Jval.newArray()
        k.permissions.forEach { perms.add(it) }
        obj.put("permissions", perms)
        arr.add(obj)
    }
    keysFile.parentFile?.mkdirs()
    keysFile.writeText(arr.toString())
}

// 生成 43 字符 base64url 密钥 (32 字节随机)
private fun genKeySecret(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 按密钥 secret 解析身份; 不存在/过期的密钥返回 null */
private fun resolveApiKey(secret: String): AuthInfo? {
    if (secret.isBlank()) return null
    val h = sha256Hex(secret)
    for (k in apiKeys.values) {
        if (MessageDigest.isEqual(h.toByteArray(StandardCharsets.UTF_8), k.secretHash.toByteArray(StandardCharsets.UTF_8))) {
            val exp = k.expiresAt
            if (exp != null && exp < System.currentTimeMillis()) return null
            return AuthInfo("key:${k.name}", ROLE_USER, k.permissions.toSet())
        }
    }
    return null
}

/** 密钥配置的独立限速(按 username "key:<name>" 查, 0=不单独限速) */
private fun apiKeyRate(username: String): Double {
    val n = username.removePrefix("key:")
    return apiKeys.values.firstOrNull { it.name == n }?.rateLimit ?: 0.0
}

// ==================== 密码哈希 (PBKDF2WithHmacSHA256) ====================
private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    return try {
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: Exception) { null }
}

private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

private fun hashPassword(pwd: String, salt: ByteArray): String {
    val spec = PBEKeySpec(pwd.toCharArray(), salt, 10000, 256)
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    return bytesToHex(key)
}

/** 返回 "salt$hash" 格式的密码哈希 */
private fun makePasswordHash(pwd: String): String {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    return "${bytesToHex(salt)}\$${hashPassword(pwd, salt)}"
}

private fun verifyPassword(pwd: String, stored: String): Boolean {
    val parts = stored.split('$')
    if (parts.size != 2) return false
    val salt = hexToBytes(parts[0]) ?: return false
    val hash = hashPassword(pwd, salt)
    return MessageDigest.isEqual(hash.toByteArray(StandardCharsets.UTF_8), parts[1].toByteArray(StandardCharsets.UTF_8))
}

// SSE 日志缓冲区（带递增序号，支持断线重连续号恢复）
// C3: logBuffer 使用 LinkedBlockingQueue 作为共享只读缓冲区（toList 不消费元素）
// logSignal 用于 SSE 线程阻塞等待新日志，避免 Thread.sleep 忙轮询
// 日志类型: chat=玩家聊天(带 <T>/<A> 标记) command=命令及结果([WebUI]/[CMD] 标记) event=其他
private data class LogEntry(val seq: Long, val level: String, val text: String, val type: String)
private val logBuffer = LinkedBlockingQueue<LogEntry>()
private val logSignal = LinkedBlockingQueue<Unit>()
private val MAX_LOG_BUFFER = 500
private val logSeqCounter = java.util.concurrent.atomic.AtomicLong(0)

// 内容图标懒加载标志（Core.atlas 在 onEnable 时未初始化，需延迟到首次请求）
@Volatile private var contentIconsInitialized = false
private val contentIconLock = Any()

// C1: 状态快照（游戏线程定时更新，HTTP 线程直接读取，避免 runBlocking 阻塞）
@Volatile private var statusSnapshot: JSONObject? = null

// C6: 地图缩略图缓存（timestamp -> PNG bytes）
@Volatile private var snapshotCache: Pair<Long, ByteArray>? = null

// C5: 每日一言缓存（timestamp -> text）
@Volatile private var dailyQuoteCache: Pair<Long, String>? = null

// ban store 懒加载
private val rpcService by lazy { Services.get<RpcService>().get() }
private val banStore get() = rpcService.get<PlayerBanStore>()
private val banService by lazy { Services.get<BanService>().get() }
private val langApi by lazy { Services.get<LangService>().get() }

// 从 console.kts 获取 ThreadLocal, 用于读取 Log.formatter 保存的原始颜色码文本
private val unifiedTextHolder: ThreadLocal<String?>? by lazy {
    depends("coreMindustry/console")?.import<ThreadLocal<String?>>("unifiedTextHolder")
}

// 从 console.kts 获取 stripAllColors, 用于清理历史日志文件中的颜色码
private val stripAllColorsFn: ((String) -> String)? by lazy {
    depends("coreMindustry/console")?.import<(String) -> String>("stripAllColors")
}

// 静态文件目录
private val webuiDir: File get() = Config.dataDir.resolve("webui")
// 用户个人背景图目录 (每用户背景独立, 存 webuiBackground/ 下)
private val bgDir: File get() = Config.dataDir.resolve("webui/webuiBackground")

// 审计日志
private val auditLogFile: File get() = Config.dataDir.resolve("webui/audit.log")
private val auditLogOld: File get() = Config.dataDir.resolve("webui/audit.log.old")
private val AUDIT_MAX_SIZE = 10L * 1024 * 1024
// E5: 审计日志写入锁，防止并发写导致文件损坏
private val auditLock = Any()

// 日志文件 (Mindustry 数据目录下的 logs/log-0.txt)
private val logFile: File get() = Core.settings.getDataDirectory().child("logs/log-0.txt").file()

// ==================== 工具函数 ====================

// 生成 32 字符 hex token
private fun genToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

// token 的 SHA-256 哈希前 16 字符（审计日志用，不暴露原 token）
private fun tokenHash(t: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(t.toByteArray(StandardCharsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }.take(16)
}

// 名称校验: 黑名单机制，仅拒绝路径穿越和控制字符
private fun isValidName(name: String): Boolean {
    if (name.isBlank() || name.length > 100) return false
    if (name.contains("..")) return false
    return name.all { c -> c.code >= 0x20 && c != '/' && c != '\\' }
}

// 写入审计日志
private fun appendAudit(ip: String, tHash: String, command: String) {
    synchronized(auditLock) {
        try {
            val dir = auditLogFile.parentFile
            if (!dir.exists()) dir.mkdirs()
            // 超过 10MB 轮转
            if (auditLogFile.exists() && auditLogFile.length() > AUDIT_MAX_SIZE) {
                if (auditLogOld.exists()) auditLogOld.delete()
                auditLogFile.renameTo(auditLogOld)
            }
            val time = Instant.now().toString()
            val line = "[$time] [$ip] [$tHash] command: $command\n"
            auditLogFile.appendText(line)
        } catch (e: Exception) {
            logger.warning("写入审计日志失败: ${e.message}")
        }
    }
}

// ==================== HTTP 工具 ====================

// 发送 JSON 响应
private fun HttpExchange.json(code: Int, msg: String, data: Any? = null, httpCode: Int = 200) {
    val resp = JSONObject()
    resp.put("code", code)
    resp.put("msg", msg)
    if (data != null) resp.put("data", data)
    sendBody(httpCode, resp.toString(), "application/json; charset=utf-8")
}

// 发送文本响应
private fun HttpExchange.sendBody(code: Int, body: String, contentType: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", contentType)
    responseHeaders.set("Content-Length", bytes.size.toString())
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

// 发送字节数组
private fun HttpExchange.sendBytes(code: Int, bytes: ByteArray, contentType: String) {
    responseHeaders.set("Content-Type", contentType)
    responseHeaders.set("Content-Length", bytes.size.toString())
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

// 读取请求体 (maxBytes 限制, 防止超大 body 拖垮内存; 超限截断)
private fun HttpExchange.readBody(maxBytes: Int = 1_048_576): String {
    return try {
        val reader = requestBody.bufferedReader(StandardCharsets.UTF_8)
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var total = 0
        while (total < maxBytes) {
            val n = reader.read(buf, 0, minOf(8192, maxBytes - total))
            if (n < 0) break
            sb.append(buf, 0, n)
            total += n
        }
        sb.toString()
    } catch (_: Exception) { "" }
}

// 客户端 IP
private fun HttpExchange.clientIp(): String {
    return remoteAddress.address.hostAddress
}

// ==================== 速率限制(令牌桶, 按 user@IP 维度) ====================
// 游客为共享用户: guest@IP 每个 IP 独立计数, 单个 IP 打满不影响其他 IP
private val rateBuckets = ConcurrentHashMap<String, DoubleArray>()
// 全局并发请求计数(SSE 长连接不计入)
private val activeRequests = java.util.concurrent.atomic.AtomicInteger(0)

/** 取令牌: key 维度令牌桶; rate<=0 不限速; 返回是否放行 */
private fun takeToken(key: String, rate: Double, burst: Int): Boolean {
    if (rate <= 0) return true
    val now = System.nanoTime()
    val bucket = rateBuckets.computeIfAbsent(key) { doubleArrayOf(burst.toDouble(), now.toDouble()) }
    return synchronized(bucket) {
        val elapsed = (now - bucket[1].toLong()) / 1_000_000_000.0
        bucket[0] = minOf(burst.toDouble(), bucket[0] + elapsed * rate)
        bucket[1] = now.toDouble()
        if (bucket[0] >= 1.0) {
            bucket[0] -= 1.0
            true
        } else false
    }
}

/** 清理长期不活动的限速桶(防止 map 无限增长) */
private fun cleanupRateBuckets() {
    if (rateBuckets.size <= 2000) return
    val cutoff = System.nanoTime() - 300_000_000_000L // 5 分钟未活动
    rateBuckets.entries.removeIf { it.value[1].toLong() < cutoff }
}

// ==================== 认证 ====================
// 请求身份: 超级管理员(role=2) / 游客(role=1) / 普通用户(role=0)
data class AuthInfo(val username: String, val role: Int, val permissions: Set<String>)

/** 从 session token 解析身份 (不含游客降级) */
private fun resolveSessionToken(sessionToken: String): AuthInfo? {
    if (sessionToken.isBlank()) return null
    val s = sessions[sessionToken] ?: return null
    if (s.expire.isBefore(Instant.now())) {
        sessions.remove(sessionToken)
        return null
    }
    return AuthInfo(s.username, s.role, s.permissions)
}

/** 认证链: 管理 token 直通 admin > Bearer session > API 密钥 > cookie session > 游客降级(仅无凭证时) */
private fun HttpExchange.resolveAuth(): AuthInfo? {
    // 是否携带了任何凭证: 有凭证但全部失效(如服务器重启后过期的会话 token)= 需强制重新登录,
    // 应返回 null 触发 401, 而非降级为游客
    var hadCredential = false
    // 1. Authorization: Bearer <token>
    val auth = requestHeaders.getFirst("Authorization")
    if (auth != null && auth.startsWith("Bearer ")) {
        val t = auth.removePrefix("Bearer ").trim()
        hadCredential = t.isNotBlank()
        // 管理 token 直通 = 管理员 (参考 OpenList 的 admin token 机制, 兼容旧脚本)
        if (t.isNotBlank() && MessageDigest.isEqual(
                t.toByteArray(StandardCharsets.UTF_8),
                effectiveToken().toByteArray(StandardCharsets.UTF_8)
            )) {
            return AuthInfo(adminUser, ROLE_ADMIN, emptySet())
        }
        resolveSessionToken(t)?.let { return it }
        resolveApiKey(t)?.let { return it }
    }
    // 2. Cookie: webui_session=<token>
    val cookieToken = getCookie(SESSION_COOKIE)
    if (cookieToken != null) {
        hadCredential = true
        resolveSessionToken(cookieToken)?.let { return it }
    }
    // 3. 游客降级: 仅完全无凭证的匿名请求降级为游客(仅仪表盘, 无需登录);
    //    携带失效凭证(重启后过期的会话 token / 被撤销的密钥等)则返回 null -> 401 重新登录
    if (!hadCredential && guestAvailable()) return AuthInfo("guest", ROLE_GUEST, GUEST_PERMS)
    return null
}

/** 获取当前 session token（用于审计） */
private fun HttpExchange.sessionTokenHash(): String {
    val auth = requestHeaders.getFirst("Authorization")
    if (auth != null && auth.startsWith("Bearer ")) {
        return tokenHash(auth.removePrefix("Bearer ").trim())
    }
    val cookieToken = getCookie(SESSION_COOKIE)
    if (cookieToken != null) return tokenHash(cookieToken)
    return "unknown"
}

/** 权限检查: admin 全过; 返回 null 表示已发送 HTTP 401/403 响应 */
private fun HttpExchange.requirePerm(perm: String): AuthInfo? {
    val info = resolveAuth() ?: run { json(401, "Unauthorized", httpCode = 401); return null }
    if (info.role == ROLE_ADMIN) return info
    if (perm in info.permissions) return info
    json(403, "Forbidden: $perm", httpCode = 403)
    return null
}

// 日志系列权限节点: 任一即可访问控制台日志, 类型细分在 handler 内按权限过滤
val LOG_PERMS = setOf("webui.api.logs", "webui.api.logsChat", "webui.api.logsCommand")

/** 当前身份可查看的日志类型: null=全部; 空集=无(调用方应已拦截); 否则仅列出的类型 */
private fun allowedLogTypes(auth: AuthInfo?): Set<String>? {
    if (auth == null) return emptySet()
    if (auth.role == ROLE_ADMIN) return null
    val perms = auth.permissions
    if ("webui.api.logs" in perms) return null
    val types = mutableSetOf<String>()
    if ("webui.api.logsChat" in perms) types.add("chat")
    if ("webui.api.logsCommand" in perms) types.add("command")
    return types
}

/** 按请求路径+方法映射所需权限节点(任一匹配即可); null=公开接口(仅认证类) */
private fun requiredPerm(path: String, method: String): Set<String>? = when {
    path == "/api/status" && method == "GET" -> setOf("webui.api.status")
    path == "/api/players" || path == "/api/players/search" || path == "/api/player/detail" -> setOf("webui.api.players")
    path == "/api/bans" && method == "GET" -> setOf("webui.api.bans")
    path == "/api/ban" && method == "POST" -> setOf("webui.api.ban")
    path.startsWith("/api/bans/") && method == "DELETE" -> setOf("webui.api.unban")
    path == "/api/maps" && method == "GET" -> setOf("webui.api.maps")
    path == "/api/maps/current" && method == "GET" -> setOf("webui.api.maps")
    path.startsWith("/api/maps/") && method == "PUT" -> setOf("webui.api.mapSwitch")
    path == "/api/saves" && method == "GET" -> setOf("webui.api.saves")
    path.startsWith("/api/saves/") && method == "PUT" -> setOf("webui.api.saveLoad")
    path.startsWith("/api/saves/") && method == "DELETE" -> setOf("webui.api.saveDelete")
    path == "/api/command" && method == "POST" -> setOf("webui.api.console")
    // 日志系列: 任一节点即可(类型细分在 handler 内按权限过滤)
    path == "/api/logs" || path == "/api/logs/stream" -> LOG_PERMS
    path == "/api/background/upload" || path == "/api/background/delete" -> setOf("webui.api.backgroundUpload")
    path == "/api/announcements" && method == "GET" -> setOf("webui.api.announcements")
    path == "/api/announcements" && method == "POST" -> setOf("webui.api.announceSave")
    path == "/api/announcements/delete" && method == "POST" -> setOf("webui.api.announceSave")
    path == "/api/announcements/notify" && method == "POST" -> setOf("webui.api.announceNotify")
    path == "/api/config/token" -> setOf("webui.api.settings")
    path == "/api/config/limits" -> setOf("webui.api.settings")
    path == "/api/users" || path.startsWith("/api/users/") -> setOf("webui.api.users")
    path == "/api/keys" || path.startsWith("/api/keys/") -> setOf("webui.api.settings")
    // 小地图截图: status 或 snapshot 任一(仪表盘小地图用 status, API 调用方可单独分配 snapshot)
    path == "/api/world/snapshot" && method == "GET" -> setOf("webui.api.status", "webui.api.snapshot")
    // 页面背景与每日一言: 需登录(仪表盘 status)
    path == "/api/background" && method == "GET" -> setOf("webui.api.status")
    path == "/api/daily-quote" && method == "GET" -> setOf("webui.api.status")
    // 公开接口(仅认证类, 无敏感数据): login/guest-status/session/check/colors/i18n/content-icon/logout
    else -> null
}

// 解析查询参数
private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split("&").mapNotNull {
        val idx = it.indexOf("=")
        if (idx > 0) it.substring(0, idx) to it.substring(idx + 1)
        else null
    }.toMap()
}

// session token cookie 名
private val SESSION_COOKIE = "webui_session"

// 受保护页面: 未登录访问直接 302 跳转 login.html
private val PROTECTED_PAGES = setOf("dashboard.html", "players.html", "maps.html", "console.html", "settings.html", "announcement.html")

// 从 Cookie 头解析指定 cookie 值
private fun HttpExchange.getCookie(name: String): String? {
    val cookieHeader = requestHeaders.getFirst("Cookie") ?: return null
    for (part in cookieHeader.split(";")) {
        val trimmed = part.trim()
        val idx = trimmed.indexOf("=")
        if (idx > 0 && trimmed.substring(0, idx) == name) {
            return trimmed.substring(idx + 1)
        }
    }
    return null
}

// 设置 cookie
private fun HttpExchange.setCookie(name: String, value: String, maxAge: Long, path: String = "/") {
    val sb = StringBuilder()
    sb.append(name).append("=").append(value)
    sb.append("; Path=").append(path)
    if (maxAge > 0) sb.append("; Max-Age=").append(maxAge)
    // HttpOnly: 禁止 JS 读取 (防 XSS 窃取 session); SameSite=Strict: 防跨站请求携带 cookie (CSRF)
    sb.append("; HttpOnly; SameSite=Strict")
    responseHeaders.add("Set-Cookie", sb.toString())
}

// 清除 cookie
private fun HttpExchange.clearCookie(name: String, path: String = "/") {
    val sb = StringBuilder()
    sb.append(name).append("=")
    sb.append("; Path=").append(path)
    sb.append("; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
    responseHeaders.add("Set-Cookie", sb.toString())
}

// Content-Type 推断
private fun contentTypeFor(filename: String): String {
    val ext = filename.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "map" -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }
}

// ==================== 查找玩家 ====================

// 按名称/#id/shortId 查找在线玩家
private fun findPlayer(target: String): Player? {
    return if (target.startsWith("#")) {
        Groups.player.getByID(target.substring(1).toIntOrNull() ?: -1)
    } else {
        // 名称匹配时移除空格再比较，避免含空格的玩家名匹配不到
        val cleaned = target.replace(" ", "")
        val allPlayers = Groups.player.associateBy { it.name.replace(" ", "") }
        allPlayers[cleaned] ?: PlayerData.findByShortId(target)?.player
    }
}

// ==================== API 处理 ====================

// 主路由
private fun handleApi(exchange: HttpExchange) {
    val path = exchange.requestURI.path
    val method = exchange.requestMethod
    val query = parseQuery(exchange.requestURI.query)

    // 全局并发限制(SSE 长连接不计入, 否则长连接会耗尽并发)
    val isLongStream = path == "/api/logs/stream"
    if (!isLongStream && activeRequests.incrementAndGet() > maxConcurrentRequests) {
        activeRequests.decrementAndGet()
        exchange.json(1, "Too many concurrent requests, try later", httpCode = 429)
        return
    }
    try {
    // 速率限制: 密钥级 / 整体请求 / 写操作 / 节点级 / API级 五层, 按 user@IP 维度(游客每 IP 独立)
    if (rateLimitEnabled) {
        val rlInfo = exchange.resolveAuth()
        val rlKey = "${rlInfo?.username ?: "anon"}@${exchange.clientIp()}"
        cleanupRateBuckets()
        // 0. 密钥级: 密钥配置了独立限速时按密钥整体限速(不区分 IP, 防止第三方共享密钥刷爆)
        if (rlInfo != null && rlInfo.username.startsWith("key:")) {
            val kr = apiKeyRate(rlInfo.username)
            if (kr > 0 && !takeToken("keyrate:${rlInfo.username}", kr, maxOf(1, (kr * 3).toInt()))) {
                exchange.json(1, "Rate limit exceeded, slow down", httpCode = 429)
                return
            }
        }
        // 1. 整体请求
        if (!takeToken("req:$rlKey", rateLimitPerSec, rateLimitBurst)) {
            exchange.json(1, "Rate limit exceeded, slow down", httpCode = 429)
            return
        }
        // 2. 写操作(POST/PUT/DELETE)
        if (method != "GET" && method != "HEAD" && !takeToken("op:$rlKey", rateOpPerSec, rateOpBurst)) {
            exchange.json(1, "Rate limit exceeded, slow down", httpCode = 429)
            return
        }
        // 3. 节点级(所需权限节点中配置的最大覆盖速率)
        val nodePerms = requiredPerm(path, method)
        if (nodePerms != null) {
            val nodeRate = nodePerms.mapNotNull { rateLimitPerNode[it] }.maxOrNull() ?: 0.0
            if (nodeRate > 0 && !takeToken("node:${nodePerms.first()}:$rlKey", nodeRate, maxOf(1, (nodeRate * 3).toInt()))) {
                exchange.json(1, "Rate limit exceeded, slow down", httpCode = 429)
                return
            }
        }
        // 4. API 级(端点路径级覆盖, 最细粒度)
        val apiRate = rateLimitPerApi[path] ?: 0.0
        if (apiRate > 0 && !takeToken("api:$path:$rlKey", apiRate, maxOf(1, (apiRate * 3).toInt()))) {
            exchange.json(1, "Rate limit exceeded, slow down", httpCode = 429)
            return
        }
    }

    // 统一鉴权: 需要权限节点的接口在此检查(任一匹配), 公开接口(仅认证类)跳过
    // 未认证返回 401, 无权限返回 403 (游客降级已移除, 游客需登录)
    val needed = requiredPerm(path, method)
    if (needed != null) {
        val info = exchange.resolveAuth()
        if (info == null) {
            exchange.json(401, "Unauthorized", httpCode = 401)
            return
        }
        if (info.role != ROLE_ADMIN && info.permissions.none { it in needed }) {
            exchange.json(403, "Forbidden: ${needed.first()}", httpCode = 403)
            return
        }
    }

        when {
            // 登录（不需要认证）
            path == "/api/login" && method == "POST" -> handleLogin(exchange)

            // 游客状态查询（登录页显示"以游客身份浏览"按钮用）
            path == "/api/auth/guest-status" && method == "GET" -> handleGuestStatus(exchange)

            // 颜色数据（无需认证，前端页面初始化时加载）
            path == "/api/colors" && method == "GET" -> handleColors(exchange)

            // C4: 轻量级会话检查（不访问游戏线程，供 login.html 等页面使用）
            path == "/api/session/check" && method == "GET" -> handleSessionCheck(exchange)

            // 多语言（无需认证，前端页面初始化时加载翻译表）
            path == "/api/i18n" && method == "GET" -> handleI18n(exchange, query["lang"] ?: "zh_CN")
            path == "/api/i18n/langs" && method == "GET" -> handleI18nLangs(exchange)

            // 当前用户信息
            path == "/api/me" && method == "GET" -> handleMe(exchange)

            // 用户管理（admin 专属节点 webui.api.users）
            path == "/api/users" && method == "GET" -> handleUsersList(exchange)
            path == "/api/users" && method == "POST" -> handleUserCreate(exchange)
            path == "/api/users/perm-nodes" && method == "GET" -> handlePermNodes(exchange)
            path.startsWith("/api/users/") && method == "PUT" -> handleUserUpdate(exchange, path.removePrefix("/api/users/"))
            path.startsWith("/api/users/") && method == "DELETE" -> handleUserDelete(exchange, path.removePrefix("/api/users/"))

            // 登出
            path == "/api/logout" && method == "POST" -> handleLogout(exchange)

            // 修改 token
            path == "/api/config/token" && method == "POST" -> handleConfigToken(exchange)

            // 服务器状态
            path == "/api/status" && method == "GET" -> handleStatus(exchange)

            // 玩家列表
            path == "/api/players" && method == "GET" -> handlePlayers(exchange)

            // 搜索玩家（支持离线玩家，匹配名称/uid/uuid）
            path == "/api/players/search" && method == "GET" -> handlePlayersSearch(exchange, query)

            // 玩家详情
            path == "/api/player/detail" && method == "GET" -> handlePlayerDetail(exchange, query)

            // 封禁列表
            path == "/api/bans" && method == "GET" -> handleBansList(exchange)

            // 封禁玩家
            path == "/api/ban" && method == "POST" -> handleBan(exchange)

            // 解封
            path.startsWith("/api/bans/") && method == "DELETE" -> handleUnban(exchange, path.removePrefix("/api/bans/"))

            // 地图列表
            path == "/api/maps" && method == "GET" -> handleMapsList(exchange)

            // 当前地图
            path == "/api/maps/current" && method == "GET" -> handleMapsCurrent(exchange)

            // 切换地图
            path.startsWith("/api/maps/") && method == "PUT" -> handleMapSwitch(exchange, path.removePrefix("/api/maps/"))

            // 存档列表
            path == "/api/saves" && method == "GET" -> handleSavesList(exchange)

            // 加载/删除存档
            path.startsWith("/api/saves/") && method == "PUT" -> handleSaveLoad(exchange, path.removePrefix("/api/saves/"))
            path.startsWith("/api/saves/") && method == "DELETE" -> handleSaveDelete(exchange, path.removePrefix("/api/saves/"))

            // 执行命令
            path == "/api/command" && method == "POST" -> handleCommand(exchange)

            // 历史日志
            path == "/api/logs" && method == "GET" -> handleLogs(exchange, query)

            // 实时日志流（SSE）
            path == "/api/logs/stream" && method == "GET" -> handleLogStream(exchange)

            // 背景图片配置
            path == "/api/background" && method == "GET" -> handleBackground(exchange)

            // 背景图片管理
            path == "/api/background/list" && method == "GET" -> handleBackgroundList(exchange)
            path == "/api/background/set" && method == "POST" -> handleBackgroundSet(exchange)
            path == "/api/background/upload" && method == "POST" -> handleBackgroundUpload(exchange)
            path == "/api/background/delete" && method == "POST" -> handleBackgroundDelete(exchange)

            // 当前世界实时快照
            // 临时调试: 字节序测试
            path == "/api/world/snapshot" && method == "GET" -> handleWorldSnapshot(exchange)

            // 内容图标 PNG
            path.startsWith("/api/content-icon/") && method == "GET" -> handleContentIcon(exchange, path.removePrefix("/api/content-icon/"))

            // 公告管理
            path == "/api/announcements" && method == "GET" -> handleAnnouncementsList(exchange)
            path == "/api/announcements" && method == "POST" -> handleAnnouncementsSave(exchange)
            path == "/api/announcements/delete" && method == "POST" -> handleAnnouncementsDelete(exchange)
            path == "/api/announcements/notify" && method == "POST" -> handleAnnouncementsNotify(exchange)

            // 每日一言
            path == "/api/daily-quote" && method == "GET" -> handleDailyQuote(exchange)

            // API 密钥管理 (admin 专属)
            path == "/api/keys" && method == "GET" -> handleKeysList(exchange)
            path == "/api/keys" && method == "POST" -> handleKeysCreate(exchange)
            path.startsWith("/api/keys/") && method == "PUT" -> handleKeysUpdate(exchange, path.removePrefix("/api/keys/"))
            path.startsWith("/api/keys/") && method == "DELETE" -> handleKeysDelete(exchange, path.removePrefix("/api/keys/"))

            // 限速/并发配置 (admin 专属)
            path == "/api/config/limits" && method == "GET" -> handleConfigLimitsGet(exchange)
            path == "/api/config/limits" && method == "POST" -> handleConfigLimitsSet(exchange)


            else -> exchange.json(404, "Not Found")
        }
    } catch (e: Exception) {
        try { exchange.json(1, "Internal error: ${e.message}") } catch (_: Exception) {}
    } finally {
        if (!isLongStream) activeRequests.decrementAndGet()
        exchange.close()
    }
}

// ==================== 认证 ====================

// POST /api/login
private fun handleLogin(exchange: HttpExchange) {
    val body = exchange.readBody(64 * 1024)
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    // 登录凭据: {username, password}; 兼容旧 {token} (token 视为管理员密码)
    val username = json.optString("username", "").trim()
    val password = json.optString("password", "")
    val legacyToken = json.optString("token", "")
    val loginName = username.ifBlank { adminUser }
    val loginPwd = if (username.isNotBlank()) password else legacyToken.ifBlank { password }

    // 按用户名限流 (OpenList 同款, 5 次锁 15 分钟)
    val failKey = loginName.lowercase()
    val failInfo = failures[failKey]
    if (failInfo?.lockUntil != null && failInfo.lockUntil!!.isAfter(Instant.now())) {
        exchange.json(1, "Too many failures, account locked for 15 minutes", httpCode = 429)
        return
    }

    // 延迟递增，防时序攻击
    val failCount = failInfo?.count ?: 0
    val delayMs = (200L * (1 shl failCount.coerceAtMost(5)))
    Thread.sleep(delayMs)

    // 校验: 管理员(用户名=adminUser, 密码=token 值) 或 用户表
    val isAdminLogin = loginName == adminUser && MessageDigest.isEqual(
        loginPwd.toByteArray(StandardCharsets.UTF_8),
        effectiveToken().toByteArray(StandardCharsets.UTF_8)
    )
    val user = webUsers[loginName]
    val userOk = user != null && user.role != ROLE_ADMIN && !user.disabled &&
        user.pwdHash.isNotBlank() && verifyPassword(loginPwd, user.pwdHash)

    if (isAdminLogin || userOk) {
        failures.remove(failKey)
        val (uname, role, perms) = if (isAdminLogin) Triple(adminUser, ROLE_ADMIN, emptySet<String>())
        else Triple(user!!.username, user.role, user.permissions.toSet())
        val sessionToken = genToken()
        sessions[sessionToken] = SessionInfo(uname, role, perms, Instant.MAX)
        exchange.setCookie(SESSION_COOKIE, sessionToken, -1L)
        val data = JSONObject()
            .put("sessionToken", sessionToken)
            .put("username", uname)
            .put("role", role)
            .put("expire", 0)
        exchange.json(0, "ok", data)
    } else {
        val info = failures.computeIfAbsent(failKey) { FailureInfo(0, null) }
        info.count++
        if (info.count >= 5) {
            info.lockUntil = Instant.now().plusSeconds(15 * 60)
            exchange.json(1, "Too many failures, account locked for 15 minutes", httpCode = 429)
        } else {
            exchange.json(1, "Invalid username or password", httpCode = 401)
        }
    }
}

// POST /api/logout
private fun handleLogout(exchange: HttpExchange) {
    // 优先从 Authorization header 获取，其次从 cookie
    val auth = exchange.requestHeaders.getFirst("Authorization")
    var sessionToken = ""
    if (auth != null && auth.startsWith("Bearer ")) {
        sessionToken = auth.removePrefix("Bearer ").trim()
    }
    if (sessionToken.isBlank()) {
        sessionToken = exchange.getCookie(SESSION_COOKIE) ?: ""
    }
    if (sessionToken.isNotBlank()) {
        sessions.remove(sessionToken)
    }
    // 清除 cookie
    exchange.clearCookie(SESSION_COOKIE)
    exchange.json(0, "ok")
}

// POST /api/config/token
private fun handleConfigToken(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val newToken = json.optString("newToken", "")
    if (newToken.length < 8) {
        exchange.json(1, "Token too short (min 8 chars)")
        return
    }
    // 修改持久化 token (写入 config.conf)
    token = newToken
    // 如果新 token 不是 "auto",清空 auto 模式的内存 token
    if (newToken != "auto") {
        autoAdminToken = ""
    }
    // 清空所有 session，强制重新登录
    sessions.clear()
    // 清除当前请求的 cookie，前端会被重定向到登录页
    exchange.clearCookie(SESSION_COOKIE)
    logger.info("WebUI token 已被修改，所有 session 已清空 (IP: ${exchange.clientIp()})")
    exchange.json(0, "ok")
}

// GET /api/config/limits — 当前限速/并发配置(admin, 设置页表单填充用)
private fun handleConfigLimitsGet(exchange: HttpExchange) {
    exchange.json(0, "ok", JSONObject()
        .put("rateLimitEnabled", rateLimitEnabled)
        .put("rateLimitPerSec", rateLimitPerSec)
        .put("rateLimitBurst", rateLimitBurst)
        .put("rateOpPerSec", rateOpPerSec)
        .put("rateOpBurst", rateOpBurst)
        .put("maxConcurrentRequests", maxConcurrentRequests)
        .put("rateLimitPerNode", JSONObject(rateLimitPerNode))
        .put("rateLimitPerApi", JSONObject(rateLimitPerApi)))
}

/** 限速配置持久化: 显式写入 config.conf(即使值等于默认也写, 防止恢复默认时旧值残留、重启后复活) */
private fun persistLimitConfig(path: String, value: Any) {
    try {
        val key = ConfigBuilder.all[path] ?: return
        @Suppress("UNCHECKED_CAST")
        (key as ConfigBuilder.ConfigKey<Any>).set(value, saveDefault = true)
    } catch (e: Exception) {
        logger.warning("[webui] 持久化配置 $path 失败: ${e.message}")
    }
}

// POST /api/config/limits — 修改限速/并发配置(立即生效并持久化到 config.conf)
private fun handleConfigLimitsSet(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    if (json.has("rateLimitEnabled")) {
        rateLimitEnabled = json.optBoolean("rateLimitEnabled")
        persistLimitConfig("wayzer.webui.rateLimitEnabled", rateLimitEnabled)
    }
    if (json.has("rateLimitPerSec")) {
        rateLimitPerSec = json.optDouble("rateLimitPerSec", 10.0).coerceIn(0.0, 10000.0)
        persistLimitConfig("wayzer.webui.rateLimitPerSec", rateLimitPerSec)
    }
    if (json.has("rateLimitBurst")) {
        rateLimitBurst = json.optInt("rateLimitBurst", 40).coerceIn(1, 100000)
        persistLimitConfig("wayzer.webui.rateLimitBurst", rateLimitBurst)
    }
    if (json.has("rateOpPerSec")) {
        rateOpPerSec = json.optDouble("rateOpPerSec", 3.0).coerceIn(0.0, 10000.0)
        persistLimitConfig("wayzer.webui.rateOpPerSec", rateOpPerSec)
    }
    if (json.has("rateOpBurst")) {
        rateOpBurst = json.optInt("rateOpBurst", 10).coerceIn(1, 100000)
        persistLimitConfig("wayzer.webui.rateOpBurst", rateOpBurst)
    }
    if (json.has("maxConcurrentRequests")) {
        maxConcurrentRequests = json.optInt("maxConcurrentRequests", 32).coerceIn(1, 1000)
        persistLimitConfig("wayzer.webui.maxConcurrentRequests", maxConcurrentRequests)
    }
    if (json.has("rateLimitPerNode")) {
        val obj = json.optJSONObject("rateLimitPerNode")
        val m = mutableMapOf<String, Double>()
        if (obj != null) {
            val allNodes = PERMISSION_NODES.map { it.first }.toSet()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.optDouble(k, 0.0)
                if (v > 0 && k in allNodes) m[k] = v
            }
        }
        rateLimitPerNode = m
        persistLimitConfig("wayzer.webui.rateLimitPerNode", m)
    }
    if (json.has("rateLimitPerApi")) {
        val obj = json.optJSONObject("rateLimitPerApi")
        val m = mutableMapOf<String, Double>()
        if (obj != null) {
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.optDouble(k, 0.0)
                if (v > 0 && k.startsWith("/")) m[k] = v
            }
        }
        rateLimitPerApi = m
        persistLimitConfig("wayzer.webui.rateLimitPerApi", m)
    }
    logger.info("WebUI 限速/并发配置已修改 (IP: ${exchange.clientIp()})")
    exchange.json(0, "ok")
}

// ==================== API 密钥管理 (admin 专属) ====================

// GET /api/keys — 密钥列表(不含 secret)
private fun handleKeysList(exchange: HttpExchange) {
    val arr = JSONArray()
    apiKeys.values.sortedBy { it.createdAt }.forEach { k ->
        val perms = JSONArray()
        k.permissions.sorted().forEach { perms.put(it) }
        arr.put(JSONObject()
            .put("id", k.id)
            .put("name", k.name)
            .put("permissions", perms)
            .put("createdAt", k.createdAt)
            .put("expiresAt", k.expiresAt ?: JSONObject.NULL)
            .put("rateLimit", k.rateLimit))
    }
    exchange.json(0, "ok", arr)
}

// POST /api/keys — 生成新密钥 {name, permissions[], expireDays?, rateLimit?}; secret 仅返回一次
private fun handleKeysCreate(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val name = json.optString("name", "").trim()
    if (!isValidName(name)) {
        exchange.json(1, "Invalid key name")
        return
    }
    if (apiKeys.values.any { it.name == name }) {
        exchange.json(1, "Key name already exists")
        return
    }
    val expireDays = json.optInt("expireDays", 0)
    if (expireDays < 0 || expireDays > 3650) {
        exchange.json(1, "Invalid expireDays (0-3650)")
        return
    }
    val rateLimit = json.optDouble("rateLimit", 0.0).coerceIn(0.0, 10000.0)
    val secret = genKeySecret()
    val id = "k" + genToken().take(10)
    val expiresAt = if (expireDays > 0) System.currentTimeMillis() + expireDays * 86400_000L else null
    apiKeys[id] = ApiKey(
        id = id,
        name = name,
        secretHash = sha256Hex(secret),
        permissions = validPerms(json.optJSONArray("permissions")),
        createdAt = System.currentTimeMillis(),
        expiresAt = expiresAt,
        rateLimit = rateLimit
    )
    saveKeys()
    val perms = JSONArray()
    apiKeys[id]!!.permissions.sorted().forEach { perms.put(it) }
    exchange.json(0, "ok", JSONObject()
        .put("id", id)
        .put("name", name)
        .put("secret", secret)
        .put("permissions", perms)
        .put("expiresAt", expiresAt ?: JSONObject.NULL)
        .put("rateLimit", rateLimit))
}

// DELETE /api/keys/:id — 删除密钥(立即失效)
private fun handleKeysDelete(exchange: HttpExchange, id: String) {
    if (apiKeys.remove(id) == null) {
        exchange.json(1, "Key not found")
        return
    }
    saveKeys()
    exchange.json(0, "ok")
}

// PUT /api/keys/:id — 编辑密钥(改名/改权限/改过期时间/改独立限速; secret 不变)
private fun handleKeysUpdate(exchange: HttpExchange, id: String) {
    val k = apiKeys[id] ?: run { exchange.json(1, "Key not found"); return }
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    if (json.has("name")) {
        val name = json.optString("name", "").trim()
        if (!isValidName(name)) { exchange.json(1, "Invalid key name"); return }
        if (apiKeys.values.any { it.name == name && it.id != id }) { exchange.json(1, "Key name already exists"); return }
        k.name = name
    }
    if (json.has("permissions")) k.permissions = validPerms(json.optJSONArray("permissions"))
    if (json.has("expireDays")) {
        val expireDays = json.optInt("expireDays", -1)
        if (expireDays < 0 || expireDays > 3650) { exchange.json(1, "Invalid expireDays (0-3650)"); return }
        k.expiresAt = if (expireDays > 0) System.currentTimeMillis() + expireDays * 86400_000L else null
    }
    if (json.has("rateLimit")) k.rateLimit = json.optDouble("rateLimit", 0.0).coerceIn(0.0, 10000.0)
    saveKeys()
    exchange.json(0, "ok")
}

// ==================== 服务器状态 ====================

// C1: 在游戏线程构建状态快照（由 onEnable 中的 loop 协程定时调用）
private fun buildStatusSnapshot(): JSONObject {
    val runtime = Runtime.getRuntime()
    val map = Vars.state.map
    val jvmStartTime = ManagementFactory.getRuntimeMXBean().startTime
    val uptimeSeconds = (System.currentTimeMillis() - jvmStartTime) / 1000
    val memUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val memTotalMb = runtime.maxMemory() / (1024 * 1024)
    return JSONObject()
        .put("tps", Core.graphics.framesPerSecond.coerceAtMost(255))
        .put("memoryUsed", memUsedMb)
        .put("memoryTotal", memTotalMb)
        .put("playersOnline", Groups.player.size())
        .put("uptime", uptimeSeconds)
        .put("mapId", MapManager.current.id)
        .put("mapName", map?.name() ?: "unknown")
        .put("mapAuthor", map?.author() ?: "unknown")
        .put("mapWidth", map?.width ?: 0)
        .put("mapHeight", map?.height ?: 0)
        .put("mapMode", Vars.state.rules.mode().name)
        .put("wave", Vars.state.wave)
        .put("enemies", if (Vars.state.rules.pvp) null else Vars.state.enemies)
        .put("allUnits", Groups.unit.size())
        .put("allBans", runCatching { banStore.listAll().filter { it.endTime.isAfter(Instant.now()) }.size }.getOrDefault(0))
        .put("serverVersion", mindustry.core.Version.buildString())
}

// GET /api/status
// C1: 直接返回游戏线程定时更新的快照，不阻塞 HTTP 线程
private fun handleStatus(exchange: HttpExchange) {
    val snapshot = statusSnapshot
    if (snapshot != null) {
        exchange.json(0, "ok", snapshot)
    } else {
        // 快照尚未生成（启动初期），返回最小占位数据
        val data = JSONObject()
            .put("tps", 0)
            .put("memoryUsed", 0)
            .put("memoryTotal", 0)
            .put("playersOnline", 0)
            .put("uptime", 0)
            .put("mapName", "loading")
            .put("serverVersion", mindustry.core.Version.buildString())
        exchange.json(0, "ok", data)
    }
}

// ==================== 玩家与封禁 ====================

// GET /api/players
private fun handlePlayers(exchange: HttpExchange) {
    val data = runBlocking {
        withContext(Dispatchers.game) {
            val arr = JSONArray()
            Groups.player.forEach { p ->
                val playerData = PlayerData[p]
                val (lang, timezone) = runCatching {
                    playerData.lang to playerData.timezone
                }.getOrNull() ?: ("zh_CN" to "+08:00")
                arr.put(JSONObject()
                    .put("name", p.name)
                    .put("uuid", p.uuid())
                    .put("shortId", playerData.shortId)
                    .put("ip", p.con?.address ?: "")
                    .put("team", p.team().name)
                    .put("admin", p.admin)
                    .put("lang", lang)
                    .put("timezone", timezone)
                )
            }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// GET /api/players/search?q=xxx
// 搜索玩家：支持名称模糊匹配、uid 精确匹配、uuid 前缀匹配
// 返回离线+在线玩家，包含 name/uuid/uid/online 信息
private fun handlePlayersSearch(exchange: HttpExchange, query: Map<String, String>) {
    val q = (query["q"] ?: query["query"] ?: "").trim()
    if (q.length < 1) {
        exchange.json(0, "ok", JSONArray())
        return
    }
    val data = runBlocking {
        withContext(Dispatchers.game) {
            val uuids = searchPlayers(q)
            val arr = JSONArray()
            for (uuid in uuids) {
                val onlinePlayer = Groups.player.find { it.uuid() == uuid }
                val record = getRecord(uuid)
                val lastName = getLastName(uuid) ?: onlinePlayer?.name ?: continue
                val uid = record?.uid ?: getUidByUuid(uuid)
                arr.put(JSONObject()
                    .put("name", lastName)
                    .put("uuid", uuid)
                    .put("uid", uid ?: JSONObject.NULL)
                    .put("online", onlinePlayer != null)
                )
            }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// GET /api/player/detail?uuid=xxx
private fun handlePlayerDetail(exchange: HttpExchange, query: Map<String, String>) {
    val uuid = query["uuid"] ?: ""
    if (uuid.isBlank()) { exchange.json(1, "uuid required"); return }

    val data = runBlocking {
        withContext(Dispatchers.game) {
            val onlinePlayer = Groups.player.find { it.uuid() == uuid }
            val record = getRecord(uuid)
            val lastName = getLastName(uuid)
            val banCount = countBans(uuid)
            val uid = record?.uid ?: getUidByUuid(uuid)
            val nameHistory = getNameHistory(uuid)
            val ipHistory = getIpHistory(uuid)
            val ct = clientType[uuid]

            val obj = JSONObject()
                .put("name", lastName ?: onlinePlayer?.name ?: "-")
                .put("uuid", uuid)
                .put("uid", uid ?: JSONObject.NULL)
                .put("online", onlinePlayer != null)
                .put("banCount", banCount)
                .put("nameHistory", JSONArray().apply {
                    nameHistory.forEach { e ->
                        put(JSONObject()
                            .put("name", e.name)
                            .put("firstSeenTime", e.firstSeenTime)
                        )
                    }
                })
                .put("ipHistory", JSONArray().apply {
                    ipHistory.forEach { e ->
                        put(JSONObject()
                            .put("ip", e.ip)
                            .put("firstSeenTime", e.firstSeenTime)
                        )
                    }
                })

            if (onlinePlayer != null) {
                val pd = PlayerData[onlinePlayer]
                obj.put("team", onlinePlayer.team().name)
                obj.put("admin", onlinePlayer.admin)
                // 原始名: 客户端设置的未经服务器拼接修改的名字
                obj.put("rawName", wayzer.user.realName[uuid] ?: JSONObject.NULL)
                obj.put("ip", onlinePlayer.con?.address ?: "")
                obj.put("mobile", onlinePlayer.con?.mobile == true)
                obj.put("clientLang", onlinePlayer.locale ?: "-")
                obj.put("serverLang", pd.lang)
                obj.put("timezone", pd.timezone)
                obj.put("clientType", ct?.toString() ?: "-")
            } else {
                // 离线玩家：从 KVStore 直接查询持久化的 lang 和 timezone
                val serverLang = runCatching { wayzer.user.settings[uuid] }.getOrNull() ?: "zh_CN"
                val tz = runCatching { wayzer.user.tzSettings[uuid] }.getOrNull() ?: "+08:00"
                obj.put("serverLang", serverLang)
                obj.put("timezone", tz)
                obj.put("clientType", ct?.toString() ?: "-")
            }

            if (record != null) {
                obj.put("joinCount", record.joinCount)
                obj.put("totalOnlineSeconds", record.totalOnlineSeconds)
                obj.put("firstJoinTime", record.firstJoinTime)
                record.lastLeaveTime?.let { obj.put("lastLeaveTime", it) }
                obj.put("forcedObCount", record.forcedObCount)
            }
            obj
        }
    }
    exchange.json(0, "ok", data)
}

// GET /api/bans
private fun handleBansList(exchange: HttpExchange) {
    val data = runBlocking {
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val arr = JSONArray()
            banStore.listAll().filter { it.endTime.isAfter(now) }.forEach { ban ->
                arr.put(JSONObject()
                    .put("recordId", ban.recordId)
                    .put("targetName", ban.targetName ?: "")
                    .put("operatorName", ban.operatorName ?: "Server")
                    .put("reason", ban.reason)
                    .put("createTime", ban.createTime.toString())
                    .put("endTime", ban.endTime.toString())
                    .put("ip", ban.ip ?: "")
                )
            }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// POST /api/ban
private fun handleBan(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val target = json.optString("target", "")
    val time = json.optInt("time", 0)
    val reason = json.optString("reason", "").take(200)
    val banIp = json.optBoolean("banIp", false)

    if (target.isBlank()) { exchange.json(1, "target required"); return }
    if (reason.isBlank()) { exchange.json(1, "reason required"); return }
    // time=0 表示永久封禁，用 100 年代替（banX 的 duration=0 会立即过期）
    val banTime = if (time <= 0) 52560000 else time

    val player = runBlocking { withContext(Dispatchers.game) { findPlayer(target) } }
    if (player == null) {
        // 尝试从历史数据查找
        val histData = PlayerData.findByShortId(target.removePrefix("#"))
        if (histData == null) {
            exchange.json(1, "Player not found")
            return
        }
        runBlocking {
            withContext(Dispatchers.IO) {
                banService.ban(histData, banTime, reason, null, banIp)
            }
        }
    } else {
        val playerData = runBlocking { withContext(Dispatchers.game) { PlayerData[player] } }
        runBlocking {
            withContext(Dispatchers.IO) {
                banService.ban(playerData, banTime, reason, null, banIp)
            }
        }
    }
    exchange.json(0, "ok")
}

// DELETE /api/bans/{id}
private fun handleUnban(exchange: HttpExchange, idStr: String) {
    val id = idStr.toIntOrNull()
    if (id == null) { exchange.json(1, "Invalid id"); return }
    val result = runBlocking {
        withContext(Dispatchers.IO) { banStore.delete(id) }
    }
    if (result == null) {
        exchange.json(1, "Ban record not found")
    } else {
        exchange.json(0, "ok")
    }
}

// ==================== 地图与存档 ====================

// GET /api/maps
private fun handleMapsList(exchange: HttpExchange) {
    val data = runBlocking {
        withContext(Dispatchers.game) {
            val arr = JSONArray()
            Vars.maps.customMaps().forEach { map ->
                arr.put(JSONObject()
                    .put("name", map.name())
                    .put("author", map.author())
                    .put("width", map.width)
                    .put("height", map.height)
                    .put("description", map.description())
                )
            }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// GET /api/maps/current
private fun handleMapsCurrent(exchange: HttpExchange) {
    val data = runBlocking {
        withContext(Dispatchers.game) {
            val map = Vars.state.map
            JSONObject()
                .put("name", map?.name() ?: "unknown")
                .put("author", map?.author() ?: "unknown")
                .put("width", map?.width ?: 0)
                .put("height", map?.height ?: 0)
                .put("description", map?.description() ?: "")
        }
    }
    exchange.json(0, "ok", data)
}

// PUT /api/maps/{name}
private fun handleMapSwitch(exchange: HttpExchange, name: String) {
    if (!isValidName(name)) { exchange.json(1, "Invalid map name"); return }

    // 查找地图
    val mapInfo = runBlocking {
        withContext(Dispatchers.game) {
            MapRegistry.searchMaps(null).find { it.name == name }
        }
    }
    if (mapInfo == null) {
        exchange.json(1, "Map not found")
        return
    }

    // loadMap 内部会 launch(Dispatchers.game) 异步执行完整的换图流程
    // （worldDataBegin → reset → sendWorldData → add），不需要外部 runBlocking
    MapManager.loadMap(mapInfo)
    exchange.json(0, "ok")
}

// GET /api/saves
private fun handleSavesList(exchange: HttpExchange) {
    val data = runBlocking {
        withContext(Dispatchers.IO) {
            val savesDir: Fi = Core.settings.getDataDirectory().child("saves")
            val arr = JSONArray()
            savesDir.list().forEach { f ->
                if (f.extension() == "msav") {
                    arr.put(JSONObject()
                        .put("name", f.nameWithoutExtension())
                        .put("size", f.length())
                        .put("lastModified", f.lastModified())
                    )
                }
            }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// PUT /api/saves/{name}
private fun handleSaveLoad(exchange: HttpExchange, name: String) {
    if (!isValidName(name)) { exchange.json(1, "Invalid save name"); return }
    val file: Fi = Core.settings.getDataDirectory().child("saves/$name.msav")
    if (!file.exists()) { exchange.json(1, "Save not found"); return }

    // 使用 MapManager.loadSave 而非 SaveIO.load，确保完整的换图流程
    // SaveIO.load 只加载世界状态，不通知客户端重连，会导致数据不同步
    // loadSave 内部调用 loadMap，包含 worldDataBegin → reset → sendWorldData → add
    MapManager.loadSave(file)
    exchange.json(0, "ok")
}

// DELETE /api/saves/{name}
private fun handleSaveDelete(exchange: HttpExchange, name: String) {
    if (!isValidName(name)) { exchange.json(1, "Invalid save name"); return }
    val file: Fi = Core.settings.getDataDirectory().child("saves/$name.msav")
    if (!file.exists()) { exchange.json(1, "Save not found"); return }

    runBlocking {
        withContext(Dispatchers.IO) {
            file.delete()
        }
    }
    exchange.json(0, "ok")
}

// ==================== 控制台与日志 ====================

// POST /api/command
private fun handleCommand(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val command = json.optString("command", "").take(1000)
    if (command.isBlank()) { exchange.json(1, "command required"); return }

    // 审计日志
    appendAudit(exchange.clientIp(), exchange.sessionTokenHash(), command)
    arc.util.Log.info("<WEBUI> ${exchange.clientIp()} 执行命令: $command")

    // 异步执行：提交到游戏线程，不阻塞 HTTP 线程
    // 命令输出通过 reply → Log.info → logBuffer → SSE 实时推送
    GlobalScope.launch(Dispatchers.game) {
        try {
            CommandContext.Command().apply {
                receiver = CommandContext.ConsoleReceiver
                reply = { msg ->
                    val text = msg.with("receiver" to CommandContext.ConsoleReceiver).toString()
                    arc.util.Log.info("<CMD> $text")
                }
                this.prefix = "* "
                this.arg = command.trim().split(' ').filter { it.isNotEmpty() }
                Commands.Root.handle()
            }
            // D1: vanilla 'say' 命令使用 Call.sendMessage 发送给玩家，绕过 Log.info
            // 此处显式日志记录，使输出通过 logBuffer → SSE 出现在 WebUI 控制台
            val parts = command.trim().split(' ', limit = 2)
            if (parts.size >= 2 && parts[0].equals("say", ignoreCase = true)) {
                arc.util.Log.info("<CMD> [white]Server: ${parts[1]}")
            }
        } catch (e: Exception) {
            arc.util.Log.err("Command error: ${e.message}")
        }
    }
    // 立即返回，输出通过 SSE 到达
    exchange.json(0, "ok", JSONObject().put("output", "").put("success", true))
}

// GET /api/logs?limit=100&search=keyword&level=ERROR
private fun handleLogs(exchange: HttpExchange, query: Map<String, String>) {
    val limit = (query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
    val search = query["search"]
    val level = query["level"]?.uppercase()
    // 类型权限过滤: 无 logs 全权限的用户只能看其有权限的类型
    val allowedTypes = allowedLogTypes(exchange.resolveAuth())

    val data = runBlocking {
        withContext(Dispatchers.IO) {
            if (!logFile.exists()) return@withContext JSONArray()
            val lines = logFile.readLines()
            val arr = JSONArray()
            // 解析每行为 {time, level, msg}，级别统一交给 parseLogLevel，正则仅提取时间戳与正文
            lines.map { it to parseLogLine(it) }
                .filter { (raw, parts) ->
                    val (_, lvl, _) = parts
                    (search == null || raw.contains(search, ignoreCase = true)) &&
                    (level == null || lvl == level) &&
                    (allowedTypes == null || logType(raw) in allowedTypes)
                }
                .takeLast(limit)
                .forEach { (raw, parts) ->
                    val (time, lvl, msg) = parts
                    val obj = JSONObject()
                    obj.put("time", time)
                    obj.put("level", lvl)
                    obj.put("msg", msg)
                    obj.put("type", logType(raw))
                    arr.put(obj)
                }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// 从日志行解析级别
// logBuffer 格式: "[HH:mm:ss] [I] text"（来自 Log.logger wrapper，带时间戳）
// 旧格式兼容: "[I] text"
// 日志文件格式1: "[07-15-2026 18:45:37] [I] text"（arc Log 原始格式）
// 日志文件格式2: "[2026-07-15 | 18:45:37 | 信息] text"（wayzer java.util.logging 格式）
/** 识别日志行类型: chat(聊天) / command(命令及结果) / event(其他) */
private fun logType(line: String): String = when {
    // 注意: [WEBUI]/[CMD] 方括号会被日志格式化器当颜色码剥离, 必须用尖括号标记
    line.contains("<WEBUI>") || line.contains("<CMD>") -> "command"
    Regex("""<[TA]> """).containsMatchIn(line) -> "chat"
    else -> "event"
}

private fun parseLogLevel(line: String): String {
    // 1. logBuffer wrapper 格式(带时间戳): "[HH:mm:ss] [I] text"
    val timedRegex = Regex("""^\[\d{2}:\d{2}:\d{2}\]\s*\[([DIWE])\]""")
    val timedMatch = timedRegex.find(line)
    if (timedMatch != null) {
        return when (timedMatch.groupValues[1]) {
            "D" -> "DEBUG"
            "I" -> "INFO"
            "W" -> "WARN"
            "E" -> "ERROR"
            else -> "INFO"
        }
    }
    // 1b. logBuffer wrapper 旧格式: "[I] text"
    val regex = Regex("""^\[([DIWE])\]""")
    val match = regex.find(line)
    if (match != null) {
        return when (match.groupValues[1]) {
            "D" -> "DEBUG"
            "I" -> "INFO"
            "W" -> "WARN"
            "E" -> "ERROR"
            else -> "INFO"
        }
    }
    // 2. wayzer java.util.logging 格式: "[timestamp | 信息/警告/错误]" 或 "[timestamp | INFO/WARN/ERROR]"
    val julRegex = Regex("""\[\d{4}-\d{2}-\d{2}\s*\|\s*\d{2}:\d{2}:\d{2}\s*\|\s*(信息|警告|错误|调试|INFO|WARN|ERROR|ERR|DEBUG)\]""")
    val julMatch = julRegex.find(line)
    if (julMatch != null) {
        val tag = julMatch.groupValues[1]
        return when (tag) {
            "信息", "INFO" -> "INFO"
            "警告", "WARN" -> "WARN"
            "错误", "ERROR", "ERR" -> "ERROR"
            "调试", "DEBUG" -> "DEBUG"
            else -> "INFO"
        }
    }
    // 3. arc Log 日志文件格式: "[I] " 或 "[W] " 等（在时间戳之后）
    val arcRegex = Regex("""\[\d{2}-\d{2}-\d{4}\s+\d{2}:\d{2}:\d{2}\]\s*\[([DIWE])\]""")
    val arcMatch = arcRegex.find(line)
    if (arcMatch != null) {
        return when (arcMatch.groupValues[1]) {
            "D" -> "DEBUG"
            "I" -> "INFO"
            "W" -> "WARN"
            "E" -> "ERROR"
            else -> "INFO"
        }
    }
    // 4. 通用匹配
    if (line.contains("[INFO]", true) || line.contains("[I] ")) return "INFO"
    if (line.contains("[WARN]", true) || line.contains("[W] ")) return "WARN"
    if (line.contains("[ERR]", true) || line.contains("[E] ") || line.contains("ERROR", true)) return "ERROR"
    if (line.contains("[DEBUG]", true) || line.contains("[D] ")) return "DEBUG"
    return "INFO"
}

// 从日志行解析 {time, level, msg}
// 级别统一复用 parseLogLevel（单一来源），正则仅负责提取时间戳(HH:mm:ss)与消息正文
// 无法识别的行: time="", msg=原始行
// 兼容旧日志文件中残留的颜色码 (&xx, ANSI, [name])，先剥离再解析
private fun parseLogLine(line: String): Triple<String, String, String> {
    // 先剥离颜色码，兼容旧日志文件 (如 "&lb&fb[I]&fr text&fr" -> "[I] text")
    val cleaned = stripAllColorsFn?.invoke(line) ?: line
    val lvl = parseLogLevel(cleaned)
    // 1. logBuffer wrapper 格式(带时间戳): "[HH:mm:ss] [I] text"
    Regex("""^\[(\d{2}:\d{2}:\d{2})\]\s*\[[DIWE]\]\s*(.*)$""").find(cleaned)?.let { m ->
        return Triple(m.groupValues[1], lvl, m.groupValues[2])
    }
    // 2. wayzer java.util.logging 格式: "[YYYY-MM-DD | HH:mm:ss | 级别] message"
    Regex("""^\[\d{4}-\d{2}-\d{2}\s*\|\s*(\d{2}:\d{2}:\d{2})\s*\|\s*(?:信息|警告|错误|调试|INFO|WARN|ERROR|ERR|DEBUG)\]\s*(.*)$""").find(cleaned)?.let { m ->
        return Triple(m.groupValues[1], lvl, m.groupValues[2])
    }
    // 3. arc Log 日志文件格式: "[MM-DD-YYYY HH:mm:ss] [I] message"
    Regex("""^\[\d{2}-\d{2}-\d{4}\s+(\d{2}:\d{2}:\d{2})\]\s*\[[DIWE]\]\s*(.*)$""").find(cleaned)?.let { m ->
        return Triple(m.groupValues[1], lvl, m.groupValues[2])
    }
    // 4. logBuffer wrapper 旧格式: "[I] text"（无时间戳，去掉前缀）
    Regex("""^\[[DIWE]\]\s*(.*)$""").find(cleaned)?.let { m ->
        return Triple("", lvl, m.groupValues[1])
    }
    // 5. 无法识别: 无时间戳, msg 为清理后的行
    return Triple("", lvl, cleaned)
}

// ANSI 转义码转回 arc &xx 颜色码（前端 parseMindustryColors 可解析 &xx 码）
private fun ansiToArc(text: String): String {
    return text
        .replace("\u001b[0m", "&fr")
        .replace("\u001b[1m", "&fb")
        .replace("\u001b[3m", "&fi")
        .replace("\u001b[4m", "&fu")
        .replace("\u001b[30m", "&lk")
        .replace("\u001b[31m", "&lr")
        .replace("\u001b[32m", "&lg")
        .replace("\u001b[33m", "&ly")
        .replace("\u001b[34m", "&lb")
        .replace("\u001b[35m", "&lm")
        .replace("\u001b[36m", "&lc")
        .replace("\u001b[37m", "&lw")
        .replace("\u001b[90m", "&lk")
        .replace("\u001b[91m", "&lr")
        .replace("\u001b[92m", "&lg")
        .replace("\u001b[93m", "&ly")
        .replace("\u001b[94m", "&lb")
        .replace("\u001b[95m", "&lm")
        .replace("\u001b[96m", "&lc")
        .replace("\u001b[97m", "&lw")
}

// GET /api/logs/stream (SSE)
// 支持 ?seq=N 参数：客户端重连时传入最后收到的序号，服务端从该序号之后开始发送
private fun handleLogStream(exchange: HttpExchange) {
    val query = parseQuery(exchange.requestURI.query)
    val fromSeq = query["seq"]?.toLongOrNull() ?: 0L
    // 日志类型权限: 无 logs 全权限的用户 SSE 只推其有权限的类型
    val allowedTypes = allowedLogTypes(exchange.resolveAuth())

    // SSE 响应头
    exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
    exchange.responseHeaders.set("Cache-Control", "no-cache")
    exchange.responseHeaders.set("Connection", "keep-alive")
    exchange.responseHeaders.set("X-Accel-Buffering", "no") // 禁用 nginx 缓冲
    exchange.sendResponseHeaders(200, 0) // 0 = chunked encoding

    val out = exchange.responseBody
    val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    // 从日志条目提取时间戳(如有)
    fun extractTime(line: String): String {
        // logBuffer 格式: "[HH:mm:ss] [I] text"
        val timedMatch = Regex("""^\[(\d{2}:\d{2}:\d{2})\]""").find(line)
        if (timedMatch != null) return timedMatch.groupValues[1]
        // arc Log 日志文件格式: "[07-15-2026 18:45:37] [I] text"
        val arcMatch = Regex("""^\[\d{2}-\d{2}-\d{4}\s+(\d{2}:\d{2}:\d{2})\]""").find(line)
        if (arcMatch != null) return arcMatch.groupValues[1]
        // wayzer java.util.logging 格式: "[2026-07-15 | 18:45:37 | 信息] text"
        val julMatch = Regex("""^\[\d{4}-\d{2}-\d{2}\s*\|\s*(\d{2}:\d{2}:\d{2})\s*\|""").find(line)
        if (julMatch != null) return julMatch.groupValues[1]
        return java.time.LocalTime.now().format(timeFmt)
    }
    // 从日志行提取正文（去掉时间戳和级别前缀）
    fun extractMsg(line: String): String {
        var msg = line
        // logBuffer 格式: "[HH:mm:ss] [I] text"
        msg = msg.replace(Regex("""^\[\d{2}:\d{2}:\d{2}\]\s*\[[DIWE]\]\s*"""), "")
        // logBuffer 旧格式: "[I] text"
        msg = msg.replace(Regex("""^\[[DIWE]\]\s*"""), "")
        // wayzer java.util.logging 格式: "[timestamp | 信息] text"
        msg = msg.replace(Regex("""^\[\d{4}-\d{2}-\d{2}\s*\|\s*\d{2}:\d{2}:\d{2}\s*\|\s*[^\]]+\]\s*"""), "")
        // arc Log 日志文件格式: "[07-15-2026 18:45:37] [I] text"
        msg = msg.replace(Regex("""^\[\d{2}-\d{2}-\d{4}\s+\d{2}:\d{2}:\d{2}\]\s*\[[DIWE]\]\s*"""), "")
        return msg
    }

    // 写单条日志条目到 SSE 输出流(按类型权限过滤)
    fun writeEntry(entry: LogEntry) {
        if (allowedTypes != null && entry.type !in allowedTypes) return
        val msg = extractMsg(entry.text)
        val time = extractTime(entry.text)
        val jsonEntry = JSONObject()
            .put("seq", entry.seq)
            .put("time", time)
            .put("level", entry.level)
            .put("msg", msg)
            .put("type", entry.type)
        out.write("data: ${jsonEntry}\n\n".toByteArray(StandardCharsets.UTF_8))
    }

    try {
        var sentSeq = fromSeq

        // 发送缓冲区中序号 > fromSeq 的历史日志
        val snapshot = logBuffer.toList()
        for (entry in snapshot) {
            if (entry.seq <= fromSeq) continue
            writeEntry(entry)
            sentSeq = entry.seq
        }
        out.flush()

        // 发送就绪标记（前端据此知道历史日志已发完）
        out.write("event: ready\ndata: {\"lastSeq\":${sentSeq}}\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()

        // C3: 使用 logSignal.poll(timeout) 阻塞等待新日志信号，消除 Thread.sleep 忙轮询
        // logBuffer.toList() 读取快照不消费元素，支持多 SSE 客户端同时读取
        var lastHeartbeat = System.currentTimeMillis()
        while (true) {
            // 阻塞等待最多 1 秒，有新日志信号时立即返回
            logSignal.poll(1, TimeUnit.SECONDS)
            // 读取 logBuffer 快照（不消费元素），发送 sentSeq 之后的新条目
            val current = logBuffer.toList()
            var hasNew = false
            for (entry in current) {
                if (entry.seq > sentSeq) {
                    writeEntry(entry)
                    sentSeq = entry.seq
                    hasNew = true
                }
            }
            if (hasNew) {
                out.flush()
            }
            // 心跳：每 25 秒发送一次，保持连接活跃并让前端检测断线
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat > 25000) {
                out.write(": heartbeat\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                lastHeartbeat = now
            }
        }
    } catch (_: java.io.IOException) {
        // 客户端断开连接
    } catch (_: InterruptedException) {
        // 线程被中断，正常退出
    } catch (_: Exception) {
        // 其他异常（如 OutOfMemoryError 不在此捕获）
    } finally {
        try { out.close() } catch (_: Exception) {}
    }
}

// ==================== 多语言 ====================

// GET /api/world/snapshot - 当前世界实时快照
// C6: 缓存 PNG 缩略图，避免每次请求在游戏线程重新生成
private fun handleWorldSnapshot(exchange: HttpExchange) {
    try {
        val now = System.currentTimeMillis()
        val cacheSec = snapshotCacheSec.toLong()
        val cached = snapshotCache
        // 缓存有效期内直接返回缓存的 PNG
        if (cached != null && (now - cached.first) < cacheSec * 1000) {
            exchange.sendBytes(200, cached.second, "image/png")
            return
        }
        // 缓存过期或不存在：在游戏线程生成新快照
        val tmpFile = webuiDir.resolve("snapshot_tmp.png")
        val pngBytes = runBlocking {
            withContext(Dispatchers.game) {
                val pix = MapIO.generatePreview(Vars.world.tiles)
                Fi(tmpFile).writePng(pix)
                pix.dispose()
                tmpFile.readBytes()
            }
        }
        tmpFile.delete()
        // 更新缓存
        snapshotCache = now to pngBytes
        exchange.sendBytes(200, pngBytes, "image/png")
    } catch (e: Exception) {
        try { exchange.json(1, "Failed to generate snapshot: ${e.message}") } catch (_: Exception) {}
    }
}

// GET /api/content-icon/<codepoint>.png
private fun handleContentIcon(exchange: HttpExchange, rawPath: String) {
    val name = rawPath.removeSuffix(".png")
    val cp = name.toIntOrNull()
    if (cp == null) {
        exchange.json(1, "Invalid codepoint")
        return
    }
    // 懒加载：首次请求时初始化（加锁防止并发重复执行）
    if (!contentIconsInitialized) {
        synchronized(contentIconLock) {
            if (!contentIconsInitialized) {
                initContentIcons()
                contentIconsInitialized = true
            }
        }
    }
    val cacheFile = webuiDir.resolve("content-icons").resolve("$cp.png")
    if (!cacheFile.exists()) {
        exchange.json(1, "Icon not found")
        return
    }
    try {
        exchange.sendBytes(200, cacheFile.readBytes(), "image/png")
    } catch (e: Exception) {
        try { exchange.json(1, "Icon error: ${e.message}") } catch (_: Exception) {}
    }
}

// 初始化内容图标（从 Mindustry 源码 PNG 文件扫描）
// 服务器 headless 模式下 Core.atlas 为 null，无法使用图集 API
// 改为直接从源码 assets-raw/sprites/ 目录扫描 PNG 文件，智能匹配 textureName
private fun initContentIcons() {
    try {
        if (langApi.mindustrySourceDir.isBlank()) {
            arc.util.Log.warn("[ContentIcons] 未配置 mindustrySourceDir，内容图标将无法显示")
            return
        }
        val sourceDir = java.io.File(langApi.mindustrySourceDir)
        if (!sourceDir.exists()) {
            arc.util.Log.warn("[ContentIcons] 源码目录不存在: ${langApi.mindustrySourceDir}")
            return
        }
        val spritesDir = sourceDir.resolve("core/assets-raw/sprites")
        if (!spritesDir.exists()) {
            arc.util.Log.warn("[ContentIcons] sprites 目录不存在: ${spritesDir.absolutePath}")
            return
        }

        // 1. 从 JAR 读取 icons.properties
        val propsFi = Core.files.internal("icons/icons.properties")
        if (!propsFi.exists()) {
            arc.util.Log.warn("[ContentIcons] icons.properties 不存在")
            return
        }
        val props = java.util.Properties()
        propsFi.reader().use { r -> props.load(r) }
        arc.util.Log.info("[ContentIcons] icons.properties 共 ${props.size} 条图标定义")

        // 2. 扫描 sprites 目录下所有 PNG 文件，建立多键索引
        //    键包括：原始名、去连字符、去数字后缀、去连字符+去数字后缀
        //    这样 textureName "deepwater" 能匹配到源文件 "deep-water.png"
        //    textureName "stone" 能匹配到源文件 "stone1.png"
        val pngIndex = HashMap<String, java.io.File>()
        spritesDir.walkTopDown().filter { it.isFile && it.extension.equals("png", true) }.forEach { f ->
            val name = f.nameWithoutExtension
            pngIndex[name] = f
            val noHyphen = name.replace("-", "")
            if (noHyphen != name) pngIndex[noHyphen] = f
            val noNum = name.replace(Regex("\\d+$"), "")
            if (noNum != name) {
                pngIndex[noNum] = f
                val noHyphenNoNum = noHyphen.replace(Regex("\\d+$"), "")
                if (noHyphenNoNum != noNum) pngIndex[noHyphenNoNum] = f
            }
            val noHyphenNoNum2 = noHyphen.replace(Regex("\\d+$"), "")
            if (noHyphenNoNum2 != noHyphen) pngIndex[noHyphenNoNum2] = f
        }
        arc.util.Log.info("[ContentIcons] sprites 目录扫描到 ${pngIndex.size} 个 PNG 索引条目")

        // 3. 创建缓存目录
        val cacheDir = webuiDir.resolve("content-icons")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // 4. 对每个图标定义，智能匹配源 PNG 文件并复制到缓存
        //    icons.properties 格式: codepoint=name|textureName（如 63743=spawn|block-spawn-ui）
        var loaded = 0
        var notFound = 0
        val notFoundNames = ArrayList<String>()

        // 通用内容前缀（icons.properties 中 textureName 的命名空间）
        val namePrefixes = arrayOf("block", "unit", "item", "liquid", "status", "effect", "team", "region", "mech", "core", "spawn")

        for ((key, value) in props) {
            val cp = key.toString().toIntOrNull() ?: continue
            val parts = value.toString().split("|")
            val textureName = parts.getOrNull(1)?.trim() ?: parts[0].trim()

            val cacheFile = cacheDir.resolve("$cp.png")
            if (cacheFile.exists()) { loaded++; continue }  // 已缓存，跳过

            // 智能匹配：生成候选文件名列表，按优先级尝试
            // 索引已处理连字符和数字后缀，这里尝试：
            //  1) 原始 textureName
            //  2) 去掉 -ui 后缀（必须在移除前缀之前处理并加入候选，
            //     否则 item-copper-ui → copper-ui → copper，丢失 item-copper 这个关键候选）
            //  3) 去掉已知内容前缀（block/unit/item/liquid/status/...）
            //  4) 上述变体再去连字符
            //  5) 末尾数字变体（如 launch-pad-light -> launch-pad-l1 时）
            //  6) 内部数字全剥（如 scrap-1-cell -> scrap-cell）
            val candidates = LinkedHashSet<String>()
            candidates.add(textureName)
            var core = textureName
            if (core.endsWith("-ui")) {
                core = core.substring(0, core.length - 3)
                candidates.add(core)  // 关键：加入去 -ui 后的完整名（如 item-copper）
            }
            for (p in namePrefixes) {
                if (core.startsWith("$p-")) {
                    core = core.substring(p.length + 1)
                    break
                }
            }
            candidates.add(core)
            candidates.add("block-$core")
            candidates.add("$core-ui")
            // 末尾带 -N 的变体（已被索引处理为同名键）
            val noEndNum = core.replace(Regex("-\\d+$"), "")
            if (noEndNum != core) candidates.add(noEndNum)
            // 中间也带数字的变体（如 scrap-1-cell -> scrap-cell）
            val noAnyNum = core.replace(Regex("-\\d+"), "")
            if (noAnyNum != core) candidates.add(noAnyNum)
            // 所有候选去连字符变体
            val toAdd = ArrayList<String>()
            for (c in candidates) {
                val noHyphen = c.replace("-", "")
                if (noHyphen != c) toAdd.add(noHyphen)
            }
            candidates.addAll(toAdd)

            // 在索引中查找
            var sourceFile: java.io.File? = null
            for (c in candidates) {
                if (pngIndex.containsKey(c)) {
                    sourceFile = pngIndex[c]
                    break
                }
            }

            if (sourceFile == null) {
                notFound++
                if (notFoundNames.size < 15) notFoundNames.add("$textureName (cp=$cp)")
                continue
            }

            try {
                // 直接复制 PNG 文件到缓存
                sourceFile.copyTo(cacheFile, overwrite = true)
                loaded++
            } catch (e: Exception) {
                notFound++
                if (notFoundNames.size < 15) notFoundNames.add("$textureName (copy error: ${e.message})")
            }
        }

        arc.util.Log.info("[ContentIcons] 提取完成: 成功 $loaded, 未找到 $notFound")
        if (notFoundNames.isNotEmpty()) {
            arc.util.Log.info("[ContentIcons] 未找到示例: ${notFoundNames.joinToString(", ")}")
        }
    } catch (e: Exception) {
        arc.util.Log.err("[ContentIcons] 初始化内容图标失败: ${e.message}")
        e.printStackTrace()
    }
}

// GET /api/background
private fun handleBackground(exchange: HttpExchange) {
    // 每用户背景独立: 按当前登录用户返回其背景图
    val info = exchange.resolveAuth() ?: run { exchange.json(401, "Unauthorized", httpCode = 401); return }
    val user = webUsers[info.username]
    val bg = user?.background?.trim().orEmpty()
    if (bg.isBlank()) {
        exchange.json(0, "ok", JSONObject().put("enabled", false))
        return
    }
    val file = bgDir.resolve(bg)
    if (!file.exists() || !file.canonicalPath.startsWith(bgDir.canonicalPath)) {
        exchange.json(0, "ok", JSONObject().put("enabled", false))
        return
    }
    exchange.json(0, "ok", JSONObject()
        .put("enabled", true)
        .put("url", "/bg-img/" + bg)
    )
}

// GET /api/background/list — 列出背景图库(所有用户共享图库, 各自选择自己的背景)
private fun handleBackgroundList(exchange: HttpExchange) {
    val info = exchange.resolveAuth() ?: run { exchange.json(401, "Unauthorized", httpCode = 401); return }
    val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp")
    if (!bgDir.exists()) bgDir.mkdirs()
    val files = bgDir.listFiles { f ->
        f.isFile && f.extension.lowercase() in imageExts
    }?.sortedBy { it.name } ?: emptyList()
    val arr = JSONArray()
    files.forEach { f ->
        arr.put(JSONObject()
            .put("name", f.name)
            .put("url", "/bg-img/" + f.name)
            .put("size", f.length())
        )
    }
    val current = webUsers[info.username]?.background?.trim().orEmpty()
    exchange.json(0, "ok", JSONObject().put("current", current).put("files", arr))
}

// POST /api/background/set — 设置当前登录用户的背景
private fun handleBackgroundSet(exchange: HttpExchange) {
    val info = exchange.resolveAuth() ?: run { exchange.json(401, "Unauthorized", httpCode = 401); return }
    val user = webUsers[info.username] ?: run { exchange.json(401, "Unauthorized", httpCode = 401); return }
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val name = json.optString("name", "").trim()
    if (name.isBlank()) {
        // 清除背景
        user.background = ""
        saveUsers()
        exchange.json(0, "ok")
        return
    }
    // 安全校验
    if (!bgDir.exists()) bgDir.mkdirs()
    val file = bgDir.resolve(name)
    if (!file.exists() || !file.canonicalPath.startsWith(bgDir.canonicalPath)) {
        exchange.json(1, "File not found")
        return
    }
    user.background = name
    saveUsers()
    exchange.json(0, "ok")
}

// POST /api/background/upload (multipart)
private fun handleBackgroundUpload(exchange: HttpExchange) {
    val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
    if (!contentType.startsWith("multipart/form-data")) {
        exchange.json(1, "Expected multipart/form-data")
        return
    }
    val boundary = contentType.substringAfter("boundary=", "").trim()
    if (boundary.isBlank()) {
        exchange.json(1, "No boundary")
        return
    }
    try {
        val body = exchange.requestBody.readBytes()
        val parts = parseMultipart(body, boundary)
        val filePart = parts.find { it.name == "file" }
        if (filePart == null || filePart.data.isEmpty()) {
            exchange.json(1, "No file uploaded")
            return
        }
        val filename = filePart.filename ?: "upload.png"
        // 安全校验文件名
        val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (safeName.contains("..")) {
            exchange.json(1, "Invalid filename")
            return
        }
        val info = exchange.resolveAuth() ?: run { exchange.json(401, "Unauthorized", httpCode = 401); return }
        val user = webUsers[info.username] ?: run { exchange.json(401, "Unauthorized", httpCode = 401); return }
        if (!bgDir.exists()) bgDir.mkdirs()
        val target = bgDir.resolve(safeName)
        if (!target.canonicalPath.startsWith(bgDir.canonicalPath)) {
            exchange.json(1, "Invalid path")
            return
        }
        target.writeBytes(filePart.data)
        // 上传成功后自动设为当前用户的背景
        user.background = safeName
        saveUsers()
        exchange.json(0, "ok", JSONObject().put("name", safeName))
    } catch (e: Exception) {
        exchange.json(1, "Upload failed: ${e.message}")
    }
}

// POST /api/background/delete
private fun handleBackgroundDelete(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val name = json.optString("name", "").trim()
    if (name.isBlank()) {
        exchange.json(1, "Name required")
        return
    }
    if (!bgDir.exists()) bgDir.mkdirs()
    val file = bgDir.resolve(name)
    if (!file.canonicalPath.startsWith(bgDir.canonicalPath)) {
        exchange.json(1, "Invalid path")
        return
    }
    if (!file.exists()) {
        exchange.json(1, "File not found")
        return
    }
    file.delete()
    // 删除后清掉所有引用该图的用户背景
    webUsers.values.forEach { if (it.background == name) it.background = "" }
    saveUsers()
    exchange.json(0, "ok")
}

// multipart 解析
private data class MultipartPart(val name: String, val filename: String?, val data: ByteArray)
private fun parseMultipart(body: ByteArray, boundary: String): List<MultipartPart> {
    val parts = mutableListOf<MultipartPart>()
    val delimiter = ("--" + boundary).toByteArray(StandardCharsets.UTF_8)
    val headerEnd = "\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
    val start = indexOf(body, delimiter)
    if (start < 0) return parts
    var pos = start + delimiter.size
    while (pos < body.size) {
        // 跳过 \r\n
        if (pos + 2 <= body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
            pos += 2
        }
        if (pos < body.size && body[pos] == '-'.code.toByte() && pos + 1 < body.size && body[pos + 1] == '-'.code.toByte()) {
            break // --boundary-- 结束
        }
        // 查找头部结束
        val hdrEnd = indexOf(body, headerEnd, pos)
        if (hdrEnd < 0) break
        val headerStr = String(body, pos, hdrEnd - pos, StandardCharsets.UTF_8)
        // 解析 Content-Disposition
        var name = ""
        var filename: String? = null
        for (line in headerStr.split("\r\n")) {
            if (line.startsWith("Content-Disposition:", ignoreCase = true)) {
                val nameMatch = Regex("name=\"([^\"]+)\"").find(line)
                if (nameMatch != null) name = nameMatch.groupValues[1]
                val fileMatch = Regex("filename=\"([^\"]+)\"").find(line)
                if (fileMatch != null) filename = fileMatch.groupValues[1]
            }
        }
        val dataStart = hdrEnd + headerEnd.size
        // 查找下一个 boundary
        val nextDelimiter = indexOf(body, delimiter, dataStart)
        val dataEnd = if (nextDelimiter > 0) nextDelimiter - 2 else body.size // -2 去掉末尾 \r\n
        if (dataEnd > dataStart) {
            parts.add(MultipartPart(name, filename, body.copyOfRange(dataStart, dataEnd)))
        }
        pos = if (nextDelimiter > 0) nextDelimiter else body.size
    }
    return parts
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return -1
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

// GET /api/i18n/langs - 返回可用语言列表(动态扫描 lang/ 目录)
private fun handleI18nLangs(exchange: HttpExchange) {
    val data = runBlocking {
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            // 优先使用 langApi 注册的语言(与游戏内 /lang 一致)
            langApi.supportedLangs.forEach { (code, name) ->
                arr.put(JSONObject().put("code", code).put("name", name))
            }
            // 如果 langApi 为空(兜底),扫描 lang/ 目录（.properties 格式）
            if (arr.length() == 0) {
                val langDir = Config.dataDir.resolve("lang")
                if (langDir.exists()) {
                    langDir.listFiles()?.forEach { f ->
                        val n = f.name
                        if (n == "bundle.properties") {
                            arr.put(JSONObject().put("code", "en").put("name", "English"))
                        } else if (n.startsWith("bundle_") && n.endsWith(".properties")) {
                            val code = n.removePrefix("bundle_").removeSuffix(".properties")
                            arr.put(JSONObject().put("code", code).put("name", code))
                        }
                    }
                }
            }
            arr
        }
    }
    exchange.json(0, "ok", data)
}

// GET /api/i18n?lang=zh_CN
// A1: 从 LangService.getBundleData() 读取翻译（数据来自 .properties 语言包）
// bundleData 结构: key -> (langCode -> translation)，返回请求语言的完整翻译表（含 zh_CN fallback）
private fun handleI18n(exchange: HttpExchange, lang: String) {
    val data = JSONObject()
    val bundles = langApi.getBundleMap()
    bundles.forEach { (key, translations) ->
        val text = translations[lang] ?: translations["zh_CN"] ?: translations["en"]
        if (text != null) {
            data.put(key, text)
        }
    }
    exchange.json(0, "ok", data)
}

// ==================== 静态文件服务 ====================

private fun serveStatic(exchange: HttpExchange, path: String) {
    // 安全: 去除前导 /
    val relativePath = path.removePrefix("/")
    // 防路径穿越
    if (relativePath.contains("..")) {
        exchange.sendBody(403, "Forbidden", "text/plain")
        return
    }

    // 背景图片列表预览
    if (relativePath.startsWith("bg-img/")) {
        val imgName = relativePath.removePrefix("bg-img/")
        if (!bgDir.exists()) bgDir.mkdirs()
        val imgFile = bgDir.resolve(imgName)
        if (imgFile.exists() && imgFile.isFile && imgFile.canonicalPath.startsWith(bgDir.canonicalPath)) {
            exchange.sendBytes(200, imgFile.readBytes(), contentTypeFor(imgFile.name))
            return
        }
    }

    // 根路径重定向到 login
    if (relativePath.isEmpty() || relativePath == "/") {
        exchange.responseHeaders.set("Location", "/login.html")
        exchange.sendResponseHeaders(302, -1)
        return
    }

    // 受保护页面: 未登录直接 302 跳转 login.html，不返回 HTML 内容
    // (大小写不敏感匹配, 防止 Windows 文件系统大小写不敏感绕过 302)
    if (PROTECTED_PAGES.any { it.equals(relativePath, ignoreCase = true) } && exchange.resolveAuth() == null) {
        // 清除无效 cookie，防止前端检测到残留 cookie 后跳回 dashboard 形成循环
        exchange.clearCookie(SESSION_COOKIE)
        exchange.responseHeaders.set("Location", "/login.html")
        exchange.sendResponseHeaders(302, -1)
        return
    }

    val file = webuiDir.resolve(relativePath)
    if (!file.exists() || file.isDirectory) {
        exchange.sendBody(404, "Not Found", "text/plain")
        return
    }

    val bytes = file.readBytes()
    // 静态文件禁用浏览器缓存, 确保 JS/CSS 修改后立即生效 (避免用户看到旧版 app.js)
    exchange.responseHeaders.set("Cache-Control", "no-cache, must-revalidate")
    exchange.sendBytes(200, bytes, contentTypeFor(file.name))
}

// ==================== HttpServer 生命周期 ====================

// 记录 auto 模式下管理 token 的生成时间,用于判断是否需要刷新
private var adminTokenGeneratedAt: Instant = Instant.now()
// auto 模式下实际使用的管理 token(不写入 config.conf,仅在内存)
private var autoAdminToken: String = ""

// 获取当前有效的管理 token (auto 模式下检查是否需要刷新)
private fun effectiveToken(): String {
    if (token == "auto" || token.isBlank()) {
        // auto 模式:检查是否到期
        val expireSeconds = maxOf(tokenExpire.toLong(), 60L)
        val now = Instant.now()
        if (autoAdminToken.isBlank() || now.isAfter(adminTokenGeneratedAt.plusSeconds(expireSeconds))) {
            // 到期,生成新 token
            autoAdminToken = genToken()
            adminTokenGeneratedAt = now
            sessions.clear() // 清空所有 session,强制重新登录
            logger.info("WebUI auto 模式 token 已自动刷新,旧 session 已清空")
            logger.info("WebUI 新管理 token: $autoAdminToken，请妥善保管")
        }
        return autoAdminToken
    }
    return token
}

/** 创建 HTTP/HTTPS 服务器: sslEnabled 时加载 PKCS12 证书库启用 TLS, 失败降级为 HTTP */
private fun createHttpServer(): HttpServer {
    if (sslEnabled) {
        val ksFile = Config.dataDir.resolve(sslKeystore)
        if (ksFile.exists()) {
            return try {
                val ks = java.security.KeyStore.getInstance("PKCS12")
                java.io.FileInputStream(ksFile).use { ks.load(it, sslKeystorePass.toCharArray()) }
                val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, sslKeystorePass.toCharArray())
                val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
                sslCtx.init(kmf.keyManagers, null, null)
                com.sun.net.httpserver.HttpsServer.create(InetSocketAddress(host, port), 0).apply {
                    httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(sslCtx)
                }
            } catch (e: Exception) {
                logger.warning("[webui] HTTPS 初始化失败(${e.message}), 降级为 HTTP")
                HttpServer.create(InetSocketAddress(host, port), 0)
            }
        }
        logger.warning("[webui] HTTPS 已启用但证书库不存在: $ksFile (请用 keytool 生成), 降级为 HTTP")
    }
    return HttpServer.create(InetSocketAddress(host, port), 0)
}

private fun startServer() {
    try {
        // token 为 auto 或空时:每次启动生成新 token(不写回 config.conf)
        if (token == "auto" || token.isBlank()) {
            autoAdminToken = genToken()
            adminTokenGeneratedAt = Instant.now()
            logger.info("WebUI 已生成管理 token (auto 模式): $autoAdminToken，请妥善保管")
            logger.info("Token 有效期: ${tokenExpire} 秒,到期后自动刷新")
        }

        httpServer = createHttpServer()
        httpServer!!.createContext("/", { exchange ->
            try {
                val path = exchange.requestURI.path
                if (path.startsWith("/api/")) {
                    handleApi(exchange)
                } else {
                    serveStatic(exchange, path)
                }
            } catch (e: Exception) {
                try { exchange.json(1, "Internal error: ${e.message}") } catch (_: Exception) {}
            } finally {
                exchange.close()
            }
        })
        httpServer!!.executor = executor
        httpServer!!.start()
        logger.info("WebUI 已启动，监听 http://$host:$port")
    } catch (e: java.net.BindException) {
        logger.severe("WebUI 端口 $port 被占用，WebUI 未启动: ${e.message}")
    } catch (e: Exception) {
        logger.severe("WebUI 启动失败: ${e.message}")
    }
}

// 清理过期 session
private fun cleanupSessions() {
    val now = Instant.now()
    val expired = sessions.entries.filter { it.value.expire.isBefore(now) }
    expired.forEach { sessions.remove(it.key) }
    if (expired.isNotEmpty()) {
        logger.info("WebUI 清理过期 session: ${expired.size} 个")
    }
}

// ==================== 脚本生命周期 ====================

onEnable {
    // 加载用户系统 (内置游客, 默认禁用)
    logger.info("[webui] rateLimit: enabled=${rateLimitEnabled} req=${rateLimitPerSec}/s burst=${rateLimitBurst} op=${rateOpPerSec}/s opBurst=${rateOpBurst} perNode=${rateLimitPerNode}")
    loadUsers()
    loadKeys()
    // 启动时从日志文件种子 logBuffer（捕获 onEnable 之前的日志）
    try {
        if (logFile.exists()) {
            val lines = logFile.readLines()
            val startIdx = (lines.size - 100).coerceAtLeast(0)
            for (i in startIdx until lines.size) {
                val seq = logSeqCounter.incrementAndGet()
                val level = parseLogLevel(lines[i])
                logBuffer.add(LogEntry(seq, level, lines[i], logType(lines[i])))
            }
            while (logBuffer.size > MAX_LOG_BUFFER) logBuffer.poll()
        }
    } catch (_: Exception) {}
    // 注册日志钩子: 从 console.kts 的 Log.formatter 获取带 Mindustry 颜色码的文本
    // Log.logger 收到的 text 已经过 Log.formatter 转换（ANSI 码或无颜色）
    // 但 unifiedTextHolder 保存了格式化前的 unified 文本（含 &xx 和 [name] 颜色码）
    val origLogger = arc.util.Log.logger
    val logTimeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    arc.util.Log.logger = object : arc.util.Log.LogHandler {
        override fun log(level: arc.util.Log.LogLevel, text: String) {
            try {
                val levelTag = if (level === arc.util.Log.LogLevel.debug) "D"
                    else if (level === arc.util.Log.LogLevel.info) "I"
                    else if (level === arc.util.Log.LogLevel.warn) "W"
                    else if (level === arc.util.Log.LogLevel.err) "E"
                    else "I"
                val levelFull = when (levelTag) {
                    "D" -> "DEBUG"
                    "I" -> "INFO"
                    "W" -> "WARN"
                    "E" -> "ERROR"
                    else -> "INFO"
                }
                val timeStr = java.time.LocalTime.now().format(logTimeFmt)
                // 优先使用 ThreadLocal 中的原始文本（含 Mindustry 颜色码 [name] [#hex] &xx）
                // 回退到 text（ANSI 码，前端也支持解析）
                val rawColored = unifiedTextHolder?.get()
                unifiedTextHolder?.remove()
                val displayText = rawColored ?: text
                val formatted = "[$timeStr] [$levelTag] $displayText"
                val seq = logSeqCounter.incrementAndGet()
                logBuffer.add(LogEntry(seq, levelFull, formatted, logType(formatted)))
                while (logBuffer.size > MAX_LOG_BUFFER) logBuffer.poll()
                // C3: 通知所有 SSE 等待线程有新日志
                logSignal.offer(Unit)
            } catch (_: Exception) {}
            origLogger.log(level, text)
        }
    }
    // 内容图标懒加载：Core.atlas 在 onEnable 时未初始化，改为首次请求 /api/content-icon/ 时触发
    if (webuiEnabled) {
        startServer()
        // C1: 游戏线程定时更新状态快照（HTTP 线程直接读取，避免 runBlocking 阻塞）
        loop(Dispatchers.game) {
            try {
                statusSnapshot = buildStatusSnapshot()
            } catch (e: Exception) {
                logger.warning("WebUI 状态快照更新失败: ${e.message}")
            }
            delay(statusSnapshotIntervalMs.toLong())
        }
        // C7: 预初始化内容图标（延迟 10 秒等待游戏资源加载完成，保留懒加载兜底）
        launch(Dispatchers.game) {
            delay(10000)
            if (!contentIconsInitialized) {
                synchronized(contentIconLock) {
                    if (!contentIconsInitialized) {
                        initContentIcons()
                        contentIconsInitialized = true
                    }
                }
            }
        }
    }
    // 后台协程清理过期 session，用 Dispatchers.Default 不阻塞主线程
    loop(Dispatchers.Default) {
        delay(10 * 60 * 1000)
        cleanupSessions()
    }
}

onDisable {
    httpServer?.stop(1)
    executor.shutdown()
    try {
        executor.awaitTermination(2, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {}
    logger.info("WebUI 已停止")
}

// ==================== 公告 API ====================

// 通过 depends/import 访问 announcements.kts 的导出
// 使用 String 跨脚本传递避免 ClassLoader 不一致导致 ClassCastException
val getAnnouncementsJsonString: () -> String by lazy {
    depends("wayzer/ext/announcements")?.import<() -> String>("getAnnouncementsJsonString") ?: { "[]" }
}
val addAnnouncement: (String, String, Boolean) -> Any? by lazy {
    depends("wayzer/ext/announcements")?.import<(String, String, Boolean) -> Any?>("addAnnouncement") ?: { _, _, _ -> null }
}
val updateAnnouncement: (Int, String?, String?, Boolean?) -> Boolean by lazy {
    depends("wayzer/ext/announcements")?.import<(Int, String?, String?, Boolean?) -> Boolean>("updateAnnouncement") ?: { _, _, _, _ -> false }
}
val deleteAnnouncement: (Int) -> Boolean by lazy {
    depends("wayzer/ext/announcements")?.import<(Int) -> Boolean>("deleteAnnouncement") ?: { false }
}
val notifyAllOnline: () -> Unit by lazy {
    depends("wayzer/ext/announcements")?.import<() -> Unit>("notifyAllOnline") ?: {}
}

private fun handleAnnouncementsList(exchange: HttpExchange) {
    val jsonStr = getAnnouncementsJsonString()
    val list = JSONArray(jsonStr)
    val data = JSONObject().apply {
        put("announcements", list)
    }
    exchange.json(0, "ok", data)
}

private fun handleAnnouncementsSave(exchange: HttpExchange) {
    try {
        val body = JSONObject(exchange.readBody())
        val id = body.optInt("id", -1)
        val title = body.optString("title", "")
        val content = body.optString("content", "")
        val pinned = body.optBoolean("pinned", false)

        if (title.isBlank() || content.isBlank()) {
            exchange.json(1, "标题和内容不能为空")
            return
        }

        if (id > 0) {
            val ok = updateAnnouncement(id, title, content, pinned)
            if (!ok) {
                exchange.json(1, "公告不存在")
                return
            }
        } else {
            addAnnouncement(title, content, pinned)
        }
        exchange.json(0, "ok")
    } catch (e: Exception) {
        exchange.json(1, "保存失败: ${e.message}")
    }
}

private fun handleAnnouncementsDelete(exchange: HttpExchange) {
    try {
        val body = JSONObject(exchange.readBody())
        val id = body.getInt("id")
        val ok = deleteAnnouncement(id)
        if (ok) exchange.json(0, "ok")
        else exchange.json(1, "公告不存在")
    } catch (e: Exception) {
        exchange.json(1, "删除失败: ${e.message}")
    }
}

private fun handleAnnouncementsNotify(exchange: HttpExchange) {
    notifyAllOnline()
    exchange.json(0, "ok")
}

// ==================== 每日一言 API ====================

private fun handleDailyQuote(exchange: HttpExchange) {
    val now = System.currentTimeMillis()
    val cached = dailyQuoteCache
    // 缓存 5 分钟内有效：直接返回
    if (cached != null && (now - cached.first) < 5 * 60 * 1000) {
        exchange.json(0, "ok", JSONObject().put("text", cached.second))
        return
    }
    // 缓存过期或不存在：立即返回缓存/默认值，异步刷新
    val defaultText = cached?.second ?: "不积跬步，无以至千里。"
    exchange.json(0, "ok", JSONObject().put("text", defaultText))
    // C5: 异步获取一言，不阻塞 HTTP 线程
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val conn = java.net.URL("https://v1.hitokoto.cn/?encode=text").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            dailyQuoteCache = System.currentTimeMillis() to text
        } catch (_: Exception) {}
    }
}

// GET /api/colors — 返回完整的 WebUI 颜色映射数据（无需认证）
private fun handleColors(exchange: HttpExchange) {
    try {
        // 初始化 ColorExtractor 的源码目录（如果尚未设置）
        if (ColorExtractor.sourceDir.isBlank()) {
            ColorExtractor.sourceDir = langApi.mindustrySourceDir
        }
        val rawData = ColorExtractor.generateFrontendColors()
        val data = JSONObject()
        for ((key, value) in rawData) {
            when (value) {
                is Map<*, *> -> data.put(key, JSONObject(value as Map<*, *>))
                is Number -> data.put(key, value)
                is String -> data.put(key, value)
                is Boolean -> data.put(key, value)
                else -> data.put(key, value.toString())
            }
        }
        exchange.json(0, "ok", data)
    } catch (e: Exception) {
        exchange.json(1, "生成颜色数据失败: ${e.message}")
    }
}

// C4: GET /api/session/check — 轻量级会话检查
// 验证 session token 有效(含游客降级), 不访问游戏线程
private fun handleSessionCheck(exchange: HttpExchange) {
    val valid = exchange.resolveAuth() != null
    exchange.json(0, "ok", JSONObject().put("valid", valid))
}

// GET /api/auth/guest-status — 游客开关状态 (登录页显示游客入口用)
private fun handleGuestStatus(exchange: HttpExchange) {
    exchange.json(0, "ok", JSONObject().put("enabled", guestAvailable()))
}

// GET /api/me — 当前用户信息(用户名/角色/权限), 供前端侧边栏按权限过滤
private fun handleMe(exchange: HttpExchange) {
    val info = exchange.resolveAuth()
    if (info == null) {
        exchange.json(401, "Unauthorized")
        return
    }
    val perms = JSONArray()
    if (info.role == ROLE_ADMIN) {
        PERMISSION_NODES.forEach { (p, _) -> perms.put(p) }
        ADMIN_ONLY_PERMS.forEach { perms.put(it) }
    } else {
        info.permissions.sorted().forEach { perms.put(it) }
    }
    exchange.json(0, "ok", JSONObject()
        .put("username", info.username)
        .put("role", info.role)
        .put("permissions", perms))
}

// ==================== 用户管理 API (admin 专属) ====================

/** 过滤非法权限节点, 只保留可分配的节点 */
private fun validPerms(arr: JSONArray?): MutableSet<String> {
    val all = PERMISSION_NODES.map { it.first }.toSet()
    val out = mutableSetOf<String>()
    arr?.forEach { val p = it.toString(); if (p in all) out.add(p) }
    return out
}

private fun webUserToJson(u: WebUser): JSONObject {
    val perms = JSONArray()
    u.permissions.sorted().forEach { perms.put(it) }
    return JSONObject()
        .put("username", u.username)
        .put("role", u.role)
        .put("disabled", u.disabled)
        .put("permissions", perms)
}

// GET /api/users — 用户列表(含游客)
private fun handleUsersList(exchange: HttpExchange) {
    val arr = JSONArray()
    // 不暴露管理员虚拟记录(凭据在 config, 仅内部存背景)
    webUsers.values.filter { it.role != ROLE_ADMIN }.sortedBy { it.username }.forEach { arr.put(webUserToJson(it)) }
    exchange.json(0, "ok", JSONObject()
        .put("admin", adminUser)
        .put("guestEnabled", webUsers["guest"]?.disabled == false)
        .put("users", arr))
}

// GET /api/users/perm-nodes — 可分配权限节点清单(前端用户管理页勾选用)
private fun handlePermNodes(exchange: HttpExchange) {
    val arr = JSONArray()
    PERMISSION_NODES.forEach { (p, descKey) ->
        arr.put(JSONObject().put("node", p).put("key", descKey))
    }
    exchange.json(0, "ok", arr)
}

// POST /api/users — 创建普通用户
private fun handleUserCreate(exchange: HttpExchange) {
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    val name = json.optString("username", "").trim()
    val pwd = json.optString("password", "")
    if (name.isBlank() || name.length > 32 || !Regex("^[a-zA-Z0-9_.-]+$").matches(name)) {
        exchange.json(1, "Invalid username")
        return
    }
    if (name == adminUser || webUsers.containsKey(name)) {
        exchange.json(1, "Username already exists")
        return
    }
    if (pwd.length < 6) {
        exchange.json(1, "Password too short (min 6)")
        return
    }
    webUsers[name] = WebUser(name, makePasswordHash(pwd), ROLE_USER, disabled = false, validPerms(json.optJSONArray("permissions")))
    saveUsers()
    exchange.json(0, "ok")
}

// PUT /api/users/:name — 更新用户(启用禁用/权限/重置密码)
private fun handleUserUpdate(exchange: HttpExchange, name: String) {
    val u = webUsers[name] ?: run { exchange.json(1, "User not found"); return }
    if (u.role == ROLE_ADMIN) {
        exchange.json(1, "Cannot modify admin")
        return
    }
    val body = exchange.readBody()
    val json = try { JSONObject(body) } catch (_: Exception) {
        exchange.json(1, "Invalid JSON")
        return
    }
    if (u.role == ROLE_GUEST) {
        // 游客只切换启用状态(无需登录, 无密码), 权限固定
        if (json.has("disabled")) u.disabled = json.optBoolean("disabled")
    } else {
        if (json.has("disabled")) u.disabled = json.optBoolean("disabled")
        if (json.has("permissions")) u.permissions = validPerms(json.optJSONArray("permissions"))
        if (json.has("password")) {
            val pwd = json.optString("password")
            if (pwd.isNotBlank()) {
                if (pwd.length < 6) { exchange.json(1, "Password too short (min 6)"); return }
                u.pwdHash = makePasswordHash(pwd)
            }
        }
    }
    saveUsers()
    exchange.json(0, "ok")
}

// DELETE /api/users/:name — 删除普通用户
private fun handleUserDelete(exchange: HttpExchange, name: String) {
    val u = webUsers[name] ?: run { exchange.json(1, "User not found"); return }
    if (u.role != ROLE_USER) {
        exchange.json(1, "Cannot delete guest")
        return
    }
    webUsers.remove(name)
    saveUsers()
    exchange.json(0, "ok")
}
