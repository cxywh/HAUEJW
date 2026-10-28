package com.cxywh.hauejw

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** 本地存储：只存学号 + 课表缓存 + 导入的课表 + 本地修改 + 界面偏好；密码绝不落盘 */
/**
 * @param fixedAccount 指定账号时键名按它拼；不传就跟着当前登录账号走。
 * 定时查询要在后台读写「不是当前账号」的数据，所以需要这个入口。
 */
class Prefs(context: Context, private val fixedAccount: String? = null) {

    private val sp = context.getSharedPreferences("hauejw", Context.MODE_PRIVATE)

    /** 当前账号；没登录时用占位符，避免键名互相覆盖 */
    private fun acc() = account().ifBlank { "_default" }

    /** 账号私有数据的键名（课表缓存 / 保存时间 / 来源 / 本地修改），不同账号互不干扰 */
    private fun key(base: String) = scopedKey(base, fixedAccount ?: username)

    /** 这套数据实际属于哪个账号 */
    fun account(): String = fixedAccount ?: username

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    /** 清掉某个账号的校园卡本地状态（房间、上次读数、预警、定时查询）；查询凭据由 SecretStore 单独清 */
    fun wipeCard(account: String = username) {
        if (account.isBlank()) return
        val e = sp.edit()
        cardKeysToWipe(sp.all.keys, account).forEach { e.remove(it) }
        e.apply()
    }

    /** 电费房间：提交用的键值对，形如 xiaoqu_id=1&loudong_id=505&louceng_id=601&room_id=15160 */
    var cardRoom: String
        get() = sp.getString(key("card_room"), "") ?: ""
        set(v) = sp.edit().putString(key("card_room"), v).apply()

    /** 定时查询开关与时刻 */
    var cardAutoOn: Boolean
        get() = sp.getBoolean(key("card_auto_on"), false)
        set(v) = sp.edit().putBoolean(key("card_auto_on"), v).apply()

    var cardHour: Int
        get() = sp.getInt(key("card_hour"), 8)
        set(v) = sp.edit().putInt(key("card_hour"), v.coerceIn(0, 23)).apply()

    var cardMinute: Int
        get() = sp.getInt(key("card_min"), 0)
        set(v) = sp.edit().putInt(key("card_min"), v.coerceIn(0, 59)).apply()

    /** 余额预警开关与阈值（单位随返回的标签，电费是度） */
    var cardWarnOn: Boolean
        get() = sp.getBoolean(key("card_warn_on"), false)
        set(v) = sp.edit().putBoolean(key("card_warn_on"), v).apply()

    var cardWarn: Double
        get() = sp.getFloat(key("card_warn"), 20f).toDouble()
        set(v) = sp.edit().putFloat(key("card_warn"), v.toFloat().coerceAtLeast(0f)).apply()

    /** 最近一次查询结果缓存：见 CardWatch.encode */
    var cardCache: String
        get() = sp.getString(key("card_cache"), "") ?: ""
        set(v) = sp.edit().putString(key("card_cache"), v).apply()

    /** 上次发通知的时间，用来避免重复轰炸 */
    var cardLastNotify: Long
        get() = sp.getLong(key("card_last_notify"), 0L)
        set(v) = sp.edit().putLong(key("card_last_notify"), v).apply()

    /** 费项编号（电费那条），定时查询用 */
    var cardItemId: String
        get() = sp.getString(key("card_item"), "") ?: ""
        set(v) = sp.edit().putString(key("card_item"), v).apply()

    /** 电费房间的可读名字，形如 某校区某号楼 A区1层 101 */
    var cardRoomName: String
        get() = sp.getString(key("card_room_name"), "") ?: ""
        set(v) = sp.edit().putString(key("card_room_name"), v).apply()

    var rememberUsername: Boolean
        get() = sp.getBoolean("rememberUsername", true)
        set(v) = sp.edit().putBoolean("rememberUsername", v).apply()

