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

    /** 已有会话就沿用最近更新的一个，没有就新建一个。 */
    suspend fun ensureSession(): Result<String> {
        val existing = runCatching { latestSession() }
        existing.getOrNull()?.let { return Result.success(it) }
        if (existing.isFailure && (existing.exceptionOrNull() as? IOException) != null) {
            return Result.failure(existing.exceptionOrNull()!!)
        }
        return createSession()
    }

    private suspend fun latestSession(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/session").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body.string().orEmpty()
            ForgeProtocol.parseLatestSessionId(Json.parseToJsonElement(body))
        }
    }

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
        runCatching {
            val payload = buildJsonObject {
                putJsonArray("answers") {
                    answers.forEach { group ->
                        addJsonArray { group.forEach { add(it) } }
                    }
                }
            }
            val request = Request.Builder()
                .url(questionUrl(pending, "reply"))
                .post(payload.toString().toRequestBody(JsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.close()
                Unit
            }
        }
    }

    /** 拒绝 question（用户点跳过）：模型会收到拒绝结果、自己决定下一步。 */
    suspend fun rejectQuestion(pending: ForgeQuestionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(questionUrl(pending, "reject"))
                    .post("{}".toRequestBody(JsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.close()
                    Unit
                }
            }
        }

    private fun questionUrl(pending: ForgeQuestionRequest, action: String): String =
        if (pending.v2) {
            "$baseUrl/api/session/${pending.sessionId}/question/${pending.id}/$action"
        } else {
            "$baseUrl/question/${pending.id}/$action"
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

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        providerId: String,
        modelId: String,
        variant: String? = null,
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
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
            }
            val request = Request.Builder()
                .url("$baseUrl/session/$sessionId/message")
                .post(payload.toString().toRequestBody(JsonMediaType))
                .build()
            val call = client.newCall(request)
            // 发送的 POST 要等整轮跑完才返回：协程取消时必须连它一起掐断，
            // 否则点停止后请求还挂着，会话也被 busy 卡住再也发不出去。
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
            while (currentCoroutineContext().isActive) {
                val line = try {
                    source.readUtf8Line()
                } catch (failure: IOException) {
                    if (currentCoroutineContext().isActive) throw failure else break
                } ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val event = ForgeProtocol.parseEvent(payload) ?: continue
                emit(event)
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}

/** 本客户端的 HTTP 失败都以 "HTTP <状态码>" 开头，据此判断错误种类（如 404 = 会话已不存在）。 */
internal fun Throwable?.hasHttpCode(code: Int): Boolean =
    (this as? IOException)?.message?.startsWith("HTTP $code") == true
