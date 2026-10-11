package com.cxywh.hauejw

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 课表导入/导出。支持三种格式（按内容自动识别，与扩展名无关）：
 *  · JSON  —— 本 App 的原生格式（含完整字段，可无损往返）
 *  · CSV   —— 一行一节课，方便用 Excel/WPS 批量编辑
 *  · ICS   —— iCalendar，教材类 App（超级课程表/WakeUp 等）的通用导出格式
 */
object TimetableCodec {

    const val FORMAT = "hauejw-timetable/1"

    data class ImportResult(val tt: Timetable, val warnings: List<String> = emptyList())

    class ImportException(message: String) : Exception(message)

    // ───────────────────────── JSON ─────────────────────────

    fun toJson(tt: Timetable): String {
        val courses = JSONArray()
        for (c in tt.courses) {
            courses.put(
                JSONObject()
                    .put("name", c.name)
                    .put("teacher", c.teacher)
                    .put("room", c.room)
                    .put("campus", c.campus)
                    .put("day", c.dayOfWeek)
                    .put("start", c.startSlot)
                    .put("end", c.endSlot)
                    .put("weeks", c.weeks.sorted())
                    .put("credit", c.credit)
                    .put("category", c.category)
                    .put("kind", c.kind)
                    .put("exam", c.exam)
                    .put("className", c.className)
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("source", tt.source)
            .put(
                "term", JSONObject()
                    .put("xnm", tt.term.xnm)
                    .put("xnmc", tt.term.xnmc)
                    .put("xqm", tt.term.xqm)
                    .put("start", tt.term.startDate?.toString() ?: "")
                    .put("end", tt.term.endDate?.toString() ?: "")
                    .put("maxWeek", tt.term.maxWeek)
            )
            .put(
                "student", JSONObject()
                    .put("name", tt.student.name)
                    .put("id", tt.student.id)
                    .put("className", tt.student.className)
                    .put("major", tt.student.major)
            )
            .put("courses", courses)
            .toString(2)
    }

    fun fromJson(text: String): Timetable {
        val o = JSONObject(text)
        val t = o.optJSONObject("term") ?: JSONObject()
        val st = o.optJSONObject("student") ?: JSONObject()
        val term = Term(
            xnm = t.optString("xnm"),
            xnmc = t.optString("xnmc"),
            xqm = t.optString("xqm"),
            startDate = t.optString("start").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            endDate = t.optString("end").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            maxWeek = t.optInt("maxWeek", 20),
        )
        val courses = mutableListOf<Course>()
        val arr = o.optJSONArray("courses") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val weeks = mutableSetOf<Int>()
            c.optJSONArray("weeks")?.let { w -> for (k in 0 until w.length()) weeks.add(w.getInt(k)) }
            if (weeks.isEmpty()) continue
            courses.add(
                Course(
                    name = c.optString("name"),
                    teacher = c.optString("teacher"),
                    room = c.optString("room"),
                    campus = c.optString("campus"),
                    dayOfWeek = c.optInt("day"),
                    startSlot = c.optInt("start"),
                    endSlot = c.optInt("end"),
                    weekText = WeekUtils.formatWeeks(weeks),
                    weeks = weeks,
                    credit = c.optString("credit"),
                    category = c.optString("category"),
                    kind = c.optString("kind"),
                    exam = c.optString("exam"),
                    className = c.optString("className"),
                )
            )
        }
        if (courses.isEmpty()) throw ImportException("文件里没有可用的课程")
        return Timetable(
            term = term,
            student = Student(st.optString("name"), st.optString("id"), st.optString("className"), st.optString("major")),
            courses = courses,
            source = Source.IMPORTED,
        )
    }

    // ───────────────────────── CSV ─────────────────────────

    const val CSV_HEADER = "课程,教师,教室,校区,星期,开始节,结束节,周次"

    fun toCsv(tt: Timetable): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        for (c in tt.courses) {
            sb.append(
                listOf(
                    c.name, c.teacher, c.room, c.campus,
                    if (c.isPractice) "整周" else WeekUtils.WEEK_LABELS[c.dayOfWeek - 1].removePrefix("周"),
                    if (c.isPractice) "" else c.startSlot.toString(),
                    if (c.isPractice) "" else c.endSlot.toString(),
                    c.weekText,
                ).joinToString(",") { esc(it) }
            ).append('\n')
        }
        return sb.toString()
    }

