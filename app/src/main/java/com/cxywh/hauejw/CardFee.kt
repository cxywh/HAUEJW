package com.cxywh.hauejw

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * 校园卡（慧新E校）接口层，只做「查费项余额」这一条链。
 *
 * 数据不在页面里，要三步：
 *  1) 在 WebView 里登录门户，拿到 access_token（App 不接触密码，见 CardLoginActivity）
 *  2) GET /berserker-app/app/listAppByType → 应用列表；电费那个应用的 website 里带 showBal=<费项编号>
 *  3) GET /charge/feeitem/getThirdDataByFeeItemId?feeitemid=<编号> → map.showData["信息"] 是一句中文，
 *     数字得从中抠出来：后端把电控系统的原始文本直接代理过来，没有结构化字段
 *
 * 请求头：synjones-auth: bearer <token>、synAccessSource: h5。
 *
 * 解析全部走字符串处理，不依赖 org.json —— 单测里能直接跑，不必起 Android 运行时。
 */
object CardFee {


    const val HOST = "card.mp.haue.edu.cn"
    const val BASE = "http://$HOST"

    /** 门户首页；登录成功后 sessionStorage 里会有 access_token */
    const val PORTAL = "$BASE/plat/shouyeUser?index=1"

    /** 校内查询凭据在 SecretStore 里的键：card@<学号>；带 @ 的键不是账号，账号列表里会被跳过 */
    const val CARD_PREFIX = "card@"

    fun cardKey(sno: String) = CARD_PREFIX + sno

    /** 门户前端里写死的客户端凭据（前端 JS 里明文可见），换 token 与刷新 token 时作为 Basic 认证 */
    private const val CLIENT = "bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val SRC = "h5"
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 20_000

    /** 缴费类接口（/charge 前缀）额外要带的 Basic，同样是前端 JS 里的值 */
    private const val PAY_BASIC = "Basic Y2hhcmdlOmNoYXJnZV9zZWNyZXQ="
    private const val PATH_THIRD = "/charge/feeitem/getThirdData"

    /** 一个可查余额的费项（电费 / 水费 / 网费…），id 就是接口要的 feeitemid */
    data class Item(val id: String, val name: String)

    /** 一句「剩余电量:12.34」拆出来的结果 */
    data class Amount(val label: String, val value: Double, val unit: String)

    data class Auth(val token: String, val refresh: String, val expiresAt: Long) {
        /** expiresAt 未知（0）时按有效算，真过期了接口会返回 401 */
        fun alive(now: Long = System.currentTimeMillis()) = token.isNotBlank() && (expiresAt <= 0L || now < expiresAt)
    }

    /** 需要重新登录（401/403，或刷新 token 失败） */
    class NeedLogin(message: String = "登录已过期，请重新登录校园卡") : IOException(message)

    // ───────────────────────── 纯解析 ─────────────────────────

