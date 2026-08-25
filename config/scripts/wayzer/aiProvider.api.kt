package wayzer

import arc.util.Http
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// ============ 数据结构 ============

enum class ProviderType { OPENAI, CLAUDE, GOOGLE }

data class ProviderConfig(
    val type: ProviderType = ProviderType.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
)

data class GenerationParams(
    val temperature: Float? = null,
    // 思考代价: none/low/medium/high/xhigh/max
    val reasoningEffort: String? = null,
    // 是否思考: true=开启(思考代价生效), false=关闭, null=不指定(按各API默认)
    val thinking: Boolean? = null,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

data class GenerationResult(
    val content: String,
    val reasoning: String? = null,
    val usage: TokenUsage? = null,
    val durationMs: Long = 0,
)

// ============ jtokkit Token 计数 ============

val encodingRegistry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()
val tokenEncoding: Encoding = encodingRegistry.getEncoding(EncodingType.CL100K_BASE)

fun calcTokens(text: String): Int = tokenEncoding.countTokens(text)

fun calcTokens(messages: List<JSONObject>): Int =
    messages.toList().sumOf { calcTokens(it.getString("content")) }

// ============ 1024 进制格式化（4K/1.00M） ============

fun formatTokens(tokens: Int): String {
    val v = tokens.toLong()
    return when {
        v >= 1024L * 1024 -> "%.2fM".format(v.toDouble() / (1024 * 1024))
        v >= 1024 -> "%.2fK".format(v.toDouble() / 1024)
        else -> v.toString()
    }
}

// ============ buildApiUrl 工具函数 ============

fun buildApiUrl(baseUrl: String, endpoint: String): String {
    val base = baseUrl.trimEnd('/')
    return if (base.endsWith("/v1", ignoreCase = true)) "$base$endpoint"
    else "$base/v1$endpoint"
}

// ============ HTTP + JSON 请求挂起包装 ============

suspend fun httpJsonRequest(
    method: Http.HttpMethod,
    url: String,
    headers: Map<String, String>,
    body: String?,
    timeoutMs: Int,
): JSONObject? = suspendCoroutine { cb ->
    val req = Http.request(method, url).timeout(timeoutMs)
    for ((k, v) in headers) req.header(k, v)
    if (body != null) req.content(body)
    req.error { cb.resume(null) }
        .submit {
            try {
                val json = JSONObject(it.resultAsString)
                if (json.has("error")) cb.resume(null) else cb.resume(json)
            } catch (e: Exception) {
                cb.resume(null)
            }
        }
}

// ============ OpenAI 格式生成 ============
// 参考 rikkahub ChatCompletionsAPI.kt
// 请求: POST {baseUrl}/v1/chat/completions
// Body: {model, messages, temperature?, top_p?, max_tokens?, reasoning_effort?, stream}
// 响应: {choices:[{message:{content, reasoning_content?, reasoning?, thinking?}}], usage:{prompt_tokens, completion_tokens, total_tokens}}

suspend fun generateOpenAI(
    config: ProviderConfig,
    messages: List<JSONObject>,
    params: GenerationParams,
    timeoutMs: Int,
): GenerationResult? {
    val url = buildApiUrl(config.baseUrl, "/chat/completions")
    val messagesArray = JSONArray()
    for (msg in messages) messagesArray.put(msg)
    val body = JSONObject()
        .put("model", config.model)
        .put("messages", messagesArray)
        .put("stream", false)
    // 只在参数非空时才放入请求体(未配置=不发送)
    params.temperature?.let { body.put("temperature", it) }
    // reasoning_effort: 参照 rikkahub ChatCompletionsAPI.kt
    // none=不传(使用默认), minimal/low/medium/high/xhigh/max 直接传
    // thinking=false 时强制不思考(不传 reasoning_effort)
    val thinkingOn = params.thinking != false
    val effort = params.reasoningEffort
    if (thinkingOn && effort != null && !effort.equals("none", ignoreCase = true)) {
        body.put("reasoning_effort", effort)
    }
    val headers = mutableMapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer ${config.apiKey}",
    )
    headers.putAll(config.customHeaders)
    val json = httpJsonRequest(Http.HttpMethod.POST, url, headers, body.toString(), timeoutMs) ?: return null
    val choices = json.optJSONArray("choices")
    if (choices == null || choices.length() == 0) return null
    val choice = choices.optJSONObject(0) ?: return null
    val message = choice.optJSONObject("message")
    val content = message?.optString("content") ?: ""
    // 兼容不同 API 返回的思考内容字段名:
    // - reasoning_content (DeepSeek)
    // - reasoning (OpenAI o1/o3)
    // - thinking (部分 API)
    // - content 数组中的 thinking 类型 (Mistral Magistral)
    var reasoning = message?.let {
        it.optString("reasoning_content").takeIf { s -> s.isNotEmpty() }
            ?: it.optString("reasoning").takeIf { s -> s.isNotEmpty() }
            ?: it.optString("thinking").takeIf { s -> s.isNotEmpty() }
    }
    // Mistral 数组形式: content 是 JsonArray, 包含 {type:"thinking", thinking:[{type:"text", text:"..."}]}
    if (reasoning.isNullOrBlank() && message != null) {
        val contentArr = message.optJSONArray("content")
        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val part = contentArr.optJSONObject(i) ?: continue
                if (part.optString("type") == "thinking") {
                    val thinkingArr = part.optJSONArray("thinking")
                    if (thinkingArr != null) {
                        val sb = StringBuilder()
                        for (j in 0 until thinkingArr.length()) {
                            val t = thinkingArr.optJSONObject(j) ?: continue
                            sb.append(t.optString("text", ""))
                        }
                        if (sb.isNotEmpty()) reasoning = sb.toString()
                    }
                }
            }
        }
    }
    val usage = json.optJSONObject("usage")
    val tokenUsage = if (usage != null) TokenUsage(
        usage.optInt("prompt_tokens", 0),
        usage.optInt("completion_tokens", 0),
        usage.optInt("total_tokens", 0),
    ) else null
    return GenerationResult(content, reasoning?.takeIf { it.isNotEmpty() }, tokenUsage)
}