    private fun esc(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuote && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQuote = !inQuote
                ch == ',' && !inQuote -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    fun fromCsv(text: String): Timetable = fromCsv(text, mutableListOf())

    fun fromCsv(text: String, outWarn: MutableList<String>): Timetable {
        val warnings = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ImportException("文件是空的")

        var header: List<String>? = null
        for ((idx, raw) in lines.withIndex()) {
            val cells = splitCsvLine(raw)
            if (idx == 0 && cells.any { it.contains("课程") || it.contains("科目") }) {
                header = cells
                continue
            }
            fun col(vararg names: String): String {
                val h = header ?: return ""
                for (n in names) {
                    val i = h.indexOfFirst { it.contains(n) }
                    if (i >= 0 && i < cells.size) return cells[i]
                }
                return ""
            }
            val name: String
            val teacher: String
            val room: String
            val campus: String
            val dayRaw: String
            val startRaw: String
            val endRaw: String
            val weeksRaw: String
            if (header != null) {
                name = col("课程", "科目", "名称")
                teacher = col("教师", "老师")
                room = col("教室", "地点")
                campus = col("校区")
                dayRaw = col("星期", "周几")
                startRaw = col("开始")
                endRaw = col("结束")
                weeksRaw = col("周次")
            } else {
                // 无表头：按固定顺序取前 8 列
                fun at(i: Int) = cells.getOrNull(i).orEmpty()
                name = at(0); teacher = at(1); room = at(2); campus = at(3)
                dayRaw = at(4); startRaw = at(5); endRaw = at(6); weeksRaw = at(7)
            }
            if (name.isBlank()) continue
            val weeks = WeekUtils.parseWeeks(weeksRaw)
            if (weeks.isEmpty()) {
                warnings.add("第 ${idx + 1} 行「$name」没有可识别的周次，已跳过")
                continue
            }
            val practice = dayRaw.contains("整周")
            val day = if (practice) 0 else WeekUtils.parseDay(dayRaw)
            if (!practice && (day == 0 || startRaw.isBlank() || endRaw.isBlank())) {
                warnings.add("第 ${idx + 1} 行「$name」星期或节次认不出，已跳过")
                continue
            }
            val s = startRaw.toIntOrNull() ?: 1
            val e = endRaw.toIntOrNull() ?: s
            courses.add(
                Course(
                    name = name, teacher = teacher, room = room, campus = campus,
                    dayOfWeek = day, startSlot = if (practice) 0 else s, endSlot = if (practice) 0 else e,
                    weekText = WeekUtils.formatWeeks(weeks), weeks = weeks,
                )
            )
        }
        if (courses.isEmpty()) throw ImportException("没解析出任何课程，请对照示例文件检查格式")
        outWarn.addAll(warnings)
        return Timetable(
            term = Term(maxWeek = courses.flatMap { it.weeks }.maxOrNull() ?: 20),
            student = Student(),
            courses = courses,
            source = Source.IMPORTED,
        )
    }

    // ───────────────────────── ICS ─────────────────────────

    fun toIcs(tt: Timetable): String {
        val df = DateTimeFormatter.ofPattern("yyyyMMdd")
        val tf = DateTimeFormatter.ofPattern("HHmmss")
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//HAUEJW//教务助手//CN\r\nCALSCALE:GREGORIAN\r\n")
        for (c in tt.courses) {
            if (c.isPractice) continue
            // 周次可能有断档，按连续段拆成多个 VEVENT
            val sorted = c.weeks.sorted()
            val runs = mutableListOf<MutableList<Int>>()
            for (w in sorted) {
                val last = runs.lastOrNull()
                if (last != null && w == last.last() + 1) last.add(w) else runs.add(mutableListOf(w))
            }
            for (run in runs) {
                val date = WeekUtils.dateOf(tt.term, run.first(), c.dayOfWeek) ?: continue
                val slot = Slots.of(c.startSlot) ?: continue
                val endSlot = Slots.of(c.endSlot) ?: slot
                val dayCode = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[c.dayOfWeek - 1]
                sb.append("BEGIN:VEVENT\r\n")
                sb.append("UID:").append(c.key.hashCode()).append('-').append(run.first()).append("@hauejw\r\n")
                sb.append("SUMMARY:").append(c.name).append("\r\n")
                if (c.room.isNotBlank()) sb.append("LOCATION:").append(c.roomText).append("\r\n")
                sb.append("DESCRIPTION:教师:").append(c.teacher).append(" 周次:").append(WeekUtils.formatWeeks(run.toSet()))
                    .append(" 节次:").append(c.startSlot).append('-').append(c.endSlot).append("\r\n")
                sb.append("DTSTART:").append(date.format(df)).append('T').append(slot.beginTime.format(tf)).append("\r\n")
                sb.append("DTEND:").append(date.format(df)).append('T').append(endSlot.endTime.format(tf)).append("\r\n")
                sb.append("RRULE:FREQ=WEEKLY;COUNT=").append(run.size).append(";BYDAY=").append(dayCode).append("\r\n")
                sb.append("END:VEVENT\r\n")
            }
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    fun fromIcs(text: String, term: Term?): Timetable = fromIcs(text, term, mutableListOf())

    fun fromIcs(text: String, term: Term?, outWarn: MutableList<String>): Timetable {
        val warnings = mutableListOf<String>()
        // 1) 展开折行（RFC5545：续行以空格/制表符开头）
        val lines = mutableListOf<String>()
        text.replace("\r\n", "\n").replace("\r", "\n").split('\n').forEach { ln ->
            if ((ln.startsWith(" ") || ln.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last().trimEnd() + ln.trim()
            } else lines.add(ln)
        }

        data class Ev(
            var name: String = "", var room: String = "", var teacher: String = "",
            var dtStart: LocalDate? = null, var tStart: LocalTime? = null,
            var tEnd: LocalTime? = null, var rrule: String = "", var weeks: MutableSet<Int> = mutableSetOf(),
        )

        val events = mutableListOf<Ev>()
        var cur: Ev? = null
        for (ln in lines) {
            when {
                ln.equals("BEGIN:VEVENT", true) -> cur = Ev()
                ln.equals("END:VEVENT", true) -> { cur?.let { events.add(it) }; cur = null }
            }
            val e = cur ?: continue
            val colon = ln.indexOf(':')
            if (colon < 0 || ln.startsWith("BEGIN") || ln.startsWith("END")) continue
            val keyRaw = ln.substring(0, colon)
            val value = ln.substring(colon + 1).trim()
            when (keyRaw.substringBefore(';').uppercase()) {
                "SUMMARY" -> e.name = value
                "LOCATION" -> e.room = value
                "DESCRIPTION" -> {
                    val t = Regex("(?:教师|老师|授课教师)[:：]\\s*([^\\s,，;；]+)").find(value)
                    e.teacher = t?.groupValues?.get(1) ?: value.substringBefore('\n').take(12)
                    Regex("(?:周次|上课周)[:：]\\s*([0-9,\\-—~～周单双()（）;；]+)").find(value)?.let {
                        e.weeks.addAll(WeekUtils.parseWeeks(it.groupValues[1]))
                    }
                }
                "DTSTART" -> {
                    parseIcsTime(value)?.let { (d, t) -> e.dtStart = d; e.tStart = t }
                }
                "DTEND" -> parseIcsTime(value)?.let { (_, t) -> e.tEnd = t }
                "RRULE" -> e.rrule = value
            }
        }
        if (events.isEmpty()) throw ImportException("文件里没有日历事件（VEVENT）")

        // 2) 周次基准：优先用传入的学期；没有就用最早那节课所在周的周一
        var base = term
        if (base?.startDate == null) {
            val first = events.mapNotNull { it.dtStart }.minOrNull()
            if (first != null) {
                base = (base ?: Term()).copy(startDate = WeekUtils.mondayOfDate(first), maxWeek = 30)
                warnings.add("文件里没有学期开始日期，已按最早一节课所在周为第 1 周计算")
            }
        }

        val courses = mutableListOf<Course>()
        for (e in events) {
            if (e.name.isBlank()) continue
            val day = WeekUtils.parseDay(Regex("BYDAY=([A-Z,]+)").find(e.rrule)?.groupValues?.get(1) ?: "")
                .takeIf { it in 1..7 } ?: e.dtStart?.let { WeekUtils.dowOf(it) } ?: 0
            if (day == 0) { warnings.add("「${e.name}」认不出星期，已跳过"); continue }

            val weeks = mutableSetOf<Int>()
            weeks.addAll(e.weeks)
            val count = Regex("COUNT=(\\d+)").find(e.rrule)?.groupValues?.get(1)?.toIntOrNull()
            val until = Regex("UNTIL=(\\d{8})").find(e.rrule)?.groupValues?.get(1)
                ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd")) }.onFailure { e -> Diag.record("ImportExport.fromIcs", e) }.getOrNull() }
            val seed = e.dtStart?.let { d -> base?.let { WeekUtils.weekOf(it, d) } } ?: 1
            if (weeks.isEmpty()) {
                val n = when {
                    count != null -> count
                    until != null && base?.startDate != null ->
                        ((java.time.temporal.ChronoUnit.DAYS.between(base.startDate, until) / 7).toInt() + 1 - seed + 1)
                    e.dtStart != null -> 1
                    else -> 1
                }
                for (i in 0 until n.coerceAtLeast(1)) weeks.add(seed + i)
            }
            if (weeks.isEmpty()) continue

            val startSlot = e.tStart?.let { Slots.byTime(it) } ?: 1
            val endSlot = e.tEnd?.let { t -> Slots.ALL.minByOrNull { kotlin.math.abs(it.endTime.toSecondOfDay() - t.toSecondOfDay()) }?.index }
                ?: startSlot
            val (campus, room) = splitCampus(e.room)
            courses.add(
                Course(
                    name = e.name.trim(), teacher = e.teacher.trim(), room = room, campus = campus,
                    dayOfWeek = day,
                    startSlot = minOf(startSlot, endSlot), endSlot = maxOf(startSlot, endSlot),
                    weekText = WeekUtils.formatWeeks(weeks), weeks = weeks,
                )
            )
        }
        if (courses.isEmpty()) throw ImportException("没解析出任何课程")
        // 合并：很多课表 App 是「一周一节课一个事件」，这里按课程/时间归并周次
        val merged = LinkedHashMap<String, Course>()
        for (c in courses) {
            val id = "${c.name}|${c.teacher}|${c.room}|${c.campus}|${c.dayOfWeek}|${c.startSlot}|${c.endSlot}"
            val old = merged[id]
            if (old == null) merged[id] = c
            else {
                val all = old.weeks + c.weeks
                merged[id] = old.copy(weeks = all, weekText = WeekUtils.formatWeeks(all))
            }
        }
        val finalCourses = merged.values.sortedWith(compareBy({ it.dayOfWeek }, { it.startSlot }, { it.name }))
        outWarn.addAll(warnings)
        return Timetable(
            term = (base ?: Term()).copy(maxWeek = maxOf(base?.maxWeek ?: 20, finalCourses.flatMap { it.weeks }.maxOrNull() ?: 20)),
            student = Student(),
            courses = finalCourses,
            source = Source.IMPORTED,
        )
    }

    private fun splitCampus(loc: String): Pair<String, String> {
        val t = loc.trim()
        if (t.isBlank()) return "" to ""
        val i = t.indexOf("校区")
        return if (i >= 0) t.substring(0, i + 2) to t.substring(i + 2) else "" to t
    }

    /** 20260901T080000 / 20260901T0800 / 20260901[Z] → (日期, 时间) */
    private fun parseIcsTime(v: String): Pair<LocalDate, LocalTime?>? {
        val s = v.trim().removeSuffix("Z")
        if (s.length < 8) return null
        val date = runCatching { LocalDate.parse(s.substring(0, 8), DateTimeFormatter.ofPattern("yyyyMMdd")) }.onFailure { e -> Diag.record("ImportExport.parseIcsTime", e) }.getOrNull()
            ?: return null
        if (s.length < 11 || s[8] != 'T') return date to null
        val hm = s.substring(9).replace(":", "")
        val hh = hm.take(2).toIntOrNull() ?: return date to null
        val mm = hm.drop(2).take(2).toIntOrNull() ?: 0
        return date to LocalTime.of(hh.coerceIn(0, 23), mm.coerceIn(0, 59))
    }

    // ───────────────────────── 自动识别 ─────────────────────────

    fun parse(text: String, term: Term? = null): ImportResult {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.isEmpty()) throw ImportException("文件是空的")
        val warn = mutableListOf<String>()
        val tt = when {
            t.startsWith("BEGIN:VCALENDAR", true) || t.contains("BEGIN:VEVENT") -> fromIcs(text, term, warn)
            t.startsWith("{") -> fromJson(text)
            else -> fromCsv(text, warn)
        }
        return ImportResult(tt, warn)
    }

    /** 给用户的示例文件（照着改就能导入） */
    val TEMPLATE_CSV = """
        课程,教师,教室,校区,星期,开始节,结束节,周次
        数字化服装定制,魏欢,9A306-307,南校区,一,5,8,1-2周
        数字化服装定制,魏欢,7B401,南校区,五,5,8,1-2周
        功能及智能服装设计,王旭,9A402-403,南校区,二,7,8,1-10周,14-19周
        晚自习,自习,,,三,9,10,1-19周
    """.trimIndent() + "\n"

    val TEMPLATE_ICS = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//HAUEJW//教务助手示例//CN
        BEGIN:VEVENT
        SUMMARY:数字化服装定制
        LOCATION:南校区9A306-307
        DESCRIPTION:教师:魏欢 周次:1-2周 节次:5-8
        DTSTART:20260831T140000
        DTEND:20260831T173000
        RRULE:FREQ=WEEKLY;COUNT=2;BYDAY=MO
        END:VEVENT
        END:VCALENDAR
    """.trimIndent() + "\n"
}
