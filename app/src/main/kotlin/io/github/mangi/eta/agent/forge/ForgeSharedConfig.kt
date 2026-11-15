package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.repository.ProviderRepository

/**
 * Forge 与 Assist 共用同一套服务商配置：从模型提供商里当前选中的服务商与模型
 * 解析出 opencode 需要的三项（Base URL、API Key、模型 ID），不再有 Forge 专属设置。
 *
 * opencode 侧的 relay 是 OpenAI 兼容协议，所以只有 OpenAI 兼容（含自定义）且用
 * API Key 的服务商能喂给 Forge；选中 Anthropic / Gemini 或 OAuth 服务商时视为未配置，
 * 由 Forge 门禁提示去模型提供商里换一个。
 */
internal sealed interface ForgeSharedConfig {
    data class Ready(
        val settings: ForgeSettings,
        val credentials: ForgeCredentials,
    ) : ForgeSharedConfig

    data object NotConfigured : ForgeSharedConfig
}

internal suspend fun resolveForgeSharedConfig(): ForgeSharedConfig {
    val providers = ProviderRepository.allProviders()
    val selection = ProviderRepository.settings()
    val provider = providers.firstOrNull { it.id == selection.selectedProviderId && it.isEnabled }
        ?: providers.firstOrNull { it.isEnabled }
        ?: return ForgeSharedConfig.NotConfigured
    if (provider.runtimeProviderType != ProviderTypes.OPENAI_COMPATIBLE) {
        return ForgeSharedConfig.NotConfigured
    }
    if (provider.authMode != ProviderAuthModes.API_KEY) return ForgeSharedConfig.NotConfigured
    val model = provider.selectedOrFirstModel(selection.selectedModelId)
        ?: return ForgeSharedConfig.NotConfigured
    val baseUrl = provider.baseUrl.trim()
    val apiKey = provider.apiKey.trim()
    val modelId = model.modelId.trim()
    if (baseUrl.isEmpty() || apiKey.isEmpty() || modelId.isEmpty()) {
        return ForgeSharedConfig.NotConfigured
    }
    return ForgeSharedConfig.Ready(
        settings = ForgeSettings(baseUrl = baseUrl, modelId = modelId),
        credentials = ForgeCredentials(apiKey = apiKey),
    )
}
