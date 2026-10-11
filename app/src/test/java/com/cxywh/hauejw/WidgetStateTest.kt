package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 桌面小组件"该显示什么"的测试。
 *
 * 内容被抽成纯函数 [widgetState]，就是为了能这样钉住它 —— RemoteViews 本身没法单测，
 * 但它显示的那几行字完全可以。所有数字都按校历 2026-08-31 → 2027-01-10（133 天 / 19 周）实测。
 */
class WidgetStateTest {

    private val term = Term(
        xnm = "2026", xnmc = "2026-2027", xqm = "3",
        startDate = LocalDate.parse("2026-08-31"),
        endDate = LocalDate.parse("2027-01-10"),
        maxWeek = 19,
    )

    private fun course(
        name: String, day: Int = 1, s: Int = 5, e: Int = 6,
        room: String = "9A306-307", weeks: Set<Int> = (1..19).toSet(),
    ) = Course(name, "魏老师", room, "南校区", day, s, e, WeekUtils.formatWeeks(weeks), weeks)

    private fun tt(vararg cs: Course) = Timetable(term, Student(), cs.toList())
    private fun d(y: Int, m: Int, dd: Int) = LocalDate.of(y, m, dd)
    private fun t(y: Int, m: Int, dd: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, m, dd, h, mi)

    private val 高数 = course("高等数学A1", day = 1, s = 1, e = 2)
    private val 服装 = course("数字化服装定制", day = 1, s = 5, e = 6)

    // ───────────── 空态 ─────────────

    @Test
    fun `没有课表时说清楚怎么办`() {
        val st = widgetState(null, t(2026, 10, 12, 13))
        assertEquals("教务助手", st.week)
        assertEquals("还没有课表", st.term)
        assertEquals("打开 App 登录一次，课表就会显示在这里", st.empty)
        assertTrue("空态不该有课行", st.rows.isEmpty())
        assertEquals("", st.today)
        assertEquals("", st.next)
        assertEquals("10月12日 周一", st.date)
    }

    // ───────────── 有课的正常一天 ─────────────

    @Test
    fun `一天的课表与今天还剩几节`() {
        val st = widgetState(tt(高数, 服装), t(2026, 10, 12, 13))
        assertNull(st.empty)
        assertEquals("第 7 周", st.week)
        assertEquals("学期已过 32% · 距放假 90 天", st.term)
        assertEquals("今天还剩 1 节", st.today)          // 上午那节 10:05 就结束了
        assertEquals(2, st.rows.size)
        // 上午那节已经上完 → 灰
        assertEquals(WidgetRow("08:30", "高等数学A1", "9A306-307", past = true), st.rows[0])
        assertEquals(WidgetRow("14:00", "数字化服装定制", "9A306-307", past = false), st.rows[1])
        assertEquals("下一节 · 今天 14:00 数字化服装定制 · 9A306-307", st.next)
    }

    @Test
    fun `正在上课时也报出还剩几节`() {
        val st = widgetState(tt(高数, 服装), t(2026, 10, 12, 14, 30))
        assertEquals("正在上课 · 今天还剩 1 节", st.today)
        assertFalse("正在上的不算已上完", st.rows[1].past)
    }

    @Test
    fun `今天的课上完了`() {
        val st = widgetState(tt(高数, 服装), t(2026, 10, 12, 16))
        assertEquals("今天的课已上完", st.today)
        assertTrue("两节都该是灰的", st.rows.all { it.past })
        assertEquals("下一节 · 周一 08:30 高等数学A1 · 9A306-307", st.next)   // 顺延到下周
    }

    @Test
    fun `一天超过四门时第 4 行改成还有几门`() {
        // 周二排满 5 门
        val cs = arrayOf(
            course("课一", day = 2, s = 1, e = 2), course("课二", day = 2, s = 3, e = 4),
            course("课三", day = 2, s = 5, e = 6), course("课四", day = 2, s = 7, e = 8),
            course("课五", day = 2, s = 9, e = 10),
        )
        val st = widgetState(tt(*cs), t(2026, 10, 13, 7))
        assertEquals("今天还剩 5 节", st.today)
        assertEquals("布局只有 4 行：3 行课 + 1 行提示", 4, st.rows.size)
        assertFalse("前 3 行是真课", st.rows[2].note)
        assertTrue(st.rows[3].note)
        assertEquals("还有 2 门课…", st.rows[3].name)
        assertEquals("", st.rows[3].time)
    }

    // ───────────── 没课的那天 / 特殊时期 ─────────────

    @Test
    fun `周日没课时报出下一节课`() {
        val st = widgetState(tt(高数, 服装), t(2026, 10, 11, 10))
        assertEquals("10月11日 周日", st.date)
        assertEquals("第 6 周", st.week)
        assertEquals("学期已过 31% · 距放假 91 天", st.term)
        assertEquals("今天没有课", st.today)
        assertTrue(st.rows.isEmpty())
        assertEquals("下一节 · 周一 08:30 高等数学A1 · 9A306-307", st.next)
    }

    @Test
    fun `教室为空时下一节不带那个点`() {
        val noRoom = course("形势与政策", day = 1, s = 1, e = 2, room = "")
        val st = widgetState(tt(noRoom), t(2026, 10, 11, 10))
        assertEquals("下一节 · 周一 08:30 形势与政策", st.next)
    }

    @Test
    fun `还没开学`() {
        val st = widgetState(tt(高数), t(2026, 8, 1, 10))
        assertEquals("未开学", st.week)
        assertEquals("开学日期 2026-08-31", st.term)
        assertEquals("今天没有课", st.today)
        assertTrue(st.rows.isEmpty())
        assertEquals("接下来两周没有课", st.next)
    }

    @Test
    fun `放假之后`() {
        val st = widgetState(tt(高数), t(2027, 2, 1, 10))
        assertEquals("已结课", st.week)
        assertEquals("学期已结束 · 已放假", st.term)
        assertEquals("今天没有课", st.today)
        assertEquals("接下来两周没有课", st.next)
    }

    @Test
    fun `导入的课表没有校历就说清楚，不装成今天没课`() {
        val noCal = Timetable(Term(maxWeek = 19), Student(), listOf(高数))
        val st = widgetState(noCal, t(2026, 10, 12, 13))
        assertEquals("课表", st.week)
        assertEquals("导入的课表没有校历，无法判断今天该上什么课", st.term)
        assertEquals("打开 App 查看本周课表", st.today)
        assertTrue("没有校历就无法把课挂到具体某一天", st.rows.isEmpty())
        assertEquals("", st.next)
    }

    @Test
    fun `有开学日没结束日时，课照常显示`() {
        // 周次只需要开学日；距放假才需要结束日
        val half = Timetable(term.copy(endDate = null), Student(), listOf(高数, 服装))
        val st = widgetState(half, t(2026, 10, 12, 13))
        assertEquals("第 7 周", st.week)
        assertEquals("没有学期结束日期，算不出距放假", st.term)
        assertEquals("今天还剩 1 节", st.today)
        assertEquals(2, st.rows.size)
    }
}
