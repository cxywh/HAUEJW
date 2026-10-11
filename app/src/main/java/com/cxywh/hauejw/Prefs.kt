package com.cxywh.hauejw

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** 本地存储：只存学号 + 课表缓存 + 导入的课表 + 本地修改 + 界面偏好；密码绝不落盘 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("hauejw", Context.MODE_PRIVATE)

    /** 当前账号；没登录时用占位符，避免键名互相覆盖 */
    private fun acc() = username.ifBlank { "_default" }

    /** 账号私有数据的键名（课表缓存 / 保存时间 / 来源 / 本地修改），不同账号互不干扰 */
    private fun key(base: String) = scopedKey(base, username)

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

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

    /** 导入的课表（TimelineCodec JSON） */
    var importedJson: String?
        get() = sp.getString("imported", null)
        set(v) = sp.edit().putString("imported", v).apply()

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
internal fun scopedKey(base: String, account: String): String =
    base + "@" + account.ifBlank { "_default" }

/**
 * 老版本这些数据只有一份（键名没有账号后缀）：升级后挪到当前学号名下，
 * 否则用户会看到「明明登录了却像没课表」。
 */
internal fun migrateSingleAccountData(sp: android.content.SharedPreferences, account: String) {
    if (account.isBlank()) return
    val e = sp.edit()
    var moved = false
    for (base in listOf("tt", "ttAt", "src", "edits")) {
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
