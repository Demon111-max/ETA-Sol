package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Forge（opencode）对话配置的非敏感部分。
 *
 * [baseUrl] 与 [modelId] 是 opencode 的 provider 配置（中转站地址、模型 ID）；
 * API Key 与其它密钥一样走 Android Keystore 加密存储，不落在 DataStore 里。
 */
@Serializable
internal data class ForgeSettings(
    val baseUrl: String = "",
    val modelId: String = DEFAULT_MODEL_ID,
    /** opencode 侧的 provider ID：由共用配置里服务商的名字生成，不再写死 relay。 */
    val providerId: String = "relay",
    /** opencode 侧的 provider 显示名，与 Assist 里看到的服务商名一致。 */
    val providerName: String = "Relay",
    /** 服务商的接口协议（OpenAiEndpointMode）：决定 opencode 配置里选哪个 AI SDK 包。 */
    val endpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
    /** 服务商 + 模型两级的自定义请求头（模型级同名覆盖服务商级）：Assist 每个请求都带，Forge 也要带进 opencode 配置，否则需要请求头的中转站会在 Forge 里 401。 */
    val customHeaders: List<CustomHeader> = emptyList(),
    /** 模型的有效上下文上限（用户显式覆盖优先）：写进 opencode 的 limit，并供 Forge 输入栏的用量显示。 */
    val contextWindow: Int? = null,
    /**
     * 模型是否支持图片输入：来自共用配置里模型的 supportsVision（设置页模型编辑的「图片」勾选、
     * 远程拉取与官方目录的 inputModalities 都汇到这一个字段）。写进 opencode 配置的
     * modalities/attachment；不写时 opencode 默认按纯文字模型处理、图片会被降级成合成文本。
     */
    val supportsVision: Boolean = false,
) {
    companion object {
        const val DEFAULT_MODEL_ID = "deepseek-v4.1-flash"
    }
}

/** Forge 的密钥材料；仅在请求配置写入与内存里短暂存在。 */
@Serializable
internal data class ForgeCredentials(
    val apiKey: String = "",
)
