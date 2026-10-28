package com.cxywh.hauejw

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.PowerManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 一条待发提醒。
 * 自包含：接收器只读 Intent/JSON，不用再去解课表 —— 免得开机时课表还没解出来就漏发。
 */
data class RemindItem(
    val at: Long,            // 触发时刻（epoch millis）
    val name: String,        // 课名
    val slotText: String,    // 第 3-4 节
    val startText: String,   // 10:25
    val room: String,
    val teacher: String,
    val lead: Int,           // 提前分钟数（0 = 上课时刻）
    val style: String = Reminder.STYLE_NOTIFY,   // 用户选的提醒方式：消息 / 闹钟
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at); put("name", name); put("slot", slotText)
        put("start", startText); put("room", room); put("teacher", teacher); put("lead", lead)
        put("style", style)
    }

    companion object {
        fun fromJson(o: JSONObject) = RemindItem(
            at = o.optLong("at"),
            name = o.optString("name"),
            slotText = o.optString("slot"),
            startText = o.optString("start"),
            room = o.optString("room"),
            teacher = o.optString("teacher"),
            lead = o.optInt("lead"),
            style = o.optString("style", Reminder.STYLE_NOTIFY),
        )
    }
}

/**
 * 课前提醒。
 *
 * 不是“定一个闹钟就完事”：把未来 WINDOW_DAYS 天的**每一节课**各排一条通知，
 * 同一天有多门课就各自发一条消息（课名 + 节次 + 时间 + 教室 + 教师），
 * 到点由系统唤醒接收器发出，**不需要 App 在后台常驻**。
 *
 * 链路：
 *   1. [reschedule] 算出待发清单 → 落盘到 Prefs（自包含）→ 逐条交给 AlarmManager
 *   2. 到点系统唤醒 [RemindReceiver] → 直接用 Intent 里的内容发通知
 *   3. 开机 / 应用升级后 [BootReceiver] 用落盘的清单重新武装闹钟
 */
object Reminder {

    /** 提醒方式：消息（横幅通知） / 闹钟（整屏弹出 + 铃声） */
    const val STYLE_NOTIFY = "notify"
    const val STYLE_ALARM = "alarm"

    const val CH_ID = "class_remind"

    /** 闹钟式提醒用另一个渠道：走闹钟音量、带铃声、允许整屏 */
    const val CH_ALARM = "class_alarm"
    const val EXTRA_PAYLOAD = "item"

    /** 排未来多少天（两周：窗口短、课表一改就重排，永远新鲜） */
    private const val WINDOW_DAYS = 14

    /** 闹钟条数上限（系统每应用上限数百条，这里留足余量） */
    private const val MAX_ALARMS = 300

    /** 测试提醒固定用这个 requestCode，免得踩到正常提醒 */
    private const val TEST_REQ = 9001

