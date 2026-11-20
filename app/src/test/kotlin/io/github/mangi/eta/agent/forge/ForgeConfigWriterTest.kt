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
    fun offVariantActuallyDisablesThinkingOnBothWireShapes() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.HIGH),
            canDisable = true,
        )

        val off = modelEntry(capabilities)["variants"]!!.jsonObject["off"]!!.jsonObject

        // 一套口径给 OpenAI 兼容家族，另一套给 DeepSeek/百炼/Moonshot 那族；
        // 只写其中一个就会出现「药丸显示关、上游照样开」（3.4.50 P1-3）。
        assertEquals("none", off["reasoningEffort"]!!.jsonPrimitive.content)
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun reasoningDisabledWritesNoVariantsAtAll() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.HIGH),
            canDisable = true,
        )

        assertNull(modelEntry(capabilities, reasoningDisabled = true)["variants"])
    }

    @Test
    fun fallbackCapabilitiesKeepTheSameGearsAsAssist() {
        val variants = modelEntry(UnknownReasoningCapabilities)["variants"]!!.jsonObject

        assertEquals(
            setOf("off", "minimal", "low", "medium", "high", "xhigh", "max"),
            variants.keys,
        )
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

    private fun modelEntry(
        capabilities: ModelReasoningCapabilities?,
        reasoningDisabled: Boolean = false,
    ): JsonObject = ForgeConfigWriter.opencodeConfigJson(
        settings = ForgeSettings(baseUrl = "https://example.com/v1", modelId = "m-1"),
        reasoningCapabilities = capabilities,
        reasoningDisabled = reasoningDisabled,
    )["provider"]!!.jsonObject[ForgeConfigWriter.PROVIDER_ID]!!
        .jsonObject["models"]!!.jsonObject["m-1"]!!.jsonObject
}
