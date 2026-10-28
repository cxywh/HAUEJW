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
 * 学校系统 23:00–03:00 不开放查询，落在这段的时刻顺延到 03:05。
 */
object CardWatch {

    const val REQ = 7301
    private const val CH_ID = "card_warn"
    private const val NOTE_ID = 7301
    private const val QUIET_FROM = 23
    private const val QUIET_TO = 3
    private const val SEP = "|"

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

    fun schedule(ctx: Context, prefs: Prefs) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)
        if (!prefs.cardAutoOn) return
        val at = nextAt(prefs.cardHour, prefs.cardMinute, System.currentTimeMillis())
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { e ->
            Diag.record("CardWatch.arm", e)
            runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ,
        Intent(ctx, CardWatchReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 查一次并落缓存；失败与低余额各发一条通知 */
    fun check(ctx: Context, prefs: Prefs) {
        val sno = prefs.username
        val room = prefs.cardRoom
        val item = prefs.cardItemId
        if (sno.isBlank() || room.isBlank() || item.isBlank()) return
        val picked = CardFee.roomOf(room)
        runCatching {
            var auth = CardFee.load(ctx, sno) ?: throw CardFee.NeedLogin()
            if (!auth.alive()) auth = CardFee.refresh(auth) ?: throw CardFee.NeedLogin()
            CardFee.meters(ctx, auth.token, item, picked, picked.size)
        }.onSuccess { a ->
            prefs.cardCache = encode(a.label, a.value, a.unit, System.currentTimeMillis())
            if (prefs.cardWarnOn && a.value <= prefs.cardWarn) {
                val now = System.currentTimeMillis()
                if (now - prefs.cardLastNotify > 20L * 3600_000L) {
                    val where = prefs.cardRoomName.ifBlank { "宿舍电费" }
                    notify(
                        ctx,
                        "电费余额低",
                        where + " 剩余 " + fmt(a.value) + a.unit + "，已低于预警 " +
                            fmt(prefs.cardWarn) + a.unit,
                    )
                    prefs.cardLastNotify = now
                }
            }
        }.onFailure { e ->
            val now = System.currentTimeMillis()
            if (now - prefs.cardLastNotify > 12L * 3600_000L) {
                notify(
                    ctx,
                    "电费查询失败",
                    if (e is CardFee.NeedLogin) "校园卡登录已过期，去「校园卡余额」重新登录一次。"
                    else "查询出错：" + (e.message ?: e.javaClass.simpleName),
                )
                prefs.cardLastNotify = now
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

    private fun notify(ctx: Context, title: String, text: String) {
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTE_ID, n) }
    }
}

/** 定时查询的落点：查完把下一次排上 */
class CardWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            val prefs = Prefs(context)
            try {
                CardWatch.check(context, prefs)
            } catch (e: Exception) {
                Diag.record("CardWatch.check", e)
            } finally {
                CardWatch.schedule(context, prefs)
                pending.finish()
            }
        }.start()
    }
}
