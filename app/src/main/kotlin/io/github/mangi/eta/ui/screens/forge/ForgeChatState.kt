package io.github.mangi.eta.ui.screens.forge

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import android.net.Uri
import io.github.mangi.eta.agent.forge.ForgeApiClient
import io.github.mangi.eta.agent.forge.ForgeChatMessage
import io.github.mangi.eta.agent.forge.ForgeConfigWriteResult
import io.github.mangi.eta.agent.forge.ForgeConfigWriter
import io.github.mangi.eta.agent.forge.ForgeEvent
import io.github.mangi.eta.agent.forge.ForgeOutgoingAttachment
import io.github.mangi.eta.agent.forge.ForgePart
import io.github.mangi.eta.agent.forge.ForgeQuestionRequest
import io.github.mangi.eta.agent.forge.ForgeRole
import io.github.mangi.eta.agent.forge.ForgeServerManager
import io.github.mangi.eta.agent.forge.ForgeServerStartResult
import io.github.mangi.eta.agent.forge.ForgeWorkspace
import io.github.mangi.eta.agent.forge.ForgeWorkspaces
import io.github.mangi.eta.agent.forge.hasHttpCode
import io.github.mangi.eta.agent.forge.ForgeSharedConfig
import io.github.mangi.eta.agent.forge.resolveForgeSharedConfig
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Forge 对话的准备阶段。 */
internal sealed interface ForgeStartup {
    data object Connecting : ForgeStartup

    data object Ready : ForgeStartup

    data class Failed(val message: String) : ForgeStartup
}

/** 启动遮罩上的阶段：和 start() 的真实步骤一一对应，文字走资源。 */
internal enum class ForgeStartupStage {
    Preparing,
    StartingServer,
    Connecting,
}

/** 一轮运行失败的落点：错误行画在 anchorMessageId 所在的那一轮末尾，新开一轮不顶掉它。 */
internal data class ForgeTurnError(
    val message: String,
    val statusCode: Int?,
    val anchorMessageId: String?,
)

/** 遮罩最短展示时长（毫秒）：动画本身约 0.7 秒画完，这个时长保证它每次都完整播完再进对话。 */
private const val FORGE_SPLASH_MIN_DURATION_MS = 1_250L

/** 一次共用配置对齐的结果：启动与发送共用同一套处理，只在失败呈现上不同。 */
private sealed interface ConfigApply {
    data object Ready : ConfigApply

    data object NotConfigured : ConfigApply

    data class Failed(val output: String) : ConfigApply
}

/** 上次成功写入 Linux 环境的共用配置指纹；没变就不重写、不重启 server。 */
private var appliedConfigFingerprint: String? = null

/**
 * 配置对齐的互斥锁：预热与进 Forge 的 start/send 是两条独立路径，
 * 不加锁并发时会同时写同一组配置文件、把 server 连重启两次（交错写坏偶发启动失败）。
 */
private val configApplyMutex = Mutex()

/**
 * 配置指纹要覆盖一切会影响写入结果与 server 行为的输入：
 * 服务商身份/地址/模型/Key/协议之外，还有显示名（写进配置的 name）、自定义请求头、
 * 上下文上限，以及**当前 Linux 发行版**——配置是写进该发行版 rootfs 的，
 * 换发行版后新 rootfs 里根本没有这份配置，指纹不含发行版就会误判「已对齐」跳过写入，
 * 与 3.4.25「换 Key 不生效」是同一族漏项。
 */
private fun forgeConfigFingerprint(
    settings: ForgeSettings,
    credentials: ForgeCredentials,
    distributionWireName: String,
): String = buildString {
    append(settings.providerId).append('\n')
    append(settings.providerName).append('\n')
    append(settings.baseUrl).append('\n')
    append(settings.modelId).append('\n')
    append(credentials.apiKey).append('\n')
    append(settings.endpointMode).append('\n')
    append(settings.contextWindow?.toString().orEmpty()).append('\n')
    append(distributionWireName).append('\n')
    settings.customHeaders.forEach { header ->
        append(header.name.trim().lowercase()).append('=').append(header.value).append('\n')
    }
}

/**
 * 开屏预热：App 一进来就在后台把当前共用配置写好、把 opencode server 拉起来，
 * 用户第一次切到 Forge 时直接能用，不用再对着「正在启动」等。
 * 没配好 / 环境没就绪就静默跳过——真进 Forge 时它自己会走门禁与完整启动流程；
 * 指纹与 ForgeChatState 共用同一份，预热做过的对齐不会在进 Forge 时重做一遍。
 */
internal suspend fun prewarmForgeServer(context: Context) {
    val shared = resolveForgeSharedConfig()
    if (shared !is ForgeSharedConfig.Ready) return
    val server = ForgeServerManager.default(context)
    configApplyMutex.withLock {
        val fingerprint = forgeConfigFingerprint(
            shared.settings,
            shared.credentials,
            LinuxEnvironmentSettingsRepository.current(context).wireName,
        )
        if (fingerprint != appliedConfigFingerprint) {
            if (ForgeConfigWriter.write(context, shared.settings, shared.credentials)
                is ForgeConfigWriteResult.Written
            ) {
                // 重启成功（或本来没在跑）才认这份指纹：重启失败还置位，
                // 进 Forge 时会误判已对齐、继续拿旧配置发，失败还无迹可查。
                when (val restarted = server.restartIfRunning(server.environment())) {
                    is ForgeServerStartResult.Failed ->
                        AndroidAgentLogger.warn("Forge prewarm restart failed: ${restarted.code}")

                    else -> appliedConfigFingerprint = fingerprint
                }
            }
        }
    }
    server.ensureRunning(server.environment())
}

/** 一条待发附件（输入栏里挂着的那种）：添加时尽量已落到本地暂存文件，source 是可直接读的绝对路径或 content://。 */
internal data class ForgePendingAttachment(
    val id: String,
    val displayName: String,
    val mime: String,
    val source: String,
    val isImage: Boolean,
    val isFolder: Boolean = false,
    /** 图片缩略图（小图才有）：只喂输入栏的待发图片条预览，不参与发送。 */
    val previewDataUrl: String? = null,
)

/** 一条排队中的发送（本轮还在跑时用户又发了一条）：轮次自然结束时按序续发。 */
private data class ForgeQueuedSend(
    val text: String,
    val attachments: List<ForgePendingAttachment>,
)

/**
 * 把内部错误（server 失败码、IOException 原文、HTTP 码）翻成用户能看懂的话。
 * 原始信息只该进日志；界面上出现 FORGE_SERVER_EXITED / timeout / 整段命令输出，
 * 用户既看不懂也不知道去哪修。未知错误截断成一行附上，不整段甩。
 */
internal fun forgeErrorText(context: Context, raw: String): String {
    val text = raw.trim()
    return when {
        text == "FORGE_SERVER_TIMEOUT" -> context.getString(R.string.forge_error_server_timeout)
        text == "FORGE_SERVER_EXITED" -> context.getString(R.string.forge_error_server_exited)
        text == "FORGE_PORT_IN_USE" -> context.getString(R.string.forge_error_port_in_use)
        text == "FORGE_SERVER_STOP_FAILED" -> context.getString(R.string.forge_error_stop_failed)
        text == "BACKGROUND_START_NOT_ALLOWED" -> context.getString(R.string.forge_error_background_start)
        text == "LINUX_ENVIRONMENT_NOT_READY" -> context.getString(R.string.forge_error_env_not_ready)
        text == "INVALID_ENVIRONMENT" -> context.getString(R.string.forge_error_env_invalid)
        text.contains("timeout", ignoreCase = true) -> context.getString(R.string.forge_error_timeout)
        text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("Failed to connect", ignoreCase = true) ||
            text.contains("Connection refused", ignoreCase = true) ->
            context.getString(R.string.forge_error_connect)

        text.startsWith("HTTP ") -> {
            val code = text.removePrefix("HTTP ").takeWhile { it.isDigit() }
            if (code.isNotEmpty()) {
                context.getString(R.string.forge_error_http, code)
            } else {
                text.take(120)
            }
        }

        else -> text.take(120)
    }
}

