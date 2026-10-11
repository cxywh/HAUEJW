package com.cxywh.hauejw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream

/**
 * 把课表画成 PNG（导出用）。
 *
 * 几何全部沿用界面上的 GridMetrics / Slots 数学（左栏 44dp、每节 54dp 那一套），
 * 按比例放大到图片尺寸，这样导出的图和 App 里看到的一致。
 * 纯 Canvas 绘制，不依赖 Compose 的渲染管线，所以离屏也能出图。
 */
object TimetableImage {

    private const val DAY_W = 132f
    private const val LEFT_W = 74f
    private const val SLOT_H = 128f
    private const val TITLE_H = 104f
    private const val HEAD_H = 92f
    private const val FOOT_H = 56f
    private const val PAD = 18f

    /** 课表 → PNG 字节。week 为当前显示的周次，maxWeek 用于标题里的范围提示。 */
    fun render(tt: Timetable, week: Int, maxWeek: Int): ByteArray {
        val p = C.palette
        val fullW = (LEFT_W + DAY_W * 7).toInt()
        val fullH = (TITLE_H + HEAD_H + SLOT_H * Slots.COUNT + FOOT_H).toInt()
        val bmp = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(p.bg.toArgb())

        val cFg = p.fg.toArgb()
        val cDim = p.dim.toArgb()
        val cCard = p.card.toArgb()
        val cLine = p.line.toArgb()
        val cLineStrong = p.lineStrong.toArgb()
        val cNoon = p.noon.toArgb()
        val cNight = p.night.toArgb()

        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

        // ── 标题 ──
        t.textSize = 40f
        t.color = cFg
        bold.textSize = 44f
        cv.drawText("课程表", PAD, 62f, bold)
        t.textSize = 24f
        t.color = cDim
        val meta = listOf(
            "第 $week 周" + if (maxWeek > 0) " / 共 $maxWeek 周" else "",
            run {
                val a = WeekUtils.dateOf(tt.term, week, 1)
                val b = WeekUtils.dateOf(tt.term, week, 7)
                if (a != null && b != null) {
                    "${a.monthValue}月${a.dayOfMonth}日 - ${b.monthValue}月${b.dayOfMonth}日"
                } else ""
            },
            tt.student.name.ifBlank { tt.student.id },
            tt.student.className,
        ).filter { it.isNotBlank() }.joinToString("　")
        cv.drawText(meta.ifBlank { tt.term.title }, PAD, 92f, t)

        // ── 星期表头 ──
        val gridTop = TITLE_H + HEAD_H
        cv.drawRect(0f, TITLE_H, fullW.toFloat(), gridTop, Paint().apply { color = cCard })
        t.textSize = 22f
        val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        for (d in 1..7) {
            val cx = LEFT_W + DAY_W * (d - 1)
            t.color = cFg
            t.textAlign = Paint.Align.CENTER
            cv.drawText(weekDays[d - 1], cx + DAY_W / 2, TITLE_H + 36f, t)
            val date = WeekUtils.dateOf(tt.term, week, d)
            t.color = cDim
            t.textSize = 19f
            cv.drawText(
                date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "",
                cx + DAY_W / 2, TITLE_H + 62f, t,
            )
            t.textSize = 22f
        }
        t.textAlign = Paint.Align.LEFT

        // 星期之间的竖线
        for (d in 0..7) {
            val x = LEFT_W + DAY_W * d
            cv.drawLine(x, TITLE_H.toFloat(), x, gridTop + SLOT_H * Slots.COUNT, Paint().apply { color = cLine; strokeWidth = 1f })
        }

        // ── 节次列 ──
        t.textSize = 22f
        t.textAlign = Paint.Align.CENTER
        Slots.ALL.forEach { s ->
            val cy = gridTop + SLOT_H * (s.index - 1)
            t.color = cFg
            cv.drawText("${s.index}", LEFT_W / 2, cy + SLOT_H / 2 - 10f, t)
            t.color = cDim
            t.textSize = 16f
            cv.drawText(s.begin, LEFT_W / 2, cy + SLOT_H / 2 + 12f, t)
            cv.drawText(s.end, LEFT_W / 2, cy + SLOT_H / 2 + 32f, t)
            t.textSize = 22f
        }
        t.textAlign = Paint.Align.LEFT

        // ── 横向网格线：和界面一样，画在 slot 边界上（不参与布局累加）──
        for (i in 0..Slots.COUNT) {
            val y = gridTop + SLOT_H * i
            val strong = i == 4 || i == 8
            cv.drawLine(
                LEFT_W, y, fullW.toFloat(), y,
                Paint().apply {
                    color = if (strong) cLineStrong else cLine
                    strokeWidth = if (strong) 2f else 1f
                },
            )
        }

        // ── 「中」「晚」标记（与界面同一套 slot 数学）──
        listOf(4 to (cNoon to "中"), 8 to (cNight to "晚")).forEach { (after, m) ->
            val (col, label) = m
            val y = gridTop + SLOT_H * after
            cv.drawLine(LEFT_W, y, fullW.toFloat(), y, Paint().apply { color = col; strokeWidth = 3f })
            t.textSize = 20f
            t.color = col
            t.textAlign = Paint.Align.CENTER
            cv.drawText(label, LEFT_W / 2, y + 7f, t)
            t.textAlign = Paint.Align.LEFT
        }

        // ── 课程块 ──
        for (d in 1..7) {
            tt.coursesOf(week, d).forEach { c ->
                val start = (c.startSlot - 1).coerceIn(0, Slots.COUNT - 1)
                val span = c.slotSpan.coerceAtMost(Slots.COUNT - start)
                val x = LEFT_W + DAY_W * (d - 1) + 3.5f
                val y = gridTop + SLOT_H * start + 2.5f
                val w = DAY_W - 7f
                val h = SLOT_H * span - 5f

                // 与界面同源：同一组渐变/色条/墨色，导出图不会和 App 漂色
                val (fillA, fillB) = CourseColors.fillColors(c.name)
                val (barA, barB) = CourseColors.barColors(c.name)
                val inkCol = CourseColors.ink(c.name)
                val ink = inkCol.toArgb()

                val rr = 16f
                val rect = RectF(x, y, x + w, y + h)
                // 柔和投影：一笔偏移的极淡圆角矩形（不越出本格，避免压到邻列）
                cv.drawRoundRect(
                    RectF(x + 1f, y + 3f, x + w + 1f, y + h + 1f), rr, rr,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14222B45 },
                )
                // 卡片渐变底
                cv.drawRoundRect(
                    rect, rr, rr,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = android.graphics.LinearGradient(
                            x, y, x, y + h, fillA.toArgb(), fillB.toArgb(),
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                    },
                )
                // 左侧色条：本身按「左侧圆角 = 卡片圆角、右侧直角」画出来，
                // 不依赖 clipPath（试过裁剪，导出图里角上仍是直角外露）
                val barPath = android.graphics.Path().apply {
                    addRoundRect(
                        RectF(x, y, x + 8f, y + h),
                        floatArrayOf(rr, rr, 0f, 0f, 0f, 0f, rr, rr),
                        android.graphics.Path.Direction.CW,
                    )
                }
                cv.drawPath(
                    barPath,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = android.graphics.LinearGradient(
                            x, y, x, y + h, barA.toArgb(), barB.toArgb(),
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                    },
                )

                val tx = x + 20f
                val maxW = w - 32f
                val namePaint = Paint(t).apply {
                    typeface = Typeface.DEFAULT_BOLD; textSize = 21f; color = ink
                }
                // 课程名按字折行（中文按字断行），行数按跨节数给（比界面宽一些，导出图空间更大）
                val maxNameLines = if (span >= 4) 6 else if (span >= 3) 5 else if (span >= 2) 3 else 2
                var ty = y + 27f
                wrap(c.name, maxW, namePaint, maxNameLines).forEach { line ->
                    cv.drawText(line, tx, ty, namePaint)
                    ty += 23f
                }
                // 教室：比课名小一号、半透明；教师：再小、更淡 —— 层级拉开
                val roomPaint = Paint(t).apply {
                    textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                    color = inkCol.copy(alpha = 0.72f).toArgb()
                }
                val teachPaint = Paint(t).apply {
                    textSize = 16f; color = inkCol.copy(alpha = 0.55f).toArgb()
                }
                if (c.room.isNotBlank() && ty <= y + h - 8f) {
                    cv.drawText(ellipsize(c.room, maxW, roomPaint), tx, ty, roomPaint)
                    ty += 21f
                }
                if (c.teacher.isNotBlank() && ty <= y + h - 8f) {
                    cv.drawText(ellipsize(c.teacher, maxW, teachPaint), tx, ty, teachPaint)
                }
            }
        }

