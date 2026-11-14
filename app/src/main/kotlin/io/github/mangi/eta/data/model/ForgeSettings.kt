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
