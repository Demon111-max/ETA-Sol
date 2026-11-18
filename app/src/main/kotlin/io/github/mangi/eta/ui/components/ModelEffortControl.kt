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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仿旧 Sol（Codex 式）的合并控件：一颗药丸同时管「选模型」与「思考档位」。
 *
 * 交互与长相都照 Sol 原版（数值摘自 Sol 的 Sheets.tsx 与 app.css，不许用系统组件默认长相顶替）：
 * - 点药丸先开档位浮层：270dp 宽、104dp 高的小卡片，贴在药丸正上方右侧浮着，没有标题、没有关闭按钮，
 *   点外面就收；头部上下两行居中——档位词 15sp 半粗强调色在上，模型名 13sp 灰字带小箭头在下，点它跳模型浮层；
 * - 滑块：轨道是 25dp 粗的浅灰胶囊，强调色填充从左长到手柄处，4dp 刻度点画在轨道里面，
 *   手柄是 29dp 白圆钮带阴影；手柄弹簧 stiffness 560 / damping 34 / mass 0.7（dampingRatio ≈ 0.86），
 *   拖动时放大 1.15、过刻度震动、松手吸附最近档才回调，轻点轨道直接跳档；
 * - 模型浮层与档位浮层同宽（270dp），内容复用既有 ModelSheetContent（分组 + 当前项打勾）。
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

    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier.onSizeChanged { anchorSize = it },
    ) {
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

        if (modelPickerState != null && sheet != ModelEffortSheet.None) {
            // Sol 原版浮层：贴在控件右上方的小卡片，点外面收起，没有系统弹窗壳。
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(
                    x = 0,
                    y = -(anchorSize.height + with(density) { 10.dp.roundToPx() }),
                ),
                onDismissRequest = { sheet = ModelEffortSheet.None },
                properties = PopupProperties(focusable = true),
            ) {
                SolSheetCard {
                    when (sheet) {
                        ModelEffortSheet.Effort -> EffortSheetContent(
                            gears = gears,
                            effort = effort,
                            modelName = modelName,
                            onOpenModels = { sheet = ModelEffortSheet.Model },
                            onEffortChange = { picked ->
                                onEffortChange(picked)
                                sheet = ModelEffortSheet.None
                            },
                        )
                        ModelEffortSheet.Model -> Box(
                            modifier = Modifier.heightIn(max = 430.dp),
                        ) {
                            ModelSheetContent(
                                state = modelPickerState,
                                onModelSelected = { modelId ->
                                    sheet = ModelEffortSheet.None
                                    onModelSelected(modelId)
                                },
                            )
                        }
                        ModelEffortSheet.None -> Unit
                    }
                }
            }
        }
    }
}

private enum class ModelEffortSheet { None, Model, Effort }

/** Sol 浮层卡片：270dp 宽、圆角、细边框、柔和投影；底色跟主题表面色（Sol 原版浅色为白）。 */
@Composable
private fun SolSheetCard(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .width(270.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = colors.onSurface.copy(alpha = if (isDark) 0.12f else 0.08f),
                shape = shape,
            ),
    ) {
        content()
    }
}

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

/**
 * 档位浮层内容，几何照 Sol app.css 的 .sheet--effort：
 * 卡片高 104dp、左右内边距 13dp；头部在顶部 13dp 处，上下两行居中（档位词行高 22dp、模型行高 16dp、行距 4dp）；
 * 滑块区顶部在 64dp、轨道高 25dp（手柄触点上下各扩 10dp，与 Sol 的 44dp 触点一致）。
 */
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
    val modelTint = colors.onSurface.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .width(270.dp)
            .height(104.dp)
            .padding(horizontal = 13.dp),
    ) {
        // 头部：档位词（15sp 半粗、强调色、180ms 上下滑动）在上，模型名（13sp 灰字 + 11dp 小箭头）在下，都居中。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 13.dp)
                .height(22.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = bigWord,
                transitionSpec = {
                    (slideInVertically(tween(180)) { it } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -it } + fadeOut(tween(120)))
                },
                label = "effort_big_word",
                contentAlignment = Alignment.Center,
            ) { word ->
                Text(
                    text = word,
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.primary,
                    maxLines = 1,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 39.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = onOpenModels)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = modelName,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = modelTint,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = modelTint,
            )
        }
        // 滑块区：轨道中心在卡片顶部 76.5dp 处（64 + 25/2），触点盒高 45dp（54dp 起）。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 54.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
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
                // 只剩一档时滑块无从拖：居中一颗选用钮，尤其当前是 OFF 时要能重新开启。
                val only = gears[0]
                Box(
                    modifier = Modifier
                        .height(25.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f), CircleShape)
                        .border(0.5.dp, colors.primary.copy(alpha = 0.25f), CircleShape)
                        .clickable(role = Role.Button) { onEffortChange(only) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
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
        }
    }
}

/**
 * Sol 原版 5 档滑块，长相照 app.css 的 .slider：
 * 轨道是 25dp 粗的浅灰（#EBEBEB）胶囊，强调色填充以 scaleX 从左长到手柄处；
 * 5 个 4dp 刻度点画在轨道里面（横向内缩 12dp 等分），已过刻度在填充上呈半透明白、未过为 #C4C4C4；
 * 手柄是 29dp 白圆钮带阴影，在内缩 14.5dp 的轨道里走（圆心正对刻度）。
 * 弹簧 stiffness 560 / dampingRatio ≈ 0.86（原版 damping 34 / mass 0.7），拖动放大 1.15（弹簧 700/30），
 * 过刻度震动、松手吸附最近档才回调，轻点轨道直接跳档。
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
    val isDark = colors.surface.luminance() < 0.5f
    val trackColor = if (isDark) colors.onSurface.copy(alpha = 0.14f) else Color(0xFFEBEBEB)
    val tickIdleColor = if (isDark) colors.onSurface.copy(alpha = 0.35f) else Color(0xFFC4C4C4)
    val knobSize = 29.dp
    val railPadding = 14.5.dp
    val tickPadding = 12.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(45.dp)
            .onSizeChanged { trackWidthPx = it.width }
            .pointerInput(gears) {
                // 轻点轨道直接跳到最近档（与拖动松手同口径）。
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
        // 轨道胶囊 + 填充（填充宽度就是 fraction，与 Sol 的 scaleX 同口径）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(25.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = fraction.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(colors.primary, CircleShape),
            )
        }
        // 刻度点：画在轨道里面，已过刻度压在填充上呈半透明白。
        gears.indices.forEach { index ->
            val tickFraction = index.toFloat() / segments
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val tickPaddingPx = with(density) { tickPadding.toPx() }
                        val usable = (trackWidthPx - tickPaddingPx * 2).coerceAtLeast(0f)
                        IntOffset(
                            x = (tickPaddingPx + usable * tickFraction - with(density) { 2.dp.toPx() }).roundToInt(),
                            y = 0,
                        )
                    }
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= selectedIndex) {
                            Color.White.copy(alpha = 0.62f)
                        } else {
                            tickIdleColor
                        },
                    ),
            )
        }
        // 手柄：白圆钮带阴影，圆心落在 fraction 对应的刻度上。
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
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(if (isDark) Color(0xFFF5F5F5) else Color.White),
        )
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
