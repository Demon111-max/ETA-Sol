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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.window.Dialog
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.forge.ForgeChatMessage
import io.github.mangi.eta.agent.forge.ForgePart
import io.github.mangi.eta.agent.forge.ForgeProtocol
import io.github.mangi.eta.agent.forge.ForgeQuestionRequest
import io.github.mangi.eta.agent.forge.ForgeRole
import io.github.mangi.eta.agent.forge.ForgeToolStatus
import io.github.mangi.eta.agent.forge.ForgeWorkspace
import io.github.mangi.eta.agent.forge.ForgeWorkspaces
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import top.yukonga.miuix.kmp.basic.Icon
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
    state: ForgeChatState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    onBusyChanged: (String?, Boolean) -> Unit = { _, _ -> },
    /** 打开侧边栏（工作区列表在那里）：空页的工作区卡片点一下就走到这。 */
    onOpenWorkspacePicker: () -> Unit = {},
) {
    // 状态由 AgentAppRoot 持有后传进来：面板整块销毁重建（进设置/终端子页再回来）时
    // 草稿、待发附件、排队消息、当前会话都跟着状态活着。start() 自己会在已 Ready 时
    // 直接返回（并把断掉的事件订阅接回去），所以这里无条件调用是安全的。
    LaunchedEffect(Unit) { state.start() }
    // 运行状态同步给上层：侧边栏要在正在跑的 Forge 会话条目上显示运行圆点。
    LaunchedEffect(state.busy, state.sessionId) { onBusyChanged(state.sessionId, state.busy) }
    DisposableEffect(Unit) {
        onDispose {
            // 只断订阅/在途任务，状态本身留在上层：再进来时 start() 会把订阅接回去。
            state.shutdown()
            onBusyChanged(null, false)
        }
    }

    // 底部整叠（提示条/提问卡/输入区）的实测高度：列表按它抬高底部留白，
    // 停在底部时最后一行正好落在输入区上方，不被浮层盖住。
    var bottomStackHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val surface = MiuixTheme.colorScheme.surface

    Box(modifier = modifier.fillMaxSize()) {
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

                ForgeStartup.Ready -> ForgeTranscript(
                    state = state,
                    topInset = topInset,
                    bottomInset = bottomStackHeight,
                    onOpenWorkspacePicker = onOpenWorkspacePicker,
                )
            }
        }
        if (state.startup is ForgeStartup.Ready) {
            // 与 Assist 同构：消息层整屏铺满，底部整叠作为浮层盖在列表上，背后一层
            // 垂直渐隐承接，内容滚动时从渐隐里穿过。以前是上下分栏：列表底边把一行字
            // 齐根切断、下面接着输入区的白色块（用户图 3 指的就是这里，不是顶栏）。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .onSizeChanged { size ->
                        bottomStackHeight = with(density) { size.height.toDp() }
                    },
            ) {
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.notice?.let { notice ->
                        ForgeNoticeBar(
                            text = notice,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    state.pendingQuestion?.let { request ->
                        ForgeQuestionCard(
                            request = request,
                            onAnswer = { answers -> state.answerQuestion(answers) },
                            onSkip = { state.dismissQuestion() },
                            answering = state.questionAnswerInFlight,
                        )
                    }
                    if (state.workspace == null) {
                        // 3.4.43（N7）：没有工作区就没有可写目录，也建不出会话。
                        // 以前这里照样渲染输入区，一条消息发出去就会捡到全局会话、
                        // 或者建出一个落不了盘的孤儿会话。现在直接把输入换成一句提示 + 入口。
                        ForgeWorkspaceRequiredBar(onAddWorkspace = onOpenWorkspacePicker)
                    } else {
                        ForgeComposer(
                            state = state,
                            onSend = { text -> state.send(text) },
                            onStop = { state.stop() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 没有工作区时代替输入区的提示条（3.4.43 N7）：说明为什么不能发，并给一个添加入口。
 * 输入区整个不渲染，所以不存在「点进去打字却发不出去」的半死状态。
 */
@Composable
private fun ForgeWorkspaceRequiredBar(onAddWorkspace: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.forge_send_needs_workspace),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.forge_workspace_add),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clickable { onAddWorkspace() },
        )
    }
}

/** 错误提示条：错误色 + 图标，别再用一行弱色灰字——用户看漏了就以为「没反应」。 */
@Composable
private fun ForgeNoticeBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = StatusError,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = StatusError,
        )
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
private fun ForgeTranscript(
    state: ForgeChatState,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    onOpenWorkspacePicker: () -> Unit = {},
) {
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
            // 空页门面（3.4.43 B2，用户明确要求）：只有一枚锻造主题徽章 + 标题，其余留白。
            // 删掉的两样东西：两行提示废话（「直接说要改什么…」「切换工作区在侧边栏里」）
            // 和工作区卡片——空白页不再显示当前工作区，工作区在侧边栏看。
            // 唯一例外：一个工作区都没有时给一个「添加工作区」的入口，否则新用户连第一句话
            // 都发不出去（N7）。
            Column(
                modifier = Modifier.fillMaxSize().padding(top = topInset, start = 24.dp, end = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ForgeForgeBadge()
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.forge_empty_title),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (state.workspace == null) {
                    Spacer(modifier = Modifier.height(18.dp))
                    ForgeGateAction(
                        text = stringResource(R.string.forge_workspace_add),
                        onClick = onOpenWorkspacePicker,
                    )
                }
            }
        }
        return
    }
    // 按轮分组：一轮里 opencode 会产出多条助手消息（每步一条），必须合成一个工作过程块，
    // 否则每条消息各带一个汇总头、轮次没结束就逐个收起，和 dsh 一整段连着的样子对不上。
    // 3.4.43（N9）：走增量缓存——只有最后一轮会随 delta 变化，前面的轮次对象整批复用，
    // 那些行在 LazyColumn 里参数没变，Compose 直接跳过重组。
    val turnCache = remember { ForgeTurnGroupCache() }
    val turns = remember(messages) { turnCache.group(messages) }
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
    // 跟底锚定（与 Assist 同口径）：用户没往上翻时流式追加贴住底部；一旦上滑看前文，
    // 新内容不许抢屏，改出一个回到底部的按钮。以前是每个 delta 无条件 scrollToItem，
    // 流式期间根本没法往上看。
    var followBottom by remember { mutableStateOf(true) }
    // 判定容差：末项底边离视口底还有这么多像素以内，仍算「停在底部」。
    val followBottomSlackPx = with(LocalDensity.current) { FOLLOW_BOTTOM_SLACK.toPx() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val total = info.totalItemsCount
                if (total > 0) {
                    val last = info.visibleItemsInfo.lastOrNull()
                    if (last != null) {
                        // 只看「最后一项是否可见」不够：末项（一整轮回答）常常高于视口，用户
                        // 上滑读它时末项仍可见，流式内容却一直把人拽回底部。要求末项的底边
                        // 真的贴到视口底（含容差）才算贴底。
                        followBottom = last.index >= total - 1 &&
                            last.offset + last.size <= info.viewportEndOffset + followBottomSlackPx
                    }
                }
            }
    }
    LaunchedEffect(state.busy) { if (state.busy) followBottom = true }
    LaunchedEffect(lastSignature, state.busy) {
        if (followBottom) {
            listState.scrollToItem(if (state.busy) turns.size else turns.lastIndex)
        }
    }
    val lastAssistantTurn = turns.filterIsInstance<ForgeTurn.Assistant>().lastOrNull()
    val turnError = state.turnError
    // 失败锚点可能落在用户消息上（助手还没产出任何 part 时失败，最后一条就是用户消息），
    // 以前只在助手轮里找锚，找不到就回退到最后一条助手轮——于是错误被画到上一轮回答下面；
    // 一个助手轮都没有时更是整条错误都不渲染。
    val errorAnchorInAssistant = turnError != null && turns.filterIsInstance<ForgeTurn.Assistant>()
        .any { turn -> turn.messages.any { it.id == turnError.anchorMessageId } }
    val standaloneError = turnError != null && turns.none { it is ForgeTurn.Assistant }
    val coroutineScope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize()) {
        // 与 Assist 同款：列表整屏铺满，顶部留白走 contentPadding 让顶栏有内容可模糊；
        // 底部留白 = 输入区整叠高度，停在底部时最后一行落在输入区上方，
        // 滚动时内容从输入区的渐隐层下穿过，不再被分栏底边切断。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = topInset + 12.dp,
                bottom = bottomInset + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(turns, key = { it.key }) { turn ->
                when (turn) {
                    is ForgeTurn.User -> ForgeUserRow(message = turn.message)

                    is ForgeTurn.Assistant -> ForgeAssistantTurnRow(
                        messages = turn.messages,
                        // 提问挂起时这一轮也没结束：保持展开，不许提前收起出汇总头。
                        active = (state.busy || state.pendingQuestion != null) &&
                            turn.messages.lastOrNull()?.id == messages.lastOrNull()?.id,
                        error = turnError?.takeIf { err ->
                            // 锚在助手轮里就画在那一轮；锚不在助手轮（失败时最后一条是用户消息）
                            // 才退到最后一条助手轮，且只画一处，别重复渲染。
                            if (errorAnchorInAssistant) {
                                turn.messages.any { it.id == err.anchorMessageId }
                            } else {
                                turn === lastAssistantTurn
                            }
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
            if (standaloneError && turnError != null) {
                // 一个助手轮都没有（首条消息就失败）：没有可依附的回答，错误单独占一行。
                item(key = "forge_standalone_error") {
                    ForgeTurnErrorRow(error = turnError)
                }
            }
        }
        if (!followBottom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = bottomInset + 12.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        followBottom = true
                        coroutineScope.launch {
                            listState.scrollToItem(if (state.busy) turns.size else turns.lastIndex)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.forge_back_to_bottom),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
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
    // 只有附件没有文字的消息也要有可见内容：把附件名逐行摆进气泡，别渲染成一个空气泡。
    val text = message.parts
        .filterIsInstance<ForgePart.Text>()
        .joinToString("\n") { it.text }
    val attachmentNames = message.parts
        .filterIsInstance<ForgePart.Attachment>()
        .map { it.filename?.takeIf(String::isNotBlank) ?: it.mime ?: "file" }
    val content = listOf(text) + attachmentNames
    ChatMessageItem(
        message = UserMessageUi(
            id = message.id,
            content = content.filter { it.isNotBlank() }.joinToString("\n"),
        ),
        onSuggestionClick = {},
        onRunTraceClick = {},
        onOpenBrowser = {},
        showBrowserShortcut = false,
        showCopyAction = true,
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
    // 3.4.43（N9）：分段走增量缓存，每个 delta 只重切最后一个 part，前面的段对象原样复用。
    val segmentCache = remember { ForgeSegmentCache() }
    val segments = remember(messages) { segmentCache.segments(messages.flatMap { it.parts }) }
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
                    showCopyAction = true,
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
    state: ForgeChatState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachments = state.pendingAttachments
    // 输入栏的待发模型是 Assist 的形状：图片走图片条、其余走文件引用条，这里做一次映射。
    val pendingImages = attachments.filter { it.isImage }.map { attachment ->
        PendingImageUi(
            id = attachment.id,
            uri = attachment.source,
            dataUrl = attachment.previewDataUrl.orEmpty(),
            mimeType = attachment.mime,
        )
    }
    val pendingFiles = attachments.filter { !it.isImage }.map { attachment ->
        PendingFileReferenceUi(
            id = attachment.id,
            reference = AgentFileReference(
                displayName = attachment.displayName,
                absolutePath = attachment.source,
                kind = if (attachment.isFolder) {
                    AgentFileReferenceKind.Directory
                } else {
                    AgentFileReferenceKind.File
                },
            ),
        )
    }
    val queuedMessages = state.queuedIds.zip(state.queuedTexts).map { (id, text) ->
        QueuedMessageUi(id = "forge-queue-$id", content = text)
    }
    // 底部渐隐与 imePadding 已由外层底部整叠统一提供（盖住提问卡/输入区），
    // 这里只剩输入区本体，别再套一层渐隐免得交界处颜色叠深。
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
        ) {
            AgentChatInputBar(
                // 草稿存在状态里而不是输入框内部：面板被销毁重建（进子页再回来）时
                // 已输入的内容和待发附件都要还在。
                input = state.draft,
                onInputChange = { state.draft = it },
                isCompacting = false,
                contextUsage = AgentContextUsageUi(
                    contextTokens = state.contextUsageTokens
                        ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                    contextWindow = state.contextWindowLimit,
                ),
                showContextUsage = true,
                isStreaming = state.busy,
                reasoningEffort = state.reasoningEffort,
                availableReasoningEfforts = ForgeReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFiles,
                queuedMessages = queuedMessages,
                isEditingMessage = false,
                editHasLaterTurns = false,
                preserveFollowingMessages = false,
                onReasoningEffortChange = { state.reasoningEffort = it },
                onCompactContext = {},
                canCompactContext = false,
                onSubmit = onSend,
                onRemoveQueuedMessage = { id ->
                    id.removePrefix("forge-queue-").toLongOrNull()
                        ?.let { queuedId -> state.removeQueuedMessage(queuedId) }
                },
                onStop = onStop,
                onAttachImage = { uri ->
                    scope.launch {
                        materializePickedImage(context, uri)?.let { state.addAttachment(it) }
                            ?: state.notify(context.getString(R.string.forge_attachment_add_failed))
                    }
                },
                onRemoveImage = { id -> state.removeAttachment(id) },
                onAttachFiles = { uris ->
                    scope.launch {
                        var failed = false
                        uris.forEach { uri ->
                            val attachment = materializePickedFile(context, uri)
                            if (attachment != null) state.addAttachment(attachment) else failed = true
                        }
                        if (failed) {
                            state.notify(context.getString(R.string.forge_attachment_add_failed))
                        }
                    }
                },
                onAttachFolder = { uri ->
                    scope.launch {
                        materializePickedFolder(context, uri)?.let { state.addAttachment(it) }
                            ?: state.notify(context.getString(R.string.forge_attachment_add_failed))
                    }
                },
                onAttachFilePath = { path ->
                    materializePathEntry(context, path)?.let { state.addAttachment(it) }
                        ?: state.notify(context.getString(R.string.forge_attachment_add_failed))
                },
                onRemoveFileReference = { id -> state.removeAttachment(id) },
                onCancelMessageEdit = {},
                showAttach = true,
                hintText = stringResource(R.string.forge_input_hint),
                workingText = stringResource(R.string.forge_working),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 附件在添加时就落到本地暂存目录：选择器的 URI 授权寿命不可靠，等发送时再读可能已经失效。 */
private fun forgeStageDir(context: Context): File =
    File(context.cacheDir, "forge-stage").apply { mkdirs() }

/** 查选择器给的文件名（OpenableColumns），查不到返回 null。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
}.getOrNull()

/** 把选择器给的内容流复制进暂存目录，返回暂存文件；超限或读不到返回 null。 */
private fun copyToStage(context: Context, uri: Uri, name: String, maxBytes: Long): File? {
    // 3.4.43（N11）：暂存路径也要过同一套清洗（forgeSanitizeFilename），
    // 以前这里直接用 SAF 的 DISPLAY_NAME 原文拼路径，落盘时才清洗，两套标准。
    val target = File(forgeStageDir(context), "${UUID.randomUUID()}-${forgeSanitizeFilename(name)}")
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var total = 0L
        input.use { stream ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) error("attachment too large")
                    out.write(buffer, 0, read)
                }
            }
        }
        target
    }.getOrElse {
        target.delete()
        null
    }
}

private suspend fun materializePickedImage(context: Context, uriString: String): ForgePendingAttachment? =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uriString),
            )
            ?: "image/jpeg"
        val name = queryDisplayName(context, uri)
            ?: "image-${System.currentTimeMillis()}.${MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"}"
        val staged = copyToStage(context, uri, name, MAX_STAGED_ATTACHMENT_BYTES) ?: return@withContext null
        // 小图顺手做一张预览（输入栏图片条要的 dataUrl）；大图跳过，条上只显示占位框。
        val preview = runCatching {
            val bytes = staged.readBytes()
            if (bytes.size <= MAX_PREVIEW_BYTES) {
                "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
            } else {
                null
            }
        }.getOrNull()
        ForgePendingAttachment(
            id = "att-${UUID.randomUUID()}",
            displayName = name,
            mime = mime,
            source = staged.absolutePath,
            isImage = true,
            previewDataUrl = preview,
        )
    }

