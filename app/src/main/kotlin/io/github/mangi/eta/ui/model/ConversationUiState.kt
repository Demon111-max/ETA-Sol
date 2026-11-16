package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.forge.ForgeSessionSummary
import io.github.mangi.eta.agent.forge.ForgeWorkspaces
import io.github.mangi.eta.data.model.forgeConversationId

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    /** Forge 会话列表没拉到时的说明（server 没在跑等）：面板在列表末尾摆一行，别让 Forge 条目无声消失。 */
    val forgeListHint: String? = null,
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
    /** Forge 会话的 opencode 目录（环境内路径），侧边栏按工作区分组用；Assist 对话为 null。 */
    val forgeDirectory: String? = null,
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
    busySessionIds: Set<String> = emptySet(),
    forgeListHint: String? = null,
): ConversationPaneUiState {
    val query = searchQuery.trim()
    val assistConversations = conversations.filterNot { it.forge }
    val forgeEntries = sessions
        .filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
        .map { session ->
            // 正在跑的会话顶到列表前部并显示运行圆点（与 Assist 的活跃会话同款处理），
            // 更新时间按当前时刻算，免得它被判进旧日期分组、把「今天」组从中间截断。
            // 运行集合来自服务端状态对齐：后台还在跑的 Forge 会话不止打开着的那一条。
            val isBusy = session.id in busySessionIds
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
                // 工作区改过名时旧会话的 directory 仍记着旧挂载名：经别名表解析到当前名字，
                // 否则侧边栏按 directory 精确相等分组会把它们全丢进「其它会话」。
                forgeDirectory = session.directory
                    .ifBlank { null }
                    ?.let { ForgeWorkspaces.canonicalDirectory(it) },
            )
        }
    val pinned = assistConversations.filter { it.isPinned }
    val rest = (assistConversations.filterNot { it.isPinned } + forgeEntries)
        .sortedByDescending { it.updatedAtMillis }
    return copy(
        conversations = pinned + rest,
        // Forge 打开着且停在空白页（没有活动会话）时不许回落到 Assist 的选中项，
        // 否则抽屉里会莫名其妙高亮着一条 Assist 会话。
        selectedConversationId = if (forgeOpen) {
            activeSessionId?.let { forgeConversationId(it) }
        } else {
            selectedConversationId
        },
        forgeListHint = if (sessions.isEmpty()) forgeListHint else null,
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
