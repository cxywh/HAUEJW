package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 学期进度 / 今天还剩几节 / 下一节课 的纯逻辑测试。
 *
 * 时间基准一律由参数注入（不调 LocalDateTime.now()），否则测试会随运行时刻变红。
 * 期望值都是按校历 2026-08-31 → 2027-01-10 实测算出来的：
 * 总 133 天、19 周；2026-10-11 在第 6 周（已过 42 天 / 31%）。
 */
class SemesterTest {

    private val term = Term(
        xnm = "2026", xnmc = "2026-2027", xqm = "3",
        startDate = LocalDate.parse("2026-08-31"),
        endDate = LocalDate.parse("2027-01-10"),
        maxWeek = 19,
    )

    private fun course(
        name: String = "数字化服装定制", day: Int = 1, s: Int = 5, e: Int = 6,
        weeks: Set<Int> = (1..19).toSet(),
    ) = Course(name, "魏欢", "9A306-307", "南校区", day, s, e, WeekUtils.formatWeeks(weeks), weeks)

    private fun tt(vararg cs: Course) = Timetable(term, Student(), cs.toList())

    private fun d(y: Int, m: Int, dd: Int) = LocalDate.of(y, m, dd)
    private fun t(y: Int, m: Int, dd: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, m, dd, h, mi)

    // ───────────── 学期进度 ─────────────

    @Test
    fun `学期进度与距放假`() {
        val p = semesterProgress(term, d(2026, 10, 11))!!
        assertEquals(6, p.week)
        assertEquals(19, p.totalWeeks)
        assertEquals(133, p.totalDays)
        assertEquals(42, p.daysElapsed)
        assertEquals(31, p.percent)
        assertEquals(91, p.daysToEnd)
        assertTrue(p.started)
        assertFalse(p.ended)
        assertEquals("距放假 91 天", daysToEndText(p))
        assertEquals("第 6 周", weekText(p))
    }

    @Test
    fun `开学当天算第一天`() {
        val p = semesterProgress(term, d(2026, 8, 31))!!
        assertEquals(1, p.week)
        assertEquals(1, p.daysElapsed)
        assertEquals(0, p.percent)          // 133 天里的第 1 天，整数百分比仍是 0
        assertEquals(132, p.daysToEnd)
    }

    @Test
    fun `开学前不算进度`() {
        val p = semesterProgress(term, d(2026, 8, 1))!!
        assertTrue(p.notStarted)
        assertEquals(0, p.week)
        assertEquals(0, p.percent)
        assertEquals(0, p.daysElapsed)
        assertEquals("还没开学", daysToEndText(p))
        assertEquals("未开学", weekText(p))
    }

    @Test
    fun `最后一天与放假后`() {
        val last = semesterProgress(term, d(2027, 1, 10))!!
        assertEquals(100, last.percent)
        assertEquals(0, last.daysToEnd)
        assertEquals(19, last.week)
        assertFalse(last.ended)

        val after = semesterProgress(term, d(2027, 2, 1))!!
        assertTrue(after.ended)
        assertEquals(100, after.percent)
        assertEquals(0, after.daysToEnd)      // 绝不出现负数天
        assertEquals("已放假", daysToEndText(after))
    }

    @Test
    fun `没有校历就不给进度`() {
        assertNull(semesterProgress(Term(maxWeek = 19), d(2026, 10, 11)))
        assertNull(semesterProgress(term.copy(endDate = null), d(2026, 10, 11)))
        // 起止反过来（数据脏）也要挡住，不能算出负天数
        assertNull(semesterProgress(term.copy(startDate = d(2027, 1, 10), endDate = d(2026, 8, 31)), d(2026, 10, 11)))
    }

    // ───────────── 某天的课 ─────────────

    @Test
    fun `只认学期内、同一天的课`() {
        val sat = course(name = "周末课", day = 6)
        val mon = course(name = "周一课", day = 1)
        val t = tt(sat, mon)

        assertEquals(listOf("周末课"), coursesOn(t, d(2026, 10, 10)).map { it.name })  // 周六，第 6 周
        assertEquals(listOf("周一课"), coursesOn(t, d(2026, 10, 12)).map { it.name })  // 周一，第 7 周
        assertTrue("周日没课", coursesOn(t, d(2026, 10, 11)).isEmpty())
        assertTrue("开学前没课", coursesOn(t, d(2026, 7, 1)).isEmpty())
        assertTrue("放假后没课", coursesOn(t, d(2027, 3, 1)).isEmpty())
    }