private suspend fun materializePickedFile(context: Context, uriString: String): ForgePendingAttachment? =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val name = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file-${System.currentTimeMillis()}"
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                name.substringAfterLast('.', ""),
            )
            ?: "application/octet-stream"
        val staged = copyToStage(context, uri, name, MAX_STAGED_ATTACHMENT_BYTES)
        ForgePendingAttachment(
            id = "att-${UUID.randomUUID()}",
            displayName = name,
            mime = mime,
            // 太大没复制成：保留原始 URI，发送时再试一次（失败会按名字报出来）。
            source = staged?.absolutePath ?: uriString,
            isImage = false,
        )
    }

private suspend fun materializePickedFolder(context: Context, uriString: String): ForgePendingAttachment? =
    withContext(Dispatchers.IO) {
        // 文件夹走与 Assist 同一个网关把树 URI 解析成真实路径，发送时整夹复制进工作区。
        val gateway = AgentFileReferenceGateway(context.applicationContext, AndroidAgentLogger)
        when (val resolution = gateway.resolveDocumentUri(Uri.parse(uriString), AgentFileReferenceKind.Directory)) {
            is AgentFileReferenceGateway.Resolution.Success -> ForgePendingAttachment(
                id = "att-${UUID.randomUUID()}",
                displayName = resolution.reference.displayName,
                mime = "application/x-directory",
                source = resolution.reference.absolutePath,
                isImage = false,
                isFolder = true,
            )

            is AgentFileReferenceGateway.Resolution.Failure -> null
        }
    }

