package io.github.mangi.eta.ui.screens.forge

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.forge.ForgeApiClient
import io.github.mangi.eta.agent.forge.ForgeChatMessage
import io.github.mangi.eta.agent.forge.ForgeConfigWriteResult
import io.github.mangi.eta.agent.forge.ForgeConfigWriter
import io.github.mangi.eta.agent.forge.ForgeEvent
import io.github.mangi.eta.agent.forge.ForgePart
import io.github.mangi.eta.agent.forge.ForgeQuestionRequest
import io.github.mangi.eta.agent.forge.ForgeRole
import io.github.mangi.eta.agent.forge.ForgeServerManager
import io.github.mangi.eta.agent.forge.ForgeServerStartResult
import io.github.mangi.eta.agent.forge.hasHttpCode
import io.github.mangi.eta.agent.forge.ForgeSharedConfig
import io.github.mangi.eta.agent.forge.resolveForgeSharedConfig
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Forge 对话的准备阶段。 */
internal sealed interface ForgeStartup {
    data object Connecting : ForgeStartup

    data object Ready : ForgeStartup

    data class Failed(val message: String) : ForgeStartup
}

/** 一次共用配置对齐的结果：启动与发送共用同一套处理，只在失败呈现上不同。 */
private sealed interface ConfigApply {
    data object Ready : ConfigApply

    data object NotConfigured : ConfigApply

    data class Failed(val output: String) : ConfigApply
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

    /** 还没启动完就被要求打开的会话（从侧边栏点进来），start() 拿到服务后优先打开它。 */
    private var pendingOpenSessionId: String? = null

    /** 还没启动完就被要求开新对话（空白页），start() 就绪后不落到最近会话、直接空白。 */
    private var pendingBlank = false
    private var providerId: String = ForgeConfigWriter.PROVIDER_ID
    private var modelId: String = ""
    private var eventJob: Job? = null
    private var startJob: Job? = null
    private val server = ForgeServerManager.default(context)
    private val api = ForgeApiClient()

    /** 进入 Forge 时调用：启动 server → 拿 session → 拉全量消息 → 订阅事件。 */
    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            startup = ForgeStartup.Connecting
            notice = null
            // 配置与 Assist 共用同一套服务商设置：解析并应用（变了才重写配置并重启 server）。
            when (val applied = applySharedConfig()) {
                ConfigApply.NotConfigured -> {
                    startup = ForgeStartup.Failed(context.getString(R.string.forge_gate_config_missing))
                    return@launch
                }

                is ConfigApply.Failed -> {
                    startup = ForgeStartup.Failed(
                        context.getString(R.string.forge_server_failed, applied.output),
                    )
                    return@launch
                }

                ConfigApply.Ready -> Unit
            }
            val environment = server.environment()
            when (val result = server.ensureRunning(environment)) {
                is ForgeServerStartResult.Failed -> {
                    startup = ForgeStartup.Failed(
                        context.getString(R.string.forge_server_failed, result.code),
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
                busy = false
                pendingQuestion = null
                startup = ForgeStartup.Ready
                onSessionsChanged(null)
                return@launch
            }
            val id = pending ?: api.ensureSession().getOrElse { error ->
                startup = ForgeStartup.Failed(
                    context.getString(R.string.forge_server_failed, error.message.orEmpty()),
                )
                return@launch
            }
            val loaded = api.loadMessages(id)
            if (loaded.isFailure && loaded.exceptionOrNull().hasHttpCode(404)) {
                // 刚拿到的会话立刻就 404（server 在启动间隙被换过）：退回空白页，
                // 会话等第一条消息发送时再建。
                sessionId = null
                messages = emptyList()
                busy = false
                startup = ForgeStartup.Ready
                onSessionsChanged(null)
                return@launch
            }
            sessionId = id
            messages = loaded.getOrDefault(emptyList())
            api.sessionBusy(id).onSuccess { busy = it }
            startup = ForgeStartup.Ready
            subscribe(id)
            refreshPendingQuestion(id)
            onSessionsChanged(id)
        }
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
    private suspend fun applySharedConfig(): ConfigApply {
        val shared = resolveForgeSharedConfig()
        if (shared !is ForgeSharedConfig.Ready) return ConfigApply.NotConfigured
        modelId = shared.settings.modelId
        providerId = shared.settings.providerId
        val fingerprint = shared.settings.providerId + "\n" + shared.settings.baseUrl +
            "\n" + shared.settings.modelId + "\n" + shared.credentials.apiKey
        if (fingerprint != appliedConfigFingerprint) {
            when (val written = ForgeConfigWriter.write(context, shared.settings, shared.credentials)) {
                ForgeConfigWriteResult.Written -> {
                    appliedConfigFingerprint = fingerprint
                    server.restartIfRunning(server.environment())
                }

                ForgeConfigWriteResult.EnvironmentNotReady -> Unit

                is ForgeConfigWriteResult.Failed -> return ConfigApply.Failed(written.output)
            }
        }
        return ConfigApply.Ready
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        notice = null
        scope.launch {
            // 发送前先对齐配置：换过 Key/服务商/模型时，这一步会把新配置写好并重启 server，
            // 再往下建会话/发消息就都走新配置。server 重启后当前会话的事件订阅会断线重连对齐。
            when (val applied = applySharedConfig()) {
                ConfigApply.NotConfigured -> {
                    busy = false
                    notice = context.getString(R.string.forge_gate_config_missing)
                    return@launch
                }

                is ConfigApply.Failed -> {
                    busy = false
                    notice = context.getString(R.string.forge_send_failed, applied.output)
                    return@launch
                }

                ConfigApply.Ready -> Unit
            }
            var id = sessionId ?: api.createSession().getOrElse { error ->
                busy = false
                notice = context.getString(R.string.forge_send_failed, error.message.orEmpty())
                return@launch
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
            )
            if (firstTry.isFailure && firstTry.exceptionOrNull().hasHttpCode(404)) {
                // 手里的会话在服务端已不存在（server 被重启换掉、或列表过期点进了已删的会话）：
                // 新建一个会话把这条消息重发一次，不要把 404 直接甩给用户。
                val fresh = api.createSession().getOrElse { error ->
                    busy = false
                    notice = context.getString(R.string.forge_send_failed, error.message.orEmpty())
                    return@launch
                }
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
                ).onFailure { error ->
                    busy = false
                    notice = context.getString(R.string.forge_send_failed, error.message.orEmpty())
                }
                return@launch
            }
            firstTry.onFailure { error ->
                busy = false
                notice = context.getString(R.string.forge_send_failed, error.message.orEmpty())
            }
        }
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
        eventJob?.cancel()
        sessionId = null
        messages = emptyList()
        busy = false
        notice = null
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
        scope.launch {
            val loaded = api.loadMessages(id)
            if (loaded.isFailure && loaded.exceptionOrNull().hasHttpCode(404)) {
                // 侧边栏列表可能过期：这个会话在服务端已经没了。退回空白页并刷新列表，
                // 别把死会话留在手里——接下来发什么都是 404。
                sessionId = null
                pendingQuestion = null
                messages = emptyList()
                busy = false
                notice = context.getString(R.string.forge_session_gone)
                onSessionsChanged(null)
                return@launch
            }
            sessionId = id
            pendingQuestion = null
            messages = loaded.getOrDefault(emptyList())
            busy = false
            api.sessionBusy(id).onSuccess { busy = it }
            notice = null
            subscribe(id)
            refreshPendingQuestion(id)
            onSessionsChanged(id)
        }
    }