// ============ Claude 格式生成 ============
// 参考 rikkahub ClaudeProvider.kt
// 请求: POST {baseUrl}/v1/messages
// Headers: x-api-key, anthropic-version: 2023-06-01
// Body: {model, messages:[{role, content:[{type:"text", text:"..."}]}], max_tokens, system?:[{type:"text", text:"..."}], temperature?, top_p?, thinking?:{type:"disabled"|"adaptive"}, stream}
// 响应: {content:[{type:"text", text:"..."}, {type:"thinking", thinking:"..."}], usage:{input_tokens, output_tokens}}

suspend fun generateClaude(
    config: ProviderConfig,
    messages: List<JSONObject>,
    params: GenerationParams,
    timeoutMs: Int,
): GenerationResult? {
    val url = buildApiUrl(config.baseUrl, "/messages")
    // Claude 格式: system 提取为顶层字段, 其余消息 content 用数组格式
    val systemText = messages.filter { it.optString("role") == "system" }
        .joinToString("\n\n") { it.optString("content", "") }
    val claudeMessages = JSONArray()
    for (msg in messages) {
        val role = msg.optString("role")
        if (role == "system") continue
        val content = msg.optString("content", "")
        // content 用数组格式 [{type:"text", text:"..."}]
        val contentArr = JSONArray()
        contentArr.put(JSONObject().put("type", "text").put("text", content))
        claudeMessages.put(JSONObject().put("role", role).put("content", contentArr))
    }
    val body = JSONObject()
        .put("model", config.model)
        .put("messages", claudeMessages)
        .put("max_tokens", 64_000) // 翻译场景固定 64k 输出上限
        .put("stream", false)
    // system 用数组格式
    if (systemText.isNotEmpty()) {
        val systemArr = JSONArray()
        systemArr.put(JSONObject().put("type", "text").put("text", systemText))
        body.put("system", systemArr)
    }
    // temperature 只在非思考模式时生效
    if (params.temperature != null && params.thinking != true) {
        body.put("temperature", params.temperature)
    }
    // thinking 参数(用户指定格式): {"thinking": {"type": "enabled/disabled"}}
    // 思考代价 effort: none/low/medium/high/xhigh/max, Claude 用 output_config.effort 控制强度
    val effort = params.reasoningEffort
    val thinkingOn = params.thinking != false
    if (!thinkingOn) {
        body.put("thinking", JSONObject().put("type", "disabled"))
    } else if (effort != null && !effort.equals("none", ignoreCase = true)) {
        body.put("thinking", JSONObject().put("type", "enabled"))
        val claudeEffort = when (effort.lowercase()) {
            "minimal" -> "low"      // Claude 不支持 minimal, 降级为 low
            "xhigh" -> "high"        // Claude 用 high 而非 xhigh
            "max" -> "max"
            else -> effort.lowercase() // low/medium/high
        }
        body.put("output_config", JSONObject().put("effort", claudeEffort))
    } else {
        // 思考开启但未指定代价: 用默认思考
        body.put("thinking", JSONObject().put("type", "enabled"))
    }
    val headers = mutableMapOf(
        "Content-Type" to "application/json",
        "x-api-key" to config.apiKey,
        "anthropic-version" to "2023-06-01",
    )
    headers.putAll(config.customHeaders)
    val json = httpJsonRequest(Http.HttpMethod.POST, url, headers, body.toString(), timeoutMs) ?: return null
    val contentArr = json.optJSONArray("content")
    if (contentArr == null || contentArr.length() == 0) return null
    val contentText = StringBuilder()
    val reasoningText = StringBuilder()
    for (i in 0 until contentArr.length()) {
        val part = contentArr.optJSONObject(i) ?: continue
        when (part.optString("type")) {
            "text" -> contentText.append(part.optString("text", ""))
            "thinking" -> reasoningText.append(part.optString("thinking", ""))
        }
    }
    val usage = json.optJSONObject("usage")
    val tokenUsage = if (usage != null) {
        val input = usage.optInt("input_tokens", 0)
        val output = usage.optInt("output_tokens", 0)
        TokenUsage(input, output, input + output)
    } else null
    return GenerationResult(
        contentText.toString(),
        reasoningText.toString().takeIf { it.isNotEmpty() },
        tokenUsage,
    )
}

