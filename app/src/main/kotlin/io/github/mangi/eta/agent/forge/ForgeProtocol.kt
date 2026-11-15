package io.github.mangi.eta.agent.forge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * opencode 会话里一条消息的角色。opencode 只有用户与助手两种，助手消息里会混着思考、工具与正文。
 */
internal enum class ForgeRole { User, Assistant }

/** opencode 工具的四种状态，对应 ToolPart.state.status。 */
internal enum class ForgeToolStatus { Pending, Running, Completed, Failed }

/**
 * opencode 消息里的一段内容。只保留界面需要的信息：正文、思考、工具调用，
 * 其余（step-start、snapshot、patch 等）解析成 [ForgePart.Other] 原样跳过。
 */
internal sealed interface ForgePart {
    val id: String
    val messageId: String

    /** 这一段是否已经结束；未结束的思考与正文按流式渲染。 */
    val ended: Boolean

    data class Text(
        override val id: String,
        override val messageId: String,
        val text: String,
        override val ended: Boolean,
    ) : ForgePart

    data class Reasoning(
        override val id: String,
        override val messageId: String,
        val text: String,
        override val ended: Boolean,
    ) : ForgePart

    data class Tool(
        override val id: String,
        override val messageId: String,
        val callId: String,
        val tool: String,
        val status: ForgeToolStatus,
        val title: String?,
        val input: String?,
        val output: String?,
        val error: String?,
    ) : ForgePart {
        override val ended: Boolean
            get() = status == ForgeToolStatus.Completed || status == ForgeToolStatus.Failed
    }

    data class Other(
        override val id: String,
        override val messageId: String,
        val type: String,
        override val ended: Boolean = true,
    ) : ForgePart
}

/** opencode 会话里的一条消息，parts 保持服务端返回的顺序。 */
internal data class ForgeChatMessage(
    val id: String,
    val role: ForgeRole,
    val parts: List<ForgePart>,
)

/** /event 上与本页面有关的事件。 */
internal sealed interface ForgeEvent {
    data class PartUpdated(val sessionId: String, val part: ForgePart) : ForgeEvent

    /**
     * 正文/思考的增量文本（message.part.delta）。opencode 流式期间只发这种事件，
     * part.updated 只在一段开始（text 为空）与结束（text 为全文）时发；
     * 不接 delta，流式期间界面上的思考与正文就一直是空的。
     */
    data class PartDelta(
        val sessionId: String,
        val messageId: String?,
        val partId: String,
        val delta: String,
    ) : ForgeEvent

    data class MessageUpdated(
        val sessionId: String,
        val messageId: String,
        val role: ForgeRole,
        val completed: Boolean,
    ) : ForgeEvent

    data class Idle(val sessionId: String) : ForgeEvent

    data class Failed(val sessionId: String, val message: String?) : ForgeEvent

    data class Ignored(val type: String) : ForgeEvent
}

/**
 * opencode HTTP 协议的 JSON 解析。所有字段都按 openapi（GET /doc）里的形状读取，
 * 缺失字段退化成空值，遇到不认识的 part 类型不报错、直接跳过。
 */