/** 「输入路径」入口：路径真实存在才收，文件/文件夹按实际类型登记，不复制（发送时再落工作区）。 */
private fun materializePathEntry(context: Context, rawPath: String): ForgePendingAttachment? {
    val file = File(rawPath.trim())
    if (!file.exists()) return null
    return when {
        file.isDirectory -> ForgePendingAttachment(
            id = "att-${UUID.randomUUID()}",
            displayName = file.name.ifBlank { file.absolutePath },
            mime = "application/x-directory",
            source = file.absolutePath,
            isImage = false,
            isFolder = true,
        )

        file.isFile -> {
            val extension = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            ForgePendingAttachment(
                id = "att-${UUID.randomUUID()}",
                displayName = file.name,
                mime = mime,
                source = file.absolutePath,
                isImage = mime.startsWith("image/"),
            )
        }

        else -> null
    }
}

private const val MAX_STAGED_ATTACHMENT_BYTES = 64L * 1024 * 1024
private const val MAX_PREVIEW_BYTES = 1_500_000

/** 判定「列表停在底部」的容差：末项底边离视口底这么近就算贴底，避免像素级抖动误判成上滑。 */
private val FOLLOW_BOTTOM_SLACK = 8.dp

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
    answering: Boolean = false,
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
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
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
                // 3.4.43（N10）：有一个请求在途就锁住两个按钮。
                // 以前双击会并发提交，第二次拿 404 再回退，最后弹一句假的「回答失败」。
                enabled = !answering,
            )
            TextButton(
                text = stringResource(R.string.forge_question_submit),
                onClick = {
                    val answers = request.questions.indices.map { index ->
                        val custom = customAnswers[index]?.trim().orEmpty()
                        val picked = selections[index].orEmpty().toList()
                        // 3.4.43（N6）：多选时「勾的选项 + 自写答案」一起提交（去重），
                        // 以前 custom 非空就整组替换，勾了的选项被静默丢掉，跟卡片上
                        // 写着「多选时选项保留」自相矛盾。单选仍然互斥，以自写为准。
                        when {
                            custom.isEmpty() -> picked
                            request.questions[index].multiple -> (picked + custom).distinct()
                            else -> listOf(custom)
                        }
                    }
                    onAnswer(answers)
                },
                enabled = ready && !answering,
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

/**
 * 空白页的徽章（3.4.43 B2）：原创的锻造主题图形——一块铁砧，砧面上方三粒火星，
 * 深色圆角底 + 底部暖光。跟启动屏那个终端 `>_` 字标不是一套形象，也不再借用。
 * 整幅图形用 Canvas 直接画（不引外部资源），缩放到任何尺寸都清晰。
 */
@Composable
private fun ForgeForgeBadge(modifier: Modifier = Modifier, size: Dp = 104.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.verticalGradient(listOf(Color(0xFF23201D), Color(0xFF141210)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            // 炉火暖光：从砧座底部往上洇，让深色底不发死。
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x59FF8A3D), Color(0x00FF8A3D)),
                    center = Offset(w * 0.5f, h * 0.84f),
                    radius = w * 0.5f,
                ),
                radius = w * 0.5f,
                center = Offset(w * 0.5f, h * 0.84f),
            )
            // 铁砧：左尖砧角 → 顶面 → 收腰 → 底座，一笔闭合。
            val anvil = Path().apply {
                moveTo(w * 0.09f, h * 0.44f)
                lineTo(w * 0.25f, h * 0.33f)
                lineTo(w * 0.87f, h * 0.33f)
                lineTo(w * 0.87f, h * 0.47f)
                lineTo(w * 0.65f, h * 0.47f)
                lineTo(w * 0.60f, h * 0.62f)
                lineTo(w * 0.75f, h * 0.62f)
                lineTo(w * 0.79f, h * 0.77f)
                lineTo(w * 0.30f, h * 0.77f)
                lineTo(w * 0.34f, h * 0.62f)
                lineTo(w * 0.44f, h * 0.62f)
                lineTo(w * 0.40f, h * 0.47f)
                lineTo(w * 0.09f, h * 0.47f)
                close()
            }
            drawPath(anvil, color = Color(0xFFC7CCD6))
            // 砧面高光：一条稍亮的横线，交代出「面」而不是一块平色。
            drawLine(
                color = Color(0xFFEFF2F7),
                start = Offset(w * 0.26f, h * 0.365f),
                end = Offset(w * 0.86f, h * 0.365f),
                strokeWidth = h * 0.022f,
                cap = StrokeCap.Round,
            )
            // 火星：三粒，中间最大最高。
            drawCircle(Color(0xFFFFC46B), radius = w * 0.036f, center = Offset(w * 0.29f, h * 0.20f))
            drawCircle(Color(0xFFFFDCA6), radius = w * 0.028f, center = Offset(w * 0.5f, h * 0.12f))
            drawCircle(Color(0xFFFFC46B), radius = w * 0.023f, center = Offset(w * 0.69f, h * 0.21f))
        }
    }
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
    forEach { part -> part.appendSegment(segments, work) }
    flushWork(segments, work)
    return segments
}

