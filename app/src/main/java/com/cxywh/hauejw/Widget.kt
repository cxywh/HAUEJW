package com.cxywh.hauejw

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面小组件：今天课表 + 下一节课 + 学期进度/距放假 + 今天还剩几节，下面按开关挂一块「回声洞」。
 *
 * 数据全部来自 [Prefs] 的本地缓存（loadBase + 本地修改），所以
 * **不需要联网、也不需要 App 在后台运行** —— 这正是它和「打开 App 才能看」的本质区别。
 *
 * 同一个组件两种形态，取决于实例当前尺寸（一个拖成 2x2、另一个拖成 4x2 是常态）：
 * 宽 < [WIDE_MIN_DP] 走 2x2 简版（只剩「今天还剩几节」和「下一节」），否则走详细版。
 */
class HaueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // 系统拉起 / 重新添加：顺手播一次故障动画，刚放上去就有「开机」的感觉
        runAsync(context, "HaueWidget.onUpdate", animateEcho = true, advanceEcho = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK -> runAsync(context, "HaueWidget.onTick", animateEcho = false, advanceEcho = false)
            ACTION_ECHO -> runAsync(context, "HaueWidget.onEcho", animateEcho = true, advanceEcho = true)
        }
    }

    /** 被拖大拖小：布局形态跟着换，必须重画 */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        refresh(context)
    }

    override fun onEnabled(context: Context) = refresh(context)

    override fun onDisabled(context: Context) = cancelAll(context)

    /**
     * 广播回调跑在主线程，而解课表 JSON、读 jwzs.txt 都要花时间，不能让主线程等：
     * 统一丢给 worker 串行执行，用 goAsync 撑住这次广播的生命周期。
     */
    private fun runAsync(ctx: Context, tag: String, animateEcho: Boolean, advanceEcho: Boolean) {
        val pending = goAsync()
        val app = ctx.applicationContext
        worker.execute {
            try {
                renderAndSchedule(app, animateEcho, advanceEcho)
            } catch (e: Exception) {
                Diag.record(tag, e)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        private const val TAG = "HaueWidget"

        const val ACTION_TICK = "com.cxywh.hauejw.WIDGET_TICK"
        const val ACTION_ECHO = "com.cxywh.hauejw.WIDGET_ECHO"

        /** 渲染串行走这一个线程：系统的 onUpdate/onTick 与 App 侧 refresh 不会同时改同一批 RemoteViews */
        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "haue-widget").apply { isDaemon = true }
        }

        /** 合并突发刷新：连着一串变化只排一次；运行期间又变了就在跑完后补一次，保证最后状态一定画上 */
        private val refreshQueued = AtomicBoolean(false)

        private const val REQ_TICK = 7101

        /** 心跳间隔：30 分钟。睡多久、被系统限制多久，醒来后至少还有它把整条链重新接上 */
        private const val HEARTBEAT_MS = 30 * 60_000L
        private const val REQ_TAP = 7102
        private const val REQ_ECHO = 7103
        private const val REQ_KEY_BASE = 7200
        private const val MAX_KEY = 12

        /** 窄于这个宽度就用 2x2 简版（2 列约 110dp，4 列约 250dp，中间留足余量） */
        private const val WIDE_MIN_DP = 180

        /** 详细版里回声洞的四帧正文。RemoteViews 只能整块替换，所以每帧都要重画整份 */
        private val ECHO_BODY = intArrayOf(R.id.w_echo_t0, R.id.w_echo_t1, R.id.w_echo_t2, R.id.w_echo_t3)

        private val ROWS = intArrayOf(R.id.w_row0, R.id.w_row1, R.id.w_row2, R.id.w_row3)
        private val TIMES = intArrayOf(R.id.w_t0, R.id.w_t1, R.id.w_t2, R.id.w_t3)
        private val NAMES = intArrayOf(R.id.w_c0, R.id.w_c1, R.id.w_c2, R.id.w_c3)
        private val ROOMS = intArrayOf(R.id.w_r0, R.id.w_r1, R.id.w_r2, R.id.w_r3)

        /** 上次记过日志的尺寸：只在尺寸/开关变化时打一行，免得刷屏 */
        private var lastSizeLog = ""

        // ── 小组件配色：直接用 Theme.kt 的 Palettes，不再手抄十六进制 ──
        // 背景是带圆角的 shape drawable（RemoteViews 没法在运行时补圆角），它的两个底色
        // 落在 res/values/colors.xml，改主题时只需对齐那两行。
        private fun wBg(p: Palette) = if (p.dark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light
        private fun wFg(p: Palette) = p.fg.toArgb()
        private fun wSub(p: Palette) = p.fgSub.toArgb()
        private fun wDim(p: Palette) = p.dim.toArgb()
        private fun wAccent(p: Palette) = p.accent.toArgb()
        private fun wLine(p: Palette) = p.line.toArgb()

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

        /**
         * App 侧数据一变（登录 / 切账号 / 退出 / 更新课表 / 改过课 / 换主题 / 改回声洞）就调它。
         * 内部串行 + 合并，连着调也不会排出一队渲染，可以直接调。
         */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            if (!refreshQueued.compareAndSet(false, true)) return
            worker.execute {
                // 先放开再渲染：渲染期间来的新变化会再排一次，最后状态一定画得上
                refreshQueued.set(false)
                runCatching { renderAndSchedule(app, animateEcho = false, advanceEcho = false) }
                    .onFailure { e -> Diag.record("HaueWidget.refresh", e) }
            }
        }

        /**
         * 请求启动器把桌面上的那一块改成 [cells] 格宽（0 表示不动）。
         *
         * 安卓没有「应用直接改小组件尺寸」的公开接口，唯一沾边的是 updateAppWidgetOptions：
         * 把 min/max 宽高（dp）交给启动器，由它决定要不要照着调。**实测华为 / 荣耀桌面不认**
         * （请求后桌面节点包围盒分毫未变），AOSP 桌面据文档会照办 —— 所以这里只是 best-effort，
         * 界面文案必须写「长按卡片拖边角」，不能写成点了就会变小。
         * 改完想知道实际落在哪一档，看渲染时记下的「尺寸 WxH」（那也是面板显示用的来源）。
         */
        fun resize(ctx: Context, cells: Int) {
            if (cells <= 0) return
            val app = ctx.applicationContext
            runCatching {
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, HaueWidget::class.java))
                if (ids.isEmpty()) return
                val w = cellsToDp(cells)
                val h = cellsToDp(2)
                val o = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                }
                for (id in ids) mgr.updateAppWidgetOptions(id, o)
                Log.i(TAG, "请求改尺寸 ${cells}×2 → ${w}x${h} dp")
            }.onFailure { e -> Diag.record("HaueWidget.resize", e) }
        }

        /**
         * 立刻换一条回声洞（设置面板的「立刻换一条」按钮用）。
         * 走的是和定时器同一条广播，所以会一起播故障动画。
         */
        fun echoNow(ctx: Context) {
            val app = ctx.applicationContext
            runCatching { app.sendBroadcast(Intent(app, HaueWidget::class.java).setAction(ACTION_ECHO)) }
                .onFailure { e -> Diag.record("HaueWidget.echoNow", e) }
        }

        // ────────────────────────── 渲染 ──────────────────────────

        /** 一个小组件实例 + 它当前的尺寸（同一个 App 的多个实例尺寸可以不一样） */
        private data class WidgetJob(val id: Int, val wDp: Int, val hDp: Int)

        /**
         * 这一轮回声洞要显示的内容：正文 + 四帧 + 要不要播动画。
         * cyclable = 文件里真有句子可轮换 —— 空文件只显示一句提示，那就别再排下一次换句了。
         */
        private data class EchoRound(
            val text: String,
            val frames: List<String>,
            val animate: Boolean,
            val cyclable: Boolean,
        )

        private fun renderAndSchedule(ctx: Context, animateEcho: Boolean, advanceEcho: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, HaueWidget::class.java))
            if (ids.isEmpty()) {
                cancelAll(ctx)
                return
            }

            val prefs = Prefs(ctx)
            val now = LocalDateTime.now()
            val tt = runCatching {
                prefs.loadBase()?.let { base -> base.withCourses(prefs.edits.apply(base.courses)) }
            }.onFailure { e -> Diag.record("HaueWidget.load", e) }.getOrNull()

            val p = C.byId(prefs.theme)
            // 关掉小组件：撤掉全部闹钟，卡片上留一句「已关闭」，之后不再定时醒
            if (!prefs.widgetOn) {
                cancelAll(ctx)
                lastReschedKey = null // 重新打开时要能重新武装闹钟
                val off = buildOff(ctx, p)
                for (id in ids) {
                    runCatching { mgr.updateAppWidget(id, off) }
                        .onFailure { e -> Diag.record("HaueWidget.update", e) }
                }
                return
            }
            // 电费：只读本机缓存，小组件不联网；没有缓存就写明是没查过还是没登录
            val fee = feeLine(
                prefs.cardCache,
                SecretStore.has(ctx, "card@" + prefs.username),
            )

            // 内容与各尺寸的「该显示什么」都只算一次：下面每个实例、动画的每一帧都复用，不重复解课表
            val sm = smallState(tt, now, fee)
            val round = echoRound(ctx, prefs, advanceEcho)
            val jobs = ids.map { id -> jobOf(mgr, id) }
            val stByHeight = jobs.map { it.hDp }.distinct()
                .associateWith { h -> widgetState(tt, now, widgetRowsFor(h, round != null), fee) }

            logSizes(jobs, prefs)

            // 日期 / 课表 / 实例尺寸都没变就不重排：闹钟是绝对时刻，没变化时重排纯属白跑
            // （回声洞每 5 秒进来渲染一次，不拦的话每次都是十几个 cancel + set 的 binder 往返）
            val rkey = "${now.toLocalDate()}|${tt?.hashCode()}|" +
                jobs.joinToString(",") { "${it.id}:${it.wDp}x${it.hDp}" }
            if (rkey != lastReschedKey) {
                lastReschedKey = rkey
                reschedule(ctx, tt, now)
            }
            scheduleEcho(ctx, prefs, round)
            scheduleHeartbeat(ctx, prefs)

            if (animateEcho && round != null && round.frames.size > 1) {
                // 四帧里的最后一帧就是正片，所以不先推一次完整内容 —— 那一推立刻会被第一帧覆盖。
                burst(ctx, mgr, prefs, p, stByHeight, sm, round, jobs)
            } else {
                // 不播动画的场合（半小时兜底、拖尺寸、换主题）：必须推一次，否则屏幕上还是旧的
                for (j in jobs) push(ctx, mgr, prefs, p, stByHeight.getValue(j.hDp), sm, round, j, frame = ECHO_BODY.size - 1)
            }
        }

        private fun jobOf(mgr: AppWidgetManager, id: Int): WidgetJob {
            val opt = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val w = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 250
            val h = opt?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 110
            return WidgetJob(id, w, h)
        }

        /** 尺寸变化时记一行并存下来：排查「拖窄了没换成简版」先看这行；设置面板显示的也是这份 */
        private fun logSizes(jobs: List<WidgetJob>, prefs: Prefs) {
            val key = jobs.joinToString("|") { "${it.wDp}x${it.hDp}" }
            if (key == lastSizeLog) return
            lastSizeLog = key
            Log.i(TAG, "尺寸 " + key + " echo=" + prefs.echoOn)
            prefs.lastWidgetSize = key
        }

        /** 上次重排闹钟的依据（日期 + 课表 + 各实例尺寸）；没变就跳过，省掉回声洞每 5 秒一次的十几个闹钟往返 */
        private var lastReschedKey: String? = null

        private fun push(
            ctx: Context, mgr: AppWidgetManager, prefs: Prefs, p: Palette,
            st: WidgetState, sm: SmallState, round: EchoRound?,
            job: WidgetJob, frame: Int,
        ) {
            val v = if (widgetIsSmall(prefs.widgetForm, job.wDp, WIDE_MIN_DP)) buildSmall(ctx, p, sm)
            else buildWide(ctx, p, st, round, frame)
            runCatching { mgr.updateAppWidget(job.id, v) }
                .onFailure { e -> Diag.record("HaueWidget.update", e) }
        }

        /**
         * 故障动画：连着推四帧（乱码 → 再乱一次 → 整块糊掉 → 正片）。
         * 每帧都得是一份完整的 RemoteViews（updateAppWidget 是整块替换，改不了单块），
         * 所以内容在外面算好传进来，这里只重画、不重算。
         * RemoteViews 自带的动画能力很弱，能做「机器故障」观感的就是这个组合：
         * 正文换成乱码 + ViewFlipper 的进/出动画（res/anim/echo_in、echo_out）。
         */
        private fun burst(
            ctx: Context, mgr: AppWidgetManager, prefs: Prefs, p: Palette,
            stByHeight: Map<Int, WidgetState>, sm: SmallState, round: EchoRound, jobs: List<WidgetJob>,
        ) {
            val h = Handler(Looper.getMainLooper())
            for (f in round.frames.indices) {
                h.postDelayed({
                    runCatching {
                        for (j in jobs) {
                            push(ctx, mgr, prefs, p, stByHeight.getValue(j.hDp), sm, round, j, frame = f)
                        }
                    }.onFailure { e -> Diag.record("HaueWidget.burst", e) }
                }, (f * Echo.FRAME_MS).toLong())
            }
        }

        /**
         * 关掉小组件时画的一句说明：复用简版布局，只是把「下一节」那行留空，
         * buildSmall 见到 nextWhen 为空就会把标签一起收起来。
         */
        private fun buildOff(ctx: Context, p: Palette): RemoteViews = buildSmall(
            ctx, p,
            SmallState("小组件已关闭", "", "在「常用功能 → 桌面小组件」里重新打开", null),
        )

        /** 2x2 简版：只有「今天还剩几节」和「下一节」 */
        private fun buildSmall(ctx: Context, p: Palette, sm: SmallState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_board_small)
            v.setInt(R.id.w2_root, "setBackgroundResource", wBg(p))
            v.setOnClickPendingIntent(R.id.w2_root, tapPi(ctx))

            v.setTextViewText(R.id.w2_today, sm.today)
            v.setTextColor(R.id.w2_today, wAccent(p))
            // nextWhen 为空 = 这行不该出现（关掉小组件、加载中）：连「下一节」标签一起收起来
            val hasNext = sm.nextWhen.isNotBlank()
            v.setViewVisibility(R.id.w2_next_label, if (hasNext) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w2_next_label, "下一节")
            v.setTextColor(R.id.w2_next_label, wDim(p))
            v.setViewVisibility(R.id.w2_next_when, if (hasNext) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w2_next_when, sm.nextWhen)
            v.setTextColor(R.id.w2_next_when, wSub(p))
            v.setTextViewText(R.id.w2_next_name, sm.nextName)
            v.setTextColor(R.id.w2_next_name, if (hasNext) wFg(p) else wDim(p))

            v.setTextViewText(R.id.w2_fee, sm.fee)
            v.setTextColor(R.id.w2_fee, wSub(p))
            v.setViewVisibility(R.id.w2_fee, if (sm.fee.isBlank()) View.GONE else View.VISIBLE)

            v.setTextViewText(R.id.w2_empty, sm.empty ?: "")
            v.setViewVisibility(R.id.w2_empty, if (sm.empty != null) View.VISIBLE else View.GONE)
            return v
        }

        /** 详细版：课表 + 学期进度 + 下一节，底部按开关挂一块回声洞 */
        private fun buildWide(
            ctx: Context, p: Palette, st: WidgetState, round: EchoRound?, frame: Int,
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_board)
            v.setInt(R.id.w_root, "setBackgroundResource", wBg(p))
            v.setOnClickPendingIntent(R.id.w_root, tapPi(ctx))

            v.setTextViewText(R.id.w_week, st.week)
            v.setTextColor(R.id.w_week, wFg(p))
            v.setTextViewText(R.id.w_date, st.date)
            v.setTextColor(R.id.w_date, wDim(p))
            v.setTextViewText(R.id.w_term, st.term)
            v.setTextColor(R.id.w_term, wDim(p))
            v.setTextViewText(R.id.w_today, st.today)
            v.setTextColor(R.id.w_today, wAccent(p))

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
                v.setTextColor(TIMES[i], if (r.past) wDim(p) else wAccent(p))
                v.setTextViewText(NAMES[i], r.name)
                v.setTextColor(NAMES[i], when {
                    r.note -> wSub(p)
                    r.past -> wDim(p)
                    else -> wFg(p)
                })
                v.setTextViewText(ROOMS[i], r.room)
                v.setTextColor(ROOMS[i], if (r.past) wDim(p) else wSub(p))
            }

            // st.empty 非空 = 还没有课表；否则那块提示整块隐藏
            if (st.empty != null) {
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
                v.setTextViewText(R.id.w_empty, st.empty)
                v.setTextColor(R.id.w_empty, wAccent(p))
            } else {
                v.setViewVisibility(R.id.w_empty, View.GONE)
            }

            v.setTextViewText(R.id.w_next, st.next)
            v.setTextColor(R.id.w_next, wSub(p))
            v.setTextViewText(R.id.w_fee, st.fee)
            v.setTextColor(R.id.w_fee, wSub(p))
            v.setViewVisibility(R.id.w_fee, if (st.fee.isBlank()) View.GONE else View.VISIBLE)

            if (round == null) {
                v.setViewVisibility(R.id.w_echo_line, View.GONE)
                v.setViewVisibility(R.id.w_echo_block, View.GONE)
            } else {
                v.setViewVisibility(R.id.w_echo_line, View.VISIBLE)
                v.setInt(R.id.w_echo_line, "setBackgroundColor", wLine(p))
                v.setViewVisibility(R.id.w_echo_block, View.VISIBLE)
                v.setTextViewText(R.id.w_echo_label, ctx.getString(R.string.echo_label))
                v.setTextColor(R.id.w_echo_label, wDim(p))
                val f = frame.coerceIn(0, round.frames.size - 1)
                round.frames.forEachIndexed { i, s ->
                    v.setTextViewText(ECHO_BODY[i], s)
                    v.setTextColor(ECHO_BODY[i], wSub(p))
                }
                v.setInt(R.id.w_echo_flip, "setDisplayedChild", f)
            }
            return v
        }

        /** 取这一轮回声洞的正文与四帧；关着就是 null。advance = 到点了，换下一句 */
        private fun echoRound(ctx: Context, prefs: Prefs, advance: Boolean): EchoRound? {
            if (!prefs.echoOn) return null
            val texts = runCatching { Echo.load(ctx) }
                .onFailure { e -> Diag.record("HaueWidget.echo", e) }
                .getOrDefault(emptyList())

            if (texts.isEmpty()) {
                val hint = ctx.getString(R.string.echo_no_content)
                return EchoRound(hint, List(ECHO_BODY.size) { hint }, animate = false, cyclable = false)
            }

            val rnd = Random()
            var text = prefs.echoShown?.takeIf { it.isNotBlank() }
            var animate = false
            // 该换句了：advance 是真到点了；text 为空、或已不在文件里（用户改了 jwzs.txt）也要重挑
            if (advance || text == null || text !in texts) {
                val q = prefs.echoQueue.split(",").mapNotNull { it.trim().toIntOrNull() }
                val r = Echo.pick(texts.size, q, prefs.echoPos, texts, text, rnd)
                if (r.index in texts.indices) {
                    text = texts[r.index]
                    prefs.echoQueue = r.queue.joinToString(",")
                    prefs.echoPos = r.pos
                    prefs.echoShown = text
                    animate = advance
                }
            }
            val t = text ?: return null
            // 帧数不能超过布局里的正文槽位（ECHO_BODY 有几个用几个）；帧数又反过来决定换句节奏，
            // 所以以后想加帧，只需在这里和布局里各加一处，节奏会自动跟着变
            return EchoRound(t, Echo.frames(t, rnd).take(ECHO_BODY.size), animate, cyclable = true)
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

            // 没有课表（没登录 / 刚退出登录）：闹钟全撤掉，别让它每半小时白醒一次。
            // 数据回来时 App 侧会调 refresh()，那时重新武装。
            if (tt == null) {
                cancelTickAlarms(ctx)
                return
            }

            // 兜底心跳不在这里排：reschedule 会被 lastReschedKey 跳过，而心跳必须每轮都续上，
            // 见 scheduleHeartbeat()。这里只负责关键时刻那几条一次性闹钟。

            for (i in 0 until MAX_KEY) am.cancel(pi(ctx, ACTION_TICK, REQ_KEY_BASE + i))

            val nowMs = System.currentTimeMillis()
            val at = mutableListOf<Long>()
            coursesOn(tt, now.toLocalDate()).forEach { c ->
                Slots.of(c.endSlot)?.let { at += atMillis(now.toLocalDate(), it.endTime) }
            }
            nextClass(tt, now)?.let { at += atMillis(it.date, it.begin) }
            at += now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            at.filter { it > nowMs }.distinct().sorted().take(MAX_KEY)
                .forEachIndexed { i, t -> oneShot(am, ctx, t, REQ_KEY_BASE + i) }
        }

        /**
         * 回声洞的换句闹钟：整句完整显示之后再停 5 秒，换下一条 —— 不是按固定间隔刷新。
         * 每次渲染都重排一次（cancel + set），所以闹钟被系统吞掉、进程被杀、重启手机之后
         * 都能在下一次任何触发里自己接上，不会断在半路。
         * 用 RTC 而不是 RTC_WAKEUP：屏幕黑着没人看这块，等设备下一次醒来再换就行 —— 不叫醒设备。
         */
        private fun scheduleEcho(ctx: Context, prefs: Prefs, round: EchoRound?) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx, ACTION_ECHO, REQ_ECHO))
            if (!prefs.widgetOn || !prefs.echoOn || round == null || !round.cyclable) return
            runCatching {
                am.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + Echo.nextEchoInMs(round.frames.size),
                    pi(ctx, ACTION_ECHO, REQ_ECHO),
                )
            }.onFailure { e -> Diag.record("HaueWidget.echoTick", e) }
        }

        /**
         * 心跳闹钟：每 30 分钟醒一次，只干一件事 —— 把整条链重新拉起来（渲染会顺手重排关键闹钟和换句闹钟）。
         *
         * 为什么必须有它：其余闹钟全是非唤醒型（RTC），设备一睡着就不触发；系统再限制一下后台，
         * 连闹钟都可能不发。原来靠一条 setInexactRepeating(RTC) 当兜底，睡久了就断在半路，
         * 得开一次 App 才恢复。这里换成 RTC_WAKEUP + setAndAllowWhileIdle：打盹状态也能按维护窗口
         * 醒来，醒来就把链接上。一天 48 次，代价可以忽略。
         *
         * 它必须每轮都排（和换句闹钟一样放在 lastReschedKey 判断之外），跳过一次就再没人续上。
         */
        private fun scheduleHeartbeat(ctx: Context, prefs: Prefs) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val p = pi(ctx, ACTION_TICK, REQ_TICK)
            if (!prefs.widgetOn) {
                am.cancel(p)
                return
            }
            runCatching {
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + HEARTBEAT_MS,
                    p,
                )
            }.onFailure { e -> Diag.record("HaueWidget.heartbeat", e) }
        }

        private fun oneShot(am: AlarmManager, ctx: Context, at: Long, req: Int) {
            val p = pi(ctx, ACTION_TICK, req)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
                else am.set(AlarmManager.RTC_WAKEUP, at, p)
            }.onFailure { e -> Diag.record("HaueWidget.oneShot", e) }
        }

        private fun cancelTickAlarms(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx, ACTION_TICK, REQ_TICK))
            for (i in 0 until MAX_KEY) am.cancel(pi(ctx, ACTION_TICK, REQ_KEY_BASE + i))
        }

        private fun cancelAll(ctx: Context) {
            cancelTickAlarms(ctx)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx, ACTION_ECHO, REQ_ECHO))
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
