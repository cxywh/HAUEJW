package com.cxywh.hauejw

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 小组件里的一行。
 * past = 这节课今天已经上完了（渲染成灰色）；note = 不是课，是「还有 N 门课…」这类提示。
 */
data class WidgetRow(
    val time: String,
    val name: String,
    val room: String,
    val past: Boolean,
    val note: Boolean = false,
)

/**
 * 小组件一屏要显示的全部内容。
 *
 * 刻意和渲染分开：RemoteViews 没法单测，但"该显示什么"可以 —— 于是把内容做成
 * 这个纯数据类，由 [widgetState] 算出来，Widget.kt 只负责照着它上色。
 * empty 非空 = 还没有课表，此时其余字段都是空的。
 */
data class WidgetState(
    val week: String,
    val date: String,
    val term: String,
    val today: String,
    val rows: List<WidgetRow>,
    val next: String,
    val empty: String?,
)

/** 小组件布局里默认就 4 行课，超出的用最后一行提示「还有 N 门课…」；详细版按高度可以只给 2~4 行 */
const val WIDGET_ROWS = 4

/**
 * 详细版按可用高度显示几行课。回声洞打开时要让出它那块高度，所以先扣掉再算。
 * 数字是经验值：一行课约 17dp，头部（周次/学期进度/今天还剩几节）约 52dp，下一节约 18dp，
 * 内边距 28dp，回声洞整块（标签 + 两行正文）约 44dp。
 */
fun widgetRowsFor(heightDp: Int, echoOn: Boolean): Int {
    val h = if (echoOn) heightDp - 44 else heightDp
    return when {
        h >= 200 -> 4
        h >= 160 -> 3
        h >= 130 -> 2
        else -> 1
    }
}

private val WIDGET_DATE_FMT = DateTimeFormatter.ofPattern("M月d日")

/** 小组件当前该显示的内容。纯函数：时间由参数传入，课表为空就用空态。maxRows 由可用高度决定。 */
fun widgetState(tt: Timetable?, now: LocalDateTime, maxRows: Int = WIDGET_ROWS): WidgetState {
    val rows0 = maxRows.coerceAtLeast(1)
    val today = now.toLocalDate()
    val dateText = today.format(WIDGET_DATE_FMT) + " " + WeekUtils.WEEK_LABELS[WeekUtils.dowOf(today) - 1]

    if (tt == null) {
        return WidgetState(
            week = "教务助手",
            date = dateText,
            term = "还没有课表",
            today = "",
            rows = emptyList(),
            next = "",
            empty = "打开 App 登录一次，课表就会显示在这里",
        )
    }

    // 没有任何校历起止日 → 日期到周次的映射不存在，"今天有哪些课"根本无从判断。
    // 这时候必须说清楚原因，而不是显示成"今天没有课"（那是在骗人）。
    if (tt.term.startDate == null) {
        return WidgetState(
            week = "课表",
            date = dateText,
            term = "导入的课表没有校历，无法判断今天该上什么课",
            today = "打开 App 查看本周课表",
            rows = emptyList(),
            next = "",
            empty = null,
        )
    }

    val prog = semesterProgress(tt.term, today)
    val termLine = when {
        // 有开学日、没有结束日：周次算得出，距放假算不出
        prog == null -> "没有学期结束日期，算不出距放假"
        prog.notStarted -> "开学日期 " + (tt.term.startDate?.toString() ?: "未知")
        prog.ended -> "学期已结束 · 已放假"
        else -> "学期已过 ${prog.percent}% · ${daysToEndText(prog)}"
    }

    val list = coursesOn(tt, today)
    val remain = remainingToday(tt, now)
    val ongoing = ongoingNow(tt, now)
    val todayLine = when {
        list.isEmpty() -> "今天没有课"
        ongoing != null -> "正在上课 · 今天还剩 ${remain.size} 节"
        remain.isEmpty() -> "今天的课已上完"
        else -> "今天还剩 ${remain.size} 节"
    }

    val overflow = list.size > rows0
    val shown = if (overflow) list.take(rows0 - 1) else list
    val t = now.toLocalTime()
    val rows = shown.map { c ->
        val end = Slots.of(c.endSlot)?.endTime
        WidgetRow(
            time = Slots.of(c.startSlot)?.begin ?: "",
            name = c.name,
            room = c.room,
            past = end != null && t.isAfter(end),
        )
    }.toMutableList()
    if (overflow) {
        rows += WidgetRow("", "还有 ${list.size - (rows0 - 1)} 门课…", "", past = false, note = true)
    }

    val nc = nextClass(tt, now)
    val nextLine = if (nc == null) {
        "接下来两周没有课"
    } else {
        val day = if (nc.isToday) "今天" else WeekUtils.WEEK_LABELS[WeekUtils.dowOf(nc.date) - 1]
        val room = if (nc.course.room.isNotBlank()) " · " + nc.course.room else ""
        "下一节 · $day ${nc.begin} ${nc.course.name}$room"
    }

    // 周次只要开学日就能算；semesterProgress 为空（缺结束日）时也要显示第几周
    val weekLabel = if (prog != null) weekText(prog)
    else WeekUtils.weekOf(tt.term, today)?.let { "第 $it 周" } ?: "课表"

    return WidgetState(
        week = weekLabel,
        date = dateText,
        term = termLine,
        today = todayLine,
        rows = rows,
        next = nextLine,
        empty = null,
    )
}

/**
 * 2x2 简版（同一个组件被拖窄到 2 列时）要显示的内容。
 * 只要两件事：今天还剩几节、下一节是什么 —— 周次、学期进度这些在 2 列宽里根本放不下。
 */
data class SmallState(
    val today: String,
    val nextWhen: String,
    val nextName: String,
    val empty: String?,
)

/** 2x2 简版当前该显示的内容。纯函数，和 [widgetState] 共用同一批判断。 */
fun smallState(tt: Timetable?, now: LocalDateTime): SmallState {
    fun blank(msg: String) = SmallState("", "", "", msg)

    if (tt == null) return blank("打开 App 登录一次，课表就会显示在这里")
    if (tt.term.startDate == null) return blank("导入的课表没有校历，无法判断今天该上什么课")

    val today = now.toLocalDate()
    val list = coursesOn(tt, today)
    val remain = remainingToday(tt, now)
    val ongoing = ongoingNow(tt, now)
    val todayLine = when {
        list.isEmpty() -> "今天没有课"
        ongoing != null -> "正在上课 · 还剩 ${remain.size} 节"
        remain.isEmpty() -> "今天的课已上完"
        else -> "今天还剩 ${remain.size} 节"
    }

    val nc = nextClass(tt, now) ?: return SmallState(todayLine, "接下来两周没有课", "", null)
    val day = if (nc.isToday) "今天" else WeekUtils.WEEK_LABELS[WeekUtils.dowOf(nc.date) - 1]
    return SmallState(
        today = todayLine,
        nextWhen = "$day ${nc.begin}",
        nextName = nc.course.name,
        empty = null,
    )
}

/**
 * 卡片按哪种画法：设置里固定选了 wide / small 就听它的，选 auto 才看桌面给的实际宽度。
 * 纯函数，跟 widgetRowsFor 一样能被单测钉住（取值不认识时当 auto 处理）。
 */
fun widgetIsSmall(form: String, widthDp: Int, wideMinDp: Int): Boolean = when (form) {
    "small" -> true
    "wide" -> false
    else -> widthDp < wideMinDp
}
