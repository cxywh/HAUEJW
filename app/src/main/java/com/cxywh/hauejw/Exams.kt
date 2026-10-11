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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 原生考试信息查询。
 *
 * 和成绩页同一个道理：学校那个考试页单独打开时脚本链是断的（点「查询」不发请求），
 * 但接口本身是好的 —— 直接 POST 页面自身的地址 + doType=query 就能拿到 jqGrid 的 JSON。
 * 这里沿用成绩页验证过的那套（含会话过期自动补登）。
 *
 * 注意：考试页的字段名没有像成绩页那样被真机数据核对过，所以首次查询会把原始 JSON
 */
data class ExamRow(
    val kcmc: String,     // 课程
    val time: String,     // 考试时间
    val place: String,    // 校区 + 考场
    val kind: String,     // 考试名称 + 方式（期末考试 · 笔试）
    val teacher: String,  // 教师
    val seat: String,     // 座位号（有就显示）
)

/** 查询结果 + 学校自己给的培养层次（本科 / 专科），用来判断学制是四年还是三年 */
data class ExamPage(val rows: List<ExamRow>, val pycc: String)

object ExamsApi {

    private const val PAGE = "/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105"

    /** 查某学年某学期的考试安排。xnm=学年起始年，xqm：3=第一学期, 12=第二学期, 16=第三学期。 */
    fun fetch(ctx: android.content.Context?, xnm: Int, xqm: Int): ExamPage {
        val url = JwApi.BASE + PAGE
        val form = mapOf(
            "xnm" to xnm.toString(),
            "xqm" to xqm.toString(),
            "page" to "1",
            "rows" to "300",
            "sidx" to "",
            "sord" to "asc",
            "_search" to "false",
            "doType" to "query",
        )
        val body = JwApi.post(url, form, referer = url, ajax = true)
        if (body.isBlank()) {
            throw IllegalStateException("响应为空 · " + JwApi.postDebug(url, form, url))
        }
        // 落一份原始数据，便于核对字段名（不参与界面逻辑，失败也无所谓）
        if (ctx != null) {
        }
        val j = JSONObject(body)
        val arr = j.optJSONArray("items") ?: return ExamPage(emptyList(), j.optString("pycc"))
        val out = ArrayList<ExamRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val room = pick(o, "cdmc", "ksdd", "jasmc", "jsdmc", "kscsmc")
            val campus = pick(o, "cdxqmc", "xqmc")
            val name = pick(o, "ksmc")                                    // 期末考试 / 补考 …
            val mode = pick(o, "ksfs", "ksfsmc", "khfsmc", "ksxzmc")       // 笔试 / 机试 …
            out += ExamRow(
                kcmc = pick(o, "kcmc", "kch", "jxbmc"),
                time = pick(o, "kssj", "kssjms", "ksrq", "kksj", "kssjmc"),
                place = listOf(campus, room).filter { it.isNotBlank() }.joinToString(" "),
                kind = listOf(name, mode).filter { it.isNotBlank() }.joinToString(" · "),
                // jsxx 形如「11666/马洁」（工号/姓名），取姓名那截
                teacher = pick(o, "jsxm", "kkjs", "jsxx", "teacher").substringAfterLast("/"),
                seat = pick(o, "zwmc", "zwh", "zw"),
            )
        }
        return ExamPage(out, if (arr.length() > 0) arr.optJSONObject(0)?.optString("pycc").orEmpty() else "")
    }

    /** 从若干个可能的字段名里取第一个非空的（不同学校/版本的字段名不一致） */
    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }
}

@Composable
fun ExamsPane(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val entry = remember { entryYearOf(ctx) }
    var span by remember { mutableStateOf(4) }                 // 学制：4=四年制（本科）, 3=三年制（专科）
    val years = remember(entry, span) {
        val base = entry ?: (java.time.LocalDate.now().year - 4)
        (base until base + span).toList()
    }
    var year by remember { mutableStateOf(years.first()) }
    var term by remember { mutableStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<ExamRow>?>(null) }
    var msg by remember { mutableStateOf("") }

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
                    try {
                        ExamsApi.fetch(ctx, year, term)
                    } catch (e: Exception) {
                        if (!relogin(ctx)) throw e
                        ExamsApi.fetch(ctx, year, term)
                    }
                }.onFailure { e -> Diag.record("Exams.load", e) }
            }
            busy = false
            res.fold(
                onSuccess = { page ->
                    rows = page.rows
                    applySpan(page.pycc)
                    if (page.rows.isEmpty()) {
                        msg = "$year-${year + 1} 年 ${termLabel(term)}：这段时间没有考试安排"
                    }
                },
                onFailure = { e ->
                    rows = null
                    msg = "查询失败：${e.message ?: "未知错误"}。如果提示登录相关，请返回主界面重新登录一次。"
                },
            )
        }
    }

    Column(
        Modifier.fillMaxSize().background(C.bg).padding(14.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("考试信息", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.fg, modifier = Modifier.weight(1f))
            Text("关闭", fontSize = 14.sp, color = C.dim, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "时间 / 考场以学校系统为准，考前请再核一次。",
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
            TERM_OPTIONS.forEach { (code, label) -> Chip(label, code == term) { term = code } }
        }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (busy) C.dim else C.accent)
                    .clickable(enabled = !busy) { load() }
                    .padding(horizontal = 22.dp, vertical = 11.dp),
            ) {
                Text(if (busy) "查询中…" else "查询", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(10.dp))
            Text("共 ${rows?.size ?: 0} 场", fontSize = 12.sp, color = C.dim)
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 12.sp, color = C.danger)
        }

        rows?.let { list ->
            Spacer(Modifier.height(12.dp))
            list.forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(r.kcmc.ifBlank { "（未命名课程）" }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.fg)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (r.time.isNotBlank()) append(r.time)
                            if (r.place.isNotBlank()) { if (isNotEmpty()) append(" · "); append(r.place) }
                            if (r.seat.isNotBlank()) { if (isNotEmpty()) append(" · "); append("座位 ${r.seat}") }
                        }.ifBlank { "时间 / 考场待学校公布" },
                        fontSize = 13.sp, color = C.accent, fontWeight = FontWeight.Medium,
                    )
                    if (r.kind.isNotBlank() || r.teacher.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            buildString {
                                if (r.kind.isNotBlank()) append(r.kind)
                                if (r.teacher.isNotBlank()) { if (isNotEmpty()) append(" · "); append(r.teacher) }
                            },
                            fontSize = 11.sp, color = C.dim,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
