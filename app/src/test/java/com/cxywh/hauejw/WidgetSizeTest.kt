package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 小组件两种形态：2x2 简版显示什么、详细版按可用高度给几行课。
 * 实测校历：2026-08-31（周一）开学 → 2027-01-10，19 周；2026-10-12 是第 7 周周一。
 */
class WidgetSizeTest {

    private val term = Term(
        xnm = "2026", xnmc = "2026-2027", xqm = "3",
        startDate = LocalDate.parse("2026-08-31"),
        endDate = LocalDate.parse("2027-01-10"),
        maxWeek = 19,
    )

    private fun course(
        name: String, day: Int = 1, s: Int = 5, e: Int = 6,
        weeks: Set<Int> = (1..19).toSet(),
    ) = Course(name, "魏老师", "9A402-403", "南校区", day, s, e, WeekUtils.formatWeeks(weeks), weeks)

    private fun tt(vararg cs: Course) = Timetable(term, Student(), cs.toList())

    /** 第 7 周周一（2026-10-12）的某个时刻 */
    private fun t(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 12, h, mi)

    // ───────────── 2x2 简版 ─────────────

    @Test
    fun `没课表时简版给一句话`() {
        val s = smallState(null, t(9))
        assertEquals("", s.today)
        assertNotNull(s.empty)
    }

    @Test
    fun `简版显示今天还剩几节和下一节`() {
        val s = smallState(
            tt(course("数字化服装定制", s = 5, e = 6), course("高等数学A1", s = 1, e = 2)),
            t(8),
        )
        // 08:00：1-2 节还没开始，两节都在等着
        assertEquals("今天还剩 2 节", s.today)
        assertEquals("今天 08:30", s.nextWhen)
        assertEquals("高等数学A1", s.nextName)
    }

    @Test
    fun `上完最后两节后简版说已上完`() {
        val s = smallState(tt(course("高等数学A1", s = 1, e = 2)), t(11))
        assertEquals("今天的课已上完", s.today)
    }

    @Test
    fun `当天没课时简版说今天没有课`() {
        val s = smallState(tt(course("高等数学A1", day = 3, s = 1, e = 2)), t(10))
        assertEquals("今天没有课", s.today)
    }

    @Test
    fun `导入的课表没校历时简版说清原因`() {
        val noCal = Timetable(term.copy(startDate = null, endDate = null), Student(), listOf(course("甲")))
        val s = smallState(noCal, t(9))
        assertTrue(s.empty!!.contains("没有校历"))
    }

    // ───────────── 按高度决定行数 ─────────────

    @Test
    fun `行数按高度给`() {
        assertEquals(4, widgetRowsFor(250, echoOn = false))
        assertEquals(3, widgetRowsFor(170, echoOn = false))
        assertEquals(2, widgetRowsFor(140, echoOn = false))
        assertEquals(1, widgetRowsFor(110, echoOn = false))
    }

    @Test
    fun `开了回声洞同样高度少一行`() {
        assertEquals(4, widgetRowsFor(240, echoOn = false))
        assertEquals(3, widgetRowsFor(240, echoOn = true))
    }

    // ───────────── 详细版按行数收窄 ─────────────

    @Test
    fun `详细版只有两行时最后一行变成还有几门课`() {
        val cs = (1..4).map { course("课$it", s = it * 2 - 1, e = it * 2) }
        val full = widgetState(tt(*cs.toTypedArray()), t(7), maxRows = 4)
        assertEquals(4, full.rows.size)

        val narrow = widgetState(tt(*cs.toTypedArray()), t(7), maxRows = 2)
        assertEquals(2, narrow.rows.size)
        assertTrue(narrow.rows.last().note)
    }
}