/**
 * Forge 单会话对话的状态：确保 opencode server 在跑、拿一个 session、订阅 /event。
 *
 * opencode 的输出原样进入界面，不再经过 Eta 的 AgentLoop。
 */
internal class ForgeChatState(
    private val context: Context,
    private val scope: CoroutineScope,
    /** 会话集合或当前会话变化时回调（新建、切换、一轮结束标题生成后），上层据此刷新侧边栏列表。 */
    private val onSessionsChanged: (activeSessionId: String?) -> Unit = {},
) {
    var startup by mutableStateOf<ForgeStartup>(ForgeStartup.Connecting)

    /** 启动遮罩显示的当前阶段，随 start() 的真实进度推进（仿 DSHA 启动屏的阶段文字）。 */
    var startupStage by mutableStateOf(ForgeStartupStage.Preparing)

    /** 本轮开始时刻（elapsedRealtime）：等待行从发送那一刻起算秒数。 */
    var turnStartedAtMs by mutableLongStateOf(0L)
        private set

    /** 最近一次有新内容到达的时刻：等待行据此判定「暂未响应」。 */
    var lastActivityAtMs by mutableLongStateOf(0L)
        private set

    /** 最近一轮的运行失败（错误行画进对话区，不再只飘一句通知）。 */
    var turnError by mutableStateOf<ForgeTurnError?>(null)
        private set
    var messages by mutableStateOf<List<ForgeChatMessage>>(emptyList())
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    /**
     * 模型调用 question 工具后挂起等回答的请求；不为空时界面出提问卡。
     * 没人回答整轮会一直挂着，所以进入/切会话/重连时都要向服务端重新对齐一次。
     */
    var pendingQuestion by mutableStateOf<ForgeQuestionRequest?>(null)
        private set

    /** Forge 的思考档位：发送时按档位名选 opencode 配置里预置的 variant。 */
    var reasoningEffort by mutableStateOf(ReasoningEffort.DEFAULT)

    /** 当前打开的 opencode 会话 id；侧边栏高亮与列表刷新要读它，所以做成可观察状态。 */
    var sessionId: String? by mutableStateOf(null)
        private set

    /** 当前工作区（DSH 式命名文件夹）：server 以它为 cwd，附件与产出都落它里面。为 null 表示无可用工作区、回落私有目录。 */
    var workspace by mutableStateOf<ForgeWorkspace?>(null)
        private set

    /** 全部工作区（切换菜单用），随 start/切换刷新。 */
    var workspaces by mutableStateOf<List<ForgeWorkspace>>(emptyList())
        private set

    /** 当前模型的上下文上限（输入栏用量显示用），来自共用配置对齐。 */
    var contextWindowLimit by mutableStateOf<Int?>(null)
        private set

    /** 输入栏里待发的附件（图片/文件/文件夹/路径四种入口都落到这里），发送时复制进当前工作区再以 file part 发出。 */
    var pendingAttachments by mutableStateOf<List<ForgePendingAttachment>>(emptyList())
        private set

    /** 本轮还在跑时排队的发送（只存文本供输入栏展示条数与内容预览），轮次自然结束时按序续发。 */
    var queuedTexts by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 输入框草稿。以前草稿只活在输入框自己的 rememberTextFieldState 里，
     * 面板一旦重建（进设置/终端子页再回来）就跟着组合一起没了；状态提升到
     * 这里之后草稿与列表/队列同寿命。
     */
    var draft by mutableStateOf("")

    /** 最近一条助手消息报告的上下文占用（tokens），没有数据时为 null。 */
    val contextUsageTokens: Long?
        get() = messages.lastOrNull { it.role == ForgeRole.Assistant }?.contextTokens

    /** 还没启动完就被要求打开的会话（从侧边栏点进来），start() 拿到服务后优先打开它。 */
    private var pendingOpenSessionId: String? = null

    /** 还没启动完就被要求开新对话（空白页），start() 就绪后不落到最近会话、直接空白。 */
    private var pendingBlank = false

    /** 还没启动完就被要求在指定工作区开新对话（侧边栏工作区的「+」），start() 在拉起 server 前先把选择切过去。 */
    private var pendingWorkspaceName: String? = null

    /**
     * 面板四条一次性信号的「已消费序号」。这些序号以前用面板里的 remember 记着，
     * 面板重建（子页往返会整块销毁重建）后初值归零，陈旧的请求就被当成新请求再执行一遍
     * （工作区被改回、会话被拽回、空白页永久粘住）。状态活得比面板久，挪到这里才算真一次性。
     */
    private var handledNewConversationSignal by mutableIntStateOf(0)
    private var handledOpenSessionSeq by mutableIntStateOf(0)
    private var handledWorkspaceSeq by mutableIntStateOf(0)
    private var handledStructureSignal by mutableIntStateOf(0)

    /** 提问挂起期间收到过 idle（只是让出，不是结束）：回答/跳过成功后要重新挂一次确认。 */
    private var idleDuringQuestion = false

    /** 一次性信号消费：序号与上次处理的不同才执行，且立刻记下，重建后不再重放。 */
    fun consumeNewConversationSignal(seq: Int): Boolean {
        if (seq == handledNewConversationSignal) return false
        handledNewConversationSignal = seq
        return true
    }

    fun consumeOpenSessionSeq(seq: Int): Boolean {
        if (seq == handledOpenSessionSeq) return false
        handledOpenSessionSeq = seq
        return true
    }

    fun consumeWorkspaceSeq(seq: Int): Boolean {
        if (seq == handledWorkspaceSeq) return false
        handledWorkspaceSeq = seq
        return true
    }

    fun consumeStructureSignal(seq: Int): Boolean {
        if (seq == handledStructureSignal) return false
        handledStructureSignal = seq
        return true
    }
    private var providerId: String = ForgeConfigWriter.PROVIDER_ID
    private var modelId: String = ""
    private var eventJob: Job? = null
    private var startJob: Job? = null
    private var sendJob: Job? = null
    private var openJob: Job? = null
    private var idleConfirmJob: Job? = null
    private var noticeJob: Job? = null
    private var queuedSends: List<ForgeQueuedSend> = emptyList()

    /** 发送世代号：每次真正发起发送 +1，新建/切换/停止也 +1；在途发送回写前校验，防迟到回调劫持当前界面。 */
    private var sendGeneration = 0

    /** 是否有发送协程在途：重连对齐 busy 时不能把发送中的 busy 冲掉（对齐只信服务端会误判刚提交未开跑的瞬间）。 */
    private var sendInFlight = false
    private val server = ForgeServerManager.default(context)
    private val api = ForgeApiClient()

    /** 给界面层的公开提示入口（如附件添加失败）：与内部错误同一套呈现与自动过期。 */
    fun notify(text: String) = showNotice(text)

    /** 工作区在别处（切换菜单里增删改名）变过后，界面层调它刷新状态里的当前工作区与列表。 */
    fun refreshWorkspaces() = refreshWorkspaceState()

    /** 错误提示统一入口：设置后自动过期消失，不再永久挂着跨操作残留（成功路径会直接清）。 */
    private fun showNotice(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_TTL_MS)
            if (notice == text) notice = null
        }
    }

    private fun clearNotice() {
        noticeJob?.cancel()
        notice = null
    }

    /** busy 每次变更都留一条日志：再出现「没干完就收起」时看日志就能定位是哪条路径清的，不靠猜。 */
    private fun setBusy(value: Boolean, source: String) {
        if (busy != value) {
            AndroidAgentLogger.warn("Forge busy -> $value ($source) session=${sessionId ?: "-"}")
        }
        busy = value
    }

    /** 进入 Forge 时调用：启动 server → 拿 session → 拉全量消息 → 订阅事件。 */
    fun start() {
        if (startJob?.isActive == true) return
        // 面板重建（子页往返会整块销毁重建）会再调一次 start()：状态已是 Ready 就直接返回，
        // 既不重播启动遮罩，也不把当前会话重新对回「最近会话」。只把上次销毁时断掉的
        // 事件订阅接回去——否则重建后收不到任何事件，busy 与内容都不再更新。
        if (startup is ForgeStartup.Ready) {
            val current = sessionId
            if (current != null && eventJob?.isActive != true) {
                subscribe(current)
                alignSessionBusy(current)
            }
            return
        }
        startJob = scope.launch {
            startup = ForgeStartup.Connecting
            startupStage = ForgeStartupStage.Preparing
            val splashStartedAt = SystemClock.elapsedRealtime()
            clearNotice()
            // 配置与 Assist 共用同一套服务商设置：解析并应用（变了才重写配置并重启 server）。
            when (val applied = applySharedConfig()) {
                ConfigApply.NotConfigured -> {
                    startup = ForgeStartup.Failed(context.getString(R.string.forge_gate_config_missing))
                    return@launch
                }

                is ConfigApply.Failed -> {
                    startup = ForgeStartup.Failed(
                        context.getString(
                            R.string.forge_server_failed,
                            forgeErrorText(context, applied.output),
                        ),
                    )
                    return@launch
                }

                ConfigApply.Ready -> Unit
            }
            pendingWorkspaceName?.let { target ->
                pendingWorkspaceName = null
                // 侧边栏指定了工作区开新对话：先把选择切过去，server 直接以新工作区
                // 目录启动，并且落到空白页，不恢复旧工作区的最近会话。
                ForgeWorkspaces.select(target)
                pendingBlank = true
            }
            refreshWorkspaceState()
            startupStage = ForgeStartupStage.StartingServer
            val environment = server.environment()
            when (val result = server.ensureRunning(environment)) {
                is ForgeServerStartResult.Failed -> {
                    startup = ForgeStartup.Failed(
                        context.getString(
                            R.string.forge_server_failed,
                            forgeErrorText(context, result.code),
                        ),
                    )
                    return@launch
                }

                is ForgeServerStartResult.Running -> Unit
            }
            val pending = pendingOpenSessionId
            pendingOpenSessionId = null
            if (pending == null && pendingBlank) {
                // 启动期间就要求开新对话：连会话都不建，直接落到空白页。
                pendingBlank = false
                sessionId = null
                messages = emptyList()
                setBusy(false, "start-blank")
                pendingQuestion = null
                awaitSplashMinDuration(splashStartedAt)
                startup = ForgeStartup.Ready
                workspaceFallbackNotice()
                onSessionsChanged(null)
                return@launch
            }
            startupStage = ForgeStartupStage.Connecting
            // 按当前工作区的 directory 找会话：以前取「全局最新会话」，切到 B 工作区也会把
            // A 工作区的会话拉进来（消息写进 A 的目录）。workspace 为 null 时退回旧行为。
            val id = pending ?: api.ensureSession(workspace?.linuxPath).getOrElse { error ->
                startup = ForgeStartup.Failed(
                    context.getString(
                        R.string.forge_server_failed,
                        forgeErrorText(context, error.message.orEmpty()),
                    ),
                )
                return@launch
            }
            val loaded = api.loadMessages(id)
            if (loaded.isFailure && loaded.exceptionOrNull().hasHttpCode(404)) {
                // 刚拿到的会话立刻就 404（server 在启动间隙被换过）：退回空白页，
                // 会话等第一条消息发送时再建。
                sessionId = null
                messages = emptyList()
                setBusy(false, "start-404")
                awaitSplashMinDuration(splashStartedAt)
                startup = ForgeStartup.Ready
                workspaceFallbackNotice()
                onSessionsChanged(null)
                return@launch
            }
            if (loaded.isFailure) {
                // 非 404 的加载失败（网络/服务端抖动）：别把有记录的会话显示成空白页，
                // 落到失败页让用户重试，数据还在服务端。
                startup = ForgeStartup.Failed(
                    context.getString(
                        R.string.forge_load_failed,
                        forgeErrorText(context, loaded.exceptionOrNull()?.message.orEmpty()),
                    ),
                )
                return@launch
            }
            sessionId = id
            messages = loaded.getOrDefault(emptyList())
            api.sessionBusy(id).onSuccess { setBusy(it, "start-align"); if (it) markBusyObserved() }
            awaitSplashMinDuration(splashStartedAt)
            startup = ForgeStartup.Ready
            workspaceFallbackNotice()
            subscribe(id)
            refreshPendingQuestion(id)
            onSessionsChanged(id)
            maybeSetFallbackTitle(id)
        }
    }

    /** 工作区状态刷新：默认工作区确保存在，再读当前选择与全部列表。 */
    private fun refreshWorkspaceState() {
        ForgeWorkspaces.ensureDefault()
        workspaces = ForgeWorkspaces.all()
        workspace = ForgeWorkspaces.currentSelection()
    }

    /** 没有可用工作区（挂载建不出来）时明示：当前在私有目录干活、产出文件拿不出来，不静默。 */
    private fun workspaceFallbackNotice() {
        if (workspace == null) {
            notice = context.getString(R.string.forge_workspace_unavailable)
        }
    }

    /**
     * 切换工作区：记住选择 → server 以新工作区的目录重启 → 落到新工作区的空白页。
     * 旧工作区的会话留在它自己的列表里（侧边栏按当前 server 的会话刷新），与 DSH 的工作区语义一致。
     */
    fun switchWorkspace(name: String) {
        if (workspace?.name == name) return
        ForgeWorkspaces.select(name)
        refreshWorkspaceState()
        resetAndRestartServer()
    }

    /** 当前工作区被改名/移除后：选择已在 ForgeWorkspaces 里跟好，这里只负责把界面与 server 切到新目录。 */
    fun restartAfterWorkspaceStructureChange() {
        refreshWorkspaceState()
        resetAndRestartServer()
    }

    /** 侧边栏里增删改名了工作区：当前工作区跟着变了才重启 server 切过去，没变只刷新列表。 */
    fun onWorkspacesStructureChanged() {
        val before = workspace?.name
        refreshWorkspaceState()
        if (workspace?.name != before) {
            resetAndRestartServer()
        }
    }

    /** 面板创建时记下「在指定工作区开新对话」，start() 拉起 server 前应用（见 start 内）。 */
    fun requestWorkspaceOnStart(name: String) {
        pendingWorkspaceName = name
    }

    /** 切工作区的公共收尾：废掉在途发送与订阅、回空白页、让 server 以当前工作区目录重启。 */
    private fun resetAndRestartServer() {
        invalidateInFlightSend()
        openJob?.cancel()
        idleConfirmJob?.cancel()
        eventJob?.cancel()
        sessionId = null
        messages = emptyList()
        setBusy(false, "workspace-switch")
        pendingQuestion = null
        turnError = null
        clearNotice()
        onSessionsChanged(null)
        scope.launch {
            when (val result = server.restart(server.environment())) {
                is ForgeServerStartResult.Failed -> showNotice(
                    context.getString(
                        R.string.forge_server_failed,
                        forgeErrorText(context, result.code),
                    ),
                )

                ForgeServerStartResult.Running -> workspaceFallbackNotice()
            }
        }
    }

    /**
     * 等遮罩的最短展示时长走完再进对话：server 已被开屏预热时 start() 会瞬间走完，
     * 没有这道等待动画会被一闪而过——用户要的是每次进 Forge 都完整播一遍。
     * 只等在 Ready 之前，失败路径不走这里，报错要立刻露出来。
     */
    private suspend fun awaitSplashMinDuration(startedAtMs: Long) {
        val remaining = FORGE_SPLASH_MIN_DURATION_MS - (SystemClock.elapsedRealtime() - startedAtMs)
        if (remaining > 0) delay(remaining)
    }

    fun retry() {
        startJob?.cancel()
        startJob = null
        start()
    }

    /**
     * 把共用配置对齐到当前选择：解析服务商/模型/Key，与上次应用的指纹不同才
     * 重写 opencode 配置并重启 server（重启会等到健康才返回）。启动时调一次，
     * 之后每次发送前再调一次——用户在设置里换了 Key/服务商/模型，Forge 不必
     * 重进也要立刻生效，否则会一直拿启动时那套旧配置发，换掉的死 Key 一直 403。
     */
    private suspend fun applySharedConfig(): ConfigApply = configApplyMutex.withLock {
        val shared = resolveForgeSharedConfig()
        if (shared !is ForgeSharedConfig.Ready) return@withLock ConfigApply.NotConfigured
        modelId = shared.settings.modelId
        providerId = shared.settings.providerId
        contextWindowLimit = shared.settings.contextWindow
        val fingerprint = forgeConfigFingerprint(
            shared.settings,
            shared.credentials,
            LinuxEnvironmentSettingsRepository.current(context).wireName,
        )
        if (fingerprint != appliedConfigFingerprint) {
            when (val written = ForgeConfigWriter.write(context, shared.settings, shared.credentials)) {
                ForgeConfigWriteResult.Written -> {
                    // 重启失败要让调用方知道：server 没起来还继续往下发，只会无声卡死；
                    // 指纹只在重启成功后才认账，失败下次还要重试对齐。
                    val restarted = server.restartIfRunning(server.environment())
                    if (restarted is ForgeServerStartResult.Failed) {
                        return@withLock ConfigApply.Failed(restarted.code)
                    }
                    appliedConfigFingerprint = fingerprint
                }

                ForgeConfigWriteResult.EnvironmentNotReady -> Unit

                is ForgeConfigWriteResult.Failed -> return@withLock ConfigApply.Failed(written.output)
            }
        }
        ConfigApply.Ready
    }

    /**
     * 输入栏的发送入口：带走当前待发附件。本轮还在跑时不吞消息——排进队列，
     * 轮次自然结束时按序续发（以前 busy 直接 return，用户打的字发出去了个寂寞）。
     */
    fun send(text: String) {
        val attachments = pendingAttachments
        if (text.isBlank() && attachments.isEmpty()) return
        pendingAttachments = emptyList()
        if (busy) {
            queuedSends = queuedSends + ForgeQueuedSend(text, attachments)
            queuedTexts = queuedSends.map { queued ->
                queued.text.ifBlank { queued.attachments.firstOrNull()?.displayName.orEmpty() }
            }
            return
        }
        launchSend(text, attachments)
    }

    /** 删掉一条排队中的发送（输入栏队列条上的删除）。 */
    fun removeQueuedMessage(index: Int) {
        if (index !in queuedSends.indices) return
        queuedSends = queuedSends.filterIndexed { i, _ -> i != index }
        queuedTexts = queuedSends.map { queued ->
            queued.text.ifBlank { queued.attachments.firstOrNull()?.displayName.orEmpty() }
        }
    }

    fun addAttachment(attachment: ForgePendingAttachment) {
        pendingAttachments = pendingAttachments + attachment
    }

    fun removeAttachment(id: String) {
        pendingAttachments = pendingAttachments.filterNot { it.id == id }
    }

    /** 轮次确认结束时续发队列里的第一条；没有队列就什么都不做。 */
    private fun drainQueue() {
        if (busy) return
        val next = queuedSends.firstOrNull() ?: return
        queuedSends = queuedSends.drop(1)
        queuedTexts = queuedSends.map { queued ->
            queued.text.ifBlank { queued.attachments.firstOrNull()?.displayName.orEmpty() }
        }
        launchSend(next.text, next.attachments)
    }

    private fun clearQueue() {
        queuedSends = emptyList()
        queuedTexts = emptyList()
    }

    /**
     * 作废在途发送：换代 → 取消协程 → 显式清 sendInFlight。
     * 以前四处（切工作区/新建/打开会话/停止）都是「sendGeneration++ 再 cancel」，而
     * launchSend 的 finally 是 `if (generation == sendGeneration) sendInFlight = false`：
     * 换代之后判等恒假，sendInFlight 就永久停在 true。它唯一的消费点
     * （alignAfterReconnect 里「发送在途不许冲掉 busy」）于是永久失效。
     */
    private fun invalidateInFlightSend() {
        sendGeneration++
        sendJob?.cancel()
        sendJob = null
        sendInFlight = false
    }

    /** 本轮失败/被停止时清掉排队消息，并告诉用户清了几条（0 条不打扰）。 */
    private fun clearQueueWithNotice() {
        val dropped = queuedSends.size
        clearQueue()
        if (dropped > 0) {
            showNotice(context.getString(R.string.forge_queue_cleared, dropped))
        }
    }

    private fun launchSend(text: String, attachments: List<ForgePendingAttachment>) {
        val generation = ++sendGeneration
        sendInFlight = true
        setBusy(true, "send")
        clearNotice()
        turnError = null
        // 计时从用户点发送这一刻起算：哪怕消息还没在对话区回显，等待行也要立刻开始走秒。
        SystemClock.elapsedRealtime().let {
            turnStartedAtMs = it
            lastActivityAtMs = it
        }
        sendJob = scope.launch {
            try {
                performSend(generation, text, attachments)
            } finally {
                if (generation == sendGeneration) sendInFlight = false
            }
        }
    }

    private suspend fun performSend(
        generation: Int,
        text: String,
        attachments: List<ForgePendingAttachment>,
    ) {
        fun superseded() = generation != sendGeneration
        fun fail(source: String, message: String) {
            if (!superseded()) {
                setBusy(false, source)
                showNotice(message)
                // 本轮已经失败：排队消息不会再有人续发（drainQueue 只在 confirmIdle 里调），
                // 留着只会在下一次成功后突然一起发出去，索性清掉并告知条数。
                clearQueueWithNotice()
            }
        }
        // 发送前先对齐配置：换过 Key/服务商/模型时，这一步会把新配置写好并重启 server，
        // 再往下建会话/发消息就都走新配置。server 重启后当前会话的事件订阅会断线重连对齐。
        when (val applied = applySharedConfig()) {
            ConfigApply.NotConfigured -> {
                fail("send-noconfig", context.getString(R.string.forge_gate_config_missing))
                return
            }

            is ConfigApply.Failed -> {
                fail(
                    "send-configfail",
                    context.getString(
                        R.string.forge_send_failed,
                        forgeErrorText(context, applied.output),
                    ),
                )
                return
            }

            ConfigApply.Ready -> Unit
        }
        if (superseded()) return
        // 配置刚重启过、或 server 意外不在跑时，先确保它在跑再发；
        // 拉不起来就把失败摆到界面上，不要对着不在跑的 server 无声地发。
        when (val ensured = server.ensureRunning(server.environment())) {
            is ForgeServerStartResult.Failed -> {
                fail(
                    "send-serverfail",
                    context.getString(
                        R.string.forge_send_failed,
                        forgeErrorText(context, ensured.code),
                    ),
                )
                return
            }

            ForgeServerStartResult.Running -> Unit
        }
        if (superseded()) return
        // 附件先复制进当前工作区，opencode 才能以 file part 读到；单个失败只报名字、不连累整条消息。
        val staged = stageAttachments(attachments)
        if (staged.failedNames.isNotEmpty()) {
            showNotice(
                context.getString(
                    R.string.forge_attachment_failed,
                    staged.failedNames.joinToString("、"),
                ),
            )
        }
        if (attachments.isNotEmpty() && staged.outgoing.isEmpty() && text.isBlank()) {
            fail("send-attachfail", context.getString(R.string.forge_attachment_all_failed))
            return
        }
        if (superseded()) return
        // 建会话也按当前工作区找：没有会话时优先复用本工作区最近的会话（跨工作区串台的另一处入口），
        // 本工作区确实没有才新建。
        var id = sessionId ?: api.ensureSession(workspace?.linuxPath).getOrElse { error ->
            fail(
                "send-createfail",
                context.getString(
                    R.string.forge_send_failed,
                    forgeErrorText(context, error.message.orEmpty()),
                ),
            )
            return
        }
        if (sessionId == null) {
            sessionId = id
            subscribe(id)
            onSessionsChanged(id)
        }
        val firstTry = api.sendMessage(
            id,
            text,
            providerId,
            modelId,
            variant = reasoningEffort.forgeVariant(),
            attachments = staged.outgoing,
        )
        if (firstTry.isFailure && firstTry.exceptionOrNull().hasHttpCode(404)) {
            // 手里的会话在服务端已不存在（server 被重启换掉、或列表过期点进了已删的会话）：
            // 新建一个会话把这条消息重发一次，不要把 404 直接甩给用户。
            val fresh = api.createSession().getOrElse { error ->
                fail(
                    "send-createfail",
                    context.getString(
                        R.string.forge_send_failed,
                        forgeErrorText(context, error.message.orEmpty()),
                    ),
                )
                return
            }
            if (superseded()) return
            sessionId = fresh
            subscribe(fresh)
            onSessionsChanged(fresh)
            id = fresh
            api.sendMessage(
                id,
                text,
                providerId,
                modelId,
                variant = reasoningEffort.forgeVariant(),
                attachments = staged.outgoing,
            ).onFailure { error ->
                fail(
                    "send-failed",
                    context.getString(
                        R.string.forge_send_failed,
                        forgeErrorText(context, error.message.orEmpty()),
                    ),
                )
            }
            return
        }
        firstTry.onFailure { error ->
            fail(
                "send-failed",
                context.getString(
                    R.string.forge_send_failed,
                    forgeErrorText(context, error.message.orEmpty()),
                ),
            )
        }
        // 发送成功只是「已提交」：busy 保持，由事件流的 Idle/Failed 决定本轮何时结束。
    }

    /** 附件落盘结果：成功复制的 outgoing 列表 + 失败的文件名。 */
    private data class StageResult(
        val outgoing: List<ForgeOutgoingAttachment>,
        val failedNames: List<String>,
    )

    /** 把待发附件逐个复制进当前工作区的 Android 目录，算出各自的 Linux 路径。 */
    private suspend fun stageAttachments(
        attachments: List<ForgePendingAttachment>,
    ): StageResult = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext StageResult(emptyList(), emptyList())
        val ws = workspace ?: return@withContext StageResult(
            outgoing = emptyList(),
            failedNames = attachments.map { it.displayName },
        )
        val directory = File(ws.sourcePath)
        val outgoing = mutableListOf<ForgeOutgoingAttachment>()
        val failed = mutableListOf<String>()
        for (item in attachments) {
            runCatching {
                if (item.isFolder) {
                    val folderName = copyFolderIntoWorkspace(File(item.source), directory)
                    outgoing += ForgeOutgoingAttachment(
                        mime = "application/x-directory",
                        filename = folderName,
                        linuxPath = ws.linuxPath + "/" + folderName,
                    )
                } else {
                    val target = uniqueTargetFile(directory, sanitizeFilename(item.displayName))
                    val input = if (item.source.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(item.source))
                    } else {
                        File(item.source).inputStream()
                    } ?: error("cannot open attachment source")
                    input.use { stream -> target.outputStream().use { out -> stream.copyTo(out) } }
                    outgoing += ForgeOutgoingAttachment(
                        mime = item.mime,
                        filename = target.name,
                        linuxPath = ws.linuxPath + "/" + target.name,
                    )
                }
            }.onFailure { failed += item.displayName }
        }
        StageResult(outgoing, failed)
    }

    /**
     * 文件夹附件：整夹复制进工作区（opencode 在工作区里读它最稳），返回落盘后的文件夹名。
     * 带上限（文件数/总字节）防手滑把整个相册拖进来；超限整条附件判失败、由 notice 报名字。
     */
    private fun copyFolderIntoWorkspace(sourceDir: File, workspaceDir: File): String {
        require(sourceDir.isDirectory) { "folder attachment source is not a directory" }
        val targetDir = uniqueTargetFile(workspaceDir, sanitizeFilename(sourceDir.name.ifBlank { "folder" }))
        var fileCount = 0
        var totalBytes = 0L
        sourceDir.walkTopDown().forEach { node ->
            if (node.isFile) {
                fileCount++
                totalBytes += node.length()
                require(fileCount <= MAX_FOLDER_ATTACHMENT_FILES && totalBytes <= MAX_FOLDER_ATTACHMENT_BYTES) {
                    "folder attachment too large"
                }
            }
        }
        sourceDir.copyRecursively(targetDir, overwrite = false)
        return targetDir.name
    }

    /** 文件名清洗：空白折成下划线、去掉 URL 敏感字符，其余（含中文）原样保留。 */
    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[?#%<>:\\\"|*]"), "")
        return cleaned.ifBlank { "file" }
    }

    /** 目标文件重名时自动加 (2)、(3)… 后缀，不覆盖工作区里已有的文件。 */
    private fun uniqueTargetFile(directory: File, name: String): File {
        directory.mkdirs()
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base($index)$extension")
            index++
        }
        return candidate
    }

    /**
     * 进入时直接落到新对话空白页（模式切换的既定行为）：start() 会优先处理它，
     * 但排在「打开指定会话」之后——列表点开某条会话时那个请求说了算。
     */
    fun requestBlankOnStart() {
        pendingBlank = true
    }

    /**
     * 新建对话：回到空白页，**不立刻建会话**——会话等第一条消息发送时才建。
     * 否则每点一次新建，列表里就多一条空的「New session - …」，
     * 删除当前会话时也会立刻冒出一条新的，看起来像删不掉。
     * 旧会话留在服务端不删，正在跑的轮次不受影响。
     * 还没启动完时先记下，start() 就绪后直接落到空白页。
     */
    fun newConversation() {
        if (startup !is ForgeStartup.Ready) {
            pendingBlank = true
            return
        }
        // 在途发送必须先掐掉：它的 404 自愈/失败回写会把刚建的新对话又劫持回旧会话。
        invalidateInFlightSend()
        openJob?.cancel()
        idleConfirmJob?.cancel()
        eventJob?.cancel()
        clearQueue()
        sessionId = null
        messages = emptyList()
        setBusy(false, "new-conversation")
        clearNotice()
        turnError = null
        turnStartedAtMs = 0L
        pendingQuestion = null
        onSessionsChanged(null)
    }

    /**
     * 从侧边栏打开指定的 Forge 会话：切过去、拉它的消息、重订事件。
     * 还没启动完时先记下目标，start() 会优先打开它，而不是最近用的会话。
     */
    fun openSession(id: String) {
        if (id.isBlank()) return
        if (startup !is ForgeStartup.Ready) {
            pendingOpenSessionId = id
            return
        }
        if (id == sessionId) return
        // 连点两个会话时只认最后一次：取消上一个打开任务，迟到的加载结果不许覆盖。
        openJob?.cancel()
        invalidateInFlightSend()
        idleConfirmJob?.cancel()
        clearQueue()
        openJob = scope.launch {
            val loaded = api.loadMessages(id)
            if (loaded.isFailure && loaded.exceptionOrNull().hasHttpCode(404)) {
                // 侧边栏列表可能过期：这个会话在服务端已经没了。退回空白页并刷新列表，
                // 别把死会话留在手里——接下来发什么都是 404。
                // 旧订阅必须一起掐掉：它还按闭包里的旧 id 在校验，事件仍然「匹配」，
                // 会把已删会话的 part 写进空白页、还会把 busy 顶起来。
                eventJob?.cancel()
                eventJob = null
                sessionId = null
                pendingQuestion = null
                messages = emptyList()
                turnError = null
                setBusy(false, "open-gone")
                showNotice(context.getString(R.string.forge_session_gone))
                onSessionsChanged(null)
                return@launch
            }
            if (loaded.isFailure) {
                // 非 404 加载失败：当前会话原样保留，只报一句，别把有记录的会话显示成空白页。
                showNotice(
                    context.getString(
                        R.string.forge_load_failed,
                        forgeErrorText(context, loaded.exceptionOrNull()?.message.orEmpty()),
                    ),
                )
                return@launch
            }
            sessionId = id
            pendingQuestion = null
            messages = loaded.getOrDefault(emptyList())
            setBusy(false, "open-reset")
            turnError = null
            turnStartedAtMs = 0L
            alignSessionBusy(id)
            clearNotice()
            subscribe(id)
            refreshPendingQuestion(id)
            onSessionsChanged(id)
            maybeSetFallbackTitle(id)
        }
    }

    /**
     * 打开会话后对齐 busy：查询失败时稍后重试一次——以前失败就把 busy 停在 false，
     * 正在跑的会话看着像空闲（没有事件能再把它置回 true）。事件兜底在 handle() 里：
     * 收到任何 part 活动且 busy 为 false 时也会补置。
     */
    private fun alignSessionBusy(id: String) {
        scope.launch {
            val result = api.sessionBusy(id)
            if (result.isSuccess) {
                if (sessionId == id) {
                    val value = result.getOrThrow()
                    setBusy(value, "open-align")
                    if (value) markBusyObserved()
                }
                return@launch
            }
            delay(BUSY_ALIGN_RETRY_DELAY_MS)
            if (sessionId != id) return@launch
            api.sessionBusy(id).onSuccess {
                if (sessionId == id) {
                    setBusy(it, "open-align-retry")
                    if (it) markBusyObserved()
                }
            }
        }
    }

    /** 回答提问卡：答完 opencode 会把这一轮继续跑下去；失败保留卡片让用户重试。 */
    fun answerQuestion(answers: List<List<String>>) {
        val pending = pendingQuestion ?: return
        scope.launch {
            api.replyQuestion(pending, answers)
                .onSuccess {
                    if (pendingQuestion?.id == pending.id) pendingQuestion = null
                    // 提问期间收到过 idle（被我们压住了）：答完这一轮还要继续跑，
                    // 没有第二个 idle 会来，重新挂一次确认，否则 busy 永远解不开。
                    if (idleDuringQuestion) {
                        idleDuringQuestion = false
                        sessionId?.let { confirmIdle(it) }
                    }
                }
                .onFailure { error ->
                    showNotice(
                        context.getString(
                            R.string.forge_question_failed,
                            forgeErrorText(context, error.message.orEmpty()),
                        ),
                    )
                }
        }
    }

    /** 跳过提问卡：走 reject，模型收到拒绝后自己决定下一步。 */
    fun dismissQuestion() {
        val pending = pendingQuestion ?: return
        scope.launch {
            api.rejectQuestion(pending)
                .onSuccess {
                    if (pendingQuestion?.id == pending.id) pendingQuestion = null
                    // 同 answerQuestion：跳过之后这一轮还要继续，补挂一次结束确认。
                    if (idleDuringQuestion) {
                        idleDuringQuestion = false
                        sessionId?.let { confirmIdle(it) }
                    }
                }
                .onFailure { error ->
                    showNotice(
                        context.getString(
                            R.string.forge_question_failed,
                            forgeErrorText(context, error.message.orEmpty()),
                        ),
                    )
                }
        }
    }

    /** 向服务端对齐当前会话挂起中的 question（事件可能在断线/切会话期间错过）。 */
    private suspend fun refreshPendingQuestion(id: String) {
        api.listPendingQuestions().onSuccess { list ->
            if (sessionId == id) {
                pendingQuestion = list.firstOrNull { it.sessionId == id }
            }
        }
    }

    /**
     * 标题兜底：opencode 靠一笔单独的标题生成调用改名，那笔调用在中转站上失败时，
     * 标题会永远停在「New session - 时间戳」。本会话已有内容、标题却还是默认样式时，
     * 用第一条用户消息当标题写回去；服务端已经有正经标题则不碰。
     */
    private fun maybeSetFallbackTitle(id: String) {
        val firstUserText = messages.firstOrNull { it.role == ForgeRole.User }
            ?.parts?.filterIsInstance<ForgePart.Text>()?.firstOrNull()?.text
            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (firstUserText.isBlank()) return
        scope.launch {
            val summary = api.listSessions().getOrNull()?.firstOrNull { it.id == id }
                ?: return@launch
            if (summary.title.isNotBlank() && !summary.title.startsWith("New session")) {
                return@launch
            }
            api.renameSession(id, firstUserText.take(30))
            onSessionsChanged(id)
        }
    }

    fun stop() {
        // 先掐断在途的发送并立刻放开 busy：以前是等 abort 请求回来才清 busy，
        // server 无响应时停止按钮等于没按，整个会话被一个 busy 永久卡死。
        invalidateInFlightSend()
        idleConfirmJob?.cancel()
        setBusy(false, "stop")
        // 停止 = 这一轮作废：排队消息一并清掉并告知条数。留着它们的话，下一轮成功收尾时
        // drainQueue 会把用户已经放弃的消息偷偷发出去。
        clearQueueWithNotice()
        // 提问卡还挂着时停止 = 这一轮作废，卡片一并撤掉，别留一张答了也没用的卡。
        pendingQuestion = null
        val id = sessionId ?: return
        scope.launch { api.abort(id) }
    }

    fun shutdown() {
        eventJob?.cancel()
        startJob?.cancel()
        openJob?.cancel()
        idleConfirmJob?.cancel()
        noticeJob?.cancel()
    }

    private fun subscribe(id: String) {
        eventJob?.cancel()
        eventJob = scope.launch {
            while (isActive) {
                try {
                    api.events().collect { event -> handle(id, event) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // SSE 断线：重新拉一次全量消息对齐，不自己编内容。
                    alignAfterReconnect(id)
                    // 第二解锁通道：事件流断了就再也收不到 idle，busy 只能靠 HTTP 复核解开。
                    if (busy && sessionId == id) confirmIdle(id)
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                alignAfterReconnect(id)
                // 正常 EOF 也会让 collect 返回（服务端换进程/连接被关）：同样补一次 HTTP 复核。
                if (busy && sessionId == id) confirmIdle(id)
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    /**
     * 断线重连对齐：拉全量消息，并按服务端状态对齐 busy——
     * 断线期间发出的 idle 事件收不到，只对齐消息会让 busy 永远挂着。
     * 已切到别的会话时不回写（sessionId 已变）。
     */
    private suspend fun alignAfterReconnect(id: String) {
        val loaded = api.loadMessages(id)
        if (loaded.isFailure && loaded.exceptionOrNull().hasHttpCode(404)) {
            // 断线期间 server 被换过，这个会话已经不存在了：清掉死状态，
            // 别让它一直挂在手里、之后每次发送都 404。
            if (sessionId == id) {
                sessionId = null
                messages = emptyList()
                setBusy(false, "reconnect-gone")
                pendingQuestion = null
                onSessionsChanged(null)
            }
            return
        }
        loaded.onSuccess { if (sessionId == id) messages = it }
        api.sessionBusy(id).onSuccess {
            // 发送在途时不许对齐把 busy 冲掉：消息刚提交、服务端还没开跑的瞬间状态是 idle，
            // 照单全收会让等待行消失、还能重复发送，stop 也会停错对象。
            if (sessionId == id && !sendInFlight) {
                setBusy(it, "reconnect-align")
                if (it) markBusyObserved()
            }
        }
        refreshPendingQuestion(id)
    }

    /** 打开/重连到一条正在跑的会话时补上计时起点：发送不在本页发生，只能从观察到 busy 起算。 */
    private fun markBusyObserved() {
        if (turnStartedAtMs == 0L) {
            SystemClock.elapsedRealtime().let {
                turnStartedAtMs = it
                lastActivityAtMs = it
            }
        }
    }

    /** 收到内容活动时的统一处理：刷新活动时刻、busy 兜底补置。待确认的 idle 不在这里撤销——确认循环自己会看活动时刻，活动变了它会重新等一段静默期。 */
    private fun onSessionActivity() {
        lastActivityAtMs = SystemClock.elapsedRealtime()
        if (!busy) {
            // 有 part 在动就说明这一轮在跑：打开会话时 busy 对齐失败留下的假空闲靠这里兜底。
            setBusy(true, "part-activity")
            markBusyObserved()
            // idle 事件可能已经丢了（断线间隙）：给兜底置忙的轮次也挂上结束确认，
            // 免得 busy 只能等人手动停止。确认走同一套「静默 + 服务端闲」双条件。
            // 发送在途时不挂：这时服务端状态本来就是 idle，挂了只会立刻误收尾。
            if (!sendInFlight) sessionId?.let { confirmIdle(it) }
        }
    }

    /**
     * session.idle 不能照单全收：opencode 在 question 等待等「让出」时刻也会发 idle，
     * 任务没干完就被收起、汇总头提前出现就是这么来的。收到 idle 先挂起，
     * 等一段静默期后查服务端状态确认真闲、且期间没有新内容、没有待答提问，才收尾并续发队列。
     * 确认必须是循环：以前等的那一拍里只要来一点新内容、或服务端状态慢半拍还报忙，
     * 确认就被直接丢弃且再也不会重来——idle 事件已经消费掉了，busy 从此永远挂着
     * （输出完了还在「正在思考」、停止按钮不收，就是这个洞）。现在两种情况都重新等下一拍，
     * 直到真静默且服务端真闲才收尾；长任务期间服务端一直报忙，循环就一直守着，不误收。
     */
    private fun confirmIdle(session: String) {
        idleConfirmJob?.cancel()
        idleConfirmJob = scope.launch {
            var rechecks = 0
            var unknown = 0
            while (true) {
                if (sessionId != session) return@launch
                // 提问卡挂着 = 这一轮在等回答，idle 只是让出。以前这里直接 return，
                // 而回答成功后没有任何人重新挂确认，busy 就永久挂着。现在只等，不放弃。
                if (pendingQuestion != null) {
                    delay(IDLE_RECHECK_DELAY_MS)
                    continue
                }
                // 发送在途（消息刚提交、服务端还没开跑）时服务端状态就是 idle：不许据此收尾。
                // 这条守卫在收尾前还要再查一次，因为等待期间状态可能变化。
                if (sendInFlight) {
                    delay(IDLE_RECHECK_DELAY_MS)
                    continue
                }
                val activityAtWaitStart = lastActivityAtMs
                delay(if (rechecks == 0) IDLE_CONFIRM_DELAY_MS else IDLE_RECHECK_DELAY_MS)
                if (sessionId != session) return@launch
                if (pendingQuestion != null || sendInFlight) continue
                if (lastActivityAtMs != activityAtWaitStart) {
                    // 等待期间又有新内容：这一轮还没完，重新等下一段静默期。
                    rechecks = 0
                    continue
                }
                val stillBusy = api.sessionBusy(session).getOrNull()
                if (stillBusy == null) {
                    // 查询失败（断网/超时/404）≠ 服务端不忙：以前把失败当「真闲」直接收尾，
                    // 模型还在跑就被判结束、队列立刻补发下一条。未知就退避重查，
                    // 连续未知到上限（约 20s）才当作结束，并留下 warn 以便事后查。
                    unknown++
                    if (unknown >= IDLE_UNKNOWN_MAX) {
                        AndroidAgentLogger.warn(
                            "Forge idle confirmed after $unknown unknown probes session=$session",
                        )
                        finishIdle(session)
                        return@launch
                    }
                    rechecks++
                    continue
                }
                if (stillBusy) {
                    if (rechecks == 0) {
                        AndroidAgentLogger.warn("Forge idle deferred: server still busy session=$session")
                    }
                    rechecks++
                    continue
                }
                finishIdle(session)
                return@launch
            }
        }
    }

    /** 一轮真正结束时的收尾：放开 busy、通知列表刷新、补标题、续发队列。 */
    private fun finishIdle(session: String) {
        setBusy(false, "idle-confirmed")
        // 一轮结束时 opencode 可能刚给会话生成了标题，通知上层刷新列表。
        onSessionsChanged(session)
        maybeSetFallbackTitle(session)
        drainQueue()
    }

    private fun handle(session: String, event: ForgeEvent) {
        when (event) {
            is ForgeEvent.PartUpdated -> {
                if (event.sessionId == session) {
                    onSessionActivity()
                    upsertPart(event.part)
                }
            }

            is ForgeEvent.PartDelta -> {
                if (event.sessionId == session) {
                    onSessionActivity()
                    appendDelta(event)
                }
            }

            is ForgeEvent.MessageUpdated -> {
                if (event.sessionId == session) {
                    onSessionActivity()
                    ensureMessage(event.messageId, event.role)
                }
            }

            // 空 sessionID（协议没带）按当前会话处理：以前直接 Ignored，busy 就永远解不开了。
            is ForgeEvent.Idle -> if (event.sessionId.isBlank() || event.sessionId == session) {
                if (pendingQuestion != null) {
                    // 提问还挂着 = 这一轮在等回答，idle 只是让出，不许收尾。
                    // 记下来：回答成功后要主动重新挂一次确认，否则不会再有第二个 idle 到来。
                    idleDuringQuestion = true
                    AndroidAgentLogger.warn("Forge idle deferred: question pending session=$session")
                } else {
                    confirmIdle(session)
                }
            }

            is ForgeEvent.Failed -> if (event.sessionId.isBlank() || event.sessionId == session) {
                idleConfirmJob?.cancel()
                setBusy(false, "failed")
                // 失败这一轮结束了：排队消息不会再有 confirmIdle 来续发，清掉并告知条数。
                clearQueueWithNotice()
                // 手动停止触发的 Aborted 不是故障，不算失败。
                // 真失败画进对话区：错误行钉在本轮末尾（红点 + 错误原文 + 状态码），
                // 不再只在输入框上方飘一句转瞬即逝的通知；对话区还是空的时才退回通知。
                if (!event.isAbort) {
                    val text = event.message?.takeIf { it.isNotBlank() }
                    if (text != null && messages.isNotEmpty()) {
                        turnError = ForgeTurnError(
                            message = text,
                            statusCode = event.statusCode,
                            anchorMessageId = messages.lastOrNull()?.id,
                        )
                    } else if (text != null) {
                        showNotice(context.getString(R.string.forge_run_failed, text))
                    }
                }
            }

            is ForgeEvent.QuestionAsked -> {
                // 只接当前打开会话的提问；别的会话的提问等切回去时靠列表对齐捡回来。
                if (event.request.sessionId == session) {
                    idleConfirmJob?.cancel()
                    pendingQuestion = event.request
                    if (!busy) setBusy(true, "question-asked")
                }
            }

            is ForgeEvent.QuestionCleared -> {
                // 只认「请求 id 精确命中 + 会话对得上（或事件没带会话）」的清除；
                // 以前 requestId 为空就无条件清当前卡，别的会话答完题会把这边的卡误撤，
                // 而这边还挂在提问上没人答、整轮挂死。
                val current = pendingQuestion
                if (current != null && event.requestId.isNotBlank() &&
                    event.requestId == current.id &&
                    (event.sessionId == null || event.sessionId == session)
                ) {
                    pendingQuestion = null
                }
            }

            is ForgeEvent.Ignored -> Unit
        }
    }

    private fun ensureMessage(messageId: String, role: ForgeRole) {
        if (messages.any { it.id == messageId }) return
        messages = messages + ForgeChatMessage(id = messageId, role = role, parts = emptyList())
    }

    private fun upsertPart(part: ForgePart) {
        val current = messages.toMutableList()
        val messageIndex = current.indexOfFirst { it.id == part.messageId }
        if (messageIndex >= 0) {
            val message = current[messageIndex]
            val parts = message.parts.toMutableList()
            val partIndex = parts.indexOfFirst { it.id == part.id }
            if (partIndex >= 0) {
                parts[partIndex] = mergePartText(parts[partIndex], part)
            } else {
                parts.add(part)
            }
            current[messageIndex] = message.copy(parts = parts)
        } else {
            current.add(
                ForgeChatMessage(
                    id = part.messageId,
                    role = ForgeRole.Assistant,
                    parts = listOf(part),
                ),
            )
        }
        messages = current
    }

    /**
     * part.updated 的开始快照 text 为空，不能把 delta 已攒出的文本冲掉；
     * 未结束的文本/思考段只接受不比现有短的全文，结束快照的全文照单全收。
     */
    private fun mergePartText(existing: ForgePart, incoming: ForgePart): ForgePart = when {
        existing is ForgePart.Text && incoming is ForgePart.Text ->
            if (!incoming.ended && incoming.text.length < existing.text.length) {
                incoming.copy(text = existing.text)
            } else {
                incoming
            }

        existing is ForgePart.Reasoning && incoming is ForgePart.Reasoning ->
            if (!incoming.ended && incoming.text.length < existing.text.length) {
                incoming.copy(text = existing.text)
            } else {
                incoming
            }

        else -> incoming
    }

    /** 把增量文本接到对应 part 末尾；part 还没出现时忽略，后续 part.updated / 全量拉取会补齐。 */
    private fun appendDelta(event: ForgeEvent.PartDelta) {
        val current = messages.toMutableList()
        val messageIndex = if (event.messageId != null) {
            current.indexOfFirst { it.id == event.messageId }
        } else {
            current.indexOfFirst { message -> message.parts.any { it.id == event.partId } }
        }
        if (messageIndex < 0) return
        val message = current[messageIndex]
        val parts = message.parts.toMutableList()
        val partIndex = parts.indexOfFirst { it.id == event.partId }
        if (partIndex < 0) return
        parts[partIndex] = when (val part = parts[partIndex]) {
            is ForgePart.Text -> part.copy(text = part.text + event.delta)
            is ForgePart.Reasoning -> part.copy(text = part.text + event.delta)
            else -> return
        }
        current[messageIndex] = message.copy(parts = parts)
        messages = current
    }

    /** DEFAULT / OFF 不发 variant（用上游默认）；其余档位按 wireValue 选 variant。 */
    private fun ReasoningEffort.forgeVariant(): String? = when (this) {
        ReasoningEffort.DEFAULT, ReasoningEffort.OFF -> null
        else -> wireValue
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 1_000L

        /** session.idle 的去抖确认延迟：真结束不会在这期间有新内容，让出型 idle 会被服务端状态否掉。 */
        const val IDLE_CONFIRM_DELAY_MS = 800L

        /** idle 确认没过（还有新内容 / 服务端还报忙）时，下一拍复查的间隔。 */
        const val IDLE_RECHECK_DELAY_MS = 2_000L

        /**
         * 连续「查不出来」的次数上限：查询失败不是「不忙」，但也不能无限等下去——
         * 断网时按 2s 一拍，约 20s 后按结束处理并打 warn，避免 busy 永久挂住。
         */
        const val IDLE_UNKNOWN_MAX = 10

        /** 打开会话后 busy 对齐失败的重试延迟。 */
        const val BUSY_ALIGN_RETRY_DELAY_MS = 600L

        /** 错误提示的自动消失时长，别再永久挂着跨操作残留。 */
        const val NOTICE_TTL_MS = 6_000L

        /** 文件夹附件复制进工作区的上限：文件数与总字节，超了整条判失败。 */
        const val MAX_FOLDER_ATTACHMENT_FILES = 300
        const val MAX_FOLDER_ATTACHMENT_BYTES = 64L * 1024 * 1024
    }
}