        // ── 页脚 ──
        val fy = gridTop + SLOT_H * Slots.COUNT
        t.textSize = 18f
        t.color = cDim
        cv.drawText(
            "由「教务助手」导出　${java.time.LocalDate.now()}　第 $week 周",
            PAD, fy + 36f, t,
        )

        return ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            bos.toByteArray()
        }
    }

    /** 按字符宽度折行（中文按字断行），最多 maxLines 行，超出部分末行加省略号 */
    private fun wrap(s: String, maxW: Float, p: Paint, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (cur.isNotEmpty() && p.measureText(cur.toString() + ch) > maxW) {
                out.add(cur.toString())
                cur = StringBuilder()
                if (out.size >= maxLines) break
            }
            cur.append(ch)
            i++
        }
        if (out.size < maxLines && cur.isNotEmpty()) out.add(cur.toString())
        if (out.isEmpty()) return listOf(s)
        // 还有没塞下的字符 → 末行加省略号，让它看起来是被截断而不是写完了
        if (out.joinToString("").length < s.length) {
            var last = out[out.size - 1]
            while (last.isNotEmpty() && p.measureText(last + "…") > maxW) last = last.dropLast(1)
            out[out.size - 1] = last + "…"
        }
        return out
    }

    /** 简单省略号截断（不换行，够用且可预期） */
    private fun ellipsize(s: String, maxW: Float, p: Paint): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }
}
