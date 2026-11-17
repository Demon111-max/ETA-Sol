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

    /** 配置没就绪，带具体原因（3.4.43 N19）：六条不同的路各自显示对应的话。 */
    data class NotConfigured(val reason: ForgeSharedConfig.NotConfigured.Reason) : ConfigApply

    data class Failed(val output: String) : ConfigApply
}

/**
 * 配置没就绪时的用户可读说明（N19）：以前六种原因共用一句「还没配置」，
 * 用户不知道该去补 Key、选模型还是换服务商。
 */
private fun forgeConfigMissingText(
    context: Context,
    reason: ForgeSharedConfig.NotConfigured.Reason,
): String = context.getString(
    when (reason) {
        ForgeSharedConfig.NotConfigured.Reason.PROVIDER_DISABLED ->
            R.string.forge_gate_config_provider_disabled

        ForgeSharedConfig.NotConfigured.Reason.PROVIDER_INCOMPATIBLE ->
            R.string.forge_gate_config_provider_incompatible

        ForgeSharedConfig.NotConfigured.Reason.AUTH_MODE_UNSUPPORTED ->
            R.string.forge_gate_config_auth_mode

        ForgeSharedConfig.NotConfigured.Reason.NO_MODEL ->
            R.string.forge_gate_config_no_model

        ForgeSharedConfig.NotConfigured.Reason.MISSING_BASE_URL ->
            R.string.forge_gate_config_missing_base_url

        ForgeSharedConfig.NotConfigured.Reason.MISSING_API_KEY ->
            R.string.forge_gate_config_missing_key

        ForgeSharedConfig.NotConfigured.Reason.MISSING_MODEL_ID ->
            R.string.forge_gate_config_missing_model
    },
)

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
    /** 稳定 id：输入栏队列条用它做 key，删中间一条时后面的条目不会串位、删错。 */
    val id: Long,
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

    /**
     * 3.4.43（N10）：提问卡提交/跳过是否在途。以前没有在途标记，按钮在请求回来前一直可点，
     * 双击会先 404 再回退重试、最后弹一句假的「回答失败」。UI 据此禁用按钮。
     */
    var questionAnswerInFlight by mutableStateOf(false)
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

    /** 与 [queuedTexts] 一一对应的稳定 id：输入栏的队列条用它做 key。 */
    var queuedIds by mutableStateOf<List<Long>>(emptyList())
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

    /** 排队发送的自增 id 源（只增不减，删条目不会让别的条目换 key）。 */
    private var nextQueuedId = 0L

    /** 发送世代号：每次真正发起发送 +1，新建/切换/停止也 +1；在途发送回写前校验，防迟到回调劫持当前界面。 */
    private var sendGeneration = 0

    /** 是否有发送协程在途：重连对齐 busy 时不能把发送中的 busy 冲掉（对齐只信服务端会误判刚提交未开跑的瞬间）。 */
    private var sendInFlight = false

    /** 最近一次 idle 收尾的时刻：用来识别「同一轮迟到的 part」，别把它当新一轮复活 busy。 */
    private var idleConfirmedAtMs = 0L

    /**
     * 3.4.43（B4）「显式新建 = 全新会话」标记：newConversation()/落空白页时置位，
     * 下一次发送必须 createSession() 新建，绝不许经任何「找已有会话」的分支复用。
     */
    private var mustCreateSession = false

    /**
     * 3.4.43（B0）停止水位：stop() 时置位不为 null，此后到达的 part/message 更新一律丢弃不渲染，
     * 界面按下即定格——服务端已发出、在途的 part 不会再往屏幕上冒。
     * 新一轮发送 / 打开别的会话 / 本轮收尾（idle 或失败）时解除。
     */
    private var stopFrozen = false

    /**
     * 本轮开始前已有消息的 id 集合：用来判断「哪些消息是这一轮产生的」。
     * 失败错误行只许锚在本轮消息上，本轮一条都没有就独立成行（N5），不许挂到上一轮末条。
     */
    private var turnStartMessageIds: Set<String> = emptySet()

    /**
     * 上次 refreshWorkspaceState 观察到的挂载指纹（N16）。结构变更的回调发生在落盘之后，
     * 那时再读「before」两次必然相等、分支永不成立；所以「before」只能是上次观察到的值。
     */
    private var lastMountsFingerprint: String? = null
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
            if (current != null) {
                if (eventJob?.isActive != true) {
                    subscribe(current)
                    alignSessionBusy(current)
                }
                // 3.4.43（N1）：面板销毁时 shutdown() 取消了 eventJob，离开期间生成的内容
                // /event 不会重放——重进不补拉，这段回答（以及失败状态）就永远回不到转录里，
                // busy 却显示已完成。每次重进都补一次全量消息对齐。
                openJob?.cancel()
                openJob = scope.launch { alignAfterReconnect(current) }
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
                is ConfigApply.NotConfigured -> {
                    startup = ForgeStartup.Failed(forgeConfigMissingText(context, applied.reason))
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
            // 3.4.43（B4）：start() 不再按工作区目录去「捡最近的会话」。
            // 只有明确要求打开某条会话（侧边栏点开 → pendingOpenSessionId，恢复的是已知的具体
            // sessionId）才拉旧内容；其余一律落空白页，会话由第一条消息显式新建。
            // 以前这里是 ensureSession(workspace?.linuxPath)：它会把本工作区最近更新的一条旧会话
            // 捡回来续聊（旧历史全量进上下文，所以「新会话」既不是新的、还特别慢）。
            if (pending == null) {
                // 没有可用工作区（默认目录建不出来、且没有别的挂载）时更不能建会话——那会得到
                // 一个谁也认领不了的孤儿会话；有工作区时同样落空白页（显式新建的对话 = 全新会话）。
                pendingBlank = false
                enterBlankPage(if (workspace == null) "start-noworkspace" else "start-blank")
                awaitSplashMinDuration(splashStartedAt)
                startup = ForgeStartup.Ready
                // 没有工作区就明示产出会落在私有目录（不静默）；有工作区时不打扰。
                if (workspace == null) workspaceFallbackNotice()
                // 「当前会话」在空白页上不存在，侧边栏不能高亮某条旧会话。
                onSessionsChanged(null)
                replayPendingNavigation()
                return@launch
            }
            startupStage = ForgeStartupStage.Connecting
            val id = pending
            val loaded = api.loadMessages(id)
            if (loaded.isFailure && loaded.exceptionOrNull().hasHttpCode(404)) {
                // 刚拿到的会话立刻就 404（server 在启动间隙被换过、或这条会话已被删）：
                // 退回空白页，会话等第一条消息发送时再新建。
                enterBlankPage("start-404")
                awaitSplashMinDuration(splashStartedAt)
                startup = ForgeStartup.Ready
                onSessionsChanged(null)
                replayPendingNavigation()
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
            // 启动窗口内又点了「开某个会话 / 开新对话」：这里补一次消费，别留到下次 start。
            replayPendingNavigation()
        }
    }

    /** 工作区状态刷新：默认工作区确保存在，再读当前选择与全部列表。 */
    private fun refreshWorkspaceState() {
        ForgeWorkspaces.ensureDefault()
        workspaces = ForgeWorkspaces.all()
        workspace = ForgeWorkspaces.currentSelection()
        // 记下这一刻的挂载指纹：结构变更回调发生在落盘之后，那时再读「变更前」两次必然相等。
        lastMountsFingerprint = ForgeWorkspaces.mountsFingerprint()
    }

    /**
     * 落到空白页（3.4.43 B4）：不建会话、不拉消息，并打上 must-create 标记——
     * 从这里发出的第一条消息必须是 createSession() 新建，绝不许复用任何已有会话。
     * 这是「显式新建的对话 = 全新会话」这条语义的唯一落点。
     */
    private fun enterBlankPage(source: String) {
        mustCreateSession = true
        openJob?.cancel()
        idleConfirmJob?.cancel()
        stopFrozen = false
        turnStartMessageIds = emptySet()
        sessionId = null
        messages = emptyList()
        setBusy(false, source)
        pendingQuestion = null
        turnError = null
        turnStartedAtMs = 0L
        clearNotice()
    }

    /** 没有可用工作区（挂载建不出来）时明示：当前在私有目录干活、产出文件拿不出来，不静默。 */
    private fun workspaceFallbackNotice() {
        if (workspace == null) {
            // 走 showNotice 而不是直接写 notice：直接赋值绕过了 TTL 与「同一条不重复弹」的去重，
            // 这条提示会一直挂在页面上，后面所有 showNotice 也覆盖不掉它。
            showNotice(context.getString(R.string.forge_workspace_unavailable))
        }
    }

    /**
     * 启动挂起窗口内新到的导航请求（开某会话 / 开新对话）：`start()` 只在拉起 server 前
     * 取一次 pending，窗口内点进来的请求会留在字段里，等到下次 start 才被消费——表现为
     * 「点了没反应」，隔一次进面板又莫名跳走。Ready 之后补一次消费，把它就地执行掉。
     */
    private fun replayPendingNavigation() {
        val queuedOpen = pendingOpenSessionId
        if (queuedOpen != null) {
            pendingOpenSessionId = null
            pendingBlank = false
            openSession(queuedOpen)
            return
        }
        if (pendingBlank) {
            pendingBlank = false
            newConversation()
        }
    }

    /**
     * 切换工作区：记住选择 → server 以新工作区的目录重启 → 落到新工作区的空白页。
     * 旧工作区的会话留在它自己的列表里（侧边栏按当前 server 的会话刷新），与 DSH 的工作区语义一致。
     */
    fun switchWorkspace(name: String) {
        if (workspace?.name == name) return
        // 侧边栏/外部调用可能递来一个已经被删掉的工作区名：写进 prefs 会让「当前工作区」
        // 指向不存在的挂载，server 起来后 cwd 落空、会话成了孤儿。这里先校验存在性。
        if (ForgeWorkspaces.all().none { it.name == name }) {
            showNotice(context.getString(R.string.forge_workspace_unavailable))
            refreshWorkspaceState()
            return
        }
        ForgeWorkspaces.select(name)
        refreshWorkspaceState()
        resetAndRestartServer()
    }

    /** 当前工作区被改名/移除后：选择已在 ForgeWorkspaces 里跟好，这里只负责把界面与 server 切到新目录。 */
    fun restartAfterWorkspaceStructureChange() {
        refreshWorkspaceState()
        resetAndRestartServer()
    }

    /**
     * 侧边栏里增删改名了工作区：只有「当前工作区名变了」才重启 server。名字没变但挂载集合
     * 变了（改名往返、删掉别的挂载、挂载表重排）时 server 的 --cwd 与可见挂载都已过期，
     * 会话会写进旧目录，因此再加一条挂载指纹比对。
     */
    fun onWorkspacesStructureChanged() {
        val beforeName = workspace?.name
        // 3.4.43（N16）：这个回调是在挂载表**已经落盘之后**才来的，此时再读一次「变更前」的
        // 指纹两次必然相等，指纹分支以前永远不成立——只剩「当前工作区改名/删除」能触发重启，
        // 其它挂载的增删改名都靠 ensureRunning 在下次发送时兜底重启，把几十秒卡顿甩到发送途中。
        // 现在「before」取上一次实际观察到的指纹（refreshWorkspaceState 里记的），才是真前值。
        val beforeFingerprint = lastMountsFingerprint
        refreshWorkspaceState()
        val afterFingerprint = lastMountsFingerprint
        val structureChanged = beforeFingerprint != null && afterFingerprint != beforeFingerprint
        if (workspace?.name != beforeName || structureChanged) {
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
        // 3.4.43（N8）：待发附件是按「当前工作区目录」暂存/落盘的，切工作区后它们会掉进新工作区
        // 的目录里（旧工作区的相对路径失效）。切之前清掉并明示，免得用户以为附件还挂着。
        // 草稿（draft）保留：那是用户正在打的字，切工作区不该吞掉。
        clearNotice()
        val droppedAttachments = pendingAttachments.size
        if (droppedAttachments > 0) {
            pendingAttachments = emptyList()
            showNotice(context.getString(R.string.forge_attachments_cleared, droppedAttachments))
        }
        // 切工作区 = 换会话域：下一个会话由首条消息显式新建，不许捡新工作区里的最近旧会话（B4）。
        mustCreateSession = true
        stopFrozen = false
        turnStartMessageIds = emptySet()
        sessionId = null
        messages = emptyList()
        setBusy(false, "workspace-switch")
        pendingQuestion = null
        turnError = null
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
        // 重试是「从头再来」：上一轮留下的错误条、秒表起点、残提问都必须清掉，否则失败页
        // 重试成功后仍带着旧的 turnError 与一个从上次开始就算的计时。
        turnError = null
        turnStartedAtMs = 0L
        pendingQuestion = null
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
        if (shared !is ForgeSharedConfig.Ready) {
            // 未就绪带原因：调用方按原因给具体提示（N19）。
            val reason = (shared as? ForgeSharedConfig.NotConfigured)?.reason
                ?: ForgeSharedConfig.NotConfigured.Reason.PROVIDER_DISABLED
            return@withLock ConfigApply.NotConfigured(reason)
        }
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
        // 3.4.43（N7）：没有工作区就不许发。以前只看 startup==Ready 就放行，workspace==null 时
        // 发送路径会走「全局捡会话」分支，跟 start() 专门防孤儿会话的逻辑对着干。
        // 现在发送路径上已无任何「捡会话」分支，没有工作区就直接拒绝并提示先添加。
        if (workspace == null) {
            showNotice(context.getString(R.string.forge_send_needs_workspace))
            return
        }
        pendingAttachments = emptyList()
        if (busy) {
            queuedSends = queuedSends + ForgeQueuedSend(++nextQueuedId, text, attachments)
            syncQueueState()
            return
        }
        // 3.4.43（N2）：队列里还有存量（上一轮在别处结束、或 busy 被对齐为闲）时先排进队按序续发，
        // 不许这条后发的消息插到存量前面抢先发出去。
        if (queuedSends.isNotEmpty()) {
            queuedSends = queuedSends + ForgeQueuedSend(++nextQueuedId, text, attachments)
            syncQueueState()
            drainQueue()
            return
        }
        launchSend(text, attachments)
    }

    /** 队列的两个对外列表（文本 + 稳定 id）永远一起更新，别让它们错位。 */
    private fun syncQueueState() {
        queuedTexts = queuedSends.map { queued ->
            queued.text.ifBlank { queued.attachments.firstOrNull()?.displayName.orEmpty() }
        }
        queuedIds = queuedSends.map { it.id }
    }

    /** 删掉一条排队中的发送（输入栏队列条上的删除）：按稳定 id 删，不按位置。 */
    fun removeQueuedMessage(id: Long) {
        val remaining = queuedSends.filterNot { it.id == id }
        if (remaining.size == queuedSends.size) return
        queuedSends = remaining
        syncQueueState()
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
        syncQueueState()
        launchSend(next.text, next.attachments)
    }

    private fun clearQueue() {
        queuedSends = emptyList()
        syncQueueState()
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
        // 本轮开始前的消息快照：失败错误行只许锚在本轮产生的消息上（N5）。
        turnStartMessageIds = messages.map { it.id }.toSet()
        // 新一轮开始 = 停止水位作废：新的 part 必须能正常渲染（B0）。
        stopFrozen = false
        idleDuringQuestion = false
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
        suspend fun fail(source: String, message: String) {
            if (superseded()) return
            setBusy(false, source)
            showNotice(message)
            // 本轮已经失败：排队消息不会再有人续发，留着只会在下一次成功后突然一起发出去，
            // 索性清掉并告知条数。
            clearQueueWithNotice()
            // 3.4.43（N5）：发送失败后本地可能已经落后于服务端（消息其实已部分发出、服务端也
            // 可能已产出错误块），补一次全量对齐，别让界面长期与服务端不一致。
            reloadAfterFailure()
        }
        // 发送前先对齐配置：换过 Key/服务商/模型时，这一步会把新配置写好并重启 server，
        // 再往下建会话/发消息就都走新配置。server 重启后当前会话的事件订阅会断线重连对齐。
        when (val applied = applySharedConfig()) {
            is ConfigApply.NotConfigured -> {
                fail("send-noconfig", forgeConfigMissingText(context, applied.reason))
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
        // 3.4.43（B4）：会话解析只剩两条路——手里已有具体 sessionId 就用它；否则一律
        // createSession() 新建。这里**没有**、也不许再有「按工作区目录找最近会话」的分支：
        // 那正是「只发了一句你好，对话里却堆着旧测试会话多条历史」的根因。
        var id = sessionId
        if (id == null || mustCreateSession) {
            id = api.createSession().getOrElse { error ->
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
            // 标记已消费：这个会话就是本次显式新建的结果。
            mustCreateSession = false
            sessionId = id
            messages = emptyList()
            turnStartMessageIds = emptySet()
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
            mustCreateSession = false
            sessionId = fresh
            messages = emptyList()
            turnStartMessageIds = emptySet()
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

    /**
     * 失败后的全量对齐（3.4.43 N5）：拉一次服务端消息，把本地转录补回真实状态。
     * 只对齐内容，不动 busy——那次发送已经收尾了。
     */
    private suspend fun reloadAfterFailure() {
        val id = sessionId ?: return
        api.loadMessages(id).onSuccess { if (sessionId == id) messages = it }
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

    /**
     * 文件名清洗：空白折成下划线、去掉 URL 敏感字符，其余（含中文）原样保留。
     * 3.4.43（N11）：实现搬到文件末尾的 `forgeSanitizeFilename`，暂存路径（ForgeChatPane.copyToStage）
     * 与最终落盘共用同一套标准——以前暂存用 SAF 原文拼路径、落盘才清洗，同源两套标准。
     */
    private fun sanitizeFilename(raw: String): String = forgeSanitizeFilename(raw)

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
            // 还没启动完就要求开新对话：start() 落空白页时会打 must-create 标记（B4）。
            return
        }
        // 在途发送必须先掐掉：它的 404 自愈/失败回写会把刚建的新对话又劫持回旧会话。
        invalidateInFlightSend()
        eventJob?.cancel()
        eventJob = null
        clearQueue()
        // 3.4.43（B4）：空白页 + must-create 标记。以前这里只把 sessionId 置 null，
        // 发送路径随后 ensureSession(directory) 会把本工作区最近的旧会话捡回来续聊——
        // 用户点了「新会话」，结果是把旧历史全量载入上下文（又慢又串）。现在第一条消息必新建。
        enterBlankPage("new-conversation")
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
        // 明确要打开某个会话时，之前记下的「下次落空白页」就作废：两者是互斥的导航意图，
        // 不清掉的话这次打开成功、下次进面板却又莫名回到空白页。
        pendingBlank = false
        // 打开已有会话是唯一允许「复用已有会话」的路径（B4），must-create 标记在这里必须失效；
        // 停止水位也一并解除，否则这条会话的新内容会被当成「已停止那轮」的在途更新丢掉（B0）。
        mustCreateSession = false
        stopFrozen = false
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
                // 死会话已清空：下一条消息必须新建会话，不许再「捡」一个（B4）。
                mustCreateSession = true
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
            turnStartMessageIds = emptySet()
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
                    // 发送在途时服务端一定还没开跑（状态是 idle）：这时对齐出来的 false
                    // 会把刚点发送的等待行直接收掉。有在途发送就不许用它覆盖 busy。
                    if (!sendInFlight) {
                        setBusy(value, "open-align")
                        if (value) markBusyObserved()
                        // 3.4.43（N2）：对齐出「闲」时，队列里的存量必须立刻续发——
                        // 以前 drainQueue 只在 finishIdle 里调，切走/重进/断线对齐期间结束的
                        // 那一轮没人续发，排队的消息就永久滞留。
                        else drainQueue()
                    }
                }
                return@launch
            }
            delay(BUSY_ALIGN_RETRY_DELAY_MS)
            if (sessionId != id || sendInFlight) return@launch
            api.sessionBusy(id).onSuccess {
                if (sessionId == id && !sendInFlight) {
                    setBusy(it, "open-align-retry")
                    if (it) markBusyObserved() else drainQueue()
                }
            }
        }
    }

    /** 回答提问卡：答完 opencode 会把这一轮继续跑下去；失败保留卡片让用户重试。 */
    fun answerQuestion(answers: List<List<String>>) {
        val pending = pendingQuestion ?: return
        // N10：在途期间重复提交直接丢弃，不重复打服务端、也不弹假的「回答失败」。
        if (questionAnswerInFlight) return
        questionAnswerInFlight = true
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
            questionAnswerInFlight = false
        }
    }

    /** 跳过提问卡：走 reject，模型收到拒绝后自己决定下一步。 */
    fun dismissQuestion() {
        val pending = pendingQuestion ?: return
        if (questionAnswerInFlight) return
        questionAnswerInFlight = true
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
            questionAnswerInFlight = false
        }
    }

    /** 向服务端对齐当前会话挂起中的 question（事件可能在断线/切会话期间错过）。 */
    private suspend fun refreshPendingQuestion(id: String) {
        val probe = api.listPendingQuestions()
        // 查询失败不等于「没有提问」：照旧保留手上的卡，别把可答的卡清掉、整轮没人能回答。
        if (probe.isFailure) return
        val fetched = probe.getOrNull()?.firstOrNull { it.sessionId == id }
        if (sessionId != id) return
        if (fetched != null) {
            pendingQuestion = fetched
            return
        }
        // 列表里没有本会话的卡，但手上正好有一张：它可能是这次请求发出之后才到的 question.asked
        // （响应早于它生成）。隔一拍再确认一次，仍然没有才清——直接清会让用户丢掉这道题。
        val current = pendingQuestion ?: run {
            // 手上也没有卡：确认页面已经是「无卡」状态即可。
            pendingQuestion = null
            return
        }
        if (current.sessionId != id) return
        delay(PENDING_QUESTION_RECHECK_MS)
        if (sessionId != id) return
        val again = api.listPendingQuestions().getOrNull()?.firstOrNull { it.sessionId == id }
        if (again != null) {
            pendingQuestion = again
        } else if (pendingQuestion?.id == current.id) {
            pendingQuestion = null
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
            // 改名成功才需要刷新列表：失败时再刷一次只会多打一次全量会话请求（每轮 2-3 次）。
            if (api.renameSession(id, firstUserText.take(30)).isSuccess) {
                onSessionsChanged(id)
            }
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
        // 3.4.43（B0）：按下即定格。服务端已发出、在途的 part 更新在 abort 生效前还会继续到达，
        // 以前只按 sessionId 匹配就照单渲染，用户看到「按了终止还往外冒一会儿」。
        // 置停止水位后，属于这一轮的在途 part/message 更新一律丢弃（见 handle）。
        stopFrozen = true
        idleDuringQuestion = false
        val id = sessionId ?: return
        // abort 请求照常立即发：服务端要走到边界才能真停，那是它的事；App 侧已经定格。
        scope.launch { api.abort(id) }
    }

    fun shutdown() {
        eventJob?.cancel()
        startJob?.cancel()
        openJob?.cancel()
        idleConfirmJob?.cancel()
        noticeJob?.cancel()
        // 发送协程也要掐：面板销毁（子页往返、切到别的 tab）后它还在跑，回写会打到
        // 已经不在屏幕上的 state，重新进面板时那次发送的收尾还会把新页面的 busy 改掉。
        sendJob?.cancel()
        sendJob = null
        sendInFlight = false
        sendGeneration++
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
        loaded.onSuccess {
            if (sessionId == id) {
                messages = it
                // 全量对齐拿到的是服务端权威状态（可能已包含被停止那一轮的部分产出）：
                // 这是一次性快照，不是「在途更新」，所以停止水位在这里解除（B0）。
                stopFrozen = false
                turnStartMessageIds = emptySet()
            }
        }
        api.sessionBusy(id).onSuccess {
            // 发送在途时不许对齐把 busy 冲掉：消息刚提交、服务端还没开跑的瞬间状态是 idle，
            // 照单全收会让等待行消失、还能重复发送，stop 也会停错对象。
            if (sessionId == id && !sendInFlight) {
                setBusy(it, "reconnect-align")
                if (it) markBusyObserved() else drainQueue() // N2：对齐为闲就续发存量
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
        val now = SystemClock.elapsedRealtime()
        lastActivityAtMs = now
        if (!busy) {
            // 一轮刚收尾（idle 已确认）之后紧跟着到的 part 是同一轮的收尾尾巴（合并后的
            // 快照、迟到的 delta）。把它当新一轮会在界面上闪一下「正在思考」，还会再挂一次
            // 结束确认——那一拍里服务端确实闲，于是又走一遍 drainQueue。宁可只刷新活动时刻。
            if (!sendInFlight && now - idleConfirmedAtMs < LATE_PART_GRACE_MS) return
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
            // 3.4.44（C2）：Idle 事件到达后的**第一探不等待**。
            // 用户口径：倒计时必须跟着模型输出完毕消失，不许输出完了还挂着转一两秒。
            // 以前第一拍先 delay(IDLE_CONFIRM_DELAY_MS=800) 才去问服务端，服务端那一拍
            // 还报忙（opencode 收尾慢半拍很常见）就再等 2000ms 下一拍，正常一轮结束后
            // 倒计时还要多挂 0.8–2.8 秒。现在第一探立即查一次：明确不忙就当场收尾
            // （只剩一次 localhost 往返）；报忙 / 查询未知才进入下面的「静默期 + 复查」慢路径，
            // 那 800/2000 的节奏从此只用于这种异常情况。
            var probeImmediately = true
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
                if (probeImmediately) {
                    probeImmediately = false
                    // 首探：不等待，立即问一次服务端（一次 localhost 往返）。
                    val immediateBusy = api.sessionBusy(session).getOrNull()
                    // 查询在途期间状态可能变化：收尾前把守卫再查一遍（与慢路径同源，不削弱防误收）。
                    if (sessionId != session) return@launch
                    if (pendingQuestion != null || sendInFlight) continue
                    if (lastActivityAtMs != activityAtWaitStart) {
                        // 查询期间又来了新内容（末段 part 还在流）：这一轮还没完，撤销收尾，
                        // 落到下面的静默期复查。
                        continue
                    }
                    if (immediateBusy == false) {
                        // 服务端明确不忙：立即收尾，倒计时随 idle 事件当场熄灭。
                        finishIdle(session)
                        return@launch
                    }
                    if (immediateBusy == null) {
                        // 未知 ≠ 不忙：计入未知次数，攒够上限仍按结束处理（与慢路径同一口径）。
                        unknown++
                        if (unknown >= IDLE_UNKNOWN_MAX) {
                            AndroidAgentLogger.warn(
                                "Forge idle confirmed after $unknown unknown probes session=$session",
                            )
                            finishIdle(session)
                            return@launch
                        }
                    } else if (rechecks == 0) {
                        AndroidAgentLogger.warn("Forge idle deferred: server still busy session=$session")
                    }
                    // 报忙或未知：转入静默期复查。rechecks 保持 0，首次静默期仍是 800ms。
                    continue
                }
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
        // 记下收尾时刻：紧接着到的 part 属于同一轮，不再复活 busy（见 onSessionActivity）。
        idleConfirmedAtMs = SystemClock.elapsedRealtime()
        // 计时起点归零：下一轮从用户点发送那刻重新起算，否则等待行会显示上一轮的总时长。
        turnStartedAtMs = 0L
        // 一轮结束时 opencode 可能刚给会话生成了标题，通知上层刷新列表。
        onSessionsChanged(session)
        maybeSetFallbackTitle(session)
        drainQueue()
    }

    private suspend fun handle(session: String, event: ForgeEvent) {
        when (event) {
            is ForgeEvent.PartUpdated -> {
                // 3.4.43（B0）：停止水位内到达的 part 属于被终止那一轮，一律丢弃不渲染。
                if (event.sessionId == session && !stopFrozen) {
                    onSessionActivity()
                    upsertPart(event.part)
                }
            }

            is ForgeEvent.PartDelta -> {
                if (event.sessionId == session && !stopFrozen) {
                    onSessionActivity()
                    appendDelta(event)
                }
            }

            is ForgeEvent.MessageUpdated -> {
                if (event.sessionId == session && !stopFrozen) {
                    onSessionActivity()
                    ensureMessage(event.messageId, event.role)
                }
            }

            // 空 sessionID（协议没带）按当前会话处理：以前直接 Ignored，busy 就永远解不开了。
            is ForgeEvent.Idle -> if (event.sessionId.isBlank() || event.sessionId == session) {
                if (stopFrozen) {
                    // 3.4.43（B0）：被终止那一轮终于走到边界并报闲。界面早已定格（stop() 里
                    // busy=false、汇总头定在「已停止」），这里只记收尾时刻、不做任何渲染动作；
                    // 停止水位一直保留到新一轮发送 / 重新打开会话 / 重新进面板全量对齐为止。
                    idleConfirmedAtMs = SystemClock.elapsedRealtime()
                    turnStartedAtMs = 0L
                } else if (pendingQuestion != null) {
                    // 提问还挂着 = 这一轮在等回答，idle 只是让出，不许收尾。
                    // 记下来：回答成功后要主动重新挂一次确认，否则不会再有第二个 idle 到来。
                    idleDuringQuestion = true
                    AndroidAgentLogger.warn("Forge idle deferred: question pending session=$session")
                } else {
                    confirmIdle(session)
                }
            }

            is ForgeEvent.Failed -> if (event.sessionId.isBlank() || event.sessionId == session) {
                // 3.4.43（N4）：空 sessionId 的失败以前无条件清队列、写错误——别的会话失败也会
                // 误杀当前会话（Idle 分支有复核，这里以前没有，不对称）。先复核一次：当前会话
                // 若仍报忙，这条失败不属于它，直接忽略。
                if (event.sessionId.isBlank() && api.sessionBusy(session).getOrNull() == true) {
                    AndroidAgentLogger.warn("Forge failed event ignored: session still busy session=$session")
                    return
                }
                idleConfirmJob?.cancel()
                setBusy(false, "failed")
                // 3.4.43（N3）：提问挂起期间本轮失败，卡片不能留成僵尸卡——清掉卡与「提问期
                // idle」标记，与 stop() 对齐；否则卡片还挂着、提交/跳过永远失败。
                pendingQuestion = null
                idleDuringQuestion = false
                // 真失败就是终局，停止水位随之解除；手动终止（Aborted）保持水位，
                // 否则 abort 之后迟到的那批 part 又会冒到屏幕上（B0）。
                if (!event.isAbort) stopFrozen = false
                // 失败这一轮结束了：排队消息不会再有 confirmIdle 来续发，清掉并告知条数。
                clearQueueWithNotice()
                // 手动停止触发的 Aborted 不是故障，不算失败。
                if (!event.isAbort) {
                    // 3.4.43（N5）：失败后本地与服务端可能已经不一致（消息服务端其实收下了、
                    // 或带着错误块落了库），补一次全量对齐再定位错误行。
                    if (sessionId == session) {
                        api.loadMessages(session).onSuccess { if (sessionId == session) messages = it }
                    }
                    val text = event.message?.takeIf { it.isNotBlank() }
                    if (text != null) {
                        // 错误行只锚在本轮产生的消息上；本轮一条都没有就独立成行（anchor=null），
                        // 绝不挂到上一轮末条（那会让错误看起来属于上一轮，N5）。
                        turnError = ForgeTurnError(
                            message = text,
                            statusCode = event.statusCode,
                            anchorMessageId = messages.lastOrNull { it.id !in turnStartMessageIds }?.id,
                        )
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
                if (current != null && (event.sessionId == null || event.sessionId == session)) {
                    if (event.requestId.isNotBlank()) {
                        if (event.requestId == current.id) pendingQuestion = null
                    } else {
                        // 兜底：服务端没带请求 id（字段改名/旧版本）时，会话对得上就认这次清除。
                        // 会话内同时只可能有一张卡（pendingQuestion 是单值），误撤范围限于本会话。
                        pendingQuestion = null
                    }
                }
            }

            is ForgeEvent.Ignored -> Unit
        }
    }

    private fun ensureMessage(messageId: String, role: ForgeRole) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) {
            messages = messages + ForgeChatMessage(id = messageId, role = role, parts = emptyList())
            return
        }
        // part 事件可能先于 message.updated 到达，占位消息只能先按 Assistant 建；真正的角色
        // 到了必须纠正回来，否则用户消息被当成助手消息渲染（没有用户气泡、错误条也挂错行）。
        val existing = messages[index]
        if (existing.role != role) {
            val updated = messages.toMutableList()
            updated[index] = existing.copy(role = role)
            messages = updated
        }
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
     * 未结束的文本/思考段只接受不比现有短的全文。
     * 结束快照一般带全文，但服务端也会发「只带已确认前缀」的结束快照：这种情况下
     * （更短且是现有文本的前缀）采用现有文本，只把 ended 标记收下——否则回复尾部会被截断。
     */
    private fun mergePartText(existing: ForgePart, incoming: ForgePart): ForgePart = when {
        existing is ForgePart.Text && incoming is ForgePart.Text -> when {
            incoming.text.length >= existing.text.length -> incoming
            incoming.ended && existing.text.startsWith(incoming.text) -> incoming.copy(text = existing.text)
            else -> incoming.copy(text = existing.text)
        }

        existing is ForgePart.Reasoning && incoming is ForgePart.Reasoning -> when {
            incoming.text.length >= existing.text.length -> incoming
            incoming.ended && existing.text.startsWith(incoming.text) -> incoming.copy(text = existing.text)
            else -> incoming.copy(text = existing.text)
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

        /** 收尾后多久内到的 part 视为同一轮的尾巴（不复活 busy、不再挂结束确认）。 */
        const val LATE_PART_GRACE_MS = 1_500L

        /** refreshPendingQuestion 发现「列表空但手上有卡」时的复核间隔。 */
        const val PENDING_QUESTION_RECHECK_MS = 400L

        /** 错误提示的自动消失时长，别再永久挂着跨操作残留。 */
        const val NOTICE_TTL_MS = 6_000L

        /** 文件夹附件复制进工作区的上限：文件数与总字节，超了整条判失败。 */
        const val MAX_FOLDER_ATTACHMENT_FILES = 300
        const val MAX_FOLDER_ATTACHMENT_BYTES = 64L * 1024 * 1024
    }
}

/**
 * 附件名清洗（3.4.43 N11）：空白折成下划线、去掉路径分隔符与 URL 敏感字符，其余（含中文）原样保留。
 * 附件名来自 SAF 的 displayName（外部来源，不可信），直接拼进路径时 `../` 会写到目录之外，
 * 所以暂存路径和最终落盘必须走同一套标准。ForgeChatState 与 ForgeChatPane 共用这一个实现。
 */
internal fun forgeSanitizeFilename(raw: String): String {
    val cleaned = raw.trim()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[/\\\\]+"), "_")
        .replace("..", "_")
        .replace(Regex("[?#%<>:\\\"|*]"), "")
    return cleaned.ifBlank { "file" }
}