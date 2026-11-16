package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider as CouiDivider
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import androidx.compose.ui.window.Dialog
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.forge.ForgeArchivedSessions
import io.github.mangi.eta.agent.forge.ForgeWorkspace
import io.github.mangi.eta.agent.forge.ForgeWorkspaces
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ForgeChatMode
import io.github.mangi.eta.data.model.forgeSessionIdOf
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

@Stable
private class PaneBarInsets {
    var top by mutableStateOf(0.dp)
    var bottom by mutableStateOf(0.dp)
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: Dp,
    listState: LazyListState,
    isRevealed: Boolean,
    activeModelLabel: String?,
    hasPermissionAttention: Boolean,
    chatMode: ForgeChatMode,
    onChatModeSelected: (ForgeChatMode) -> Unit,
    forgeWorkspaces: List<ForgeWorkspace> = emptyList(),
    onForgeNewConversationInWorkspace: (String) -> Unit = {},
    onForgeWorkspacesChanged: () -> Unit = {},
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val isSearching = query.isNotBlank()
    val groups = remember(state.conversations) { state.conversations.groupForDrawer() }
    // Forge 模式的工作区视图：会话按 opencode 目录归到各工作区名下（见列表分支）。
    // 归档是 App 侧本地记录；本地搜索只过滤 Forge 分区，不动底部全局搜索。
    val isForgeMode = chatMode == ForgeChatMode.Forge
    var forgeArchivedIds by remember { mutableStateOf(ForgeArchivedSessions.all()) }
    var forgeShowArchived by remember { mutableStateOf(false) }
    var forgeSearchOpen by remember { mutableStateOf(false) }
    var forgeLocalQuery by remember { mutableStateOf("") }
    var folderPickerOpen by remember { mutableStateOf(false) }
    var workspaceActionTarget by remember { mutableStateOf<ForgeWorkspace?>(null) }
    var workspaceRenameTarget by remember { mutableStateOf<ForgeWorkspace?>(null) }
    var workspaceRemoveTarget by remember { mutableStateOf<ForgeWorkspace?>(null) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    val forgeConversations = remember(state.conversations, forgeArchivedIds, forgeShowArchived, forgeLocalQuery) {
        state.conversations.filter { it.forge }
            .filter { conversation ->
                val sessionId = forgeSessionIdOf(conversation.id)
                forgeShowArchived || sessionId == null || sessionId !in forgeArchivedIds
            }
            .filter {
                forgeLocalQuery.isBlank() ||
                    it.title.contains(forgeLocalQuery.trim(), ignoreCase = true) ||
                    it.preview.contains(forgeLocalQuery.trim(), ignoreCase = true)
            }
    }
    val forgeGroups = remember(forgeConversations, forgeWorkspaces) {
        val byWorkspace = forgeWorkspaces.map { workspace ->
            workspace to forgeConversations.filter { it.forgeDirectory == workspace.linuxPath }
        }
        val groupedIds = byWorkspace.flatMap { it.second }.map { it.id }.toSet()
        val others = forgeConversations.filter { it.id !in groupedIds }
        byWorkspace to others
    }
    val currentForgeWorkspaceName = remember(forgeWorkspaces) {
        ForgeWorkspaces.currentSelection()?.name
    }
    val containerColor = conversationPaneContainerColor()
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiBackdrop = if (isCoui) rememberCouiBackdrop() else null
    val miuixBackdrop = if (!isCoui) rememberTopBarBackdrop() else null
    val barInsets = remember { PaneBarInsets() }
    val bodyCaptureModifier = when {
        isCoui && couiBackdrop != null -> Modifier.couiBackdrop(couiBackdrop)
        !isCoui && miuixBackdrop != null -> Modifier.captureForTopBar(miuixBackdrop)
        else -> Modifier
    }

    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(containerColor),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 测量顶栏与底栏真实高度（含安全区与 IME），同步给滚动画布的 contentPadding，
            // 使列表自然穿梭于顶底浮层之下并分别被各自 backdrop 采样，同时避免 Subcompose 重组开销。
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(containerColor)
                            .then(bodyCaptureModifier),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                                )
                                .scrollEndHaptic()
                                .overScrollVertical(),
                            contentPadding = PaddingValues(
                                top = barInsets.top + 10.dp,
                                bottom = barInsets.bottom + 10.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                            overscrollEffect = null,
                        ) {
                            if (!isSearching) {
                                item(key = "pane_mode_switch") {
                                    AssistForgeSwitch(
                                        mode = chatMode,
                                        onModeSelected = onChatModeSelected,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                                    )
                                }
                                item(key = "pane_workbench") {
                                    PaneWorkbenchSection(
                                        activeModelLabel = activeModelLabel,
                                        onOpenModelProviders = onOpenModelProviders,
                                        onOpenCharacters = onOpenCharacters,
                                        onOpenTools = onOpenTools,
                                        onOpenSkills = onOpenSkills,
                                    )
                                }
                            }

                            if (isForgeMode && !isSearching) {
                                // Forge 模式：会话按工作区分组（工作区头行带「...」管理与「+」新建），
                                // 顶上一行是分区头：搜索 / 已归档显隐 / 添加工作区。
                                item(key = "forge_ws_section_header") {
                                    ForgeWorkspaceSectionHeader(
                                        showArchived = forgeShowArchived,
                                        onSearchClick = { forgeSearchOpen = !forgeSearchOpen },
                                        onToggleArchived = { forgeShowArchived = !forgeShowArchived },
                                        onAddWorkspace = { folderPickerOpen = true },
                                    )
                                }
                                if (forgeSearchOpen) {
                                    item(key = "forge_ws_search") {
                                        ForgeWorkspaceSearchField(
                                            query = forgeLocalQuery,
                                            onQueryChange = { forgeLocalQuery = it },
                                        )
                                    }
                                }
                                val (workspaceGroups, otherForgeConversations) = forgeGroups
                                workspaceGroups.forEach { (workspace, sessions) ->
                                    item(key = "forge_ws_header_${workspace.name}") {
                                        ForgeWorkspaceGroupHeader(
                                            workspace = workspace,
                                            isCurrent = workspace.name == currentForgeWorkspaceName,
                                            onMenuClick = { workspaceActionTarget = workspace },
                                            onNewConversation = {
                                                onForgeNewConversationInWorkspace(workspace.name)
                                            },
                                        )
                                    }
                                    items(
                                        items = sessions,
                                        key = { "forge_ws_${workspace.name}_${it.id}" },
                                    ) { conversation ->
                                        ForgeConversationRow(
                                            conversation = conversation,
                                            selected = conversation.id == state.selectedConversationId,
                                            archivedIds = forgeArchivedIds,
                                            onClick = { onConversationSelected(conversation.id) },
                                            onRename = { onConversationRename(conversation) },
                                            onDelete = { onConversationDelete(conversation) },
                                            onArchivedIdsChange = { forgeArchivedIds = it },
                                        )
                                    }
                                }
                                if (otherForgeConversations.isNotEmpty()) {
                                    item(key = "forge_ws_others_header") {
                                        ForgeOtherSessionsHeader()
                                    }
                                    items(
                                        items = otherForgeConversations,
                                        key = { "forge_ws_other_${it.id}" },
                                    ) { conversation ->
                                        ForgeConversationRow(
                                            conversation = conversation,
                                            selected = conversation.id == state.selectedConversationId,
                                            archivedIds = forgeArchivedIds,
                                            onClick = { onConversationSelected(conversation.id) },
                                            onRename = { onConversationRename(conversation) },
                                            onDelete = { onConversationDelete(conversation) },
                                            onArchivedIdsChange = { forgeArchivedIds = it },
                                        )
                                    }
                                }
                                if (forgeWorkspaces.isEmpty() && forgeConversations.isEmpty()) {
                                    item(key = "forge_ws_empty") {
                                        EmptyConversations(isSearching = false)
                                    }
                                }
                            } else if (state.conversations.isEmpty()) {
                                item(key = "pane_empty_conversations") {
                                    EmptyConversations(isSearching = isSearching)
                                }
                            } else {
                                groups.forEachIndexed { groupIndex, group ->
                                    item(key = "section-${group.section}") {
                                        ConversationSectionHeader(
                                            group = group,
                                            isFirstSection = groupIndex == 0,
                                            hasWorkbenchAbove = !isSearching,
                                        )
                                    }
                                    items(
                                        items = group.items,
                                        key = { it.id },
                                    ) { conversation ->
                                        ConversationHistoryRow(
                                            conversation = conversation,
                                            selected = conversation.id == state.selectedConversationId,
                                            onClick = { onConversationSelected(conversation.id) },
                                            onTogglePin = { onConversationTogglePin(conversation) },
                                            onRename = { onConversationRename(conversation) },
                                            onExport = { onConversationExport(conversation) },
                                            onDelete = { onConversationDelete(conversation) },
                                        )
                                    }
                                }
                            }
                            // Forge 列表没拉到（server 没在跑）时在末尾留一行说明，
                            // 别让 Forge 会话整批消失得无声无息。
                            state.forgeListHint?.let { hint ->
                                item(key = "pane_forge_hint") {
                                    Text(
                                        text = hint,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 10.dp,
                                            bottom = 4.dp,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    ConversationPaneTopBar(
                        containerColor = containerColor,
                        couiBackdrop = couiBackdrop,
                        miuixBackdrop = miuixBackdrop,
                        onNewConversation = onNewConversation,
                    )
                    ConversationPaneBottomDock(
                        query = state.searchQuery,
                        isRevealed = isRevealed,
                        hasPermissionAttention = hasPermissionAttention,
                        containerColor = containerColor,
                        couiBackdrop = couiBackdrop,
                        miuixBackdrop = miuixBackdrop,
                        onSearchChange = onSearchChange,
                        onOpenPermissions = onOpenPermissions,
                        onOpenSettings = onOpenSettings,
                    )
                },
            ) { measurables, constraints ->
                val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val topBar = measurables[1].measure(looseConstraints)
                val bottomDock = measurables[2].measure(looseConstraints)
                val topDp = topBar.height.toDp()
                val bottomDp = bottomDock.height.toDp()
                if (barInsets.top != topDp) barInsets.top = topDp
                if (barInsets.bottom != bottomDp) barInsets.bottom = bottomDp
                val body = measurables[0].measure(constraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    body.placeRelative(0, 0)
                    topBar.placeRelative(0, 0)
                    bottomDock.placeRelative(0, constraints.maxHeight - bottomDock.height)
                }
            }

            // 侧栏物理右侧细微收边，保证浅/深/纯黑模式下与右侧聊天舞台始终保持利落边界。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(0.5.dp)
                    .background(conversationPaneEdgeBorderColor()),
            )

            // Forge 工作区管理弹窗群（文件夹选择器 / 改名 / 移除 / 报错），都挂在侧栏根 Box 里。
            val workspaceAddFailedText = stringResource(R.string.forge_workspace_add_failed)
            val workspaceRenameFailedText = stringResource(R.string.forge_workspace_rename_failed)
            if (folderPickerOpen) {
                FolderPickerDialog(
                    onDismiss = { folderPickerOpen = false },
                    onConfirm = { path ->
                        folderPickerOpen = false
                        ForgeWorkspaces.add(path)
                            .onSuccess { created ->
                                onForgeWorkspacesChanged()
                                onForgeNewConversationInWorkspace(created.name)
                            }
                            .onFailure { workspaceError = workspaceAddFailedText }
                    },
                )
            }
            workspaceActionTarget?.let { workspace ->
                ForgeWorkspaceActionDialog(
                    workspace = workspace,
                    onDismiss = { workspaceActionTarget = null },
                    onRename = {
                        workspaceActionTarget = null
                        workspaceRenameTarget = workspace
                    },
                    onRemove = {
                        workspaceActionTarget = null
                        workspaceRemoveTarget = workspace
                    },
                )
            }
            workspaceRenameTarget?.let { workspace ->
                ForgeWorkspaceRenameDialog(
                    workspace = workspace,
                    onDismiss = { workspaceRenameTarget = null },
                    onConfirm = { newName ->
                        workspaceRenameTarget = null
                        ForgeWorkspaces.rename(workspace.name, newName)
                            .onSuccess { onForgeWorkspacesChanged() }
                            .onFailure { workspaceError = workspaceRenameFailedText }
                    },
                )
            }
            workspaceRemoveTarget?.let { workspace ->
                ForgeWorkspaceRemoveDialog(
                    workspace = workspace,
                    onDismiss = { workspaceRemoveTarget = null },
                    onConfirm = {
                        workspaceRemoveTarget = null
                        ForgeWorkspaces.remove(workspace.name)
                        onForgeWorkspacesChanged()
                    },
                )
            }
            workspaceError?.let { message ->
                ForgeWorkspaceErrorDialog(
                    message = message,
                    onDismiss = { workspaceError = null },
                )
            }
        }
    }
}

