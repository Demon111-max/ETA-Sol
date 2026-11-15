package io.github.mangi.eta.agent.forge

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal sealed interface ForgeConfigWriteResult {
    data object Written : ForgeConfigWriteResult

    data object EnvironmentNotReady : ForgeConfigWriteResult

    data class Failed(val code: Int, val output: String) : ForgeConfigWriteResult
}

/**
 * 把 Forge 配置写进 Linux 环境里的 opencode 约定位置：
 * `~/.config/opencode/opencode.json`（provider ID 由共用配置的服务商名生成）与
 * `~/.local/share/opencode/auth.json`（API Key）。
 * 两个文件都要求 0600；写入走既有的终端通道（proot/chroot 各按当前后端），不新造通道。
 */
internal object ForgeConfigWriter {
    const val CONFIG_PATH = "~/.config/opencode/opencode.json"
    const val AUTH_PATH = "~/.local/share/opencode/auth.json"
    const val PROVIDER_ID = "relay"
    private const val WRITE_TIMEOUT_SECONDS = 60L

    internal fun opencodeConfigJson(settings: ForgeSettings): JsonObject = buildJsonObject {
        put("\$schema", "https://opencode.ai/config.json")
        putJsonObject("provider") {
            putJsonObject(settings.providerId) {
                // 按协议选包：Responses 走 @ai-sdk/openai，Chat Completions 走兼容包。
                // 写死兼容包会让 GPT 这类 Responses 渠道在 opencode 里发不出/挂死。
                put(
                    "npm",
                    if (settings.endpointMode == OpenAiEndpointMode.RESPONSES) {
                        "@ai-sdk/openai"
                    } else {
                        "@ai-sdk/openai-compatible"
                    },
                )
                put("name", settings.providerName.ifBlank { settings.providerId })
                putJsonObject("options") {
                    put("baseURL", settings.baseUrl.trim())
                }
                putJsonObject("models") {
                    putJsonObject(settings.modelId.trim()) {
                        put("name", settings.modelId.trim())
                        // 思考档位：variant 名与 Eta 的 ReasoningEffort wireValue 对齐，
                        // Forge 发送消息时按档位名选 variant（见 ForgeChatState）。
                        putJsonObject("variants") {
                            putJsonObject("minimal") { put("reasoningEffort", "minimal") }
                            putJsonObject("low") { put("reasoningEffort", "low") }
                            putJsonObject("medium") { put("reasoningEffort", "medium") }
                            putJsonObject("high") { put("reasoningEffort", "high") }
                            putJsonObject("xhigh") { put("reasoningEffort", "xhigh") }
                            putJsonObject("max") { put("reasoningEffort", "xhigh") }
                        }
                    }
                }
            }
        }
        put("model", "${settings.providerId}/${settings.modelId.trim()}")
        putJsonObject("permission") {
            put("edit", "allow")
            put("bash", "allow")
        }
    }

    internal fun authJson(settings: ForgeSettings, credentials: ForgeCredentials): JsonObject =
        buildJsonObject {
            putJsonObject(settings.providerId) {
                put("type", "api")
                put("key", credentials.apiKey.trim())
            }
        }

    suspend fun write(
        context: Context,
        settings: ForgeSettings,
        credentials: ForgeCredentials,
    ): ForgeConfigWriteResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val distribution = LinuxEnvironmentSettingsRepository.current(appContext)
        val environment = distribution.terminalEnvironment
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsPath)) {
            return@withContext ForgeConfigWriteResult.EnvironmentNotReady
        }
        val command = writeCommand(
            configJson = opencodeConfigJson(settings).toString(),
            authJson = authJson(settings, credentials).toString(),
        )
        val supervisor = ShellProcessSupervisor()
        try {
            val result = runOneShotShell(
                processSupervisor = supervisor,
                identity = TerminalRuntime.defaultIdentity(environment, rootfsPath),
                command = command,
                timeoutSeconds = WRITE_TIMEOUT_SECONDS,
                environment = environment,
                linuxRootfsPath = rootfsPath,
            )
            if (result.exitCode == 0) {
                ForgeConfigWriteResult.Written
            } else {
                ForgeConfigWriteResult.Failed(
                    code = result.exitCode,
                    // shell 的报错走 stderr，只取 stdout 会把真因丢掉、界面只剩一个退出码。
                    output = (result.output.decodeToString() + result.stderr.decodeToString())
                        .trim().take(400),
                )
            }
        } finally {
            supervisor.beginClosing()
        }
    }

    /** 用 heredoc 落盘，避免把 Key 拼进会回显的命令行；写完立即收权限到 600。 */
    internal fun writeCommand(configJson: String, authJson: String): String = buildString {
        append("set -e\n")
        append("mkdir -p \"${'$'}HOME/.config/opencode\" \"${'$'}HOME/.local/share/opencode\"\n")
        append("cat > \"${'$'}HOME/.config/opencode/opencode.json\" <<'ETA_FORGE_CONFIG_EOF'\n")
        append(configJson).append('\n')
        append("ETA_FORGE_CONFIG_EOF\n")
        append("cat > \"${'$'}HOME/.local/share/opencode/auth.json\" <<'ETA_FORGE_AUTH_EOF'\n")
        append(authJson).append('\n')
        append("ETA_FORGE_AUTH_EOF\n")
        append(
            "chmod 600 \"${'$'}HOME/.config/opencode/opencode.json\" " +
                "\"${'$'}HOME/.local/share/opencode/auth.json\"",
        )
    }
}
