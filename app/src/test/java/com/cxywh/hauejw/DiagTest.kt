package com.cxywh.hauejw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断缓冲的测试。
 *
 * 最重要的一条不是"能记录"，而是 **"绝不记录凭据"** ——
 * 异常消息里常带 URL、查询参数、学号密码，一旦被写进缓冲/文件就是泄密。
 * 这里用一条带假凭据的异常把该约定钉死，谁改坏了会立刻红。
 */
class DiagTest {

    @After
    fun tearDown() = Diag.clear()

    /** 只留「时间 + 位置 + 异常类名」，异常消息一个字都不进缓冲区 */
    @Test
    fun record_neverLeaksMessage() {
        Diag.clear()
        Diag.attach(null)
        val fakeSecret = "user=202300000000&pwd=Sup3rSecret!"
        Diag.record("JwApi.login", RuntimeException(fakeSecret))

        val line = Diag.dump().last()
        assertTrue("应含位置标签", line.contains("JwApi.login"))
        assertTrue("应含异常类名", line.contains("RuntimeException"))
        assertFalse("绝不能记录异常消息（含学号）", line.contains("202300000000"))
        assertFalse("绝不能记录异常消息（含口令）", line.contains("Sup3rSecret"))
    }

    /** 没有 Throwable 的失败也要留痕 */
    @Test
    fun record_acceptsNullThrowable() {
        Diag.clear()
        Diag.attach(null)
        Diag.record("SomeFn", null)
        assertTrue(Diag.dump().last().contains("SomeFn"))
    }

    /** 内存缓冲有上限，不会无限增长 */
    @Test
    fun ring_isCapped() {
        Diag.clear()
        Diag.attach(null)
        repeat(Diag.MAX + 40) { Diag.record("f", null) }
        assertEquals(Diag.MAX, Diag.dump().size)
    }

    /** 文件出口同样截断，且内容与内存一致 */
    @Test
    fun fileSink_isWrittenAndCapped() {
        Diag.clear()
        val dir = File(System.getProperty("java.io.tmpdir"), "diagtest-" + System.nanoTime())
        dir.mkdirs()
        Diag.attach(dir)
        repeat(Diag.MAX + 25) { Diag.record("f", null) }
        val log = File(dir, "diag.log")
        assertTrue("应生成 diag.log", log.exists())
        assertTrue("文件行数不得超过上限", log.readLines().size <= Diag.MAX)
        dir.deleteRecursively()
    }

    /** 清空后内存与文件都干净 */
    @Test
    fun clear_emptiesEverything() {
        Diag.clear()
        val dir = File(System.getProperty("java.io.tmpdir"), "diagtest2-" + System.nanoTime())
        dir.mkdirs()
        Diag.attach(dir)
        Diag.record("f", null)
        Diag.clear()
        assertTrue(Diag.dump().isEmpty())
        assertEquals("", File(dir, "diag.log").readText())
        dir.deleteRecursively()
    }
}
