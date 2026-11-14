package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ForgeChatMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏 Assist / Forge 分段控件。纯静态呈现：切换时选中段直接换位，不做滑动动画。
 */
@Composable
internal fun AssistForgeSwitch(
    mode: ForgeChatMode,
    onModeSelected: (ForgeChatMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val containerColor = if (dark) MiuixTheme.colorScheme.secondaryContainer else AssistForgeSwitchColors.Container
    Row(
        modifier = modifier
            .size(width = AssistForgeSwitchColors.Width, height = AssistForgeSwitchColors.Height)
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .padding(AssistForgeSwitchColors.ContainerInset)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeChatMode.entries.forEach { item ->
            val selected = item == mode
            val label = stringResource(
                when (item) {
                    ForgeChatMode.Assist -> R.string.forge_mode_assist
                    ForgeChatMode.Forge -> R.string.forge_mode_forge
                },
            )
            val source = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .width(AssistForgeSwitchColors.SegmentWidth)
                    .fillMaxHeight()
                    .then(
                        if (selected) {
                            Modifier
                                .shadow(
                                    elevation = AssistForgeSwitchColors.SelectedElevation,
                                    shape = CircleShape,
                                    clip = false,
                                    ambientColor = AssistForgeSwitchColors.Shadow,
                                    spotColor = AssistForgeSwitchColors.Shadow,
                                )
                                .background(AssistForgeSwitchColors.SelectedBackground, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .selectable(
                        selected = selected,
                        interactionSource = source,
                        indication = null,
                        role = Role.Tab,
                        onClick = { if (!selected) onModeSelected(item) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp),
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        AssistForgeSwitchColors.SelectedContent
                    } else {
                        AssistForgeSwitchColors.UnselectedContent
                    },
                )
            }
        }
    }
}

private object AssistForgeSwitchColors {
    val Width = 136.dp
    val Height = 36.dp
    val ContainerInset = 3.dp
    val SegmentWidth = 65.dp
    val SelectedElevation = 1.dp

    /** 浅色模式下的容器灰，取值固定为 #EDEEF1；深色模式改用主题容器灰 token。 */
    val Container = Color(0xFFEDEEF1)
    val SelectedBackground = Color(0xFFFFFFFF)
    val SelectedContent = Color(0xFF17181C)
    val UnselectedContent = Color(0xFF5C6068)
    val Shadow = Color(0x14000000)
}
