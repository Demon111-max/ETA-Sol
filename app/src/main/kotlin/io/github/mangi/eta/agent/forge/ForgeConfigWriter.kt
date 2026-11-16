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
                    // 自定义请求头：需要请求头鉴权/路由的中转站，少了它 opencode 一直 401。
                    val headers = settings.customHeaders
                        .filter { it.name.isNotBlank() && it.value.isNotBlank() }
                    if (headers.isNotEmpty()) {
                        putJsonObject("headers") {
                            headers.forEach { header -> put(header.name.trim(), header.value) }
                        }
                    }
                }
                putJsonObject("models") {
                    putJsonObject(settings.modelId.trim()) {
                        put("name", settings.modelId.trim())
                        // 不写 limit：opencode 的 limit 要求 context 与 output 成对出现，
                        // 而 App 的模型设置只有上下文窗口、没有最大输出值；只写 context
                        // 会让整份配置被 opencode 判为无效、server 启动即退出（1.19.5 实测）。
                        // 上下文窗口目前只供 App 侧用量显示（ForgeSettings.contextWindow）。
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

    /**
     * 用 heredoc 落盘，避免把 Key 拼进会回显的命令行；写完立即收权限到 600。
     *
     * 两份文件都先写同目录 `.tmp` 再 `mv -f` 原子覆盖：写到一半被杀/超时只坏临时文件，
     * 正式文件要么还是上一版完整的、要么已是新的一版，不会留下截断的 JSON。
     * 覆盖前先探测旧文件能否解析（jq 优先；精简环境里没有 jq 时退化为首尾花括号判据），
     * 解析不了说明是上次写坏的残留：先 `mv` 成 `.bak` 再覆盖，让坏文件在下一次写入时自愈。
     */
    internal fun writeCommand(configJson: String, authJson: String): String = buildString {
        append("set -e\n")
        append("mkdir -p \"${'$'}HOME/.config/opencode\" \"${'$'}HOME/.local/share/opencode\"\n")
        append("eta_forge_config=\"${'$'}HOME/.config/opencode/opencode.json\"\n")
        append("eta_forge_auth=\"${'$'}HOME/.local/share/opencode/auth.json\"\n")
        append("eta_forge_backup_if_corrupt() {\n")
        append("  [ -f \"${'$'}1\" ] || return 0\n")
        append("  if command -v jq >/dev/null 2>&1; then\n")
        append("    jq -e . \"${'$'}1\" >/dev/null 2>&1 && return 0\n")
        // 没有 jq 的精简环境：退化为「首字符是 { 且末尾（去空白后）是 }」，
        // 截断的 JSON 一定丢掉收尾括号；文件本身是一行 JSON，不能按行数判。
        append("  elif [ \"${'$'}(head -c 1 \"${'$'}1\")\" = \"{\" ] &&\n")
        append("       [ \"${'$'}(tr -d ' \\t\\r\\n' < \"${'$'}1\" | tail -c 1)\" = \"}\" ]; then\n")
        append("    return 0\n")
        append("  fi\n")
        append("  mv -f \"${'$'}1\" \"${'$'}1.bak\"\n")
        append("}\n")
        append("eta_forge_backup_if_corrupt \"${'$'}eta_forge_config\"\n")
        append("cat > \"${'$'}eta_forge_config.tmp\" <<'ETA_FORGE_CONFIG_EOF'\n")
        append(configJson).append('\n')
        append("ETA_FORGE_CONFIG_EOF\n")
        append("chmod 600 \"${'$'}eta_forge_config.tmp\"\n")
        append("mv -f \"${'$'}eta_forge_config.tmp\" \"${'$'}eta_forge_config\"\n")
        append("eta_forge_backup_if_corrupt \"${'$'}eta_forge_auth\"\n")
        append("cat > \"${'$'}eta_forge_auth.tmp\" <<'ETA_FORGE_AUTH_EOF'\n")
        append(authJson).append('\n')
        append("ETA_FORGE_AUTH_EOF\n")
        append("chmod 600 \"${'$'}eta_forge_auth.tmp\"\n")
        append("mv -f \"${'$'}eta_forge_auth.tmp\" \"${'$'}eta_forge_auth\"\n")
    }
}