    /** 记住的账号清单（最近的排最前）。只存学号，不含任何密码信息；密码在 SecretStore 里加密存 */
    var accounts: List<String>
        get() = (sp.getString("accounts", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotBlank() }
        set(v) = sp.edit().putString("accounts", v.filter { it.isNotBlank() }.distinct().joinToString("\n")).apply()

    fun addAccount(id: String) {
        if (id.isBlank()) return
        accounts = listOf(id) + accounts.filter { it != id }
    }

    fun removeAccount(id: String) {
        accounts = accounts.filter { it != id }
    }

    /** 是否记住密码（勾选后密码以 AES-GCM 加密存本机，密钥在系统密钥库；不勾选则从不落盘） */
    var rememberPassword: Boolean
        get() = sp.getBoolean("rememberPassword", false)
        set(v) = sp.edit().putBoolean("rememberPassword", v).apply()

    /** 外观主题：light / dark */
    var theme: String
        get() = sp.getString("theme", "light") ?: "light"
        set(v) = sp.edit().putString("theme", v).apply()

    /** 登录后从教务系统首页菜单里解析出的「菜单名 → 路径」，供常用页面直达用 */
    var menuJson: String?
        get() = sp.getString("menus", null)
        set(v) = sp.edit().putString("menus", v).apply()

    val menus: Map<String, String>
        get() = try {
            val o = JSONObject(menuJson ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: Exception) {
            emptyMap()
        }

    /** 上次浏览的周次（-1 = 跟随本周） */
    var lastWeek: Int
        get() = sp.getInt("lastWeek", -1)
        set(v) = sp.edit().putInt("lastWeek", v).apply()

    /** 服务端课表缓存（原始 JSON 响应 + 校历） */
    var timetableCache: String?
        get() = sp.getString(key("tt"), null)
        set(v) = sp.edit().putString(key("tt"), v).apply()

    var timetableSavedAt: Long
        get() = sp.getLong(key("ttAt"), 0L)
        set(v) = sp.edit().putLong(key("ttAt"), v).apply()

    /** 课表来源：server / imported */
    var source: String
        get() = sp.getString(key("src"), Source.SERVER) ?: Source.SERVER
        set(v) = sp.edit().putString(key("src"), v).apply()

    /** 本地修改（EditLayer 的 JSON） */
    var editsJson: String?
        get() = sp.getString(key("edits"), null)
        set(v) = sp.edit().putString(key("edits"), v).apply()

    /** 导入的课表（TimelineCodec JSON）——同样按账号隔离，否则 A 导入的课表会串到 B 名下 */
    var importedJson: String?
        get() = sp.getString(key("imported"), null)
        set(v) = sp.edit().putString(key("imported"), v).apply()

    // 课前提醒
    var remindEnabled: Boolean
        get() = sp.getBoolean("remind", false)
        set(v) = sp.edit().putBoolean("remind", v).apply()

    var remindMinutes: Int
        get() = sp.getInt("remindMin", 20)
        set(v) = sp.edit().putInt("remindMin", v).apply()

    /** 提醒方式：notify = 消息通知，alarm = 闹钟式（整屏 + 铃声） */
    var remindStyle: String
        get() = sp.getString("remindStyle", Reminder.STYLE_NOTIFY) ?: Reminder.STYLE_NOTIFY
        set(v) = sp.edit().putString("remindStyle", v).apply()

    /**
     * 课前提醒的待发清单（JSON）。
     * 自包含设计：开机重排时直接读它，不用再去解课表 —— 否则开机那会儿课表还没解出来就漏发。
     */
    var remindPlan: String?
        get() = sp.getString("remindPlan", null)
        set(v) = sp.edit().putString("remindPlan", v).apply()

    // ── 回声洞：小组件底部那块自定义文字 ──
    // 存的都是"轮到哪一句了"，和课表数据无关，所以不按账号分（换个账号看同一批句子更合理）。

    /** 是否在小组件里显示回声洞（默认打开：这是新功能的可见入口，不想要在设置里一键关） */
    var echoOn: Boolean
        get() = sp.getBoolean("echoOn", true)
        set(v) = sp.edit().putBoolean("echoOn", v).apply()

    /** 是否启用桌面小组件（默认开）：关掉后撤掉全部闹钟、卡片显示「已关闭」，不再定时醒 */
    var widgetOn: Boolean
        get() = sp.getBoolean("widgetOn", true)
        set(v) = sp.edit().putBoolean("widgetOn", v).apply()

    /** 卡片形态：auto 按桌面实际宽度决定，wide 固定详细版，small 固定简版 */
    var widgetForm: String
        get() = sp.getString("widgetForm", "auto") ?: "auto"
        set(v) = sp.edit().putString("widgetForm", v).apply()

    /**
     * 最近一次渲染时各实例的实际尺寸（"宽x高|宽x高"，dp），由渲染路径写入。
     * 设置面板显示「桌面那张现在几格」读的就是它 —— 和画卡片用的是同一份数据，
     * 不会像 getAppWidgetOptions 那样读到已卸载实例的残留值。
     */
    var lastWidgetSize: String
        get() = sp.getString("lastWidgetSize", "") ?: ""
        set(v) = sp.edit().putString("lastWidgetSize", v).apply()

    /** 已洗好的显示顺序（本轮内保证不重复）；pos 走到头就重洗换下一轮 */
    var echoQueue: String
        get() = sp.getString("echoQueue", "") ?: ""
        set(v) = sp.edit().putString("echoQueue", v).apply()

    var echoPos: Int
        get() = sp.getInt("echoPos", 0)
        set(v) = sp.edit().putInt("echoPos", v).apply()

    /** 上一句的正文：重洗时避开它，免得连着两条一模一样 */
    var echoShown: String?
        get() = sp.getString("echoShown", null)
        set(v) = sp.edit().putString("echoShown", v).apply()

    val edits: EditLayer get() = EditLayer.fromJson(editsJson)

    fun saveEdits(layer: EditLayer) {
        editsJson = if (layer.isEmpty) null else layer.toJson()
    }

    fun clearEdits() {
        editsJson = null
    }

    /** 从服务端缓存还原课表 */
    fun loadServer(): Timetable? {
        val cached = timetableCache ?: return null
        return try {
            val w = JSONObject(cached)
            val cal = Term(
                xnm = "", xnmc = "",
                xqm = w.optJSONObject("raw")?.optJSONObject("xsxx")?.optString("XQM").orEmpty(),
                startDate = w.optString("start").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                endDate = w.optString("end").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            )
            JwApi.parseTimetable(w.getJSONObject("raw"), cal)
        } catch (e: Exception) {
            null
        }
    }

    /** 还原导入的课表 */
    fun loadImported(): Timetable? {
        val json = importedJson ?: return null
        return try {
            TimetableCodec.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    /** 当前应显示的原始课表（未套用本地修改） */
    fun loadBase(): Timetable? =
        if (source == Source.IMPORTED) (loadImported() ?: loadServer()) else (loadServer() ?: loadImported())

    fun saveImported(tt: Timetable) {
        importedJson = TimetableCodec.toJson(tt)
        source = Source.IMPORTED
    }

    fun clearImported() {
        importedJson = null
        if (source == Source.IMPORTED) source = Source.SERVER
    }
}

/**
 * 账号清单排序：以 Prefs 里的使用顺序为准（最近用的在前），
 * SecretStore 里多出来的（迁移/异常残留）挂到后面，避免界面上账号顺序乱跳。
 */
internal fun mergeAccounts(order: List<String>, stored: List<String>): List<String> =
    order.filter { it in stored } + stored.filterNot { it in order }

/**
 * 账号私有数据的键名。抽成纯函数是为了能钉单测 —— 键名串了就等于两个账号的课表互相覆盖。
 */
/** 某个账号名下需要随「退出/忘记登录」一起清掉的校园卡键 */
internal fun cardKeysToWipe(all: Collection<String>, account: String): List<String> {
    val suffix = "@" + account
    return all.filter { it.startsWith("card_") && it.endsWith(suffix) }.sorted()
}

internal fun scopedKey(base: String, account: String): String =
    base + "@" + account.ifBlank { "_default" }

/**
 * 账号私有数据的键名基名。集中在这一处，避免「加了新的账号数据却忘了加进迁移名单」
 * —— 迁移漏一个，老用户升级后就会看到「明明登录了却没有课表」。
 */
internal val ACCOUNT_SCOPED_KEYS = listOf("tt", "ttAt", "src", "edits", "imported")

/**
 * 老版本这些数据只有一份（键名没有账号后缀）：升级后挪到当前学号名下，
 * 否则用户会看到「明明登录了却像没课表」。
 */
internal fun migrateSingleAccountData(sp: android.content.SharedPreferences, account: String) {
    if (account.isBlank()) return
    val e = sp.edit()
    var moved = false
    for (base in ACCOUNT_SCOPED_KEYS) {
        if (!sp.contains(base) || sp.contains(scopedKey(base, account))) continue
        when (val v = sp.all[base]) {
            is String -> e.putString(scopedKey(base, account), v)
            is Long -> e.putLong(scopedKey(base, account), v)
            is Int -> e.putLong(scopedKey(base, account), v.toLong())
            else -> continue
        }
        e.remove(base)
        moved = true
    }
    if (moved) e.apply()
}
