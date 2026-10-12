package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 回声洞的解析、故障帧、挑选顺序。
 * 这些都不碰 Android API（读写文件那部分单独用 Context，不在测试范围内），能在 JVM 上直接跑。
 */
class EchoTest {

    // ───────────── 解析：中英文标点都认 ─────────────

    @Test
    fun `半角引号与分号`() {
        assertEquals(listOf("甲", "乙"), Echo.parse("\"甲\";\"乙\";"))
    }

    @Test
    fun `全角引号与分号`() {
        assertEquals(listOf("甲", "乙"), Echo.parse("“甲”；“乙”；"))
    }

    @Test
    fun `直角引号和三种混用`() {
        assertEquals(listOf("甲", "乙", "丙"), Echo.parse("「甲」;『乙』；“丙”"))
    }

    @Test
    fun `引号写反也认`() {
        assertEquals(listOf("晚点睡"), Echo.parse("”晚点睡”；"))
        assertEquals(listOf("晚点睡"), Echo.parse("“晚点睡“；"))
    }

    @Test
    fun `整句没有引号就整句照显`() {
        assertEquals(listOf("今天也要加油", "晚安"), Echo.parse("今天也要加油;晚安"))
    }

    @Test
    fun `换行也断句`() {
        assertEquals(listOf("甲", "乙"), Echo.parse("\"甲\"\n\"乙\""))
    }

    @Test
    fun `引号里的分号不算断句`() {
        assertEquals(listOf("睡吧；别熬了"), Echo.parse("\"睡吧；别熬了\";"))
    }

    @Test
    fun `引号里出现别的引号算正文`() {
        assertEquals(listOf("他说“好”"), Echo.parse("\"他说“好”\""))
    }

    @Test
    fun `颜文字与 emoji 原样保留`() {
        val s = "(╯°□°）╯︵ ┻━┻  ☕🚀 (๑•̀ㅂ•́)و✧"
        assertEquals(listOf(s), Echo.parse("\"$s\";"))
    }

    @Test
    fun `空段丢掉 重复只留一条`() {
        assertEquals(emptyList<String>(), Echo.parse(";;；\n\n"))
        assertEquals(listOf("甲"), Echo.parse("\"甲\";\"甲\";；"))
    }

    @Test
    fun `引号没闭合也照样取出内容`() {
        assertEquals(listOf("半句话"), Echo.parse("\"半句话"))
    }

    // ───────────── 故障帧 ─────────────

    @Test
    fun `四帧且最后一帧是原文`() {
        val t = "别熬了，明天第一节 08:30 🚀"
        val f = Echo.frames(t, Random(7))
        assertEquals(4, f.size)
        assertEquals(t, f[3])
    }

    @Test
    fun `乱码帧不劈开 emoji 且码点数不变`() {
        val t = "早点睡 🌙 好吗"
        val cp = t.codePointCount(0, t.length)
        val g = Echo.glitch(t, Random(1), ratio = 1.0)
        assertEquals(cp, g.codePointCount(0, g.length))
        assertNotEquals(t, g)
    }

    @Test
    fun `色块帧会变长但不出错`() {
        val t = "加载中"
        val g = Echo.glitch(t, Random(2), ratio = 1.0, block = true)
        assertTrue(g.length >= t.length)
    }

    @Test
    fun `空格不被替换成乱码`() {
        val g = Echo.glitch("a b c", Random(3), ratio = 1.0)
        assertEquals(2, g.count { it == ' ' })
    }

    // ───────────── 挑选顺序：尽量不重复 ─────────────

    @Test
    fun `一轮之内不重复`() {
        val texts = (1..8).map { "第 $it 句" }
        var q = emptyList<Int>()
        var p = 0
        var last: Int? = null
        val seen = mutableListOf<Int>()
        repeat(8) { i ->
            val r = Echo.pick(texts.size, q, p, texts, last?.let { texts[it] }, Random(i.toLong()))
            seen += r.index
            last = r.index
            q = r.queue
            p = r.pos
        }
        assertEquals(8, seen.toSet().size)
    }

    @Test
    fun `换一轮时首句不等于上一轮末句`() {
        val texts = (1..5).map { "第 $it 句" }
        var q = emptyList<Int>()
        var p = 0
        var last: Int? = null
        repeat(40) { i ->
            val r = Echo.pick(texts.size, q, p, texts, last?.let { texts[it] }, Random(i.toLong()))
            assertNotEquals(last, r.index)
            last = r.index
            q = r.queue
            p = r.pos
        }
    }

    @Test
    fun `条数变了会重洗`() {
        val texts = (1..3).map { "第 $it 句" }
        // 旧队列里还留着已经不存在的下标（用户删掉了几句）
        val r = Echo.pick(3, listOf(5, 7), 0, texts, null, Random(1))
        assertTrue(r.index in texts.indices)
        assertEquals(1, r.pos)
    }

    @Test
    fun `一句都没有时返回负一`() {
        assertEquals(-1, Echo.pick(0, emptyList(), 0, emptyList(), null, Random(1)).index)
    }

    // ───────────── 换句节奏 ─────────────

    @Test
    fun `整句完整显示之后才停五秒`() {
        // 四帧：三帧乱码各隔 150ms，第四帧才是完整正文
        assertEquals(450L, Echo.shownInMs())
        assertEquals(5_450L, Echo.nextEchoInMs())
        // 只有正片、没有故障帧时，就是显示完直接停 5 秒
        assertEquals(5_000L, Echo.nextEchoInMs(frames = 1))
        // 「停 5 秒」这个量不随帧数变
        assertEquals(5_000L, Echo.nextEchoInMs() - Echo.shownInMs())
    }

    // ───────────── 容量与截断 ─────────────

    @Test
    fun `超长单句会被截断并加省略号`() {
        val long = "字".repeat(Echo.MAX_STATEMENT_CHARS + 500)
        val out = Echo.parse("\"$long\";")
        assertEquals(1, out.size)
        assertEquals(Echo.MAX_STATEMENT_CHARS + 1, out[0].length)   // 截断处那个省略号
        assertTrue(out[0].endsWith("…"))
        assertTrue(out[0].startsWith("字"))
    }

    @Test
    fun `文件读满上限时不会把末字劈成乱码`() {
        // 「你好」的 UTF-8 共 6 字节；砍在第 5 字节上，正好把「好」劈成一半
        val full = "你好".toByteArray(Charsets.UTF_8)
        val cut = full.copyOf(5)
        val trimmed = Echo.trimPartialUtf8(cut)
        assertEquals("你", Echo.decode(trimmed))
        // 正好整字收尾时不动它
        assertEquals("你好", Echo.decode(Echo.trimPartialUtf8("你好".toByteArray(Charsets.UTF_8))))
        // 纯 ASCII 原样返回
        assertEquals("abc", Echo.decode(Echo.trimPartialUtf8("abc".toByteArray(Charsets.UTF_8))))
    }

    // ───────────── 编码 ─────────────

    @Test
    fun `utf8 带 BOM 也能读`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "“你好”".toByteArray(Charsets.UTF_8)
        assertEquals("“你好”", Echo.decode(bytes))
    }

    @Test
    fun `gbk 兜底`() {
        // 「回声洞」的 GBK 编码不是合法 UTF-8，应该落到 GBK 分支解出来
        val gbk = byteArrayOf(
            0xBB.toByte(), 0xD8.toByte(), 0xC9.toByte(),
            0xF9.toByte(), 0xB6.toByte(), 0xB4.toByte(),
        )
        assertEquals("回声洞", Echo.decode(gbk))
    }
}
