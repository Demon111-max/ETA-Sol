package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.shape.CouiCapsuleShape
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一轮里的思考与工具调用按 DeepSeek Harness 的形态逐行呈现：每条思考、每次工具
 * 调用都是独立的 24dp 披露行（dsh 图标 + 标题 · 摘要），点行展开看详情。
 * 进行中的轮次没有汇总头；轮次结束后出现一行汇总头（带底边发丝线），可折叠整段过程。
 */
@Composable
internal fun AgentWorkProcess(
    id: String,
    messages: List<AgentChatMessageUi>,
    assistantOverlay: Boolean = false,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    failed: Boolean = false,
) {
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    val steps = remember(messages) { messages.toWorkSteps() }
    // null 表示自动模式；用户点过之后只认手动选择。
    var manualExpanded by rememberSaveable(id) { mutableStateOf<Boolean?>(null) }

    // 失败的轮次默认展开（dsh 的失败收尾就是摊开的：汇总头「处理失败」+ 过程行 + 错误行），
    // 其余时候以“是否仍是本轮末尾的工作过程”判定收起，而不是 running：思考结束到工具开始之间
    // running 会短暂为 false，按它收起会造成反复开合。展开态在组合期同步推导，与正文
    // 条目插入落在同一帧，不经 LaunchedEffect 晚一帧收起。语音浮层空间有限，自动模式不展开。
    val bodyVisible = manualExpanded ?: ((active || failed) && !assistantOverlay)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (assistantOverlay) 16.dp else 20.dp, vertical = 2.dp),
    ) {
        // dsh 形态：进行中的轮次没有汇总头，行直接出现在流里；轮次结束后
        // 汇总头出现且过程默认收起，点汇总头展开整段。
        if (!active) {
            AgentWorkHeader(
                messages = messages,
                working = running,
                failed = failed,
                expanded = bodyVisible,
                onClick = { manualExpanded = !bodyVisible },
            )
        }
        AnimatedVisibility(
            visible = bodyVisible,
            enter = dshFoldEnter(),
            exit = dshFoldExit(),
        ) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                steps.forEach { step ->
                    key(step.key) {
                        AgentWorkStepNode(
                            step = step,
                            onOpenBrowser = onOpenBrowser,
                            currentBrowserMessageId = currentBrowserMessageId,
                            retainedStreamingStates = retainedStreamingStates,
                        )
                    }
                }
            }
        }
    }
}

/**
 * dsh 的 turn-process 汇总头：24dp 一行 14sp 弱色文字 + 末尾 14dp chevron-down
 * （展开时旋转 180°，100ms），下方 8dp 间隔后一条 0.5dp 发丝线。只在轮次结束后出现。
 */
@Composable
private fun AgentWorkHeader(
    messages: List<AgentChatMessageUi>,
    working: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    failed: Boolean = false,
) {
    val runningTool = messages.lastOrNull { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    } as? ToolActivityMessageUi
    val summary = remember(messages) { messages.workSummary() }
    val failedText = stringResource(R.string.forge_turn_failed_header)
    val text = when {
        failed -> failedText
        runningTool != null -> pluralStringResource(
            R.plurals.work_processing_step,
            summary.toolCount,
            summary.toolCount,
        ) + " · " + runningTool.argumentsSummary.ifBlank { toolDisplayName(runningTool.toolName) }
        working -> stringResource(R.string.work_analyzing)
        else -> workSummaryText(summary)
    }
    val colors = MiuixTheme.colorScheme
    val tertiary = dshTertiaryColor()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(100),
        label = "work_header_chevron",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                // 汇总头整行可点，不给按压底色（与对话区其他行同一口径）。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerText(
                text = text,
                active = working,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                color = tertiary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = DshChevronDownIcon(),
                contentDescription = stringResource(
                    if (expanded) R.string.work_collapse else R.string.work_expand,
                ),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(14.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
                tint = tertiary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(0.5.dp)
                .background(colors.outline.copy(alpha = 0.45f)),
        )
    }
}

@Composable
private fun workSummaryText(summary: AgentWorkSummary): String {
    val parts = buildList {
        if (summary.thinkingCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_thinking, summary.thinkingCount, summary.thinkingCount))
        }
        if (summary.toolCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_tools, summary.toolCount, summary.toolCount))
        }
        if (summary.failedCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_failed, summary.failedCount, summary.failedCount))
        }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.work_completed) }
}

@Composable
private fun AgentWorkStepNode(
    step: AgentWorkStep,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
) {
    when (step) {
        is AgentWorkStep.ToolGroup -> ToolGroupRow(group = step, onOpenBrowser = onOpenBrowser)

        is AgentWorkStep.Single -> when (val message = step.message) {
            is ToolActivityMessageUi -> ToolActivityRow(
                message = message,
                onOpenBrowser = onOpenBrowser,
                showBrowserShortcut = message.id == currentBrowserMessageId,
            )

            is ThinkingMessageUi -> ThinkingRow(
                message = message,
                retainedStreamingState = retainedStreamingStates[message.id],
                compact = true,
            )

            is ToolSummaryMessageUi -> ChatMessageItem(
                message = message,
                onSuggestionClick = {},
                onRunTraceClick = {},
                onOpenBrowser = onOpenBrowser,
                showBrowserShortcut = false,
                compact = true,
            )

            else -> Unit
        }
    }
}

