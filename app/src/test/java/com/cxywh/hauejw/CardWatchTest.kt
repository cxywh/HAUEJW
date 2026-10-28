package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 定时查询的时刻计算与缓存编解码 */
class CardWatchTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun local(ms: Long): LocalDateTime =
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test
    fun nextAt_当天还没到点就排当天() {
        val now = at(2026, 10, 28, 6, 30)
        val next = local(CardWatch.nextAt(8, 0, now, zone))
        assertEquals(LocalDateTime.of(2026, 10, 28, 8, 0), next)
    }

    @Test
    fun nextAt_已经过了就排次日() {
        val now = at(2026, 10, 28, 9, 0)
        val next = local(CardWatch.nextAt(8, 0, now, zone))
        assertEquals(LocalDateTime.of(2026, 10, 29, 8, 0), next)
    }

    @Test
    fun nextAt_设到深夜顺延到次日凌晨() {
        // 23:30 落在 23:00–03:00，学校系统不开放查询
        val now = at(2026, 10, 28, 10, 0)
        val next = local(CardWatch.nextAt(23, 30, now, zone))
        assertEquals(LocalDateTime.of(2026, 10, 29, 3, 5), next)
    }

    @Test
    fun nextAt_设到凌晨也顺延到当天凌晨三点五分() {
        val now = at(2026, 10, 28, 1, 0)
        val next = local(CardWatch.nextAt(2, 0, now, zone))
        assertEquals(LocalDateTime.of(2026, 10, 28, 3, 5), next)
    }

    @Test
    fun nextAt_边界三点整不再顺延() {
        val now = at(2026, 10, 28, 1, 0)
        val next = local(CardWatch.nextAt(3, 0, now, zone))
        assertEquals(LocalDateTime.of(2026, 10, 28, 3, 0), next)
    }

    @Test
    fun cache_编解码往返() {
        val raw = CardWatch.encode("剩余电量", 87.81, "度", 1793000000000L)
        val c = CardWatch.decode(raw)!!
        assertEquals("剩余电量", c.label)
        assertEquals(87.81, c.value, 0.001)
        assertEquals("度", c.unit)
        assertEquals(1793000000000L, c.at)
    }

    @Test
    fun cache_标签里的竖线被清掉不破坏格式() {
        val raw = CardWatch.encode("剩余|电量", 1.0, "元", 1L)
        val c = CardWatch.decode(raw)!!
        assertEquals("剩余电量", c.label)
        assertEquals(1.0, c.value, 0.001)
    }

    @Test
    fun cache_坏数据返回空() {
        assertNull(CardWatch.decode(""))
        assertNull(CardWatch.decode("只有一段"))
        assertNull(CardWatch.decode("a|b|c|d"))
        assertNull(CardWatch.decode("a|1|度|notanumber"))
    }

    @Test
    fun nextAt_时刻总在未来且不在静默段() {
        val now = at(2026, 10, 28, 12, 0)
        for (h in 0..23) {
            val next = local(CardWatch.nextAt(h, 0, now, zone))
            assertTrue("h=$h 排到了过去", next.atZone(zone).toInstant().toEpochMilli() > now)
            val quiet = next.hour >= 23 || next.hour < 3
            assertTrue("h=$h 仍在静默段 $next", !quiet)
        }
    }

    @Test
    fun wipeCard_只清本账号的校园卡键() {
        val all = listOf(
            "card_room@A", "card_cache@A", "card_room_name@A", "card_auto_on@A",
            "card_warn@B", "tt@A", "username", "edits@A",
        )
        assertEquals(
            listOf("card_auto_on@A", "card_cache@A", "card_room@A", "card_room_name@A"),
            cardKeysToWipe(all, "A"),
        )
    }

    @Test
    fun wipeCard_空账号不动任何键() {
        assertEquals(emptyList<String>(), cardKeysToWipe(listOf("card_room@A"), ""))
    }

    @Test
    fun reqOf_每个学号各自一个请求码() {
        val a = CardWatch.reqOf("202300000001")
        val b = CardWatch.reqOf("202300000002")
        assertEquals(a, CardWatch.reqOf("202300000001"))
        assertTrue("两个学号撞了请求码", a != b)
        assertTrue("请求码不应与提醒的 71xx 重叠", a > 0x73000000)
    }

    @Test
    fun 两个学号的电费数据互不相干() {
        assertTrue(scopedKey("card_cache", "202300000001") != scopedKey("card_cache", "202300000002"))
        assertTrue(scopedKey("card_room", "202300000001") != scopedKey("card_room", "202300000002"))
        assertEquals("card_room@202300000001", scopedKey("card_room", "202300000001"))
    }

    @Test
    fun 补查_过了预定时刻十分钟才补() {
        val next = at(2026, 10, 28, 8, 0)
        assertTrue(CardWatch.shouldCatchUp(true, next, next + 20 * 60_000L, 10))
        assertTrue(!CardWatch.shouldCatchUp(true, next, next + 5 * 60_000L, 10))
        assertTrue(!CardWatch.shouldCatchUp(false, next, next + 20 * 60_000L, 10))
        assertTrue(!CardWatch.shouldCatchUp(true, 0L, next + 20 * 60_000L, 10))
    }

    @Test
    fun 补查_禁查时段不补() {
        val next = at(2026, 10, 28, 8, 0)
        val now = next + 5L * 3600_000L     // 13:00
        assertTrue(!CardWatch.shouldCatchUp(true, next, now, 23))
        assertTrue(!CardWatch.shouldCatchUp(true, next, now, 0))
        assertTrue(!CardWatch.shouldCatchUp(true, next, now, 2))
        assertTrue(CardWatch.shouldCatchUp(true, next, now, 3))
        assertTrue(CardWatch.shouldCatchUp(true, next, now, 22))
    }
}
