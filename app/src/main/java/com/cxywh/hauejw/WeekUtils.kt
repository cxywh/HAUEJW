package com.cxywh.hauejw

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 节次与上课时间 */
data class SlotTime(val index: Int, val begin: String, val end: String) {
    val beginTime: LocalTime get() = LocalTime.parse(begin)
    val endTime: LocalTime get() = LocalTime.parse(end)
}

object Slots {
    /** 1~8 为白天，9~10 为晚上（学校未提供作息表，这里是可改的常量） */
    val ALL = listOf(
        SlotTime(1, "08:30", "09:15"),
        SlotTime(2, "09:20", "10:05"),
        SlotTime(3, "10:25", "11:10"),
        SlotTime(4, "11:15", "12:00"),
        SlotTime(5, "14:00", "14:45"),
        SlotTime(6, "14:50", "15:35"),
        SlotTime(7, "15:55", "16:40"),
        SlotTime(8, "16:45", "17:30"),
        SlotTime(9, "18:30", "19:15"),
        SlotTime(10, "19:20", "20:05"),
    )
    val COUNT = ALL.size

    fun of(index: Int): SlotTime? = ALL.firstOrNull { it.index == index }

    /** 当前时刻处于第几节（课间算上一节） */
    fun nowSlot(t: LocalTime = LocalTime.now()): Int? {
        for (s in ALL) {
            if (!t.isBefore(s.beginTime) && t.isBefore(s.endTime.plusSeconds(45))) return s.index
        }
        return null
    }

    /** 按开始时间反推节次（导入外部文件时用） */
    fun byTime(time: LocalTime): Int =
        ALL.minByOrNull { kotlin.math.abs(it.beginTime.toSecondOfDay() - time.toSecondOfDay()) }?.index ?: 1
}

object WeekUtils {

    /** "1-10周,14-19周" / "9-10周" / "1-16周(单)" / "14-17周" → {1..10, 14..19} */
    fun parseWeeks(zcd: String): Set<Int> {
        val weeks = mutableSetOf<Int>()
        val odd = zcd.contains("单")
        val even = zcd.contains("双")
        zcd.replace("周", "")
            .split(',', '，', ';', '；')
            .forEach { raw ->
                val part = raw.replace("(", "").replace(")", "")
                    .replace("（", "").replace("）", "")
                    .replace("单", "").replace("双", "")
                    .trim()
                if (part.isEmpty()) return@forEach
                val range = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(part)
                if (range != null) {
                    val a = range.groupValues[1].toInt()
                    val b = range.groupValues[2].toInt()
                    if (a <= b) for (w in a..b) weeks.add(w)
                } else if (Regex("^\\d+$").matches(part)) {
                    weeks.add(part.toInt())
                }
            }
        if (odd) return weeks.filter { it % 2 == 1 }.toSet()
        if (even) return weeks.filter { it % 2 == 0 }.toSet()
        return weeks
    }

    /** {1,2,5,6,7} → "1-2,5-7周"（连续段合并） */
    fun formatWeeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1)) {
            if (w == prev + 1) { prev = w; continue }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = w; prev = w
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",") + "周"
    }

    /** "1-4" → 1..4（容错 "第1-4节" / "1"） */
    fun parseSlots(jcs: String): Pair<Int, Int> {
        val nums = Regex("\\d+").findAll(jcs).map { it.value.toInt() }.toList()
        return when {
            nums.isEmpty() -> 1 to 1
            nums.size == 1 -> nums[0] to nums[0]
            else -> minOf(nums[0], nums[1]) to maxOf(nums[0], nums[1])
        }
    }

    fun mondayOf(term: Term, week: Int): LocalDate? =
        term.startDate?.plusDays(7L * (week - 1))

    fun dateOf(term: Term, week: Int, day: Int): LocalDate? =
        mondayOf(term, week)?.plusDays((day - 1).toLong())

    /** 某日期属于第几周（1 起），不在学期内返回 null */
    fun weekOf(term: Term, date: LocalDate = LocalDate.now()): Int? {
        val start = term.startDate ?: return null
        if (date.isBefore(start)) return null
        val w = (ChronoUnit.DAYS.between(start, date) / 7).toInt() + 1
        return if (w in 1..term.maxWeek) w else null
    }

    fun todayDow(date: LocalDate = LocalDate.now()): Int = dowOf(date)

    fun dowOf(date: LocalDate): Int =
        when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
            DayOfWeek.SUNDAY -> 7
        }

    val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** "周一"/"星期一"/"一"/"1"/"MO" → 1；认不出返回 0 */
    fun parseDay(s: String): Int {
        val t = s.trim().uppercase()
        WEEK_LABELS.forEachIndexed { i, lbl ->
            if (t.contains(lbl) || t.contains("星期" + lbl.removePrefix("周"))) return i + 1
        }
        listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").forEachIndexed { i, m ->
            if (t.contains(m)) return i + 1
        }
        // 单字：一 二 三 四 五 六 日（天）
        val singles = listOf("一", "二", "三", "四", "五", "六", "日", "天")
        singles.forEachIndexed { i, c ->
            if (t.contains(c)) return if (i == 6 || i == 7) 7 else i + 1
        }
        Regex("\\d+").find(t)?.let { n ->
            val d = n.value.toInt()
            if (d in 1..7) return d
            if (d == 0) return 7
        }
        return 0
    }

    /** 返回该日期所在周的周一 */
    fun mondayOfDate(date: LocalDate): LocalDate = date.minusDays((dowOf(date) - 1).toLong())
}
