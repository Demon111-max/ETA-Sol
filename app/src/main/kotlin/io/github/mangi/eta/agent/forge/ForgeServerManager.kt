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
import io.github.mangi.eta.config.Prefs
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

        // 不用 opencode 默认的 4096：Sol 等其他应用的 opencode server 也监听 4096，
        // PRoot 共用宿主网络命名空间，撞上会连错 server。Forge 专用 4097。
        const val PORT = 4097
        const val BASE_URL = "http://$HOST:$PORT"
        const val COMMAND = "export PATH=\"\$HOME/.opencode/bin:\$PATH\" && opencode serve --hostname $HOST --port $PORT"
        const val HEALTH_PATH = "/session"
        const val HEALTH_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val PROBE_TIMEOUT_MS = 2_000L

        /** 在跑但不健康时的短宽限：给正在启动的 server 留出就绪时间，过了还不健康按卡死处理、停掉重拉。 */
        private const val UNHEALTHY_GRACE_MS = 8_000L

        /** 停掉自家旧 server 后等端口释放的最长等待，免得新 server 抢不到 4097。 */
        private const val PORT_DRAIN_TIMEOUT_MS = 3_000L

        /** 在跑的 server 启动时焊入的挂载集合指纹；与当前挂载不一致说明挂载改过、需要重启才生效。 */
        private const val PREFS_KEY_SERVER_MOUNTS_FP = "forge_server_mounts_fp"

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
                task.task.backend == backend && task.task.command.trim() == COMMAND &&
                task.task.cwd == desiredCwd()
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext ForgeServerStatus()
        if (!task.running) return@withContext ForgeServerStatus(taskId = task.task.id, code = "FORGE_SERVER_EXITED")
        ForgeServerStatus(running = true, taskId = task.task.id, healthy = isHealthy())
    }

    /** 已在跑就直接用；否则启动守护任务并最多等 30 秒就绪。 */
    suspend fun ensureRunning(environment: TerminalEnvironment): ForgeServerStartResult = startupMutex.withLock {
        // 工作区先就位：默认工作区不存在时先建，server 的 cwd 与挂载都以它为准。
        ForgeWorkspaces.ensureDefault()
        val cwd = desiredCwd()
        var current = status(environment)
        var stoppedAny = false
        if (current.running && current.healthy) {
            if (serverMountsFingerprint() == ForgeWorkspaces.mountsFingerprint()) {
                return ForgeServerStartResult.Running
            }
            // 挂载集合在 server 启动后改过（新加共享文件夹/工作区）：在跑的 server 焊的是旧挂载，
            // 看不到新目录。停掉重拉，让挂载变更生效，而不是让用户以为没保存。
            stoppedAny = stopAllForgeTasksLocked()
            current = ForgeServerStatus()
        }
        val statusCode = current.code
        if (statusCode != null &&
            (statusCode == "INVALID_ENVIRONMENT" || statusCode == "LINUX_ENVIRONMENT_NOT_READY")
        ) {
            return ForgeServerStartResult.Failed(statusCode)
        }
        // 端口只有一个：停掉其他环境（发行版/身份/后端不同）或旧工作区（cwd 不同）名下的 Forge server，
        // 否则健康检查会把旧 server 误认成本环境的，配置/工作区改了也静默不生效。
        stoppedAny = stopMismatchedTasksLocked(environment, cwd) || stoppedAny
        if (current.running && !current.healthy) {
            // 进程还在但不应答：先给短宽限（可能正在启动），仍不健康按卡死处理，停掉重拉，不再干等 30 秒。
            if (waitUntilHealthy(environment, UNHEALTHY_GRACE_MS) == null) {
                return ForgeServerStartResult.Running
            }
            stoppedAny = stopAllForgeTasksLocked() || stoppedAny
            current = ForgeServerStatus()
        }
        if (!current.running) {
            if (isHealthy()) {
                // 刚停掉自家旧 server 时端口可能还没释放：等它排空再启动；排空后仍有人应答才是外部 server。
                val drained = !stoppedAny || waitForPortDrain()
                if (!drained || isHealthy()) {
                    return ForgeServerStartResult.Failed("FORGE_PORT_IN_USE")
                }
            }
            when (val started = start(environment)) {
                is ForgeServerStartResult.Failed -> return started
                ForgeServerStartResult.Running -> Unit
            }
        }
        val failure = waitUntilHealthy(environment)
        if (failure == null) ForgeServerStartResult.Running else ForgeServerStartResult.Failed(failure)
    }

    /** 当前工作区的 Linux 路径（server 的 cwd）；没有可用工作区时回落环境私有目录。 */
    private fun desiredCwd(): String =
        ForgeWorkspaces.currentSelection()?.linuxPath ?: ForgeWorkspaces.FALLBACK_CWD

    /** 停掉环境/身份/后端/cwd 与当前目标不符的在跑 Forge server；返回是否停过任何一个。本函数须在 startupMutex 内调用。 */
    private suspend fun stopMismatchedTasksLocked(
        environment: TerminalEnvironment,
        cwd: String,
    ): Boolean {
        val distribution = environment.linuxDistribution ?: return false
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsPath)) return false
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        val backend = LinuxEnvironmentPaths.backendOf(rootfsPath)
        var stopped = false
        daemonSupervisor.list()
            .filter { task ->
                task.running && task.task.command.trim() == COMMAND &&
                    (task.task.environment != environment || task.task.identity != identity ||
                        task.task.backend != backend || task.task.cwd != cwd)
            }
            .forEach { stopped = daemonSupervisor.stop(it.task.id) || stopped }
        return stopped
    }

    /** 停掉全部 Forge server 任务（不论环境与 cwd）。本函数须在 startupMutex 内调用。 */
    private suspend fun stopAllForgeTasksLocked(): Boolean {
        var stopped = false
        daemonSupervisor.list()
            .filter { it.task.command.trim() == COMMAND }
            .forEach { stopped = daemonSupervisor.stop(it.task.id) || stopped }
        return stopped
    }

    /** 等端口不再应答（旧 server 真正退出），最多等 PORT_DRAIN_TIMEOUT_MS。 */
    private suspend fun waitForPortDrain(): Boolean {
        val deadline = System.currentTimeMillis() + PORT_DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!isHealthy()) return true
            delay(200)
        }
        return !isHealthy()
    }

    private fun serverMountsFingerprint(): String? = runCatching {
        Prefs.localAgentPreferences()?.getString(PREFS_KEY_SERVER_MOUNTS_FP, null)
    }.getOrNull()

    private fun saveServerMountsFingerprint() {
        runCatching {
            Prefs.localAgentPreferences()?.edit()
                ?.putString(PREFS_KEY_SERVER_MOUNTS_FP, ForgeWorkspaces.mountsFingerprint())
                ?.apply()
        }
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
                cwd = desiredCwd(),
                identity = identity,
                environment = environment,
            )) {
                is DaemonStartResult.Started -> {
                    // 记下这次启动焊入的挂载集合，之后挂载改动靠它判定要不要重启（ensureRunning）。
                    saveServerMountsFingerprint()
                    ForgeServerStartResult.Running
                }
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
        val wasRunning = status(environment).running
        val stopped = stop(environment)
        if (wasRunning && !stopped && status(environment).running) {
            // 旧 server 停不掉还活着：ensureRunning 会把它误认成重启成功，
            // 配置换了也还是旧 server 在答——必须把失败报出去，不许假成功。
            return ForgeServerStartResult.Failed("FORGE_SERVER_STOP_FAILED")
        }
        return ensureRunning(environment)
    }

    /** 保存配置后调用：server 正在跑才重启，没跑就不主动拉起。 */
    suspend fun restartIfRunning(environment: TerminalEnvironment): ForgeServerStartResult? {
        val current = status(environment)
        if (!current.running && !current.healthy) return null
        return restart(environment)
    }

    /** 等 server 就绪：返回 null 表示就绪，否则返回失败码；守护任务中途退出立即失败，不干等满时限。 */
    private suspend fun waitUntilHealthy(
        environment: TerminalEnvironment,
        timeoutMs: Long = HEALTH_TIMEOUT_MS,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return null
            if (status(environment).code == "FORGE_SERVER_EXITED") return "FORGE_SERVER_EXITED"
            delay(POLL_INTERVAL_MS)
        }
        return if (isHealthy()) null else "FORGE_SERVER_TIMEOUT"
    }

}
