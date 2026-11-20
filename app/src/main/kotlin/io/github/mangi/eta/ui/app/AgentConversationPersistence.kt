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
        val content = Content(state = state, title = title, updatedAt = now)
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
        saved = saved + (id to Content(state = state, title = title, updatedAt = updatedAt, isPinned = isPinned))
    }

    suspend fun forgetConversation(id: String) = mutex.withLock {
        saved = saved - id
    }

    private fun project(snapshot: AgentConversationStore.Snapshot): Map<String, Content> =
        snapshot.conversationsById.mapValues { (id, state) ->
            Content(
                state = state,
                title = snapshot.titles[id].orEmpty(),
                updatedAt = snapshot.updatedAt[id] ?: 0L,
                isPinned = id in snapshot.pinnedIds,
            )
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
        /**
         * 未发送的输入草稿（3.4.50 审理 P3-14 / P3-16）。
         *
         * 它不进数据库：`conversations` 表没有草稿列，加列要动 Room 版本号 + 迁移脚本
         * （本批禁止改 data/db，也不许新增 DB/wire 字段），所以草稿由 AgentConversationStore
         * 单独落一份旁路 JSON。放进 Content 是为了让「哪些会话有草稿」跟着保存快照一起走：
         * 正文事务成功之后整份草稿一次写出，事务回滚就整份不写，不会出现「正文没落、草稿先落」。
         *
         * 故意不进 [sameMetadata]：草稿变化不许触发正文行重写（否则每 200ms 敲字都重写一遍
         * 会话元数据，正是 3.4.49 P2-1 想避免的整屏/整行重组）。
         */
        val input: String = "",
    ) {
        // 13 个实参全部具名：这条链上有两个 Boolean（isPinned 在第 3 位、isConversationLoaded
        // 在第 12 位），位置传参时看走眼就是把「置顶」写成「已载入」，而且编译器不会报错
        // （3.4.50 审理 P3-17）。
        constructor(
            state: AgentChatHomeUiState,
            title: String,
            updatedAt: Long,
            isPinned: Boolean = false,
        ) : this(
            title = title,
            updatedAt = updatedAt,
            isPinned = isPinned,
            // 落盘一律取原档：回退钳制只影响显示与本次发送，不许覆盖会话存档里的原档位
            // （3.4.49 审理 P1-2）。六条写回路径（逐字草稿、改档、挂图、排队、分支、发送/完成）
            // 最终都从这里出，收口一处即可，不必逐条堵。
            reasoningEffort = state.archivedReasoningEffort ?: state.reasoningEffort,
            appliedRuntimeRunIds = state.appliedRuntimeRunIds,
            roleplay = state.roleplay,
            roleplayMessages = if (state.roleplay == null) RoleplayMessageState() else state.roleplayMessages,
            modelId = state.modelId,
            messages = state.messages,
            history = state.history,
            journal = state.journal.ifEmpty { state.history },
            isConversationLoaded = state.isConversationLoaded,
            messageStartIndex = state.messageStartIndex,
            // 草稿跟着快照走，但不进正文事务（见 [input] 的注释）。
            input = state.input,
        )

        fun sameMetadata(other: Content): Boolean =
            title == other.title && updatedAt == other.updatedAt && isPinned == other.isPinned &&
                reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId
    }
}
