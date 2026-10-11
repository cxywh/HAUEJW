package com.cxywh.hauejw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 一套配色。四个主题共用同一批字段名，界面代码只认字段名，换主题不用改界面。 */
data class Palette(
    val id: String, val title: String, val desc: String,
    val dark: Boolean = false, val glass: Boolean = false,
    val bg: Color, val card: Color, val cardBorder: Color,
    val fg: Color, val fgSub: Color, val dim: Color,
    val line: Color, val lineStrong: Color,
    val accent: Color, val accentTint: Color,
    val selBg: Color, val todayBg: Color,
    val warnBg: Color, val warnFg: Color,
    val okBg: Color, val okFg: Color,
    val danger: Color, val dangerBg: Color,
    /** 「中」分割线（4、5 节之间） */
    val noon: Color,
    /** 「晚」分割线（8、9 节之间） */
    val night: Color,
    /** 玻璃主题的背景光斑 */
    val backdrop: List<Color> = emptyList(),
    val blur: Dp = 0.dp,
)

object Palettes {

    val light = Palette(
        id = "light", title = "亮色", desc = "白底分层灰，白天看最清楚",
        bg = Color(0xFFEFF2F7), card = Color(0xFFFCFDFF), cardBorder = Color(0x00000000),
        fg = Color(0xFF16191F), fgSub = Color(0xFF5C6675), dim = Color(0xFF9AA3B2),
        line = Color(0xFFF2F4F9), lineStrong = Color(0xFFE6EAF2),
        accent = Color(0xFF4F46E5), accentTint = Color(0xFFE7EAFE),
        selBg = Color(0xFFF7F9FE), todayBg = Color(0xFFEEF1FE),
        warnBg = Color(0xFFFFF6E5), warnFg = Color(0xFF96600A),
        okBg = Color(0xFFE8F7EE), okFg = Color(0xFF1B7F4B),
        danger = Color(0xFFD93F3F), dangerBg = Color(0xFFFDEEEE),
        noon = Color(0xFFD98A1F), night = Color(0xFF4F46E5),
    )

    val dark = Palette(
        id = "dark", title = "暗色", desc = "深色分层，卡片浮起来", dark = true,
        bg = Color(0xFF0E1015), card = Color(0xFF171A21), cardBorder = Color(0x1FFFFFFF),
        fg = Color(0xFFEFF1F6), fgSub = Color(0xFFB4BECD), dim = Color(0xFF8C96A6),
        line = Color(0xFF1F232C), lineStrong = Color(0xFF2A3040),
        accent = Color(0xFF8B95FF), accentTint = Color(0xFF232842),
        selBg = Color(0xFF1A1E26), todayBg = Color(0xFF1E2445),
        warnBg = Color(0xFF3A2E14), warnFg = Color(0xFFF0C46E),
        okBg = Color(0xFF14301F), okFg = Color(0xFF6FDC9B),
        danger = Color(0xFFEF6B6B), dangerBg = Color(0xFF3A1D1D),
        noon = Color(0xFFE8A34A), night = Color(0xFF8C9CFF),
    )

    val all = listOf(light, dark)
}

/**
 * 全局当前配色。getter 读的是 Compose 的 MutableState，
 * 所以界面里任何 `C.xxx` 都会在换主题时自动重组，不用到处传参。
 */
object C {
    private var state by mutableStateOf(Palettes.light)

    var palette: Palette
        get() = state
        set(v) { state = v }

    fun byId(id: String): Palette = Palettes.all.firstOrNull { it.id == id } ?: Palettes.light

    val bg get() = state.bg
    val card get() = state.card
    val cardBorder get() = state.cardBorder
    val fg get() = state.fg
    val fgSub get() = state.fgSub
    val dim get() = state.dim
    val line get() = state.line
    val lineStrong get() = state.lineStrong
    val accent get() = state.accent
    val accentTint get() = state.accentTint
    val primary get() = state.accent
    val selBg get() = state.selBg
    val todayBg get() = state.todayBg
    val warnBg get() = state.warnBg
    val warnFg get() = state.warnFg
    val okBg get() = state.okBg
    val okFg get() = state.okFg
    val danger get() = state.danger
    val dangerBg get() = state.dangerBg
    val noon get() = state.noon
    val night get() = state.night

