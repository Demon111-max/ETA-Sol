package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.mangi.eta.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选择器固定起点：共享存储根目录，往上不再走（工作区只会落在共享存储里）。 */
private const val PICKER_ROOT = "/sdcard"

/**
 * 文件夹选择器（dsh 式）：面包屑逐级浏览、新建文件夹、显示隐藏文件开关，
 * 底部「取消 / 打开」把当前目录交出去。不让用户手输路径，更不用去文件管理器复制。
 * 按钮规矩：相邻按钮留 10dp 间隔、短按钮全圆角。
 */
@Composable
internal fun FolderPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var currentPath by remember { mutableStateOf(PICKER_ROOT) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var newFolderOpen by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath, showHidden, refreshTick) {
        loaded = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                File(currentPath).listFiles()
                    ?.filter { it.isDirectory && (showHidden || !it.name.startsWith(".")) }
                    ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }
        entries = result.getOrNull() ?: emptyList()
        loadFailed = result.getOrNull() == null
        loaded = true
    }

    val segments = remember(currentPath) {
        currentPath.removePrefix(PICKER_ROOT).split('/').filter { it.isNotBlank() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.folder_picker_title),
                style = MiuixTheme.textStyles.title4,
                color = colors.onSurface,
            )
            // 面包屑：手机存储 › 一级 › 二级，点任意一级直接跳回去。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickerCrumb(
                    label = stringResource(R.string.folder_picker_storage_root),
                    isCurrent = segments.isEmpty(),
                    onClick = { currentPath = PICKER_ROOT },
                )
                segments.forEachIndexed { index, segment ->
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.onSurfaceVariantSummary,
                    )
                    PickerCrumb(
                        label = segment,
                        isCurrent = index == segments.lastIndex,
                        onClick = {
                            currentPath = PICKER_ROOT + segments.take(index + 1)
                                .joinToString(separator = "/", prefix = "/")
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            when {
                loadFailed -> Text(
                    text = stringResource(R.string.folder_picker_load_failed),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                loaded && entries.isEmpty() -> Text(
                    text = stringResource(R.string.folder_picker_empty),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                ) {
                    items(entries, key = { it.absolutePath }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { currentPath = folder.absolutePath }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = colors.onSurfaceVariantSummary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = folder.name,
                                style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerPillButton(
                    text = stringResource(R.string.folder_picker_new_folder),
                    onClick = { newFolderOpen = true },
                    modifier = Modifier.weight(1f),
                )
                PickerPillButton(
                    text = stringResource(R.string.folder_picker_show_hidden),
                    onClick = { showHidden = !showHidden },
                    modifier = Modifier.weight(1f),
                    highlighted = showHidden,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerPillButton(
                    text = stringResource(R.string.forge_dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PickerPillButton(
                    text = stringResource(R.string.folder_picker_open),
                    onClick = { onConfirm(currentPath) },
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
            }
        }
    }

    if (newFolderOpen) {
        NewFolderDialog(
            onDismiss = { newFolderOpen = false },
            onConfirm = { name ->
                newFolderOpen = false
                val created = File(currentPath, name)
                val ok = runCatching {
                    if (created.isDirectory) true else created.mkdirs() && created.isDirectory
                }.getOrDefault(false)
                if (ok) {
                    // 建完直接进去，用户下一步多半就是「打开」选它当工作区。
                    currentPath = created.absolutePath
                } else {
                    refreshTick++
                }
            },
        )
    }
}

/** 面包屑的一级：当前级深色，其余弱色可点。 */
@Composable
private fun PickerCrumb(
    label: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp),
        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        color = if (isCurrent) colors.onSurface else colors.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** 选择器里的全圆角按钮（与侧栏管理弹窗同一套规矩）。 */
@Composable
private fun PickerPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    highlighted: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val background = when {
        primary -> colors.primary
        highlighted -> colors.primary.copy(alpha = 0.16f)
        else -> colors.tertiaryContainer
    }
    val contentColor = when {
        primary -> colors.onPrimary
        highlighted -> colors.primary
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 新建文件夹小弹窗：输名字 → 在当前目录建文件夹并直接进去。 */
@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.folder_picker_new_folder_title),
                style = MiuixTheme.textStyles.title4,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.folder_picker_new_folder_title),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerPillButton(
                    text = stringResource(R.string.forge_dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PickerPillButton(
                    text = stringResource(R.string.folder_picker_create),
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty() && !trimmed.contains('/') && trimmed != "." && trimmed != "..") {
                            onConfirm(trimmed)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
            }
        }
    }
}
