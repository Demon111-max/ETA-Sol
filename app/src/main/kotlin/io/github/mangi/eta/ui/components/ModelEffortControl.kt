package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
 * 交互与长相都照 Sol 原版（数值摘自 Sol 仓库的 Sheets.tsx、App.tsx、app.css 与 tokens.css，
 * 不许用系统组件默认长相顶替，也不许猜数）：
 * - 浮层是 270dp 宽的小卡片：圆角 18dp（--r-sheet），投影照 --shadow-sheet 的两层叠画，
 *   边框 1dp；落位按 Sol 的锚点公式——底边贴住药丸上沿（间隙 5dp）、右边缘与输入卡片右边缘对齐，
 *   模型浮层最高到状态栏下（药丸上沿 − 5 − 8），超出在卡片内部滚动；
 * - 出现/消失：弹簧 stiffness 480 / damping 34 / mass 0.7（dampingRatio ≈ 0.93），
 *   进场从下移 10dp、缩放 0.97、透明度 0 起，退场到下移 8dp、缩放 0.98、透明度 0，
 *   缩放中心在卡片右下角；背后一层 rgba(13,13,13,0.06) 遮罩，160ms 淡入淡出，点击关闭；
 * - 两个浮层之间切换：内容横滑 8dp + 淡入淡出，160ms，缓动 (0.16, 1, 0.3, 1)；
 * - 档位浮层：高 104dp，头部上下两行居中（档位词 15sp 半粗强调色、180ms 上下滑动在上，
 *   模型名 13sp 灰字带 11dp 小箭头在下，点它跳模型浮层）；滑块轨道是 25dp 粗的浅灰胶囊，
 *   强调色填充从左长到手柄处，4dp 刻度点画在轨道里面，手柄是 29dp 白圆钮，
 *   阴影照 --shadow-knob（0 1px 4px rgba(15,15,15,0.22) + 1px 圈 rgba(15,15,15,0.04)）；
 *   手柄弹簧 stiffness 560 / damping 34 / mass 0.7（dampingRatio ≈ 0.86），拖动放大 1.15、
 *   过刻度震动、松手吸附最近档才回调，轻点轨道直接跳档；
 * - 模型浮层：顶部提示行 "Select model"（高 28dp、10sp、灰字），下面是拍平的模型行
 *   （最小高 34dp、11.5sp，模型名完整显示，后跟 "·" + 分组，分组放不下省略，当前行右侧打勾），
 *   数据仍读同一份 [AgentModelPickerUiState]、仍走同一个 onModelSelected，不另起数据源。
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
    // 输入卡片右边缘在窗口中的横坐标（px）：浮层右边缘要与它对齐（Sol 锚点公式）。
    // 由输入栏量好传入；为 0（还没量到）时退回与药丸右边缘对齐。
    sheetRightEdgeWindowPx: Int = 0,
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

    val density = LocalDensity.current
    var anchorLeftPx by remember { mutableStateOf(0) }
    var anchorTopPx by remember { mutableStateOf(0) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    // 模型浮层高度上限（Sol 锚点公式）：药丸上沿 − 间隙 5 − 状态栏下沿 − 8，最小 120dp。
    val modelSheetMaxHeight = with(density) {
        ((anchorTopPx - statusBarTopPx).coerceAtLeast(0).toDp() - 5.dp - 8.dp)
            .coerceAtLeast(120.dp)
    }

    // 浮层进出场：renderedSheet 是实际在画的那张（退场动画播完才撤），sheet 是目标状态。
    var renderedSheet by remember { mutableStateOf(ModelEffortSheet.None) }
    var exiting by remember { mutableStateOf(false) }
    val sheetProgress = remember { Animatable(0f) }
    LaunchedEffect(sheet) {
        if (sheet != ModelEffortSheet.None) {
            renderedSheet = sheet
            exiting = false
            sheetProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.93f, stiffness = 480f),
            )
        } else if (renderedSheet != ModelEffortSheet.None) {
            exiting = true
            sheetProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.93f, stiffness = 480f),
            )
            if (sheet == ModelEffortSheet.None) {
                renderedSheet = ModelEffortSheet.None
            }
        }
    }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (sheet != ModelEffortSheet.None) 1f else 0f,
        animationSpec = tween(160),
        label = "sheet_scrim_alpha",
    )

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val pos = coordinates.positionInWindow()
            anchorLeftPx = pos.x.roundToInt()
            anchorTopPx = pos.y.roundToInt()
            anchorWidthPx = coordinates.size.width
            anchorHeightPx = coordinates.size.height
        },
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
                        // 照 Sol：点药丸先进档位浮层，再点一次同一入口收起；
                        // 一档都没有（思考不支持/被关）才直达模型浮层。
                        val target = if (canPickEffort) {
                            ModelEffortSheet.Effort
                        } else {
                            ModelEffortSheet.Model
                        }
                        sheet = if (sheet == target) ModelEffortSheet.None else target
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

        if (modelPickerState != null && renderedSheet != ModelEffortSheet.None) {
            // 遮罩：rgba(13,13,13,0.06)，160ms 淡入淡出，点击关闭（Sol .sheet-backdrop）。
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { sheet = ModelEffortSheet.None },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0D0D).copy(alpha = 0.06f * scrimAlpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { sheet = ModelEffortSheet.None },
                )
            }
            // 浮层卡片：右边缘对齐输入卡片右边缘，底边在药丸上沿上方 5dp（Sol 锚点公式）。
            val anchorRightPx = anchorLeftPx + anchorWidthPx
            val shiftX = if (sheetRightEdgeWindowPx > 0) {
                sheetRightEdgeWindowPx - anchorRightPx
            } else {
                0
            }
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(
                    x = shiftX,
                    y = -(anchorHeightPx + with(density) { 5.dp.roundToPx() }),
                ),
                onDismissRequest = { sheet = ModelEffortSheet.None },
                properties = PopupProperties(focusable = true),
            ) {
                val progress = sheetProgress.value
                val translateYDp = if (exiting) {
                    (1f - progress) * 8f
                } else {
                    (1f - progress) * 10f
                }
                val scale = if (exiting) {
                    0.98f + 0.02f * progress
                } else {
                    0.97f + 0.03f * progress
                }
                SolSheetCard(
                    modifier = Modifier.graphicsLayer {
                        alpha = progress
                        translationY = with(density) { translateYDp.dp.toPx() }
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(1f, 1f)
                    },
                ) {
                    AnimatedContent(
                        targetState = renderedSheet,
                        transitionSpec = {
                            // 去模型浮层：内容从右 8dp 滑入、旧内容向右 8dp 滑出；回档位浮层镜像（Sol 原版方向）。
                            val dir = if (targetState == ModelEffortSheet.Model) 1 else -1
                            val slidePx = with(density) { 8.dp.roundToPx() }
                            (slideInHorizontally(tween(160, easing = SheetSwitchEase)) { dir * slidePx } +
                                fadeIn(tween(160)))
                                .togetherWith(
                                    slideOutHorizontally(tween(160, easing = SheetSwitchEase)) { dir * slidePx } +
                                        fadeOut(tween(160)),
                                )
                        },
                        label = "sheet_content_switch",
                    ) { current ->
                        when (current) {
                            ModelEffortSheet.Effort -> EffortSheetContent(
                                gears = gears,
                                effort = effort,
                                modelName = modelName,
                                onOpenModels = { sheet = ModelEffortSheet.Model },
                                // 选档不收浮层（Sol 原版口径）：松手只落档，
                                // 收起只走点遮罩、点药丸或返回键。
                                onEffortChange = { picked ->
                                    onEffortChange(picked)
                                },
                            )
                            ModelEffortSheet.Model -> SolModelList(
                                state = modelPickerState,
                                maxHeight = modelSheetMaxHeight,
                                onModelSelected = { modelId ->
                                    sheet = ModelEffortSheet.None
                                    onModelSelected(modelId)
                                },
                            )
                            ModelEffortSheet.None -> Box(modifier = Modifier.size(1.dp))
                        }
                    }
                }
            }
        }
    }
}

