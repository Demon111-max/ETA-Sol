package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.ConversationBranchRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 只在事务提交后推进保存基线，不持有输入草稿、待发送图片等临时 UI 状态。 */
internal class AgentConversationPersistence(initial: AgentConversationStore.Snapshot? = null) {
    private val mutex = Mutex()
    // 未对齐的会话不进入基线，首次保存按新会话整段写入，重排 sort_index。
    private var saved = initial?.let { snapshot -> project(snapshot) - snapshot.unalignedConversationIds }.orEmpty()
    // 已提交但尚未进入 Main 状态的分支必须合入旧快照，直到保存队列观察到发布后的状态。
    private var unpublishedBranches = emptyMap<String, Content>()

    suspend fun save(context: Context, snapshot: AgentConversationStore.Snapshot) = mutex.withLock {
        val projected = project(snapshot)
        val current = unpublishedBranches + projected
        AgentConversationStore.saveChanges(context, snapshot.selectedConversationId, current, saved)
        saved = current
        unpublishedBranches = unpublishedBranches - projected.keys
    }

    suspend fun branch(
        context: Context,
        sourceId: String,
        targetMessageId: String,
        branchId: String,
        title: String,
        now: Long,
    ): AgentChatHomeUiState = mutex.withLock {
        val database = EtaDatabase.get(context)
        val state = database.withTransaction {
            ConversationBranchRepository.create(database, sourceId, targetMessageId, branchId, title, now)
            checkNotNull(AgentConversationStore.loadConversation(context, branchId))
        }
        val content = Content(state, title, now)
        saved = saved + (branchId to content)
        unpublishedBranches = unpublishedBranches + (branchId to content)
        state
    }

    suspend fun adoptConversation(
        id: String,
        state: AgentChatHomeUiState,
        title: String,
        updatedAt: Long,
        isPinned: Boolean = false,
    ) = mutex.withLock {
        saved = saved + (id to Content(state, title, updatedAt, isPinned))
    }

    suspend fun forgetConversation(id: String) = mutex.withLock {
        saved = saved - id
    }

    private fun project(snapshot: AgentConversationStore.Snapshot): Map<String, Content> =
        snapshot.conversationsById.mapValues { (id, state) ->
            Content(state, snapshot.titles[id].orEmpty(), snapshot.updatedAt[id] ?: 0L, id in snapshot.pinnedIds)
        }

    internal data class Content(
        val title: String,
        val updatedAt: Long,
        val isPinned: Boolean,
        val reasoningEffort: ReasoningEffort,
        val appliedRuntimeRunIds: List<String>,
        val roleplay: RoleplayBinding?,
        val roleplayMessages: RoleplayMessageState,
        val modelId: String?,
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
        val journal: List<AgentModelClient.ConversationMessage>,
        val isConversationLoaded: Boolean,
        val messageStartIndex: Int,
    ) {
        constructor(
            state: AgentChatHomeUiState,
            title: String,
            updatedAt: Long,
            isPinned: Boolean = false,
        ) : this(
            title, updatedAt, isPinned,
            // 落盘一律取原档：回退钳制只影响显示与本次发送，不许覆盖会话存档里的原档位
            // （3.4.49 审理 P1-2）。六条写回路径（逐字草稿、改档、挂图、排队、分支、发送/完成）
            // 最终都从这里出，收口一处即可，不必逐条堵。
            state.archivedReasoningEffort ?: state.reasoningEffort,
            state.appliedRuntimeRunIds,
            state.roleplay, if (state.roleplay == null) RoleplayMessageState() else state.roleplayMessages,
            state.modelId, state.messages, state.history, state.journal.ifEmpty { state.history },
            state.isConversationLoaded, state.messageStartIndex,
        )

        fun sameMetadata(other: Content): Boolean =
            title == other.title && updatedAt == other.updatedAt && isPinned == other.isPinned &&
                reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId
    }
}