    // ────────────────────────── 通知渠道 ──────────────────────────

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CH_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_ID, "课前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "上课前的消息提醒（横幅）"
                    enableVibration(true)
                }
            )
        }
        if (mgr.getNotificationChannel(CH_ALARM) == null) {
            val ch = NotificationChannel(CH_ALARM, "课前提醒（闹钟式）", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "上课前的闹钟式提醒：整屏弹出 + 闹钟铃声"
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ch.setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)   // 走闹钟音量，静音也能响
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            mgr.createNotificationChannel(ch)
        }
    }

    // ────────────────────────── 排程 ──────────────────────────

    /** 算出「什么时候、哪门课」的待发清单（只取未来、按时间排序） */
    fun plan(
        tt: Timetable,
        leadMinutes: Int,
        style: String = STYLE_NOTIFY,
        from: LocalDateTime = LocalDateTime.now(),
    ): List<RemindItem> {
        val out = mutableListOf<RemindItem>()
        // 窗口从 from 当天起算：from 默认是「现在」，测试里注入固定时刻时结果才是确定的
        val today = from.toLocalDate()
        for (i in 0 until WINDOW_DAYS) {
            val date = today.plusDays(i.toLong())
            val week = WeekUtils.weekOf(tt.term, date) ?: continue
            val dow = WeekUtils.dowOf(date)
            tt.coursesOf(week, dow).forEach { c ->
                if (c.isPractice) return@forEach          // 集中实践是整周，不按节次提醒
                val begin = Slots.of(c.startSlot)?.beginTime ?: return@forEach
                val at = LocalDateTime.of(date, begin).minusMinutes(leadMinutes.toLong())
                if (!at.isAfter(from)) return@forEach      // 已经过去的（含刚过去的）不排
                out.add(
                    RemindItem(
                        at = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        name = c.name,
                        slotText = if (c.startSlot == c.endSlot) "第 ${c.startSlot} 节"
                        else "第 ${c.startSlot}-${c.endSlot} 节",
                        startText = Slots.of(c.startSlot)?.begin ?: "",
                        room = c.roomText,
                        teacher = c.teacher,
                        lead = leadMinutes,
                        style = style,
                    )
                )
            }
        }
        return out.sortedBy { it.at }.take(MAX_ALARMS)
    }

    /** 重排：开关变化 / 课表变化 / 启动后调用。会覆盖旧清单 */
    fun reschedule(ctx: Context, tt: Timetable?, prefs: Prefs) {
        if (tt == null || !prefs.remindEnabled) {
            cancelAll(ctx)
            prefs.remindPlan = null
            return
        }
        ensureChannel(ctx)
        val items = plan(tt, prefs.remindMinutes, prefs.remindStyle)
        prefs.remindPlan = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
        arm(ctx, items)
    }

    /** 开机 / 应用升级后：用落盘清单重新武装（不需要课表） */
    fun rearmFromPlan(ctx: Context, prefs: Prefs) {
        cancelAll(ctx)
        if (!prefs.remindEnabled) return
        ensureChannel(ctx)
        val now = System.currentTimeMillis()
        val alive = readPlan(prefs).filter { it.at > now }      // 丢掉已经过期的
        arm(ctx, alive)
    }

    /** 取消全部已排的提醒（含测试位） */
    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_ALARMS) am.cancel(pendingFor(ctx, i, null))
        am.cancel(pendingFor(ctx, TEST_REQ, null))
    }

    /**
     * 把清单逐条交给系统闹钟。
     * 必须先 cancelAll：本次清单可能比上一次短（改了窗口/删了课），
     * 不清理就留下旧索引的闹钟 —— 它们会按「旧的课、旧的时间」乱响。
     * （踩过：窗口从 5 周改 2 周后，11-03 的残留闹钟还在。）
     */
    private fun arm(ctx: Context, items: List<RemindItem>) {
        cancelAll(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        items.forEachIndexed { i, item ->
            val pi = pendingFor(ctx, i, item)
            // 精确闹钟要权限：Android 12/13 起没授权就退化成「窗口内」提醒（误差几分钟，课前提醒够用）
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
                else am.setWindow(AlarmManager.RTC_WAKEUP, item.at, 10 * 60_000L, pi)
            }.onFailure { e -> Diag.record("Reminder.arm", e) }.onFailure {
                runCatching { am.set(AlarmManager.RTC_WAKEUP, item.at, pi) }.onFailure { e -> Diag.record("Reminder.arm", e) }
            }
        }
    }

    private fun pendingFor(ctx: Context, req: Int, item: RemindItem?): PendingIntent {
        val i = Intent(ctx, RemindReceiver::class.java).apply {
            action = Reminder::class.java.name
            if (item != null) putExtra(EXTRA_PAYLOAD, item.toJson().toString())
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, req, i, flags)
    }

    /** delaySec 秒后发一条测试通知，用来验证整条链路是不是通的 */
    fun testIn(ctx: Context, prefs: Prefs, style: String = STYLE_NOTIFY, delaySec: Long = 5) {
        ensureChannel(ctx)
        val item = RemindItem(
            at = System.currentTimeMillis() + delaySec * 1000,
            name = "测试提醒",
            slotText = "第 1 节",
            startText = "08:30",
            room = "7B401",
            teacher = "本人",
            lead = prefs.remindMinutes,
            style = style,
        )
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingFor(ctx, TEST_REQ, item)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
            else am.set(AlarmManager.RTC_WAKEUP, item.at, pi)
        }.onFailure { e -> Diag.record("Reminder.testIn", e) }
    }

    private fun readPlan(prefs: Prefs): List<RemindItem> = try {
        val arr = JSONArray(prefs.remindPlan ?: "[]")
        (0 until arr.length()).map { RemindItem.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }


    // ────────────────────────── 发通知 ──────────────────────────

    /**
     * 叫醒屏幕。
     *
     * 为什么不用全屏意图就完事：Android 14+ 起 USE_FULL_SCREEN_INTENT 不再默认授予，
     * 华为/Honor 对全屏通知另有管控 —— 结果就是「铃声响了、屏幕不亮」。
     * 所以闹钟模式下直接在广播里抢一把带 ACQUIRE_CAUSES_WAKEUP 的锁：
     * 它是闹钟类应用点亮屏幕最硬的手段，且 60 秒后自动释放，不会耗电。
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "hauejw:alarm"
            )
            wl.acquire(60_000L)
        }.onFailure { e -> Diag.record("Reminder.wakeScreen", e) }
    }

    /** 全屏界面的 Intent（闹钟模式下既给通知做全屏意图，也直接尝试拉起） */
    private fun alarmIntent(ctx: Context, item: RemindItem): Intent =
        Intent(ctx, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("name", item.name)
            .putExtra("slot", item.slotText)
            .putExtra("start", item.startText)
            .putExtra("room", item.room)
            .putExtra("teacher", item.teacher)
            .putExtra("lead", item.lead)

    fun post(ctx: Context, item: RemindItem) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return      // 没授权就别硬发，静默跳过

        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val title = if (item.lead > 0) "${item.lead} 分钟后上课 · ${item.name}"
        else "该上课了 · ${item.name}"
        val detail = listOf(item.slotText, item.startText, item.room, item.teacher)
            .filter { it.isNotBlank() }.joinToString("　")
        val text = if (item.lead > 0) "还有 ${item.lead} 分钟\n$detail" else "现在上课\n$detail"

        val alarm = item.style == STYLE_ALARM
        val b = NotificationCompat.Builder(ctx, if (alarm) CH_ALARM else CH_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)

        if (alarm) {
            // 闹钟式：整屏接管（锁屏也弹出），用户不点「知道了」不会消失
            val full = PendingIntent.getActivity(
                ctx, 1,
                alarmIntent(ctx, item),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
            )
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
                .setFullScreenIntent(full, true)

            // ① 先点亮屏幕（不依赖系统的全屏意图是否放行）
            wakeScreen(ctx)
            // ② 再直接拉起整屏界面。广播接收器带临时白名单，多数 ROM 允许；
            //    被拦也无所谓 —— 屏幕已亮、通知已响，用户点通知一样进得来。
            runCatching { ctx.startActivity(alarmIntent(ctx, item)) }.onFailure { e -> Diag.record("Reminder.post", e) }
        } else {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
        }

        runCatching { nm.notify(item.name.hashCode() * 31 + item.at.toInt(), b.build()) }.onFailure { e -> Diag.record("Reminder.post", e) }
    }
}

/** 到点被系统唤醒 → 直接发通知（内容全在 Intent 里） */
class RemindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(Reminder.EXTRA_PAYLOAD) ?: return
        val item = runCatching { RemindItem.fromJson(JSONObject(raw)) }.onFailure { e -> Diag.record("Reminder.onReceive", e) }.getOrNull() ?: return
        Reminder.post(context, item)
    }
}

/** 开机 / 升级 / 插拔电源后重新武装闹钟（系统会清掉已排闹钟，不重排就永久失效） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // 插拔电源是个白捡的恢复点：每天充电都会经过这里。系统把后台限制住、闹钟被掐掉之后，
            // 这条能替用户把小组件的换句链重新接上，不用他专门开一次 App。
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> {
                val p = Prefs(context)
                Reminder.rearmFromPlan(context, p)
                CardWatch.syncAll(context)
                // 开机后闹钟全被清空，看门狗重新挂上
                p.watchArmedAt = 0L
                CardWatch.ensureWatchdog(context)
                // 闹钟被系统清空后小组件也停在旧内容上，一并刷新
                HaueWidget.refresh(context)
            }
        }
    }
}
