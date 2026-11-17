package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiChipTabs
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.filterGroups
import io.github.mangi.eta.ui.model.modelCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 聊天首页顶栏标题：只负责居中显示标题，不再承担模型切换。
 *
 * 3.4.45 起模型选择与思考档位合并进输入栏右下角的合并控件（ModelEffortControl，
 * 仿旧 Sol 的 Codex 式药丸），顶栏按用户定案只留居中标题：有会话标题显示标题，
 * 角色对话显示头像+角色名（点开角色详情），都没有时显示应用名。
 * [state] / [isStreaming] / [onModelSelected] 参数保留只是为了不动壳层调用点，内部不再使用。
 */
@Composable
internal fun AgentTopBarChatHeader(
    conversationTitle: String?,
    character: RoleplayChatUi?,
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    onModelSelected: (String) -> Unit,
    onOpenCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when {
            character != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onOpenCharacter)
                            .padding(2.dp),
                    ) {
                        CharacterAvatar(
                            name = character.characterName,
                            avatarPath = character.avatarPath,
                            size = 30.dp,
                            shape = CircleShape,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = character.characterName,
                        style = MiuixTheme.textStyles.body2.copy(
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenCharacter)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            else -> {
                Text(
                    text = conversationTitle?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.app_name),
                    style = MiuixTheme.textStyles.body1.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ModelSubtitleRow(
    selected: AgentModelOptionUi?,
    modelName: String,
    textColor: Color,
    chevronColor: Color,
    showChevron: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 1.dp),
    ) {
        ModelBrandMark(
            modelId = selected?.modelId,
            sourceType = selected?.providerSourceType,
            size = 11.dp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = modelName,
            style = MiuixTheme.textStyles.footnote2.copy(
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 168.dp),
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = chevronColor,
            )
        }
    }
}

@Composable
private fun ModelQuickList(
    state: AgentModelPickerUiState,
    onModelSelected: (String) -> Unit,
) {
    val select = rememberConfirmedSelection(onModelSelected)
    val selectedId = select.pendingId ?: state.selectedModel?.id
    val showLabels = state.providerGroups.size > 1
    Column(
        modifier = Modifier
            .width(ModelPopupWidth)
            .verticalScroll(rememberScrollState())
            .padding(top = if (showLabels) 0.dp else 6.dp, bottom = 6.dp),
    ) {
        state.providerGroups.forEach { group ->
            if (showLabels) ModelProviderLabel(group.providerName)
            group.models.forEach { model ->
                ModelPickerRow(
                    model = model,
                    selected = model.id == selectedId,
                    onClick = { select(model.id) },
                )
            }
        }
    }
}

private sealed interface ModelSheetEntry {
    val key: String

    data class Header(val providerId: String, val name: String) : ModelSheetEntry {
        override val key: String get() = "header:$providerId"
    }

    data class Option(val model: AgentModelOptionUi) : ModelSheetEntry {
        override val key: String get() = "model:${model.providerId}:${model.id}"
    }
}

/**
 * 模型选择浮层内容（分组 + 搜索 + 当前项打勾）：顶栏旧入口与输入栏合并控件共用同一份，
 * 不许两处各画一份列表（链式反应防冲突：选择口径只能有一份）。
 */
@Composable
internal fun ModelSheetContent(
    state: AgentModelPickerUiState,
    onModelSelected: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val entries = remember(state.providerGroups, query, providerFilter) {
        state.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
            .flatMap { group ->
                // 只看一个服务商时，筛选标签已经说明了归属，分组标题只会重复。
                val header = if (providerFilter == null) {
                    listOf(ModelSheetEntry.Header(group.providerId, group.providerName))
                } else {
                    emptyList()
                }
                header + group.models.map(ModelSheetEntry::Option)
            }
    }
    val selectedIndex = entries.indexOfFirst {
        it is ModelSheetEntry.Option && it.model.id == state.selectedModel?.id
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(listState) {
        snapshotFlow { query to providerFilter }.drop(1).collect { listState.scrollToItem(0) }
    }
    val select = rememberConfirmedSelection(onModelSelected)
    val selectedId = select.pendingId ?: state.selectedModel?.id
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .then(if (isCoui) Modifier else Modifier.navigationBarsPadding()),
    ) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiSearchBarBox(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.ui_search_model_df5586),
                    backgroundColor = Color.Transparent,
                )
                if (state.providerGroups.size > 1) {
                    val allLabel = stringResource(R.string.model_picker_filter_all)
                    val tabItems = remember(state.providerGroups, allLabel) {
                        listOf("" to allLabel) + state.providerGroups.map { it.providerId to it.providerName }
                    }
                    CouiChipTabs(
                        items = tabItems,
                        selectedKey = providerFilter.orEmpty(),
                        onSelectedKeyChange = { key -> providerFilter = key.takeIf { it.isNotEmpty() } },
                        backgroundColor = Color.Transparent,
                    )
                }
            }
            AppearanceUiStyle.MIUIX -> {
                InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.ui_search_model_df5586),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 8.dp),
                )
                if (state.providerGroups.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        item(key = "all") {
                            EtaChoiceChip(
                                text = stringResource(R.string.model_picker_filter_all),
                                selected = providerFilter == null,
                                onClick = { providerFilter = null },
                            )
                        }
                        items(state.providerGroups, key = { it.providerId }) { group ->
                            EtaChoiceChip(
                                text = group.providerName,
                                selected = providerFilter == group.providerId,
                                onClick = { providerFilter = group.providerId },
                            )
                        }
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.page_no_matching_model_found_ae7e96),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 48.dp),
                    )
                }
            }
            items(entries, key = ModelSheetEntry::key, contentType = { it::class }) { entry ->
                when (entry) {
                    is ModelSheetEntry.Header -> ModelProviderLabel(entry.name, horizontalPadding = 24.dp)
                    is ModelSheetEntry.Option -> ModelPickerRow(
                        model = entry.model,
                        selected = entry.model.id == selectedId,
                        onClick = { select(entry.model.id) },
                        horizontalPadding = 12.dp,
                    )
                }
            }
        }
    }
}

/**
 * 点选后先把对勾移到新模型上，停留片刻再收起弹层，让人看清选中了谁。
 * 弹层收起会让作用域随组合一起离开，未完成的确认随之取消，不会回写已关闭的界面。
 */
private class ConfirmedSelection(
    private val onConfirm: (String) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    var pendingId by mutableStateOf<String?>(null)
        private set

    operator fun invoke(modelId: String) {
        if (pendingId != null) return
        pendingId = modelId
        launch {
            delay(SelectionConfirmMs)
            onConfirm(modelId)
        }
    }
}

@Composable
private fun rememberConfirmedSelection(onConfirm: (String) -> Unit): ConfirmedSelection {
    val scope = rememberCoroutineScope()
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    return remember { ConfirmedSelection({ currentOnConfirm(it) }) { block -> scope.launch { block() } } }
}

@Composable
private fun ModelProviderLabel(name: String, horizontalPadding: Dp = 20.dp) {
    Text(
        text = name,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelPickerRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp = 8.dp,
) {
    val containerColor = if (selected) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent
    val contentColor = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 2.dp)
            .squircleSurface(color = containerColor, cornerRadius = 12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.displayName,
            style = MiuixTheme.textStyles.body2,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.ui_current_model_a0af8f),
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
internal fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MiuixTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.56f),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private val ModelPopupWidth = 248.dp

/** 不超过这个数量时弹窗一眼就能看完，不需要搜索。 */
private const val ModelPopupLimit = 8

private const val SelectionConfirmMs = 140L
