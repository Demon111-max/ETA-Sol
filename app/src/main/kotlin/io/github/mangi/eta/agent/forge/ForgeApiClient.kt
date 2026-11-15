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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
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

    /** 已有会话就沿用第一个，没有就新建一个；Forge 本期只有单会话。 */
    suspend fun ensureSession(): Result<String> {
        val existing = runCatching { listSessions() }
        existing.getOrNull()?.firstOrNull()?.let { return Result.success(it) }
        if (existing.isFailure && (existing.exceptionOrNull() as? IOException) != null) {
            return Result.failure(existing.exceptionOrNull()!!)
        }
        return createSession()
    }

    private suspend fun listSessions(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/session").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body.string().orEmpty()
            ForgeProtocol.parseSessionList(Json.parseToJsonElement(body))
        }
    }

    private suspend fun createSession(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/session")
                .post("{}".toRequestBody(JsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body.string().orEmpty()
                ForgeProtocol.parseSessionId(Json.parseToJsonElement(body))
                    ?: throw IOException("session id missing")
            }
        }
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body.string().orEmpty().take(400)
                    throw IOException("HTTP ${response.code} $detail".trim())
                }
                response.body.close()
                Unit
            }
        }
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
        eventClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body.source() ?: throw IOException("empty event stream")
            while (currentCoroutineContext().isActive) {
                val line = source.readUtf8Line() ?: break
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
