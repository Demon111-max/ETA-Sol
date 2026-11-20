package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes

internal object AgentRuntimeRequestConfigResolver {
    fun constrainPresentation(
        request: AgentRuntimeWire.RunRequest,
        isEtaProcess: Boolean,
    ): AgentRuntimeWire.RunRequest {
        val supportedEntry = request.handoff?.source in setOf(
            AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
        )
        if (!request.config.supportsSearchSuggestions || isEtaProcess && supportedEntry) return request
        return request.copy(config = request.config.copy(supportsSearchSuggestions = false))
    }

    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        usesDefaultRuntimeConfig(request) || ProviderAuthModes.isOAuth(request.config.authMode)

    fun usesDefaultRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

    /**
     * 用 Runtime 侧重新解析出来的能力整体替换入口进程送来的那份。
     *
     * 能力是「服务商/目录真声明」还是「能力未知时的回落形状」，只能由 Runtime 侧按
     * (providerId, authMode, modelId) 走与 UI 同一个解析入口自己判断：入口进程带过来的
     * 配置里没有任何可信的标记可用（@Transient 的 isFallback 过不了 Bundle 与 JSON，
     * 跨进程后就变成「真声明」），所以这里一律不信，重算一遍。查不到就是没声明（null），
     * 发送侧据此什么都不发——这就是旧的 null 护栏（3.4.50 审理 P1-1）。
     *
     * 档位本身是用户的明确选择，不是猜的：有真声明时按声明归一化（避免入口进程算出的
     * 越界档位撞上服务商硬表），没声明时原样留着（老版本兼容路径仍会用它）。
     */
    fun applyDeclaredReasoningCapabilities(
        request: AgentRuntimeWire.RunRequest,
        resolve: (providerId: String, authMode: String, modelId: String) -> ModelReasoningCapabilities?,
    ): AgentRuntimeWire.RunRequest {
        val config = request.config
        val declared = resolve(config.providerId, config.authMode, config.model)
        val requestedEffort = config.effectiveReasoningEffort
        val effort = declared?.normalize(requestedEffort) ?: requestedEffort
        if (declared == config.reasoningCapabilities && effort == requestedEffort) return request
        return request.copy(
            config = config.copy(
                reasoningCapabilities = declared,
                reasoningEffort = effort,
                thinkingEnabled = effort.enablesReasoning,
            ),
        )
    }

    /**
     * 只用 Runtime 侧的模型与凭据替换入口配置；工具开关保留入口请求的值，
     * 随后仍由 AgentRuntimePolicy 与本地权限取交集。模型配置本身不携带工具开关，
     * 直接整体替换会让默认关闭的终端、敏感读取与敏感操作工具在语音入口始终不可用。
     */
    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        runtimeConfig: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        val entry = request.config
        val preserveReasoningChoice = !usesDefaultRuntimeConfig(request)
        if (preserveReasoningChoice) {
            require(
                runtimeConfig.providerId == entry.providerId &&
                    runtimeConfig.authMode == entry.authMode && runtimeConfig.model == entry.model
            ) { "账号登录配置已变更，请重新选择服务商和模型" }
        }
        val effort = if (preserveReasoningChoice) {
            runtimeConfig.reasoningCapabilities?.normalize(entry.effectiveReasoningEffort)
                ?: runtimeConfig.effectiveReasoningEffort
        } else runtimeConfig.effectiveReasoningEffort
        val config = runtimeConfig.copy(
            terminalTools = entry.terminalTools,
            browserTools = entry.browserTools,
            deviceDirectTools = entry.deviceDirectTools,
            deviceSensitiveReadTools = entry.deviceSensitiveReadTools,
            deviceSensitiveActionTools = entry.deviceSensitiveActionTools,
            subagentTools = entry.subagentTools,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = runtimeConfig.autoCompactionEnabled && entry.autoCompactionEnabled,
            supportsSearchSuggestions = entry.supportsSearchSuggestions,
        )
        val handoff = request.handoff ?: return request.copy(config = config)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }
}
