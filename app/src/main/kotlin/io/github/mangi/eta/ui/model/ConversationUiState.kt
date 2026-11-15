package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.forge.ForgeSessionSummary
import io.github.mangi.eta.data.model.forgeConversationId

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
 * 会话列表里补上 Forge 的真实会话（来自 opencode 的会话列表，不进 Eta 数据库，一个会话一条）：
 * 与 Assist 未置顶会话按更新时间混排，置顶的 Assist 会话仍排在最前；搜索时按标题过滤。
 */
internal fun ConversationPaneUiState.withForgeConversations(
    sessions: List<ForgeSessionSummary>,
    forgeOpen: Boolean,
    activeSessionId: String?,
    untitledTitle: String,
    busySessionId: String? = null,
): ConversationPaneUiState {
    val query = searchQuery.trim()
    val assistConversations = conversations.filterNot { it.forge }
    val forgeEntries = sessions
        .filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
        .map { session ->
            // 正在跑的会话顶到列表前部并显示运行圆点（与 Assist 的活跃会话同款处理），
            // 更新时间按当前时刻算，免得它被判进旧日期分组、把「今天」组从中间截断。
            val isBusy = session.id == busySessionId
            val effectiveUpdated = if (isBusy) {
                maxOf(session.updatedAtMillis, System.currentTimeMillis())
            } else {
                session.updatedAtMillis
            }
            val label = session.title.ifBlank { untitledTitle }
            ConversationSummaryUi(
                id = forgeConversationId(session.id),
                title = label,
                preview = label,
                timeLabel = forgeTimeLabel(effectiveUpdated),
                updatedAtMillis = effectiveUpdated,
                mode = ConversationModeUi.Chat,
                isActiveRun = isBusy,
                forge = true,
            )
        }
    val pinned = assistConversations.filter { it.isPinned }
    val rest = (assistConversations.filterNot { it.isPinned } + forgeEntries)
        .sortedByDescending { it.updatedAtMillis }
    return copy(
        conversations = pinned + rest,
        selectedConversationId = if (forgeOpen) {
            activeSessionId?.let { forgeConversationId(it) } ?: selectedConversationId
        } else {
            selectedConversationId
        },
    )
}

/** Forge 条目的时间小字：今天只显示时刻，更早显示月-日。 */
private fun forgeTimeLabel(updatedAtMillis: Long): String {
    if (updatedAtMillis <= 0L) return ""
    val startOfToday = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val pattern = if (updatedAtMillis >= startOfToday) "HH:mm" else "MM-dd"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(updatedAtMillis))
}
