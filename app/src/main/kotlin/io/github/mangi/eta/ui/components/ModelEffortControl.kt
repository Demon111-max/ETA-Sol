package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 仿旧 Sol（Codex 式）的合并控件：一颗药丸同时管「选模型」与「思考档位」。
 *
 * 交互照 Sol 原版：点药丸先开档位浮层（大字档位词 + 5 档滑块），浮层右上角是当前模型名，
 * 点它跳到模型浮层（分组列表、当前项打勾，复用既有 ModelSheetContent）。
 *
 * 防冲突口径（与设置页的模型能力声明不许打架）：
 * - 设置页管能力上限：滑块只能停在 [availableEfforts] 允许的档上，没勾的档不存在于滑块；
 * - 本控件只写「当前对话选哪档」，从不回写能力字段；
 * - 药丸上显示的档位名必须等于归一化后真正生效的档：调用方传入的 [effort] 已过
 *   ModelReasoningCapabilities.normalize()，这里只做档位词映射，不再二次发明档位；
 * - DEFAULT（没选过）时药丸显示「默认」、滑块停在 High 兜底位（Sol 原版兜底就是 high），
 *   用户一拖一放才落成具体档；OFF（思考被关）时药丸只剩模型名，进浮层拖动任一档即重新开启。
 */
@Composable
internal fun ModelEffortControl(
    modelPickerState: AgentModelPickerUiState?,
    onModelSelected: (String) -> Unit,
    effort: ReasoningEffort,
    availableEfforts: List<ReasoningEffort>,
    enabled: Boolean,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gears = remember(availableEfforts) { solEffortGears(availableEfforts) }
    var sheet by remember { mutableStateOf(ModelEffortSheet.None) }
    val modelName = modelPickerState?.selectedModel?.displayName
        ?: stringResource(R.string.model_not_selected)
    val gearLabel = when {
        effort == ReasoningEffort.OFF -> null
        effort == ReasoningEffort.DEFAULT -> stringResource(R.string.effort_default_label)
        else -> solGearLabel(effort)
    }
    val canPickEffort = enabled && gears.isNotEmpty() && modelPickerState != null
    val canPickModel = enabled && modelPickerState != null &&
        modelPickerState.providerGroups.isNotEmpty()
    val clickable = canPickEffort || canPickModel

    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val ink = colors.onSurface.copy(alpha = 1f)
    val active = effort != ReasoningEffort.OFF
    val contentColor = if (active) colors.primary else ink.copy(alpha = if (isDark) 0.58f else 0.54f)
    val bgColor = if (active) {
        colors.primary.copy(alpha = if (isDark) 0.18f else 0.10f)
    } else {
        ink.copy(alpha = if (isDark) 0.06f else 0.038f)
    }
    val borderColor = if (active) {
        colors.primary.copy(alpha = if (isDark) 0.30f else 0.22f)
    } else {
        ink.copy(alpha = if (isDark) 0.10f else 0.065f)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val description = stringResource(R.string.chat_reasoning_effort, gearLabel ?: modelName)

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 210.dp)
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(bgColor, CircleShape)
                .border(0.5.dp, borderColor, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = clickable,
                    role = Role.Button,
                    onClick = {
                        // 照 Sol：点药丸先进档位浮层；一档都没有（思考不支持/被关）才直达模型浮层。
                        sheet = if (canPickEffort) {
                            ModelEffortSheet.Effort
                        } else {
                            ModelEffortSheet.Model
                        }
                    },
                )
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            ShrinkingPillText(
                text = if (gearLabel != null) "$modelName · $gearLabel" else modelName,
                color = contentColor,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
        }
    }

    if (modelPickerState != null) {
        ModelEffortSheetContainer(
            show = sheet == ModelEffortSheet.Effort,
            title = stringResource(R.string.effort_sheet_title),
            onDismiss = { sheet = ModelEffortSheet.None },
        ) {
            EffortSheetContent(
                gears = gears,
                effort = effort,
                modelName = modelName,
                onOpenModels = { sheet = ModelEffortSheet.Model },
                onEffortChange = { picked ->
                    onEffortChange(picked)
                    sheet = ModelEffortSheet.None
                },
            )
        }
        ModelEffortSheetContainer(
            show = sheet == ModelEffortSheet.Model,
            title = stringResource(R.string.model_picker_title),
            onDismiss = { sheet = ModelEffortSheet.None },
        ) {
            ModelSheetContent(
                state = modelPickerState,
                onModelSelected = { modelId ->
                    sheet = ModelEffortSheet.None
                    onModelSelected(modelId)
                },
            )
        }
    }
}

private enum class ModelEffortSheet { None, Model, Effort }

