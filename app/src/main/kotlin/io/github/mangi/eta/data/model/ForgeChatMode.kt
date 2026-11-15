package io.github.mangi.eta.data.model

/**
 * 顶栏 Assist / Forge 分段控件的模式。Assist 是现有 Eta Agent，Forge 直接由 opencode 驱动。
 */
enum class ForgeChatMode(val wireValue: String) {
    Assist("assist"),
    Forge("forge"),
    ;

    companion object {
        fun fromWire(value: String?): ForgeChatMode =
            entries.firstOrNull { it.wireValue == value } ?: Assist
    }
}

/** 旧版单会话时期的固定条目 id，仅保留兼容；新条目用下面的会话 id 前缀。 */
internal const val FORGE_CONVERSATION_ID = "forge-conversation"

/** 侧边栏里一个 Forge 会话条目的 id：前缀 + opencode 会话 id，一个会话一条。 */
internal const val FORGE_SESSION_ID_PREFIX = "forge-session:"

internal fun forgeConversationId(sessionId: String): String = FORGE_SESSION_ID_PREFIX + sessionId

internal fun forgeSessionIdOf(conversationId: String): String? {
    if (!conversationId.startsWith(FORGE_SESSION_ID_PREFIX)) return null
    return conversationId.removePrefix(FORGE_SESSION_ID_PREFIX).ifEmpty { null }
}
