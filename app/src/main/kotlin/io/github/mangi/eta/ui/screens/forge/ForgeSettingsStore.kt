package io.github.mangi.eta.ui.screens.forge

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.forge.ForgeConfigWriteResult
import io.github.mangi.eta.agent.forge.ForgeConfigWriter
import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.repository.ForgeCredentialsUnavailable
import io.github.mangi.eta.data.repository.ForgeSettingsRepository
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ForgeSettingsValidationFailure(val userMessage: String) : IllegalArgumentException(userMessage)

internal class ForgeSettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit = {},
) {
    var settings by mutableStateOf(ForgeSettings())
        private set
    var credentials by mutableStateOf(ForgeCredentials())
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    init {
        scope.launch {
            try {
                settings = ForgeSettingsRepository.settings()
                try {
                    credentials = ForgeSettingsRepository.credentials(context)
                } catch (_: ForgeCredentialsUnavailable) {
                    message = "已保存的 API Key 无法解密，请重新填写后保存"
                    messageIsError = true
                }
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "读取 Forge 配置失败，请重试"
                messageIsError = true
            }
        }
    }

    fun edit(value: ForgeSettings) {
        settings = value
        message = null
    }

    fun editCredentials(value: ForgeCredentials) {
        credentials = value
        message = null
    }

    fun save() {
        if (!loaded || saving) return
        val config = settings.normalized()
        val secrets = credentials.normalized()
        saving = true
        scope.launch {
            try {
                validateForgeSettings(config, secrets)
                ForgeSettingsRepository.saveCredentials(context, secrets)
                ForgeSettingsRepository.save(config)
                settings = config
                credentials = secrets
                messageIsError = false
                message = when (val result = ForgeConfigWriter.write(context, config, secrets)) {
                    ForgeConfigWriteResult.Written -> {
                        onSaved()
                        "已保存，并写入 Linux 环境里的 opencode 配置"
                    }
                    ForgeConfigWriteResult.EnvironmentNotReady ->
                        "已保存；Linux 工具环境尚未安装，配置将在环境就绪后写入"
                    is ForgeConfigWriteResult.Failed -> {
                        messageIsError = true
                        "已保存，但写入 opencode 配置失败（退出码 ${result.code}）：${result.output}"
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = (error as? ForgeSettingsValidationFailure)?.userMessage ?: "请重试"
                message = "保存失败：$reason"
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }
}

internal fun validateForgeSettings(settings: ForgeSettings, credentials: ForgeCredentials) {
    val baseUrl = settings.baseUrl.trim()
    if (baseUrl.isEmpty()) throw ForgeSettingsValidationFailure("请填写中转站地址")
    val uri = runCatching { URI(baseUrl) }.getOrNull()
    if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        throw ForgeSettingsValidationFailure("中转站地址须为合法的 HTTP(S) 地址")
    }
    if (settings.modelId.isBlank()) throw ForgeSettingsValidationFailure("请填写模型 ID")
    if (credentials.apiKey.isBlank()) throw ForgeSettingsValidationFailure("请填写 API Key")
}

private fun ForgeSettings.normalized(): ForgeSettings = copy(
    baseUrl = baseUrl.trim(),
    modelId = modelId.trim().ifEmpty { ForgeSettings.DEFAULT_MODEL_ID },
)

private fun ForgeCredentials.normalized(): ForgeCredentials = copy(apiKey = apiKey.trim())
