package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 纯逻辑单元测试：周次解析、编辑层、导入导出。
 * 这些代码不碰 Android API，所以能直接在 JVM 上跑（./gradlew testDebugUnitTest）。
 */
class LogicTest {

    private val term = Term(
        xnm = "2026", xnmc = "2026-2027", xqm = "3",
        startDate = LocalDate.parse("2026-08-31"),
        endDate = LocalDate.parse("2027-01-10"),
        maxWeek = 19,
    )

    private fun course(
        name: String = "数字化服装定制", teacher: String = "魏老师", room: String = "9A306-307",
        day: Int = 1, s: Int = 5, e: Int = 8, weeks: Set<Int> = setOf(1, 2),
    ) = Course(name, teacher, room, "南校区", day, s, e, WeekUtils.formatWeeks(weeks), weeks)

    // ───────────── 节次 ─────────────

    @Test
    fun `共 10 节课，9~10 节在晚上`() {
        assertEquals(10, Slots.ALL.size)
        assertEquals(10, Slots.COUNT)
        assertEquals("18:30", Slots.of(9)!!.begin)
        assertEquals("20:05", Slots.of(10)!!.end)
        assertTrue(Slots.nowSlot(java.time.LocalTime.parse("19:10")) == 9)
        assertTrue(Slots.byTime(java.time.LocalTime.parse("14:00")) == 5)
    }

    // ───────────── 周次 ─────────────

    @Test
    fun `周次解析与回写`() {
        assertEquals(setOf(1, 2, 5, 6, 7), WeekUtils.parseWeeks("1-2周,5-7周"))
        assertEquals("1-2,5-7周", WeekUtils.formatWeeks(setOf(1, 2, 5, 6, 7)))
        assertEquals(setOf(1, 3, 5), WeekUtils.parseWeeks("1-5周(单)"))
        assertEquals(setOf(2, 4), WeekUtils.parseWeeks("2-4周(双)"))
        assertEquals(6, WeekUtils.weekOf(term, LocalDate.parse("2026-10-10")))
        assertEquals(LocalDate.parse("2026-10-05"), WeekUtils.dateOf(term, 6, 1))
    }

    // ───────────── 编辑层 ─────────────

    @Test
    fun `重命名作用于该课所有周且定位不乱`() {
        val base = listOf(course(weeks = setOf(1, 2)), course(name = "功能及智能服装设计", day = 2, s = 7, e = 8, weeks = setOf(1, 2)))
        val layer = EditLayer(listOf(EditOp(EditKind.RENAME, base[0].key, name = "服定")))
        val out = layer.apply(base)
        assertEquals(2, out.size)
        assertTrue(out.all { it.name == "服定" || it.name == "功能及智能服装设计" })
        val renamed = out.first { it.name == "服定" }
        assertEquals(base[0].key, renamed.key)          // key 没变 → 还能继续编辑
        assertEquals(2, renamed.weeks.size)             // 两周都改名

        // 改名后再叠加一次移除，仍能定位到这门课
        val layer2 = layer.plus(EditOp(EditKind.REMOVE_SEM, base[0].key))
        assertEquals(1, layer2.apply(base).size)
    }

    @Test
    fun `从本周移除只影响这一周`() {
        val c = course(weeks = setOf(1, 2, 3))
        val layer = EditLayer(listOf(EditOp(EditKind.REMOVE_THIS, c.key, week = 2, day = 1, start = 5, end = 8)))
        val out = layer.apply(listOf(c))
        assertEquals(1, out.size)
        assertEquals(setOf(1, 3), out[0].weeks)          // 只少了第 2 周
        assertFalse(2 in out[0].weeks)
    }

    @Test
    fun `从本周移除这门课会清掉该周所有节次`() {
        val a = course(weeks = setOf(1, 2), day = 1, s = 5, e = 8)
        val b = course(weeks = setOf(1, 2), day = 5, s = 5, e = 8, room = "7B401")
        val layer = EditLayer(listOf(EditOp(EditKind.REMOVE_WEEK_ALL, a.key, week = 1)))
        val out = layer.apply(listOf(a, b))
        assertEquals(2, out.size)
        assertTrue(out.all { it.weeks == setOf(2) })
    }

