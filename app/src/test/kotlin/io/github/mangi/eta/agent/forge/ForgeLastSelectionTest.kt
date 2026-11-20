package io.github.mangi.eta.agent.forge

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.UnknownReasoningCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Forge 新对话沿用上次模型与档位（3.4.52）的决策与存档格式。
 *
 * 这些判断决定用户新开对话时看到什么模型、发出去什么档位：判错的表现是「模型被悄悄换掉」
 * 或「档位撞服务商硬表整轮失败」，所以恢复/退回/归一化三类各自钉住。
 */
class ForgeLastSelectionTest {

    private fun declared(vararg efforts: ReasoningEffort, canDisable: Boolean = false) =
        ModelReasoningCapabilities(
            supportedEfforts = efforts.toList(),
            defaultEffort = ReasoningEffort.HIGH,
            canDisable = canDisable,
            mandatory = !canDisable,
        )

    // ---------- 存档格式 ----------

    @Test
    fun selectionSurvivesAnEncodeDecodeRoundTrip() {
        val selection = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.XHIGH)

        assertEquals(selection, ForgeLastSelectionStore.parse(ForgeLastSelectionStore.encode(selection)))
    }

    @Test
    fun unreadableArchiveIsTreatedAsNoArchiveInsteadOfThrowing() {
        // 存档只是「省一次手选」，任何坏内容都不许影响 Forge 启动：一律当没有。
        assertNull(ForgeLastSelectionStore.parse(null))
        assertNull(ForgeLastSelectionStore.parse(""))
        assertNull(ForgeLastSelectionStore.parse("   "))
        assertNull(ForgeLastSelectionStore.parse("{not json"))
        assertNull(ForgeLastSelectionStore.parse("[]"))
        // 缺键、空模型 id、认不出的档位名：都不猜，直接作废。
        assertNull(ForgeLastSelectionStore.parse("""{"reasoningEffort":"high"}"""))
        assertNull(ForgeLastSelectionStore.parse("""{"modelId":"  ","reasoningEffort":"high"}"""))
        assertNull(ForgeLastSelectionStore.parse("""{"modelId":"model-a"}"""))
        assertNull(ForgeLastSelectionStore.parse("""{"modelId":"model-a","reasoningEffort":"turbo"}"""))
    }

    // ---------- 恢复 / 退回 ----------

    @Test
    fun archiveIsRestoredWhenTheModelIsStillUsable() {
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.HIGH)
        val model = ForgeRestoreModel(
            modelId = "model-a",
            rowId = "row-a",
            capabilities = declared(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        )

        assertEquals(
            ForgeRestoreDecision.Restore(modelId = "model-a", rowId = "row-a", effort = ReasoningEffort.HIGH),
            forgeRestoreDecision(saved, model),
        )
    }

    @Test
    fun missingArchiveOrMissingModelFallsBackToTheSharedSelection() {
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.HIGH)
        val model = ForgeRestoreModel(modelId = "model-a", rowId = "row-a", capabilities = declared(ReasoningEffort.HIGH))

        // 没有存档：走今天的规矩（共享当前模型 + 声明播种）。
        assertEquals(ForgeRestoreDecision.Keep, forgeRestoreDecision(null, model))
        // 存档模型没了/被禁用/Forge 用不了：整份作废，同样退回。
        assertEquals(ForgeRestoreDecision.Keep, forgeRestoreDecision(saved, null))
        assertEquals(ForgeRestoreDecision.Keep, forgeRestoreDecision(null, null))
    }

    @Test
    fun archiveForAnotherModelNeverOverridesTheCurrentOne() {
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.HIGH)
        val model = ForgeRestoreModel(modelId = "model-b", rowId = "row-b", capabilities = declared(ReasoningEffort.HIGH))

        assertEquals(ForgeRestoreDecision.Keep, forgeRestoreDecision(saved, model))
    }

    // ---------- 归一化 ----------

    @Test
    fun archivedEffortIsNarrowedToWhatTheModelStillSupports() {
        // 模型后来收窄了档位集：收敛到可选档，不许把不可选档发给服务商（硬表会抛错整轮失败）。
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.MAX)
        val model = ForgeRestoreModel(modelId = "model-a", rowId = "row-a", capabilities = declared(ReasoningEffort.LOW))

        assertEquals(
            ForgeRestoreDecision.Restore(modelId = "model-a", rowId = "row-a", effort = ReasoningEffort.LOW),
            forgeRestoreDecision(saved, model),
        )
    }

    @Test
    fun archivedOffIsNarrowedWhenTheModelCannotDisableThinking() {
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.OFF)
        val model = ForgeRestoreModel(modelId = "model-a", rowId = "row-a", capabilities = declared(ReasoningEffort.LOW))

        assertEquals(
            ForgeRestoreDecision.Restore(modelId = "model-a", rowId = "row-a", effort = ReasoningEffort.DEFAULT),
            forgeRestoreDecision(saved, model),
        )
    }

    @Test
    fun unknownCapabilitiesKeepTheArchivedEffortAsIs() {
        // 能力未知时 Forge 放行全部档位（回落表），存档档位原样用——这正是「别再看到默认」的场景。
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.MAX)
        val model = ForgeRestoreModel(modelId = "model-a", rowId = "row-a", capabilities = UnknownReasoningCapabilities)

        assertEquals(
            ForgeRestoreDecision.Restore(modelId = "model-a", rowId = "row-a", effort = ReasoningEffort.MAX),
            forgeRestoreDecision(saved, model),
        )
    }

    @Test
    fun explicitlyUnsupportedModelRestoresTheModelWithoutAnyEffort() {
        // 明确声明不支持思考（effective 能力为 null）：模型照旧恢复，档位落 DEFAULT、不发 variant。
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.HIGH)
        val model = ForgeRestoreModel(modelId = "model-a", rowId = "row-a", capabilities = null)

        assertEquals(
            ForgeRestoreDecision.Restore(modelId = "model-a", rowId = "row-a", effort = ReasoningEffort.DEFAULT),
            forgeRestoreDecision(saved, model),
        )
    }

    // ---------- 同一进程内开新对话 ----------

    @Test
    fun newConversationKeepsTheArchivedEffortForTheSameModel() {
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.HIGH)
        val capabilities = declared(ReasoningEffort.LOW, ReasoningEffort.HIGH)

        assertEquals(
            ReasoningEffort.HIGH,
            forgeArchivedEffortForModel("model-a", saved, capabilities),
        )
        // 存档档位超出当前模型能力：收敛，不抛错。
        assertEquals(
            ReasoningEffort.LOW,
            forgeArchivedEffortForModel("model-a", saved, declared(ReasoningEffort.LOW)),
        )
        // 明确不支持思考：落 DEFAULT。
        assertEquals(
            ReasoningEffort.DEFAULT,
            forgeArchivedEffortForModel("model-a", saved, capabilities = null),
        )
    }

    @Test
    fun newConversationFallsBackToDeclarationSeedingForOtherModels() {
        val saved = ForgeLastSelection(modelId = "model-a", effort = ReasoningEffort.HIGH)
        val capabilities = declared(ReasoningEffort.HIGH)

        // 换过模型 / 还没有模型 / 没有存档：交给声明播种（返回 null）。
        assertNull(forgeArchivedEffortForModel("model-b", saved, capabilities))
        assertNull(forgeArchivedEffortForModel("", saved, capabilities))
        assertNull(forgeArchivedEffortForModel(null, saved, capabilities))
        assertNull(forgeArchivedEffortForModel("model-a", null, capabilities))
    }
}