// ============ Google Gemini 格式生成 ============
// 参考 rikkahub GoogleProvider.kt
// 请求: POST {baseUrl}/v1beta/models/{model}:generateContent
// Headers: x-goog-api-key
// Body: {contents:[{role:"user"|"model", parts:[{text:"..."}]}], systemInstruction?:{parts:[{text:"..."}]}, generationConfig:{temperature?, topP?, maxOutputTokens?, thinkingConfig?:{includeThoughts, thinkingBudget?}}, safetySettings:[{category, threshold:"OFF"}]}
// 响应: {candidates:[{content:{parts:[{text, thought?}]}}], usageMetadata:{promptTokenCount, candidatesTokenCount, thoughtsTokenCount?, totalTokenCount}}

suspend fun generateGoogle(
    config: ProviderConfig,
    messages: List<JSONObject>,
    params: GenerationParams,
    timeoutMs: Int,
): GenerationResult? {
    val base = config.baseUrl.trimEnd('/')
    val googleBase = if (base.endsWith("/v1beta", ignoreCase = true)) base else "$base/v1beta"
    val url = "$googleBase/models/${config.model}:generateContent"
    // systemInstruction: 提取 system 消息
    val systemText = messages.filter { it.optString("role") == "system" }
        .joinToString("\n\n") { it.optString("content", "") }
    val contents = JSONArray()
    for (msg in messages) {
        val role = msg.optString("role")
        if (role == "system") continue
        val content = msg.optString("content", "")
        // Google role: assistant -> model
        val geminiRole = if (role == "assistant") "model" else "user"
        contents.put(JSONObject()
            .put("role", geminiRole)
            .put("parts", JSONArray().put(JSONObject().put("text", content))))
    }
    val body = JSONObject().put("contents", contents)
    // systemInstruction
    if (systemText.isNotEmpty()) {
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
    }
    // generationConfig
    val generationConfig = JSONObject()
    params.temperature?.let { generationConfig.put("temperature", it) }
    // thinkingConfig: 参照 rikkahub GoogleProvider.kt
    // Gemini 3 系列: 用 thinkingLevel (minimal/low/medium/high)
    // Gemini 2.5 及更早: 用 thinkingBudget (数值, 0=关闭)
    // 字段名是 camelCase: includeThoughts, thinkingLevel, thinkingBudget
    val effort = params.reasoningEffort
    val thinkingOn = params.thinking != false
    if (thinkingOn && effort != null && !effort.equals("none", ignoreCase = true)) {
        val thinkingConfig = JSONObject().put("includeThoughts", true)
        val isGemini3 = config.model.contains(Regex("gemini.3", RegexOption.IGNORE_CASE))
        if (isGemini3) {
            val thinkingLevel = when (effort.lowercase()) {
                "minimal" -> "minimal"
                "low" -> "low"
                "medium" -> "medium"
                else -> "high"  // high/xhigh/max
            }
            thinkingConfig.put("thinkingLevel", thinkingLevel)
        } else {
            // Gemini 2.5: 用 thinkingBudget (参照 rikkahub ReasoningLevel.budgetTokens)
            val budgetTokens = when (effort.lowercase()) {
                "minimal" -> 0
                "low" -> 1_000
                "medium" -> 2_000
                "high" -> 8_000
                "xhigh" -> 16_000
                "max" -> 32_000
                else -> 2_000
            }
            thinkingConfig.put("thinkingBudget", budgetTokens)
        }
        generationConfig.put("thinkingConfig", thinkingConfig)
    }
    if (generationConfig.length() > 0) body.put("generationConfig", generationConfig)
    // safetySettings: 全部设为 OFF, 避免被安全过滤拦截
    val safetyArr = JSONArray()
    for (category in listOf(
        "HARM_CATEGORY_HARASSMENT",
        "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "HARM_CATEGORY_DANGEROUS_CONTENT",
        "HARM_CATEGORY_CIVIC_INTEGRITY"
    )) {
        safetyArr.put(JSONObject().put("category", category).put("threshold", "OFF"))
    }
    body.put("safetySettings", safetyArr)
    // 认证: 用 x-goog-api-key header (非 ?key= 查询参数)
    val headers = mutableMapOf(
        "Content-Type" to "application/json",
        "x-goog-api-key" to config.apiKey,
    )
    headers.putAll(config.customHeaders)
    val json = httpJsonRequest(Http.HttpMethod.POST, url, headers, body.toString(), timeoutMs) ?: return null
    val candidates = json.optJSONArray("candidates")
    if (candidates == null || candidates.length() == 0) return null
    val candidate = candidates.optJSONObject(0) ?: return null
    val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
    val contentText = StringBuilder()
    val reasoningText = StringBuilder()
    if (parts != null) {
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text", "")
            if (text.isEmpty()) continue
            // thought=true 表示是思考内容
            if (part.optBoolean("thought", false)) reasoningText.append(text)
            else contentText.append(text)
        }
    }
    val usageMeta = json.optJSONObject("usageMetadata")
    val tokenUsage = if (usageMeta != null) TokenUsage(
        usageMeta.optInt("promptTokenCount", 0),
        usageMeta.optInt("candidatesTokenCount", 0) + usageMeta.optInt("thoughtsTokenCount", 0),
        usageMeta.optInt("totalTokenCount", 0),
    ) else null
    return GenerationResult(
        contentText.toString(),
        reasoningText.toString().takeIf { it.isNotEmpty() },
        tokenUsage,
    )
}