/** 把攒着的工作项收成一个工作块（key 只用首条 part 的 id）。 */
private fun flushWork(segments: MutableList<ForgeSegment>, work: MutableList<AgentChatMessageUi>) {
    if (work.isEmpty()) return
    // key 只用首条 part 的 id：以前还拼段数，流式期间段数一变 key 就变，
    // 工作块的展开状态（rememberSaveable 按 key 存）被重置、自己收起又展开。
    segments += ForgeSegment.Work(key = "work-${work.first().id}", items = work.toList())
    work.clear()
}

/**
 * 单个 part 的切段动作（3.4.43 N9）。原来整段逻辑写在 [toSegments] 里，
 * 抽出来是为了让分段缓存（[ForgeSegmentCache]）能「从上次的状态接着往后切」：
 * 一遍左折叠的状态只有 (segments, work) 两项，缓存住这两项就能精确续算，
 * 结果与从头全量切逐字节相同。
 */
private fun ForgePart.appendSegment(
    segments: MutableList<ForgeSegment>,
    work: MutableList<AgentChatMessageUi>,
) {
    when (this) {
        is ForgePart.Reasoning -> work += ThinkingMessageUi(
            id = id,
            content = text,
            isStreaming = !ended,
        )

        is ForgePart.Tool -> work += toToolActivity()

        is ForgePart.Text -> if (text.isNotBlank()) {
            flushWork(segments, work)
            segments += ForgeSegment.Body(
                id = id,
                text = text,
                streaming = !ended,
            )
        }

        is ForgePart.Retry -> {
            // 紧挨着的重试并成一行（次数取最大）；中间隔了思考/工具就另起一行，
            // 保持重试在时间线上的真实位置。
            val lastSegment = segments.lastOrNull()
            if (work.isEmpty() && lastSegment is ForgeSegment.Retry) {
                segments[segments.lastIndex] = lastSegment.copy(
                    attempt = maxOf(lastSegment.attempt, attempt),
                    errorMessage = errorMessage ?: lastSegment.errorMessage,
                    statusCode = statusCode ?: lastSegment.statusCode,
                )
            } else {
                flushWork(segments, work)
                segments += ForgeSegment.Retry(
                    id = id,
                    attempt = attempt,
                    errorMessage = errorMessage,
                    statusCode = statusCode,
                )
            }
        }

        // 助手侧的附件 part 不单独成行（上下文文件由工具行体现），用户侧在 ForgeUserRow 处理。
        is ForgePart.Attachment -> Unit

        is ForgePart.Other -> Unit
    }
}

