package com.cxywh.hauejw

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 桌面小组件：今天课表 + 下一节课 + 学期进度/距放假 + 今天还剩几节。
 *
 * 数据全部来自 [Prefs] 的本地缓存（loadBase + 本地修改），所以
 * **不需要联网、也不需要 App 在后台运行** —— 这正是它和「打开 App 才能看」的本质区别。
 *
 * 颜色不用 Compose（RemoteViews 里没有 Compose，也读不到 C 这个运行时配色），
 * 而是按 Prefs.theme 在下面 [wFg] 等常量里取一套固定值 —— 与 Theme.kt 的 Palettes 手工对齐，
 * 改主题时两边要一起改。
 */
class HaueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // goAsync：onUpdate 跑在主线程，而解析课表 JSON 是要花时间的，不能让主线程等
        val pending = goAsync()
        Thread {
            try {
                renderAndSchedule(context)
            } catch (e: Exception) {
                Diag.record("HaueWidget.onUpdate", e)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) {
            val pending = goAsync()
            Thread {
                try {
                    renderAndSchedule(context)
                } catch (e: Exception) {
                    Diag.record("HaueWidget.onTick", e)
                } finally {
                    runCatching { pending.finish() }
                }
            }.start()
        }
    }

    override fun onEnabled(context: Context) = refresh(context)

    override fun onDisabled(context: Context) = cancelAll(context)

    companion object {

        const val ACTION_TICK = "com.cxywh.hauejw.WIDGET_TICK"

        private const val REQ_TICK = 7101
        private const val REQ_TAP = 7102
        private const val REQ_KEY_BASE = 7200
        private const val MAX_KEY = 12

        private val ROWS = intArrayOf(R.id.w_row0, R.id.w_row1, R.id.w_row2, R.id.w_row3)
        private val TIMES = intArrayOf(R.id.w_t0, R.id.w_t1, R.id.w_t2, R.id.w_t3)
        private val NAMES = intArrayOf(R.id.w_c0, R.id.w_c1, R.id.w_c2, R.id.w_c3)
        private val ROOMS = intArrayOf(R.id.w_r0, R.id.w_r1, R.id.w_r2, R.id.w_r3)

        // ── 小组件配色（与 Theme.kt 的 Palettes.light / .dark 对齐）──
        private fun wBg(dark: Boolean) = if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light
        private fun wFg(dark: Boolean) = if (dark) 0xFFEFF1F6.toInt() else 0xFF16191F.toInt()
        private fun wSub(dark: Boolean) = if (dark) 0xFFB4BECD.toInt() else 0xFF5C6675.toInt()
        private fun wDim(dark: Boolean) = if (dark) 0xFF8C96A6.toInt() else 0xFF9AA3B2.toInt()
        private fun wAccent(dark: Boolean) = if (dark) 0xFF8B95FF.toInt() else 0xFF4F46E5.toInt()

        /**
         * 请系统弹出「把小组件添加到桌面」的确认框（Android 8.0+ 的 requestPinAppWidget）。
         * 放进 App 里是为了不必去启动器里翻找 —— 对不熟桌面的用户，这一下就是全部操作。
         * 返回 false 表示这台设备的桌面不支持该方式（会弹一句说明，让用户改用桌面长按）。
         */
        fun pin(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            val provider = ComponentName(ctx, HaueWidget::class.java)
            if (!mgr.isRequestPinAppWidgetSupported) {
                Toast.makeText(
                    ctx,
                    "这台设备的桌面不支持从应用内添加小组件，请在桌面长按空白处手动添加",
                    Toast.LENGTH_LONG,
                ).show()
                return false
            }
            return runCatching { mgr.requestPinAppWidget(provider, null, null) }
                .onFailure { e -> Diag.record("HaueWidget.pin", e) }
                .getOrDefault(false)
        }

        /** App 侧数据一变（登录 / 更新课表 / 改过课 / 换主题）就调它；内部自己开线程，可直接调 */
        fun refresh(ctx: Context) {
            Thread {
                runCatching { renderAndSchedule(ctx) }
                    .onFailure { e -> Diag.record("HaueWidget.refresh", e) }
            }.start()
        }

        // ────────────────────────── 渲染 ──────────────────────────

        private fun renderAndSchedule(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, HaueWidget::class.java))
            if (ids.isEmpty()) return

            val prefs = Prefs(ctx)
            val now = LocalDateTime.now()
            val tt = runCatching {
                prefs.loadBase()?.let { base -> base.withCourses(prefs.edits.apply(base.courses)) }
            }.onFailure { e -> Diag.record("HaueWidget.load", e) }.getOrNull()

            for (id in ids) {
                runCatching { mgr.updateAppWidget(id, build(ctx, prefs, tt, now)) }
                    .onFailure { e -> Diag.record("HaueWidget.update", e) }
            }
            reschedule(ctx, tt, now)
        }

        private fun build(ctx: Context, prefs: Prefs, tt: Timetable?, now: LocalDateTime): RemoteViews {
            val dark = prefs.theme == "dark"
            val st = widgetState(tt, now)
            val v = RemoteViews(ctx.packageName, R.layout.widget_board)
            v.setInt(R.id.w_root, "setBackgroundResource", wBg(dark))
            v.setOnClickPendingIntent(R.id.w_root, tapPi(ctx))

            v.setTextViewText(R.id.w_week, st.week)
            v.setTextColor(R.id.w_week, wFg(dark))
            v.setTextViewText(R.id.w_date, st.date)
            v.setTextColor(R.id.w_date, wDim(dark))
            v.setTextViewText(R.id.w_term, st.term)
            v.setTextColor(R.id.w_term, wDim(dark))
            v.setTextViewText(R.id.w_today, st.today)
            v.setTextColor(R.id.w_today, wAccent(dark))

            for (i in 0 until WIDGET_ROWS) {
                val r = st.rows.getOrNull(i)
                if (r == null) {
                    v.setViewVisibility(ROWS[i], View.GONE)
                    v.setTextViewText(TIMES[i], "")
                    v.setTextViewText(NAMES[i], "")
                    v.setTextViewText(ROOMS[i], "")
                    continue
                }
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                v.setTextViewText(TIMES[i], r.time)
                v.setTextColor(TIMES[i], if (r.past) wDim(dark) else wAccent(dark))
                v.setTextViewText(NAMES[i], r.name)
                v.setTextColor(NAMES[i], when {
                    r.note -> wSub(dark)
                    r.past -> wDim(dark)
                    else -> wFg(dark)
                })
                v.setTextViewText(ROOMS[i], r.room)
                v.setTextColor(ROOMS[i], if (r.past) wDim(dark) else wSub(dark))
            }

            // st.empty 非空 = 还没有课表；否则那块提示整块隐藏
            if (st.empty != null) {
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
                v.setTextViewText(R.id.w_empty, st.empty)
                v.setTextColor(R.id.w_empty, wAccent(dark))
            } else {
                v.setViewVisibility(R.id.w_empty, View.GONE)
            }

            v.setTextViewText(R.id.w_next, st.next)
            v.setTextColor(R.id.w_next, wSub(dark))

            return v
        }

        // ────────────────────────── 刷新调度 ──────────────────────────

        /**
         * 闹钟没法定"每节课开始/结束都醒一次"以外更精细的时刻，这里就取三个关键点：
         * ① 每半小时一次兜底滴答（设备在用的时候自然会跑到）
         * ② 今天每节课的**结束时刻**（"还剩 N 节"要及时减一）
         * ③ **下一节课的开始时刻** + 次日零点（日期与周次都要翻页）
         */
        private fun reschedule(ctx: Context, tt: Timetable?, now: LocalDateTime) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching {
                am.setInexactRepeating(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 60_000L,
                    AlarmManager.INTERVAL_HALF_HOUR,
                    pi(ctx, ACTION_TICK, REQ_TICK),
                )
            }.onFailure { e -> Diag.record("HaueWidget.tick", e) }

            for (i in 0 until MAX_KEY) am.cancel(pi(ctx, ACTION_TICK, REQ_KEY_BASE + i))

            if (tt == null) return
            val nowMs = System.currentTimeMillis()
            val at = mutableListOf<Long>()
            coursesOn(tt, now.toLocalDate()).forEach { c ->
                Slots.of(c.endSlot)?.let { at += atMillis(now.toLocalDate(), it.endTime) }
            }
            nextClass(tt, now)?.let { at += atMillis(it.date, it.begin) }
            at += LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            at.filter { it > nowMs }.distinct().sorted().take(MAX_KEY)
                .forEachIndexed { i, t -> oneShot(am, ctx, t, REQ_KEY_BASE + i) }
        }

        private fun oneShot(am: AlarmManager, ctx: Context, at: Long, req: Int) {
            val p = pi(ctx, ACTION_TICK, req)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
                else am.set(AlarmManager.RTC_WAKEUP, at, p)
            }.onFailure { e -> Diag.record("HaueWidget.oneShot", e) }
        }

        private fun cancelAll(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx, ACTION_TICK, REQ_TICK))
            for (i in 0 until MAX_KEY) am.cancel(pi(ctx, ACTION_TICK, REQ_KEY_BASE + i))
        }

        private fun atMillis(date: LocalDate, time: LocalTime): Long =
            LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        private fun pi(ctx: Context, action: String, req: Int): PendingIntent {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(ctx, req, Intent(ctx, HaueWidget::class.java).setAction(action), flags)
        }

        private fun tapPi(ctx: Context): PendingIntent {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(
                ctx, REQ_TAP,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
            )
        }
    }
}