// ============ 核心入口: generate() ============

suspend fun generate(
    config: ProviderConfig,
    messages: List<JSONObject>,
    params: GenerationParams = GenerationParams(),
    timeoutMs: Int = 60_000,
): GenerationResult? {
    val start = System.currentTimeMillis()
    return withTimeoutOrNull(timeoutMs.toLong()) {
        val result = when (config.type) {
            ProviderType.OPENAI -> generateOpenAI(config, messages, params, timeoutMs)
            ProviderType.CLAUDE -> generateClaude(config, messages, params, timeoutMs)
            ProviderType.GOOGLE -> generateGoogle(config, messages, params, timeoutMs)
        }
        result?.copy(durationMs = System.currentTimeMillis() - start)
    }
}

// ============ 获取模型列表: fetchModels() ============

suspend fun fetchModels(config: ProviderConfig): List<String>? {
    return when (config.type) {
        ProviderType.GOOGLE -> {
            val base = config.baseUrl.trimEnd('/')
            val googleBase = if (base.endsWith("/v1beta", ignoreCase = true)) base else "$base/v1beta"
            val url = "$googleBase/models?pageSize=100"
            val headers = mutableMapOf("x-goog-api-key" to config.apiKey)
            headers.putAll(config.customHeaders)
            val json = httpJsonRequest(Http.HttpMethod.GET, url, headers, null, 30_000) ?: return null
            val arr = json.optJSONArray("models") ?: return null
            (0 until arr.length()).mapNotNull {
                val obj = arr.optJSONObject(it) ?: return@mapNotNull null
                obj.optString("name", "").removePrefix("models/").takeIf { s -> s.isNotEmpty() }
            }
        }
        ProviderType.CLAUDE -> {
            val url = buildApiUrl(config.baseUrl, "/models")
            val headers = mutableMapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01",
            )
            headers.putAll(config.customHeaders)
            val json = httpJsonRequest(Http.HttpMethod.GET, url, headers, null, 30_000) ?: return null
            val arr = json.optJSONArray("data") ?: return null
            (0 until arr.length()).mapNotNull {
                val obj = arr.optJSONObject(it) ?: return@mapNotNull null
                obj.optString("id", "").takeIf { s -> s.isNotEmpty() }
            }
        }
        ProviderType.OPENAI -> {
            val url = buildApiUrl(config.baseUrl, "/models")
            val headers = mutableMapOf("Authorization" to "Bearer ${config.apiKey}")
            headers.putAll(config.customHeaders)
            val json = httpJsonRequest(Http.HttpMethod.GET, url, headers, null, 30_000) ?: return null
            val arr = json.optJSONArray("data") ?: return null
            (0 until arr.length()).mapNotNull {
                val obj = arr.optJSONObject(it) ?: return@mapNotNull null
                obj.optString("id", "").takeIf { s -> s.isNotEmpty() }
            }
        }
    }
}
