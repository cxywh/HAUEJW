package com.cxywh.hauejw

import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表编辑层：所有本地修改都记成一条条 op，作用在「服务端原始课表」之上。
 * 好处：随时可撤销、可只作用于某一次、不动服务端数据。
 */
enum class EditKind {
    RENAME,          // 重命名课程（同门课全改）
    REMOVE_THIS,     // 从某一周移除这一次课
    REMOVE_WEEK_ALL, // 从某一周移除这门课（该周所有次）
    REMOVE_SEM,      // 从本学期移除这门课
    MOVE_ONCE,       // 调整时间：只作用于这一次
    MOVE_ALL,        // 调整时间：作用于本学期这门课的所有次
}

data class EditOp(
    val kind: EditKind,
    val key: String,          // 课程标识 name|teacher（原始名，重命名后仍用它定位）
    val week: Int = 0,        // REMOVE_THIS / REMOVE_WEEK_ALL / MOVE_ONCE 用
    val day: Int = 0,         // 来源：星期（1~7）
    val start: Int = 0,       // 来源：开始节次
    val end: Int = 0,         // 来源：结束节次
    val toDay: Int = 0,       // 目标星期（MOVE_*）
    val toStart: Int = 0,     // 目标开始节次（MOVE_*）
    val name: String = "",    // RENAME 的新名字
) {
    /** 人类可读的描述，用于「本地修改」列表 */
    fun describe(courseName: String): String = when (kind) {
        EditKind.RENAME -> "「$courseName」改名为「$name」"
        EditKind.REMOVE_THIS -> "第${week}周 移除 ${WeekUtils.WEEK_LABELS.getOrElse(day - 1) { "" }} 第${start}-${end}节 的「$courseName」"
        EditKind.REMOVE_WEEK_ALL -> "第${week}周 移除「$courseName」全部"
        EditKind.REMOVE_SEM -> "本学期 移除「$courseName」"
        EditKind.MOVE_ONCE -> "第${week}周 「$courseName」改到 ${WeekUtils.WEEK_LABELS.getOrElse(toDay - 1) { "" }} 第${toStart}节起"
        EditKind.MOVE_ALL -> "本学期 「$courseName」全部改到 ${WeekUtils.WEEK_LABELS.getOrElse(toDay - 1) { "" }} 第${toStart}节起"
    }
}

class EditLayer(val ops: List<EditOp> = emptyList()) {

    val isEmpty: Boolean get() = ops.isEmpty()

    fun plus(op: EditOp): EditLayer = EditLayer(ops + op)

    /** 清除与某门课相关的全部修改 */
    fun clearFor(key: String): EditLayer = EditLayer(ops.filterNot { it.key == key })

    fun clear(): EditLayer = EditLayer(emptyList())

    fun forKey(key: String): List<EditOp> = ops.filter { it.key == key }

    fun renamedKey(key: String): String? =
        ops.lastOrNull { it.kind == EditKind.RENAME && it.key == key }?.name

    /**
     * 把修改应用到课程列表。
     * 顺序：整学期移除 → 整体调时间 → 单周移除/单次调时间 → 重命名（放最后，保持 key 稳定）
     */
    fun apply(courses: List<Course>): List<Course> {
        val removedSem = ops.filter { it.kind == EditKind.REMOVE_SEM }.map { it.key }.toSet()
        var list = courses.filter { it.key !in removedSem }

        // 整体调时间
        for (op in ops.filter { it.kind == EditKind.MOVE_ALL }) {
            list = list.map { c ->
                if (c.key == op.key && !c.isPractice) {
                    val span = c.slotSpan
                    c.copy(dayOfWeek = op.toDay.coerceIn(1, 7), startSlot = op.toStart, endSlot = op.toStart + span - 1)
                } else c
            }
        }

        // 单周操作
        for (op in ops.filter { it.kind == EditKind.REMOVE_THIS || it.kind == EditKind.REMOVE_WEEK_ALL || it.kind == EditKind.MOVE_ONCE }) {
            list = applyOneWeek(list, op)
        }

        // 重命名（同时记住原始 key，后续编辑仍能定位到这门课）
        val renames = ops.filter { it.kind == EditKind.RENAME }.associate { it.key to it.name }
        if (renames.isNotEmpty()) {
            list = list.map { c ->
                val nn = renames[c.key]
                if (nn != null && nn.isNotBlank()) c.copy(name = nn, origKey = c.key) else c
            }
        }
        return list
    }

    private fun applyOneWeek(list: List<Course>, op: EditOp): List<Course> {
        val out = mutableListOf<Course>()
        for (c in list) {
            if (c.key != op.key || op.week !in c.weeks) {
                out.add(c); continue
            }
            val matches = c.dayOfWeek == op.day && c.startSlot == op.start && c.endSlot == op.end
            if (op.kind == EditKind.REMOVE_WEEK_ALL || (op.kind == EditKind.REMOVE_THIS && matches)) {
                out.add(c.withWeeks(c.weeks - op.week))
            } else if (op.kind == EditKind.MOVE_ONCE && matches) {
                out.add(c.withWeeks(c.weeks - op.week))
                if (!c.isPractice) {
                    val span = c.slotSpan
                    out.add(
                        c.copy(
                            dayOfWeek = op.toDay.coerceIn(1, 7),
                            startSlot = op.toStart,
                            endSlot = op.toStart + span - 1,
                            weeks = setOf(op.week),
                            weekText = WeekUtils.formatWeeks(setOf(op.week)),
                        )
                    )
                }
            } else {
                out.add(c)
            }
        }
        return out.filter { it.weeks.isNotEmpty() || it.isPractice }
    }

    private fun Course.withWeeks(w: Set<Int>): Course =
        copy(weeks = w, weekText = WeekUtils.formatWeeks(w))

    // ───────────── 持久化（存进 SharedPreferences） ─────────────

    fun toJson(): String {
        val arr = JSONArray()
        for (o in ops) {
            arr.put(
                JSONObject()
                    .put("k", o.kind.name)
                    .put("key", o.key)
                    .put("w", o.week)
                    .put("d", o.day)
                    .put("s", o.start)
                    .put("e", o.end)
                    .put("td", o.toDay)
                    .put("ts", o.toStart)
                    .put("n", o.name)
            )
        }
        return arr.toString()
    }

    companion object {
        val EMPTY = EditLayer()

        fun fromJson(text: String?): EditLayer {
            if (text.isNullOrBlank()) return EMPTY
            return try {
                val arr = JSONArray(text)
                val out = mutableListOf<EditOp>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val kind = runCatching { EditKind.valueOf(o.getString("k")) }.onFailure { e -> Diag.record("Edits.fromJson", e) }.getOrNull()
                    if (kind == null) continue
                    out.add(
                        EditOp(
                            kind = kind,
                            key = o.optString("key"),
                            week = o.optInt("w"),
                            day = o.optInt("d"),
                            start = o.optInt("s"),
                            end = o.optInt("e"),
                            toDay = o.optInt("td"),
                            toStart = o.optInt("ts"),
                            name = o.optString("n"),
                        )
                    )
                }
                EditLayer(out)
            } catch (e: Exception) {
                EMPTY
            }
        }
    }
}
