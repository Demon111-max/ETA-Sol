package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * 侧栏 Assist / Forge 分段控件。选中段是一块白色药丸，切换时用弹簧滑动到另一段，
 * 不是直接换位。宽度由调用方通过 [modifier] 给定（侧栏里占满整行）。
 */
@Composable
internal fun AssistForgeSwitch(
    mode: ForgeChatMode,
    onModeSelected: (ForgeChatMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val containerColor = if (dark) MiuixTheme.colorScheme.secondaryContainer else AssistForgeSwitchColors.Container
    val modes = ForgeChatMode.entries
    val selectedIndex = modes.indexOf(mode).coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .height(AssistForgeSwitchColors.Height)
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .padding(AssistForgeSwitchColors.ContainerInset)
            .selectableGroup(),
    ) {
        val segmentWidth = maxWidth / modes.size
        val pillOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
            label = "assistForgeSwitchPill",
        )
        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(
                    elevation = AssistForgeSwitchColors.SelectedElevation,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = AssistForgeSwitchColors.Shadow,
                    spotColor = AssistForgeSwitchColors.Shadow,
                )
                .background(AssistForgeSwitchColors.SelectedBackground, CircleShape),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { item ->
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
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            interactionSource = source,
                            indication = null,
                            role = Role.Tab,
                            // 已选段也要转发：高亮段是「当前打开的对话」的模式，
                            // 点它可能是想把「下一个新对话」的记住模式改过来（见 AgentAppRoot.selectChatMode）。
                            onClick = { onModeSelected(item) },
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
}

private object AssistForgeSwitchColors {
    val Height = 36.dp
    val ContainerInset = 3.dp
    val SelectedElevation = 1.dp

    /** 浅色模式下的容器灰，取值固定为 #EDEEF1；深色模式改用主题容器灰 token。 */
    val Container = Color(0xFFEDEEF1)
    val SelectedBackground = Color(0xFFFFFFFF)
    val SelectedContent = Color(0xFF17181C)
    val UnselectedContent = Color(0xFF5C6068)
    val Shadow = Color(0x14000000)
}
