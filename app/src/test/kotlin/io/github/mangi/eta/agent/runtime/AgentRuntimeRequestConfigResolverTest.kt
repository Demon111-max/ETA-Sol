package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderReasoning
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.CustomHeader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 需要 Robolectric：P1-1 的回归测试必须真的走一遍 [AgentRuntimeWire] 的 Bundle 编码→解码，
 * 裸 JVM 下 android.os.Bundle 全是 not-mocked 桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class AgentRuntimeRequestConfigResolverTest {
    @Test
    fun onlyEtaProcessesWithSupportedEntryCanDeclareSearchPresentation() {
        val sources = listOf(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "breeno", "xiaoai", "unknown")
        for (source in sources) for (isEta in listOf(false, true)) {
            val original = request(source, modelConfig("fixture", ReasoningEffort.DEFAULT)
                .copy(supportsSearchSuggestions = true))
            val constrained = AgentRuntimeRequestConfigResolver.constrainPresentation(original, isEta)
            assertEquals(isEta && source in sources.take(2), constrained.config.supportsSearchSuggestions)
        }
    }

    @Test
    fun voiceConfigResolutionPreservesOnlyTheEntryPresentationDeclaration() {
        val local = modelConfig("fixture", ReasoningEffort.DEFAULT)
        val original = request(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            local.copy(supportsSearchSuggestions = true))
        assertTrue(AgentRuntimeRequestConfigResolver.applyRuntimeConfig(original, local)
            .config.supportsSearchSuggestions)
        assertFalse(AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            original.copy(config = local), local.copy(supportsSearchSuggestions = true),
        ).config.supportsSearchSuggestions)
    }

    @Test
    fun oauthRequestUsesLocalConnectionAndPreservesSupportedTransientChoices() {
        val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
            providerId = "account-provider",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            baseUrl = "https://api.openai.com/v1",
            customHeaders = listOf(CustomHeader("x-local", "fixture")),
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
            ),
        )
        val entry = local.copy(
            apiKey = "fixture-untrusted", baseUrl = "https://untrusted.invalid",
            customHeaders = listOf(CustomHeader("x-untrusted", "fixture")),
            reasoningEffort = ReasoningEffort.HIGH,
            terminalTools = true, deviceSensitiveReadTools = true,
            autoCompactionEnabled = false,
        )
        val request = request("breeno", entry)
        assertTrue(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        val result = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local).config

        assertEquals(local.baseUrl, result.baseUrl)
        assertEquals("", result.apiKey)
        assertEquals(local.customHeaders, result.customHeaders)
        assertEquals(ReasoningEffort.HIGH, result.reasoningEffort)
        assertTrue(result.terminalTools)
        assertTrue(result.deviceSensitiveReadTools)
        assertFalse(result.autoCompactionEnabled)
    }

    @Test
    fun oauthRequestCannotChangeLocalAccountOrInjectUnsupportedReasoning() {
        val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
            providerId = "account-provider", authMode = ProviderAuthModes.XAI_GROK,
            reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW)),
        )
        val request = request("xiaoai", local.copy(reasoningEffort = ReasoningEffort.MAX))
        assertEquals(ReasoningEffort.LOW,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local).config.reasoningEffort)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local.copy(providerId = "other-provider"))
        }
    }

    @Test
    fun etaVoiceRequestUsesRuntimeConfigAndUpdatesArchiveMetadata() {
        val runtimeConfig = modelConfig("runtime-key", ReasoningEffort.HIGH)
        val request = request(
            source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            config = modelConfig("entry-key", ReasoningEffort.OFF),
        )

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
        val payload = AgentExternalArchivePayload.from(requireNotNull(resolved.handoff).payload)

        assertTrue(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertEquals("runtime-key", resolved.config.apiKey)
        requireNotNull(payload)
        assertTrue(requireNotNull(payload.thinkingEnabled))
        assertEquals(ReasoningEffort.HIGH, payload.reasoningEffort)
    }

    @Test
    fun etaVoiceRequestKeepsEntryToolSwitchesWhileTakingRuntimeModel() {
        val entry = modelConfig("entry-key", ReasoningEffort.OFF).copy(
            terminalTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            browserTools = false,
        )
        val request = request(source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, config = entry)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        ).config

        assertEquals("runtime-key", resolved.apiKey)
        assertTrue(resolved.terminalTools)
        assertTrue(resolved.deviceSensitiveReadTools)
        assertTrue(resolved.deviceSensitiveActionTools)
        assertFalse(resolved.browserTools)
    }

    @Test
    fun externalAssistantRequestKeepsEntryConfig() {
        val entryConfig = modelConfig("entry-key", ReasoningEffort.OFF)
        val request = request(source = "breeno", config = entryConfig)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        )

        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertSame(request, resolved)
        assertSame(entryConfig, resolved.config)
    }

    @Test
    fun fallbackCapabilitiesCannotSurviveTheWireAsADeclaration() {
        // 3.4.50 P1-1 的盲区钉死：能力未知时 UI 侧的回落形状（放行全档）会被序列化进
        // Bundle，跨进程后与真声明完全同形，发送侧据此把猜出来的档位真发出去。所以能力
        // 必须在 Runtime 侧按同一个解析入口重新解析，解析不到就是「没声明」。
        val fallback = ModelReasoningCapabilities(
            supportedEfforts = ReasoningEffort.entries.filter {
                it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT
            },
            defaultEffort = ReasoningEffort.DEFAULT,
            canDisable = true,
        )
        val entry = modelConfig("entry-key", ReasoningEffort.MAX).copy(
            providerId = "provider-1",
            authMode = ProviderAuthModes.API_KEY,
            providerSourceType = ProviderSourceTypes.OPENAI,
            model = "gpt-6.1-sol",
            reasoningCapabilities = fallback,
        )
        val decoded = AgentRuntimeWire.runRequestFromBundle(
            AgentRuntimeWire.toLegacyBundle(request("breeno", entry)),
        )
        // 盲区本体：回落形状完整过桥，且与真声明无法区分（没有任何随配置传递的标记）。
        assertEquals(fallback, decoded.config.reasoningCapabilities)

        // Runtime 侧重新解析：查不到模型＝没声明＝什么都不发。
        val refreshed = AgentRuntimeRequestConfigResolver.applyDeclaredReasoningCapabilities(decoded) {
                _, _, _ ->
            null
        }
        assertNull(refreshed.config.reasoningCapabilities)
        assertEquals(ReasoningEffort.MAX, refreshed.config.reasoningEffort)
        val body = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(body, refreshed.config)
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("thinking"))

        // 真声明仍然生效：越界档位按声明归一化，然后照常写进请求。
        val declared = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW),
            canDisable = true,
        )
        val normalized = AgentRuntimeRequestConfigResolver.applyDeclaredReasoningCapabilities(decoded) {
                _, _, _ ->
            declared
        }
        assertEquals(declared, normalized.config.reasoningCapabilities)
        assertEquals(ReasoningEffort.LOW, normalized.config.reasoningEffort)
        assertTrue(normalized.config.thinkingEnabled)
        val declaredBody = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(declaredBody, normalized.config)
        assertEquals("low", declaredBody.getString("reasoning_effort"))
    }

    @Test
    fun declaredCapabilitiesRefreshIsANoOpWhenNothingChanged() {
        // 已声明且档位合法时不能平白重建配置：否则下游每次都要重新比较，还会把
        // 「入口进程的档位」和「本地档位」搅在一起（3.4.50 审理 P3-13 的 equals 噪声）。
        val declared = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            canDisable = true,
        )
        val decoded = AgentRuntimeWire.runRequestFromBundle(
            AgentRuntimeWire.toLegacyBundle(
                request(
                    "breeno",
                    modelConfig("entry-key", ReasoningEffort.HIGH)
                        .copy(reasoningCapabilities = declared),
                ),
            ),
        )
        val refreshed = AgentRuntimeRequestConfigResolver.applyDeclaredReasoningCapabilities(decoded) {
                _, _, _ ->
            declared
        }
        assertSame(decoded, refreshed)
    }

    private fun request(
        source: String,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest = AgentRuntimeWire.RunRequest(
        runId = "run-1",
        prompt = "测试",
        config = config,
        images = emptyList(),
        handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = source,
            payload = AgentExternalArchivePayload(
                userText = "测试",
                conversationKey = "conversation-1",
                title = "测试",
            ).toJson(),
        ),
    )

    private fun modelConfig(
        apiKey: String,
        reasoningEffort: ReasoningEffort,
    ): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        model = "test-model",
        systemPrompt = "",
        thinkingEnabled = reasoningEffort.enablesReasoning,
        reasoningEffort = reasoningEffort,
    )
}
