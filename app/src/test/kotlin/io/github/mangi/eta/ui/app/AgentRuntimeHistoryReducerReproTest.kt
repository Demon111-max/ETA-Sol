package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import org.junit.Test

/**
 * 最小复现：ui\app\AgentRuntimeHistoryReducer.kt:35-36
 *
 *     val users = state.history.indices.filter { state.history[it].role == "user" }
 *     val laterHistory = if (extra > 0) state.history.drop(users.takeLast(extra).first()) else emptyList()
 *
 * `extra` 由 `compactedUserTurns` 计数撑起来，而 `users` 只统计真实的 role=="user" 条目。
 * 上下文压缩后 history 里只剩摘要条目（compactedUserTurns = N，没有 user 条目），
 * 此时 `extra > 0 && users.isEmpty()` 成立，`takeLast(0).first()` 必然抛 NoSuchElementException。
 *
 * 调用点 AgentAppState.kt:2501 与 AgentPendingResultRecovery.kt:34 均无 try/catch。
 */
class AgentRuntimeHistoryReducerReproTest {

    @Test
    fun compactedHistoryWithoutUserEntryDoesNotCrash() {
        val compactedHistory = listOf(
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "（早前 3 轮对话的摘要）",
                contextSummary = true,
                compactedUserTurns = 3,
            ),
        )
        val state = AgentChatUiState(
            messages = emptyList(),
            history = compactedHistory,
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        val snapshot = AgentContextSnapshot(
            operationId = "run-1",
            messages = compactedHistory,
            coveredUserTurns = 0,
            consumedUserTurns = 0,
        )

        // 期望：快照与 transcript 对不上时降级处理，不应抛异常。
        AgentRuntimeHistoryReducer.apply(
            state = state,
            runId = "run-1",
            additions = emptyList(),
            snapshot = snapshot,
        )
    }
}
