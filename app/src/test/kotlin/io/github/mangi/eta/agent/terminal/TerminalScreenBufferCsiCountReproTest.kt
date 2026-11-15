package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 最小复现：agent\terminal\TerminalScreenBuffer.kt:420（以及 :401-413 的 tabForward/tabBackward、
 * :438-449 的 scrollDown）
 *
 *     private fun scrollUp(count: Int) { repeat(count) { ... } }
 *     ...
 *     604: val count = arg(0, 1)   // 来自 CSI 参数，最大可到 Int.MAX_VALUE
 *     630: 'S' -> scrollUp(count)
 *
 * 同族的 insertLines/deleteLines（:775、:787）都写了 `count.coerceAtMost(...)`，唯独
 * tabForward/tabBackward/scrollUp/scrollDown 直接 repeat(count)，一条 ESC[<巨大数字>S 就能让
 * feed() 在 PTY 读取线程上空转很久，终端停止刷新、子进程因管道写满被阻塞。
 *
 * 本测试用 2000 万次（远小于 Int.MAX_VALUE，避免测试进程长时间空转）验证「解析必须在上限内返回」。
 */
class TerminalScreenBufferCsiCountReproTest {

    @Test
    fun hugeScrollCountDoesNotSpinParser() {
        val buffer = TerminalScreenBuffer(cols = 80, rows = 24)
        val sequence = "\u001B[20000000S".toByteArray(StandardCharsets.US_ASCII)

        var failure: Throwable? = null
        val worker = Thread {
            try {
                buffer.feed(sequence)
            } catch (t: Throwable) {
                failure = t
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(2_000)

        assertFalse(
            "ESC[20000000S 解析超过 2s 仍未返回：CSI count 未做上限截断",
            worker.isAlive,
        )
        failure?.let { throw it }
    }
}