internal object ForgeProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseSessionId(element: JsonElement): String? =
        (element as? JsonObject)?.stringOrNull("id")

    fun parseSessionList(element: JsonElement): List<String> =
        (element as? JsonArray).orEmpty()
            .mapNotNull { parseSessionId(it) }

    fun parseMessages(element: JsonElement): List<ForgeChatMessage> =
        (element as? JsonArray).orEmpty()
            .mapNotNull { parseMessageEntry(it) }
            .filter { it.parts.isNotEmpty() }

    fun parseMessagesResponse(element: JsonElement): List<ForgeChatMessage> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val info = (entry as? JsonObject)?.get("info")?.jsonObject ?: return@mapNotNull null
            val parts = (entry["parts"] as? JsonArray).orEmpty().mapNotNull { parsePart(it) }
            ForgeChatMessage(
                id = info.stringOrNull("id") ?: parts.firstOrNull()?.messageId ?: return@mapNotNull null,
                role = info.role(),
                parts = parts,
            )
        }.filter { it.parts.isNotEmpty() }
    }

    private fun parseMessageEntry(element: JsonElement): ForgeChatMessage? {
        val obj = element as? JsonObject ?: return null
        val parts = (obj["parts"] as? JsonArray).orEmpty().mapNotNull { parsePart(it) }
        val id = obj.stringOrNull("id") ?: parts.firstOrNull()?.messageId ?: return null
        return ForgeChatMessage(id = id, role = obj.role(), parts = parts)
    }

    fun parsePart(element: JsonElement): ForgePart? {
        val obj = element as? JsonObject ?: return null
        val id = obj.stringOrNull("id") ?: return null
        val messageId = obj.stringOrNull("messageID") ?: return null
        val time = obj["time"] as? JsonObject
        val ended = time != null && time.stringOrNull("end") != null
        return when (obj.stringOrNull("type")) {
            "text" -> ForgePart.Text(
                id = id,
                messageId = messageId,
                text = obj.stringOrNull("text").orEmpty(),
                ended = ended,
            )

            "reasoning" -> ForgePart.Reasoning(
                id = id,
                messageId = messageId,
                text = obj.stringOrNull("text").orEmpty(),
                ended = ended,
            )

            "tool" -> parseToolPart(obj, id, messageId)

            else -> ForgePart.Other(
                id = id,
                messageId = messageId,
                type = obj.stringOrNull("type").orEmpty(),
            )
        }
    }

    private fun parseToolPart(obj: JsonObject, id: String, messageId: String): ForgePart.Tool {
        val state = obj["state"] as? JsonObject
        val status = when (state?.stringOrNull("status")) {
            "pending" -> ForgeToolStatus.Pending
            "running" -> ForgeToolStatus.Running
            "completed" -> ForgeToolStatus.Completed
            "error" -> ForgeToolStatus.Failed
            else -> ForgeToolStatus.Pending
        }
        return ForgePart.Tool(
            id = id,
            messageId = messageId,
            callId = obj.stringOrNull("callID").orEmpty(),
            tool = obj.stringOrNull("tool").orEmpty(),
            status = status,
            title = state?.stringOrNull("title"),
            input = state?.get("input")?.let(::compactJson),
            output = state?.get("output")?.let(::textOrNull),
            error = state?.get("error")?.let(::textOrNull),
        )
    }

    fun parseEvent(payload: String): ForgeEvent? {
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val type = obj.stringOrNull("type") ?: return null
        val properties = obj["properties"] as? JsonObject
        val sessionId = properties?.stringOrNull("sessionID")
        return when (type) {
            "message.part.updated" -> {
                val part = properties?.get("part")?.let(::parsePart)
                if (part != null && sessionId != null) {
                    ForgeEvent.PartUpdated(sessionId, part)
                } else {
                    ForgeEvent.Ignored(type)
                }
            }

            "message.part.delta" -> {
                val partId = properties?.stringOrNull("partID")
                val delta = properties?.stringOrNull("delta")
                if (sessionId != null && partId != null && delta != null &&
                    properties.stringOrNull("field") == "text"
                ) {
                    ForgeEvent.PartDelta(
                        sessionId = sessionId,
                        messageId = properties.stringOrNull("messageID"),
                        partId = partId,
                        delta = delta,
                    )
                } else {
                    ForgeEvent.Ignored(type)
                }
            }

            "message.updated" -> {
                val info = properties?.get("info")?.jsonObject
                val messageId = info?.stringOrNull("id")
                if (info != null && messageId != null && sessionId != null) {
                    ForgeEvent.MessageUpdated(
                        sessionId = sessionId,
                        messageId = messageId,
                        role = info.role(),
                        completed = info.stringOrNull("completion") != null ||
                            (info["time"] as? JsonObject)?.stringOrNull("completed") != null,
                    )
                } else {
                    ForgeEvent.Ignored(type)
                }
            }

            "session.idle" -> sessionId?.let { ForgeEvent.Idle(it) } ?: ForgeEvent.Ignored(type)

            "session.error" -> ForgeEvent.Failed(sessionId.orEmpty(), properties?.stringOrNull("message"))

            else -> ForgeEvent.Ignored(type)
        }
    }

    private fun JsonObject.role(): ForgeRole =
        if (stringOrNull("role") == "user") ForgeRole.User else ForgeRole.Assistant

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun textOrNull(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.stringOrNull("message") ?: compactJson(element)
        else -> compactJson(element)
    }

    private fun compactJson(element: JsonElement): String = element.toString()
}