    @Test
    fun `集中实践不占节次，不算当天的课`() {
        val practice = Course("金工实习", "李工", "", "", 0, 1, 1, "5周", setOf(5))
        assertTrue(coursesOn(tt(practice), d(2026, 9, 28)).isEmpty())
    }

    @Test
    fun `周次不覆盖的周就没有这门课`() {
        // course() 默认周一（day = 1）；2026-08-31 是第 1 周周一
        val onlyFirstTwo = course(weeks = setOf(1, 2))
        assertTrue("第 1 周周一有课", coursesOn(tt(onlyFirstTwo), d(2026, 8, 31)).isNotEmpty())
        assertTrue("第 2 周周一有课", coursesOn(tt(onlyFirstTwo), d(2026, 9, 7)).isNotEmpty())
        assertTrue("第 3 周周一不该有课", coursesOn(tt(onlyFirstTwo), d(2026, 9, 14)).isEmpty())
        assertTrue("第 7 周周一不该有课", coursesOn(tt(onlyFirstTwo), d(2026, 10, 12)).isEmpty())
    }

    // ───────────── 今天还剩 / 正在上 / 下一节 ─────────────

    @Test
    fun `今天还剩几节`() {
        // 周一第 5-6 节 = 14:00-15:35
        val t = tt(course())
        val mon = d(2026, 10, 12)

        assertEquals(1, remainingToday(t, LocalDateTime.of(mon, LocalTime.parse("13:00"))).size)
        assertEquals(1, remainingToday(t, LocalDateTime.of(mon, LocalTime.parse("14:30"))).size)
        assertEquals(1, remainingToday(t, LocalDateTime.of(mon, LocalTime.parse("15:34"))).size)
        assertEquals("15:35 结束，此刻不该再算「还剩」", 0, remainingToday(t, LocalDateTime.of(mon, LocalTime.parse("15:35"))).size)
        assertEquals(0, remainingToday(t, LocalDateTime.of(mon, LocalTime.parse("18:00"))).size)
        assertEquals("没课的一天", 0, remainingToday(t, LocalDateTime.of(d(2026, 10, 11), LocalTime.parse("10:00"))).size)
    }

    @Test
    fun `正在上的课`() {
        val t = tt(course())
        val mon = d(2026, 10, 12)
        assertEquals("数字化服装定制", ongoingNow(t, LocalDateTime.of(mon, LocalTime.parse("14:30")))?.name)
        assertNull("还没开始", ongoingNow(t, LocalDateTime.of(mon, LocalTime.parse("13:00"))))
        assertNull("已经下课", ongoingNow(t, LocalDateTime.of(mon, LocalTime.parse("15:36"))))
    }

    @Test
    fun `下一节课会跨周找`() {
        val t = tt(course())                      // 每周一 14:00
        // 周日 10:00 → 下一节是第二天（周一，第 7 周）14:00
        val fromSun = nextClass(t, t(2026, 10, 11, 10))!!
        assertEquals(d(2026, 10, 12), fromSun.date)
        assertEquals(LocalTime.parse("14:00"), fromSun.begin)
        assertFalse("不是今天", fromSun.isToday)

        // 周一早上 → 今天 14:00
        val fromMonAm = nextClass(t, t(2026, 10, 12, 9))!!
        assertTrue(fromMonAm.isToday)
        assertEquals(LocalTime.parse("14:00"), fromMonAm.begin)

        // 周一 16:00（今天的课已开始过）→ 顺延到下周周一
        val fromMonPm = nextClass(t, t(2026, 10, 12, 16))!!
        assertEquals(d(2026, 10, 19), fromMonPm.date)
        assertFalse(fromMonPm.isToday)
    }

    @Test
    fun `完全没有课就没有下一节`() {
        assertNull(nextClass(tt(), t(2026, 10, 11, 10)))
        // 只有第 1 周有课；从第 3 周开始找，14 天窗口内不该再找到它
        val early = course(weeks = setOf(1))
        assertNull("第 1 周早就过去了，不该被当成下一节", nextClass(tt(early), t(2026, 9, 20, 10)))
    }
}
