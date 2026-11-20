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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    internal fun opencodeConfigJson(
        settings: ForgeSettings,
        reasoningDisabled: Boolean = false,
    ): JsonObject = buildJsonObject {
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
                        // 输入能力：opencode 对自定义模型默认只认文字，不声明 image 就会把
                        // 图片附件降级成「Called the Read tool…」合成文本、模型也自称不支持识图。
                        // 来源是共用配置的 supportsVision（设置页「图片」勾选），与 Assist 同一份。
                        put("attachment", settings.supportsVision)
                        putJsonObject("modalities") {
                            putJsonArray("input") {
                                add("text")
                                if (settings.supportsVision) add("image")
                            }
                            putJsonArray("output") { add("text") }
                        }
                        // 不写 limit：opencode 的 limit 要求 context 与 output 成对出现，
                        // 而 App 的模型设置只有上下文窗口、没有最大输出值；只写 context
                        // 会让整份配置被 opencode 判为无效、server 启动即退出（1.19.5 实测）。
                        // 上下文窗口目前只供 App 侧用量显示（ForgeSettings.contextWindow）。
                        // 思考档位：variant 名与 Eta 的 ReasoningEffort wireValue 对齐，
                        // Forge 发送消息时按档位名选 variant（见 ForgeChatState）。
                        // variant 的 reasoningEffort 也必须与 wireValue 一致：此前 "max"
                        // 写的是 "xhigh"，药丸显示 Max、实际深度只有 Extra High，
                        // 与 Assist 同档位发出的 "max" 不一致（3.4.49 审理）。
                        // 模型明确不支持思考时不写 variants：行为层本来就不发 variant，
                        // 配置里留着只会让人手改/排查时误以为可用（3.4.49 审理）。
                        if (!reasoningDisabled) {
                            putJsonObject("variants") {
                                putJsonObject("minimal") { put("reasoningEffort", "minimal") }
                                putJsonObject("low") { put("reasoningEffort", "low") }
                                putJsonObject("medium") { put("reasoningEffort", "medium") }
                                putJsonObject("high") { put("reasoningEffort", "high") }
                                putJsonObject("xhigh") { put("reasoningEffort", "xhigh") }
                                putJsonObject("max") { put("reasoningEffort", "max") }
                            }
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
        reasoningDisabled: Boolean = false,
    ): ForgeConfigWriteResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val distribution = LinuxEnvironmentSettingsRepository.current(appContext)
        val environment = distribution.terminalEnvironment
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsPath)) {
            return@withContext ForgeConfigWriteResult.EnvironmentNotReady
        }
        val command = writeCommand(
            configJson = opencodeConfigJson(settings, reasoningDisabled).toString(),
            authJson = authJson(settings, credentials).toString(),
            providerId = settings.providerId,
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // shell/文件操作抛出的异常不能直接穿给调用方协程：ForgeChatState 与 start() 都不接，
            // 异常一路冒到 startup 协程只会让它永远停在 Connecting。这里转成既有失败类型。
            ForgeConfigWriteResult.Failed(
                code = -1,
                output = "config write failed: " + (error.message ?: error.javaClass.simpleName),
            )
        } finally {
            supervisor.beginClosing()
        }
    }

    /**
     * 用 heredoc 落盘，避免把 Key 拼进会回显的命令行；写完立即收权限到 600。
     *
     * 两份文件都先写同目录 `.tmp` 再原子替换：写到一半被杀/超时只坏临时文件，
     * 正式文件要么还是上一版完整的、要么已是新的一版，不会留下截断的 JSON。
     *
     * 3.4.43（N20）合并写入：以前是整表覆盖——`opencode.json` 里用户手改的 provider/agent/
     * 主题设置、`auth.json` 里用户用 `opencode auth login` 存的别家凭据，换一次 Key/服务商/
     * 模型就被静默抹掉。现在改成：
     * 1. 旧文件存在且是合法 JSON 且环境里有 jq → `jq -s '.[0] * .[1]'` 递归合并，
     *    App 管的字段（provider.<id>、model、permission 等）以 App 为准（右侧覆盖左侧），
     *    App 不管的键与别家服务商条目原样保留；其中 opencode.json 的当前服务商条目
     *    整段以新内容替换（深合并删不掉键：删过的自定义请求头会被旧条目合并回来，
     *    3.4.45 审计实证后补的这一步，细节见 eta_forge_install 调用处）；
     * 2. 没有 jq 的极简环境 → 先把旧文件复制成 `.bak`，再整份覆盖：合并做不到，
     *    但至少用户手改的东西能捞回来，不会静默消失；
     * 3. 旧文件本身解析不了（上次写坏的残留）→ 由 `eta_forge_backup_if_corrupt` 先 `mv` 成
     *    `.bak` 让坏文件自愈，随后按「文件不存在」直接落新文件。
     * 合并只做「深合并到对象」这一层，数组整段取 App 的值（不拼接），语义写在此处备查。
     */
    internal fun writeCommand(configJson: String, authJson: String, providerId: String): String = buildString {
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
        // $1 = 正式文件，$2 = 刚写好的 .tmp（新内容），$3 = 可选的 jq 合并程序（缺省深合并）。
        append("eta_forge_install() {\n")
        append("  if [ ! -f \"${'$'}1\" ]; then\n")
        append("    mv -f \"${'$'}2\" \"${'$'}1\"\n")
        append("    return 0\n")
        append("  fi\n")
        append("  if command -v jq >/dev/null 2>&1; then\n")
        // 递归合并成「旧 ← 新」：新内容里出现的键以 App 为准，其余键保留（$3 可换合并程序）。
        append("    if jq -s \"${'$'}{3:-.[0] * .[1]}\" \"${'$'}1\" \"${'$'}2\" > \"${'$'}2.merged\"; then\n")
        append("      mv -f \"${'$'}2.merged\" \"${'$'}1\"\n")
        append("      rm -f \"${'$'}2\"\n")
        append("      return 0\n")
        append("    fi\n")
        append("    rm -f \"${'$'}2.merged\"\n")
        append("  fi\n")
        // 合并失败或没有 jq：留一份 .bak 再整份覆盖，绝不静默丢配置。
        append("  cp -f \"${'$'}1\" \"${'$'}1.bak\"\n")
        append("  mv -f \"${'$'}2\" \"${'$'}1\"\n")
        append("}\n")
        append("eta_forge_backup_if_corrupt \"${'$'}eta_forge_config\"\n")
        append("cat > \"${'$'}eta_forge_config.tmp\" <<'ETA_FORGE_CONFIG_EOF'\n")
        append(configJson).append('\n')
        append("ETA_FORGE_CONFIG_EOF\n")
        append("chmod 600 \"${'$'}eta_forge_config.tmp\"\n")
        // opencode.json 的合并要多一步：当前服务商条目整段以新内容为准，不能只深合并——
        // 深合并只认「新里有的键」，用户在设置页删掉的自定义请求头、换掉的旧模型会留在旧条目里
        // 被合并「复活」，改了等于没改。服务商条目由 App 独占生成，整段替换不伤用户手改的其它键；
        // auth.json 不做此处理，别家凭据还要靠深合并保留。
        append("eta_forge_install \"${'$'}eta_forge_config\" \"${'$'}eta_forge_config.tmp\" '.[1] as ${'$'}new | (.[0] * ${'$'}new) | if (${'$'}new.provider // {}) | has(\"$providerId\") then .provider[\"$providerId\"] = ${'$'}new.provider[\"$providerId\"] else . end'\n")
        append("chmod 600 \"${'$'}eta_forge_config\"\n")
        append("eta_forge_backup_if_corrupt \"${'$'}eta_forge_auth\"\n")
        append("cat > \"${'$'}eta_forge_auth.tmp\" <<'ETA_FORGE_AUTH_EOF'\n")
        append(authJson).append('\n')
        append("ETA_FORGE_AUTH_EOF\n")
        append("chmod 600 \"${'$'}eta_forge_auth.tmp\"\n")
        append("eta_forge_install \"${'$'}eta_forge_auth\" \"${'$'}eta_forge_auth.tmp\"\n")
        append("chmod 600 \"${'$'}eta_forge_auth\"\n")
    }
}
