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
        val accounts = (Prefs(ctx).accounts + SecretStore.accounts(ctx)).distinct()
        accounts.forEach { sync(ctx, it) }
    }

    /**
     * 补查：进程被杀、闹钟被系统清掉之后，下次有机会时把漏掉的那次补上。
     * 判断依据是本机记下的「下次该查的时刻」，超过 10 分钟算漏。
     * 23:00–03:00 不查，只把闹钟重排一次。
     */
    /** 该不该补查：开着、排过、过了预定时刻 10 分钟以上，且不在禁查时段 */
    fun shouldCatchUp(enabled: Boolean, next: Long, now: Long, hour: Int): Boolean {
        if (!enabled || next <= 0L) return false
        if (hour >= QUIET_FROM || hour < QUIET_TO) return false
        return now > next + 10 * 60_000L
    }

    fun catchUp(ctx: Context) {
        val accounts = (Prefs(ctx).accounts + SecretStore.accounts(ctx)).distinct()
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
        if (now - p.watchArmedAt in 0 until 5L * 3600_000L) return
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
                now + 6L * 3600_000L,
                6L * 3600_000L,
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

    /** 查一次并落缓存；失败与低余额各发一条通知 */
    fun check(ctx: Context, account: String) {
        if (account.isBlank()) return
        val p = Prefs(ctx, account)
        val room = p.cardRoom
        val item = p.cardItemId
        if (room.isBlank() || item.isBlank()) return
        val picked = CardFee.roomOf(room)
        runCatching {
            var auth = CardFee.load(ctx, account) ?: throw CardFee.NeedLogin()
            if (!auth.alive()) auth = CardFee.refresh(auth) ?: throw CardFee.NeedLogin()
            CardFee.meters(ctx, auth.token, item, picked, picked.size)
        }.onSuccess { a ->
            p.cardCache = encode(a.label, a.value, a.unit, System.currentTimeMillis())
            if (p.cardWarnOn && a.value <= p.cardWarn) {
                val now = System.currentTimeMillis()
                if (now - p.cardLastNotify > 20L * 3600_000L) {
                    val where = p.cardRoomName.ifBlank { "宿舍电费" }
                    notify(
                        ctx, account,
                        "电费余额低",
                        where + " 剩余 " + fmt(a.value) + a.unit + "，已低于预警 " +
                            fmt(p.cardWarn) + a.unit,
                    )
                    p.cardLastNotify = now
                }
            }
        }.onFailure { e ->
            val now = System.currentTimeMillis()
            if (now - p.cardLastNotify > 12L * 3600_000L) {
                notify(
                    ctx, account,
                    "电费查询失败",
                    if (e is CardFee.NeedLogin) "登录已过期，去「电费查询」重新登录一次。"
                    else "查询出错：" + (e.message ?: e.javaClass.simpleName),
                )
                p.cardLastNotify = now
            }
        }
    }

    private fun fmt(v: Double): String =
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
