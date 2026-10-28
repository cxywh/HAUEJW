package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 用电历史与「够用约 N 天」的推算 */
class CardUsageTest {

    private val day = 24L * 3600_000L
    private val t0 = 1_793_000_000_000L      // 固定基准时刻，避免依赖系统时间

    private fun h(vararg pairs: Pair<Long, Double>): String =
        pairs.joinToString(";") { (at, used) -> "$at:$used" }

    @Test
    fun `同一天只留最后一条`() {
        var s = CardWatch.pushHistory("", 100.0, t0)
        s = CardWatch.pushHistory(s, 101.0, t0 + 3600_000L)
        s = CardWatch.pushHistory(s, 102.0, t0 + 5 * 3600_000L)
        val list = CardWatch.decodeHistory(s)
        assertEquals(1, list.size)
        assertEquals(102.0, list[0].used, 0.001)
    }

    @Test
    fun `超过十四天的样本被清掉`() {
        var s = CardWatch.pushHistory("", 50.0, t0 - 20 * day)
        assertEquals(1, CardWatch.decodeHistory(s).size)
        s = CardWatch.pushHistory(s, 60.0, t0)
        val list = CardWatch.decodeHistory(s)
        assertEquals(1, list.size)
        assertEquals(60.0, list[0].used, 0.001)
    }

    @Test
    fun `used 为 0 的读数不进历史`() {
        // 服务端没给 usedAmp 时是 0，记进去会把日均压成 0
        assertEquals("", CardWatch.pushHistory("", 0.0, t0))
    }

    @Test
    fun `七天十四度算成两天一度`() {
        val s = h(t0 to 100.0, t0 + 7 * day to 114.0)
        assertEquals(2.0, CardWatch.dailyUse(s, t0 + 7 * day)!!, 0.0001)
    }

    @Test
    fun `窗口外的老样本不参与`() {
        // 20 天前那条已被视为过期，只剩最近 7 天内的两条
        val s = h(t0 - 20 * day to 1.0, t0 to 100.0, t0 + 7 * day to 114.0)
        assertEquals(2.0, CardWatch.dailyUse(s, t0 + 7 * day)!!, 0.0001)
    }

    @Test
    fun `跨度不足一天不给日均`() {
        val s = h(t0 to 100.0, t0 + 6 * 3600_000L to 101.0)
        assertNull(CardWatch.dailyUse(s, t0 + 6 * 3600_000L))
        assertNull(CardWatch.daysLeft(87.05, s, t0 + 6 * 3600_000L))
    }

    @Test
    fun `只有一天记录时按实际跨度算并标出一天`() {
        val s = h(t0 to 100.0, t0 + day to 104.0)
        val u = CardWatch.usage(s, t0 + day)!!
        assertEquals(4.0, u.perDay, 0.0001)
        assertEquals(1, u.days)
        assertEquals(21, CardWatch.daysLeft(87.05, s, t0 + day))   // 87.05 / 4 = 21.7
    }

    @Test
    fun `满七天后标出七天`() {
        val s = h(t0 to 100.0, t0 + 7 * day to 114.0)
        assertEquals(7, CardWatch.usage(s, t0 + 7 * day)!!.days)
    }

    @Test
    fun `读数倒退当作换表不给估算`() {
        val s = h(t0 to 200.0, t0 + 7 * day to 190.0)
        assertNull(CardWatch.dailyUse(s, t0 + 7 * day))
    }

    @Test
    fun `余额除以日均得到天数`() {
        val s = h(t0 to 100.0, t0 + 7 * day to 114.0)
        assertEquals(43, CardWatch.daysLeft(87.05, s, t0 + 7 * day))   // 87.05 / 2.0 = 43.5
        assertEquals(0, CardWatch.daysLeft(0.4, s, t0 + 7 * day))
    }

    @Test
    fun `坏数据不影响解析`() {
        val s = "abc;123:4.5;;99"
        val list = CardWatch.decodeHistory(s)
        assertEquals(1, list.size)
        assertEquals(4.5, list[0].used, 0.0001)
    }

    @Test
    fun `小组件有天数时显示够用约几天`() {
        val cache = CardWatch.encode("剩余电量", 87.05, "度", t0)
        assertEquals("电费 87.05度 · 够用约43天", feeLine(cache, true, 43))
    }

    @Test
    fun `没有天数时退回查询时刻`() {
        val cache = CardWatch.encode("剩余电量", 87.05, "度", t0)
        val text = feeLine(cache, true, null)
        assertEquals(true, text.startsWith("电费 87.05度 · "))
        assertEquals(1, Regex(""":\d\d$""").findAll(text).count())
    }

    @Test
    fun `没缓存时按登录状态给字样`() {
        assertEquals("电费 未查询", feeLine("", true, null))
        assertEquals("电费 未登录", feeLine("", false, null))
        assertNotNull(feeLine("", true, null))
    }

    @Test
    fun `真实返回里能取到累计用电量与余额`() {
        // 2026-10-28 14:01 真机实测的响应体
        val json = """{"msg":"success","code":200,"map":{"showData":{"剩余电量(度)":"87.05"},""" +
            """"data":{"room_id":"11273","allAmp":"7056.25","xiaoqu_id":"1",""" +
            """"usedAmp":"6969.2001953125","loudong_id":"505","louceng_id":"612"},""" +
            """"surplusCharge":"87.05"}}"""
        val a = CardFee.showOf(json)!!
        assertEquals("剩余电量", a.label)
        assertEquals(87.05, a.value, 0.0001)
        assertEquals("度", a.unit)
        assertEquals(6969.2001953125, a.used, 0.0001)
    }
}
