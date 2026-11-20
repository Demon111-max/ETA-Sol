package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.AssistantOverlaySettings
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.UnknownReasoningCapabilities
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    fun subagentSettingsFlow() = SettingsDataStore.subagentSettingsFlow()

    suspend fun setSubagentSettings(settings: SubagentSettings) {
        SettingsDataStore.setSubagentSettings(settings)
    }

    fun conversationTitleSettingsFlow() = SettingsDataStore.conversationTitleSettingsFlow()

    suspend fun setConversationTitleSettings(settings: ConversationTitleSettings) {
        SettingsDataStore.setConversationTitleSettings(settings)
    }

    fun assistantOverlaySettingsFlow() = SettingsDataStore.assistantOverlaySettingsFlow()

    suspend fun setAssistantOverlaySettings(settings: AssistantOverlaySettings) {
        SettingsDataStore.setAssistantOverlaySettings(settings)
    }

    /**
     * 解析全局对话浮窗助手（电源键 / 系统助手入口）实际运行所用的模型与思考配置。
     *
     * 指定了独立浮窗模型且仍启用时优先使用，否则平滑回退到全局主模型；
     * 思考等级未显式指定时跟随全局默认思考开关与模型默认档位。
     */
    suspend fun assistantOverlayRuntimeConfig(): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.assistantOverlaySettings()
        val customModelId = settings.modelId?.takeIf { it.isNotBlank() }
        val base = if (customModelId != null) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.allProviders().enabledModel(customModelId)?.let { (provider, model) ->
                buildRuntimeConfig(provider, model)
            }
        } else {
            null
        } ?: currentRuntimeConfig() ?: return null
        return applyAssistantOverlaySettings(base, settings)
    }

    internal fun applyAssistantOverlaySettings(
        base: AgentModelClient.ModelConfig,
        settings: AssistantOverlaySettings,
        defaultThinkingEnabled: Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
    ): AgentModelClient.ModelConfig {
        val explicitEffort = settings.resolvedReasoningEffort
        val capabilities = base.reasoningCapabilities
        val effort = when {
            explicitEffort != null -> when {
                capabilities != null -> capabilities.normalize(explicitEffort)
                explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                else -> base.effectiveReasoningEffort
            }
            !defaultThinkingEnabled -> capabilities?.normalize(ReasoningEffort.OFF) ?: ReasoningEffort.OFF
            else -> base.effectiveReasoningEffort
        }
        return base.copy(
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
        )
    }

    /**
     * 解析对话标题生成所用的模型与思考配置。
     *
     * 关闭自动标题时返回 `null`；若指定了独立标题模型且仍启用则优先使用，否则平滑回退到
     * 会话当前模型（或全局默认模型）。思考等级未显式指定时默认取最低可用档位以节省 Token。
     */
    suspend fun titleGeneratorConfig(
        conversationModelId: String? = null,
    ): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.conversationTitleSettings()
        if (!settings.enabled) return null
        val customModelId = settings.modelId?.takeIf { it.isNotBlank() }
        val base = if (customModelId != null) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.allProviders().enabledModel(customModelId)?.let { (provider, model) ->
                buildRuntimeConfig(provider, model)
            }
        } else {
            null
        } ?: runtimeConfigFor(conversationModelId) ?: return null
        return applyTitleGenerationSettings(base, settings)
    }

    internal fun applyTitleGenerationSettings(
        base: AgentModelClient.ModelConfig,
        settings: ConversationTitleSettings,
    ): AgentModelClient.ModelConfig {
        val explicitEffort = settings.resolvedReasoningEffort
        val capabilities = base.reasoningCapabilities
        val effort = when {
            explicitEffort != null -> when {
                capabilities != null -> capabilities.normalize(explicitEffort)
                explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                else -> base.effectiveReasoningEffort
            }
            capabilities != null -> {
                val selectable = capabilities.selectableEfforts
                if (ReasoningEffort.OFF in selectable) {
                    ReasoningEffort.OFF
                } else {
                    selectable.filter { it != ReasoningEffort.DEFAULT }
                        .minByOrNull(ReasoningEffort::rank)
                        ?: ReasoningEffort.DEFAULT
                }
            }
            else -> base.effectiveReasoningEffort
        }
        return base.copy(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            subagentTools = false,
            hostedWebSearchEnabled = false,
            autoCompactionEnabled = false,
            supportsSearchSuggestions = false,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
        )
    }

    /**
     * 解析 Subagent 实际运行所用的模型配置。
     *
     * 指定了独立子模型且该模型仍处于启用状态时优先使用子模型，否则平滑回退到主模型；
     * 工具开关继承父请求，并强制关闭 `subagentTools` 防止递归派发。
     */
    suspend fun subagentRuntimeConfig(
        primaryConfig: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        val settings = SettingsDataStore.subagentSettings()
        val secondaryModelId = settings.modelId?.takeIf { it.isNotBlank() }
        val resolvedSecondary = if (secondaryModelId != null) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.allProviders().enabledModel(secondaryModelId)
        } else {
            null
        }
        val base = resolvedSecondary?.let { (provider, model) ->
            buildRuntimeConfig(provider, model)
        } ?: primaryConfig
        val explicitEffort = settings.resolvedReasoningEffort
        val effort = when {
            explicitEffort != null -> {
                val capabilities = base.reasoningCapabilities
                when {
                    capabilities != null -> capabilities.normalize(explicitEffort)
                    explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                    else -> base.effectiveReasoningEffort
                }
            }
            resolvedSecondary != null -> base.effectiveReasoningEffort
            else -> primaryConfig.effectiveReasoningEffort
        }
        return base.copy(
            terminalTools = primaryConfig.terminalTools,
            browserTools = primaryConfig.browserTools,
            deviceDirectTools = primaryConfig.deviceDirectTools,
            deviceSensitiveReadTools = primaryConfig.deviceSensitiveReadTools,
            deviceSensitiveActionTools = primaryConfig.deviceSensitiveActionTools,
            subagentTools = false,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = primaryConfig.autoCompactionEnabled,
            supportsSearchSuggestions = false,
        )
    }

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(provider, model)
    }

    /** 会话绑定的模型仍可用时使用它，否则回落到默认模型。 */
    suspend fun runtimeConfigFor(modelId: String?): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            return buildRuntimeConfig(provider, model)
        }
        return currentRuntimeConfig()
    }

    suspend fun oauthRuntimeConfigFor(
        providerId: String,
        authMode: String,
        modelName: String,
    ): AgentModelClient.ModelConfig? {
        if (!ProviderAuthModes.isOAuth(authMode)) return null
        val provider = ProviderRepository.providerById(providerId)
            ?.takeIf { it.isEnabled && it.authMode == authMode } ?: return null
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == modelName } ?: return null
        return buildRuntimeConfig(provider, model)
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(if (config.authMode == ProviderAuthModes.API_KEY) config else config.copy(apiKey = ""))

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt.orEmpty().trim()
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
        val endpointMode = profile?.endpointMode ?: provider.endpointMode()
        // 只带服务商/目录的真声明，不带「能力未知」的回落形状：这段配置会进 Bundle 与
        // Prefs（runtimeConfigJson），而回落形状跨进程后与真声明长得一模一样，发送侧
        // 会把猜出来的档位当真声明写进请求（3.4.50 审理 P1-1）。没声明就是 null，
        // 由 Runtime 侧按同一个解析入口重新解析。
        val declaredCapabilities = reasoningCapabilities(provider, model)
        // 用户/目录明确关掉思考时记 OFF（老版本兼容路径据此把 "none" 发给上游）；
        // 其余从服务商默认起播，UI 侧发送前会用用户实际选的档位覆盖它。
        val thinkingDisabled = model.effectiveReasoning == false
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = profile?.providerType ?: provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = profile?.baseUrl ?: provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim().takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            thinkingEnabled = !thinkingDisabled,
            reasoningEffort = if (thinkingDisabled) ReasoningEffort.OFF else ReasoningEffort.DEFAULT,
            reasoningCapabilities = declaredCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
    }

    /**
     * 聊天链路的有效能力口径（Assist 与 Forge 共用）：解析得到就用解析结果；
     * 解析不到但模型明确声明不支持思考 → null（真不支持）；其余属于「能力未知」
     * → 回落全档位 [UnknownReasoningCapabilities]。档位上限、发送归一化与运行时
     * 配置只许走这一份，不许调用方各自拿 [reasoningCapabilities] 的 null 猜方向——
     * 此前 Assist 把未知当不支持（档位消失）、Forge 放行 5 档，同模型两边两样
     * （3.4.49 审理）。
     */
    fun effectiveReasoningCapabilities(
        provider: ProviderSetting,
        model: Model,
    ): ModelReasoningCapabilities? {
        reasoningCapabilities(provider, model)?.let { return it }
        if (model.effectiveReasoning == false) return null
        return UnknownReasoningCapabilities
    }

    fun reasoningCapabilities(provider: ProviderSetting, model: Model): ModelReasoningCapabilities? {
        if (provider is GeminiProviderSetting) {
            return GeminiModelCapabilities.resolveReasoning(model)
        }
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            provider.endpointMode() == OpenAiEndpointMode.RESPONSES
        return ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
    }

    /**
     * 运行时侧的能力重解析入口：按 (providerId, authMode, modelId) 找回与 UI 侧同一口径的
     * 声明能力（[reasoningCapabilities]，不含「能力未知」回落形状）。
     *
     * 查不到（provider 或 model 已被删/禁用，登录方式已变）一律当作「没声明」返回 null：
     * 宁可不发思考参数，也不能把入口进程那份可能只是回落猜测的能力当真声明用——这份
     * 能力跨进程后与真声明无法区分（3.4.50 审理 P1-1）。
     */
    suspend fun declaredReasoningCapabilities(
        providerId: String,
        authMode: String,
        modelId: String,
    ): ModelReasoningCapabilities? {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(providerId)
            ?.takeIf { it.isEnabled && it.authMode == authMode } ?: return null
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == modelId } ?: return null
        return reasoningCapabilities(provider, model)
    }

    private fun ProviderSetting.endpointMode(): String = when (this) {
        is OpenAiCompatibleProviderSetting -> endpointMode
        is CustomProviderSetting -> endpointMode
        is AnthropicProviderSetting, is GeminiProviderSetting -> ""
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)
}