private enum class ModelEffortSheet { None, Model, Effort }

/** 浮层切换缓动（Sol 原版 cubic-bezier(0.16, 1, 0.3, 1)）。 */
private val SheetSwitchEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * Sol 浮层卡片：宽 270dp、圆角 18dp（--r-sheet）、边框 1dp。
 * 投影照 --shadow-sheet 的两层：外层 0 14px 40px rgba(15,15,15,0.11)、内层 0 2px 8px rgba(15,15,15,0.05)，
 * Compose 的单值 elevation 画不出双层，用两个嵌套 shadow 近似（大 elevation 承第一层、小 elevation 承第二层）。
 */
@Composable
private fun SolSheetCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(18.dp)
    val shadowInk = Color(0xFF0F0F0F)
    Box(
        modifier = modifier
            .width(270.dp)
            .shadow(
                elevation = 20.dp,
                shape = shape,
                ambientColor = shadowInk.copy(alpha = 0.11f),
                spotColor = shadowInk.copy(alpha = 0.11f),
            ),
    ) {
        Box(
            modifier = Modifier
                .shadow(
                    elevation = 4.dp,
                    shape = shape,
                    ambientColor = shadowInk.copy(alpha = 0.05f),
                    spotColor = shadowInk.copy(alpha = 0.05f),
                )
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
}

/**
 * 模型浮层内容，照 Sol 的 ModelSheet（Sheets.tsx）与 .sheet--model / .modelrow / .sheet__hint（app.css）：
 * 卡片上下内边距 6dp；提示行 "Select model" 高 28dp、10sp、灰字、左右 12dp；
 * 模型行最小高 34dp、左右 12dp、11.5sp：模型名完整显示，后跟灰色 "·"（左右各 4dp）+ 分组名（放不下省略），
 * 当前选中行右侧 32dp 宽的格子里打勾（14dp）。点行即选中并收起。
 * 数据只读传入的 [state]、选择只走 onModelSelected，与设置页/顶栏共用同一份选择口径，不另起一份。
 */
@Composable
private fun SolModelList(
    state: AgentModelPickerUiState,
    maxHeight: Dp,
    onModelSelected: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val gray = colors.onSurface.copy(alpha = 0.55f)
    val rows = remember(state.providerGroups) {
        state.providerGroups.flatMap { group ->
            group.models.map { model -> group.providerName to model }
        }
    }
    Column(
        modifier = Modifier
            .width(270.dp)
            .heightIn(max = maxHeight)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Select model",
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.1.sp,
                ),
                color = gray,
                maxLines = 1,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(rows, key = { "${it.second.providerId}/${it.second.id}" }) { (providerName, model) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .clickable(role = Role.Button) { onModelSelected(model.id) }
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp),
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "·",
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp),
                        color = gray,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Text(
                        text = providerName,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp),
                        color = gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (model.id == state.selectedModel?.id) {
                        Box(
                            modifier = Modifier.width(32.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.onSurface,
                            )
                        }
                    }
                }
            }
        }
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
    val density = LocalDensity.current
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
                    // 滑动行程照 Sol 原版是 ±10dp（不是整词高度）；且不许裁剪，
                    // 词在滑动中要完整露出（Sol 的 .effort__value 就是 overflow: visible）。
                    val travel = with(density) { 10.dp.roundToPx() }
                    (slideInVertically(tween(180)) { travel } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -travel } + fadeOut(tween(120)))
                },
                sizeTransform = SizeTransform(clip = false) { _, _ -> tween(180) },
                label = "effort_big_word",
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
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
 * 手柄是 29dp 白圆钮，在内缩 14.5dp 的轨道里走（圆心正对刻度），阴影照 --shadow-knob：
 * 0 1px 4px rgba(15,15,15,0.22) 用一层小 elevation 近似，外加 1px 圈 rgba(15,15,15,0.04) 用描边落。
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
    // 震动口径：手柄真正跨进一个新档位时才震一次。手势回调里必须读最新档位，
    // 不能用 pointerInput 捕获的旧值——旧值会让「停在别的档区间里」被反复判定成跨档、动一下就震。
    val currentIndex by rememberUpdatedState(selectedIndex)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(45.dp)
            .onGloballyPositioned { trackWidthPx = it.size.width }
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
                    if (index != currentIndex) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                    if (index != currentIndex) {
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
        // 手柄：白圆钮，圆心落在 fraction 对应的刻度上；阴影照 --shadow-knob 两层落。
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
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFF0F0F0F).copy(alpha = 0.22f),
                    spotColor = Color(0xFF0F0F0F).copy(alpha = 0.22f),
                )
                .clip(CircleShape)
                .background(if (isDark) Color(0xFFF5F5F5) else Color.White)
                .border(1.dp, Color(0x0A0F0F0F), CircleShape),
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