/** 药丸文字：放不下就缩字号，不省略（Sol 原版口径）。 */
@Composable
private fun ShrinkingPillText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var fontSize by remember(text) { mutableStateOf(12.5.sp) }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(
            fontSize = fontSize,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = color,
        maxLines = 1,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > 9.5.sp) {
                fontSize = (fontSize.value - 1f).sp
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun EffortSheetContent(
    gears: List<ReasoningEffort>,
    effort: ReasoningEffort,
    modelName: String,
    onOpenModels: () -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 滑块初始停靠：当前档的位置；DEFAULT 兜底 High（Sol 原版）；OFF 停第一档（拖动即开启）。
    val initialIndex = remember(gears, effort) {
        solGearIndex(effort, gears).coerceIn(0, (gears.size - 1).coerceAtLeast(0))
    }
    var previewIndex by remember(gears, effort) { mutableStateOf(initialIndex) }
    var interacted by remember(gears, effort) { mutableStateOf(false) }
    val bigWord = when {
        effort == ReasoningEffort.OFF && !interacted -> stringResource(R.string.effort_off_label)
        effort == ReasoningEffort.DEFAULT && !interacted -> stringResource(R.string.effort_default_label)
        gears.isEmpty() -> ""
        else -> solGearLabel(gears[previewIndex]) ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = bigWord,
                transitionSpec = {
                    (slideInVertically(tween(180)) { it } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -it } + fadeOut(tween(120)))
                },
                label = "effort_big_word",
            ) { word ->
                Text(
                    text = word,
                    style = MiuixTheme.textStyles.title3.copy(
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.onSurface,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // 右上角模型跳转钮：点它去模型浮层（Sol 原版）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onOpenModels)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = modelName,
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.primary,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = colors.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        if (gears.size >= 2) {
            EffortSlider(
                gears = gears,
                selectedIndex = previewIndex,
                onPreviewChange = { index ->
                    interacted = true
                    previewIndex = index
                },
                onRelease = { index ->
                    val picked = gears[index]
                    if (picked != effort) onEffortChange(picked)
                },
            )
        } else if (gears.size == 1) {
            // 只剩一档时滑块无从拖：给一个明确的选用钮，尤其当前是 OFF 时要能重新开启。
            val only = gears[0]
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.12f), CircleShape)
                    .border(0.5.dp, colors.primary.copy(alpha = 0.25f), CircleShape)
                    .clickable(role = Role.Button) { onEffortChange(only) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = solGearLabel(only) ?: "",
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

/**
 * Sol 原版 5 档滑块：手柄 29dp、轨道两侧留白 14.5dp（正好半个手柄，手柄中心对准刻度）、
 * 回弹弹簧 stiffness 560 / damping 34 / mass 0.7（换算 dampingRatio ≈ 0.86）、
 * 拖动时手柄放大 1.15（弹簧 700/30 → dampingRatio ≈ 0.57）、过刻度震动、松手吸附最近档才回调。
 */
@Composable
private fun EffortSlider(
    gears: List<ReasoningEffort>,
    selectedIndex: Int,
    onPreviewChange: (Int) -> Unit,
    onRelease: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val segments = (gears.size - 1).coerceAtLeast(1)
    val fraction = remember { Animatable(selectedIndex.toFloat() / segments) }
    var trackWidthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val knobScale by animateFloatAsState(
        targetValue = if (dragging) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.57f, stiffness = 700f),
        label = "effort_knob_scale",
    )
    LaunchedEffect(selectedIndex, dragging) {
        if (!dragging) {
            fraction.animateTo(
                targetValue = selectedIndex.toFloat() / segments,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
            )
        }
    }
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface.copy(alpha = 1f)
    val knobSize = 29.dp
    val railPadding = 14.5.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(knobSize)
            .onSizeChanged { trackWidthPx = it.width }
            .pointerInput(gears) {
                // 轻点轨道直接跳到最近档（与拖动松手同口径）：只认拖动时点按毫无反应，手感缺一截。
                detectTapGestures { offset ->
                    if (trackWidthPx <= 0) return@detectTapGestures
                    val railPaddingPx = with(density) { railPadding.toPx() }
                    val usable = (trackWidthPx - railPaddingPx * 2).coerceAtLeast(1f)
                    val tapped = ((offset.x - railPaddingPx) / usable).coerceIn(0f, 1f)
                    val index = (tapped * segments).roundToInt().coerceIn(0, gears.lastIndex)
                    scope.launch {
                        fraction.animateTo(
                            targetValue = index.toFloat() / segments,
                            animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
                        )
                    }
                    onPreviewChange(index)
                    onRelease(index)
                }
            }
            .pointerInput(gears) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        val index = (fraction.value * segments).roundToInt()
                            .coerceIn(0, gears.lastIndex)
                        scope.launch {
                            fraction.animateTo(
                                targetValue = index.toFloat() / segments,
                                animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
                            )
                        }
                        onRelease(index)
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    if (trackWidthPx <= 0) return@detectDragGestures
                    val railPaddingPx = with(density) { railPadding.toPx() }
                    val usable = (trackWidthPx - railPaddingPx * 2).coerceAtLeast(1f)
                    val next = ((change.position.x - railPaddingPx) / usable).coerceIn(0f, 1f)
                    scope.launch { fraction.snapTo(next) }
                    val index = (next * segments).roundToInt().coerceIn(0, gears.lastIndex)
                    if (index != selectedIndex) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onPreviewChange(index)
                }
            },
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = railPadding)
                .height(4.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.12f)),
        )
        // 刻度点
        gears.indices.forEach { index ->
            val tickFraction = index.toFloat() / segments
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val railPaddingPx = with(density) { railPadding.toPx() }
                        val usable = (trackWidthPx - railPaddingPx * 2).coerceAtLeast(0f)
                        IntOffset(
                            x = (railPaddingPx + usable * tickFraction - with(density) { 2.dp.toPx() }).roundToInt(),
                            y = 0,
                        )
                    }
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.25f)),
            )
        }
        // 手柄：中心落在 fraction 对应的刻度上。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val railPaddingPx = with(density) { railPadding.toPx() }
                    val usable = (trackWidthPx - railPaddingPx * 2).coerceAtLeast(0f)
                    val knobPx = with(density) { knobSize.toPx() }
                    IntOffset(
                        x = (railPaddingPx + usable * fraction.value - knobPx / 2).roundToInt(),
                        y = 0,
                    )
                }
                .size(knobSize)
                .graphicsLayer {
                    scaleX = knobScale
                    scaleY = knobScale
                }
                .clip(CircleShape)
                .background(colors.primary)
                .border(0.5.dp, ink.copy(alpha = 0.08f), CircleShape),
        )
    }
}