private data class WorkbenchEntry(
    val icon: ImageVector,
    val title: String,
    val trailingValue: String? = null,
    val onClick: () -> Unit,
)

@Composable
private fun PaneWorkbenchSection(
    activeModelLabel: String?,
    onOpenModelProviders: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    val modelSummary = activeModelLabel?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.conversation_model_unconfigured)
    val entries = listOf(
        WorkbenchEntry(
            icon = ConversationPaneIcons.Model,
            title = stringResource(R.string.conversation_dock_models),
            trailingValue = modelSummary,
            onClick = onOpenModelProviders,
        ),
        WorkbenchEntry(
            icon = ConversationPaneIcons.Character,
            title = stringResource(R.string.conversation_dock_characters),
            onClick = onOpenCharacters,
        ),
        WorkbenchEntry(
            icon = ConversationPaneIcons.Tools,
            title = stringResource(R.string.route_tools),
            onClick = onOpenTools,
        ),
        WorkbenchEntry(
            icon = ConversationPaneIcons.Skills,
            title = stringResource(R.string.route_skills),
            onClick = onOpenSkills,
        ),
    )

    val cardColor = conversationPaneCardColor()
    val dividerIndent = ConversationPanelMetrics.WorkbenchRowHorizontalPadding +
        ConversationPanelMetrics.WorkbenchIconSize +
        ConversationPanelMetrics.WorkbenchIconGap

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val shape = remember { CouiShape(ConversationPanelMetrics.WorkbenchCornerRadius, 2.5f) }
            val dark = CouiTheme.isDark
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
                    .then(
                        if (!dark) {
                            Modifier.dropShadow(
                                shape = shape,
                                shadow = Shadow(
                                    radius = 10.dp,
                                    color = Color.Black,
                                    alpha = 0.025f,
                                ),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(shape)
                    .background(cardColor, shape)
                    .then(
                        if (!dark) {
                            Modifier.border(0.5.dp, Color(0x0A000000), shape)
                        } else {
                            Modifier.border(0.5.dp, Color(0x12FFFFFF), shape)
                        },
                    ),
            ) {
                entries.forEachIndexed { index, entry ->
                    CouiWorkbenchRow(entry = entry, dark = dark)
                    if (index < entries.lastIndex) {
                        CouiDivider(
                            startIndent = dividerIndent,
                            endIndent = ConversationPanelMetrics.WorkbenchRowHorizontalPadding,
                        )
                    }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            val colors = MiuixTheme.colorScheme
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
                colors = CardDefaults.defaultColors(color = cardColor),
            ) {
                entries.forEachIndexed { index, entry ->
                    MiuixWorkbenchRow(entry = entry)
                    if (index < entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                start = dividerIndent,
                                end = ConversationPanelMetrics.WorkbenchRowHorizontalPadding,
                            ),
                            thickness = 0.5.dp,
                            color = colors.dividerLine.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CouiWorkbenchRow(
    entry: WorkbenchEntry,
    dark: Boolean,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    val source = remember { MutableInteractionSource() }
    val chevronTint = Color(if (dark) 0x4DFFFFFF else 0x42000000)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ConversationPanelMetrics.WorkbenchRowMinHeight)
            .couiButtonFeedback(dark, source, enabled = true, list = true)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = entry.onClick,
            )
            .padding(
                horizontal = ConversationPanelMetrics.WorkbenchRowHorizontalPadding,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouiIcon(
            imageVector = entry.icon,
            contentDescription = null,
            modifier = Modifier.size(ConversationPanelMetrics.WorkbenchIconSize),
            tint = colors.title.copy(alpha = 0.86f),
        )
        Spacer(Modifier.width(ConversationPanelMetrics.WorkbenchIconGap))
        CouiText(
            text = entry.title,
            kind = CouiTextKind.ItemTitle,
            color = colors.title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!entry.trailingValue.isNullOrEmpty()) {
            CouiText(
                text = entry.trailingValue,
                kind = CouiTextKind.ItemValue,
                color = colors.summary,
                modifier = Modifier
                    .widthIn(max = 136.dp)
                    .padding(start = 8.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CouiIcon(
            imageVector = CouiIcons.Outlined.Next,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(width = 11.dp, height = 20.dp),
            tint = chevronTint,
        )
    }
}

@Composable
private fun MiuixWorkbenchRow(entry: WorkbenchEntry) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ConversationPanelMetrics.WorkbenchRowMinHeight)
            .clickable(role = Role.Button, onClick = entry.onClick)
            .padding(
                horizontal = ConversationPanelMetrics.WorkbenchRowHorizontalPadding,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            modifier = Modifier.size(ConversationPanelMetrics.WorkbenchIconSize),
            tint = colors.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(ConversationPanelMetrics.WorkbenchIconGap))
        Text(
            text = entry.title,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!entry.trailingValue.isNullOrEmpty()) {
            Text(
                text = entry.trailingValue,
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier
                    .widthIn(max = 136.dp)
                    .padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = ConversationPaneIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(14.dp),
            tint = colors.onSurfaceVariantActions,
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    isFirstSection: Boolean,
    hasWorkbenchAbove: Boolean,
) {
    val label = group.localizedLabel()
    val topPadding = when {
        isFirstSection && hasWorkbenchAbove -> ConversationPanelMetrics.FirstSectionTopPadding
        isFirstSection -> 8.dp
        else -> ConversationPanelMetrics.SectionTopPadding
    }
    val horizontalInset = ConversationPanelMetrics.PaneHorizontalPadding +
        ConversationPanelMetrics.RowHorizontalPadding

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiText(
                text = label,
                kind = CouiTextKind.GroupTitle,
                color = colors.summary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalInset,
                        top = topPadding,
                        end = horizontalInset,
                        bottom = ConversationPanelMetrics.SectionBottomPadding,
                    )
                    .semantics { heading() },
                textAlign = TextAlign.Start,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalInset,
                        top = topPadding,
                        end = horizontalInset,
                        bottom = ConversationPanelMetrics.SectionBottomPadding,
                    )
                    .semantics { heading() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onArchiveToggle: (() -> Unit)? = null,
    archived: Boolean = false,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val anchor = rememberCouiMenuAnchor()
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val couiRowShape = remember { CouiShape(ConversationPanelMetrics.RowCornerRadius, 2.1f) }
    val miuixColors = MiuixTheme.colorScheme
    val miuixDark = miuixColors.surface.luminance() < 0.5f

    val rowBackground = when {
        !selected -> Color.Transparent
        isCoui -> if (couiDark) Color(0xFF262932) else Color.White
        else -> miuixColors.tertiaryContainer.copy(alpha = if (miuixDark) 0.72f else 0.88f)
    }
    val rowModifier = if (isCoui) {
        val source = remember { MutableInteractionSource() }
        Modifier
            .then(
                if (selected && !couiDark) {
                    Modifier.dropShadow(
                        shape = couiRowShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black,
                            alpha = 0.028f,
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .clip(couiRowShape)
            .background(rowBackground, couiRowShape)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 0.5.dp,
                        color = if (couiDark) Color(0x1AFFFFFF) else Color(0x0D000000),
                        shape = couiRowShape,
                    )
                } else {
                    Modifier
                },
            )
            .couiButtonFeedback(couiDark, source, enabled = true, list = true)
    } else {
        Modifier.squircleSurface(
            color = rowBackground,
            cornerRadius = ConversationPanelMetrics.RowCornerRadius,
        )
    }

    val titleColor = if (isCoui) {
        CouiPageDefaults.themeColors(couiDark).title
    } else {
        miuixColors.onSurface
    }
    val summaryColor = if (isCoui) {
        CouiPageDefaults.themeColors(couiDark).summary
    } else {
        miuixColors.onSurfaceVariantSummary
    }
    val accentColor = if (isCoui) {
        CouiTheme.colorSchemeFor(couiDark).accent
    } else {
        miuixColors.primary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .couiMenuAnchor(anchor)
                .then(rowModifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor.open { showActionMenu = true }
                    },
                )
                .padding(
                    start = ConversationPanelMetrics.RowHorizontalPadding,
                    end = if (selected) 8.dp else ConversationPanelMetrics.RowHorizontalPadding,
                    top = ConversationPanelMetrics.RowVerticalPadding,
                    bottom = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conversation.characterName != null) {
                CharacterAvatar(
                    name = conversation.characterName,
                    avatarPath = conversation.characterAvatarPath,
                    size = 30.dp,
                    shape = CircleShape,
                    modifier = if (selected) {
                        Modifier.border(1.5.dp, accentColor.copy(alpha = 0.75f), CircleShape)
                    } else {
                        Modifier
                    },
                )
                Spacer(Modifier.width(11.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                val title = conversation.title.ifBlank { conversation.preview }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = titleColor,
                        style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.forge) {
                        Text(
                            text = stringResource(R.string.forge_mode_forge),
                            color = accentColor,
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                // 标题与角色名相同（如未改名的角色会话）时不再重复第二行。
                conversation.characterName?.takeIf { it != title }?.let { name ->
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                        color = summaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                conversation.runStatus?.let { status ->
                    Text(
                        text = status,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .size(ConversationPanelMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(accentColor),
                )
            } else if (conversation.isPinned) {
                Icon(
                    imageVector = ConversationPaneIcons.Pin,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(13.dp),
                    tint = summaryColor.copy(alpha = 0.72f),
                )
            }

            // Forge 条目只有重命名/删除（走 opencode 接口），没有置顶/导出，菜单里按 forge 裁剪。
            if (selected) {
                val moreLabel = stringResource(R.string.action_more)
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = moreLabel,
                        ) {
                            anchor.open { showActionMenu = true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = moreLabel,
                        modifier = Modifier.size(17.dp),
                        tint = summaryColor,
                    )
                }
            }
        }

        // 将选中指示条放在左内边距留白区，保证选中行与普通行标题严格处于同一条左对齐基线。
        if (selected && conversation.characterName == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 5.5.dp)
                    .width(2.5.dp)
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(accentColor),
            )
        }

        val pinText = stringResource(
            if (conversation.isPinned) {
                R.string.action_unpin_conversation
            } else {
                R.string.action_pin_conversation
            },
        )
        val renameText = stringResource(R.string.action_rename)
        val exportText = stringResource(R.string.action_export)
        val deleteText = stringResource(R.string.action_delete)
        val archiveText = stringResource(
            if (archived) R.string.forge_session_unarchive else R.string.forge_session_archive,
        )
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                val destructiveColor = CouiDialogDefaults.themeColors().destructive
                val menuItems = remember(pinText, renameText, exportText, deleteText, archiveText, destructiveColor, conversation.forge) {
                    if (conversation.forge) {
                        buildList {
                            add(CouiMenuItem(value = 1, text = renameText, icon = Icons.Outlined.Edit))
                            if (onArchiveToggle != null) {
                                add(
                                    CouiMenuItem(
                                        value = 4,
                                        text = archiveText,
                                        icon = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                    ),
                                )
                            }
                            add(
                                CouiMenuItem(
                                    value = 3,
                                    text = deleteText,
                                    icon = Icons.Outlined.Delete,
                                    iconTint = destructiveColor,
                                    dividerBefore = true,
                                ),
                            )
                        }
                    } else {
                        listOf(
                            CouiMenuItem(value = 0, text = pinText, icon = Icons.Outlined.PushPin),
                            CouiMenuItem(value = 1, text = renameText, icon = Icons.Outlined.Edit),
                            CouiMenuItem(value = 2, text = exportText, icon = Icons.Outlined.Download),
                            CouiMenuItem(
                                value = 3,
                                text = deleteText,
                                icon = Icons.Outlined.Delete,
                                iconTint = destructiveColor,
                                dividerBefore = true,
                            ),
                        )
                    }
                }
                Menu(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                    items = menuItems,
                    onItemClick = { item ->
                        showActionMenu = false
                        when (item.value) {
                            0 -> onTogglePin()
                            1 -> onRename()
                            2 -> onExport()
                            3 -> onDelete()
                            4 -> onArchiveToggle?.invoke()
                        }
                    },
                    anchor = anchor,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowListPopup(
                    show = showActionMenu,
                    popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                    alignment = PopupPositionProvider.Align.BottomEnd,
                    onDismissRequest = { showActionMenu = false },
                ) {
                    val pinItem = remember(pinText) {
                        DropdownItem(
                            text = pinText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.PushPin,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val renameItem = remember(renameText) {
                        DropdownItem(
                            text = renameText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val exportItem = remember(exportText) {
                        DropdownItem(
                            text = exportText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val archiveItem = remember(archiveText, archived) {
                        DropdownItem(
                            text = archiveText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                )
                            },
                        )
                    }
                    val deleteItem = remember(deleteText) {
                        DropdownItem(
                            text = deleteText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    modifier = modifier.size(ConversationPanelMetrics.ActionIconSize),
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                    val forgeMenuSize = if (onArchiveToggle != null) 3 else 2
                    val deleteColors = DropdownDefaults.dropdownColors(
                        contentColor = MiuixTheme.colorScheme.error,
                        selectedContentColor = MiuixTheme.colorScheme.error,
                        selectedIndicatorColor = MiuixTheme.colorScheme.error,
                    )
                    ListPopupColumn {
                        if (!conversation.forge) {
                            DropdownImpl(
                                item = pinItem,
                                optionSize = 4,
                                isSelected = false,
                                index = 0,
                                onSelectedIndexChange = {
                                    showActionMenu = false
                                    onTogglePin()
                                },
                            )
                        }
                        DropdownImpl(
                            item = renameItem,
                            optionSize = if (conversation.forge) forgeMenuSize else 4,
                            isSelected = false,
                            index = if (conversation.forge) 0 else 1,
                            onSelectedIndexChange = {
                                showActionMenu = false
                                onRename()
                            },
                        )
                        if (conversation.forge && onArchiveToggle != null) {
                            DropdownImpl(
                                item = archiveItem,
                                optionSize = forgeMenuSize,
                                isSelected = false,
                                index = 1,
                                onSelectedIndexChange = {
                                    showActionMenu = false
                                    onArchiveToggle()
                                },
                            )
                        }
                        if (!conversation.forge) {
                            DropdownImpl(
                                item = exportItem,
                                optionSize = 4,
                                isSelected = false,
                                index = 2,
                                onSelectedIndexChange = {
                                    showActionMenu = false
                                    onExport()
                                },
                            )
                        }
                        DropdownImpl(
                            item = deleteItem,
                            optionSize = if (conversation.forge) forgeMenuSize else 4,
                            isSelected = false,
                            index = if (conversation.forge) forgeMenuSize - 1 else 3,
                            dropdownColors = deleteColors,
                            onSelectedIndexChange = {
                                showActionMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val dark = if (isCoui) CouiTheme.isDark else MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val primaryTextColor = if (isCoui) {
        CouiPageDefaults.themeColors(dark).summary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val badgeBg = conversationPaneCardColor()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ConversationPanelMetrics.PaneHorizontalPadding + 12.dp,
                vertical = ConversationPanelMetrics.EmptyVerticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(badgeBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSearching) ConversationPaneIcons.Search else ConversationPaneIcons.BrandEta,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = primaryTextColor.copy(alpha = 0.72f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
            ),
            color = primaryTextColor,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (!isSearching) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.conversation_empty_hint),
                color = primaryTextColor.copy(alpha = 0.75f),
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    // 必须按 section 全量归并，不能只合并相邻项：列表按更新时间排序，
    // 但 section 还看 isActiveRun / 时间是否为 0——一个正在后台跑的旧对话
    // 或时间缺失的条目会被判进 Today 却排在旧日期分组中间，只合并相邻项
    // 就会把同一 section 切成两组，LazyColumn 里两个组头 key 相同直接闪退。
    val bySection = LinkedHashMap<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in this) {
        bySection.getOrPut(conversation.drawerSection()) { mutableListOf() } += conversation
    }
    return bySection.map { (section, items) ->
        ConversationDrawerGroup(section = section, items = items)
    }
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}

// ---------------------------------------------------------------------------
// Forge 工作区分区（侧边栏 Forge 模式）：分区头、工作区组头、会话行与管理弹窗。
// 按钮规矩（用户定）：相邻按钮至少留 10dp 间隔，短按钮用全圆角，别再贴在一起。
// ---------------------------------------------------------------------------

/** Forge 分区里的一条会话行：在通用行上接好归档动作（归档状态走本地记录）。 */
@Composable
private fun ForgeConversationRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    archivedIds: Set<String>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onArchivedIdsChange: (Set<String>) -> Unit,
) {
    val sessionId = forgeSessionIdOf(conversation.id)
    ConversationHistoryRow(
        conversation = conversation,
        selected = selected,
        onClick = onClick,
        onTogglePin = {},
        onRename = onRename,
        onExport = {},
        onDelete = onDelete,
        onArchiveToggle = if (sessionId != null) {
            {
                ForgeArchivedSessions.setArchived(sessionId, sessionId !in archivedIds)
                onArchivedIdsChange(ForgeArchivedSessions.all())
            }
        } else {
            null
        },
        archived = sessionId != null && sessionId in archivedIds,
    )
}

/** Forge 分区头：「工作区」标题 + 搜索 / 已归档显隐 / 添加工作区三个按钮。 */
@Composable
private fun ForgeWorkspaceSectionHeader(
    showArchived: Boolean,
    onSearchClick: () -> Unit,
    onToggleArchived: () -> Unit,
    onAddWorkspace: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ConversationPanelMetrics.PaneHorizontalPadding, end = 10.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.forge_workspace_title),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        ForgeHeaderIconButton(
            icon = Icons.Outlined.Search,
            label = stringResource(R.string.forge_workspace_search),
            onClick = onSearchClick,
        )
        ForgeHeaderIconButton(
            icon = if (showArchived) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            label = stringResource(
                if (showArchived) R.string.forge_workspace_hide_archived else R.string.forge_workspace_show_archived,
            ),
            highlighted = showArchived,
            onClick = onToggleArchived,
        )
        ForgeHeaderIconButton(
            icon = Icons.Rounded.CreateNewFolder,
            label = stringResource(R.string.forge_workspace_add),
            onClick = onAddWorkspace,
        )
    }
}

/** 分区头上的圆形图标按钮：34dp 点击区，按钮之间留 6dp（连同内边距，相邻点击区不贴脸）。 */
@Composable
private fun ForgeHeaderIconButton(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(if (highlighted) colors.tertiaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(19.dp),
            tint = if (highlighted) colors.primary else colors.onSurfaceVariantSummary,
        )
    }
}

/** Forge 分区本地搜索框：只过滤 Forge 会话标题，不动底部全局搜索。 */
@Composable
private fun ForgeWorkspaceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        label = stringResource(R.string.forge_workspace_search),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding, vertical = 6.dp),
    )
}

/** 工作区组头：文件夹图标 + 名字 + 「...」管理 + 「+」在这个工作区开新对话。 */
@Composable
private fun ForgeWorkspaceGroupHeader(
    workspace: ForgeWorkspace,
    isCurrent: Boolean,
    onMenuClick: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val moreLabel = stringResource(R.string.action_more)
    val newLabel = stringResource(R.string.forge_workspace_new_in)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ConversationPanelMetrics.PaneHorizontalPadding, end = 10.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isCurrent) colors.primary else colors.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = workspace.name,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = moreLabel, onClick = onMenuClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                contentDescription = moreLabel,
                modifier = Modifier.size(18.dp),
                tint = colors.onSurfaceVariantSummary,
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(30.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = newLabel, onClick = onNewConversation),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = newLabel,
                modifier = Modifier.size(19.dp),
                tint = colors.onSurfaceVariantSummary,
            )
        }
    }
}

/** 对不上任何工作区的会话（目录在工作区外/服务端历史遗留）单独成一组。 */
@Composable
private fun ForgeOtherSessionsHeader() {
    Text(
        text = stringResource(R.string.forge_workspace_other_sessions),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(
            start = ConversationPanelMetrics.PaneHorizontalPadding,
            top = 14.dp,
            bottom = 2.dp,
        ),
    )
}

/** 全圆角按钮：短文案按钮统一用它，相邻摆放时由调用方留 10dp 间隔。 */
@Composable
private fun ForgePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val background = when {
        primary -> colors.primary
        destructive -> colors.error
        else -> colors.tertiaryContainer
    }
    val contentColor = when {
        primary || destructive -> colors.onPrimary
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 工作区管理弹窗的统一容器：24dp 圆角、内边距 20dp。 */
@Composable
private fun ForgeWorkspaceDialogContainer(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            content()
        }
    }
}

/** 工作区「...」菜单：改名 / 移除 / 取消，竖排全圆角按钮、间隔 10dp。 */
@Composable
private fun ForgeWorkspaceActionDialog(
    workspace: ForgeWorkspace,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    ForgeWorkspaceDialogContainer(onDismiss = onDismiss) {
        Text(
            text = workspace.name,
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = workspace.sourcePath,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ForgePillButton(
                text = stringResource(R.string.forge_workspace_rename),
                onClick = onRename,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePillButton(
                text = stringResource(R.string.forge_workspace_remove),
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                destructive = true,
            )
            ForgePillButton(
                text = stringResource(R.string.forge_dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 工作区改名弹窗。 */
@Composable
private fun ForgeWorkspaceRenameDialog(
    workspace: ForgeWorkspace,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(workspace.name) { mutableStateOf(workspace.name) }
    ForgeWorkspaceDialogContainer(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.forge_workspace_rename_title),
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.forge_workspace_rename_title),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.forge_workspace_rename_hint),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ForgePillButton(
                text = stringResource(R.string.forge_dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            ForgePillButton(
                text = stringResource(R.string.forge_dialog_confirm),
                onClick = { onConfirm(name.trim()) },
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
    }
}

/** 工作区移除确认弹窗：只删记录，文件夹与文件都留在设备上。 */
@Composable
private fun ForgeWorkspaceRemoveDialog(
    workspace: ForgeWorkspace,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ForgeWorkspaceDialogContainer(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.forge_workspace_remove_confirm_title),
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.forge_workspace_remove_confirm_body, workspace.name),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ForgePillButton(
                text = stringResource(R.string.forge_dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            ForgePillButton(
                text = stringResource(R.string.forge_workspace_remove),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                destructive = true,
            )
        }
    }
}

/** 工作区操作失败的提示弹窗。 */
@Composable
private fun ForgeWorkspaceErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    ForgeWorkspaceDialogContainer(onDismiss = onDismiss) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        ForgePillButton(
            text = stringResource(R.string.forge_dialog_confirm),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            primary = true,
        )
    }
}
