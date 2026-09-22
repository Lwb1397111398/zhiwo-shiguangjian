package com.zhiwo.shiguangjian.data.settings

import org.junit.Assert.*
import org.junit.Test

class ScheduleDisplayPrefsTest {

    @Test fun P01_编码解码往返一致() {
        val p = ScheduleDisplayPrefs(
            order = ScheduleSectionId.values().reversed(),
            hidden = setOf(ScheduleSectionId.MISSED, ScheduleSectionId.UNFILLED)
        )
        assertEquals(p, ScheduleDisplayPrefs.parse(ScheduleDisplayPrefs.encode(p)))
    }

    @Test fun P02_空值与坏JSON回退默认() {
        listOf(null, "", "   ", "{不是json", "[]").forEach {
            assertEquals(it, ScheduleDisplayPrefs.DEFAULT, ScheduleDisplayPrefs.parse(it))
        }
    }

    @Test fun P03_缺失的段追加到末尾() {
        val p = ScheduleDisplayPrefs.parse("""{"v":1,"order":["DAILY_FIXED"],"hidden":[]}""")
        assertEquals(ScheduleSectionId.DAILY_FIXED, p.order.first())
        assertEquals(ScheduleSectionId.values().toList().sortedBy { p.order.indexOf(it) }, p.order)
        assertEquals(ScheduleSectionId.values().size, p.order.size)
    }

    @Test fun P04_未知段名被丢弃() {
        val p = ScheduleDisplayPrefs.parse("""{"order":["DAILY_FIXED","来自未来版本"],"hidden":["NOPE"]}""")
        assertTrue(p.order.none { it.name == "来自未来版本" })
        assertEquals(ScheduleSectionId.values().toList(), p.order)
        assertTrue(p.hidden.isEmpty())
    }

    @Test fun P05_全部隐藏时回退为不隐藏防止页面空白() {
        val all = ScheduleSectionId.values().joinToString(",") { """"${it.name}"""" }
        val p = ScheduleDisplayPrefs.parse("""{"order":[],"hidden":[$all]}""")
        assertTrue("至少要留一段可见", p.visibleOrder.isNotEmpty())
    }

    @Test fun P06_重复段名只保留一次() {
        val p = ScheduleDisplayPrefs.parse("""{"order":["DAILY_FIXED","DAILY_FIXED","ADHOC_TODAY"]}""")
        assertEquals(p.order.distinct(), p.order)
    }

    @Test fun P07_移动顺序到边界外不动() {
        val order = ScheduleSectionId.values().toList()
        assertEquals(order, ScheduleDisplayPrefs.move(order, ScheduleSectionId.DAILY_FIXED, -1))
        assertEquals(order, ScheduleDisplayPrefs.move(order, ScheduleSectionId.UNFILLED, 1))
        val moved = ScheduleDisplayPrefs.move(order, ScheduleSectionId.DONE_TODAY, -2)
        assertEquals(order.toMutableList().also { it.remove(ScheduleSectionId.DONE_TODAY); it.add(6, ScheduleSectionId.DONE_TODAY) }, moved)
    }

    @Test fun P08_visibleOrder与hidden一致() {
        val p = ScheduleDisplayPrefs(ScheduleSectionId.values().toList(), setOf(ScheduleSectionId.GOALS))
        assertFalse(ScheduleSectionId.GOALS in p.visibleOrder)
        assertEquals(ScheduleSectionId.values().size - 1, p.visibleOrder.size)
    }
}