internal enum class WorkNodeTone { Running, Done, Failed }

internal fun ToolActivityStatusUi.nodeTone(): WorkNodeTone = when (this) {
    ToolActivityStatusUi.Running -> WorkNodeTone.Running
    ToolActivityStatusUi.Failed -> WorkNodeTone.Failed
    ToolActivityStatusUi.Success, ToolActivityStatusUi.Unknown -> WorkNodeTone.Done
}

/**
 * 时间线节点：左侧固定宽度的槽位放轻量状态图标，细导引线在绘制阶段画出，不参与测量。
 * 已完成节点去掉灰底圆球外壳以降低视觉噪音；仅运行或失败态保留柔和光晕。
 */
@Composable
internal fun WorkTimelineNode(
    icon: ImageVector,
    tone: WorkNodeTone,
    isLast: Boolean,
    connectTop: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val lineColor = colors.onSurface.copy(alpha = if (isDark) 0.12f else 0.085f)
    val hasHalo = tone != WorkNodeTone.Done
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = WorkGutterWidth.toPx() / 2f
                val stroke = 1.dp.toPx()
                val visualSize = (if (hasHalo) WorkNodeHaloSize else WorkDoneIconSize).toPx()
                val gap = (if (hasHalo) 2.5.dp else 3.5.dp).toPx()
                val nodeTop = (WorkRowHeight.toPx() - visualSize) / 2f
                val nodeBottom = nodeTop + visualSize
                if (connectTop && nodeTop - gap > 0f) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, nodeTop - gap),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                if (!isLast && size.height > nodeBottom + gap) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x, nodeBottom + gap),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .width(WorkGutterWidth)
                .height(WorkRowHeight),
            contentAlignment = Alignment.Center,
        ) {
            WorkNodeBadge(icon = icon, tone = tone, isDark = isDark)
        }
        Spacer(modifier = Modifier.width(WorkGutterGap))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun WorkNodeBadge(icon: ImageVector, tone: WorkNodeTone, isDark: Boolean) {
    val colors = MiuixTheme.colorScheme
    val pulse = rememberDrawPhasePulse(active = tone == WorkNodeTone.Running)
    when (tone) {
        WorkNodeTone.Running -> Box(
            modifier = Modifier
                .size(WorkNodeHaloSize)
                .graphicsLayer { alpha = pulse?.value ?: 1f }
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colors.primary,
            )
        }
        WorkNodeTone.Failed -> Box(
            modifier = Modifier
                .size(WorkNodeHaloSize)
                .clip(CircleShape)
                .background(StatusError.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = StatusError,
            )
        }
        WorkNodeTone.Done -> Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(WorkDoneIconSize),
            tint = colors.onSurface.copy(alpha = if (isDark) 0.46f else 0.40f),
        )
    }
}

/** 呼吸动画只返回 State，由调用方在 graphicsLayer 中读取，帧间不触发重组。 */
@Composable
internal fun rememberDrawPhasePulse(active: Boolean): State<Float>? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "work_node_pulse")
    return transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "work_node_pulse_alpha",
    )
}

/**
 * 进行中的标题用一道扫过的高光提示“正在发生”。高光在绘制阶段以 SrcAtop 叠在字形上，
 * 动画只使绘制失效；离屏层限定在这一行文字范围内。
 */
@Composable
internal fun ShimmerText(
    text: String,
    active: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shimmerModifier = if (active) {
        // 高光收一档：以前是纯 onSurface 全强度 + 35% 宽的光带，扫过去像反光板；
        // 压到 0.55 透明、22% 宽，只留一点「活着」的感觉。
        val highlight = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        val transition = rememberInfiniteTransition(label = "work_shimmer")
        val progress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_600, easing = LinearEasing),
            ),
            label = "work_shimmer_progress",
        )
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val band = maxOf(size.width * 0.22f, 48.dp.toPx())
                val center = -band + (size.width + band * 2f) * progress.value
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        startX = center - band,
                        endX = center + band,
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            }
    } else {
        Modifier
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(shimmerModifier),
    )
}

@Composable
internal fun ExpandChevron(
    expanded: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "expand_chevron",
    )
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = contentDescription,
        modifier = modifier
            .size(13.dp)
            .graphicsLayer { rotationZ = rotation },
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
    )
}

/** 时间线标题行与节点槽位共用的几何尺寸，保证图标中心与首行文字中线对齐。 */
internal val WorkRowHeight: Dp = 30.dp
internal val WorkGutterWidth: Dp = 18.dp
internal val WorkGutterGap: Dp = 8.dp
private val WorkNodeHaloSize: Dp = 18.dp
private val WorkDoneIconSize: Dp = 13.5.dp
