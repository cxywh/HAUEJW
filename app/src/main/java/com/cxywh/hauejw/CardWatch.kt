package com.cxywh.hauejw

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 电费定时查询与余额预警。
 *
 * 每天在用户设定的时刻查一次，结果写本机缓存；低于预警值发一条通知；
 * 查询失败（凭据过期、网络不通）也发一条，免得用户以为还查着。
 *
 * 一切按账号分开：每个学号各排自己的闹钟、各写自己的缓存、各发自己的通知，
 * 本机存了多个学号时互不影响。
 *
 * 学校系统 23:00–03:00 不开放查询，落在这段的时刻顺延到 03:05。
 */
object CardWatch {

    /** 请求码区间与提醒（71xx）、心跳（72xx）分开 */
    private const val REQ_BASE = 0x73010000.toInt()
    private const val REQ_WATCH = 0x73030000.toInt()
    const val ACTION_WATCH = "com.cxywh.hauejw.CARD_WATCH"
    private const val NOTE_BASE = 0x73020000.toInt()
    private const val CH_ID = "card_warn"
    private const val QUIET_FROM = 23
    private const val QUIET_TO = 3
    private const val SEP = "|"
    const val EXTRA_ACCOUNT = "account"

    /** 一次查询的结果缓存 */
    data class Cache(val label: String, val value: Double, val unit: String, val at: Long)

    fun encode(label: String, value: Double, unit: String, at: Long): String =
        label.replace(SEP, "") + SEP + value + SEP + unit.replace(SEP, "") + SEP + at

    fun decode(raw: String): Cache? {
        val p = raw.split(SEP)
        if (p.size != 4) return null
        val v = p[1].toDoubleOrNull() ?: return null
        val at = p[3].toLongOrNull() ?: return null
        return Cache(p[0], v, p[2], at)
    }

    // ───────────────────────── 用电历史 ─────────────────────────

    /** 一次读数里的累计用电量（usedAmp），与余额一起记，用来算日均 */
    data class Sample(val at: Long, val used: Double)

    private const val SAMPLE_SEP = ";"
    private const val SAMPLE_KV = ":"
    private const val DAY_MS = 24L * 3600_000L

    /** 历史留 14 天（多一倍余量应付漏查），日均只看最近 7 天 */
    private const val HISTORY_DAYS = 14L
    private const val WINDOW_DAYS = 7L

    fun decodeHistory(raw: String): List<Sample> =
        raw.split(SAMPLE_SEP).mapNotNull { p ->
            val kv = p.split(SAMPLE_KV)
            if (kv.size != 2) return@mapNotNull null
            val at = kv[0].toLongOrNull() ?: return@mapNotNull null
            val used = kv[1].toDoubleOrNull() ?: return@mapNotNull null
            Sample(at, used)
        }.sortedBy { it.at }

    fun encodeHistory(list: List<Sample>): String =
        list.joinToString(SAMPLE_SEP) { it.at.toString() + SAMPLE_KV + String.format(java.util.Locale.US, "%.2f", it.used) }

    /**
     * 追加一条样本。同一天只留最后一条，超过 14 天的清掉；
     * used 为 0 表示服务端没给这个字段，直接不记（记了会把日均压成 0）。
     */
    fun pushHistory(raw: String, used: Double, at: Long): String {
        if (used <= 0.0) return raw
        val kept = decodeHistory(raw)
            .filter { it.at > at - HISTORY_DAYS * DAY_MS && it.at <= at }
            .filterNot { it.at / DAY_MS == at / DAY_MS }
        return encodeHistory(kept + Sample(at, used))
    }

    /** 日均用电：perDay 是度/天，days 是实际用到几天样本（不足 7 天时按实际的写） */
    data class Usage(val perDay: Double, val days: Int)

    /**
     * 按最近 7 天的样本算日均；样本跨度不足 1 天、或读数倒退（换表/清零）返回 null。
     * 头几天只有一两条记录时，用实际跨度算并如实标出天数，不硬凑成 7 天。
     */
    fun usage(raw: String, now: Long): Usage? {
        val win = decodeHistory(raw).filter { it.at in (now - (WINDOW_DAYS + 1) * DAY_MS)..now }
        if (win.size < 2) return null
        val span = (win.last().at - win.first().at).toDouble() / DAY_MS
        if (span < 1.0) return null
        val used = win.last().used - win.first().used
        if (used <= 0.0) return null
        return Usage(used / span, span.toInt().coerceIn(1, WINDOW_DAYS.toInt()))
    }

