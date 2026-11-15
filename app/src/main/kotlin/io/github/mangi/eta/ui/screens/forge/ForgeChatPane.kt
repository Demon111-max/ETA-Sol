package io.github.mangi.eta.ui.screens.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.forge.ForgeChatMessage
import io.github.mangi.eta.agent.forge.ForgePart
import io.github.mangi.eta.agent.forge.ForgeProtocol
import io.github.mangi.eta.agent.forge.ForgeQuestionRequest
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Forge 对话：opencode 的输出按 parts 原样铺在 Assist 同款时间线上，
 * 思考块、工具行、正文都复用现有组件，不经过 Eta 的 AgentLoop。
 */
@Composable
internal fun ForgeChatPane(
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    newConversationSignal: Int = 0,
    openSessionRequest: Pair<String, Int>? = null,
    onSessionsChanged: (String?) -> Unit = {},
    onBusyChanged: (String?, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ForgeChatState(context.applicationContext, scope, onSessionsChanged) }
    LaunchedEffect(Unit) { state.start() }
    // 「新建对话」信号递增时才另起新对话；面板重建时带着旧信号值进来不能重复触发，
    // 否则从侧边栏点开某个会话、面板一重组就会被拽回空白页。
    var consumedNewConversationSignal by remember { mutableStateOf(newConversationSignal) }
    LaunchedEffect(newConversationSignal) {
        if (newConversationSignal != consumedNewConversationSignal) {
            consumedNewConversationSignal = newConversationSignal
            state.newConversation()
        }
    }
    // 侧边栏点开某个 Forge 会话：请求序号变化就切到目标会话。
    LaunchedEffect(openSessionRequest) {
        val request = openSessionRequest ?: return@LaunchedEffect
        state.openSession(request.first)
    }
    // 运行状态同步给上层：侧边栏要在正在跑的 Forge 会话条目上显示运行圆点。
    LaunchedEffect(state.busy, state.sessionId) { onBusyChanged(state.sessionId, state.busy) }
    DisposableEffect(Unit) {
        onDispose {
            state.shutdown()
            onBusyChanged(null, false)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (val startup = state.startup) {
                ForgeStartup.Connecting -> ForgeCaption(
                    text = stringResource(R.string.forge_server_starting),
                    modifier = Modifier.padding(top = topInset + 12.dp, start = 14.dp),
                )

                is ForgeStartup.Failed -> Box(modifier = Modifier.padding(top = topInset)) {
                    ListEmptyState(
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
                }

                ForgeStartup.Ready -> ForgeTranscript(state = state, topInset = topInset)
            }
        }
        if (state.startup is ForgeStartup.Ready) {
            state.notice?.let { notice ->
                ForgeCaption(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            state.pendingQuestion?.let { request ->
                ForgeQuestionCard(
                    request = request,
                    onAnswer = { answers -> state.answerQuestion(answers) },
                    onSkip = { state.dismissQuestion() },
                )
            }
            ForgeComposer(
                isStreaming = state.busy,
                reasoningEffort = state.reasoningEffort,
                onReasoningEffortChange = { state.reasoningEffort = it },
                onSend = { text -> state.send(text) },
                onStop = { state.stop() },
            )
        }
    }
}

@Composable
private fun ForgeTranscript(state: ForgeChatState, topInset: Dp = 0.dp) {
    val messages = state.messages
    if (messages.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = topInset),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForgeCaption(text = stringResource(R.string.forge_empty_hint))
        }
        return
    }
    // 按轮分组：一轮里 opencode 会产出多条助手消息（每步一条），必须合成一个工作过程块，
    // 否则每条消息各带一个汇总头、轮次没结束就逐个收起，和 dsh 一整段连着的样子对不上。
    val turns = remember(messages) { messages.groupIntoTurns() }
    val listState = rememberLazyListState()
    val lastSignature = messages.lastOrNull()?.let { message ->
        // 签名要含最后一个 part 的内容长度：流式 delta 只加长文本，part 数和 id 都不变，
        // 不含长度的话流式期间签名不变、列表不会跟着滚到底。
        val tail = when (val last = message.parts.lastOrNull()) {
            is ForgePart.Text -> "t${last.text.length}"
            is ForgePart.Reasoning -> "r${last.text.length}"
            is ForgePart.Tool -> "tool${last.status}"
            else -> ""
        }
        "${message.id}:${message.parts.size}:${message.parts.lastOrNull()?.id}:$tail"
    }
    LaunchedEffect(lastSignature) {
        // 流式追加时贴住底部；用无动画的 scrollToItem，不引入新的滚动效果。
        listState.scrollToItem(turns.lastIndex)
    }
    // 与 Assist 同款：列表整屏铺到顶栏底下，顶部留白走 contentPadding，顶栏才有内容可模糊。
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = topInset + 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(turns, key = { it.key }) { turn ->
            when (turn) {
                is ForgeTurn.User -> ForgeUserRow(message = turn.message)

                is ForgeTurn.Assistant -> ForgeAssistantTurnRow(
                    messages = turn.messages,
                    active = state.busy &&
                        turn.messages.lastOrNull()?.id == messages.lastOrNull()?.id,
                )
            }
        }
    }
}

/** 一轮对话：一条用户消息，或连续的若干条助手消息（opencode 一步一条，要合并显示）。 */
private sealed interface ForgeTurn {
    val key: String

    data class User(val message: ForgeChatMessage) : ForgeTurn {
        override val key: String get() = message.id
    }

