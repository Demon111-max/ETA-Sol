package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧边栏抽屉分组（3.4.44 C1 回归护栏）。
 *
 * 3.4.43 的分组键是 `Day(epochDay, sampleMillis)`：data class 相等性含 sampleMillis，于是同一天里
 * updatedAtMillis 不同的两条会话被当成两个「不同 section」→ 同一天裂成多个组头；而组头的
 * LazyColumn key 只含 epochDay（[sectionKey]）→ 多个组头算出同一个 key → LazyColumn 抛
 * `Key "section-day-…" was used multiple times` 直接闪退（真机上 Forge 模式点 Assist 必现）。
 *
 * 这里直接对分组与 key 生成做断言：同一天只出一个组、所有组头 key 互不相同。
 */
class ConversationDrawerGroupingTest {

    @Test
    fun sameDayConversationsWithDifferentTimestampsShareOneDayGroup() {
        val now = todayNoonMillis()
        val conversations = listOf(
            conversation(id = "conv-a", updatedAtMillis = now),
            // 同一天里更早的时刻：3.4.43 就是这一条把「今天」裂成了两组。
            conversation(id = "conv-b", updatedAtMillis = now - TimeUnit.HOURS.toMillis(3)),
            conversation(id = "conv-c", updatedAtMillis = now - TimeUnit.MINUTES.toMillis(5)),
        )

        val groups = conversations.groupForDrawer()

        assertEquals("同一天只应有一个日期组", 1, groups.size)
        assertEquals(
            "同一天的三条会话都应留在该组内",
            setOf("conv-a", "conv-b", "conv-c"),
            groups.single().items.map { it.id }.toSet(),
        )
        assertTrue(
            "同一天的分组应归到本地日期天",
            groups.single().section is ConversationDrawerSection.Day,
        )
    }

    @Test
    fun sectionKeysAreUniqueAcrossAllGroups() {
        val now = todayNoonMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        val conversations = listOf(
            conversation(id = "pinned-1", updatedAtMillis = now, isPinned = true),
            conversation(id = "today-1", updatedAtMillis = now),
            conversation(id = "today-2", updatedAtMillis = now - TimeUnit.HOURS.toMillis(6)),
            conversation(id = "yesterday-1", updatedAtMillis = now - day),
            conversation(id = "yesterday-2", updatedAtMillis = now - day - TimeUnit.HOURS.toMillis(2)),
            conversation(id = "old-1", updatedAtMillis = now - 5 * day),
        )

        val keys = conversations.groupForDrawer().map { sectionKey(it.section) }

        assertEquals("组头 key 必须互不相同（相同即 LazyColumn 闪退）", keys.size, keys.toSet().size)
        assertEquals("置顶 1 组 + 3 个日期组", 4, keys.size)
        assertTrue("置顶组 key 应存在", keys.contains("pinned"))
        assertTrue("今天的组头 key 应按本地日期生成", keys.any { it == expectedDayKey(now) })
        assertTrue("昨天的组头 key 也应存在", keys.any { it == expectedDayKey(now - day) })
    }

    @Test
    fun interleavedSectionsMergeIntoOneGroupPerSection() {
        val now = todayNoonMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        // 输入不是按 section 排好的：今天的会话被昨天的隔开。
        // 只合并相邻项的实现会把「今天」裂成两组、组头 key 撞车闪退（3.4.44 C1 的另一半）。
        val conversations = listOf(
            conversation(id = "today-1", updatedAtMillis = now),
            conversation(id = "yesterday-1", updatedAtMillis = now - day),
            conversation(id = "today-2", updatedAtMillis = now - TimeUnit.HOURS.toMillis(2)),
        )

        val groups = conversations.groupForDrawer()

        assertEquals("交错输入也只应有两个组", 2, groups.size)
        val todayGroup = groups.first { it.section is ConversationDrawerSection.Day }
        assertEquals(
            setOf("today-1", "today-2"),
            todayGroup.items.map { it.id }.toSet(),
        )
        assertEquals(
            "组头 key 仍须互不相同",
            groups.size,
            groups.map { sectionKey(it.section) }.toSet().size,
        )
    }

    @Test
    fun missingTimestampFallsIntoTodayGroup() {
        val now = todayNoonMillis()
        val conversations = listOf(
            conversation(id = "old-1", updatedAtMillis = now - TimeUnit.DAYS.toMillis(5)),
            conversation(id = "timeless", updatedAtMillis = 0L),
        )

        val groups = conversations.groupForDrawer()

        val timelessGroup = groups.first { group -> group.items.any { it.id == "timeless" } }
        assertEquals(
            "时间缺失（0）应按今天归组",
            expectedDayKey(now),
            sectionKey(timelessGroup.section),
        )
    }

    @Test
    fun interleavedPinnedStayInSinglePinnedGroup() {
        val now = todayNoonMillis()
        val conversations = listOf(
            conversation(id = "pinned-1", updatedAtMillis = now, isPinned = true),
            conversation(id = "today-1", updatedAtMillis = now),
            conversation(id = "pinned-2", updatedAtMillis = now - TimeUnit.HOURS.toMillis(1), isPinned = true),
        )

        val groups = conversations.groupForDrawer()

        assertEquals(2, groups.size)
        val pinnedGroup = groups.first { it.section == ConversationDrawerSection.Pinned }
        assertEquals(listOf("pinned-1", "pinned-2"), pinnedGroup.items.map { it.id })
    }

    @Test
    fun pinnedConversationsStayInSinglePinnedGroup() {
        val now = todayNoonMillis()
        val conversations = listOf(
            conversation(id = "pinned-1", updatedAtMillis = now, isPinned = true),
            conversation(id = "pinned-2", updatedAtMillis = now - TimeUnit.DAYS.toMillis(3), isPinned = true),
            conversation(id = "today-1", updatedAtMillis = now),
        )

        val groups = conversations.groupForDrawer()

        assertEquals(2, groups.size)
        val pinnedGroup = groups.first { it.section == ConversationDrawerSection.Pinned }
        assertEquals(listOf("pinned-1", "pinned-2"), pinnedGroup.items.map { it.id })
    }

    /**
     * 时间锚点固定在本地正午：用 System.currentTimeMillis() 加减几小时推「同一天」，
     * 在午夜后几小时内跑测试就会跨到前一天、组数多 1（2026-11-19 00:0x 实测踩中）。
     */
    private fun todayNoonMillis(): Long =
        LocalDate.now(ZoneId.systemDefault())
            .atTime(LocalTime.NOON)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun expectedDayKey(timestampMillis: Long): String {
        val epochDay = Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        return sectionKey(ConversationDrawerSection.Day(epochDay))
    }

    private fun conversation(
        id: String,
        updatedAtMillis: Long,
        isPinned: Boolean = false,
    ): ConversationSummaryUi = ConversationSummaryUi(
        id = id,
        title = id,
        preview = "",
        timeLabel = "",
        updatedAtMillis = updatedAtMillis,
        mode = ConversationModeUi.Chat,
        isPinned = isPinned,
    )
}
