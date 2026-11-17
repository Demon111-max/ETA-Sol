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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选择器默认起点：共享存储根目录（外置 SD 卡由存储设备切换列出，往上仍不再走）。 */
private const val PICKER_ROOT = "/sdcard"

/**
 * 列出可以浏览的存储根：共享存储 + 外置 SD（`/storage/XXXX-XXXX` 这种卷）。
 *
 * 3.4.43（N21）：以前起点写死 `/sdcard` 且没有手输路径入口，外置 SD 卡上的目录
 * 根本没有入口可选（校验层 `SharedFolderMounts.validateSource` 本身是允许 `/storage`
 * 子目录的）。这里把可见的卷都列出来给切换，最小改动补齐这条路。
 */
private fun detectStorageRoots(): List<String> {
    val roots = mutableListOf(PICKER_ROOT)
    val external = runCatching {
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.absolutePath != PICKER_ROOT }
            // /storage/emulated 只是共享存储的上级目录（/sdcard 就在它下面），/storage/self 同理，
            // 列出来只会让用户看到两个指向同一处的入口。
            ?.filter { it.name != "emulated" && it.name != "self" }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }.getOrNull().orEmpty()
    external.forEach { roots += it.absolutePath }
    return roots
}

/**
 * 文件夹选择器（dsh 式）：存储设备切换 + 面包屑逐级浏览、新建文件夹、显示隐藏文件开关，
 * 底部「取消 / 打开」把当前目录交出去。不用去文件管理器复制路径。
 * 按钮规矩：相邻按钮留 10dp 间隔、短按钮全圆角。
 */
@Composable
internal fun FolderPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 当前目录与隐藏文件开关要能扛住配置变更/进程重建（rememberSaveable），
    // 否则转屏后选择器会跳回根目录。
    var currentRoot by rememberSaveable { mutableStateOf(PICKER_ROOT) }
    var currentPath by rememberSaveable { mutableStateOf(PICKER_ROOT) }
    // 可选存储设备（共享存储 / 外置 SD）：首次组合时在 IO 线程探一次，探不到就只剩共享存储。
    var storageRoots by remember { mutableStateOf(listOf(PICKER_ROOT)) }
    LaunchedEffect(Unit) {
        storageRoots = withContext(Dispatchers.IO) { detectStorageRoots() }
    }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath, showHidden, refreshTick) {
        // 切目录时先复位：否则上一目录的「读取失败」会闪一下，而且确认按钮会沿用旧结论。
        loaded = false
        loadFailed = false
        confirmError = null
        entries = emptyList()
        var failed = false
        val listed = withContext(Dispatchers.IO) {
            try {
                File(currentPath).listFiles()
                    ?.filter { it.isDirectory && (showHidden || !it.name.startsWith(".")) }
                    ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            } catch (cancellation: CancellationException) {
                // 协程取消必须继续上抛，不能被当成「读取失败」（否则取消一次就闪一屏错误）。
                throw cancellation
            } catch (failure: Throwable) {
                failed = true
                null
            }
        }
        entries = listed ?: emptyList()
        // listFiles() 返回 null 也代表读不出来（不是目录 / 无权限），一并算失败。
        loadFailed = failed || listed == null
        loaded = true
    }

    val segments = remember(currentPath, currentRoot) {
        currentPath.removePrefix(currentRoot).split('/').filter { it.isNotBlank() }
    }
    // 「打开」的前置条件：列表已读完且没失败，且不能是存储根目录本身（那是目录树起点，不是工程目录）。
    val atStorageRoot = currentPath == currentRoot
    val canConfirm = loaded && !loadFailed && !atStorageRoot
    val rootNotAllowedText = stringResource(R.string.forge_folder_root_not_allowed)
    val unreadableText = stringResource(R.string.forge_folder_unreadable)
    val createFailedText = stringResource(R.string.forge_folder_create_failed)

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
            // 存储设备切换（只在真有第二个卷时才出现，单存储设备时界面与以前完全一致）。
            if (storageRoots.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    storageRoots.forEach { root ->
                        val label = if (root == PICKER_ROOT) {
                            stringResource(R.string.folder_picker_storage_root)
                        } else {
                            root.trimEnd('/').substringAfterLast('/')
                        }
                        PickerCrumb(
                            label = label,
                            isCurrent = currentRoot == root,
                            onClick = {
                                currentRoot = root
                                currentPath = root
                            },
                        )
                    }
                }
            }
            // 面包屑：手机存储 › 一级 › 二级，点任意一级直接跳回去。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rootLabel = if (currentRoot == PICKER_ROOT) {
                    stringResource(R.string.folder_picker_storage_root)
                } else {
                    currentRoot.trimEnd('/').substringAfterLast('/')
                }
                PickerCrumb(
                    label = rootLabel,
                    isCurrent = segments.isEmpty(),
                    onClick = { currentPath = currentRoot },
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
                            currentPath = currentRoot + segments.take(index + 1)
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
            if (atStorageRoot) {
                Text(
                    text = rootNotAllowedText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            confirmError?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            createError?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
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
                    // 确认前再校验一次真实可读性：列表读失败、或目标目录不存在/不可读时只在本地报错，
                    // 不把坏路径交给上层（否则会建出一个永远用不了的工作区）。
                    onClick = {
                        val target = File(currentPath)
                        when {
                            atStorageRoot -> confirmError = rootNotAllowedText
                            !target.isDirectory || !target.canRead() -> confirmError = unreadableText
                            else -> {
                                confirmError = null
                                onConfirm(currentPath)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    enabled = canConfirm,
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
                    createError = null
                    // 建完直接进去，用户下一步多半就是「打开」选它当工作区。
                    currentPath = created.absolutePath
                } else {
                    // 建不出来（无权限 / 名字非法 / 只剩只读）要看得见，别静默什么都不发生。
                    createError = createFailedText
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
    enabled: Boolean = true,
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
            // 禁用态只淡化背景与文字、不再接收点击（默认 enabled=true，既有调用外观不变）。
            .background(if (enabled) background else background.copy(alpha = 0.35f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Medium,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
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
