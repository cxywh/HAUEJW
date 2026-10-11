package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 课前提醒排程的纯逻辑测试。
 *
 * plan() 不碰 Android API（时间基准由 from 参数注入，不用 LocalDateTime.now()），
 * 所以能直接在 JVM 上跑。这几条锁住的是最容易悄悄坏掉的东西：
 * 排程窗口、提前量、以及"过去/实践课不要排"。
 */
class ReminderTest {

    private val term = Term(
        xnm = "2026", xnmc = "2026-2027", xqm = "3",
        startDate = LocalDate.parse("2026-08-31"),
        endDate = LocalDate.parse("2027-01-10"),
        maxWeek = 19,
    )

    private fun course(
        name: String = "数字化服装定制", day: Int = 1, s: Int = 5, e: Int = 6,
        weeks: Set<Int> = (1..19).toSet(),
    ) = Course(name, "魏老师", "7B401", "南校区", day, s, e, WeekUtils.formatWeeks(weeks), weeks)

    private fun tt(vararg cs: Course) = Timetable(term, Student(), cs.toList())

    private fun at(ms: Long): LocalDateTime =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** 排程窗口不得超过两周（14 天）—— 用户明确要求「最多两周」 */
    @Test
    fun plan_neverExceedsTwoWeeks() {
        val from = LocalDateTime.of(2026, 10, 11, 0, 0)
        val items = Reminder.plan(tt(course()), leadMinutes = 20, from = from)
        val limit = from.plusDays(14)
        items.forEach {
            assertFalse("排到了窗口之外: ${at(it.at)}", at(it.at).isAfter(limit))
        }
    }

    /** 提前量精确：触发时刻 = 上课时刻 − 提前分钟数 */
    @Test
    fun plan_appliesLeadMinutes() {
        val from = LocalDateTime.of(2026, 10, 11, 0, 0)
        // 2026-10-12 是周一，course() 默认周一第 5 节
        val items = Reminder.plan(tt(course()), leadMinutes = 30, from = from)
        assertTrue("应该排到周一那节", items.isNotEmpty())
        val first = items.first()
        assertEquals(LocalDate.of(2026, 10, 12), at(first.at).toLocalDate())
        assertEquals(30, first.lead)
        // 触发时刻应当早于该节开始时间，且正好差 lead 分钟
        val begin = Slots.of(5)!!.beginTime
        assertEquals(begin.minusMinutes(30), at(first.at).toLocalTime())
    }

    /** 已经过去的课不再排（用 from 把基准推到当天上课之后） */
    @Test
    fun plan_skipsPastOccurrences() {
        val mondayLate = LocalDateTime.of(2026, 10, 12, 23, 0)   // 周一深夜
        val items = Reminder.plan(tt(course()), leadMinutes = 20, from = mondayLate)
        // 周一那节已经过点，第二批要在下一个周一
        items.forEach {
            assertTrue("排到了过去的时刻: ${at(it.at)}", at(it.at).isAfter(mondayLate))
        }
        assertEquals(LocalDate.of(2026, 10, 19), at(items.first().at).toLocalDate())
    }

    /** 提醒方式要透传到每条待发项（接收器据此决定走消息还是闹钟） */
    @Test
    fun plan_carriesStyle() {
        val from = LocalDateTime.of(2026, 10, 11, 0, 0)
        val alarm = Reminder.plan(tt(course()), 20, Reminder.STYLE_ALARM, from)
        val notify = Reminder.plan(tt(course()), 20, Reminder.STYLE_NOTIFY, from)
        assertTrue(alarm.isNotEmpty() && notify.isNotEmpty())
        assertTrue(alarm.all { it.style == Reminder.STYLE_ALARM })
        assertTrue(notify.all { it.style == Reminder.STYLE_NOTIFY })
    }

    /** 同一天多门课各自排一条，且按时间先后排序 */
    @Test
    fun plan_oneItemPerClassSortedByTime() {
        val from = LocalDateTime.of(2026, 10, 11, 0, 0)
        val items = Reminder.plan(
            tt(course("早课", s = 1, e = 2), course("晚课", s = 9, e = 10)),
            leadMinutes = 20, from = from,
        )
        val sameDay = items.filter { at(it.at).toLocalDate() == LocalDate.of(2026, 10, 12) }
        assertEquals("周一应有两节课各一条", 2, sameDay.size)
        assertTrue(sameDay[0].at < sameDay[1].at)
        assertEquals("早课", sameDay[0].name)
    }

    /** 集中实践课（整周，dayOfWeek = 0）不按节次提醒 */
    @Test
    fun plan_skipsPractices() {
        val from = LocalDateTime.of(2026, 10, 11, 0, 0)
        val practice = Course("集中实践", "", "", "", 0, 0, 0, "5周", setOf(5))
        val items = Reminder.plan(tt(practice), leadMinutes = 20, from = from)
        assertTrue("实践课不该产生闹钟式提醒", items.isEmpty())
    }
}
