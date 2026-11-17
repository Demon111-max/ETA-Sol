package io.github.mangi.eta.ui.screens.forge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.linuxPackageProfileReady
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.forge.ForgeServerManager
import io.github.mangi.eta.agent.forge.ForgeSharedConfig
import io.github.mangi.eta.agent.forge.resolveForgeSharedConfig
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction

/** Forge 未就绪时缺的那一项，按检查顺序排列。 */
internal enum class ForgePrerequisite {
    LinuxEnvironment,
    Opencode,
    Config,
}

internal sealed interface ForgeGateState {
    data object Checking : ForgeGateState

    data object Ready : ForgeGateState

    data class Missing(val prerequisite: ForgePrerequisite) : ForgeGateState
}

/**
 * Forge 页面的未就绪门禁：只做静态说明 + 一个跳转按钮，不自动安装、不做进度动画。
 * 检查顺序：Debian 环境 → opencode 是否已装 → 共用服务商配置是否可用。
 */
@Composable
internal fun ForgeScreen(
    state: ForgeChatState,
    onOpenLinuxEnvironment: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    onBusyChanged: (String?, Boolean) -> Unit = { _, _ -> },
    /** 打开侧边栏的工作区列表（空页工作区卡片的出口）。 */
    onOpenWorkspacePicker: () -> Unit = {},
) {
    val context = LocalContext.current
    var gate by remember { mutableStateOf<ForgeGateState>(ForgeGateState.Checking) }
    LaunchedEffect(Unit) {
        gate = ForgeGateState.Checking
        gate = withContext(Dispatchers.IO) { checkForgeGate(context.applicationContext) }
    }
    // 门禁不能只查一次：用户按提示去装好环境/opencode 再回来，还显示「缺失」、
    // 非得杀掉 App 重进。页面每次回到前台时重查一次（已通过的不打扰，不降级闪烁）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && gate !is ForgeGateState.Ready) {
                CoroutineScope(Dispatchers.IO).launch {
                    val checked = checkForgeGate(context.applicationContext)
                    withContext(Dispatchers.Main) {
                        if (gate !is ForgeGateState.Ready) gate = checked
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val gateState = gate) {
        // 门禁通过后直接进对话；检查中与未就绪保持同一块静态版面。
        ForgeGateState.Ready -> ForgeChatPane(
            state = state,
            modifier = modifier.fillMaxSize(),
            topInset = topInset,
            onBusyChanged = onBusyChanged,
            onOpenWorkspacePicker = onOpenWorkspacePicker,
        )

        // 检查中保持空白：不引入任何进度指示。
        ForgeGateState.Checking -> Box(modifier = modifier.fillMaxSize())

        is ForgeGateState.Missing -> ForgeGateMissing(
            prerequisite = gateState.prerequisite,
            onOpenLinuxEnvironment = onOpenLinuxEnvironment,
            onOpenModelProviders = onOpenModelProviders,
            modifier = modifier.padding(top = topInset),
        )
    }
}

@Composable
private fun ForgeGateMissing(
    prerequisite: ForgePrerequisite,
    onOpenLinuxEnvironment: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (titleRes, summaryRes) = when (prerequisite) {
        ForgePrerequisite.LinuxEnvironment ->
            R.string.forge_gate_linux_missing to R.string.forge_gate_linux_missing_summary

        ForgePrerequisite.Opencode ->
            R.string.forge_gate_opencode_missing to R.string.forge_gate_opencode_missing_summary

        ForgePrerequisite.Config ->
            R.string.forge_gate_config_missing to R.string.forge_gate_config_missing_summary
    }
    val actionRes = if (prerequisite == ForgePrerequisite.Config) {
        R.string.forge_gate_open_settings
    } else {
        R.string.forge_gate_open_environment
    }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ListEmptyState(
            title = stringResource(titleRes),
            summary = stringResource(summaryRes),
            icon = Icons.Rounded.Construction,
            action = {
                ForgeGateAction(
                    text = stringResource(actionRes),
                    onClick = {
                        if (prerequisite == ForgePrerequisite.Config) {
                            onOpenModelProviders()
                        } else {
                            onOpenLinuxEnvironment()
                        }
                    },
                )
            },
        )
    }
}

@Composable
internal fun ForgeGateAction(text: String, onClick: () -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiButton(
            text = text,
            role = CouiButtonRole.Primary,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

private fun checkForgeGate(context: android.content.Context): ForgeGateState {
    // 发行版必须跟用户当前选的走：以前写死 Debian，选了 Alpine 时环境明明装好也报缺失、
    // 文案还让他去装 Debian，而 server 与写配置用的都是 current()——三处口径必须一致。
    val distribution = LinuxEnvironmentSettingsRepository.current(context)
    val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
        return ForgeGateState.Missing(ForgePrerequisite.LinuxEnvironment)
    }
    // opencode 就绪判定：标记文件之外再看二进制本体——标记丢了但手动装过的不该误判未装，
    // 标记还在但二进制被删的也不能放行。
    // 3.4.43（N17）：路径不再写死 `rootfs/root/...`，改为按 server 启动命令同源的家目录解析
    // （`$HOME/.opencode/bin/opencode` 是 COMMAND 里加进 PATH 的那份）。普通身份手动装到
    // 自己家目录的 opencode 不会再被永久挡在门禁外。
    val environment = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
    val manager = ForgeServerManager.default(context)
    val binaryPresent = manager.opencodeBinaryCandidates(environment, rootfs.absolutePath).any { it.isFile }
    if (!linuxPackageProfileReady(rootfs, LinuxPackageProfiles.OPENCODE) && !binaryPresent) {
        return ForgeGateState.Missing(ForgePrerequisite.Opencode)
    }
    val configured = runBlockingGate { resolveForgeSharedConfig() is ForgeSharedConfig.Ready }
    if (!configured) return ForgeGateState.Missing(ForgePrerequisite.Config)
    return ForgeGateState.Ready
}

private fun <T> runBlockingGate(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
