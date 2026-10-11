package com.cxywh.hauejw

import java.time.LocalDate

/** 一节课（一条开课记录） */
data class Course(
    val name: String,
    val teacher: String,
    val room: String,
    val campus: String,
    val dayOfWeek: Int,      // 1=周一 … 7=周日；0 = 集中实践（整周）
    val startSlot: Int,      // 从 1 开始
    val endSlot: Int,
    val weekText: String,    // 原始周次描述，如 "1-10周,14-19周"
    val weeks: Set<Int>,
    val credit: String = "",
    val category: String = "",   // 课程类别
    val kind: String = "",       // 必修/选修
    val exam: String = "",       // 考核方式
    val className: String = "",
    val origKey: String = "",    // 被重命名后仍指向原始课程，保证编辑定位不乱
) {
    val isPractice: Boolean get() = dayOfWeek == 0
    val slotSpan: Int get() = (endSlot - startSlot + 1).coerceAtLeast(1)

    /** 课程标识：同名同教师视为同一门课（重命名/整学期移除/整体调时间都按它归并） */
    val key: String get() = origKey.ifBlank { "$name|$teacher" }

    /** 单次上课的标识：同门课同一天同一节次视为同一次 */
    val occKey: String get() = "$key|$dayOfWeek|$startSlot|$endSlot"

    val roomText: String get() = if (campus.isBlank() || room.isBlank()) room else "$campus$room"

    /** 卡片副标题：只留教室，校区放到详情里，窄卡片上更不容易被截断 */
    val cardSub: String get() = room.ifBlank { roomText }

    val timeText: String
        get() = if (isPractice) "整周" else "${WeekUtils.WEEK_LABELS[dayOfWeek - 1]} 第${startSlot}-${endSlot}节"

    /** 整条课程的可读描述（详情/全部课程列表） */
    val fullText: String
        get() = listOf(timeText, weekText, teacher, roomText).filter { it.isNotBlank() }.joinToString("　")
}

data class Student(
    val name: String = "",
    val id: String = "",
    val className: String = "",
    val major: String = "",
)

data class Term(
    val xnm: String = "",        // "2026"
    val xnmc: String = "",       // "2026-2027"
    val xqm: String = "",        // "3" / "12" / "16"
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val maxWeek: Int = 20,
) {
    val termName: String
        get() = when (xqm) {
            "3" -> "第1学期"; "12" -> "第2学期"; "16" -> "第3学期"; else -> "${xqm}学期"
        }
    val title: String get() = "${xnmc.ifBlank { xnm }} $termName"
}

/** 课表来源 */
object Source {
    const val SERVER = "server"    // 从教务系统拉取
    const val IMPORTED = "imported" // 从外部文件导入
}

data class Timetable(
    val term: Term,
    val student: Student,
    val courses: List<Course>,
    val source: String = Source.SERVER,
) {
    // ────────── 派生索引（懒建，每个实例只算一次）──────────
    // 课表格子渲染时 (周, 星期) 的组合会被反复查询；原来每次都全表 filter+sorted，
    // 一屏 7 天 × 二十来周、再叠加重组，量级很难看。索引后单次查询是 O(1)。

    /** (周次, 星期) → 当天的课（按开始节次排序） */
    private val byDay: Map<Pair<Int, Int>, List<Course>> by lazy {
        courses
            .flatMap { c -> c.weeks.map { w -> (w to c.dayOfWeek) to c } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.sortedBy { it.startSlot } }
    }

    /** 周次 → 该周的集中实践课 */
    private val practicesByWeek: Map<Int, List<Course>> by lazy {
        courses.filter { it.isPractice }
            .flatMap { c -> c.weeks.map { it to c } }
            .groupBy({ it.first }, { it.second })
    }

    private val weeksUsed: Set<Int> by lazy { courses.flatMap { it.weeks }.toHashSet() }

    /** 某一周某一天的课（按开始节次排序） */
    fun coursesOf(week: Int, dayOfWeek: Int): List<Course> = byDay[week to dayOfWeek] ?: emptyList()

    /** 某一周的集中实践课 */
    fun practicesOf(week: Int): List<Course> = practicesByWeek[week] ?: emptyList()

    fun hasClass(week: Int): Boolean = week in weeksUsed

    /** 本学期有课的最大周次（周次选择器显示到哪一周） */
    val maxWeekInUse: Int by lazy { weeksUsed.maxOrNull() ?: 0 }

    /** 应用到网格的课程列表（编辑层即在此之上生效） */
    fun withCourses(list: List<Course>): Timetable = copy(courses = list)

    /** 同门课去重后的列表（编辑面板里按门课列出） */
    val distinctCourses: List<Course> by lazy {
        courses.filter { !it.isPractice }.distinctBy { it.key }
    }
}
