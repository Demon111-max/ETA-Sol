package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.initialReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 合并控件的档位表/档位词/停靠位置口径（3.4.47 审计补测）：
 * 空列表就是没档可选、不许自行放行；Minimal 并入 Light；显示词必须与真正生效的档对应。
 */
class ModelEffortGearsTest {

    @Test
    fun gears_emptyAvailableMeansNoGears() {
        assertEquals(emptyList<ReasoningEffort>(), solEffortGears(emptyList()))
    }

    @Test
    fun gears_keepSolOrderAndOnlyAvailable() {
        val gears = solEffortGears(
            listOf(
                ReasoningEffort.MAX,
                ReasoningEffort.HIGH,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.XHIGH,
            ),
        )
        assertEquals(
            listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            gears,
        )
    }

    @Test
    fun gears_minimalFoldsIntoLightSlot() {
        val gears = solEffortGears(listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH))
        assertEquals(listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH), gears)
        assertEquals("Light", solGearLabel(gears[0]))
    }

    @Test
    fun gears_lowWinsOverMinimalWhenBothAvailable() {
        val gears = solEffortGears(listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW))
        assertEquals(listOf(ReasoningEffort.LOW), gears)
    }

    @Test
    fun labels_matchSolWordsAndNullForOffAndDefault() {
        assertEquals("Light", solGearLabel(ReasoningEffort.LOW))
        assertEquals("Medium", solGearLabel(ReasoningEffort.MEDIUM))
        assertEquals("High", solGearLabel(ReasoningEffort.HIGH))
        assertEquals("Extra High", solGearLabel(ReasoningEffort.XHIGH))
        assertEquals("Max", solGearLabel(ReasoningEffort.MAX))
        assertNull(solGearLabel(ReasoningEffort.OFF))
        assertNull(solGearLabel(ReasoningEffort.DEFAULT))
    }

    @Test
    fun index_defaultFallsBackToHighPosition() {
        val gears = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
        assertEquals(2, solGearIndex(ReasoningEffort.DEFAULT, gears))
        assertEquals(2, solGearIndex(ReasoningEffort.HIGH, gears))
    }

    @Test
    fun index_missingTargetConvergesDownwardLikeNormalize() {
        val gears = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)
        // XHigh 不在表上：向下收敛到不超过它的最高档 High（与 normalize 同方向）。
        assertEquals(1, solGearIndex(ReasoningEffort.XHIGH, gears))
        // OFF 没有位置：回到第一档（拖动即开启的停靠口径由调用方处理，这里只验不越界）。
        assertEquals(0, solGearIndex(ReasoningEffort.OFF, gears))
        // 分水岭用例：目标下方有 Medium、上方有 Max——向下收敛停 Medium；
        // 旧的「按 rank 距离取最近」会停到 Max，与状态层 normalize 的结果打架。
        val splitGears = listOf(ReasoningEffort.MEDIUM, ReasoningEffort.MAX)
        assertEquals(0, solGearIndex(ReasoningEffort.XHIGH, splitGears))
        // 目标下方一档都没有：落第一档，不许越界也不许凭空造档。
        val highGears = listOf(ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        assertEquals(0, solGearIndex(ReasoningEffort.HIGH, highGears))
    }

    @Test
    fun index_defaultWithoutHighFallsToHighestBelowHigh() {
        // DEFAULT 兜底 High 位；没有 High 时停在不超过 High 的最高档（Medium），
        // 不许停到最低档把「默认≈High」的口径丢了。
        val gears = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM)
        assertEquals(1, solGearIndex(ReasoningEffort.DEFAULT, gears))
    }

    @Test
    fun index_emptyGearsIsZero() {
        assertEquals(0, solGearIndex(ReasoningEffort.HIGH, emptyList()))
    }

    @Test
    fun initialEffort_usesDeclaredDefaultForNewConversations() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            defaultEffort = ReasoningEffort.MEDIUM,
            canDisable = true,
        )
        assertEquals(
            ReasoningEffort.MEDIUM,
            initialReasoningEffort(capabilities, ReasoningEffort.DEFAULT),
        )
    }

    @Test
    fun initialEffort_fallsBackToHighAndNormalizes() {
        // 没声明默认档：兜底 High，且要过 normalize 落进可选集。
        val noDefault = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            canDisable = true,
        )
        assertEquals(
            ReasoningEffort.HIGH,
            initialReasoningEffort(noDefault, ReasoningEffort.DEFAULT),
        )
        // 声明的默认档不在可选集里：normalize 向下收敛，不许原样放行。
        val staleDefault = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MAX),
            defaultEffort = ReasoningEffort.XHIGH,
        )
        assertEquals(
            ReasoningEffort.LOW,
            initialReasoningEffort(staleDefault, ReasoningEffort.DEFAULT),
        )
    }

    @Test
    fun initialEffort_keepsUserChoiceAndUnknownStaysDefault() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            defaultEffort = ReasoningEffort.HIGH,
            canDisable = true,
        )
        // 用户已经选过档：不许播种覆盖。
        assertEquals(
            ReasoningEffort.LOW,
            initialReasoningEffort(capabilities, ReasoningEffort.LOW),
        )
        // 能力未知：不猜，保持 DEFAULT（服务商默认）。
        assertEquals(
            ReasoningEffort.DEFAULT,
            initialReasoningEffort(null, ReasoningEffort.DEFAULT),
        )
    }
}
