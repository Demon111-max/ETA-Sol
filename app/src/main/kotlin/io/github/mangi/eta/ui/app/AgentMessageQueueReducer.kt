package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi

/**
 * 会话内排队发送（Queue）与插话发送（Interrupt）的纯状态转换器。
 *
 * - 排队发送追加到队尾，等待当前回合自然结束后按 FIFO 顺序自动发出。
 * - 插话发送（[QueuedMessageUi.immediateInterrupt]）置入队首并请求中止当前运行，
 *   待当前运行收尾落盘后立即优先发出，避免取消与新运行之间出现历史写入竞态。
 * - 用户主动点击停止按钮时保留队列不自动发出，防止想停下时后续排队指令继续跑起来。
 */
internal object AgentMessageQueueReducer {

    fun enqueue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val nextQueue = if (item.immediateInterrupt) {
            listOf(item) + state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        } else {
            state.queuedMessages + item
        }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = nextQueue,
            // 输入栏不再自己 clearText（发送可能被拒，3.4.49 审理 P2-2），清空由版本号驱动。
            inputSyncVersion = state.inputSyncVersion + 1,
        )
    }

    /**
     * 将队列中的指定消息提升为立即插话。
     *
     * - 若当前仍在流式运行（非压缩）：将其移至队首并标记 [QueuedMessageUi.immediateInterrupt]，等待当前运行取消后自动发出。
     * - 若当前已空闲：直接从队列弹出并返回，由调用方立即发起新运行。
     */
    fun promoteToInterrupt(
        state: AgentChatUiState,
        id: String,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state to null
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return if (state.isStreaming && !state.isCompacting) {
            // 用户亲手把这条重新插话：清掉 blocked，让泵愿意再试一次（拦不拦由发送门当场判）。
            val promoted = target.copy(immediateInterrupt = true, blocked = false)
            state.copy(queuedMessages = listOf(promoted) + rest) to promoted
        } else {
            state.copy(queuedMessages = rest) to target.copy(immediateInterrupt = false, blocked = false)
        }
    }

    /**
     * 将排队中的消息撤回输入框继续编辑；若输入框已有未发送草稿则保留并合并，不覆盖用户正在输入的内容。
     */
    fun recallToDraft(
        state: AgentChatUiState,
        currentInputText: String,
        id: String,
    ): AgentChatUiState? {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return null
        val rest = state.queuedMessages.filterNot { it.id == id }
        val mergedInput = when {
            currentInputText.isBlank() -> target.content
            target.content.isBlank() -> currentInputText
            else -> currentInputText.trimEnd() + "\n" + target.content
        }
        val mergedImages = (state.pendingImages + target.pendingImages).distinctBy { it.id }
        val mergedFiles = (state.pendingFileReferences + target.pendingFileReferences).distinctBy { it.id }
        return state.copy(
            input = mergedInput,
            pendingImages = mergedImages,
            pendingFileReferences = mergedFiles,
            queuedMessages = rest,
            inputSyncVersion = state.inputSyncVersion + 1,
            focusInputOnSync = true,
        )
    }

    /**
     * 出队后又被发送门拦下（模型不支持图片、文件引用需要终端）时把消息放回队列。
     *
     * 出队那一步已经落盘（[pollNextToSendAfterRun] / [promoteToInterrupt] 都先写状态再发），
     * 拦下后如果只是 return，这条消息在界面上就彻底消失了（3.4.49 审理 P2-3）。
     *
     * 放队尾而不是队首，并且默认标 [QueuedMessageUi.blocked]（3.4.50 审理 P2-2）：
     * 泵只在队首取一条，[dispatchQueuedMessage] 又只被「运行收尾」和「空闲插话」两处触发，
     * 没有定时器也没有观察者——被拦下的消息放回队首就永久卡在那儿，后面所有排队消息
     * 一个都发不出去；拦截条件解除后也不会自己重试。放队尾让别的消息先走，blocked 让
     * 泵跳过它，等用户处理完（换模型/开终端/撤回）再发。
     *
     * [blocked] 传 false 用于「只是时序不对」的重放（例如出队时发现会话又在跑），
     * 那种情况运行结束后自然就该重试，不该要求用户介入。
     */
    fun requeue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
        blocked: Boolean = true,
    ): AgentChatUiState {
        if (state.queuedMessages.any { it.id == item.id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages + item.copy(
                immediateInterrupt = false,
                blocked = blocked,
            ),
        )
    }

    fun remove(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.filterNot { it.id == id },
        )
    }

    fun clearInterruptFlags(state: AgentChatUiState): AgentChatUiState {
        if (state.queuedMessages.none { it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { it.copy(immediateInterrupt = false) },
        )
    }

    /**
     * 当前运行收尾后决定是否自动弹出下一条排队消息：
     * - 队首为 [QueuedMessageUi.immediateInterrupt] 时，无论本次是否因取消结束，都立即弹出执行。
     * - 用户主动点击停止按钮（[stoppedByUser]）时，保留剩余队列，不自动发出。
     * - [QueuedMessageUi.blocked] 的消息一律跳过（它被发送门拦着，发也发不出去）。
     * - 其余情况（正常结束）弹出第一条可发的消息自动发出。
     */
    fun pollNextToSendAfterRun(
        state: AgentChatUiState,
        stoppedByUser: Boolean,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        // 跳过被发送门拦下的消息：它们发不出去，但不该占着泵口把后面能发的消息一起堵死
        // （3.4.50 审理 P2-2）。整队都被拦下时原样返回，等用户处理。
        val first = state.queuedMessages.firstOrNull { !it.blocked } ?: return state to null
        if (!first.immediateInterrupt && stoppedByUser) {
            return clearInterruptFlags(state) to null
        }
        // first 可能不在队首（前面有 blocked 的），所以按 id 移除而不是 drop(1)。
        val remaining = state.queuedMessages.filterNot { it.id == first.id }
        return state.copy(queuedMessages = remaining) to first.copy(immediateInterrupt = false)
    }
}
