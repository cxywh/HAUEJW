package com.cxywh.hauejw

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 原生成绩查询。
 *
 * 为什么要自己做：学校那个成绩页单独打开时脚本链是断的（它假定自己被门户页套着运行），
 * 点「查询」不发请求 —— 探针实测点击命中了按钮（#search_go），但学校自己的 search() 什么都没发。
 * 而接口本身是好的：POST /jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005 + doType=query，
 * 用 App 已登录的会话就能拿到 jqGrid 的 JSON（已在真机上验证出真实成绩数据）。
 * 所以这里直接走接口，不依赖学校页面。
 */
data class ScoreRow(
    val kcmc: String,
    val cj: String,
    val jd: String,
    val kcxzmc: String,
    val jsxm: String,
    val xf: String,
    val kcbj: String,
)

/** 查询结果 + 学校自己给的培养层次（本科 / 专科），用来判断学制是四年还是三年 */
data class ScorePage(val rows: List<ScoreRow>, val pycc: String)

object ScoresApi {

    private const val PAGE = "/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005"

    /**
     * 查某学年某学期的成绩。xnm 是学年起始年（2023 = 2023-2024），
     * xqm 是正方字典的学期号：3=第一学期, 12=第二学期, 16=第三学期。
     * 会话过期时学校会回登录页 HTML（不是 JSON）→ JSONObject 抛异常，由调用方提示重新登录。
     */
    fun fetch(xnm: Int, xqm: Int): ScorePage {
        val url = JwApi.BASE + PAGE
        val form = mapOf(
            "xnm" to xnm.toString(),
            "xqm" to xqm.toString(),
            "kcbjdm" to "",
            "page" to "1",
            "rows" to "300",
            "sidx" to "",
            "sord" to "asc",
            "_search" to "false",
            "doType" to "query",
        )
        val body = JwApi.post(url, form, referer = url, ajax = true)
        if (body.isBlank()) {
            // 空响应：多半是没带上会话被重定向了，把状态码/最终地址带上便于定位
            throw IllegalStateException("响应为空 · " + JwApi.postDebug(url, form, url))
        }
        val j = JSONObject(body)                       // 非 JSON（登录页）会在这里抛
        val arr = j.optJSONArray("items") ?: return ScorePage(emptyList(), j.optString("pycc"))
        val out = ArrayList<ScoreRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += ScoreRow(
                kcmc = o.optString("kcmc").ifBlank { o.optString("jxbmc") },
                cj = o.optString("cj").ifBlank { o.optString("bfzcj") },
                jd = o.optString("jd"),
                kcxzmc = o.optString("kcxzmc").ifBlank { o.optString("kclbmc") },
                jsxm = o.optString("jsxm"),
                xf = o.optString("xf"),
                kcbj = o.optString("kcbj"),
            )
        }
        val pycc = if (arr.length() > 0) arr.optJSONObject(0)?.optString("pycc").orEmpty() else ""
        return ScorePage(out, pycc)
    }
}

/** 学期号 → 中文（正方字典） */
internal val TERM_OPTIONS = listOf(3 to "第一学期", 12 to "第二学期", 16 to "第三学期")

internal fun termLabel(code: Int): String =
    TERM_OPTIONS.firstOrNull { it.first == code }?.second ?: "第 ${code} 学期"

/**
 * 学号前四位 = 入学年（如 202300000000 → 2023 年入学）。
 * 学号不是常见格式时返回 null，由调用方回退到当前年推算。
 */
internal fun entryYearOf(ctx: android.content.Context): Int? =
    Prefs(ctx).username.take(4).toIntOrNull()?.takeIf { it in 1990..2100 }

/**
 * 会话过期时用本机加密存的学号+密码补登一次。
 * 密码只在本机解密、只发往学校服务器，不出设备、不进日志。
 */
internal fun relogin(ctx: android.content.Context): Boolean = try {
    val user = Prefs(ctx).username
    val pass = SecretStore.load(ctx)
    if (user.isBlank() || pass.isNullOrBlank()) {
        false
    } else {
        JwApi.login(user, pass)
        runCatching { JwApi.cookieHeader()?.let { SecretStore.saveSession(ctx, it) } }.onFailure { e -> Diag.record("Scores.relogin", e) }
        true
    }
} catch (e: Exception) {
    false
}

