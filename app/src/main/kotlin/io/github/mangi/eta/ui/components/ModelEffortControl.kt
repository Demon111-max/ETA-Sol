package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * - 模型浮层：顶部提示行（文案走资源 model_picker_title，高 28dp、10sp、灰字），下面是拍平的模型行
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
    // 浮层关闭时回调一次：浮层是可聚焦弹窗、开着时会把软键盘顶掉，
    // 关闭后由输入栏把焦点还给输入框（3.4.47 审计问题 6）。
    onSheetClosed: () -> Unit = {},
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
    // 读屏文案与药丸文字同口径：一律「模型名 · 档位词」，OFF 用「已关闭」补上「思考已关」
    // 这层意思（3.4.49 审理 P3-4）。此前只有 OFF 态拼了模型名，其余态只剩一个档位词，
    // 同一颗药丸在不同状态下读屏说的事不一样（3.4.50 审理 P3-4）。
    val effortWord = gearLabel ?: stringResource(R.string.effort_off_label)
    val description = stringResource(
        R.string.chat_reasoning_effort,
        "$modelName · $effortWord",
    )

    val density = LocalDensity.current
    var anchorLeftPx by remember { mutableStateOf(0) }
    var anchorTopPx by remember { mutableStateOf(0) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    // 模型浮层高度上限（Sol 锚点公式）：药丸上沿 − 间隙 5 − 状态栏下沿 − 8。
    // 下限 108dp = 提示行 28 + 上下内边距 6 + 6 + 两行模型 34 × 2（3.4.49 审理 P3-5 修正了
    // 原来写 96dp 的算术错误）：3.4.47 问题 19 去掉 120dp 下限是怕把浮层顶出屏幕上沿，
    // 但 0 高会让浮层「开着却看不见」——列表被压没、只剩压暗，用户只能点遮罩关掉。
    // 极端矮窗口里卡片上沿至多压进状态栏 12dp，列表仍可滚动、遮罩/返回仍可关，比不可见强。
    val modelSheetMaxHeight = with(density) {
        ((anchorTopPx - statusBarTopPx).coerceAtLeast(0).toDp() - 5.dp - 8.dp)
            .coerceAtLeast(108.dp)
    }

    // 浮层进出场：renderedSheet 是实际在画的那张（退场动画播完才撤），sheet 是目标状态。
    var renderedSheet by remember { mutableStateOf(ModelEffortSheet.None) }
    var exiting by remember { mutableStateOf(false) }
    val sheetProgress = remember { Animatable(0f) }
    // 选模型确认反馈（旧选择器口径，3.4.47 审计问题 14）：点行先把勾落到新行上，
    // 140ms 后才真正回调并收起，让用户看清自己选中了哪个。
    val sheetScope = rememberCoroutineScope()
    var pickedModelId by remember { mutableStateOf<String?>(null) }
    var pickJob by remember { mutableStateOf<Job?>(null) }
    // 本次收层是不是「被禁用自动收起」引起的：那种情况不该把焦点抢回输入框
    // （流式期间键盘本来就该收着，3.4.49 审理 P3-7）。
    var sheetClosedByDisable by remember { mutableStateOf(false) }
    // 档位↔模型内容切换的 160ms 内，旧内容不许再接手势（审计问题 12），用一层罩子吃掉。
    var contentSwitching by remember { mutableStateOf(false) }
    var lastRenderedSheet by remember { mutableStateOf(ModelEffortSheet.None) }
    LaunchedEffect(renderedSheet) {
        val previous = lastRenderedSheet
        lastRenderedSheet = renderedSheet
        if (previous != ModelEffortSheet.None &&
            renderedSheet != ModelEffortSheet.None &&
            previous != renderedSheet
        ) {
            contentSwitching = true
            delay(170)
            contentSwitching = false
        }
    }
    LaunchedEffect(sheet) {
        if (sheet != ModelEffortSheet.None) {
            renderedSheet = sheet
            exiting = false
            // 开层就清掉「被禁用收起」的标记：上一次退场动画播到一半被这次开层打断时，
            // 下面的收尾块根本不会执行，标记会一直留到下一次正常关层，把 onSheetClosed()
            // 吞掉一次（键盘不回弹，3.4.50 审理 P3-1）。
            sheetClosedByDisable = false
            sheetProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.93f, stiffness = 480f),
            )
        } else if (renderedSheet != ModelEffortSheet.None) {
            // 140ms 确认窗内关层：点模型行这个动作已经表达了选择，不许把这唯一一笔待确认的
            // 选择连同勾一起丢掉——勾停在 pickedModelId 上却不回调，药丸显示一套实际一套，
            // 一直错到下次开层（3.4.50 审理 P2-10）。所以这里把待确认的那笔立即结清。
            // 例外是「被禁用自动收起」：那一步发生在流式开始，状态层会挡下改模型，
            // 提交了只会让勾与实际模型再次分叉，按取消处理。
            val pendingPick = pickJob
            pickJob = null
            if (pendingPick != null) {
                pendingPick.cancel()
                if (!sheetClosedByDisable) pickedModelId?.let(onModelSelected)
            }
            exiting = true
            sheetProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.93f, stiffness = 480f),
            )
            if (sheet == ModelEffortSheet.None) {
                renderedSheet = ModelEffortSheet.None
                pickedModelId = null
                // 切换遮罩的单向闩锁在这里解开：只有这里知道「内容切换/退场都结束了」
                // （3.4.49 审理 P3-3）。
                contentSwitching = false
                // 退场播完就把 exiting 落回 false：此前只靠下一次开层重置，中途被打断时
                // 它会一直是 true，遮罩判据跟着错到下次开层（3.4.50 审理 P3-7）。
                exiting = false
                // 焦点归还等浮层真正撤下后再发：退场动画期间卡片 Popup 还是可聚焦窗口，
                // 提前 requestFocus 会被它抢走、键盘回不来（3.4.49 审理，原问题 6 的时序补完）。
                // 被禁用自动收起的路径不还焦点：那是流式开始，键盘本就该收着。
                if (!sheetClosedByDisable) onSheetClosed()
                sheetClosedByDisable = false
            }
        }
    }
    // 流式开始时浮层还开着：立刻收起。浮层内的选模型/跳模型入口没有流式门控，
    // 留着它，用户点选在 Assist 侧会被状态层挡下却照样移勾关层（显示一套实际一套），
    // 在 Forge 侧会真改全局模型、让下一条排队消息换模型跑（3.4.49 审理）。
    LaunchedEffect(enabled) {
        if (!enabled && sheet != ModelEffortSheet.None) {
            sheetClosedByDisable = true
            sheet = ModelEffortSheet.None
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
                        // 点药丸打开浮层：一档都没有（思考不支持/被关）才直达模型浮层。
                        // 浮层开着时药丸被遮罩盖住，再点药丸所在位置实际点到的是遮罩、同样收起。
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

        if (modelPickerState != null && (sheet != ModelEffortSheet.None || scrimAlpha > 0.01f)) {
            // 遮罩（Sol .sheet-backdrop）：独立窗口、左上角固定在窗口原点，不许锚到药丸上——
            // 锚到药丸时整屏压暗会退化成右下角一小块（3.4.47 审计问题 1）。
            // 浅色照 Sol 的 rgba(13,13,13,0.06)；深色下 6% 黑几乎压不动，改 30%（问题 4）。
            // 退场只跟 160ms 淡出、淡完即撤窗口，不陪卡片弹簧吃点击（问题 11）。
            Popup(
                popupPositionProvider = WindowOriginPositionProvider,
                onDismissRequest = { sheet = ModelEffortSheet.None },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Color(0xFF0D0D0D).copy(
                                alpha = (if (isDark) 0.30f else 0.06f) * scrimAlpha,
                            ),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { sheet = ModelEffortSheet.None },
                )
            }
        }

        if (modelPickerState != null && renderedSheet != ModelEffortSheet.None) {
            // 浮层卡片：右边缘对齐输入卡片右边缘，底边在药丸上沿上方 5dp（Sol 锚点公式）。
            val anchorRightPx = anchorLeftPx + anchorWidthPx
            val shiftX = if (sheetRightEdgeWindowPx > 0) {
                sheetRightEdgeWindowPx - anchorRightPx
            } else {
                0
            }
            // 档位卡固定高 104dp：横屏/分屏 + 键盘时药丸上方可能不足 104+5dp，
            // 整体下移兜底、顶边不许越过状态栏下 8dp（3.4.47 审计问题 20）。
            // 模型卡高度已被 maxHeight 卡在同一条线内，不需要下移。
            val topClampPx = if (renderedSheet == ModelEffortSheet.Effort) {
                val minTopPx = statusBarTopPx + with(density) { 8.dp.roundToPx() }
                val cardTopPx = anchorTopPx - with(density) { 5.dp.roundToPx() } -
                    with(density) { 104.dp.roundToPx() }
                (minTopPx - cardTopPx).coerceAtLeast(0)
            } else {
                0
            }
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(
                    x = shiftX,
                    y = -(anchorHeightPx + with(density) { 5.dp.roundToPx() }) + topClampPx,
                ),
                onDismissRequest = { sheet = ModelEffortSheet.None },
                properties = PopupProperties(focusable = true),
            ) {
                SolSheetCard(
                    // 进度在绘制层读：动画每帧只重绘、不整卡重组（审计问题 26）。
                    modifier = Modifier.graphicsLayer {
                        val progress = sheetProgress.value
                        alpha = progress
                        translationY = (1f - progress) * if (exiting) 8.dp.toPx() else 10.dp.toPx()
                        val scale = if (exiting) {
                            0.98f + 0.02f * progress
                        } else {
                            0.97f + 0.03f * progress
                        }
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(1f, 1f)
                    },
                ) {
                    Box {
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
                                enabled = enabled,
                                onOpenModels = {
                                    // 切换遮罩同步置位：等 LaunchedEffect(renderedSheet) 再置位
                                    // 会漏出新内容首帧无遮罩的空窗（3.4.49 审理）。
                                    contentSwitching = true
                                    sheet = ModelEffortSheet.Model
                                },
                                // 选档不收浮层（Sol 原版口径）：松手只落档，
                                // 收起只走点遮罩（含药丸所在位置，打开时药丸被遮罩盖住）或返回键。
                                onEffortChange = { picked ->
                                    onEffortChange(picked)
                                },
                            )
                            ModelEffortSheet.Model -> SolModelList(
                                state = modelPickerState,
                                maxHeight = modelSheetMaxHeight,
                                confirmedModelId = pickedModelId,
                                enabled = enabled,
                                onModelSelected = { modelId ->
                                    // 先把勾落到新行上（SolModelList 读 confirmedModelId），
                                    // 140ms 后才回调并收起（审计问题 14）。
                                    // 140ms 内改主意点另一行：以最后一次为准——取消上一笔、
                                    // 勾跟到新行，不许把第二击静默吞掉（3.4.49 审理）。
                                    pickedModelId = modelId
                                    pickJob?.cancel()
                                    pickJob = sheetScope.launch {
                                        delay(140)
                                        // 先清任务引用再落选择：关层分支靠 pickJob 判断「有没有
                                        // 待确认的选择」，留着引用会让同一笔选择被结清两次
                                        // （3.4.50 审理 P2-10）。
                                        pickJob = null
                                        // 先落选择、再关层。反过来的话，关层那一步会走
                                        // LaunchedEffect(sheet) 的取消分支把本任务 cancel 掉，
                                        // 全靠「两条语句之间没有挂起点」才侥幸没丢选择（3.4.49 审理 P2-8）。
                                        onModelSelected(modelId)
                                        sheet = ModelEffortSheet.None
                                    }
                                },
                            )
                            ModelEffortSheet.None -> Box(modifier = Modifier.size(1.dp))
                        }
                        }
                        // 退场与内容切换期间罩住整卡吃掉手势：透明层不可再点，
                        // 不许静默改档/切模型（审计问题 11、12）。
                        if (exiting || contentSwitching) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent().changes.forEach { it.consume() }
                                            }
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class ModelEffortSheet { None, Model, Effort }