    fun dailyUse(raw: String, now: Long): Double? = usage(raw, now)?.perDay

    /** 按当前余额够用多少天；数据不够返回 null */
    fun daysLeft(balance: Double, raw: String, now: Long): Int? {
        val perDay = dailyUse(raw, now) ?: return null
        if (perDay <= 0.0) return null
        return kotlin.math.floor(balance / perDay).toInt().coerceAtLeast(0)
    }

    /** 每个账号一个请求码，排别人的闹钟不会顶掉这个 */
    fun reqOf(account: String): Int = REQ_BASE or (account.hashCode() and 0xFFFF)

    private fun noteIdOf(account: String): Int = NOTE_BASE or (account.hashCode() and 0xFF)

    /** 落在不可查询时段就顺延到当天/次日的 03:05 */
    fun shiftOutOfQuiet(at: ZonedDateTime): ZonedDateTime = when {
        at.hour >= QUIET_FROM -> at.plusDays(1).withHour(QUIET_TO).withMinute(5)
        at.hour < QUIET_TO -> at.withHour(QUIET_TO).withMinute(5)
        else -> at
    }

    /** 下一次查询时刻；就算设到 01:00，也会被推到 03:05 */
    fun nextAt(hour: Int, minute: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val base = Instant.ofEpochMilli(now).atZone(zone)
        var at = base.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59))
            .withSecond(0).withNano(0)
        if (!at.isAfter(base)) at = at.plusDays(1)
        at = shiftOutOfQuiet(at)
        return at.toInstant().toEpochMilli()
    }

    /** 按这个账号自己的设置排期；关掉了就取消它的闹钟 */
    fun sync(ctx: Context, account: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, account)
        am.cancel(pi)
        if (account.isBlank()) return
        val p = Prefs(ctx, account)
        if (!p.cardAutoOn) {
            p.cardNextAt = 0L
            return
        }
        val at = nextAt(p.cardHour, p.cardMinute, System.currentTimeMillis())
        p.cardNextAt = at
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { e ->
            Diag.record("CardWatch.arm", e)
            runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    /** 本机存过的账号各按自己的设置重排一次（开机、改系统时间后用） */
    fun syncAll(ctx: Context) {
        val accounts = accounts(ctx)
        accounts.forEach { sync(ctx, it) }
    }

    /** 两条通知之间至少隔这么久，免得一天里反复响 */
    private const val WARN_COOLDOWN_MS = 20L * 3600_000L
    private const val FAIL_COOLDOWN_MS = 12L * 3600_000L

    /** 看门狗周期与「刚挂过就不重挂」的窗口 */
    private const val WATCHDOG_MS = 6L * 3600_000L
    private const val WATCHDOG_SLACK_MS = 5L * 3600_000L

    /** 超过预定时刻这么久才算漏查 */
    private const val CATCH_UP_LAG_MS = 10L * 60_000L

    /** 该不该补查：开着、排过、过了预定时刻 10 分钟以上，且不在禁查时段 */
    fun shouldCatchUp(enabled: Boolean, next: Long, now: Long, hour: Int): Boolean {
        if (!enabled || next <= 0L) return false
        if (hour >= QUIET_FROM || hour < QUIET_TO) return false
        return now > next + CATCH_UP_LAG_MS
    }

    /** 本机所有学号：本地设置里记的 + 存过凭据的 */
    private fun accounts(ctx: Context): List<String> =
        (Prefs(ctx).accounts + SecretStore.accounts(ctx)).distinct()

    /**
     * 补查：进程被杀、闹钟被系统清掉之后，下次有机会时把漏掉的那次补上。
     * 判断依据是本机记下的「下次该查的时刻」，超过 10 分钟算漏。
     * 23:00–03:00 不查，只把闹钟重排一次。
     */
    fun catchUp(ctx: Context) {
        val accounts = accounts(ctx)
        val nowZ = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        accounts.forEach { acc ->
            val p = Prefs(ctx, acc)
            if (!p.cardAutoOn) return@forEach
            val now = System.currentTimeMillis()
            if (shouldCatchUp(p.cardAutoOn, p.cardNextAt, now, nowZ.hour)) {
                runCatching { check(ctx, acc) }.onFailure { Diag.record("CardWatch.catchUp", it) }
            }
            sync(ctx, acc)
        }
    }

    /**
     * 看门狗：每 6 小时醒一次，把各账号的定时查询重新排上。
     * 华为/Honor 一类系统清后台、清闹钟之后，靠这条把查询接回来。
     */
    fun ensureWatchdog(ctx: Context) {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.watchArmedAt in 0 until WATCHDOG_SLACK_MS) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx,
            REQ_WATCH,
            Intent(ctx, CardWatchReceiver::class.java).setAction(ACTION_WATCH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                now + WATCHDOG_MS,
                WATCHDOG_MS,
                pi,
            )
        }.onSuccess { p.watchArmedAt = now }.onFailure { Diag.record("CardWatch.watchdog", it) }
    }

    private fun pending(ctx: Context, account: String): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        reqOf(account),
        Intent(ctx, CardWatchReceiver::class.java).putExtra(EXTRA_ACCOUNT, account),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 查一次并落缓存；低余额与查询失败各发一条通知。
     * 取数在 [fetch] 里，这里只管结果落盘与要不要提醒。
     */
    fun check(ctx: Context, account: String) {
        if (account.isBlank()) return
        val p = Prefs(ctx, account)
        val item = p.cardItemId
        if (p.cardRoom.isBlank() || item.isBlank()) return

        runCatching { fetch(ctx, account, p, item) }
            .onSuccess { a -> onOk(ctx, account, p, a) }
            .onFailure { e -> onFail(ctx, account, p, e) }
    }

    private fun fetch(ctx: Context, account: String, p: Prefs, item: String): CardFee.Amount {
        val picked = CardFee.roomOf(p.cardRoom)
        var auth = CardFee.load(ctx, account) ?: throw CardFee.NeedLogin()
        if (!auth.alive()) {
            auth = CardFee.refresh(auth) ?: throw CardFee.NeedLogin()
            CardFee.save(ctx, account, auth)   // 刷新到的新 token 直接落盘，下次少换一次
        }
        return CardFee.meters(ctx, auth.token, item, picked, picked.size)
    }

    private fun onOk(ctx: Context, account: String, p: Prefs, a: CardFee.Amount) {
        val at = System.currentTimeMillis()
        p.cardCache = encode(a.label, a.value, a.unit, at)
        p.cardHistory = pushHistory(p.cardHistory, a.used, at)
        // 桌面小组件显示的就是这份缓存，查完立刻推一次，不必等下一次心跳
        runCatching { HaueWidget.refresh(ctx) }
        if (!p.cardWarnOn || a.value > p.cardWarn) return
        val now = System.currentTimeMillis()
        if (now - p.cardLastNotify <= WARN_COOLDOWN_MS) return
        val where = p.cardRoomName.ifBlank { "宿舍电费" }
        notify(
            ctx, account,
            "电费余额低",
            where + " 剩余 " + fmtNum(a.value) + a.unit + "，已低于预警 " + fmtNum(p.cardWarn) + a.unit,
        )
        p.cardLastNotify = now
    }

    private fun onFail(ctx: Context, account: String, p: Prefs, e: Throwable) {
        Diag.record("CardWatch.check", e)
        val now = System.currentTimeMillis()
        if (now - p.cardLastNotify <= FAIL_COOLDOWN_MS) return
        notify(
            ctx, account,
            "电费查询失败",
            if (e is CardFee.NeedLogin) "登录已过期，去「电费查询」重新登录一次。"
            else "查询出错：" + (e.message ?: e.javaClass.simpleName),
        )
        p.cardLastNotify = now
    }

    /** 87.0 → "87"，87.41 → "87.41"；小组件的电费行也用这个 */
    internal fun fmtNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CH_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CH_ID, "电费提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "电费余额低于预警值，或定时查询失败"
            }
        )
    }

    private fun notify(ctx: Context, account: String, title: String, text: String) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(account)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(noteIdOf(account), n) }
    }
}

/** 定时查询的落点：查完把下一次排上，账号随 Intent 带过来 */
class CardWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 看门狗：只负责把各账号的定时查询重新排上，并补上漏掉的那次
        if (intent.action == CardWatch.ACTION_WATCH) {
            val pending = goAsync()
            Thread {
                try {
                    CardWatch.catchUp(context)
                } catch (e: Exception) {
                    Diag.record("CardWatch.watchdog", e)
                } finally {
                    CardWatch.ensureWatchdog(context)
                    pending.finish()
                }
            }.start()
            return
        }
        val account = intent.getStringExtra(CardWatch.EXTRA_ACCOUNT).orEmpty()
        if (account.isBlank()) return
        val pending = goAsync()
        Thread {
            try {
                CardWatch.check(context, account)
            } catch (e: Exception) {
                Diag.record("CardWatch.check", e)
            } finally {
                CardWatch.sync(context, account)
                pending.finish()
            }
        }.start()
    }
}
