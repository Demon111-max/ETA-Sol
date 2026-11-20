package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.markdown.EditorialLineHeightStyle
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * 草稿外传的静默窗口：连续输入期间只按这个间隔落一次，见 AgentChatInputBar 的 snapshotFlow。
 *
 * 3.4.50 审理 P3-30：窗口内没外传的文字**不是「最多一个窗口」**。只要用户不停顿，
 * 自上次 ≥200ms 停顿以来输入的全部文字都还没进状态层；输入栏一销毁（切会话 / 切模式 /
 * 返回上一页，见 `AgentHomeScreen.kt:43` 的 `key(chatConversationCompositionKey(...))`）
 * 就整段丢，且内存与盘上都没有。所以销毁时必须走同步 flush
 * （见 AgentChatInputBar 里的 DisposableEffect），不能只靠这个窗口兜底。
 */
private const val InputDraftSyncDelayMillis = 200L

/**
 * 输入栏与上层之间的「这段文本已经交出去了」对账标记。
 *
 * 为什么需要：草稿外传有三条会重叠的路径（静默窗口到期、销毁前补交、上层同步）。没有对账时，
 * 一次补交之后仍在跑的旧协程会把同一段旧文本再写一遍，上层就把「刚清空的草稿」又填回来，
 * 已发送的内容原地复活成草稿（3.4.50 审理 P1-2 / P2-1）。同一段文本只外传一次，重复投递丢弃。
 */
private class DraftSyncMarker(initialText: String) {
    private var delivered: String = initialText

    /** 这段文本已经通过别的路径交给上层（提交、撤回、上层同步），不必再走 onInputChange。 */
    fun isDelivered(text: String): Boolean = text == delivered

    fun mark(text: String) {
        delivered = text
    }