/** 遮罩专用定位：弹窗窗口左上角固定在窗口原点，与锚点（药丸）位置无关。 */
private object WindowOriginPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/** 浮层切换缓动（Sol 原版 cubic-bezier(0.16, 1, 0.3, 1)）。 */
private val SheetSwitchEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * 浮层卡片宽度（Sol 的 --sheet-width = 270dp）。
 * 卡片外框、模型列表、档位内容三处都要用它：内容宽度决定卡片背景的实际宽度
 * （内层 Box 是包裹尺寸），改一处漏一处就会让某张卡片的底色比外框窄一截，
 * 所以提成一份常量共用（3.4.50 审理 P3-10）。
 */
private val SheetWidth = 270.dp

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
    // 深色/纯黑下页面底色与卡片同为 surface（纯黑下同为纯黑），卡片会糊在页面上：
    // 深色时把卡片底色往白提 7% 做出层次，边框也加深（3.4.47 审计问题 2、3）。
    val cardColor = if (isDark) lerp(colors.surface, Color.White, 0.07f) else colors.surface
    Box(
        modifier = modifier
            .width(SheetWidth)
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
                .background(cardColor)
                .border(
                    width = 1.dp,
                    color = colors.onSurface.copy(alpha = if (isDark) 0.16f else 0.08f),
                    shape = shape,
                ),
        ) {
            content()
        }
    }
}

