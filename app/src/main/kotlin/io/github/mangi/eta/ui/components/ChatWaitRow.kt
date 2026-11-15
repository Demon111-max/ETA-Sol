package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sin

/**
 * 一轮正在进行时的等待行（仿 dsh + dsh-claude-style 的 chat-wait 行）：
 * 三点 loader（按活动在三种点阵间切换）+ 带扫光的活动词 + 从发送起算的秒数 +
 * 迟迟没有新内容时弹出的「暂未响应」徽章。Assist 与 Forge 共用。
 *
 * 计时口径：时钟从本轮发出消息起连续走（用户明确要的口径）；徽章按插件的
 * WAIT_OVERTIME_MS——距上次有新内容到达超过 10 秒就出现，有新内容就收起。
 */

/** 等待行左侧 loader 的三种点阵（插件 chat-wait 的三条 lane）。 */
internal enum class WaitLoaderMotion { Bounce, Line, Orbit }

/** 当前活动：决定等待行的词与 loader 点阵。 */
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
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
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
    Row(
        modifier = modifier.height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WaitDotsLoader(
            motion = when (activity) {
                ChatWaitActivity.Thinking -> WaitLoaderMotion.Line
                ChatWaitActivity.PreparingTool, ChatWaitActivity.RunningTool -> WaitLoaderMotion.Bounce
                ChatWaitActivity.Writing -> WaitLoaderMotion.Orbit
            },
            color = colors.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        ShimmerText(
            text = label,
            active = true,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = colors.onSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
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
            style = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum"),
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
 * 三点（轨道阵为四点）loader，数值按插件 tokens：点 4dp、跳跃行程 8dp、
 * 弹跳周期 500ms 单程、织线周期 1250ms、轨道周期 2400ms。
 * 时间由一台 50ms 步进的时钟驱动，点位逐帧按各 lane 的公式算——插件是 CSS
 * 关键帧，这里用等价的三角/正弦/分段缓动复现，观感一致即可。
 */
@Composable
private fun WaitDotsLoader(motion: WaitLoaderMotion, color: Color) {
    var tickMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(motion) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            tickMs = SystemClock.elapsedRealtime() - start
            delay(50)
        }
    }
    Canvas(modifier = Modifier.size(width = 20.dp, height = 16.dp)) {
        val t = tickMs / 1000f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dotR = 2.dp.toPx()
        val travel = 8.dp.toPx()
        when (motion) {
            WaitLoaderMotion.Bounce -> {
                // 三点沿地面弹跳：相位错开 0 / 0.3 / 0.2 个全周期（插件的负延迟口径），
                // 落地时压扁（scale 1.7 × 0.25），升空恢复圆形。
                val xs = listOf(2.dp.toPx(), 9.dp.toPx(), 16.dp.toPx())
                val shifts = listOf(0f, 0.3f, 0.2f)
                xs.forEachIndexed { index, x ->
                    val u = ((t / 1.0f) + shifts[index]) % 1f
                    val h = 1f - abs(1f - 2f * u)
                    val squash = 1f - h
                    val rx = dotR * (1f + 0.7f * squash * squash)
                    val ry = dotR * (1f - 0.75f * squash * squash)
                    // 底边始终贴着画布底（地面），升空时整颗点上移 travel。
                    drawOval(
                        color = color,
                        topLeft = Offset(x - rx, size.height - h * travel - 2 * ry),
                        size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                    )
                }
            }

            WaitLoaderMotion.Line -> {
                // 三点沿互成 120° 的线穿过中心来回织行，过中心时缩到 0.55。
                val period = 1.25f
                val radius = 4.5.dp.toPx()
                for (i in 0..2) {
                    val angle = Math.toRadians(-90.0 + i * 120.0)
                    val s = sin(2f * Math.PI.toFloat() * (t / period + i / 3f))
                    val dx = (kotlin.math.cos(angle) * radius * s).toFloat()
                    val dy = (sin(angle) * radius * s).toFloat()
                    val scale = 1f - 0.45f * (1f - abs(s))
                    drawCircle(
                        color = color,
                        radius = dotR * scale,
                        center = Offset(cx + dx, cy + dy),
                    )
                }
            }

            WaitLoaderMotion.Orbit -> {
                // 四点在 9.5 × 11.6 的矩形对角间两两交换：动 1/6 周期、停 1/3 周期，
                // 两组对角错开半个周期（插件 orbit 关键帧的节奏）。
                val period = 2.4f
                val hx = 4.75.dp.toPx()
                val hy = 5.8.dp.toPx()
                val corners = listOf(
                    Offset(cx - hx, cy - hy),
                    Offset(cx + hx, cy - hy),
                    Offset(cx + hx, cy + hy),
                    Offset(cx - hx, cy + hy),
                )
                fun swapPosition(from: Offset, to: Offset, phase: Float): Offset {
                    val u = ((phase % 1f) + 1f) % 1f
                    val k = when {
                        u < 1f / 6f -> easeOutCubic(u * 6f)
                        u < 0.5f -> 1f
                        u < 2f / 3f -> 1f - easeOutCubic((u - 0.5f) * 6f)
                        else -> 0f
                    }
                    return Offset(from.x + (to.x - from.x) * k, from.y + (to.y - from.y) * k)
                }
                val p = t / period
                drawCircle(color, dotR, swapPosition(corners[0], corners[2], p))
                drawCircle(color, dotR, swapPosition(corners[2], corners[0], p))
                drawCircle(color, dotR, swapPosition(corners[1], corners[3], p + 0.25f))
                drawCircle(color, dotR, swapPosition(corners[3], corners[1], p + 0.25f))
            }
        }
    }
}

private val EaseOutQuint = CubicBezierEasing(0.16f, 0.75f, 0.29f, 0.97f)

private fun easeOutCubic(u: Float): Float = EaseOutQuint.transform(u.coerceIn(0f, 1f))