    /** 与最后一次交出的文本不同才投递；先记账再投递，回调里再改状态也不会重入。 */
    fun deliver(text: String, sink: (String) -> Unit) {
        if (text == delivered) return
        delivered = text
        sink(text)
    }
}

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentChatInputBar(
    input: String,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    focusInputOnSync: Boolean = true,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    preserveFollowingMessages: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    // 模型选择状态：非空时输入栏右下角渲染合并控件（模型+档位，ModelEffortControl），
    // 顶栏不再承担模型切换。为空时回落到旧的纯档位胶囊（兼容没有模型上下文的调用方）。
    modelPickerState: AgentModelPickerUiState? = null,
    onModelSelected: (String) -> Unit = {},
    // Forge 模式复用同一条输入条：不带附件入口，提示语换成 opencode 的措辞。
    showAttach: Boolean = true,
    hintText: String? = null,
    workingText: String? = null,
    /** 输入内容变化的外传（含被外部同步写入、发送后清空）：供需要跨重建保留草稿的调用方落盘。 */
    onInputChange: (String) -> Unit = {},
    /**
     * 输入栏被销毁前的草稿补交（3.4.51 P1-2）：与 [onInputChange] 分开是必须的。
     *
     * 销毁发生在「已经切到别的会话」之后（AgentAppState.selectConversation 先改
     * selectedConversationId 再让子树重组销毁），所以 [onInputChange] 这条链上的
     * updateInputDraft 此刻写的是**新会话**——旧会话的字会冒到新会话输入框里，旧会话照样丢。
     * 调用方必须在这个回调里带上草稿归属（会话键），按归属落回原会话。
     * 默认值退回 [onInputChange]：没有会话归属概念的调用方（Forge）行为不变。
     */
    onDraftFlush: (String) -> Unit = onInputChange,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val textFieldState = rememberTextFieldState(initialText = input)
    // 把输入内容外传：本组件自己持有 TextFieldState（普通输入不往上抛，避免每个字符带整个
    // 聊天舞台重组），但需要跨重建保住草稿的调用方拿不到内容，所以在这里做一次单向外抛。
    //
    // 三条外传路径叠在一起，必须互相让路（3.4.50 审理 P1-2 / P2-1）：
    // 1. 静默窗口：按 [InputDraftSyncDelayMillis] 节流。此前逐字符直抛，每敲一个字就把
    //    homeState.input 改一遍、整张会话表复制一次、整屏重组一次；窗口内连打只落一次。
    // 2. 销毁前补交（下面的 DisposableEffect）：输入栏随会话 key 一起被销毁，LaunchedEffect
    //    被取消时 delay 体根本不执行，所以窗口内的字必须在这里补交一次。补交范围是「自上次
    //    成功外传以来的全部文字」——切走前连打了多久就丢多久，不是一个窗口（3.4.50 P3-30）。
    // 3. 上层同步：发送后清空、排队消息撤回合并、编辑态回填都由上层写 input 并递增
    //    inputSyncVersion 驱动，此时输入栏里的旧文本已经作废，不能反过来写回上层。
    // 路径 1/2 与 3 的胜负由 [DraftSyncMarker] 记账判定：同一段文本只外传一次。
    // 回调经 rememberUpdatedState 取最新值：LaunchedEffect 只按 textFieldState 启动一次，
    // 直接捕获 onInputChange 会把首次组合的旧闭包一直用到销毁。
    val currentOnInputChange by rememberUpdatedState(onInputChange)
    val currentOnDraftFlush by rememberUpdatedState(onDraftFlush)
    val draftSync = remember { DraftSyncMarker(initialText = input) }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { text ->
                delay(InputDraftSyncDelayMillis)
                draftSync.deliver(text, currentOnInputChange)
            }
    }
    // 销毁前补交草稿：切会话、切模式、离开聊天页都会重建整棵输入栏，这里是「一段字都不许丢」
    // 的唯一保证（3.4.50 审理 P1-2）。取的是销毁那一刻的最新文本与最新回调，不是首次组合的闭包。
    // 走 [onDraftFlush] 而不是 [onInputChange]：销毁时上层已经切到新会话，补交必须带上归属。
    DisposableEffect(Unit) {
        onDispose {
            draftSync.deliver(textFieldState.text.toString(), currentOnDraftFlush)
        }
    }
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    var syncedVersion by remember { mutableIntStateOf(inputSyncVersion) }
    val canSend = textFieldState.text.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    // 输入卡片右边缘的窗口横坐标：合并控件的浮层右边缘要与它对齐（Sol 锚点公式），量好后传给控件。
    var inputCardRightPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage, inputSyncVersion) {
        // 编辑态与排队消息撤回由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        val versionChanged = inputSyncVersion != syncedVersion
        if (isEditingMessage || wasEditingMessage || versionChanged) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
            // 上层刚覆盖了输入框（发送后清空、撤回合并、编辑态回填）：这段文本以上层为准，
            // 记进对账标记，随后才到期的旧窗口就不会把它当草稿写回去（3.4.50 审理 P2-1）。
            draftSync.mark(input)
        }
        if (isEditingMessage || (versionChanged && input.isNotEmpty() && focusInputOnSync)) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        wasEditingMessage = isEditingMessage
        syncedVersion = inputSyncVersion
    }

    // 外部同步的第二道保险：切会话、载入、新建都靠外层 key(...) 重建输入栏来对齐，
    // inputSyncVersion 在这三条路径上不递增（3.4.50 审理 P3-20）。这里改成盯外部 input 本身：
    // 只要它变成一段「我们没交出去过」的文本就对齐过去；自己刚交出去的那段回灌时不动，
    // 否则每次落盘都会把光标拽到末尾。
    LaunchedEffect(input) {
        if (draftSync.isDelivered(input)) return@LaunchedEffect
        textFieldState.setTextAndPlaceCursorAtEnd(input)
        draftSync.mark(input)
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = queuedMessages.isNotEmpty(),
            enter = fadeIn(tween(160)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
        ) {
            AgentChatQueueStrip(
                queuedMessages = queuedMessages,
                isStreaming = isStreaming,
                onSteer = onSteerQueuedMessage,
                onRecall = { id ->
                    val draftText = textFieldState.text.toString()
                    // 当前草稿跟着撤回一起交给上层合并了，先记账：否则过期的静默窗口会把
                    // 合并前的文本再写回去，把刚撤回的内容冲掉（3.4.50 审理 P2-1）。
                    draftSync.mark(draftText)
                    onRecallQueuedMessage(id, draftText)
                },
                onRemove = onRemoveQueuedMessage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (preserveFollowingMessages) {
                    stringResource(R.string.chat_edit_preserve_following)
                } else if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 与输入框内正文同一起点（容器 10dp + 文本 8dp）。
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
            )
        }

        val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
        val couiDark = CouiTheme.isDark
        val couiInputShape = remember { CouiShape(24.dp, 2.3f) }
        val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
        val miuixInputBg = if (!isDark && MiuixTheme.colorScheme.surfaceContainer == MiuixTheme.colorScheme.surface) {
            Color(0xFFF5F6F8)
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        inputCardRightPx =
                            (coordinates.positionInWindow().x + coordinates.size.width).roundToInt()
                    }
                    .then(
                        if (isCoui) {
                            Modifier
                                .dropShadow(
                                    shape = couiInputShape,
                                    shadow = Shadow(
                                        radius = 16.dp,
                                        color = Color.Black,
                                        alpha = if (couiDark) 0.22f else 0.045f,
                                    ),
                                )
                                .clip(couiInputShape)
                                .background(
                                    if (couiDark) Color(0xFF1C1E22) else Color(0xFFF6F7F9),
                                    couiInputShape,
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (couiDark) Color(0x20FFFFFF) else Color(0x0E000000),
                                    shape = couiInputShape,
                                )
                        } else {
                            Modifier
                                .dropShadow(
                                    shape = InputContainerShape,
                                    shadow = Shadow(
                                        radius = 12.dp,
                                        color = Color.Black,
                                        alpha = if (isDark) 0.20f else 0.05f,
                                    ),
                                )
                                .squircleSurface(
                                    color = miuixInputBg,
                                    cornerRadius = 22.dp,
                                )
                                .squircleBorder(
                                    width = 0.5.dp,
                                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    cornerRadius = 22.dp,
                                )
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 36.dp)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    // 占位文字与输入正文共用同一行高，首字输入时基线不跳动。
                    val inputTextStyle = MiuixTheme.textStyles.body1.copy(
                        lineHeight = 22.sp,
                        lineHeightStyle = EditorialLineHeightStyle,
                    )
                    val character = LocalRoleplayChat.current
                    if (textFieldState.text.isBlank()) {
                        Text(
                            text = when {
                                hintText != null && isStreaming -> workingText ?: hintText
                                hintText != null -> hintText
                                // 角色对话的占位文字跟随角色，避免“交给 Eta”打断沉浸感。
                                character != null && isStreaming ->
                                    stringResource(R.string.chat_character_typing, character.characterName)
                                character != null -> stringResource(R.string.chat_character_input_hint, character.characterName)
                                isStreaming -> stringResource(R.string.chat_eta_working)
                                else -> stringResource(R.string.chat_input_hint)
                            },
                            style = inputTextStyle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        textStyle = inputTextStyle.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 1,
                            maxHeightInLines = 6,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        if (isEditingMessage) {
                            IconButton(
                                onClick = onCancelMessageEdit,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        } else if (showAttach) {
                            AgentAttachmentPickerButton(
                                popupAnchorTopPx = inputContainerTopPx,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                onAttachImage = onAttachImage,
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                            )

                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (showContextUsage && contextUsage.contextTokens != null) {
                            AgentContextUsageButton(
                                usage = contextUsage,
                                onCompact = onCompactContext,
                                canCompact = canCompactContext && !isStreaming,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // 合并控件（选模型 + 思考档位）固定在右下、发送键左侧（3.4.45 用户定案布局）；
                        // 上下文用量圈在它左边。编辑消息时不显示（编辑态只有取消与发送）。
                        // 语音听写入口已按用户要求整体移除（用不上），不要再加回来。
                        if (!isEditingMessage) {
                            if (modelPickerState != null) {
                                ModelEffortControl(
                                    modelPickerState = modelPickerState,
                                    onModelSelected = onModelSelected,
                                    effort = reasoningEffort,
                                    availableEfforts = availableReasoningEfforts,
                                    enabled = !isStreaming,
                                    onEffortChange = onReasoningEffortChange,
                                    sheetRightEdgeWindowPx = inputCardRightPx,
                                    // 浮层开着时软键盘会被它顶掉，关掉后把焦点还给输入框。
                                    onSheetClosed = { focusRequester.requestFocus() },
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                            } else if (availableReasoningEfforts.isNotEmpty()) {
                                // 没有模型上下文的调用方回落到旧的纯档位胶囊。
                                ThinkingEffortChip(
                                    effort = reasoningEffort,
                                    options = availableReasoningEfforts,
                                    enabled = !isStreaming,
                                    popupAnchorTopPx = inputContainerTopPx,
                                    popupMaxHeight = thinkingPopupMaxHeight,
                                    onEffortChange = onReasoningEffortChange,
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                        }

                        val showStreamingSend = isStreaming && !isCompacting && canSend
                        val submitCurrentDraft: (Boolean) -> Unit = { interrupt ->
                            if (canSend) {
                                val submittedText = textFieldState.text.toString()
                                // 不在这里 clearText()：发送可能被上层拒绝（模型不支持图片、文件引用
                                // 需要终端、会话正在删除……），本地先清空就等于把用户刚打的字连同
                                // 草稿一起扔掉，上层再想还原也只剩空串（3.4.49 审理 P2-2）。
                                // 真正的清空交给上层：它把 input 置空并递增 inputSyncVersion，
                                // 输入栏在 LaunchedEffect(inputSyncVersion) 里同步成空。
                                // 这段文本已经交给上层了（onSubmit），先记账：否则已经过了静默
                                // 窗口的旧协程会在清空之后把它当草稿写回去，已发内容原地复活
                                // （3.4.50 审理 P2-1）。
                                draftSync.mark(submittedText)
                                if (interrupt) {
                                    onInterruptAndSubmit(submittedText)
                                } else {
                                    onSubmit(submittedText)
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showStreamingSend,
                            enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.78f) + expandHorizontally(tween(160)),
                            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f) + shrinkHorizontally(tween(140)),
                        ) {
                            IconButton(
                                onClick = onStop,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(SendButtonVisualSize)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Stop,
                                        contentDescription = stringResource(R.string.chat_stop),
                                        modifier = Modifier.size(StopIconSize),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        val showPrimaryStop = isStreaming && !showStreamingSend
                        val sendCircleSize = 32.dp
                        val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
                        val sendButtonColor by animateColorAsState(
                            targetValue = when {
                                showPrimaryStop -> MiuixTheme.colorScheme.onSurface
                                canSend -> MiuixTheme.colorScheme.primary
                                else -> ink.copy(alpha = if (isDark) 0.08f else 0.06f)
                            },
                            animationSpec = tween(durationMillis = 160),
                            label = "send_button_color",
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    enabled = isStreaming || canSend,
                                    onClick = {
                                        if (showPrimaryStop) {
                                            onStop()
                                        } else {
                                            submitCurrentDraft(false)
                                        }
                                    },
                                    onLongClick = if (showStreamingSend) {
                                        {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            submitCurrentDraft(true)
                                        }
                                    } else {
                                        null
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(sendCircleSize)
                                    .clip(CircleShape)
                                    .background(sendButtonColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = showPrimaryStop,
                                    transitionSpec = {
                                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                            .togetherWith(
                                                fadeOut(tween(90)) +
                                                    scaleOut(tween(110), targetScale = 0.72f)
                                            )
                                    },
                                    label = "send_stop_icon",
                                ) { stopIcon ->
                                    Icon(
                                        imageVector = if (stopIcon) {
                                            Icons.Rounded.Stop
                                        } else {
                                            Icons.Rounded.ArrowUpward
                                        },
                                        contentDescription = when {
                                            stopIcon -> stringResource(R.string.chat_stop)
                                            isEditingMessage && preserveFollowingMessages -> stringResource(R.string.chat_save_message)
                                            else -> stringResource(R.string.chat_send)
                                        },
                                        modifier = Modifier.size(if (stopIcon) 12.dp else 17.dp),
                                        tint = when {
                                            stopIcon -> MiuixTheme.colorScheme.surface
                                            canSend -> MiuixTheme.colorScheme.onPrimary
                                            else -> ink.copy(alpha = if (isDark) 0.28f else 0.24f)
                                        },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

}

/**
 * 思考强度胶囊：仅含开关两态时单击直接切换，支持多档强度时展开菜单并在胶囊上直接标明英文档位。
 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val interactive = enabled && options.size > 1
    val binaryToggleTarget = remember(options, effort) {
        if (options.size == 2 && ReasoningEffort.OFF in options) {
            options.firstOrNull { it != effort }
        } else {
            null
        }
    }
    val anchor = rememberCouiMenuAnchor()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(interactive) {
        if (!interactive) showPopup = false
    }
    val popupPositionProvider = remember(popupAnchorTopPx) {
        InputPopupPositionProvider(popupAnchorTopPx)
    }

    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val ink = colors.onSurface.copy(alpha = 1f)
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            colors.primary
        } else {
            ink.copy(alpha = if (isDark) 0.58f else 0.54f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    val bgColor by animateColorAsState(
        targetValue = if (active) {
            colors.primary.copy(alpha = if (isDark) 0.18f else 0.10f)
        } else {
            ink.copy(alpha = if (isDark) 0.06f else 0.038f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) {
            colors.primary.copy(alpha = if (isDark) 0.30f else 0.22f)
        } else {
            ink.copy(alpha = if (isDark) 0.10f else 0.065f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_border",
    )

    val chipLabel = when (effort) {
        ReasoningEffort.OFF -> stringResource(R.string.reasoning_chip_off)
        ReasoningEffort.DEFAULT -> stringResource(R.string.reasoning_chip_default)
        else -> effort.displayName
    }
    val accessibilityLabel = stringResource(R.string.chat_reasoning_effort, effort.displayName)
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .couiMenuAnchor(anchor)
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(bgColor, CircleShape)
                .border(0.5.dp, borderColor, CircleShape)
                .then(
                    if (isCoui) {
                        Modifier.couiButtonFeedback(isDark, interactionSource, enabled = interactive)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = interactive,
                    role = Role.Button,
                    onClick = {
                        if (binaryToggleTarget != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEffortChange(binaryToggleTarget)
                        } else {
                            anchor.open { showPopup = true }
                        }
                    },
                )
                .semantics {
                    contentDescription = accessibilityLabel
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = null,
                modifier = Modifier.size(14.5.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = chipLabel,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
            )
        }
        if (binaryToggleTarget == null) {
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    val menuItems = remember(options, effort) {
                        options.map { option ->
                            CouiMenuItem(
                                value = option,
                                text = option.displayName,
                                selected = option == effort,
                            )
                        }
                    }
                    Menu(
                        expanded = showPopup && interactive,
                        onDismissRequest = { showPopup = false },
                        items = menuItems,
                        onItemClick = { item ->
                            showPopup = false
                            onEffortChange(item.value)
                        },
                        anchor = anchor,
                    )
                }
                AppearanceUiStyle.MIUIX -> {
                    WindowListPopup(
                        show = showPopup && interactive && popupAnchorTopPx > 0,
                        popupPositionProvider = popupPositionProvider,
                        alignment = PopupPositionProvider.Align.TopStart,
                        onDismissRequest = { showPopup = false },
                        maxHeight = popupMaxHeight,
                    ) {
                        val dismiss = LocalDismissState.current
                        ListPopupColumn {
                            options.forEachIndexed { index, option ->
                                DropdownImpl(
                                    text = option.displayName,
                                    optionSize = options.size,
                                    isSelected = option == effort,
                                    index = index,
                                    onSelectedIndexChange = {
                                        onEffortChange(option)
                                        dismiss?.invoke()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val imagePreviewer = LocalChatImagePreviewer.current
    val itemBounds = remember { HashMap<String, Rect>() }
    var previousCount by remember { mutableIntStateOf(images.size) }
    LaunchedEffect(images.size) {
        if (images.size > previousCount && images.isNotEmpty()) {
            listState.animateScrollToItem(images.lastIndex)
        }
        previousCount = images.size
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            items = images,
            key = { _, item -> item.id },
        ) { index, image ->
            Box(
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        fadeOutSpec = tween(durationMillis = 120),
                    )
                    .attachmentItemEntrance()
                    .size(64.dp)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) itemBounds[image.id] = coords.boundsInWindow()
                    }
                    .graphicsLayer {
                        alpha = if (imagePreviewer?.isPresenting(PendingImagesGroupKey, index) == true) 0f else 1f
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .then(
                        if (imagePreviewer != null) {
                            Modifier.clickable {
                                imagePreviewer.open(
                                    images = images.map { it.dataUrl },
                                    initialIndex = index,
                                    groupKey = PendingImagesGroupKey,
                                    sourceBoundsProvider = { page -> images.getOrNull(page)?.let { itemBounds[it.id] } },
                                    sourceCornerRadius = 12.dp,
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.chat_view_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 可见圆点保持小巧，触控区放大到右上角 32dp，避免误触缩略图。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .clickable(interactionSource = null, indication = null) { onRemoveImage(image.id) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ui_remove_image_089db3),
                            modifier = Modifier.size(11.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private const val PendingImagesGroupKey = "pending_images_strip"
