package com.cxywh.hauejw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园卡接口返回的解析。
 *
 * 这几条都是纯字符串处理（不碰 Context、不联网），返回样本按前端代码里的字段拼出来：
 * 应用列表里 website 带 showBal=<费项编号>，余额是 map.showData["信息"] 里的一句话。
 */
class FeeParseTest {

    @Test
    fun items_findsShowBalAndAppName() {
        val json = """
            {"code":200,"success":true,"data":[
              {"name":"生活缴费","appList":[
                {"appName":"电费","appCode":"elc","website":"http://card/x?showBal=1301&t=1"},
                {"appName":"网费","appCode":"net","website":"http://card/y?id=9"}
              ]}
            ]}
        """.trimIndent()
        val list = CardFee.itemsOf(json)
        assertEquals(1, list.size)
        assertEquals("1301", list[0].id)
        assertEquals("电费", list[0].name)
    }

    @Test
    fun items_feeitemidForm() {
        // 本校实际写法：/charge/feeitem/toAppitem?feeitemid=408
        val json = """{"appName":"电费","website":"/charge/feeitem/toAppitem?feeitemid=408"}"""
        assertEquals("408", CardFee.itemsOf(json).single().id)
        assertEquals("电费", CardFee.itemsOf(json).single().name)
    }

    @Test
    fun items_ignoresOtherQueryParams() {
        val json = """{"appName":"账单","website":"/campus-card/?name=billList&amp;id=9"}"""
        assertTrue(CardFee.itemsOf(json).isEmpty())
    }

    @Test
    fun items_handlesUrlEncodedEquals() {
        val json = """{"appName":"水费","website":"http://card/x?showBal%3D2077"}"""
        assertEquals("水费", CardFee.itemsOf(json).single().name)
        assertEquals("2077", CardFee.itemsOf(json).single().id)
    }

    @Test
    fun items_prefersAppNameOverGroupName() {
        val json = """{"name":"全部应用","appName":"电费","website":"?showBal=88"}"""
        assertEquals("电费", CardFee.itemsOf(json).single().name)
    }

    @Test
    fun items_ignoresAppsWithoutShowBal() {
        val json = """{"appList":[{"appName":"网费","website":"?id=9"},{"appName":"缴费","website":"?x=1"}]}"""
        assertTrue(CardFee.itemsOf(json).isEmpty())
    }

    @Test
    fun items_dedupesSameId() {
        val json = """[{"appName":"电费","website":"?showBal=5"},{"appName":"电费2","website":"?showBal=5"}]"""
        assertEquals(1, CardFee.itemsOf(json).size)
        assertEquals("电费", CardFee.itemsOf(json).single().name)
    }

    @Test
    fun items_fallsBackToIdWhenNoName() {
        val json = """{"website":"?showBal=777"}"""
        assertEquals("费项 777", CardFee.itemsOf(json).single().name)
    }

    @Test
    fun amount_electricity() {
        val json = """{"code":200,"map":{"showData":{"信息":"剩余电量:12.34度"}}}"""
        val a = CardFee.amount(json)!!
        assertEquals("剩余电量", a.label)
        assertEquals(12.34, a.value, 0.0001)
        assertEquals("度", a.unit)
    }

    @Test
    fun amount_money() {
        val json = """{"map":{"showData":{"信息":"剩余金额：50.00"}}}"""
        val a = CardFee.amount(json)!!
        assertEquals("剩余金额", a.label)
        assertEquals(50.0, a.value, 0.0001)
        assertEquals("元", a.unit)
    }

    @Test
    fun amount_water() {
        val json = """{"map":{"showData":{"信息":"剩余水费: 3.5"}}}"""
        val a = CardFee.amount(json)!!
        assertEquals("剩余水费", a.label)
        assertEquals(3.5, a.value, 0.0001)
        assertEquals("元", a.unit)
    }

    @Test
    fun amount_negative() {
        val json = """{"信息":"剩余金额:-12.5"}"""
        assertEquals(-12.5, CardFee.amount(json)!!.value, 0.0001)
    }

