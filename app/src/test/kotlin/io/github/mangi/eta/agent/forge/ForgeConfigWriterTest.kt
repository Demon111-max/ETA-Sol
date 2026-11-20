package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.data.model.ForgeSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.UnknownReasoningCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Forge 写进 opencode 配置的思考档位：档位集与 Assist 同源（能力的 selectableEfforts），
 * OFF 必须真关（3.4.50 P1-3）。
 *
 * 能力的三态各有各的产物，测试要分别钉住（3.4.51 P2-8 最终口径）：
 * 声明了能力 ⇒ 按 selectableEfforts 写 variants；能力未知 ⇒ 回落表七档（与 Assist 同一份）；
 * 明确不支持 ⇒ null，配置里一个 variant 都没有。
 */
class ForgeConfigWriterTest {

    @Test
    fun variantsFollowTheDeclaredCapabilitiesInsteadOfAWrittenList() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            defaultEffort = ReasoningEffort.HIGH,
            canDisable = true,
        )

        val variants = modelEntry(capabilities)["variants"]!!.jsonObject

        // OFF 来自 canDisable，DEFAULT 不发 variant；写死的六档里 max/xhigh 不该出现。
        assertEquals(setOf("off", "low", "high"), variants.keys)
        assertEquals("low", variants["low"]!!.jsonObject["reasoningEffort"]!!.jsonPrimitive.content)
        assertEquals("high", variants["high"]!!.jsonObject["reasoningEffort"]!!.jsonPrimitive.content)
    }

    @Test
    fun offVariantTurnsThinkingOffWithTheOnlyFieldThatWorksOnBothProtocols() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.HIGH),
            canDisable = true,
        )

        val off = modelEntry(capabilities)["variants"]!!.jsonObject["off"]!!.jsonObject

        // Forge 只接 OpenAI 兼容/Responses，这两条协议在 opencode 1.19.5 里只认 reasoningEffort，
        // "none" 就是「真关」（与 Assist 关思考时发出去的 wireValue 一致）。
        assertEquals("none", off["reasoningEffort"]!!.jsonPrimitive.content)
        // thinking: {type:"disabled"} 是 Anthropic 形状，写在这里既不会生效、又可能被严格的兼容
        // 上游当未知字段拒绝：不许再出现在配置里（3.4.51 P2-9）。
        assertNull(off["thinking"])
    }

    @Test
    fun explicitlyUnsupportedModelWritesNoVariants() {
        // null = 模型被明确声明不支持思考（effectiveReasoningCapabilities 唯一的 null 出口，
        // 与 reasoningDisabled 同一件事）。3.4.50 这里回落过「六档」，那支生产上走不到，
        // 也会让「明确不支持」变成「有六个档可选」（3.4.51 P2-8）。
        assertNull(modelEntry(capabilities = null)["variants"])
        // 指纹同样不能带着一份并不存在的档位表。
        assertEquals("", ForgeConfigWriter.variantSignature(null))
    }

    @Test
    fun unknownCapabilitiesKeepTheSameGearsAsAssist() {
        // 「未知」不走 null，而是回落表（含 OFF、与 Assist 同一份）：这里比的是
        // 「Forge 写的档位集 == 共用回落表的可停档」，而不是把七档名字抄一遍——
        // 抄一遍就又是一份私有真值，回落表改了这边悄悄过期（3.4.51 P2-8）。
        val variants = modelEntry(UnknownReasoningCapabilities)["variants"]!!.jsonObject
        val expected = UnknownReasoningCapabilities.selectableEfforts
            .filter { it != ReasoningEffort.DEFAULT }
            .map { it.wireValue }
            .toSet()

        assertEquals(expected, variants.keys)
    }

    @Test
    fun variantSignatureTracksTheVariantKeys() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            canDisable = true,
        )

        val signature = ForgeConfigWriter.variantSignature(capabilities)

        assertEquals("off,low,high", signature)
        assertEquals(
            modelEntry(capabilities)["variants"]!!.jsonObject.keys.joinToString(","),
            signature,
        )
    }

    private fun modelEntry(capabilities: ModelReasoningCapabilities?): JsonObject =
        ForgeConfigWriter.opencodeConfigJson(
            settings = ForgeSettings(baseUrl = "https://example.com/v1", modelId = "m-1"),
            reasoningCapabilities = capabilities,
        )["provider"]!!.jsonObject[ForgeConfigWriter.PROVIDER_ID]!!
            .jsonObject["models"]!!.jsonObject["m-1"]!!.jsonObject
}
