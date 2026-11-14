package io.github.mangi.eta.agent.forge

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class ForgeServerStatus(
    val running: Boolean = false,
    val taskId: String? = null,
    val healthy: Boolean = false,
    val code: String? = null,
)

internal sealed interface ForgeServerStartResult {
    data object Running : ForgeServerStartResult

    data class Failed(val code: String) : ForgeServerStartResult
}

/**
 * Forge 的 opencode server 托管：在 Linux 环境里以守护任务启动 `opencode serve`，
 * 用 `GET /session` 判断就绪，停止/重启复用同一套守护任务记录。
 */
internal class ForgeServerManager(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
    private val httpClient: OkHttpClient = AgentHttpClient.client,
) {
    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 4096
        const val BASE_URL = "http://$HOST:$PORT"
        const val COMMAND = "export PATH=\"\$HOME/.opencode/bin:\$PATH\" && opencode serve --hostname $HOST --port $PORT"
        const val HEALTH_PATH = "/session"
        const val HEALTH_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val PROBE_TIMEOUT_MS = 2_000L

        /** UI 与设置保存共用一份守护任务配置；记录文件同 DetachedTaskSupervisor 的默认位置。 */
        private val startupMutex = Mutex()
        private const val LEASE_ID = "forge-server"

        fun default(context: Context): ForgeServerManager {
            val appContext = context.applicationContext
            return ForgeServerManager(
                context = appContext,
                daemonSupervisor = DetachedTaskSupervisor(
                    logger = AndroidAgentLogger,
                    recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
                    linuxRootfsPathProvider = { environment ->
                        environment.linuxDistribution?.let { distribution ->
                            LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
                        }
                    },
                    linuxSharedMountsProvider = { SharedFolderMounts.current() },
                ),
            )
        }
    }

    /** 当前选择的发行版对应的终端环境；没有 Linux 发行版时退回默认。 */
    suspend fun environment(): TerminalEnvironment = withContext(Dispatchers.IO) {
        LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
    }

    /** `GET /session` 返回 200 即视为就绪。 */
    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE_URL + HEALTH_PATH).get().build()
        val probe = httpClient.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        try {
            probe.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    suspend fun status(environment: TerminalEnvironment): ForgeServerStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution
            ?: return@withContext ForgeServerStatus(code = "INVALID_ENVIRONMENT")
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsPath)) {
            return@withContext ForgeServerStatus(code = "LINUX_ENVIRONMENT_NOT_READY")
        }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        val backend = LinuxEnvironmentPaths.backendOf(rootfsPath)
        val matches = daemonSupervisor.list().filter { task ->
            task.task.environment == environment && task.task.identity == identity &&
                task.task.backend == backend && task.task.command.trim() == COMMAND
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext ForgeServerStatus()
        if (!task.running) return@withContext ForgeServerStatus(taskId = task.task.id, code = "FORGE_SERVER_EXITED")
        ForgeServerStatus(running = true, taskId = task.task.id, healthy = isHealthy())
    }

    /** 已在跑就直接用；否则启动守护任务并最多等 30 秒就绪。 */
    suspend fun ensureRunning(environment: TerminalEnvironment): ForgeServerStartResult = startupMutex.withLock {
        if (isHealthy()) return ForgeServerStartResult.Running
        val existing = daemonSupervisor.list().filter { it.running && it.task.command.trim() == COMMAND }
        if (existing.isEmpty()) {
            when (val started = start(environment)) {
                is ForgeServerStartResult.Failed -> return started
                ForgeServerStartResult.Running -> Unit
            }
        }
        if (waitUntilHealthy()) ForgeServerStartResult.Running else ForgeServerStartResult.Failed("FORGE_SERVER_TIMEOUT")
    }

    suspend fun start(environment: TerminalEnvironment): ForgeServerStartResult = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution
            ?: return@withContext ForgeServerStartResult.Failed("INVALID_ENVIRONMENT")
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsPath)) {
            return@withContext ForgeServerStartResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
        }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        val job = currentCoroutineContext().job
        val needsLease = identity == "user"
        if (needsLease && !AgentExecutionService.acquire(context, LEASE_ID) { job.cancel() }) {
            return@withContext ForgeServerStartResult.Failed("BACKGROUND_START_NOT_ALLOWED")
        }
        try {
            currentCoroutineContext().ensureActive()
            when (val started = daemonSupervisor.start(
                command = COMMAND,
                cwd = "/workspace",
                identity = identity,
                environment = environment,
            )) {
                is DaemonStartResult.Started -> ForgeServerStartResult.Running
                is DaemonStartResult.Failed -> ForgeServerStartResult.Failed(started.code)
            }
        } finally {
            if (needsLease) AgentExecutionService.release(LEASE_ID)
        }
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = startupMutex.withLock {
        withContext(Dispatchers.IO) {
            val tasks = daemonSupervisor.list().filter { it.task.command.trim() == COMMAND }
            var stopped = false
            tasks.forEach { stopped = daemonSupervisor.stop(it.task.id) || stopped }
            stopped
        }
    }

    suspend fun restart(environment: TerminalEnvironment): ForgeServerStartResult {
        stop(environment)
        return ensureRunning(environment)
    }

    /** 保存配置后调用：server 正在跑才重启，没跑就不主动拉起。 */
    suspend fun restartIfRunning(environment: TerminalEnvironment): ForgeServerStartResult? {
        val current = status(environment)
        if (!current.running && !current.healthy) return null
        return restart(environment)
    }

    private suspend fun waitUntilHealthy(): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return true
            delay(POLL_INTERVAL_MS)
        }
        return isHealthy()
    }

}
