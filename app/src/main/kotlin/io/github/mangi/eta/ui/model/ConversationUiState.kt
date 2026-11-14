package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.model.FORGE_CONVERSATION_ID

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val runStatus: String? = null,
    val characterName: String? = null,
    val characterAvatarPath: String? = null,
    /** Forge 对话在列表里的小标记；Assist 对话为 false。 */
    val forge: Boolean = false,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}

/**
 * 会话列表里补一条 Forge 对话。Forge 本期只有单会话，条目是合成出来的，不进数据库。
 */
internal fun ConversationPaneUiState.withForgeConversation(
    forgeTitle: String,
    forgeOpen: Boolean,
): ConversationPaneUiState {
    val query = searchQuery.trim()
    val assistConversations = conversations.filterNot { it.forge }
    val matches = query.isEmpty() || forgeTitle.contains(query, ignoreCase = true)
    if (!matches) return copy(conversations = assistConversations)
    val entry = ConversationSummaryUi(
        id = FORGE_CONVERSATION_ID,
        title = forgeTitle,
        preview = forgeTitle,
        timeLabel = "",
        mode = ConversationModeUi.Chat,
        forge = true,
    )
    return copy(
        conversations = listOf(entry) + assistConversations,
        selectedConversationId = if (forgeOpen) FORGE_CONVERSATION_ID else selectedConversationId,
    )
}
