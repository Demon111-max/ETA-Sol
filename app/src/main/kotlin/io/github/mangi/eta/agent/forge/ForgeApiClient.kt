package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * opencode server 的 HTTP 客户端。只覆盖 Forge 用到的端点：
 * /session、/session/{id}/message、/session/{id}/abort 与 /event。
 *
 * 事件流单独用一个不设读取超时的客户端，避免一轮长时间的思考在 60 秒后被误判断线。
 */
internal class ForgeApiClient(
    private val baseUrl: String = ForgeServerManager.BASE_URL,
    private val client: OkHttpClient = AgentHttpClient.client,
) {

    private val eventClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // 3.4.43（B4）：这里原本有 ensureSession(directory) ——「有会话就沿用本目录最近更新的那条，
    // 没有才新建」。它是「新会话其实没建新会话」的根因：newConversation() 只是把 sessionId 置空，
    // 首条消息发送时又按目录把上一个会话捡回来续聊（旧历史全量进上下文，所以还特别慢）。
    // 现在整条「捡已有会话」的路径已经删掉：
    //   · 显式新建的对话（空白页）→ 首条消息一律 createSession() 新建；
    //   · 恢复某条会话 → 只允许按已知的具体 sessionId 走 openSession(id)（侧边栏点开）。
    // 谁都不许再按目录/全局「最近一条」去挑已有会话。

    /** 拉全部会话给侧边栏列表用（标题、更新时间都在里面）。 */
    suspend fun listSessions(): Result<List<ForgeSessionSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/session").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string().orEmpty()
                ForgeProtocol.parseSessionSummaries(Json.parseToJsonElement(body))
            }
        }
    }

    /** 查会话当前是否还在跑：SSE 断线重连后用它对齐 busy，光靠事件会漏掉断线期间的 idle。 */
    suspend fun sessionBusy(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/session/status").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string().orEmpty()
                ForgeProtocol.parseSessionBusy(Json.parseToJsonElement(body), sessionId)
            }
        }
    }

    /** 全部会话的运行状态（会话ID → 是否在跑）：侧边栏刷新时给每个 Forge 条目对齐运行标记，不依赖哪个面板开着。 */
    suspend fun sessionStatuses(): Result<Map<String, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/session/status").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string().orEmpty()
                ForgeProtocol.parseSessionStatuses(Json.parseToJsonElement(body))
            }
        }
    }

    /** 新建一个 opencode 会话（Forge 新建对话用）。 */
    suspend fun createSession(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/session")
                .post("{}".toRequestBody(JsonMediaType))
                .build()
            val call = client.newCall(request)
            // 与 events() 同一套：协程被取消（如用户点停止）时把底层 HTTP 一起掐断，
            // 否则 execute() 会占着线程干等到读超时，上层的 busy 也下不来。
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string().orEmpty()
                ForgeProtocol.parseSessionId(Json.parseToJsonElement(body))
                    ?: throw IOException("session id missing")
            }
        }
    }

    /** 改会话标题（侧边栏 Forge 条目重命名走它）。 */
    suspend fun renameSession(sessionId: String, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject { put("title", title) }
                val request = Request.Builder()
                    .url("$baseUrl/session/$sessionId")
                    .patch(payload.toString().toRequestBody(JsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.close()
                    Unit
                }
            }
        }

    /** 删会话（侧边栏 Forge 条目删除走它）。 */
    suspend fun deleteSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/session/$sessionId").delete().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.close()
                Unit
            }
        }
    }

    /** 当前全部挂起中的 question 请求（跨会话，调用方按会话过滤）。 */
    suspend fun listPendingQuestions(): Result<List<ForgeQuestionRequest>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("$baseUrl/question").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body.string().orEmpty()
                    ForgeProtocol.parsePendingQuestions(Json.parseToJsonElement(body))
                }
            }
        }

    /** 回答 question：answers 与 questions 一一对应，每项是选中的选项名（或自定义答案文本）。 */
    suspend fun replyQuestion(
        pending: ForgeQuestionRequest,
        answers: List<List<String>>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("answers") {
                answers.forEach { group ->
                    addJsonArray { group.forEach { add(it) } }
                }
            }
        }
        postQuestionAction(pending, "reply", payload.toString())
    }

    /** 拒绝 question（用户点跳过）：模型会收到拒绝结果、自己决定下一步。 */
    suspend fun rejectQuestion(pending: ForgeQuestionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            postQuestionAction(pending, "reject", "{}")
        }

    /**
     * question 的 reply/reject 有 v1（/question/...）与 v2（/api/session/...）两套端点。
     * 经列表恢复的请求 v2 标记不可靠（解析时拿不到），首选端点 404 时自动回退另一套重试一次，
     * 免得提问卡还在、答案却永远发不出去、整轮挂死。
     */
    private fun postQuestionAction(
        pending: ForgeQuestionRequest,
        action: String,
        body: String,
    ): Result<Unit> = runCatching {
        fun attempt(v2: Boolean) {
            val request = Request.Builder()
                .url(questionUrl(pending.sessionId, pending.id, action, v2))
                .post(body.toRequestBody(JsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.close()
            }
        }
        try {
            attempt(pending.v2)
        } catch (failure: IOException) {
            if (failure.hasHttpCode(404)) attempt(!pending.v2) else throw failure
        }
        Unit
    }

    private fun questionUrl(sessionId: String, requestId: String, action: String, v2: Boolean): String =
        if (v2) {
            "$baseUrl/api/session/$sessionId/question/$requestId/$action"
        } else {
            "$baseUrl/question/$requestId/$action"
        }

    /** 拉全量消息：首次进入与 SSE 断线对齐都用它。 */
    suspend fun loadMessages(sessionId: String): Result<List<ForgeChatMessage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/session/$sessionId/message")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body.string().orEmpty()
                    ForgeProtocol.parseMessagesResponse(Json.parseToJsonElement(body))
                }
            }
        }

    /**
     * 发送一条消息。走异步端点 /prompt_async（官方文档：body 与 /message 相同、立即返回 204）：
     * 同步端点要等整轮跑完才返回，长轮次必然撞上共享客户端的读超时、误报「发送失败：timeout」，
     * 其实服务端还在跑。发送成功只代表已提交，本轮的生灭由 SSE 的 Idle/Failed 事件驱动。
     * attachments 是已复制进工作区的附件，以 file part 随消息发出（url 是 Linux 侧 file:// 路径）。
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        providerId: String,
        modelId: String,
        variant: String? = null,
        attachments: List<ForgeOutgoingAttachment> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                putJsonObject("model") {
                    put("providerID", providerId)
                    put("modelID", modelId)
                }
                // 思考档位：opencode 按 variant 名套用配置里该模型的 variants 定义。
                if (variant != null) put("variant", variant)
                putJsonArray("parts") {
                    if (text.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    }
                    attachments.forEach { attachment ->
                        add(
                            buildJsonObject {
                                put("type", "file")
                                put("mime", attachment.mime)
                                put("filename", attachment.filename)
                                put("url", "file://${attachment.linuxPath}")
                            },
                        )
                    }
                }
            }
            val request = Request.Builder()
                .url("$baseUrl/session/$sessionId/prompt_async")
                .post(payload.toString().toRequestBody(JsonMediaType))
                .build()
            val call = client.newCall(request)
            // 异步端点立即返回，取消钩子保留：协程被取消（如用户点停止）时不留悬挂请求。
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body.string().orEmpty().let(::serverErrorDetail)
                    throw IOException("HTTP ${response.code} $detail".trim())
                }
                response.body.close()
                Unit
            }
        }
    }

    /** opencode 的错误体是 {"name":…,"data":{"message":…}}，只取那句 message，别整段 JSON 甩给用户。 */
    private fun serverErrorDetail(body: String): String {
        val message = runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject
            ((root?.get("data") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return message ?: body.take(200)
    }

    suspend fun abort(sessionId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/session/$sessionId/abort")
                    .post("{}".toRequestBody(JsonMediaType))
                    .build()
                client.newCall(request).execute().use { response -> response.body.close() }
            }
        }
    }

    /**
     * 订阅 /event。服务端按 SSE 逐行下发，这里只关心 data 行；
     * 连接断开时把异常抛给上层，由上层重新拉全量消息对齐。
     *
     * SSE 允许多行 data（收到时按换行拼接成一个事件体），也允许 `event:`/`id:`/`retry:` 字段与
     * `:` 注释行（心跳）。以前只认「单行 data 且立刻当整条事件」，多行事件会被拆成几段各自解析失败、
     * 整条丢掉；现在攒着拼、能解析就派发，空行是事件边界，攒不住就丢掉，绝不无限吃内存。
     */
    fun events(): Flow<ForgeEvent> = flow {
        val request = Request.Builder()
            .url("$baseUrl/event")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = eventClient.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body.source() ?: throw IOException("empty event stream")
            val pending = StringBuilder()
            while (currentCoroutineContext().isActive) {
                val line = try {
                    source.readUtf8Line()
                } catch (failure: IOException) {
                    if (currentCoroutineContext().isActive) throw failure else break
                } ?: break
                when {
                    // 空行 = 一个事件的边界：把攒下的整段交出去，解析不出来就丢。
                    line.isEmpty() -> {
                        val payload = pending.toString().trim()
                        pending.setLength(0)
                        if (payload.isNotEmpty()) {
                            ForgeProtocol.parseEvent(payload)?.let { emit(it) }
                        }
                    }

                    // ":" 开头是注释/心跳，没有载荷。
                    line.startsWith(":") -> Unit

                    line.startsWith("data:") -> {
                        if (pending.isNotEmpty()) pending.append('\n')
                        pending.append(line.removePrefix("data:").trim())
                        // 有些实现不在事件末尾补空行，单行即完整 JSON 时立刻派发；
                        // parseEvent 对不完整 JSON 返回 null，于是继续攒下一行。
                        val payload = pending.toString()
                        val event = ForgeProtocol.parseEvent(payload)
                        if (event != null) {
                            pending.setLength(0)
                            emit(event)
                        } else if (pending.length > MAX_EVENT_PAYLOAD_CHARS) {
                            pending.setLength(0)
                        }
                    }

                    // event:/id:/retry: 等字段：事件类型与载荷都在 data 的 JSON 里，忽略。
                    else -> Unit
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        /** 单条事件体的上限：多行 data 攒超过这个长度就认定是脏数据，直接丢掉，避免无界增长。 */
        const val MAX_EVENT_PAYLOAD_CHARS = 4 * 1024 * 1024
    }
}

/** 本客户端的 HTTP 失败都以 "HTTP <状态码>" 开头，据此判断错误种类（如 404 = 会话已不存在）。 */
internal fun Throwable?.hasHttpCode(code: Int): Boolean =
    (this as? IOException)?.message?.startsWith("HTTP $code") == true

/** 一条要随消息发出的附件：已复制进当前工作区，[linuxPath] 是环境内的绝对路径。 */
internal data class ForgeOutgoingAttachment(
    val mime: String,
    val filename: String,
    val linuxPath: String,
)