    data class Assistant(val messages: List<ForgeChatMessage>) : ForgeTurn {
        override val key: String get() = messages.first().id
    }
}

private fun List<ForgeChatMessage>.groupIntoTurns(): List<ForgeTurn> {
    val turns = mutableListOf<ForgeTurn>()
    var pending = mutableListOf<ForgeChatMessage>()
    fun flush() {
        if (pending.isNotEmpty()) {
            turns += ForgeTurn.Assistant(pending.toList())
            pending = mutableListOf()
        }
    }
    for (message in this) {
        when (message.role) {
            ForgeRole.User -> {
                flush()
                turns += ForgeTurn.User(message)
            }

            ForgeRole.Assistant -> pending += message
        }
    }
    flush()
    return turns
}

@Composable
private fun ForgeUserRow(message: ForgeChatMessage) {
    ChatMessageItem(
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
}

@Composable
private fun ForgeAssistantTurnRow(messages: List<ForgeChatMessage>, active: Boolean) {
    // 整轮的 parts 按顺序摊平再切段：相邻的思考/工具（哪怕跨消息）连成同一个工作过程块，
    // 汇总头只在整轮结束时出现一次，轮次进行中不提前收起。
    val segments = remember(messages) { messages.flatMap { it.parts }.toSegments() }
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

@Composable
private fun ForgeComposer(
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
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
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = ForgeReasoningEfforts,
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                isEditingMessage = false,
                editHasLaterTurns = false,
                preserveFollowingMessages = false,
                onReasoningEffortChange = onReasoningEffortChange,
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

/**
 * question 工具的提问卡：模型问了问题、整轮正挂起等回答。
 * 选项点选（多选可多点），允许自定义时还能自己写答案，写了以自写为准；
 * 每个问题都有答案才能提交；跳过走 reject 让模型自己决定下一步。
 */
@Composable
private fun ForgeQuestionCard(
    request: ForgeQuestionRequest,
    onAnswer: (List<List<String>>) -> Unit,
    onSkip: () -> Unit,
) {
    var selections by remember(request.id) { mutableStateOf<Map<Int, Set<String>>>(emptyMap()) }
    var customAnswers by remember(request.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val ready = request.questions.indices.all { index ->
        selections[index]?.isNotEmpty() == true || customAnswers[index]?.isNotBlank() == true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        request.questions.forEachIndexed { index, question ->
            question.header?.takeIf { it.isNotBlank() }?.let { header ->
                Text(
                    text = header,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = question.question,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            question.options.forEach { option ->
                val selected = selections[index]?.contains(option.label) == true
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MiuixTheme.colorScheme.surface
                            },
                            RoundedCornerShape(12.dp),
                        )
                        .clickable {
                            val current = selections[index].orEmpty()
                            val next = when {
                                question.multiple ->
                                    if (option.label in current) current - option.label else current + option.label

                                selected -> emptySet()
                                else -> setOf(option.label)
                            }
                            selections = selections + (index to next)
                            // 选了选项就以选项为准，清掉这个问题的自写答案。
                            if (next.isNotEmpty()) customAnswers = customAnswers - index
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = option.label,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    option.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (question.custom) {
                val custom = customAnswers[index].orEmpty()
                BasicTextField(
                    value = custom,
                    onValueChange = { value ->
                        customAnswers = customAnswers + (index to value)
                        // 自写答案与选项互斥（多选时选项保留，自写在提交时优先）。
                        if (value.isNotBlank() && !question.multiple) {
                            selections = selections + (index to emptySet())
                        }
                    },
                    textStyle = MiuixTheme.textStyles.body2.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (custom.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.forge_question_custom_hint),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                text = stringResource(R.string.forge_question_skip),
                onClick = onSkip,
            )
            TextButton(
                text = stringResource(R.string.forge_question_submit),
                onClick = {
                    val answers = request.questions.indices.map { index ->
                        val custom = customAnswers[index]?.trim().orEmpty()
                        if (custom.isNotEmpty()) {
                            listOf(custom)
                        } else {
                            selections[index].orEmpty().toList()
                        }
                    }
                    onAnswer(answers)
                },
                enabled = ready,
                colors = ButtonDefaults.textButtonColorsPrimary(),
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
 * 一轮里的全部 parts 按顺序切成「工作过程」与「正文」两类段落，
 * 思考与工具归入同一段工作过程（对应 Assist 的可折叠工作块），正文单独成段。
 */
private fun List<ForgePart>.toSegments(): List<ForgeSegment> {
    val segments = mutableListOf<ForgeSegment>()
    val work = mutableListOf<AgentChatMessageUi>()
    fun flushWork() {
        if (work.isEmpty()) return
        segments += ForgeSegment.Work(key = "work-${work.first().id}-${segments.size}", items = work.toList())
        work.clear()
    }
    forEach { part ->
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
    argumentsSummary = title?.takeIf(String::isNotBlank)
        ?: (if (tool == "question") ForgeProtocol.summarizeQuestionInput(input) else null)
        ?: summarizeInput(input).orEmpty(),
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

/**
 * Forge 可选的思考档位：opencode 侧靠 variant 落地（配置写入时预置），
 * OFF 在这条链路上关不掉思考，不提供；DEFAULT 不发 variant、用上游默认。
 */
private val ForgeReasoningEfforts = listOf(
    ReasoningEffort.DEFAULT,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

private const val MAX_SUMMARY_CHARS = 120
