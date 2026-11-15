package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 可见入口先取得前台执行资格，再开始准备或安装；停止通知会取消同一任务。 */
internal fun CoroutineScope.launchForegroundExecution(
    context: Context,
    onUnavailable: () -> Unit,
    block: suspend () -> Unit,
): Job? {
    val id = "foreground-ui:${UUID.randomUUID()}"
    val job = launch(
        context = CoroutineExceptionHandler { _, failure ->
            AndroidAgentLogger.error("Foreground execution failed", failure)
        },
        start = CoroutineStart.LAZY,
    ) { block() }
    if (!AgentExecutionService.acquire(context, id, onStop = { job.cancel() })) {
        job.cancel()
        onUnavailable()
        return null
    }
    job.invokeOnCompletion { AgentExecutionService.release(id) }
    job.start()
    return job
}
