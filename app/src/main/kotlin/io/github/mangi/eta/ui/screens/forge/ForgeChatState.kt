package io.github.mangi.eta.ui.screens.forge

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.forge.ForgeApiClient
import io.github.mangi.eta.agent.forge.ForgeChatMessage
import io.github.mangi.eta.agent.forge.ForgeEvent
import io.github.mangi.eta.agent.forge.ForgePart
import io.github.mangi.eta.agent.forge.ForgeRole
import io.github.mangi.eta.agent.forge.ForgeServerManager
import io.github.mangi.eta.agent.forge.ForgeServerStartResult
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.repository.ForgeSettingsRepository
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
) {
    var startup by mutableStateOf<ForgeStartup>(ForgeStartup.Connecting)
    var messages by mutableStateOf<List<ForgeChatMessage>>(emptyList())
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    private var sessionId: String? = null
    private var modelId: String = ForgeSettings.DEFAULT_MODEL_ID
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
            modelId = ForgeSettingsRepository.settings().modelId
                .ifBlank { ForgeSettings.DEFAULT_MODEL_ID }
            val id = api.ensureSession().getOrElse { error ->
                startup = ForgeStartup.Failed(
                    context.getString(R.string.forge_server_failed, error.message.orEmpty()),
                )
                return@launch
            }
            sessionId = id
            messages = api.loadMessages(id).getOrDefault(emptyList())
            startup = ForgeStartup.Ready
            subscribe(id)
        }
    }

    fun retry() {
        startJob?.cancel()
        startJob = null
        start()
    }

    fun send(text: String) {
        val id = sessionId ?: return
        if (text.isBlank() || busy) return
        busy = true
        notice = null
        scope.launch {
            api.sendMessage(id, text, PROVIDER_ID, modelId).onFailure { error ->
                busy = false
                notice = context.getString(R.string.forge_send_failed, error.message.orEmpty())
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
                    api.loadMessages(id).onSuccess { messages = it }
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                api.loadMessages(id).onSuccess { messages = it }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun handle(session: String, event: ForgeEvent) {
        when (event) {
            is ForgeEvent.PartUpdated -> {
                if (event.sessionId == session) upsertPart(event.part)
            }

            is ForgeEvent.MessageUpdated -> {
                if (event.sessionId == session) ensureMessage(event.messageId, event.role)
            }

            is ForgeEvent.Idle -> if (event.sessionId == session) busy = false

            is ForgeEvent.Failed -> if (event.sessionId == session) {
                busy = false
                notice = event.message?.takeIf { it.isNotBlank() }?.let {
                    context.getString(R.string.forge_run_failed, it)
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
            if (partIndex >= 0) parts[partIndex] = part else parts.add(part)
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

    private companion object {
        const val PROVIDER_ID = "relay"
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
