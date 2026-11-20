package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ForgeCredentials
import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/**
 * Forge 与 Assist 共用同一套服务商配置：从模型提供商里当前选中的服务商与模型
 * 解析出 opencode 需要的三项（Base URL、API Key、模型 ID），不再有 Forge 专属设置。
 *
 * opencode 侧走 OpenAI 兼容协议，所以只有 OpenAI 兼容（含自定义）且用
 * API Key 的服务商能喂给 Forge；选中 Anthropic / Gemini 或 OAuth 服务商时视为未配置，
 * 由 Forge 门禁提示去模型提供商里换一个——不会自作主张换成别的启用服务商。
 */
internal sealed interface ForgeSharedConfig {
    data class Ready(
        val settings: ForgeSettings,
        val credentials: ForgeCredentials,
        /** 当前模型的思考能力：必须与 Assist 走同一个解析入口（RuntimeConfigRepository.effectiveReasoningCapabilities，含未知回落），不许 Forge 只读模型原始字段另算一份。 */
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        /** 模型被明确声明不支持思考（设置页关了「支持思考」或元数据 reasoning=false）：与「能力未知」区分——前者一档都不许开，后者才放行回落档位表。 */
        val reasoningDisabled: Boolean = false,
    ) : ForgeSharedConfig

    /**
     * 配置没就绪，且**带原因**（3.4.43 N19）。
     *
     * 以前是个无字段的 object：服务商被禁用、类型不是 OpenAI 兼容、用 OAuth、没有模型、
     * Base URL/Key/模型 ID 为空，六条完全不同的路全塌缩成同一句「还没配置」，
     * 用户根本不知道该去哪儿改哪一项。
     */
    data class NotConfigured(val reason: Reason) : ForgeSharedConfig {
        enum class Reason {
            /** 选中的服务商不存在或被禁用。 */
            PROVIDER_DISABLED,

            /** 服务商类型不是 OpenAI 兼容（Anthropic / Gemini 等）。 */
            PROVIDER_INCOMPATIBLE,

            /** 服务商用的是 OAuth 等非 API Key 认证。 */
            AUTH_MODE_UNSUPPORTED,

            /** 服务商下没有可用模型。 */
            NO_MODEL,

            /** Base URL 为空。 */
            MISSING_BASE_URL,

            /** API Key 为空。 */
            MISSING_API_KEY,

            /** 模型 ID 为空。 */
            MISSING_MODEL_ID,
        }
    }
}

internal suspend fun resolveForgeSharedConfig(): ForgeSharedConfig {
    val providers = ProviderRepository.allProviders()
    val selection = ProviderRepository.settings()
    // 只认用户选中的那一个：选中项被禁用/未配置就是 NotConfigured，绝不回落到"第一个启用的服务商"，
    // 否则用户代码会被发往没选过的厂商。服务商内部仍可回落到它自己的第一个可用模型（selectedOrFirstModel）。
    val provider = providers.firstOrNull { it.id == selection.selectedProviderId && it.isEnabled }
        ?: return ForgeSharedConfig.NotConfigured(
            ForgeSharedConfig.NotConfigured.Reason.PROVIDER_DISABLED,
        )
    if (provider.runtimeProviderType != ProviderTypes.OPENAI_COMPATIBLE) {
        return ForgeSharedConfig.NotConfigured(
            ForgeSharedConfig.NotConfigured.Reason.PROVIDER_INCOMPATIBLE,
        )
    }
    if (provider.authMode != ProviderAuthModes.API_KEY) {
        return ForgeSharedConfig.NotConfigured(
            ForgeSharedConfig.NotConfigured.Reason.AUTH_MODE_UNSUPPORTED,
        )
    }
    val model = provider.selectedOrFirstModel(selection.selectedModelId)
        ?: return ForgeSharedConfig.NotConfigured(ForgeSharedConfig.NotConfigured.Reason.NO_MODEL)
    val baseUrl = provider.baseUrl.trim()
    val apiKey = provider.apiKey.trim()
    val modelId = model.modelId.trim()
    // 分开报：三条空值里到底缺哪一个，用户要能一眼看出来。
    if (baseUrl.isEmpty()) {
        return ForgeSharedConfig.NotConfigured(
            ForgeSharedConfig.NotConfigured.Reason.MISSING_BASE_URL,
        )
    }
    if (apiKey.isEmpty()) {
        return ForgeSharedConfig.NotConfigured(
            ForgeSharedConfig.NotConfigured.Reason.MISSING_API_KEY,
        )
    }
    if (modelId.isEmpty()) {
        return ForgeSharedConfig.NotConfigured(
            ForgeSharedConfig.NotConfigured.Reason.MISSING_MODEL_ID,
        )
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
            supportsVision = model.supportsVision,
        ),
        credentials = ForgeCredentials(apiKey = apiKey),
        // 与 Assist 同一个入口：effectiveReasoningCapabilities 负责「声明 → 未知回落 →
        // 明确不支持(null)」三段口径。此前这里读 raw reasoningCapabilities，Forge 拿到
        // null 后自己回落一套写死 5 档、没有 OFF 的表，同一个模型两边档位不一样，
        // 而且 Forge 里选 Off 显示关了、实际照样写 variants（3.4.49 审理 P1-3）。
        reasoningCapabilities = RuntimeConfigRepository.effectiveReasoningCapabilities(provider, model),
        reasoningDisabled = model.effectiveReasoning == false,
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
