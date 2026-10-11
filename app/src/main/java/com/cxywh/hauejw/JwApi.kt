package com.cxywh.hauejw

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher

/**
 * 正方教务系统(jwglxt)接口层
 *
 * 登录机制（逆向自站内 login.js，与桌面端脚本一致）：
 *  1) GET  /jwglxt/xtgl/login_slogin.html        取 csrftoken + 表单隐藏配置
 *  2) GET  /jwglxt/xtgl/login_getPublicKey.html  取 RSA 公钥 {modulus, exponent}（均为 base64）
 *  3) 密码 mmsfjm=1 → RSA/PKCS#1 v1.5 加密后 base64
 *  4) POST /jwglxt/xtgl/login_slogin.html        yhm / mm / csrftoken / ...
 *  5) GET  /jwglxt/xtgl/index_initMenu.html      验证会话
 */
object JwApi {

    const val HOST = "124.system.haue.edu.cn"
    const val BASE = "http://$HOST"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }

    init {
        CookieHandler.setDefault(cookieManager)
    }

    // ───────────────────────── 基础 HTTP ─────────────────────────

    private fun open(url: String, method: String, referer: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        if (referer != null) c.setRequestProperty("Referer", referer)
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val raw: InputStream = try {
            c.inputStream
        } catch (e: Exception) {
            c.errorStream ?: return ""
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
        return sb.toString()
    }

    fun get(url: String, referer: String? = null): String =
        open(url, "GET", referer).let { c ->
            c.connect()
            val body = readBody(c)
            c.disconnect()
            body
        }

    fun post(url: String, form: Map<String, String>, referer: String? = null, ajax: Boolean = false): String {
        val payload = form.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val c = open(url, "POST", referer)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        if (ajax) c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val body = readBody(c)
        c.disconnect()
        return body
    }

    /** 诊断用：拿 HTTP 状态码 / Content-Type / 长度 / 最终 URL，用来判断"空响应"是重定向还是被拒 */
    fun postDebug(url: String, form: Map<String, String>, referer: String?): String {
        val payload = form.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return try {
            val c = open(url, "POST", referer)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val ct = c.getHeaderField("Content-Type") ?: "-"
            val body = readBody(c)
            val finalUrl = c.url.toString()
            c.disconnect()
            "HTTP $code ct=$ct len=${body.length} -> $finalUrl"
        } catch (e: Exception) {
            "诊断异常 ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ───────────────────────── 登录 ─────────────────────────

    class LoginException(message: String) : Exception(message)

    fun login(username: String, password: String): String {
        // 1) 登录页：csrftoken + 隐藏字段
        val page = get("$BASE/jwglxt/xtgl/login_slogin.html")
        val csrf = Regex("name=\"csrftoken\"\\s+value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: throw LoginException("取不到登录令牌（csrftoken），可能网络异常或页面结构变化")
        val hidden = parseInputs(page)

        // 2) 公钥
        val pkBody = get("$BASE/jwglxt/xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}")
        val pk = try {
            JSONObject(pkBody)
        } catch (e: Exception) {
            throw LoginException("取不到 RSA 公钥，服务端返回异常")
        }
        val cipherText = rsaEncrypt(password, pk.getString("modulus"), pk.getString("exponent"))

        // 3) 提交
        val form = mutableMapOf(
            "yhm" to username,
            "mm" to cipherText,
            "csrftoken" to csrf,
            "language" to "zh_CN",
            "ydType" to "",
        )
        for (k in listOf(
            "yzcskz", "mmsfjm", "mmsrddcshkzfs", "dlsbsdsj", "dlmmsfknt", "xxdm",
            "userPolicyOn", "dxsyrz", "sfxswechatlogin", "sfzywqh", "scene_id"
        )) form[k] = hidden[k] ?: ""
        val resp = post("$BASE/jwglxt/xtgl/login_slogin.html", form, "$BASE/jwglxt/xtgl/login_slogin.html")

        // 4) 校验
        Regex("用户名或密码不正确").find(resp)?.let { throw LoginException("用户名或密码不正确") }
        Regex("验证码").find(resp)?.let {
            if (resp.contains("yzm") && resp.contains("请输入验证码")) {
                throw LoginException("需要输入验证码（连续失败后由系统触发，请先在电脑浏览器登录一次）")
            }
        }
        val home = get("$BASE/jwglxt/xtgl/index_initMenu.html?jsdm=xs")
        if (home.contains("login_slogin")) throw LoginException("登录未成功，会话无效")
        return home
    }

    /** RSA/PKCS#1 v1.5 加密（对应 JSBN RSAKey.encrypt + hex2b64），modulus/exponent 均为 base64 */
    private fun rsaEncrypt(text: String, modulusB64: String, exponentB64: String): String {
        val n = BigInteger(1, Base64.decode(modulusB64, Base64.DEFAULT))
        val e = BigInteger(1, Base64.decode(exponentB64, Base64.DEFAULT))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 提取页面里所有 <input> 的 name → value */
    fun parseInputs(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tag in Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val t = tag.value
            val name = Regex("name=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: continue
            val value = Regex("value=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
            out.putIfAbsent(name, value)
        }
        return out
    }

    // ───────────────────────── 课表 ─────────────────────────

    const val KB_URL = "$BASE/jwglxt/kbcx/xskbcx_cxXsgrkb.html"

    /** 校历：从首页区域块解析「2026-2027学年 1 学期（2026-08-31 至 2027-01-10）」 */
    fun fetchCalendar(): Term? {
        return try {
            val html = get("$BASE/jwglxt/xtgl/index_cxAreaFive.html?gnmkdm=index")
            val text = html.replace(Regex("<[^>]+>"), " ")
            val m = Regex("(\\d{4})-(\\d{4})学年\\s*(\\d)\\s*学期\\s*[（(]\\s*(\\d{4}-\\d{2}-\\d{2})\\s*至\\s*(\\d{4}-\\d{2}-\\d{2})")
                .find(text) ?: return null
            val xnm = m.groupValues[1]
            val xqm = when (m.groupValues[3]) { "1" -> "3"; "2" -> "12"; else -> "16" }
            Term(
                xnm = xnm,
                xnmc = "${m.groupValues[1]}-${m.groupValues[2]}",
                xqm = xqm,
                startDate = LocalDate.parse(m.groupValues[4]),
                endDate = LocalDate.parse(m.groupValues[5]),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 候选学年学期：优先校历，其次按当前时间推算，最后兜底 */
    private fun candidates(cal: Term?): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        if (cal != null && cal.xnm.isNotBlank()) list.add(cal.xnm to cal.xqm)
        val now = LocalDate.now()
        val y = if (now.monthValue >= 8) now.year else now.year - 1
        list.add("$y" to "3")
        list.add("$y" to "12")
        list.add("${y - 1}" to "12")
        list.add("${y - 1}" to "3")
        return list.distinct()
    }

    fun fetchTimetable(): Pair<Timetable, String> {
        val idxUrl = "$BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151"
        val idx = get(idxUrl)
        if (idx.contains("login_slogin")) throw LoginException("会话已过期，请重新登录")
        val form = parseInputs(idx)

        val cal = fetchCalendar()
        var json: JSONObject? = null

        for ((xnm, xqm) in candidates(cal)) {
            val body = post(
                KB_URL,
                form + mapOf(
                    "xnm" to xnm, "xqm" to xqm, "gnmkdm" to "N2151",
                    "page" to "1", "rows" to "5000",
                ),
                idxUrl,
                ajax = true,
            )
            val obj = try { JSONObject(body) } catch (e: Exception) { null }
            if (obj != null && (obj.optJSONArray("kbList")?.length() ?: 0) +
                (obj.optJSONArray("sjkList")?.length() ?: 0) > 0
            ) {
                json = obj
                break
            }
        }
        val j = json ?: throw LoginException("未取到课表数据（可能本学期无课或接口有变）")
        val tt = parseTimetable(j, cal)
        val wrapper = JSONObject()
            .put("raw", j)
            .put("start", cal?.startDate?.toString() ?: "")
            .put("end", cal?.endDate?.toString() ?: "")
        return tt to wrapper.toString()
    }

    /** 纯解析：服务端课表 JSON → Timetable（缓存还原也走这里） */
    fun parseTimetable(j: JSONObject, cal: Term?): Timetable {
        // 学期
        val xsxx = j.optJSONObject("xsxx")
        val xnm = xsxx?.optString("XNM").orEmpty()
        val xqm = xsxx?.optString("XQM").orEmpty().ifBlank { cal?.xqm ?: "" }
        val student = Student(
            name = xsxx?.optString("XM").orEmpty(),
            id = xsxx?.optString("XH").orEmpty(),
            className = xsxx?.optString("BJMC").orEmpty(),
            major = xsxx?.optString("ZYMC").orEmpty(),
        )

        // 课程
        val courses = mutableListOf<Course>()
        val seen = mutableSetOf<String>()
        val kb = j.optJSONArray("kbList")
        if (kb != null) for (i in 0 until kb.length()) {
            val o = kb.getJSONObject(i)
            val zcd = o.optString("zcd")
            val (s, e) = WeekUtils.parseSlots(o.optString("jcs"))
            val c = Course(
                name = o.optString("kcmc"),
                teacher = o.optString("xm"),
                room = o.optString("cdmc").trim(),
                campus = o.optString("xqmc").trim(),
                dayOfWeek = o.optString("xqj").toIntOrNull() ?: 1,
                startSlot = s, endSlot = e,
                weekText = zcd,
                weeks = WeekUtils.parseWeeks(zcd),
                credit = o.optString("xf"),
                category = o.optString("kclb"),
                kind = o.optString("kcxz"),
                exam = o.optString("khfsmc"),
                className = o.optString("jxbzc"),
            )
            if (seen.add("${c.name}|${c.dayOfWeek}|${c.startSlot}|${c.endSlot}|${c.weekText}|${c.room}")) courses.add(c)
        }
        val sjk = j.optJSONArray("sjkList")
        if (sjk != null) for (i in 0 until sjk.length()) {
            val o = sjk.getJSONObject(i)
            val z = o.optString("qsjsz").ifBlank { o.optString("sjkcgs") }
            val c = Course(
                name = o.optString("kcmc"),
                teacher = o.optString("jsxm"),
                room = "", campus = "",
                dayOfWeek = 0, startSlot = 0, endSlot = 0,
                weekText = z, weeks = WeekUtils.parseWeeks(z),
                category = o.optString("kclb"),
                exam = o.optString("khfsmc"),
                className = o.optString("jxbzh"),
            )
            if (seen.add("${c.name}|practice|${c.weekText}")) courses.add(c)
        }

        // 学期与周数
        val maxFromCourses = courses.flatMap { it.weeks }.maxOrNull() ?: 20
        val maxWeek = maxOf(maxFromCourses, cal?.let {
            java.time.temporal.ChronoUnit.DAYS.between(it.startDate, it.endDate).toInt() / 7 + 1
        } ?: 20)

        val term = Term(
            xnm = xnm,
            xnmc = xsxx?.optString("XNMC").orEmpty().ifBlank { cal?.xnmc ?: "" },
            xqm = xqm,
            startDate = cal?.startDate,
            endDate = cal?.endDate,
            maxWeek = maxWeek,
        )
        return Timetable(term, student, courses)
    }

    // ───────────────────────── 会话 / 直达 ─────────────────────────

    /** 当前会话的 Cookie 串（供 WebView 注入，实现"已登录状态直达内页"） */
    fun cookieHeader(): String? {
        val cookies = cookieManager.cookieStore.get(java.net.URI.create(BASE)) ?: return null
        val s = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        return s.ifBlank { null }
    }

    fun logout() {
        try { get("$BASE/jwglxt/xtgl/login_logout.html") } catch (_: Exception) {}
        cookieManager.cookieStore.removeAll()
    }

    /**
     * 进程重启后用保存下来的会话串恢复 Cookie。
     * 不恢复的话：App 被系统回收一次，直达页就静默变成未登录状态（页面照常渲染，按钮全失效）。
     */
    fun restoreCookie(header: String) {
        try {
            val uri = java.net.URI.create(BASE)
            header.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) {
                    cookieManager.cookieStore.add(
                        uri,
                        java.net.HttpCookie(kv.substring(0, i).trim(), kv.substring(i + 1).trim()),
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    fun clearCookies() = cookieManager.cookieStore.removeAll()

    /**
     * 预热连接：冷启动第一屏经常"开头几个请求整批失败"，移动网络下多因首个 DNS 查询/建连太慢。
     * 提前打一次空请求把 DNS、TCP 暖起来，页面的脚本请求就不容易在起跑线上掉。
     */
    fun warmUp() {
        try {
            val c = java.net.URL("$BASE/jwglxt/").openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = false
            c.connect()
            c.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * 解析首页菜单里的跳转地址。学校版本不同、写法差别很大，所以宽容地多认几种：
     *  1) clickMenu('N2151','/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151','个人课表查询',...)——单/双引号都认
     *  2) 页面里任何 /jwglxt/ 下的 .html 地址，名字取它附近的标签（title / a 标签体 / 左侧最近的中文）
     * 名字 → 路径。直达页用它拿学校配的真实入口，比写死路径稳。
     */
    fun parseMenus(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        for (m in clickMenuPattern.findAll(html)) {
            val id = m.groupValues[1].trim()
            addMenu(out, m.groupValues[3], withGnmkdm(m.groupValues[2], id))
        }

        for (m in urlPattern.findAll(html)) {
            val path = m.groupValues[1].replace("&amp;", "&")
            if (out.values.any { it == path }) continue
            val label = labelNear(html, m.range.first)
                ?: path.substringAfterLast('/').substringBefore('?')
            addMenu(out, label, path)
        }
        return out
    }

    private val clickMenuPattern =
        Regex("""clickMenu\(\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]""")

    private val urlPattern = Regex("""['"](/jwglxt/[^'"]{3,120}?\.html[^'"]*)['"]""")

    /**
     * 学校菜单里的地址通常不带 gnmkdm —— 是页面上的 JS 点菜单时才把功能号拼上去的。
     * 少了它，服务端不认这个模块，返回的是「系统升级维护中」而不是 404，特别容易误判。
     */
    private fun withGnmkdm(path0: String, id: String): String {
        val path = path0.trim()
        if (id.isBlank() || !path.contains(".html") || path.contains("gnmkdm=", true)) return path
        return path + if (path.contains('?')) "&gnmkdm=$id" else "?gnmkdm=$id"
    }

    /** 诊断用：clickMenu 原始三元组「功能号 | 地址 | 名称」 */
    fun rawMenuTriples(html: String): List<String> =
        clickMenuPattern.findAll(html)
            .map { "${it.groupValues[1]}\t${it.groupValues[2]}\t${it.groupValues[3]}" }
            .distinct().toList()

    private fun addMenu(out: MutableMap<String, String>, name0: String, path0: String) {
        val name = name0.trim()
        val path = path0.trim()
        if (name.isBlank() || path.isBlank()) return
        if (path.contains("javascript", true) || path.startsWith("#")) return
        out.putIfAbsent(name, path)
    }

    /** 地址附近的中文标签：title= > <a ...>标签</a> > 往前找最近的一段中文 */
    private fun labelNear(html: String, at: Int): String? {
        val win = html.substring(at, (at + 400).coerceAtMost(html.length))
        Regex("title=\"([^\"]{2,24})\"").find(win)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.any { c -> c.code > 127 } }?.let { return it }
        Regex(""">([^<>]{2,24})<""").find(win)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.any { c -> c.code > 127 } }?.let { return it }
        val before = html.substring((at - 240).coerceAtLeast(0), at)
        Regex("""[\u4e00-\u9fa5（）()a-zA-Z0-9]{2,16}""").findAll(before).lastOrNull()?.value
            ?.takeIf { it.any { c -> c.code > 127 } }?.let { return it }
        return null
    }

    /** 页面里出现的所有 /jwglxt 地址（诊断用） */
    fun allPaths(html: String): List<String> =
        urlPattern.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.distinct().toList()

    /** clickMenu(...) 出现次数（诊断用：0 说明这所学校不是这种写法） */
    fun countClickMenu(html: String): Int =
        Regex("""clickMenu\(|toMenu\(|menuClick\(""").findAll(html).count()

    // ───────────────────────── 自检 ─────────────────────────

    data class CheckItem(val name: String, val ok: Boolean, val detail: String)

    /** 真机自检：逐项测通并给出耗时，便于判断"是网络问题还是服务器问题" */
    fun selfCheck(): List<CheckItem> {
        val out = mutableListOf<CheckItem>()

        var t0 = System.currentTimeMillis()
        val page = runCatching { get("$BASE/jwglxt/xtgl/login_slogin.html") }.onFailure { e -> Diag.record("JwApi.selfCheck", e) }
        out += page.fold(
            onSuccess = { p ->
                val ms = System.currentTimeMillis() - t0
                val ok = p.contains("csrftoken")
                CheckItem(
                    "登录页", ok,
                    "${ms}ms　${p.length / 1024}KB　" + if (ok) "表单正常" else "未找到登录表单（页面结构可能变化）"
                )
            },
            onFailure = { CheckItem("登录页", false, "不可达：" + (it.message ?: "网络异常")) },
        )

        t0 = System.currentTimeMillis()
        val pk = runCatching { get("$BASE/jwglxt/xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}") }.onFailure { e -> Diag.record("JwApi.selfCheck", e) }
        out += pk.fold(
            onSuccess = { s ->
                val ms = System.currentTimeMillis() - t0
                val ok = s.contains("modulus")
                CheckItem("RSA 公钥接口", ok, "${ms}ms　" + if (ok) "密码加密通道可用" else "返回异常")
            },
            onFailure = { CheckItem("RSA 公钥接口", false, it.message ?: "失败") },
        )

        val ck = cookieHeader()
        out += CheckItem(
            "登录会话", ck != null,
            if (ck != null) "已持有会话（可直达内页）" else "无会话，需登录",
        )

        if (ck != null) {
            t0 = System.currentTimeMillis()
            val idx = runCatching { get("$BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151") }.onFailure { e -> Diag.record("JwApi.selfCheck", e) }
            out += idx.fold(
                onSuccess = { s ->
                    val ms = System.currentTimeMillis() - t0
                    val ok = !s.contains("login_slogin")
                    CheckItem("课表页", ok, "${ms}ms　" + if (ok) "会话有效" else "被要求重新登录（会话已过期）")
                },
                onFailure = { CheckItem("课表页", false, it.message ?: "失败") },
            )
        }
        return out
    }
}
