package io.github.mangi.eta.ui.screens.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.forge.ForgeChatMessage
import io.github.mangi.eta.agent.forge.ForgePart
import io.github.mangi.eta.agent.forge.ForgeRole
import io.github.mangi.eta.agent.forge.ForgeToolStatus
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.components.AgentChatInputBar
import io.github.mangi.eta.ui.components.AgentWorkProcess
import io.github.mangi.eta.ui.components.ChatMessageItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Forge 对话：opencode 的输出按 parts 原样铺在 Assist 同款时间线上，
 * 思考块、工具行、正文都复用现有组件，不经过 Eta 的 AgentLoop。
 */
@Composable
internal fun ForgeChatPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ForgeChatState(context.applicationContext, scope) }
    LaunchedEffect(Unit) { state.start() }
    DisposableEffect(Unit) { onDispose { state.shutdown() } }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (val startup = state.startup) {
                ForgeStartup.Connecting -> ForgeCaption(
                    text = stringResource(R.string.forge_server_starting),
                )

                is ForgeStartup.Failed -> ListEmptyState(
                    title = stringResource(R.string.forge_server_failed_title),
                    summary = startup.message,
                    icon = Icons.Rounded.ErrorOutline,
                    action = {
                        ForgeGateAction(
                            text = stringResource(R.string.forge_retry),
                            onClick = { state.retry() },
                        )
                    },
                )

                ForgeStartup.Ready -> ForgeTranscript(state = state)
            }
        }
        if (state.startup is ForgeStartup.Ready) {
            state.notice?.let { notice ->
                ForgeCaption(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            ForgeComposer(
                isStreaming = state.busy,
                onSend = { text -> state.send(text) },
                onStop = { state.stop() },
            )
        }
    }
}

@Composable
private fun ForgeTranscript(state: ForgeChatState) {
    val messages = state.messages
    if (messages.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForgeCaption(text = stringResource(R.string.forge_empty_hint))
        }
        return
    }
    val listState = rememberLazyListState()
    val lastSignature = messages.lastOrNull()?.let { message ->
        "${message.id}:${message.parts.size}:${message.parts.lastOrNull()?.id}"
    }
    LaunchedEffect(lastSignature) {
        // 流式追加时贴住底部；用无动画的 scrollToItem，不引入新的滚动效果。
        listState.scrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            ForgeMessageRow(
                message = message,
                active = state.busy && message.id == messages.lastOrNull()?.id,
            )
        }
    }
}

@Composable
private fun ForgeMessageRow(message: ForgeChatMessage, active: Boolean) {
    when (message.role) {
        ForgeRole.User -> ChatMessageItem(
            message = UserMessageUi(
                id = message.id,
                content = message.parts
                    .filterIsInstance<ForgePart.Text>()
                    .joinToString("\n") { it.text },
            ),
            onSuggestionClick = {},
            onRunTraceClick = {},
            onOpenBrowser = {},
            showBrowserShortcut = false,
            showCopyAction = false,
            modifier = Modifier.fillMaxWidth(),
        )

        ForgeRole.Assistant -> {
            val segments = remember(message.parts) { message.toSegments() }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                segments.forEach { segment ->
                    when (segment) {
                        is ForgeSegment.Work -> AgentWorkProcess(
                            id = segment.key,
                            messages = segment.items,
                            onOpenBrowser = {},
                            currentBrowserMessageId = null,
                            retainedStreamingStates = emptyMap<String, StreamingMarkdownState>(),
                            active = active,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        is ForgeSegment.Body -> ChatMessageItem(
                            message = AgentMessageUi(
                                id = segment.id,
                                content = segment.text,
                                isStreaming = segment.streaming,
                            ),
                            onSuggestionClick = {},
                            onRunTraceClick = {},
                            onOpenBrowser = {},
                            showBrowserShortcut = false,
                            showCopyAction = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgeComposer(
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        // 与 Assist 输入区同一套底部渐隐，避免实心矩形把消息流截断。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.26f to surface.copy(alpha = 0.86f),
                        0.55f to surface.copy(alpha = 0.98f),
                        1.0f to surface,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
        ) {
            AgentChatInputBar(
                input = "",
                isCompacting = false,
                contextUsage = AgentContextUsageUi(contextTokens = null, contextWindow = null),
                showContextUsage = false,
                isStreaming = isStreaming,
                reasoningEffort = ReasoningEffort.OFF,
                availableReasoningEfforts = emptyList(),
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                isEditingMessage = false,
                editHasLaterTurns = false,
                preserveFollowingMessages = false,
                onReasoningEffortChange = {},
                onCompactContext = {},
                canCompactContext = false,
                onSubmit = onSend,
                onStop = onStop,
                onAttachImage = {},
                onRemoveImage = {},
                onAttachFiles = {},
                onAttachFolder = {},
                onAttachFilePath = {},
                onRemoveFileReference = {},
                onCancelMessageEdit = {},
                showAttach = false,
                hintText = stringResource(R.string.forge_input_hint),
                workingText = stringResource(R.string.forge_working),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ForgeCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier,
    )
}

private sealed interface ForgeSegment {
    data class Work(val key: String, val items: List<AgentChatMessageUi>) : ForgeSegment

    data class Body(val id: String, val text: String, val streaming: Boolean) : ForgeSegment
}

/**
 * 一条助手消息按 parts 顺序切成「工作过程」与「正文」两类段落，
 * 思考与工具归入同一段工作过程（对应 Assist 的可折叠工作块），正文单独成段。
 */
private fun ForgeChatMessage.toSegments(): List<ForgeSegment> {
    val segments = mutableListOf<ForgeSegment>()
    val work = mutableListOf<AgentChatMessageUi>()
    fun flushWork() {
        if (work.isEmpty()) return
        segments += ForgeSegment.Work(key = "work-$id-${segments.size}", items = work.toList())
        work.clear()
    }
    parts.forEach { part ->
        when (part) {
            is ForgePart.Reasoning -> work += ThinkingMessageUi(
                id = part.id,
                content = part.text,
                isStreaming = !part.ended,
            )

            is ForgePart.Tool -> work += part.toToolActivity()

            is ForgePart.Text -> if (part.text.isNotBlank()) {
                flushWork()
                segments += ForgeSegment.Body(
                    id = part.id,
                    text = part.text,
                    streaming = !part.ended,
                )
            }

            is ForgePart.Other -> Unit
        }
    }
    flushWork()
    return segments
}

private fun ForgePart.Tool.toToolActivity(): ToolActivityMessageUi = ToolActivityMessageUi(
    id = id,
    toolName = tool.ifBlank { "tool" },
    status = when (status) {
        ForgeToolStatus.Pending, ForgeToolStatus.Running -> ToolActivityStatusUi.Running
        ForgeToolStatus.Completed -> ToolActivityStatusUi.Success
        ForgeToolStatus.Failed -> ToolActivityStatusUi.Failed
    },
    argumentsSummary = title?.takeIf(String::isNotBlank) ?: summarizeInput(input).orEmpty(),
    command = input?.takeIf(String::isNotBlank),
    resultSummary = error?.takeIf(String::isNotBlank) ?: output?.takeIf(String::isNotBlank),
)

private val InputSummaryKeys = listOf(
    "description",
    "command",
    "filePath",
    "path",
    "pattern",
    "query",
    "url",
)

private fun summarizeInput(input: String?): String? {
    val raw = input?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    if (obj != null) {
        InputSummaryKeys.forEach { key ->
            val value = (obj[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
    }
    return raw.lineSequence().firstOrNull()?.take(MAX_SUMMARY_CHARS)
}

private const val MAX_SUMMARY_CHARS = 120