/**
 * 模型浮层内容，照 Sol 的 ModelSheet（Sheets.tsx）与 .sheet--model / .modelrow / .sheet__hint（app.css）：
 * 卡片上下内边距 6dp；提示行（文案走资源 model_picker_title）高 28dp、10sp、灰字、左右 12dp；
 * 模型行最小高 34dp、左右 12dp、11.5sp：模型名在前（放不下省略），后跟灰色 "·"（左右各 4dp）+ 分组名（放不下省略），
 * 当前选中行右侧 32dp 宽的格子里打勾（14dp、强调色）。只有一个服务商时不重复显示服务商名。
 * 打开时列表定位到当前模型（旧选择器口径）；点行先落勾、140ms 后才回调收起（回调在控件层）。
 * 数据只读传入的 [state]、选择只走 onModelSelected，与设置页/顶栏共用同一份选择口径，不另起一份。
 */
@Composable
private fun SolModelList(
    state: AgentModelPickerUiState,
    maxHeight: Dp,
    confirmedModelId: String?,
    enabled: Boolean,
    onModelSelected: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    // 次要文字：浅色用 Sol 的 --text-3（#6b6b6b，对比约 5:1）；深色用 onSurface 55%。
    val gray = if (isDark) colors.onSurface.copy(alpha = 0.55f) else Color(0xFF6B6B6B)
    val rows = remember(state.providerGroups) {
        state.providerGroups.flatMap { group ->
            group.models.map { model -> group.providerName to model }
        }
    }
    val showProvider = state.providerGroups.size > 1
    val selectedId = confirmedModelId ?: state.selectedModel?.id
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst { it.second.id == selectedId }
            .coerceAtLeast(0),
    )
    Column(
        modifier = Modifier
            .width(SheetWidth)
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
                text = stringResource(R.string.model_picker_title),
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.1.sp,
                ),
                color = gray,
                maxLines = 1,
            )
        }
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.model_picker_empty),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp),
                    color = gray,
                    maxLines = 1,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(rows, key = { "${it.second.providerId}/${it.second.id}" }) { (providerName, model) ->
                val isSelected = model.id == selectedId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        // 单选行：role 用 RadioButton，读屏才报「单选按钮 · 已选中」；
                        // 用 Button 只会读成普通按钮（3.4.50 审理 P3-6）。
                        .clickable(
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onModelSelected(model.id) },
                        )
                        .semantics { selected = isSelected }
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp),
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2f, fill = false),
                    )
                    if (showProvider) {
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
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier.width(32.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.ui_current_model_a0af8f),
                                modifier = Modifier.size(14.dp),
                                tint = colors.primary,
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
        // 缩到 9.5sp 下限仍放不下时走省略号，不许硬裁掉半个字（3.4.49 审理）。
        overflow = TextOverflow.Ellipsis,
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
    enabled: Boolean,
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
    // 模型名灰字：浅色用 Sol 的 --text-3（#6b6b6b），深色用 onSurface 55%（审计问题 25）。
    val isDarkSheet = colors.surface.luminance() < 0.5f
    val modelTint = if (isDarkSheet) {
        colors.onSurface.copy(alpha = 0.55f)
    } else {
        Color(0xFF6B6B6B)
    }

    Box(
        modifier = Modifier
            .width(SheetWidth)
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
                    // 不裁剪要靠 ContentTransform.using(SizeTransform(clip=false))——
                    // AnimatedContent 没有 sizeTransform 形参（3.4.47 审计 P0）。
                    val travel = with(density) { 10.dp.roundToPx() }
                    (slideInVertically(tween(180)) { travel } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(180)) { -travel } + fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(180) })
                },
                label = "effort_big_word",
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) { word ->
                // 必须显式居中：AnimatedContent 会把整宽约束传给内容，
                // Text 默认靠左绘制，不写 textAlign 词会跑到左边（3.4.48 真机实证）。
                Text(
                    text = word,
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.primary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
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
                .clickable(role = Role.Button, enabled = enabled, onClick = onOpenModels)
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
                    enabled = enabled,
                    onPreviewChange = { index ->
                        interacted = true
                        previewIndex = index
                    },
                    onRelease = { index ->
                        // 预览与落档必须同一门控，否则大词已经换成新档、effort 还是旧值，
                        // 显示一套实际一套（3.4.50 审理 P2-3）。判据两条：
                        // 1) 停在打开时的位置上就不落档：DEFAULT 在滑块上的兜底位置就是 High，
                        //    点在自己手柄上不该把「默认」具体化成 High（3.4.49 审理 P3-6）；
                        // 2) 但 OFF 在滑块上没有自己的位置（手柄停在第一档、大词写「已关闭」），
                        //    所以停在第一档是「开思考到这个档」的真实意图，必须落档。
                        if (index != initialIndex || effort == ReasoningEffort.OFF) {
                            onEffortChange(gears[index])
                        } else {
                            // 不落档就把预览退回实际档，大词回到「默认」而不是停在某个具体档上。
                            interacted = false
                            previewIndex = initialIndex
                        }
                    },
                    onDragCancelled = {
                        // 手势被系统取消/中途禁用：预览与「已交互」标记一起退回，
                        // 大词要变回「默认/关」的原始文案，不许停在某个档位词上。
                        interacted = false
                        previewIndex = initialIndex
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
                        // clickable 已经带 enabled 门控，这里不再重复判断（3.4.50 审理 P3-11）。
                        .clickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = { onEffortChange(only) },
                        )
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
 * 手柄是 29dp 白圆钮，在内缩 14.5dp 的轨道里走，阴影照 --shadow-knob：
 * 0 1px 4px rgba(15,15,15,0.22) 用一层小 elevation 近似，外加 1px 圈 rgba(15,15,15,0.04) 用描边落。
 * 刻度圆心与手柄圆心并不重合：刻度圆心 x = 12 + (W−24)·f，手柄圆心 x = 14.5 + (W−29)·f，
 * 两者相差 5f − 2.5dp（f 是 0..1 的档位进度）——f=0.5 时重合，两端各偏 2.5dp。
 * 这组数值取自 Sol 原版，强行对齐会让刻度和手柄同时离开原版位置（3.4.50 审理 P3-8）。
 * 弹簧 stiffness 560 / dampingRatio ≈ 0.86（原版 damping 34 / mass 0.7），拖动放大 1.15（弹簧 700/30），
 * 过刻度震动、松手吸附最近档才回调，轻点轨道直接跳档。
 */
@Composable
private fun EffortSlider(
    gears: List<ReasoningEffort>,
    selectedIndex: Int,
    enabled: Boolean,
    onPreviewChange: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    onDragCancelled: () -> Unit,
) {
    // 单档/零档不该走滑块：调用方把单档交给下面的「选用钮」分支（EffortSheetContent 里
    // gears.size == 1），零档连滑块都不组合。这里再挡一层，免得将来多一处调用时把 0..0 的
    // range 和「共 1 档」交给读屏（3.4.50 审理 P3-3）。
    if (gears.size < 2) return
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val segments = (gears.size - 1).coerceAtLeast(1)
    // 档位集变了（切模型、能力表变化）就按新刻度重算手柄位置，否则旧 fraction 会停在
    // 错误的刻度上（3.4.49 审理 P3-2）。
    val fractionState = remember(segments) { mutableFloatStateOf(selectedIndex.toFloat() / segments) }
    var trackWidthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var gestureActive by remember { mutableStateOf(false) }
    val knobScale by animateFloatAsState(
        targetValue = if (dragging) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.57f, stiffness = 700f),
        label = "effort_knob_scale",
    )
    // fraction 的动画只有一个驱动者：本 effect 在非手势期把手柄对齐到 selectedIndex。
    // fraction 用普通浮点状态而不是 Animatable：awaitEachGesture 是受限挂起作用域，
    // Animatable.snapTo 这类外部挂起成员在里面根本调不了（3.4.49 首次构建才暴露）；
    // 手势回路改成同步写状态值跟手，落定动画由本 effect 用 animate() 写回同一状态。
    // 手势与 effect 都在主线程串行执行，不存在旧实现里游离 animateTo 与 effect
    // 互相取消、把手柄卡在半路的问题。
    LaunchedEffect(selectedIndex, gestureActive, segments) {
        if (!gestureActive) {
            animate(
                initialValue = fractionState.floatValue,
                targetValue = selectedIndex.toFloat() / segments,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
            ) { value, _ -> fractionState.floatValue = value }
        }
    }
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val trackColor = if (isDark) colors.onSurface.copy(alpha = 0.14f) else Color(0xFFEBEBEB)
    val tickIdleColor = if (isDark) colors.onSurface.copy(alpha = 0.35f) else Color(0xFFC4C4C4)
    val knobSize = 29.dp
    val railPadding = 14.5.dp
    val tickPadding = 12.dp
    // 震动口径：手柄真正跨进一个新档位时才震一次。判定值在回调里同步更新，
    // 同一帧内连发多个 move 事件也不会对同一档重复震（3.4.47 审计问题 27）；
    // 外部改档（不是手势触发）时由 LaunchedEffect(selectedIndex) 拉齐。
    val hapticIndex = remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) { hapticIndex.intValue = selectedIndex }
    // 流式开始等禁用态：浮层开着时药丸已被门控，浮层内的滑块同样不许改档。
    val latestEnabled by rememberUpdatedState(enabled)
    // 回调与选中档一律走最新值：pointerInput 只以 gears / density 为 key，块里直接抓的参数
    // 会停在手势块创建那一刻——外部改档后旧 onRelease 闭包里的 effort 是陈值，
    // 「拖回原档」会被误判成没变化而吞掉（3.4.49 审理）。
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestOnPreviewChange by rememberUpdatedState(onPreviewChange)
    val latestOnRelease by rememberUpdatedState(onRelease)
    val latestOnDragCancelled by rememberUpdatedState(onDragCancelled)
    // 滑块此前完全没有无障碍语义：读屏只能听到一片空白，也没法改档（3.4.49 审理 P3-5）。
    val sliderDescription = stringResource(
        R.string.chat_reasoning_effort,
        gears.getOrNull(selectedIndex)?.let { solGearLabel(it) } ?: "",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(45.dp)
            // mergeDescendants：轨道、刻度、手柄都是纯绘制节点，合并成一个焦点；
            // 不合并时读屏会在同一颗滑块上停两次（3.4.50 审理 P3-18）。
            .semantics(mergeDescendants = true) {
                contentDescription = sliderDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = selectedIndex.toFloat(),
                    range = 0f..gears.lastIndex.coerceAtLeast(0).toFloat(),
                    steps = (gears.size - 2).coerceAtLeast(0),
                )
                // 补上「第几档 / 共几档」：progressBarRangeInfo 只给出数值范围，读屏不会
                // 自己换算成档位序号（3.4.50 审理 P3-5）。序号用纯数字拼，本控件不新增
                // 字符串资源——档位词已由 contentDescription 提供，这里只补位置。
                stateDescription = "${selectedIndex + 1} / ${gears.size}"
                setProgress { target ->
                    if (!latestEnabled) {
                        false
                    } else {
                        val index = target.roundToInt().coerceIn(0, gears.lastIndex)
                        latestOnPreviewChange(index)
                        latestOnRelease(index)
                        true
                    }
                }
            }
            .onGloballyPositioned { trackWidthPx = it.size.width }
            // density 也要进 key：命中区按 dp→px 换算，改字号/显示大小后不重建手势块，
            // 就还按旧密度算落点，点到的手柄位置与看到的差一截（3.4.50 审理 P3-2）。
            .pointerInput(gears, density) {
                // 内缩量在块首换算一次：pointerInput 的作用域本身就是 Density，
                // 不必再 with(density)（3.4.50 审理 P3-19）。
                val railPaddingPx = railPadding.toPx()
                // 点按与拖动共用一条手势回路。旧实现是两个 pointerInput 分别跑
                // detectTapGestures / detectDragGestures：两套检测器抢同一串事件，
                // 点按落档会被拖动检测器干扰而丢失——真机实证：点 Light 位置，
                // 手柄滑过去又弹回原档、档位词也不变（3.4.48 修复）。
                // 单回路口径：按下先记落点；移动过 touchSlop 才算拖动，拖动中手柄
                // 在本协程内同步写 fractionState 跟手、跨档震一次并报预览；抬手按最终位置
                // 落档（点按 = 没拖动过、按抬手位置算最近档），回调完成后才放开
                // gestureActive，让对齐 effect 把手柄弹簧到落定档位。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 已被退场/切换罩子吃掉的按下不归滑块：罩子的 consume 只是标记，
                    // 不查 isConsumed 的话滑块照样跟手改档，罩子在主控件上落空（3.4.49 审理）。
                    if (down.isConsumed) return@awaitEachGesture
                    if (!latestEnabled || trackWidthPx <= 0) return@awaitEachGesture
                    fun fractionForX(x: Float): Float {
                        val usable = (trackWidthPx - railPaddingPx * 2).coerceAtLeast(1f)
                        return ((x - railPaddingPx) / usable).coerceIn(0f, 1f)
                    }
                    fun indexForX(x: Float): Int =
                        (fractionForX(x) * segments).roundToInt().coerceIn(0, gears.lastIndex)
                    gestureActive = true
                    var dragged = false
                    var upX = down.position.x
                    var released = false
                    var settled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (change.isConsumed) break
                            // 拖动途中被禁用（流式开始）就不再跟手：直接退出回路，
                            // 由下面的取消分支把预览退回实际档位（3.4.49 审理 P2-10）。
                            if (!latestEnabled) break
                            // 抬起或被系统取消。系统取消（ACTION_CANCEL）在 compose 里是合成一个
                            // pressed=false 且已被消费的 PointerEvent，被上面的 isConsumed 分支
                            // 抢先 break 掉，所以能走到这里的一定是用户真抬手。
                            // 下面这个判据就是 changedToUp() 的定义（previousPressed && !pressed），
                            // 就地展开只是省一个 API，语义完全等价——此前注释说「不能用
                            // changedToUp() 判别」是错的（3.4.50 审理 P3-12）。
                            if (change.previousPressed && !change.pressed) {
                                upX = change.position.x
                                released = true
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                if (!dragged &&
                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) {
                                    dragged = true
                                    dragging = true
                                }
                                if (dragged) {
                                    fractionState.floatValue = fractionForX(change.position.x)
                                    val index = indexForX(change.position.x)
                                    if (index != hapticIndex.intValue) {
                                        hapticIndex.intValue = index
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    latestOnPreviewChange(index)
                                }
                            }
                        }
                        if (released && latestEnabled) {
                            // 落档一律按抬手坐标算：拖动途中最后一次 move 的坐标可能落后于
                            // 真实抬手点，用 lastIndex 会把手柄落在差一档的位置（3.4.49 审理 P3-8）。
                            val finalIndex = indexForX(upX)
                            if (finalIndex != hapticIndex.intValue) {
                                hapticIndex.intValue = finalIndex
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            latestOnPreviewChange(finalIndex)
                            latestOnRelease(finalIndex)
                            settled = true
                        } else if (dragged) {
                            // 系统取消（来电/下拉通知栏）或拖到一半被禁用：不落档，
                            // 预览也必须退回实际档位——否则词与手柄停在拖到的档上、
                            // 实际生效的还是原档，又是显示一套实际一套（3.4.49 审理）。
                            hapticIndex.intValue = latestSelectedIndex
                            latestOnDragCancelled()
                            settled = true
                        }
                    } finally {
                        dragging = false
                        gestureActive = false
                        // 协程被取消（节点 detach、pointerInput key 变化、密度变化）走不到上面
                        // 任何一支，预览会停在拖到的档位上；这里补一次回退（3.4.49 审理 P3-7）。
                        if (dragged && !settled) {
                            hapticIndex.intValue = latestSelectedIndex
                            latestOnDragCancelled()
                        }
                    }
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
                        scaleX = fractionState.floatValue
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
                    // offset 的 lambda 接收者本身就是 Density，不必再 with(density)
                    // （3.4.50 审理 P3-19）。
                    .offset {
                        val tickPaddingPx = tickPadding.toPx()
                        val usable = (trackWidthPx - tickPaddingPx * 2).coerceAtLeast(0f)
                        IntOffset(
                            x = (tickPaddingPx + usable * tickFraction - 2.dp.toPx()).roundToInt(),
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
                    val railPaddingPx = railPadding.toPx()
                    val usable = (trackWidthPx - railPaddingPx * 2).coerceAtLeast(0f)
                    val knobPx = knobSize.toPx()
                    IntOffset(
                        x = (railPaddingPx + usable * fractionState.floatValue - knobPx / 2).roundToInt(),
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
internal fun solEffortGears(availableEfforts: List<ReasoningEffort>): List<ReasoningEffort> {
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
internal fun solGearLabel(effort: ReasoningEffort): String? = when (effort) {
    ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "Light"
    ReasoningEffort.MEDIUM -> "Medium"
    ReasoningEffort.HIGH -> "High"
    ReasoningEffort.XHIGH -> "Extra High"
    ReasoningEffort.MAX -> "Max"
    ReasoningEffort.OFF, ReasoningEffort.DEFAULT -> null
}

/**
 * 当前档在滑块上的位置；DEFAULT 兜底 High 的位置（Sol 原版）。
 * 目标档不在滑块上（被设置页能力挡掉）时，与 normalize() 同方向**向下**收敛：
 * 取不超过目标的最高档，没有更低的才落第一档。旧实现按 rank 距离取最近，
 * 目标下方无档、上方有档时会停到比 normalize 结果更高的档上——手柄位置与
 * 状态层收敛出的实际档位对不上（3.4.49 审理）。
 */
internal fun solGearIndex(effort: ReasoningEffort, gears: List<ReasoningEffort>): Int {
    if (gears.isEmpty()) return 0
    val target = if (effort == ReasoningEffort.DEFAULT) ReasoningEffort.HIGH else effort
    val direct = when (target) {
        ReasoningEffort.MINIMAL, ReasoningEffort.LOW ->
            gears.indexOfFirst { it == ReasoningEffort.LOW || it == ReasoningEffort.MINIMAL }
        ReasoningEffort.MEDIUM -> gears.indexOf(ReasoningEffort.MEDIUM)
        ReasoningEffort.HIGH -> gears.indexOf(ReasoningEffort.HIGH)
        ReasoningEffort.XHIGH -> gears.indexOf(ReasoningEffort.XHIGH)
        ReasoningEffort.MAX -> gears.indexOf(ReasoningEffort.MAX)
        ReasoningEffort.OFF, ReasoningEffort.DEFAULT -> -1
    }
    if (direct >= 0) return direct
    return gears.withIndex()
        .filter { it.value.rank <= target.rank }
        .maxByOrNull { it.value.rank }
        ?.index ?: 0
}
