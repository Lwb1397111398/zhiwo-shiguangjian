package com.zhiwo.shiguangjian.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * F-a：闹钟码换算。v13 的 `t+10000` 配 "+1 副码" 会让任务 t 的第二枪正好撞上任务 t+1 的主码，
 * 取消一个任务顺手掐掉邻居的提醒。这里把"不许再撞"钉成用例。
 */
class ReminderIdsTest {

    @Test fun A01_相邻任务的码互不相同() {
        val codes = (0L..2000L).map { ReminderIds.of(it) }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun A02_新码解得回任务且反解覆盖全部新码() {
        for (taskId in listOf(0L, 1L, 42L, 100_000L, 5_000_000L)) {
            assertEquals(taskId, ReminderIds.taskIdOf(ReminderIds.of(taskId)))
        }
    }

    @Test fun A03_新码不与固定闹钟及旧码段重叠() {
        val fixed = setOf(1001, 1002, 1003, 1004)
        val newCodes = (0L..100_000L).map { ReminderIds.of(it) }.toSet()
        assertEquals(emptySet<Int>(), fixed.filter { it in newCodes }.toSet())
        // 旧公式（含 +1 副码）能产生的码都在基址下面
        val legacyCodes = (0L..100_000L).flatMap { ReminderIds.legacyIds(it) }.toSet()
        assertEquals(emptySet<Int>(), legacyCodes.filter { it in newCodes }.toSet())
        assertEquals(1_000_000, newCodes.min())
    }

    @Test fun A04_固定闹钟与旧码都解不出任务() {
        assertNull(ReminderIds.taskIdOf(1001))
        assertNull(ReminderIds.taskIdOf(10000))
        assertNull(ReminderIds.taskIdOf(0))
        assertNull(ReminderIds.taskIdOf(-1))
    }

    @Test fun A05_旧码清理枚举出两把枪() {
        assertEquals(listOf(10000, 10001), ReminderIds.legacyIds(0))
        assertEquals(listOf(10042, 10043), ReminderIds.legacyIds(42))
    }

    @Test fun A06_超出可编码范围直接拒绝而不是悄悄撞号() {
        assertThrows(IllegalArgumentException::class.java) { ReminderIds.of(-1) }
        assertThrows(IllegalArgumentException::class.java) { ReminderIds.of(Int.MAX_VALUE.toLong()) }
    }

    @Test fun A07_重复闹钟首枪必须落在未来且保持节律() {
        val day = 86_400_000L
        // 锚点在过去 100 天之外（v13 迁移那天的老任务）：不能只加一天，否则首枪仍在过去，
        // setInexactRepeating 会立刻补响并把提醒永久挪到"开机那一刻"
        val anchor = 1_700_000_000_000L
        val now = anchor + 100 * day + 3_600_000L
        val first = ReminderIds.nextRepeatingTrigger(anchor, day, now)
        assertTrue("首枪必须在未来: first=$first now=$now", first > now)
        assertEquals("节律不能变（与锚点相差整天）", 0L, (first - anchor) % day)
        assertTrue("最多多等不到一个周期", first - now <= day)
    }

    @Test fun A08_未来的锚点原样保留_周重复同理() {
        val week = 86_400_000L * 7
        assertEquals(2_000L, ReminderIds.nextRepeatingTrigger(2_000L, week, 1_000L))
        val anchor = 0L
        val now = anchor + 31 * 86_400_000L
        val first = ReminderIds.nextRepeatingTrigger(anchor, week, now)
        assertTrue("周节律保持", first % week == 0L)
        assertTrue(first > now)
    }
}
