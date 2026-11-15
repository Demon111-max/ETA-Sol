package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.repository.ProviderRepository

/**
 * Forge 与 Assist 共用同一套服务商配置：从模型提供商里当前选中的服务商与模型
 * 解析出 opencode 需要的三项（Base URL、API Key、模型 ID），不再有 Forge 专属设置。
 *
 * opencode 侧走 OpenAI 兼容协议，所以只有 OpenAI 兼容（含自定义）且用
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
    // 协议（Chat Completions / Responses）长在服务商自己的 endpointMode 上：
    // runtimeProviderType 对这两类服务商都只报 OPENAI_COMPATIBLE，不看协议，
    // Forge 必须单独把协议带出来，否则 Responses 的 GPT 渠道会被按 Chat 协议喂给 opencode。
    val endpointMode = when (provider) {
        is OpenAiCompatibleProviderSetting -> provider.endpointMode
        is CustomProviderSetting -> provider.endpointMode
        else -> OpenAiEndpointMode.CHAT_COMPLETIONS
    }
    // 自定义请求头：服务商级 + 模型级合并，模型级同名覆盖（与 Assist 的请求口径一致）。
    val mergedHeaders = (provider.customHeaders + model.customHeaders)
        .filter { it.name.isNotBlank() }
        .associateBy { it.name.trim().lowercase() }
        .values
        .toList()
    return ForgeSharedConfig.Ready(
        settings = ForgeSettings(
            baseUrl = baseUrl,
            modelId = modelId,
            providerId = forgeProviderId(provider.name, provider.id),
            providerName = provider.name.trim().ifBlank { provider.id },
            endpointMode = endpointMode,
            customHeaders = mergedHeaders,
            contextWindow = model.effectiveContextWindow,
        ),
        credentials = ForgeCredentials(apiKey = apiKey),
    )
}

/**
 * opencode 的 provider ID 只认小写字母/数字/连字符：取服务商名字里的可读部分生成，
 * 让 Forge 里模型的身份与 Assist 里选的服务商对得上，不再是写死的 relay；
 * 名字里没有拉丁字符时回落到服务商自身的 ID，再不行才用 relay。
 */
internal fun forgeProviderId(name: String, fallbackId: String): String {
    val slug = name.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .trim('-')
    if (slug.isNotEmpty()) return slug
    val id = fallbackId.trim().lowercase()
    return if (id.matches(Regex("[a-z0-9][a-z0-9_-]*"))) id else "relay"
}