    /** 回答提问卡：答完 opencode 会把这一轮继续跑下去；失败保留卡片让用户重试。 */
    fun answerQuestion(answers: List<List<String>>) {
        val pending = pendingQuestion ?: return
        scope.launch {
            api.replyQuestion(pending, answers)
                .onSuccess {
                    if (pendingQuestion?.id == pending.id) pendingQuestion = null
                }
                .onFailure { error ->
                    notice = context.getString(R.string.forge_question_failed, error.message.orEmpty())
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
                }
                .onFailure { error ->
                    notice = context.getString(R.string.forge_question_failed, error.message.orEmpty())
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

    fun stop() {
        val id = sessionId ?: return
        scope.launch {
            api.abort(id)
            busy = false
        }
    }

    fun shutdown() {
        eventJob?.cancel()
        startJob?.cancel()
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
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                alignAfterReconnect(id)
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
                busy = false
                pendingQuestion = null
                onSessionsChanged(null)
            }
            return
        }
        loaded.onSuccess { if (sessionId == id) messages = it }
        api.sessionBusy(id).onSuccess { if (sessionId == id) busy = it }
        refreshPendingQuestion(id)
    }

    private fun handle(session: String, event: ForgeEvent) {
        when (event) {
            is ForgeEvent.PartUpdated -> {
                if (event.sessionId == session) upsertPart(event.part)
            }

            is ForgeEvent.PartDelta -> {
                if (event.sessionId == session) appendDelta(event)
            }

            is ForgeEvent.MessageUpdated -> {
                if (event.sessionId == session) ensureMessage(event.messageId, event.role)
            }

            is ForgeEvent.Idle -> if (event.sessionId == session) {
                busy = false
                // 一轮结束时 opencode 可能刚给会话生成了标题，通知上层刷新列表。
                onSessionsChanged(session)
            }

            is ForgeEvent.Failed -> if (event.sessionId == session) {
                busy = false
                // 手动停止触发的 Aborted 不是故障，不弹错误提示。
                if (!event.isAbort) {
                    notice = event.message?.takeIf { it.isNotBlank() }?.let {
                        context.getString(R.string.forge_run_failed, it)
                    }
                }
            }

            is ForgeEvent.QuestionAsked -> {
                // 只接当前打开会话的提问；别的会话的提问等切回去时靠列表对齐捡回来。
                if (event.request.sessionId == session) pendingQuestion = event.request
            }

            is ForgeEvent.QuestionCleared -> {
                val current = pendingQuestion
                if (current != null && (event.requestId.isBlank() || event.requestId == current.id)) {
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

        /** 上次成功写入 Linux 环境的共用配置指纹；没变就不重写、不重启 server。 */
        var appliedConfigFingerprint: String? = null
    }
}
