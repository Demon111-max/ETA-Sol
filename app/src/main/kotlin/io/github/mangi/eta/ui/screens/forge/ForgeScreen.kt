package io.github.mangi.eta.ui.screens.forge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.linuxPackageProfileReady
import io.github.mangi.eta.agent.forge.ForgeSharedConfig
import io.github.mangi.eta.agent.forge.resolveForgeSharedConfig
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import kotlinx.coroutines.Dispatchers
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
    onOpenLinuxEnvironment: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    newConversationSignal: Int = 0,
    openSessionRequest: Pair<String, Int>? = null,
    onSessionsChanged: (String?) -> Unit = {},
    onBusyChanged: (String?, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var gate by remember { mutableStateOf<ForgeGateState>(ForgeGateState.Checking) }
    LaunchedEffect(Unit) {
        gate = ForgeGateState.Checking
        gate = withContext(Dispatchers.IO) { checkForgeGate(context.applicationContext) }
    }

    when (val state = gate) {
        // 门禁通过后直接进对话；检查中与未就绪保持同一块静态版面。
        ForgeGateState.Ready -> ForgeChatPane(
            modifier = modifier.fillMaxSize(),
            topInset = topInset,
            newConversationSignal = newConversationSignal,
            openSessionRequest = openSessionRequest,
            onSessionsChanged = onSessionsChanged,
            onBusyChanged = onBusyChanged,
        )

        // 检查中保持空白：不引入任何进度指示。
        ForgeGateState.Checking -> Box(modifier = modifier.fillMaxSize())

        is ForgeGateState.Missing -> ForgeGateMissing(
            prerequisite = state.prerequisite,
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
    val rootfs = LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN)
    if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
        return ForgeGateState.Missing(ForgePrerequisite.LinuxEnvironment)
    }
    if (!linuxPackageProfileReady(rootfs, LinuxPackageProfiles.OPENCODE)) {
        return ForgeGateState.Missing(ForgePrerequisite.Opencode)
    }
    val configured = runBlockingGate { resolveForgeSharedConfig() is ForgeSharedConfig.Ready }
    if (!configured) return ForgeGateState.Missing(ForgePrerequisite.Config)
    return ForgeGateState.Ready
}

private fun <T> runBlockingGate(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
