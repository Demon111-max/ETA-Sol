package io.github.mangi.eta.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ReasoningEffortSerializer::class)
enum class ReasoningEffort(
    val wireValue: String,
    val displayName: String,
    internal val rank: Int,
) {
    @SerialName("off")
    OFF("off", "Off", 0),

    @SerialName("default")
    DEFAULT("default", "Default", 1),

    @SerialName("minimal")
    MINIMAL("minimal", "Minimal", 2),

    @SerialName("low")
    LOW("low", "Low", 3),

    @SerialName("medium")
    MEDIUM("medium", "Medium", 4),

    @SerialName("high")
    HIGH("high", "High", 5),

    @SerialName("xhigh")
    XHIGH("xhigh", "XHigh", 6),

    @SerialName("max")
    MAX("max", "Max", 7),
    ;

    val enablesReasoning: Boolean
        get() = this != OFF

    companion object {
        fun fromWireValue(value: String?): ReasoningEffort? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
                ?: when (normalized) {
                    "none" -> OFF
                    "x-high", "extra_high", "extra-high" -> XHIGH
                    else -> null
                }
        }

        fun fromLegacy(thinkingEnabled: Boolean): ReasoningEffort =
            if (thinkingEnabled) DEFAULT else OFF
    }
}

object ReasoningEffortSerializer : KSerializer<ReasoningEffort> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReasoningEffort", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReasoningEffort) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): ReasoningEffort =
        ReasoningEffort.fromWireValue(decoder.decodeString()) ?: ReasoningEffort.DEFAULT
}

@Serializable
data class ModelReasoningCapabilities(
    val supportedEfforts: List<ReasoningEffort> = emptyList(),
    val defaultEffort: ReasoningEffort? = null,
    val defaultEnabled: Boolean? = null,
    val mandatory: Boolean = false,
    val canDisable: Boolean = false,
    val supportsBudget: Boolean = false,
    val maxBudgetTokens: Int? = null,
    val supportsMaxTokens: Boolean? = null,
) {
    val selectableEfforts: List<ReasoningEffort>
        get() = buildList {
            if (canDisable && !mandatory) add(ReasoningEffort.OFF)
            add(ReasoningEffort.DEFAULT)
            supportedEfforts
                .asSequence()
                .filter { it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT }
                .distinct()
                .sortedBy(ReasoningEffort::rank)
                .forEach(::add)
        }

    fun normalize(requested: ReasoningEffort): ReasoningEffort {
        val selectable = selectableEfforts
        if (requested in selectable) return requested
        if (requested == ReasoningEffort.OFF || requested == ReasoningEffort.DEFAULT) {
            return ReasoningEffort.DEFAULT
        }
        return selectable
            .filter { it != ReasoningEffort.OFF && it.rank <= requested.rank }
            .maxByOrNull(ReasoningEffort::rank)
            ?: ReasoningEffort.DEFAULT
    }
}

/**
 * 能力未知（模型没声明思考能力、也没明确声明不支持）时的回落能力：放行全部档位。
 * Assist 与 Forge 共用这一份口径，不许各写一份、各猜一个方向——此前 Assist 把
 * 「未知」当成「不支持」（档位段直接消失），Forge 放行 5 档，同一个模型两边两样
 * （3.4.49 审理）。明确不支持（解析入口返回 null 且模型声明了不支持）不走这里。
 */
internal val UnknownReasoningCapabilities = ModelReasoningCapabilities(
    supportedEfforts = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
    ),
    canDisable = true,
)

/**
 * 新对话的初始档（用户定案）：能力声明里写了默认档就用默认档，没写才兜底 High，
 * 都要先过 normalize 落进可选集。当前档不是 DEFAULT（用户已选过/已被归一化）时原样保留。
 * 能力未知（[capabilities] 为 null）时不猜，保持 DEFAULT（服务商默认）。
 * 此前 defaultEffort 只写进目录、没有任何消费方，新对话实际发的是服务商默认、
 * 滑块却停在 High 位——显示一套实际一套（3.4.49 审理）。Assist 与 Forge 共用。
 */
internal fun initialReasoningEffort(
    capabilities: ModelReasoningCapabilities?,
    current: ReasoningEffort,
): ReasoningEffort {
    if (capabilities == null || current != ReasoningEffort.DEFAULT) return current
    return capabilities.normalize(capabilities.defaultEffort ?: ReasoningEffort.HIGH)
}