    // 费项编号在 website 里：本校是 /charge/feeitem/toAppitem?feeitemid=408，
    // 别的学校也有用 showBal= 的写法，两种都认
    private val SHOW_BAL = Regex("""(?:showBal|feeitemid)(?:=|%3D)(\d+)""", RegexOption.IGNORE_CASE)
    private val APP_NAME = Regex(""""appName"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val NAME_ANY = Regex(""""(?:name|otherName)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val INFO = Regex(""""信息"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val KV = Regex("\"([^\"]+)\":\"([^\"]*)\"")
    private val MSG = Regex("\"msg\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val TOKEN = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
    private val REFRESH = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"")
    private val EXPIRES = Regex("\"expires_in\"\\s*:\\s*\"?(\\d+)")
    private val NUM = Regex("""(剩余电量|剩余金额|剩余水费|剩余流量|余额)\s*[:：]?\s*([-+]?\d+(?:\.\d+)?)""")

    /** JSON 字符串里的 \\uXXXX、\\"、\\n 还原；不处理嵌套，调用方按需多跑一次 */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                if (n == 'u' && i + 5 < s.length) {
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar()); i += 6; continue
                    }
                }
                when (n) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(' ')
                }
                i += 2
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * 从应用列表里找带 showBal 的费项。
     * 名称取同一对象里的 appName（优先级高于 name，后者可能是分组名），找不到就退回「费项 <编号>」。
     */
    fun itemsOf(json: String): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (m in SHOW_BAL.findAll(json)) {
            val id = m.groupValues[1]
            if (out.containsKey(id)) continue
            val head = json.substring(maxOf(0, m.range.first - 1500), m.range.first)
            val name = APP_NAME.findAll(head).lastOrNull()?.groupValues?.get(1)
                ?: NAME_ANY.findAll(head).lastOrNull()?.groupValues?.get(1)
                ?: ""
            out[id] = Item(id, unescape(name).trim().ifBlank { "费项 $id" })
        }
        return out.values.toList()
    }

    /** 从 getThirdDataByFeeItemId 的返回里抠数字；先看 map.showData["信息"]，再退到整段文本 */
    fun amount(json: String): Amount? {
        val text = unescape(json)
        val inner = INFO.find(text)?.groupValues?.get(1)?.let(::unescape) ?: text
        val m = NUM.find(inner) ?: NUM.find(text) ?: return null
        val v = m.groupValues[2].toDoubleOrNull() ?: return null
        val label = m.groupValues[1]
        return Amount(label, v, if (label == "剩余电量") "度" else "元")
    }

    /** 一个可选项：界面上的名字与提交值 */
    data class Opt(val name: String, val value: String)

    /** 一级下钻的返回：这一级用到的字段名 + 可选项 */
    data class Step(val codes: List<String>, val opts: List<Opt>)

    /** 解析 {codes} 与 {opts}，纯字符串处理便于单测 */
    fun stepOf(json: String): Step {
        val t = unescape(json)
        val codes = CODE.findAll(t)
            .sortedBy { it.groupValues[2].toIntOrNull() ?: 0 }
            .map { it.groupValues[1] }
            .toList()
        val opts = OPT.findAll(t).map { Opt(it.groupValues[1], it.groupValues[2]) }.toList()
        return Step(codes, opts)
    }

    /** 电表读数：取 map.showData 里的第一项，例如「剩余电量(度)」→ 87.81 度 */
    fun showOf(json: String): Amount? {
        val t = unescape(json)
        val inner = SHOW.find(t)?.groupValues?.get(1) ?: return amount(t)
        val m = KV.find(inner) ?: return amount(t)
        val raw = m.groupValues[1]
        val v = m.groupValues[2].toDoubleOrNull() ?: return null
        val unit = when {
            raw.contains("度") -> "度"
            raw.contains("元") -> "元"
            else -> ""
        }
        return Amount(raw.substringBefore('(').trim(), v, unit)
    }

    /** 逐级取选项：level 0 取校区，1 取楼栋，2 取楼层，3 取房间 */
    fun step(ctx: Context?, token: String, itemId: String, picked: Map<String, String>, level: Int): Step {
        val form = form(picked, itemId) + "&type=select&level=" + level
        val body = payCall(PATH_THIRD, token, form)
        val st = stepOf(body)
        log(ctx, "step L" + level + " opts=" + st.opts.size + " len=" + body.length)
        return st
    }

    /** 房间定下来之后查电表读数 */
    fun meters(ctx: Context?, token: String, itemId: String, picked: Map<String, String>, level: Int): Amount {
        val form = form(picked, itemId) + "&type=IEC&level=" + level
        val body = payCall(PATH_THIRD, token, form)
        log(ctx, "iec level=" + level + " len=" + body.length + " body=" + body.take(400))
        return showOf(body) ?: throw IOException(msgOf(body) ?: "门户没有返回这项的数值")
    }

    private fun form(picked: Map<String, String>, itemId: String): String {
        val all = LinkedHashMap<String, String>()
        all["feeitemid"] = itemId
        all.putAll(picked)
        return all.entries.joinToString("&") { q(it.key) + "=" + q(it.value) }
    }

    /**
     * /charge 下的接口：除公共头外还要带缴费用的 Basic 与表单类型。
     * 连接参数、UA、synjones-auth 都由 open() 统一给，不再抄一遍。
     */
    private fun payCall(path: String, token: String, form: String): String {
        val c = open(BASE + path, token)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Authorization", PAY_BASIC)
        c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        return body(c)
    }

    /** 业务失败时服务端会给一句中文，例如「未绑定房间信息！」 */
    fun msgOf(json: String): String? =
        MSG.find(unescape(json))?.groupValues?.get(1)?.trim()?.ifBlank { null }

    /** 从 oauth 返回里取 token；refresh_token 与 expires_in 可能缺 */
    fun parseToken(json: String): Auth? {
        val tok = TOKEN.find(json)?.groupValues?.get(1) ?: return null
        val ref = REFRESH.find(json)?.groupValues?.get(1).orEmpty()
        val exp = EXPIRES.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Auth(tok, ref, if (exp > 0) System.currentTimeMillis() + exp * 1000 else 0L)
    }

    // ───────────────────────── 网络 ─────────────────────────

    private fun open(url: String, token: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_MS
        c.readTimeout = READ_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("synAccessSource", SRC)
        if (!token.isNullOrBlank()) c.setRequestProperty("synjones-auth", "bearer $token")
        return c
    }

    private fun body(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code == 401 || code == 403) {
            c.disconnect()
            throw NeedLogin()
        }
        val raw: InputStream = try {
            c.inputStream
        } catch (e: IOException) {
            c.errorStream ?: run { c.disconnect(); return "" }
        }
        val enc = c.getHeaderField("Content-Encoding") ?: ""
        val stream = if (enc.contains("gzip", true)) GZIPInputStream(raw) else raw
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            var line = r.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = r.readLine()
            }
        }
        c.disconnect()
        return sb.toString()
    }

    private fun get(url: String, token: String?): String {
        val c = open(url, token)
        c.requestMethod = "GET"
        return body(c)
    }

    private fun post(url: String, token: String, payload: String, ct: String): String {
        val c = open(url, token)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", ct)
        c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        return body(c)
    }

    private val OPT = Regex("\"name\":\"([^\"]*)\",\"value\":\"([^\"]*)\"")
    private val CODE = Regex("\"code\":\"([a-zA-Z_]+)\",\"level\":(\\d+)")
    private val SHOW = Regex("\"showData\":\\{([^{}]*)\\}")

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 诊断：把接口原始返回的开头写进 Android/data/…/files/card.log，排查解析用 */
    private fun logFile(ctx: Context) =
        java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "card.log")

    fun log(ctx: Context?, text: String) {
        if (ctx == null) return
        runCatching {
            val f = logFile(ctx)
            if (f.length() > 1024 * 1024) f.delete()
            f.appendText(System.currentTimeMillis().toString() + " CF " + text + "\n")
        }
    }

    /** 应用列表里的费项（不带余额） */
    fun fetchItems(ctx: Context?, token: String): List<Item> {
        val url = "$BASE/berserker-app/app/listAppByType?synAccessSource=$SRC"
        val body = get(url, token)
        log(ctx, "listAppByType len=" + body.length + " items=" + itemsOf(body).size)
        return itemsOf(body)
    }

    /** 用 refresh_token 换新的 access_token；失败返回 null，调用方转去重新登录 */
    fun refresh(a: Auth): Auth? {
        if (a.refresh.isBlank()) return null
        return try {
            val c = open("$BASE/berserker-auth/oauth/token", null)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("Authorization", "Basic $CLIENT")
            val form = "grant_type=refresh_token&refresh_token=${q(a.refresh)}&scope=all"
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val text = body(c)
            parseToken(text)?.let { n ->
                // 有些实现不返新的 refresh_token，沿用旧的
                if (n.refresh.isBlank()) n.copy(refresh = a.refresh) else n
            }
        } catch (e: Exception) {
            null
        }
    }

    // ───────────────────────── 本地保存 ─────────────────────────

    /** token 用 Keystore 派生的密钥加密后落盘（同密码存储那套）；键名带学号，多账号互不干扰 */
    fun save(ctx: Context, sno: String, a: Auth): Boolean =
        SecretStore.save(ctx, cardKey(sno), "${q(a.token)}|${q(a.refresh)}|${a.expiresAt}")

    /** 本机是否存有该学号的校园卡查询凭据 */
    fun bound(ctx: Context, sno: String): Boolean =
        !SecretStore.load(ctx, cardKey(sno)).isNullOrBlank()

    /** 「xiaoqu_id=1&loudong_id=505&…」还原成键值对 */
    fun roomOf(encoded: String): Map<String, String> =
        encoded.split("&").mapNotNull { p ->
            val i = p.indexOf('=')
            if (i > 0) p.substring(0, i) to p.substring(i + 1) else null
        }.toMap()

    fun load(ctx: Context, sno: String): Auth? {
        val raw = SecretStore.load(ctx, cardKey(sno)) ?: return null
        val p = raw.split("|")
        if (p.size != 3) return null
        val tok = try { URLDecoder.decode(p[0], "UTF-8") } catch (e: Exception) { "" }
        if (tok.isBlank()) return null
        val ref = try { URLDecoder.decode(p[1], "UTF-8") } catch (e: Exception) { "" }
        return Auth(tok, ref, p[2].toLongOrNull() ?: 0L)
    }

    /** 只丢 token：界面还留着上次读数，用户点「立即查询」会提示重新登录 */
    fun dropToken(ctx: Context, sno: String) {
        SecretStore.save(ctx, cardKey(sno), "")
    }

    /**
     * 清掉某个学号的校园卡状态：查询凭据、上次读数、房间、费项编号、预警与定时查询设置。
     * 清完顺手取消定时查询，避免后台每天空转。
     */
    fun wipe(ctx: Context, sno: String) {
        if (sno.isBlank()) return
        SecretStore.forget(ctx, cardKey(sno))
        runCatching { logFile(ctx).delete() }
        val p = Prefs(ctx)
        p.wipeCard(sno)
        CardWatch.sync(ctx, sno)
    }
}