@Composable
fun ScoresPane(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 学号前四位 = 入学年；据此列出对应学制的学年（四年制本科 / 三年制专科）
    val entry = remember { entryYearOf(ctx) }
    var span by remember { mutableStateOf(4) }
    val years = remember(entry, span) {
        val base = entry ?: (java.time.LocalDate.now().year - 4)
        (base until base + span).toList()
    }
    var year by remember { mutableStateOf(years.first()) }
    var term by remember { mutableStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<ScoreRow>?>(null) }
    var msg by remember { mutableStateOf("") }

    // 切换学制后学年列表变短时，别让选中的年份落在列表外
    LaunchedEffect(years) { if (year !in years) year = years.first() }

    // 学校自己回的培养层次（本科/专科）决定默认学制，用户仍可手动切换
    fun applySpan(pycc: String) {
        when {
            pycc.contains("专科") || pycc.contains("高职") || pycc.contains("中专") -> span = 3
            pycc.isNotBlank() -> span = 4
        }
    }

    fun load() {
        scope.launch {
            busy = true
            msg = ""
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    // 会话过期时学校会回空响应/登录页：用本机加密存的密码补登一次再重试（密码不出设备）
                    try {
                        ScoresApi.fetch(year, term)
                    } catch (e: Exception) {
                        if (!relogin(ctx)) throw e
                        ScoresApi.fetch(year, term)
                    }
                }.onFailure { e -> Diag.record("Scores.load", e) }
            }
            busy = false
            res.fold(
                onSuccess = { page ->
                    rows = page.rows
                    applySpan(page.pycc)
                    if (page.rows.isEmpty()) msg = "$year-${year + 1} 年 ${termLabel(term)}：没有查到成绩记录（这学期可能确实还没出分）"
                },
                onFailure = { e ->
                    rows = null
                    msg = "查询失败：${e.message ?: "未知错误"}。" +
                        "如果提示登录相关，请返回主界面重新登录一次。"
                },
            )
        }
    }

    Column(
        Modifier.fillMaxSize().background(C.bg).padding(14.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的成绩", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.fg, modifier = Modifier.weight(1f))
            Text("关闭", fontSize = 14.sp, color = C.dim, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "红色 = 不及格，其余按学校规则显示。",
            fontSize = 11.sp, color = C.dim,
        )
        Spacer(Modifier.height(12.dp))

        Text("学年", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            years.forEach { y -> Chip("$y-${y + 1}", y == year) { year = y } }
        }
        Spacer(Modifier.height(10.dp))

        Text("学制", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("四年制", span == 4) { span = 4 }
            Chip("三年制", span == 3) { span = 3 }
        }
        Spacer(Modifier.height(12.dp))

        Text("学期", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TERM_OPTIONS.forEach { (code, label) ->
                Chip(label, code == term) { term = code }
            }
        }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (busy) C.dim else C.accent)
                    .clickable(enabled = !busy) { load() }
                    .padding(horizontal = 22.dp, vertical = 11.dp),
            ) {
                Text(if (busy) "查询中…" else "查询", color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(10.dp))
            Text("共 ${rows?.size ?: 0} 门", fontSize = 12.sp, color = C.dim)
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 12.sp, color = C.danger)
        }

        rows?.let { list ->
            Spacer(Modifier.height(12.dp))
            list.forEach { r ->
                val n = r.cj.toDoubleOrNull()
                val fail = n != null && n < 60
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.kcmc.ifBlank { "（未命名课程）" }, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, color = C.fg, modifier = Modifier.weight(1f))
                        Text(r.cj, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            color = if (fail) C.danger else C.accent)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            if (r.kcxzmc.isNotBlank()) append(r.kcxzmc)
                            if (r.xf.isNotBlank() && r.xf != "0") { if (isNotEmpty()) append(" · "); append("${r.xf} 学分") }
                            if (r.jd.isNotBlank() && r.jd != "0") { if (isNotEmpty()) append(" · "); append("绩点 ${r.jd}") }
                            if (r.jsxm.isNotBlank()) { if (isNotEmpty()) append(" · "); append(r.jsxm) }
                            if (r.kcbj.isNotBlank() && r.kcbj != "主修") { if (isNotEmpty()) append(" · "); append(r.kcbj) }
                        },
                        fontSize = 11.sp, color = C.dim,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) C.accent else C.card)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp,
            color = if (on) androidx.compose.ui.graphics.Color.White else C.fg)
    }
}
