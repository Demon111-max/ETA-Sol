package io.github.mangi.eta.ui.screens.forge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.mangi.eta.ui.components.ChatWaitActivity
import io.github.mangi.eta.ui.components.ChatWaitRow
import io.github.mangi.eta.ui.components.DshRowHeader
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.dshFoldEnter
import io.github.mangi.eta.ui.components.dshFoldExit
import io.github.mangi.eta.ui.components.dshTertiaryColor
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
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
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
    startBlank: Boolean = false,
    onSessionsChanged: (String?) -> Unit = {},
    onBusyChanged: (String?, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 模式切换进来时 startBlank=true：状态一创建就记下「落到新对话」，start() 读到
    // pendingBlank 会跳过恢复最近会话。面板每次进入 Forge 都是新建的，记在这里最稳。
    val state = remember {
        ForgeChatState(context.applicationContext, scope, onSessionsChanged).also { created ->
            if (startBlank) created.requestBlankOnStart()
        }
    }
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
            // 启动遮罩、失败页、对话三态之间走交叉淡入淡出，不硬切（仿 DSHA 启动屏的收尾）。
            AnimatedContent(
                targetState = state.startup,
                transitionSpec = {
                    fadeIn(animationSpec = tween(240)) togetherWith
                        fadeOut(animationSpec = tween(160))
                },
                label = "forgeStartup",
                modifier = Modifier.fillMaxSize(),
            ) { startup ->
                when (startup) {
                    ForgeStartup.Connecting -> ForgeStartupSplash(stage = state.startupStage)

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

/**
 * Forge 启动遮罩（仿 DSHA 的启动屏）：中心一团淡光晕，终端符号 >_ 一笔笔把自己画出来，
 * 下面 FORGE 字标淡入，再下面是小转圈 + 随真实启动进度变化的阶段文字。
 * 就绪后由外层 AnimatedContent 交叉淡出到对话，不留一句光秃秃的「正在启动」。
 */
@Composable
private fun ForgeStartupSplash(stage: ForgeStartupStage, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val drawProgress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "forgeStartupGlyph",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 420, delayMillis = 240, easing = FastOutSlowInEasing),
        label = "forgeStartupText",
    )
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.primary.copy(alpha = 0.12f), Color.Transparent),
                    ),
                    shape = CircleShape,
                ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val stroke = 5.dp.toPx()
                val w = size.width
                val h = size.height
                val chevronTop = Offset(w * 0.26f, h * 0.28f)
                val chevronMid = Offset(w * 0.60f, h * 0.52f)
                val chevronBottom = Offset(w * 0.26f, h * 0.76f)
                val underlineStart = Offset(w * 0.52f, h * 0.84f)
                val underlineEnd = Offset(w * 0.82f, h * 0.84f)
                fun drawPartial(from: Offset, to: Offset, progress: Float) {
                    if (progress <= 0f) return
                    val end = if (progress >= 1f) {
                        to
                    } else {
                        Offset(
                            from.x + (to.x - from.x) * progress,
                            from.y + (to.y - from.y) * progress,
                        )
                    }
                    drawLine(
                        color = colors.primary,
                        start = from,
                        end = end,
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                // 进度分配：0–0.38 画 > 的上斜笔，0.38–0.76 下斜笔，0.76–1 下划线。
                drawPartial(chevronTop, chevronMid, (drawProgress / 0.38f).coerceIn(0f, 1f))
                drawPartial(chevronMid, chevronBottom, ((drawProgress - 0.38f) / 0.38f).coerceIn(0f, 1f))
                drawPartial(underlineStart, underlineEnd, ((drawProgress - 0.76f) / 0.24f).coerceIn(0f, 1f))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "FORGE",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 7.sp,
                ),
                color = colors.onBackground.copy(alpha = textAlpha),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer { alpha = textAlpha },
            ) {
                InfiniteProgressIndicator(size = 16.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        when (stage) {
                            ForgeStartupStage.Preparing -> R.string.forge_startup_stage_preparing
                            ForgeStartupStage.StartingServer -> R.string.forge_startup_stage_starting_server
                            ForgeStartupStage.Connecting -> R.string.forge_startup_stage_connecting
                        },
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ForgeTranscript(state: ForgeChatState, topInset: Dp = 0.dp) {
    val messages = state.messages
    if (messages.isEmpty()) {
        if (state.busy) {
            // 第一句话刚发出去、服务端还没回显：先把等待行摆出来开始走秒，
            // 用户要能立刻确认「请求已经发出去了」，而不是对着一句空提示干等。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset + 12.dp, start = 34.dp, end = 14.dp),
            ) {
                ChatWaitRow(
                    activity = ChatWaitActivity.Thinking,
                    startedAtMs = state.turnStartedAtMs,
                    lastActivityAtMs = state.lastActivityAtMs,
                    overtimeEnabled = state.pendingQuestion == null,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ForgeCaption(text = stringResource(R.string.forge_empty_hint))
            }
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
            is ForgePart.Retry -> "retry${last.attempt}"
            else -> ""
        }
        "${message.id}:${message.parts.size}:${message.parts.lastOrNull()?.id}:$tail"
    }
    LaunchedEffect(lastSignature, state.busy) {
        // 流式追加时贴住底部；等待行出现时目标是它那一行。用无动画的 scrollToItem，
        // 不引入新的滚动效果。
        listState.scrollToItem(if (state.busy) turns.size else turns.lastIndex)
    }
    val lastAssistantTurn = turns.filterIsInstance<ForgeTurn.Assistant>().lastOrNull()
    val turnError = state.turnError
    val errorAnchorFound = turnError != null && turns.filterIsInstance<ForgeTurn.Assistant>()
        .any { turn -> turn.messages.any { it.id == turnError.anchorMessageId } }
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
                    error = turnError?.takeIf { err ->
                        turn === lastAssistantTurn &&
                            (err.anchorMessageId == null ||
                                turn.messages.any { it.id == err.anchorMessageId } ||
                                !errorAnchorFound)
                    },
                )
            }
        }
        if (state.busy) {
            // 等待行钉在转录末尾：loader + 当前活动词 + 从发送起算的秒数 + 超时徽章。
            item(key = "forge_wait_row") {
                ChatWaitRow(
                    activity = deriveWaitActivity(messages),
                    startedAtMs = state.turnStartedAtMs,
                    lastActivityAtMs = state.lastActivityAtMs,
                    overtimeEnabled = state.pendingQuestion == null,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

/** 等待行的活动词：看最后一轮最后一个非重试 part 在干什么（重试不算活动，词留在思考上）。 */
private fun deriveWaitActivity(messages: List<ForgeChatMessage>): ChatWaitActivity {
    val parts = messages.lastOrNull { it.role == ForgeRole.Assistant }?.parts.orEmpty()
    return when (val last = parts.lastOrNull { it !is ForgePart.Retry }) {
        is ForgePart.Text ->
            if (last.ended) ChatWaitActivity.Thinking else ChatWaitActivity.Writing

        is ForgePart.Tool -> when (last.status) {
            ForgeToolStatus.Pending -> ChatWaitActivity.PreparingTool
            ForgeToolStatus.Running -> ChatWaitActivity.RunningTool
            else -> ChatWaitActivity.Thinking
        }

        else -> ChatWaitActivity.Thinking
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
private fun ForgeAssistantTurnRow(
    messages: List<ForgeChatMessage>,
    active: Boolean,
    error: ForgeTurnError? = null,
) {
    // 整轮的 parts 按顺序摊平再切段：相邻的思考/工具（哪怕跨消息）连成同一个工作过程块，
    // 汇总头只在整轮结束时出现一次，轮次进行中不提前收起。
    val segments = remember(messages) { messages.flatMap { it.parts }.toSegments() }
    val lastWorkSegment = segments.filterIsInstance<ForgeSegment.Work>().lastOrNull()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is ForgeSegment.Work -> AgentWorkProcess(
                    id = segment.key,
                    messages = segment.items,
                    onOpenBrowser = {},
                    currentBrowserMessageId = null,
                    retainedStreamingStates = emptyMap<String, StreamingMarkdownState>(),
                    active = active,
                    failed = error != null && segment === lastWorkSegment,
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

                is ForgeSegment.Retry -> ForgeRetryRow(
                    segment = segment,
                    running = active && index == segments.lastIndex,
                )
            }
        }
        if (error != null) {
            ForgeTurnErrorRow(error = error)
        }
    }
}

/**
 * 重试行（dsh 行形态）：[刷新图标] 正在/已重试模型请求 · 第 N 次，
 * 展开看这次失败的错误原文——重试对用户必须可见，不能只剩无声的等待。
 */
@Composable
private fun ForgeRetryRow(segment: ForgeSegment.Retry, running: Boolean) {
    var expanded by rememberSaveable(segment.id) { mutableStateOf(false) }
    val detail = segment.errorMessage?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        DshRowHeader(
            icon = Icons.Rounded.Refresh,
            title = stringResource(
                if (running) R.string.forge_retry_running else R.string.forge_retry_done,
            ),
            summary = stringResource(R.string.forge_retry_attempt, segment.attempt),
            running = running,
            expanded = expanded,
            expandable = detail != null,
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = expanded && detail != null,
            enter = dshFoldEnter(),
            exit = dshFoldExit(),
        ) {
            Text(
                text = detail.orEmpty(),
                style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                color = dshTertiaryColor(),
                modifier = Modifier.padding(start = 22.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/** 本轮失败行：红点 + 红字标题 + 错误原文 + 右侧状态码片（仿 DSHA 超时收尾那一行）。 */
@Composable
private fun ForgeTurnErrorRow(error: ForgeTurnError) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(StatusError),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.forge_turn_failed_row),
            style = MiuixTheme.textStyles.body2.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = StatusError,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = error.message,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp),
            color = dshTertiaryColor(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        error.statusCode?.let { code ->
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(
                        MiuixTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = code.toString(),
                    style = TextStyle(fontSize = 10.sp, fontFeatureSettings = "tnum"),
                    color = dshTertiaryColor(),
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

    /** 连续的重试记录合并成一行：attempt 取最大，错误原文取最近一次的。 */
    data class Retry(
        val id: String,
        val attempt: Int,
        val errorMessage: String?,
        val statusCode: Int?,
    ) : ForgeSegment
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

            is ForgePart.Retry -> {
                // 紧挨着的重试并成一行（次数取最大）；中间隔了思考/工具就另起一行，
                // 保持重试在时间线上的真实位置。
                val lastSegment = segments.lastOrNull()
                if (work.isEmpty() && lastSegment is ForgeSegment.Retry) {
                    segments[segments.lastIndex] = lastSegment.copy(
                        attempt = maxOf(lastSegment.attempt, part.attempt),
                        errorMessage = part.errorMessage ?: lastSegment.errorMessage,
                        statusCode = part.statusCode ?: lastSegment.statusCode,
                    )
                } else {
                    flushWork()
                    segments += ForgeSegment.Retry(
                        id = part.id,
                        attempt = part.attempt,
                        errorMessage = part.errorMessage,
                        statusCode = part.statusCode,
                    )
                }
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