    @Test
    fun `从本学期移除清掉全部`() {
        val a = course(weeks = setOf(1, 2))
        val b = course(name = "功能及智能服装设计", day = 2, s = 7, e = 8, weeks = setOf(1, 2))
        val out = EditLayer(listOf(EditOp(EditKind.REMOVE_SEM, a.key))).apply(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals("功能及智能服装设计", out[0].name)
    }

    @Test
    fun `只改这一次不影响其他周`() {
        val c = course(weeks = setOf(1, 2, 3), day = 1, s = 5, e = 8)
        val layer = EditLayer(listOf(EditOp(
            EditKind.MOVE_ONCE, c.key, week = 2, day = 1, start = 5, end = 8, toDay = 3, toStart = 9,
        )))
        val out = layer.apply(listOf(c))
        assertEquals(2, out.size)
        val moved = out.first { it.dayOfWeek == 3 }
        assertEquals(setOf(2), moved.weeks)
        assertEquals(9, moved.startSlot)
        assertEquals(12, moved.endSlot)          // 保持 4 节跨度
        assertEquals(setOf(1, 3), out.first { it.dayOfWeek == 1 }.weeks)
    }

    @Test
    fun `整学期调时间所有周都动`() {
        val c = course(weeks = setOf(1, 2, 3), day = 1, s = 5, e = 6)
        val layer = EditLayer(listOf(EditOp(EditKind.MOVE_ALL, c.key, toDay = 4, toStart = 9)))
        val out = layer.apply(listOf(c))
        assertEquals(1, out.size)
        assertEquals(4, out[0].dayOfWeek)
        assertEquals(9, out[0].startSlot)
        assertEquals(10, out[0].endSlot)
        assertEquals(setOf(1, 2, 3), out[0].weeks)
    }

    @Test
    fun `编辑层能存档再读回`() {
        val layer = EditLayer(listOf(
            EditOp(EditKind.RENAME, "a|t", name = "新名"),
            EditOp(EditKind.MOVE_ONCE, "a|t", week = 3, day = 1, start = 5, end = 8, toDay = 2, toStart = 9),
        ))
        val back = EditLayer.fromJson(layer.toJson())
        assertEquals(layer.toJson(), back.toJson())
        assertEquals(2, back.ops.size)
        assertEquals("新名", back.renamedKey("a|t"))
    }

    // ───────────── 导入 / 导出 ─────────────

    @Test
    fun `CSV 示例文件能解析`() {
        val tt = TimetableCodec.parse(TimetableCodec.TEMPLATE_CSV, term).tt
        assertEquals(4, tt.courses.size)
        val first = tt.courses[0]
        assertEquals("数字化服装定制", first.name)
        assertEquals(1, first.dayOfWeek)
        assertEquals(5, first.startSlot)
        assertEquals(8, first.endSlot)
        assertEquals(setOf(1, 2), first.weeks)
        val evening = tt.courses.first { it.name == "晚自习" }
        assertEquals(9, evening.startSlot)
        assertEquals(10, evening.endSlot)
        assertEquals(19, evening.weeks.size)
        assertEquals(Source.IMPORTED, tt.source)
    }

    @Test
    fun `CSV 缺列会跳过并给出提示`() {
        val csv = """
            课程,教师,教室,校区,星期,开始节,结束节,周次
            正常课,王老师,9A101,南校区,二,1,2,1-4周
            坏数据,李老师,,,火星,一,一,不知道
        """.trimIndent()
        val r = TimetableCodec.parse(csv, term)
        assertEquals(1, r.tt.courses.size)
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun `CSV 导出再导入结果一致`() {
        val src = Timetable(term, Student(), listOf(
            course(weeks = setOf(1, 2, 5)),
            course(name = "功能及智能服装设计", teacher = "王老师", day = 2, s = 7, e = 8, weeks = setOf(3, 4)),
        ))
        val back = TimetableCodec.parse(TimetableCodec.toCsv(src), term).tt
        assertEquals(src.courses.size, back.courses.size)
        src.courses.forEach { c ->
            val m = back.courses.first { it.name == c.name && it.dayOfWeek == c.dayOfWeek }
            assertEquals(c.startSlot, m.startSlot)
            assertEquals(c.endSlot, m.endSlot)
            assertEquals(c.weeks, m.weeks)
        }
    }

    @Test
    fun `JSON 导出再导入无损`() {
        val src = Timetable(term, Student("张三", "202300000000", "软件工程2341", "软件工程"), listOf(
            course(weeks = setOf(1, 2, 5, 6)),
        ))
        val back = TimetableCodec.fromJson(TimetableCodec.toJson(src))
        assertEquals(src.courses, back.courses)
        assertEquals("张三", back.student.name)
        assertEquals(LocalDate.parse("2026-08-31"), back.term.startDate)
        assertEquals(19, back.term.maxWeek)
    }

    @Test
    fun `ICS 示例文件能解析`() {
        val tt = TimetableCodec.parse(TimetableCodec.TEMPLATE_ICS, term).tt
        assertEquals(1, tt.courses.size)
        val c = tt.courses[0]
        assertEquals("数字化服装定制", c.name)
        assertEquals(1, c.dayOfWeek)                 // BYDAY=MO
        assertEquals(5, c.startSlot)                 // 14:00 → 第 5 节
        assertEquals(8, c.endSlot)                   // 17:30 → 第 8 节
        assertEquals(setOf(1, 2), c.weeks)
        assertEquals("魏老师", c.teacher)
        assertEquals("南校区", c.campus)
        assertEquals("9A306-307", c.room)
    }

    @Test
    fun `ICS 周次断档也能往返`() {
        val src = Timetable(term, Student(), listOf(
            course(weeks = setOf(1, 2, 5, 7), day = 3, s = 9, e = 10, room = "7C306"),
            course(name = "功能及智能服装设计", teacher = "王老师", day = 2, s = 7, e = 8, weeks = setOf(1, 2)),
        ))
        val back = TimetableCodec.fromIcs(TimetableCodec.toIcs(src), term)
        assertEquals(src.courses.size, back.courses.size)
        src.courses.forEach { c ->
            val m = back.courses.first { it.name == c.name }
            assertEquals(c.dayOfWeek, m.dayOfWeek)
            assertEquals(c.startSlot, m.startSlot)
            assertEquals(c.endSlot, m.endSlot)
            assertEquals(c.weeks, m.weeks)
        }
    }

    @Test
    fun `没有学期开始日期时也能导入并给出提示`() {
        val noTerm = Term(maxWeek = 30)
        val r = TimetableCodec.parse(TimetableCodec.TEMPLATE_ICS, noTerm)
        assertEquals(1, r.tt.courses.size)
        assertNotNull(r.tt.term.startDate)
        assertEquals(setOf(1, 2), r.tt.courses[0].weeks)
    }

    @Test
    fun `三种格式能自动识别`() {
        assertTrue(TimetableCodec.parse(TimetableCodec.TEMPLATE_ICS, term).tt.courses.isNotEmpty())
        assertTrue(TimetableCodec.parse(TimetableCodec.TEMPLATE_CSV, term).tt.courses.isNotEmpty())
        val json = TimetableCodec.toJson(Timetable(term, Student(), listOf(course())))
        assertTrue(TimetableCodec.parse(json, term).tt.courses.isNotEmpty())
    }

    @Test
    fun `空文件和垃圾内容会明确报错`() {
        val e1 = runCatching { TimetableCodec.parse("", term) }.exceptionOrNull()
        assertTrue(e1 is TimetableCodec.ImportException)
        val e2 = runCatching { TimetableCodec.parse("hello world\n随便写点啥", term) }.exceptionOrNull()
        assertTrue(e2 is TimetableCodec.ImportException)
    }

    // ---- 直达页：菜单解析（用学校真实写法，见 menu.txt 诊断）----

    @Test
    fun `菜单地址缺 gnmkdm 时从功能号补上`() {
        // 学校首页的菜单是点的时候才把功能号拼上去的；直接打开原始地址会进「系统升级维护中」
        val html = """
            <li><a onclick="clickMenu('N305005','/cjcx/cjcx_cxDgXscj.html','学生成绩查询','')">学生成绩查询</a></li>
            <li><a onclick="clickMenu('N253512','/xsxk/zzxkyzb_cxZzxkYzbIndex.html','自主选课','')">自主选课</a></li>
            <li><a onclick="clickMenu('N358105','/kwgl/kscx_cxXsksxxIndex.html?doType=query','考试信息查询','')">考试信息查询</a></li>
        """.trimIndent()
        val m = JwApi.parseMenus(html)
        assertEquals("/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005", m["学生成绩查询"])
        assertEquals("/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512", m["自主选课"])
        // 已经带 ? 的要用 & 接，不能拼出两个 ?
        assertEquals("/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105", m["考试信息查询"])
    }

    @Test
    fun swipeWeek_向左滑下一周_向右滑上一周() {
        assertEquals(6, swipeWeek(5, -80f, 56f, 20))
        assertEquals(4, swipeWeek(5, 80f, 56f, 20))
    }

    @Test
    fun swipeWeek_幅度不够不动() {
        assertEquals(5, swipeWeek(5, -30f, 56f, 20))
        assertEquals(5, swipeWeek(5, 30f, 56f, 20))
    }

    @Test
    fun swipeWeek_首周与末周不被滑出去() {
        assertEquals(1, swipeWeek(1, 80f, 56f, 20))
        assertEquals(20, swipeWeek(20, -80f, 56f, 20))
    }

    @Test
    fun mergeAccounts_按最近使用排序_多出的挂后面() {
        assertEquals(listOf("b", "a", "c"), mergeAccounts(listOf("b", "a"), listOf("a", "c", "b")))
        assertEquals(listOf("x"), mergeAccounts(listOf("x"), listOf("x")))
        assertEquals(listOf("s1"), mergeAccounts(emptyList(), listOf("s1")))
    }

    @Test
    fun scopedKey_不同账号的课表键必须不同() {
        assertTrue(scopedKey("tt", "202300000001") != scopedKey("tt", "202200000000"))
        assertEquals("tt@202300000001", scopedKey("tt", "202300000001"))
        assertEquals("edits@202300000001", scopedKey("edits", "202300000001"))
    }

    @Test
    fun scopedKey_未登录时用占位符不与其他账号相撞() {
        assertEquals("tt@_default", scopedKey("tt", ""))
        assertEquals("tt@_default", scopedKey("tt", "   "))
    }

    @Test
    fun 账号私有数据的每一种键都必须按账号隔离() {
        // 曾经漏掉 imported：A 导入的课表会串到 B 名下。清单钉在这里，以后加新键就不会忘。
        assertTrue(ACCOUNT_SCOPED_KEYS.contains("imported"))
        for (base in ACCOUNT_SCOPED_KEYS) {
            val a = scopedKey(base, "202300000001")
            val b = scopedKey(base, "202200000000")
            assertNotEquals(a, b)
            assertTrue(a.startsWith("$base@"))
        }
    }

}
