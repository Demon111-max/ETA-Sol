package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * 一轮正在进行时的等待行：潮汐三点 loader + 带扫光的活动词 + 从发送起算的秒数 +
 * 迟迟没有新内容时弹出的「暂未响应」徽章。Assist 与 Forge 共用。
 *
 * 计时口径：时钟从本轮发出消息起连续走（用户明确要的口径）；徽章按
 * WAIT_OVERTIME_MS——距上次有新内容到达超过 10 秒就出现，有新内容就收起。
 *
 * loader 的形态与参数是先用逐帧渲染原型验过再落的代码（潮汐三点）：
 * 三个点像潮水一样依次涨落——直径、纵向位置、透明度同相位呼吸；
 * 以前的弹跳点阵大部分时间被压扁成一条横线，织线/轨道两套又花哨，
 * 统一收成这一套，并改由帧时钟驱动（以前 50ms 轮询只有 20 帧，天花板就卡）。
 */

/** 当前活动：只决定等待行的词，loader 形态只有一套。 */
internal enum class ChatWaitActivity { Thinking, PreparingTool, RunningTool, Writing }

private const val WAIT_OVERTIME_MS = 10_000L

@Composable
internal fun ChatWaitRow(
    activity: ChatWaitActivity,
    startedAtMs: Long,
    lastActivityAtMs: Long,
    modifier: Modifier = Modifier,
    overtimeEnabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // 秒数与徽章只需要 250ms 粒度；loader 的顺滑由它自己的帧时钟负责，不共用这个循环。
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    // 起点缺失（对齐路径漏网）时按当下起算，绝不拿开机时长冒充本轮耗时。
    val effectiveStartMs = if (startedAtMs == 0L) now else startedAtMs
    val elapsedSeconds = ((now - effectiveStartMs).coerceAtLeast(0L)) / 1000L
    val stalled = overtimeEnabled && startedAtMs != 0L &&
        now - lastActivityAtMs >= WAIT_OVERTIME_MS
    val label = stringResource(
        when (activity) {
            ChatWaitActivity.Thinking -> R.string.chat_wait_thinking
            ChatWaitActivity.PreparingTool -> R.string.chat_wait_preparing_tool
            ChatWaitActivity.RunningTool -> R.string.chat_wait_running_tool
            ChatWaitActivity.Writing -> R.string.chat_wait_writing
        },
    )
    val labelSlidePx = with(LocalDensity.current) { 4.dp.roundToPx() }
    Row(
        modifier = modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TideDotsLoader(color = colors.primary)
        Spacer(modifier = Modifier.width(6.dp))
        // 活动词是这一行的主信息：15sp 半粗 + 扫光；切换时上滑 4dp 淡入淡出，别硬跳。
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(160)) { labelSlidePx } +
                    fadeIn(animationSpec = tween(160))) togetherWith
                    (slideOutVertically(animationSpec = tween(160)) { -labelSlidePx } +
                        fadeOut(animationSpec = tween(160)))
            },
            label = "chatWaitLabel",
        ) { text ->
            ShimmerText(
                text = text,
                active = true,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // 秒数是次要信息：12sp 弱色，别和活动词抢视觉重量。
        Text(
            text = if (elapsedSeconds < 60) {
                stringResource(R.string.chat_wait_elapsed_seconds, elapsedSeconds)
            } else {
                stringResource(
                    R.string.chat_wait_elapsed_minutes,
                    elapsedSeconds / 60,
                    elapsedSeconds % 60,
                )
            },
            style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"),
            color = dshTertiaryColor(),
        )
        AnimatedVisibility(
            visible = stalled,
            enter = scaleIn(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                initialScale = 0.4f,
            ) + fadeIn(),
            exit = scaleOut(targetScale = 0.4f) + fadeOut(),
        ) {
            val dark = colors.surface.luminance() < 0.5f
            val warn = if (dark) Color(0xFFE8A33D) else Color(0xFFB25E09)
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(warn.copy(alpha = 0.16f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_wait_no_response),
                    style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
                    color = warn,
                )
            }
        }
    }
}

/**
 * 潮汐三点：盒 26×22dp，三点中心 x = 5/13/21dp、基线 cy = 13dp；
 * phase_i = 2π(t / 1100ms − 0.16i)，直径 = 5.2 + 1.3·sin(phase_i) dp（3.9–6.5），
 * y = cy − 1.8·sin(phase_i) dp，alpha = 0.55 + 0.45·(0.5 + 0.5·sin(phase_i))。
 * 由 rememberInfiniteTransition 的帧时钟驱动，刷新率给到多少就有多顺。
 */
@Composable
private fun TideDotsLoader(color: Color) {
    val transition = rememberInfiniteTransition(label = "tideDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "tidePhase",
    )
    Canvas(modifier = Modifier.size(width = 26.dp, height = 22.dp)) {
        val baselineY = 13.dp.toPx()
        val centersX = listOf(5.dp.toPx(), 13.dp.toPx(), 21.dp.toPx())
        centersX.forEachIndexed { index, centerX ->
            val s = sin(phase - (2.0 * PI * 0.16 * index).toFloat())
            val radius = ((5.2f + 1.3f * s) / 2f).dp.toPx()
            val centerY = baselineY - (1.8f * s).dp.toPx()
            val alpha = 0.55f + 0.45f * (0.5f + 0.5f * s)
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(centerX, centerY),
            )
        }
    }
}
