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
import io.github.mangi.eta.agent.forge.ForgeRole
import io.github.mangi.eta.agent.forge.ForgeServerManager
import io.github.mangi.eta.agent.forge.ForgeServerStartResult
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
            // 配置与 Assist 共用同一套服务商设置：进 Forge 时解析一次，变了才重写并重启 server。
            val shared = resolveForgeSharedConfig()
            if (shared !is ForgeSharedConfig.Ready) {
                startup = ForgeStartup.Failed(context.getString(R.string.forge_gate_config_missing))
                return@launch
            }
            modelId = shared.settings.modelId
            providerId = shared.settings.providerId
            val environment = server.environment()
            val fingerprint = shared.settings.providerId + "\n" + shared.settings.baseUrl +
                "\n" + shared.settings.modelId + "\n" + shared.credentials.apiKey
            if (fingerprint != appliedConfigFingerprint) {
                when (val written = ForgeConfigWriter.write(context, shared.settings, shared.credentials)) {
                    ForgeConfigWriteResult.Written -> {
                        appliedConfigFingerprint = fingerprint
                        server.restartIfRunning(environment)
                    }

                    ForgeConfigWriteResult.EnvironmentNotReady -> Unit

                    is ForgeConfigWriteResult.Failed -> {
                        startup = ForgeStartup.Failed(
                            context.getString(R.string.forge_server_failed, written.output),
                        )
                        return@launch
                    }
                }
            }
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
            sessionId = id
            messages = api.loadMessages(id).getOrDefault(emptyList())
            api.sessionBusy(id).onSuccess { busy = it }
            startup = ForgeStartup.Ready
            subscribe(id)
            onSessionsChanged(id)
        }
    }

    fun retry() {
        startJob?.cancel()
        startJob = null
        start()
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        notice = null
        scope.launch {
            // 空白新对话还没有会话：发送时才建，免得列表里堆空会话。
            val id = sessionId ?: api.createSession().getOrElse { error ->
                busy = false
                notice = context.getString(R.string.forge_send_failed, error.message.orEmpty())
                return@launch
            }
            if (sessionId == null) {
                sessionId = id
                subscribe(id)
                onSessionsChanged(id)
            }
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
            sessionId = id
            messages = api.loadMessages(id).getOrDefault(emptyList())
            busy = false
            api.sessionBusy(id).onSuccess { busy = it }
            notice = null
            subscribe(id)
            onSessionsChanged(id)
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
        api.loadMessages(id).onSuccess { if (sessionId == id) messages = it }
        api.sessionBusy(id).onSuccess { if (sessionId == id) busy = it }
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