    /**
     * 主色按钮上的文字色。暗色下主色是浅紫，白字对比不足（实测发虚），改用深色字。
     */
    val onAccent get() = if (state.dark) Color(0xFF131A33) else Color.White

    /**
     * 页面底色。玻璃主题下要透明——否则整页的不透明底色会把背景光斑盖掉
     * （踩过：光斑画了、层级也对，就是看不见，因为被页面自己铺的底色盖住了）。
     */
    val screenBg get() = if (state.glass) Color.Transparent else state.bg

    // 兼容旧调用
    val todayCell get() = state.todayBg
    val yellow get() = Color(0xFFFFC400)
    val yellowSoft get() = Color(0xFFFFF3C4)
}

/**
 * 按课程名稳定取色。
 * bar 左侧色条 / fill 卡片底 / ink 卡片上的字。
 * 暗色与玻璃主题下卡片改成半透明深色、字用浅色，保证对比度。
 */
object CourseColors {
    /** bar 色条起点（终点由它加深得来）; from/to 卡片渐变两端; ink 卡上文字 */
    data class Tone(val bar: Color, val from: Color, val to: Color, val ink: Color)

    private val palette = listOf(
        Tone(Color(0xFF4F46E5), Color(0xFFF1F3FE), Color(0xFFE4E8FD), Color(0xFF2E2A7A)),
        Tone(Color(0xFF0F766E), Color(0xFFEDF8F6), Color(0xFFDDF0EC), Color(0xFF11554E)),
        Tone(Color(0xFFB45309), Color(0xFFFDF6EC), Color(0xFFFBEBD6), Color(0xFF7A3A06)),
        Tone(Color(0xFFBE123C), Color(0xFFFDF2F6), Color(0xFFFBE3EC), Color(0xFF831843)),
        Tone(Color(0xFF7E22CE), Color(0xFFF7F0FE), Color(0xFFEFE2FB), Color(0xFF4C1D95)),
        Tone(Color(0xFF15803D), Color(0xFFEDF8F0), Color(0xFFDDF0E4), Color(0xFF14532D)),
        Tone(Color(0xFF1D4ED8), Color(0xFFEEF3FE), Color(0xFFE1EAFD), Color(0xFF1E3A8A)),
        Tone(Color(0xFF0E7490), Color(0xFFEDF7FA), Color(0xFFDEF0F6), Color(0xFF164E63)),
    )

    private fun idx(name: String): Int {
        var h = 0
        for (ch in name) h = (h * 31 + ch.code) and 0x7FFFFFFF
        return h % palette.size
    }

    private fun tone(name: String): Tone = palette[idx(name)]

    /** 左侧色条的渐变两端：亮色主题加深、暗色主题提亮，做出立体感而不是一块死色块 */
    fun barColors(name: String): Pair<Color, Color> {
        val b = tone(name).bar
        return if (C.palette.dark) b to lerp(b, Color.White, 0.28f)
        else b to lerp(b, Color.Black, 0.22f)
    }

    /** 卡片渐变两端。亮色=极浅低饱和同色相；暗色/玻璃=同色相半透明深色 */
    fun fillColors(name: String): Pair<Color, Color> {
        val t = tone(name)
        return if (C.palette.dark) {
            // 暗色下卡片是同色相半透明深色渐变：太淡看不见、太浓会盖掉深色底，
            // 0.40→0.52 是深色 card(#171A21) 上能看出渐变又不刺眼的区间
            lerp(t.bar, Color.Black, 0.28f).copy(alpha = 0.48f) to
                lerp(t.bar, Color.Black, 0.58f).copy(alpha = 0.60f)
        } else {
            t.from to t.to
        }
    }

    /** 左侧色条 */
    fun bar(name: String): Color = tone(name).bar

    /** 卡片主色（渐变中值），给不支持渐变的地方兜底 */
    fun fill(name: String): Color = fillColors(name).let { lerp(it.first, it.second, 0.5f) }

    /** 卡片上的文字颜色：浅色底用深色字，深色/玻璃底用浅色字 */
    fun ink(name: String): Color =
        if (C.palette.dark) Color(0xFFEDEFF7) else tone(name).ink

    /** 兼容旧调用 */
    fun of(name: String): Color = fill(name)
}
