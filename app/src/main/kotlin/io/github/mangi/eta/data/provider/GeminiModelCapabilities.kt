package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort

/** 仅明确确认的模型启用原生能力，不能由 generateContent 目录能力推断工具混用。 */
internal object GeminiModelCapabilities {
    private data class Capabilities(
        val reasoning: ModelReasoningCapabilities,
        val hostedSearch: Boolean,
    )

    private val lowToHigh = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
    private val minimalToHigh = listOf(ReasoningEffort.MINIMAL) + lowToHigh
    val configurableEfforts: List<ReasoningEffort> = minimalToHigh
    private val knownModels = mapOf(
        "gemini-4-carbon" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-4-argon" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.8-flash" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.6-flash" to capabilities(minimalToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.5-flash-lite" to capabilities(minimalToHigh, ReasoningEffort.MINIMAL, hostedSearch = true),
        "gemini-3.1-pro-preview" to capabilities(lowToHigh, ReasoningEffort.HIGH, hostedSearch = true),
        "gemini-3-flash-preview" to capabilities(minimalToHigh, ReasoningEffort.HIGH, hostedSearch = true),
        "gemini-3-pro-preview" to capabilities(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningEffort.HIGH, hostedSearch = true,
        ),
    )

    fun reasoningCapabilities(modelId: String): ModelReasoningCapabilities? = knownModels[normalize(modelId)]?.reasoning

    /**
     * 原生 Gemini 的声明口径，null = 未声明。此前「关思考」也合成一份空声明，
     * 与「确实声明了但没有具体档位」混成同一个值，仓库侧
     * RuntimeConfigRepository.effectiveReasoningCapabilities 的 null 分支对 Gemini
     * 因此不可达（3.4.50 审理 P3-25）。关思考本来就是「没声明思考能力」，
     * 服务端按默认跑，这里必须给出 null 而不是空声明。
     *
     * reasoningOverride = true 只表示「用户要求开思考」，没写具体档位时沿用模型自身的
     * 声明（目录/远端写入的 reasoningCapabilities），再没有才是未声明。
     */
    fun resolveReasoning(model: Model): ModelReasoningCapabilities? = when (model.reasoningOverride) {
        false -> null
        true -> model.reasoningCapabilitiesOverride ?: model.reasoningCapabilities
        null -> if (model.reasoning == false) null else {
            model.reasoningCapabilities ?: reasoningCapabilities(model.modelId)
        }
    }

    fun supportsHostedSearch(modelId: String): Boolean = knownModels[normalize(modelId)]?.hostedSearch == true

    fun normalize(modelId: String): String = modelId.trim().removePrefix("models/").lowercase()

    private fun capabilities(
        efforts: List<ReasoningEffort>,
        default: ReasoningEffort,
        hostedSearch: Boolean = false,
    ) = Capabilities(
        reasoning = ModelReasoningCapabilities(
            supportedEfforts = efforts,
            defaultEffort = default,
            mandatory = true,
        ),
        hostedSearch = hostedSearch,
    )
}
