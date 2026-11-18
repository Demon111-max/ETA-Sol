package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.ReasoningEffort
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
    fun index_missingTargetConvergesToNearestRank() {
        val gears = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)
        // XHigh 不在表上：rank 上离 High 更近，停靠 High。
        assertEquals(1, solGearIndex(ReasoningEffort.XHIGH, gears))
        // OFF 没有位置：回到第一档（拖动即开启的停靠口径由调用方处理，这里只验不越界）。
        assertEquals(0, solGearIndex(ReasoningEffort.OFF, gears))
    }

    @Test
    fun index_emptyGearsIsZero() {
        assertEquals(0, solGearIndex(ReasoningEffort.HIGH, emptyList()))
    }
}
