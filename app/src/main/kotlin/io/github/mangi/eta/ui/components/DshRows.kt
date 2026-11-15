package io.github.mangi.eta.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * DeepSeek Harness 对话区行规格的 Compose 复刻（源：dsh 的 DisclosureRow / ToolRow /
 * ReasoningRow 与 flow-motion）：行高 24dp，图标 14dp 居于 16dp 槽位、后随 6dp 间隔，
 * 标题与摘要同为 13sp 弱色单行；标题与摘要之间是 2dp 圆点、两侧各 8dp。
 * 展开时行首图标换成 chevron-up（原版行为）；折叠/展开动画为
 * 淡入淡出 70ms + 高度 110ms（延迟 50ms，cubic-bezier(0.4, 0, 0.2, 1)）。
 */

internal fun dshFoldEnter(): EnterTransition =
    fadeIn(animationSpec = tween(70)) +
        expandVertically(
            animationSpec = tween(
                durationMillis = 110,
                delayMillis = 50,
                easing = FastOutSlowInEasing,
            ),
        )

internal fun dshFoldExit(): ExitTransition =
    fadeOut(animationSpec = tween(70)) +
        shrinkVertically(
            animationSpec = tween(
                durationMillis = 110,
                delayMillis = 50,
                easing = FastOutSlowInEasing,
            ),
        )

/** 流式预览摘要的右缘渐隐（dsh 的 48dp mask）：提示文字仍在继续。 */
internal fun Modifier.dshStreamingFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (size.width > 0f) {
            val fadeStart = (1f - 48.dp.toPx() / size.width).coerceIn(0f, 1f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    fadeStart to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** dsh 行文字的弱色（label-tertiary 档），与应用既有行文字的透明度口径一致。 */
@Composable
internal fun dshTertiaryColor(): Color {
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    return colors.onSurface.copy(alpha = if (dark) 0.56f else 0.52f)
}

/** 行首槽位：16dp 宽，14dp 图标居中；展开时换成 chevron-up。 */
@Composable
internal fun DshLeadingIcon(
    expanded: Boolean,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.width(16.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = if (expanded) DshChevronUpIcon() else icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
    }
}

/** 标题与摘要之间的 2dp 圆点（label-caption 色，两侧各 8dp）。 */
@Composable
internal fun DshRowDot(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Box(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .size(2.dp)
            .clip(CircleShape)
            .background(colors.onSurface.copy(alpha = if (dark) 0.38f else 0.32f)),
    )
}

/**
 * dsh 工具行标题行：[图标] 标题 · 摘要，整行点击展开/收起；
 * 运行中标题与摘要一起走 ShimmerText 的扫光，失败时摘要换成错误色。
 */
@Composable
internal fun DshRowHeader(
    icon: ImageVector,
    title: String,
    summary: String?,
    running: Boolean,
    expanded: Boolean,
    expandable: Boolean,
    summaryError: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tertiary = dshTertiaryColor()
    val textStyle = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .then(if (expandable) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DshLeadingIcon(expanded = expanded, icon = icon, tint = tertiary)
        Spacer(modifier = Modifier.width(6.dp))
        ShimmerText(
            text = title,
            active = running,
            style = textStyle,
            color = tertiary,
            // 标题按自身宽度走（上限 200dp 防极端长名），剩余宽度全给摘要：
            // 原先标题与摘要权重相同五五分，摘要只有两三个字时标题也会被过早截断。
            modifier = Modifier.widthIn(max = 200.dp),
        )
        if (summary != null) {
            DshRowDot()
            ShimmerText(
                text = summary,
                active = running,
                style = textStyle,
                color = if (summaryError) StatusError else tertiary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