/**
 * 影响某个 part 切段结果的全部字段做成的签名（3.4.43 N9）。
 * String.hashCode 首次计算后由 String 自己缓存，重复取是 O(1)，所以拼这个签名
 * 比重新造一遍 UI 对象便宜得多；字段一旦有变化签名必变，不会出现「该重算没重算」。
 */
private fun ForgePart.cacheSignature(): String = when (this) {
    is ForgePart.Reasoning -> "r:$id:${text.hashCode()}:${text.length}:$ended"
    is ForgePart.Text -> "t:$id:${text.hashCode()}:${text.length}:$ended"
    is ForgePart.Tool ->
        "k:$id:$tool:$status:${title?.hashCode()}:${input?.hashCode()}:${output?.hashCode()}:${error?.hashCode()}"

    is ForgePart.Retry -> "y:$id:$attempt:${errorMessage?.hashCode()}:$statusCode"
    is ForgePart.Attachment -> "a:$id"
    is ForgePart.Other -> "o:$id"
}

/**
 * 一轮内的分段缓存（3.4.43 N9）：只重算「最后一个 part」，前面的 part 复用上次的折叠状态。
 * 流式期间每个 delta 只让最后一个 part 变长，所以每个 delta 的代价从「整轮全部 part」
 * 降到「一个 part」。缓存键是前面各 part 的签名列表，任何不在这条 tail 上的变化都会重算。
 */