    @Test
    fun amount_escapedUnicode() {
        // 服务端若把中文转成 \uXXXX 转义，也要能认
        val json = "{\"map\":{\"showData\":{\"\\u4fe1\\u606f\":\"\\u5269\\u4f59\\u7535\\u91cf:6.7\"}}}"
        val a = CardFee.amount(json)!!
        assertEquals("剩余电量", a.label)
        assertEquals(6.7, a.value, 0.0001)
    }

    @Test
    fun amount_textWithoutLabel() {
        val json = """{"map":{"showData":{"信息":"暂未查询到余额"}}}"""
        assertNull(CardFee.amount(json))
    }

    @Test
    fun unescape_codes() {
        assertEquals("剩余", CardFee.unescape("""\u5269\u4f59"""))
        assertEquals("""a"b""", CardFee.unescape("""a\"b"""))
        assertEquals("正常", CardFee.unescape("正常"))
    }

    @Test
    fun parseToken_full() {
        val json = """{"access_token":"abc.def","token_type":"bearer","expires_in":7200,"refresh_token":"rt1"}"""
        val a = CardFee.parseToken(json)
        assertNotNull(a)
        assertEquals("abc.def", a!!.token)
        assertEquals("rt1", a.refresh)
        assertTrue(a.expiresAt > System.currentTimeMillis())
        assertTrue(a.alive())
    }

    @Test
    fun parseToken_withoutExpiry() {
        val json = """{"access_token":"t","refresh_token":""}"""
        val a = CardFee.parseToken(json)!!
        assertEquals(0L, a.expiresAt)
        assertTrue(a.alive())
    }

    @Test
    fun parseToken_missing() {
        assertNull(CardFee.parseToken("""{"error":"invalid_grant"}"""))
    }

    @Test
    fun auth_expiredIsNotAlive() {
        val a = CardFee.Auth("t", "r", System.currentTimeMillis() - 1000)
        assertTrue(!a.alive())
        assertTrue(!CardFee.Auth("", "", 0L).alive())
    }

    @Test
    fun stepOf_readsCodesInLevelOrder() {
        val json = """{"map":{"total":[{"code":"xiaoqu_id","level":1,"name":"校区"},{"code":"loudong_id","level":2,"name":"楼栋"}],
            "data":[{"name":"河南工程学院","value":"1&河南工程学院"},{"name":"某校区某号楼","value":"505&某校区某号楼"}]}}"""
        val st = CardFee.stepOf(json)
        assertEquals(listOf("xiaoqu_id", "loudong_id"), st.codes)
        assertEquals(2, st.opts.size)
        assertEquals("505&某校区某号楼", st.opts[1].value)
    }

    @Test
    fun stepOf_emptyDataGivesNoOptions() {
        val json = """{"map":{"total":[{"code":"room_id","level":4,"name":"房间"}],"data":[]}}"""
        assertTrue(CardFee.stepOf(json).opts.isEmpty())
        assertEquals(listOf("room_id"), CardFee.stepOf(json).codes)
    }

    @Test
    fun showOf_readsSurplusElectricity() {
        val json = """{"msg":"success","code":200,"map":{"showData":{"剩余电量(度)":"87.81"},"surplusCharge":"87.81"}}"""
        val a = CardFee.showOf(json)!!
        assertEquals("剩余电量", a.label)
        assertEquals(87.81, a.value, 0.001)
        assertEquals("度", a.unit)
    }

    @Test
    fun showOf_readsMoney() {
        val json = """{"map":{"showData":{"剩余金额(元)":"12.50"}}}"""
        assertEquals("元", CardFee.showOf(json)!!.unit)
        assertEquals(12.5, CardFee.showOf(json)!!.value, 0.001)
    }

    @Test
    fun showOf_nullWhenNoNumber() {
        assertNull(CardFee.showOf("""{"map":{"tipinfo":"未绑定房间信息！"}}"""))
    }
}