/** 底部浮层容器：COUI 走既有底部 Dialog，Miuix 走 WindowBottomSheet，与顶栏旧模型浮层同款。 */
@Composable
private fun ModelEffortSheetContainer(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            Dialog(
                visible = show,
                onDismissRequest = onDismiss,
                dark = dark,
                title = title,
                confirmAction = CouiDialogAction(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                ),
                placement = CouiDialogPlacement.Bottom,
                material = rememberCouiDialogMaterial(dark),
                backdrop = rememberCouiWindowBackdrop(),
            ) {
                content()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowBottomSheet(
                show = show,
                title = title,
                onDismissRequest = onDismiss,
                insideMargin = DpSize(0.dp, 0.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * Sol 5 档与 Eta 档位的对应：Light=Low（Minimal 并入 Light）、Medium、High、Extra High=XHigh、Max。
 * 只保留 [availableEfforts] 里有的档（设置页没勾的档不许停）。
 * 空列表就是「没有档可选」——Assist 的空列表语义是能力为 null（思考关/不支持），
 * Forge 的「能力未知」由调用方传非空的回落档位表来表达；控件不许把空列表自行解释成放行 5 档，
 * 否则 Assist 里选了档也会被状态层归一化吞掉，成了能点不能用的假入口（3.4.45 审计实证）。
 */
private fun solEffortGears(availableEfforts: List<ReasoningEffort>): List<ReasoningEffort> {
    if (availableEfforts.isEmpty()) return emptyList()
    return buildList {
        when {
            ReasoningEffort.LOW in availableEfforts -> add(ReasoningEffort.LOW)
            ReasoningEffort.MINIMAL in availableEfforts -> add(ReasoningEffort.MINIMAL)
        }
        if (ReasoningEffort.MEDIUM in availableEfforts) add(ReasoningEffort.MEDIUM)
        if (ReasoningEffort.HIGH in availableEfforts) add(ReasoningEffort.HIGH)
        if (ReasoningEffort.XHIGH in availableEfforts) add(ReasoningEffort.XHIGH)
        if (ReasoningEffort.MAX in availableEfforts) add(ReasoningEffort.MAX)
    }
}

/** 档位词照 Sol 原版英文（Light / Medium / High / Extra High / Max）。 */
private fun solGearLabel(effort: ReasoningEffort): String? = when (effort) {
    ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "Light"
    ReasoningEffort.MEDIUM -> "Medium"
    ReasoningEffort.HIGH -> "High"
    ReasoningEffort.XHIGH -> "Extra High"
    ReasoningEffort.MAX -> "Max"
    ReasoningEffort.OFF, ReasoningEffort.DEFAULT -> null
}

/** 当前档在滑块上的位置；DEFAULT 兜底 High 的位置（Sol 原版），没有 High 就取最接近的一档。 */
private fun solGearIndex(effort: ReasoningEffort, gears: List<ReasoningEffort>): Int {
    if (gears.isEmpty()) return 0
    val direct = when (effort) {
        ReasoningEffort.MINIMAL, ReasoningEffort.LOW ->
            gears.indexOfFirst { it == ReasoningEffort.LOW || it == ReasoningEffort.MINIMAL }
        ReasoningEffort.MEDIUM -> gears.indexOf(ReasoningEffort.MEDIUM)
        ReasoningEffort.HIGH, ReasoningEffort.DEFAULT -> gears.indexOf(ReasoningEffort.HIGH)
        ReasoningEffort.XHIGH -> gears.indexOf(ReasoningEffort.XHIGH)
        ReasoningEffort.MAX -> gears.indexOf(ReasoningEffort.MAX)
        ReasoningEffort.OFF -> -1
    }
    if (direct >= 0) return direct
    // 目标档不在滑块上（被设置页能力挡掉）：按 rank 取最接近的一档，与 normalize() 的收敛方向一致。
    val targetRank = effort.rank
    return gears.indices.minByOrNull { index ->
        kotlin.math.abs(gears[index].rank - targetRank)
    } ?: 0
}
