package com.cxywh.hauejw

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 学期进度。没有校历起止日（term.startDate / endDate 为空）时返回 null —— 导入的课表常常没有起止日，
 * 这时界面要能优雅地什么都不显示，而不是编一个进度出来。
 */
data class SemesterProgress(
    val week: Int,            // 第几周（1 起）；未开学为 0
    val totalWeeks: Int,      // 学期总周数
    val percent: Int,         // 已过百分比 0..100
    val daysToEnd: Int,       // 距学期最后一天还有几天（当天 = 0）
    val daysElapsed: Int,     // 已过天数（开学前为 0）
    val totalDays: Int,       // 学期总天数
    val notStarted: Boolean,
    val ended: Boolean,
) {
    val started: Boolean get() = !notStarted
}

/** 学期进度与「距放假 N 天」。纯算术，不含当前时间 —— today 必须由调用方传入，否则没法单测。 */
fun semesterProgress(term: Term, today: LocalDate): SemesterProgress? {
    val start = term.startDate ?: return null
    val end = term.endDate ?: return null
    if (end.isBefore(start)) return null

    val totalDays = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    if (totalDays <= 0) return null
    val totalWeeks = maxOf(1, (totalDays + 6) / 7)

    val notStarted = today.isBefore(start)
    val ended = today.isAfter(end)

    // 已过天数：开学当天算第 1 天
    val elapsed = if (notStarted) 0
    else (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(0, totalDays)

    val percent = if (notStarted) 0 else (elapsed * 100.0 / totalDays).toInt().coerceIn(0, 100)

    // 距放假：以学期最后一天为界，过去之后就是 0（不要出现负数天数）
    val daysToEnd = ChronoUnit.DAYS.between(today, end).toInt().coerceAtLeast(0)

    val week = if (notStarted) 0
    else (ChronoUnit.DAYS.between(start, today).toInt() / 7 + 1).coerceIn(1, totalWeeks)

    return SemesterProgress(
        week = week,
        totalWeeks = totalWeeks,
        percent = percent,
        daysToEnd = daysToEnd,
        daysElapsed = elapsed,
        totalDays = totalDays,
        notStarted = notStarted,
        ended = ended,
    )
}

/** 某一天要上的课（按开始节次排好）。不在学期内、或那天没课 → 空表。集中实践是整周的不占节次，排除。 */
fun coursesOn(tt: Timetable, date: LocalDate): List<Course> {
    val week = WeekUtils.weekOf(tt.term, date) ?: return emptyList()
    return tt.coursesOf(week, WeekUtils.dowOf(date)).filter { !it.isPractice }
}

/** 今天还没结束的课（正在上的也算在内）——「今天还剩 N 节」用的就是它 */
fun remainingToday(tt: Timetable, now: LocalDateTime): List<Course> =
    coursesOn(tt, now.toLocalDate()).filter { c ->
        val end = Slots.of(c.endSlot)?.endTime ?: return@filter false
        now.toLocalTime().isBefore(end)
    }

/** 此刻正在上的课（现在落在它的节次时间区间内） */
fun ongoingNow(tt: Timetable, now: LocalDateTime): Course? {
    val t = now.toLocalTime()
    return coursesOn(tt, now.toLocalDate()).firstOrNull { c ->
        val begin = Slots.of(c.startSlot)?.beginTime ?: return@firstOrNull false
        val end = Slots.of(c.endSlot)?.endTime ?: return@firstOrNull false
        !t.isBefore(begin) && t.isBefore(end)
    }
}

/** 下一节课：先看今天还没开始的，再往后逐天找（含今天在内 horizonDays 天） */
data class NextClass(
    val course: Course,
    val date: LocalDate,
    val begin: LocalTime,
    val isToday: Boolean,
)

fun nextClass(tt: Timetable, now: LocalDateTime, horizonDays: Int = 14): NextClass? {
    val today = now.toLocalDate()
    for (i in 0..horizonDays) {
        val date = today.plusDays(i.toLong())
        for (c in coursesOn(tt, date)) {
            val begin = Slots.of(c.startSlot)?.beginTime ?: continue
            // 今天：只认还没开始的；以后的日期：第一节课就是下一节
            if (i == 0 && !begin.isAfter(now.toLocalTime())) continue
            return NextClass(c, date, begin, i == 0)
        }
    }
    return null
}

/** 「距放假 N 天」的一句话说法（界面与小组件共用，避免两处口径不一致） */
fun daysToEndText(p: SemesterProgress): String = when {
    p.notStarted -> "还没开学"
    p.ended -> "已放假"
    p.daysToEnd == 0 -> "今天是本学期最后一天"
    else -> "距放假 ${p.daysToEnd} 天"
}

/** 「第 N 周」的一句话说法 */
fun weekText(p: SemesterProgress): String = when {
    p.notStarted -> "未开学"
    p.ended -> "已结课"
    else -> "第 ${p.week} 周"
}
