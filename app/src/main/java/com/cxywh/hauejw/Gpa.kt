package com.cxywh.hauejw

/**
 * 成绩汇总（GPA / 学分进度）。纯算术，不联网、不依赖界面 —— 输入就是成绩接口返回的那批行。
 *
 * 口径（刻意保守，不做任何"替学校补算"的猜测）：
 *  - 加权 GPA   = Σ(绩点 × 学分) / Σ学分，只用「学校自己给出的 jd > 0」的行
 *  - 加权平均分 = Σ(成绩 × 学分) / Σ学分，只认能解析成数字的成绩
 *  - 学分进度   = Σ学分（全部有学分的行），通过学分只统计及格的行
 *  - 等级制成绩（优秀/良好/通过…）算学分、算通过，但不进 GPA 与平均分 —— 它们本身没有绩点
 *
 * 🔴 绩点为 0 的行要分两种情况，混在一起必然算错：
 *    ① 不及格（数字成绩 < 60）→ 是真的绩点 0，必须计入分母（否则 GPA 会被抬高）
 *    ② 免修 / 实践 / 等级制 → 绩点 0 只是"这门课没有绩点"，必须排除
 *    判据是成绩是不是数字且 < 60。
 */
data class CategoryStat(
    val name: String,
    val count: Int,
    val credits: Double,
    val gpa: Double?,
)

data class ScoreSummary(
    val count: Int,
    val credits: Double,
    val passedCredits: Double,
    val gpa: Double?,
    val avg: Double?,
    val failedCount: Int,
    val byCategory: List<CategoryStat>,
) {
    val hasGpa: Boolean get() = gpa != null
}

/** 成绩里能当"通过"看的等级制文字 */
private val PASS_WORDS = listOf("优", "良", "中", "及格", "合格", "通过", "免修")
private val FAIL_WORDS = listOf("不及格", "不合格", "未通过", "缺考", "作弊")

/** 这条成绩算通过吗（数字 >= 60，或等级制里的通过类文字） */
internal fun isPassed(cj: String): Boolean {
    cj.toDoubleOrNull()?.let { return it >= 60.0 }
    val t = cj.trim()
    if (t.isEmpty()) return false
    if (FAIL_WORDS.any { t.contains(it) }) return false
    return PASS_WORDS.any { t.contains(it) }
}

/** 这条成绩算不及格吗（数字 < 60，或明确的失败文字） */
internal fun isFailed(cj: String): Boolean {
    cj.toDoubleOrNull()?.let { return it < 60.0 }
    val t = cj.trim()
    if (t.isEmpty()) return false
    if (FAIL_WORDS.any { t.contains(it) }) return true
    return false
}

/** 该不该把这一行的绩点计入 GPA 分母 */
internal fun countsTowardGpa(jd: Double?, cj: String): Boolean {
    if (jd == null) return false
    if (jd > 0.0) return true
    // 绩点 0：只有"数字成绩不及格"才算真 0；免修/实践/等级制的 0 不算
    return isFailed(cj)
}

fun summarize(rows: List<ScoreRow>): ScoreSummary {
    var credits = 0.0
    var passedCredits = 0.0
    var failedCount = 0
    var gpaNum = 0.0
    var gpaDen = 0.0
    var avgNum = 0.0
    var avgDen = 0.0

    // 分类小计（键是学校给的课程性质，空则归"其他"）
    val catCredits = LinkedHashMap<String, Double>()
    val catCount = LinkedHashMap<String, Int>()
    val catNum = LinkedHashMap<String, Double>()
    val catDen = LinkedHashMap<String, Double>()

    for (r in rows) {
        val xf = r.xf.toDoubleOrNull() ?: 0.0
        if (xf > 0) credits += xf
        if (isPassed(r.cj) && xf > 0) passedCredits += xf
        if (isFailed(r.cj)) failedCount++

        val jd = r.jd.toDoubleOrNull()
        if (xf > 0 && countsTowardGpa(jd, r.cj)) {
            gpaNum += jd!! * xf
            gpaDen += xf
        }

        val cj = r.cj.toDoubleOrNull()
        if (xf > 0 && cj != null) {
            avgNum += cj * xf
            avgDen += xf
        }

        val key = r.kcxzmc.trim().ifBlank { "其他" }
        catCount[key] = (catCount[key] ?: 0) + 1
        if (xf > 0) catCredits[key] = (catCredits[key] ?: 0.0) + xf
        if (xf > 0 && countsTowardGpa(jd, r.cj)) {
            catNum[key] = (catNum[key] ?: 0.0) + jd!! * xf
            catDen[key] = (catDen[key] ?: 0.0) + xf
        }
    }

    val byCategory = catCount.keys.map { k ->
        val den = catDen[k] ?: 0.0
        CategoryStat(
            name = k,
            count = catCount[k] ?: 0,
            credits = catCredits[k] ?: 0.0,
            gpa = if (den > 0) catNum[k]!! / den else null,
        )
    }.sortedByDescending { it.credits }

    return ScoreSummary(
        count = rows.size,
        credits = credits,
        passedCredits = passedCredits,
        gpa = if (gpaDen > 0) gpaNum / gpaDen else null,
        avg = if (avgDen > 0) avgNum / avgDen else null,
        failedCount = failedCount,
        byCategory = byCategory,
    )
}

/** 学分数在界面上要好看：28.5 / 28（不要 28.0） */
// 固定用 Locale.US：小数点是点还是逗号取决于地区，界面上的数字不该跟着地区变
fun fmtCredit(v: Double): String =
    if (kotlin.math.abs(v - kotlin.math.round(v)) < 0.005) kotlin.math.round(v).toInt().toString()
    else String.format(java.util.Locale.US, "%.1f", v)

fun fmt2(v: Double?): String =
    if (v == null) "—" else String.format(java.util.Locale.US, "%.2f", v)
