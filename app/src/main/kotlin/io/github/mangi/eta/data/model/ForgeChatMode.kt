package io.github.mangi.eta.data.model

/**
 * 顶栏 Assist / Forge 分段控件的模式。Assist 是现有 Eta Agent，Forge 直接由 opencode 驱动。
 */
internal enum class ForgeChatMode(val wireValue: String) {
    Assist("assist"),
    Forge("forge"),
    ;

    companion object {
        fun fromWire(value: String?): ForgeChatMode =
            entries.firstOrNull { it.wireValue == value } ?: Assist
    }
}

/** Forge 本期只做单会话：整个 App 只有这一个 Forge 对话，固定复用同一个 opencode session。 */
internal const val FORGE_CONVERSATION_ID = "forge-conversation"