private class ForgeSegmentCache {
    private var headKey: List<String> = emptyList()
    private var headSegments: List<ForgeSegment> = emptyList()
    private var headWork: List<AgentChatMessageUi> = emptyList()

    fun segments(parts: List<ForgePart>): List<ForgeSegment> {
        if (parts.isEmpty()) {
            headKey = emptyList()
            headSegments = emptyList()
            headWork = emptyList()
            return emptyList()
        }
        val head = parts.subList(0, parts.size - 1)
        val key = head.map { it.cacheSignature() }
        if (key != headKey) {
            val segments = mutableListOf<ForgeSegment>()
            val work = mutableListOf<AgentChatMessageUi>()
            head.forEach { it.appendSegment(segments, work) }
            headKey = key
            headSegments = segments.toList()
            headWork = work.toList()
        }
        val segments = headSegments.toMutableList()
        val work = headWork.toMutableList()
        parts.last().appendSegment(segments, work)
        flushWork(segments, work)
        return segments
    }
}

/**
 * 转录分组缓存（3.4.43 N9）：分组只按「消息 id + 角色」切，最后一轮之后不会再变。
 * 流式期间把最后一个用户消息之前的轮次整批复用（对象相同 → LazyColumn 里那几项
 * 参数相等，Compose 直接跳过重组，连分段都不会重算），只重算最后一轮。
 */
private class ForgeTurnGroupCache {
    private var prefixKey: List<String> = emptyList()
    private var prefixTurns: List<ForgeTurn> = emptyList()

    fun group(messages: List<ForgeChatMessage>): List<ForgeTurn> {
        if (messages.isEmpty()) return emptyList()
        // 最后一轮 = 最后一个用户消息起（一条用户消息都没有时整段都算最后一轮）。
        val lastUserIndex = messages.indexOfLast { it.role == ForgeRole.User }
        val prefixEnd = if (lastUserIndex >= 0) lastUserIndex else 0
        val key = messages.subList(0, prefixEnd).map { "${it.id}:${it.role}" }
        if (key != prefixKey) {
            prefixKey = key
            prefixTurns = messages.subList(0, prefixEnd).groupIntoTurns()
        }
        return prefixTurns + messages.subList(prefixEnd, messages.size).groupIntoTurns()
    }
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
