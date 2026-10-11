package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩汇总（GPA / 学分进度）的纯逻辑测试。
 *
 * 这里锁的是「口径」——最容易悄悄算错的地方：
 *  - 加权 GPA = Σ(绩点×学分)/Σ学分
 *  - 等级制成绩（优秀/通过…）算学分算通过，但**不进 GPA 分母**
 *  - 绩点 0 分两种情况：数字成绩不及格是真的 0（要进分母）；免修/等级制的 0 只是"没有绩点"（不进）
 *  - 学分 0/空 的行不进任何分母，否则会把 GPA 拉低
 */
class GpaTest {

    private fun row(
        kcmc: String = "高等数学A1",
        cj: String = "90",
        jd: String = "4.0",
        xf: String = "5",
        kcxzmc: String = "必修课",
    ) = ScoreRow(kcmc = kcmc, cj = cj, jd = jd, kcxzmc = kcxzmc, jsxm = "某老师", xf = xf, kcbj = "主修")

    // ───────────── 基础加权 ─────────────

    @Test
    fun `加权 GPA 与加权平均分`() {
        // (4.0×5 + 2.5×3) / 8 = 3.4375； (90×5 + 75×3) / 8 = 84.375
        val s = summarize(listOf(row(cj = "90", jd = "4.0", xf = "5"), row(kcmc = "服装材料", cj = "75", jd = "2.5", xf = "3")))
        assertEquals(3.4375, s.gpa!!, 1e-9)
        assertEquals(84.375, s.avg!!, 1e-9)
        assertEquals(8.0, s.credits, 1e-9)
        assertEquals(8.0, s.passedCredits, 1e-9)
        assertEquals(0, s.failedCount)
        assertEquals(2, s.count)
    }

    @Test
    fun `空表不编数字`() {
        val s = summarize(emptyList())
        assertNull("没有成绩就该是 —，不能是 0", s.gpa)
        assertNull(s.avg)
        assertEquals(0.0, s.credits, 1e-9)
        assertEquals(0, s.count)
        assertEquals("—", fmt2(s.gpa))
    }

    // ───────────── 绩点为 0 的两种情况（最容易算错） ─────────────

    @Test
    fun `不及格的绩点 0 要进分母，把 GPA 拉下来`() {
        // (4.0×5 + 0×4) / (5+4) = 20/9 = 2.2222…
        val s = summarize(listOf(
            row(cj = "90", jd = "4.0", xf = "5"),
            row(kcmc = "工程制图", cj = "45", jd = "0", xf = "4"),
        ))
        assertEquals(2.2222222222222223, s.gpa!!, 1e-9)
        assertEquals(1, s.failedCount)
        assertEquals(9.0, s.credits, 1e-9)
        assertEquals("不及格科目不能算进通过学分", 5.0, s.passedCredits, 1e-9)
    }

    @Test
    fun `学校没给绩点的等级制课程不进 GPA 分母，但算学分与通过`() {
        val s = summarize(listOf(
            row(cj = "90", jd = "4.0", xf = "5"),
            row(kcmc = "生产实习", cj = "优秀", jd = "0", xf = "2"),
            row(kcmc = "军事理论", cj = "合格", jd = "0", xf = "1"),
        ))
        assertEquals("只有 5 学分那门有绩点", 4.0, s.gpa!!, 1e-9)
        assertEquals(8.0, s.credits, 1e-9)
        assertEquals(8.0, s.passedCredits, 1e-9)
        assertEquals(0, s.failedCount)
        assertEquals("平均分同样只认数字成绩", 90.0, s.avg!!, 1e-9)
    }

    @Test
    fun `学分 0 或空的行不该进任何分母`() {
        val s = summarize(listOf(
            row(cj = "90", jd = "4.0", xf = "5"),
            row(kcmc = "军训", cj = "90", jd = "0", xf = "0"),
            row(kcmc = "某讲座", cj = "85", jd = "", xf = ""),
        ))
        assertEquals(4.0, s.gpa!!, 1e-9)
        assertEquals(5.0, s.credits, 1e-9)
    }

    @Test
    fun `全部都是等级制时 GPA 是空，而不是 0`() {
        val s = summarize(listOf(row(cj = "优秀", jd = "0", xf = "3")))
        assertNull(s.gpa)
        assertEquals(3.0, s.credits, 1e-9)
        assertEquals(3.0, s.passedCredits, 1e-9)
    }

    // ───────────── 通过 / 不及格的判定 ─────────────

    @Test
    fun `及格与不及格的文字判定`() {
        assertTrue(isPassed("60"))
        assertFalse(isPassed("59.5"))
        assertTrue(isPassed("及格"))
        assertTrue(isPassed("良好"))
        assertTrue(isPassed("免修"))
        assertFalse("「不及格」不能被「及格」误判成通过", isPassed("不及格"))
        assertFalse(isPassed(""))

        assertTrue(isFailed("59.5"))
        assertTrue(isFailed("不及格"))
        assertTrue(isFailed("缺考"))
        assertFalse(isFailed("及格"))
        assertFalse(isFailed("优秀"))
        assertFalse("空成绩不算不及格（可能只是还没出分）", isFailed(""))
    }

    @Test
    fun `绩点 0 只在成绩确实不及格时才计入`() {
        assertTrue(countsTowardGpa(4.0, "90"))
        assertTrue("不及格的真 0", countsTowardGpa(0.0, "45"))
        assertFalse("等级制的 0", countsTowardGpa(0.0, "优秀"))
        assertFalse("空绩点", countsTowardGpa(null, "90"))
    }

    // ───────────── 分类小计 ─────────────

    @Test
    fun `按课程性质分类，学分多的排前面`() {
        val s = summarize(listOf(
            row(kcmc = "高数", cj = "90", jd = "4.0", xf = "5", kcxzmc = "必修课"),
            row(kcmc = "大英", cj = "80", jd = "3.0", xf = "4", kcxzmc = "必修课"),
            row(kcmc = "服装史", cj = "88", jd = "3.6", xf = "2", kcxzmc = "选修课"),
            row(kcmc = "某通识", cj = "通过", jd = "0", xf = "1", kcxzmc = "公共选修"),
        ))
        assertEquals(3, s.byCategory.size)
        assertEquals("必修课", s.byCategory[0].name)          // 9 学分最多
        assertEquals(9.0, s.byCategory[0].credits, 1e-9)
        assertEquals(2, s.byCategory[0].count)
        // (4.0×5 + 3.0×4) / 9 = 32/9
        assertEquals(32.0 / 9.0, s.byCategory[0].gpa!!, 1e-9)
        assertEquals("选修课", s.byCategory[1].name)          // 2 学分
        assertEquals("公共选修", s.byCategory[2].name)        // 1 学分，且 GPA 为空
        assertNull(s.byCategory[2].gpa)
        assertEquals(12.0, s.credits, 1e-9)
    }

    @Test
    fun `课程性质为空归到其他`() {
        val s = summarize(listOf(row(kcxzmc = "")))
        assertEquals("其他", s.byCategory.single().name)
    }

    // ───────────── 显示格式 ─────────────

    @Test
    fun `学分不带多余的小数点`() {
        assertEquals("28", fmtCredit(28.0))
        assertEquals("28.5", fmtCredit(28.5))
        assertEquals("0.5", fmtCredit(0.5))
    }
}
