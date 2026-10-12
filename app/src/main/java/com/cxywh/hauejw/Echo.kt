package com.cxywh.hauejw

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Random

/**
 * 回声洞：把 jwzs.txt 里的自定义语句轮流显示在课表小组件底部那一块。
 *
 * 文件格式（宽松解析，故意不做严格语法校验）：
 *     "要显示的内容";
 *     直接写一句话;
 * 分号（; ；）和换行都算语句分隔符；引号（"…" “…” 「…」 『…』 ‘…’）里的才是正文，
 * 整段一个引号都没有时，整段就是正文 —— 随手写、标点写反了都不会导致整段不显示。
 *
 * 解析/挑选/故障帧都是纯函数，能在 JVM 上单测：RemoteViews 本身没法测，但「显示哪一句」可以。
 */
object Echo {

    const val FILE_NAME = "jwzs.txt"

    /**
     * 最多读这么多字节。文件再大，屏幕上一次也只能显示一句，
     * 没必要每轮渲染都整个读进来（手滑塞进超大文件的兜底）。
     */
    private const val MAX_FILE_BYTES = 256 * 1024

    /** 单句上限：一句话写到几万字，卡片上也放不下，解析时截断并加省略号 */
    const val MAX_STATEMENT_CHARS = 2_000

    /** 「机器故障」帧用的乱码字符 */
    private const val GLYPHS = "▓▒░█▚▞▙▟#%&@*+=~<>/\\|_^"
    private const val BLOCKS = "█▓▒▚"

    // ────────────────────────── 文件 ──────────────────────────

    /** 文件管理器 → Android/data/com.cxywh.hauejw/files/ 下能直接改这个文件 */
    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), FILE_NAME)

    /** 包内默认内容（用户没动过文件时用它） */
    fun defaultText(ctx: Context): String =
        runCatching { decode(ctx.assets.open(FILE_NAME).readBytes()) }.getOrElse { "" }

    /** 用户的文件优先；没有或读不动就退回包内默认 */
    fun readRaw(ctx: Context): String {
        val f = file(ctx)
        if (f.isFile && f.length() > 0L) {
            runCatching { return decode(readCapped(f)) }
                .onFailure { e -> Diag.record("Echo.read", e) }
        }
        return defaultText(ctx)
    }

    /** 只读前 [MAX_FILE_BYTES] 字节 */
    private fun readCapped(f: File): ByteArray {
        val cap = MAX_FILE_BYTES.coerceAtMost(f.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val buf = ByteArray(cap)
        f.inputStream().use { ins ->
            var off = 0
            while (off < cap) {
                val n = ins.read(buf, off, cap - off)
                if (n <= 0) break
                off += n
            }
            return if (off == cap) trimPartialUtf8(buf) else buf.copyOf(off)
        }
    }

    /**
     * 掐掉截断处的半个 UTF-8 字符（末尾最多回退 3 字节）。
     * 不掐的话严格解码会整篇失败、退到 GBK 分支，于是整份内容变成乱码。
     */
    internal fun trimPartialUtf8(b: ByteArray): ByteArray {
        var end = b.size
        var back = 0
        while (end > 0 && back < 4 && (b[end - 1].toInt() and 0xC0) == 0x80) { end--; back++ }
        if (end <= 0) return b
        val lead = b[end - 1].toInt() and 0xFF
        val need = when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (b.size - (end - 1) >= need) b else b.copyOf(end - 1)
    }

    fun save(ctx: Context, text: String) {
        runCatching {
            ctx.getExternalFilesDir(null)?.mkdirs()
            file(ctx).writeBytes(text.toByteArray(Charsets.UTF_8))
        }.onFailure { e -> Diag.record("Echo.save", e) }
    }

    /** 解析好的语句列表 */
    fun load(ctx: Context): List<String> = parse(readRaw(ctx))

    /**
     * UTF-8 优先，读不出来按 GBK 再试一次（中文 Windows 的记事本、老编辑器常存成 GBK），
     * 并去掉 UTF-8 BOM。用严格解码：能过就是真 UTF-8，不会把 GBK 当成 UTF-8 读出乱码。
     */
    fun decode(bytes: ByteArray): String {
        val b = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) bytes.copyOfRange(3, bytes.size) else bytes

        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8

        return runCatching { String(b, Charset.forName("GBK")) }.getOrDefault("")
    }

    // ────────────────────────── 解析 ──────────────────────────

    /**
     * 引号归到哪一对（0 = 不是引号）。
     * 一对里的两个字符互认：`“甲”`、`”甲”`、`“甲“` 都能取出「甲」——
     * 不严格要求写对方向，用户随手写的引号不该让整句不显示。
     */
    private fun pairOf(c: Char): Int = when (c) {
        '"' -> 1
        '\u201C', '\u201D' -> 2
        '\u2018', '\u2019' -> 3
        '「', '」' -> 4
        '『', '』' -> 5
        else -> 0
    }

    /**
     * 解析成语句列表（保序去重）。
     *
     * 规则：碰到引号就进出「正文区」（同一对引号收尾，写反也认）；正文区里的一切
     * （含别的引号、分号）都算正文；正文区外遇到分号或换行就断句；整句一个引号都没出现时，
     * 整句原文当正文。所以 `“甲”；「乙」;` 与 `"甲";"乙";` 结果一样。
     */
    fun parse(text: String): List<String> {
        val out = ArrayList<String>()
        val inner = StringBuilder()   // 引号里的
        val outer = StringBuilder()   // 引号外的（整句没引号时兜底）
        var openPair = 0
        var quoted = false

        fun flush() {
            val s0 = (if (quoted) inner.toString() else outer.toString()).trim()
            val s = if (s0.length > MAX_STATEMENT_CHARS) s0.take(MAX_STATEMENT_CHARS) + "…" else s0
            if (s.isNotEmpty()) out.add(s)
            inner.setLength(0); outer.setLength(0); openPair = 0; quoted = false
        }

        for (ch in text) {
            val pr = pairOf(ch)
            when {
                pr != 0 && openPair == 0 -> { openPair = pr; quoted = true }
                pr != 0 && pr == openPair -> openPair = 0
                openPair != 0 -> inner.append(ch)
                ch == ';' || ch == '；' || ch == '\n' || ch == '\r' -> flush()
                else -> outer.append(ch)
            }
        }
        flush()
        return out.distinct()
    }

    // ────────────────────────── 故障帧 ──────────────────────────

    /**
     * 按比例把正文换成乱码字符，做「机器故障」里的一帧。
     * 按码点走，不会劈开 emoji；block = true 时用连续色块（「整块糊掉」的观感）。
     */
    fun glitch(text: String, rnd: Random, ratio: Double = 0.28, block: Boolean = false): String {
        val sb = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val hit = rnd.nextDouble() < ratio && !Character.isWhitespace(cp)
            if (!hit) {
                sb.append(text, i, i + n)
            } else if (block) {
                repeat(2 + rnd.nextInt(2)) { sb.append(BLOCKS[rnd.nextInt(BLOCKS.length)]) }
            } else {
                sb.append(GLYPHS[rnd.nextInt(GLYPHS.length)])
            }
            i += n
        }
        return sb.toString()
    }

    /**
     * 换句时连着播的四帧：乱码 → 再乱一次 → 整块糊掉 → 正片。
     * 最后一帧一定等于原文（渲染端拿它当「稳定」帧）。
     */
    fun frames(text: String, rnd: Random): List<String> = listOf(
        glitch(text, rnd, 0.30),
        glitch(text, rnd, 0.45),
        glitch(text, rnd, 0.20, block = true),
        text,
    )

    // ────────────────────────── 换句节奏 ──────────────────────────

    /** 故障动画相邻两帧的间隔（毫秒）：四帧连播约半秒，再快看不清、再慢不像故障 */
    const val FRAME_MS = 150

    /** 整句完整显示之后停多久，才换下一条 */
    const val HOLD_MS = 5_000L

    /** 从第一帧乱码播到整句完整显示要多久（默认四帧：三帧乱码 + 正片） */
    fun shownInMs(frames: Int = 4): Long = (frames - 1).coerceAtLeast(0) * FRAME_MS.toLong()

    /**
     * 下一次换句离现在多远：先让整句完整显示，再停 [HOLD_MS]。
     * 注意是「从渲染开始算」的绝对时长 —— 渲染发生在闹钟触发的那一刻。
     */
    fun nextEchoInMs(frames: Int = 4): Long = shownInMs(frames) + HOLD_MS

    // ────────────────────────── 挑选（尽量不重复）──────────────────────────

    /** 0..n-1 的随机排列；avoid 非空且 n > 1 时保证第一位不是它 */
    fun shuffled(n: Int, rnd: Random, avoid: Int? = null): List<Int> {
        val q = MutableList(n) { it }
        for (i in n - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = q[i]; q[i] = q[j]; q[j] = t
        }
        if (n > 1 && avoid != null && q[0] == avoid) {
            val j = 1 + rnd.nextInt(n - 1)
            val t = q[0]; q[0] = q[j]; q[j] = t
        }
        return q
    }

    data class Pick(val index: Int, val queue: List<Int>, val pos: Int)

    /**
     * 下一句该显示哪条。
     * 走完一整轮才重洗，所以一轮之内不会重复；重洗时还会避开刚显示过的那句 ——
     * 连着两条一模一样最像「坏了」。条数变了（用户改了 jwzs.txt）也会重洗。
     */
    fun pick(
        total: Int,
        queue: List<Int>,
        pos: Int,
        texts: List<String>,
        lastShown: String?,
        rnd: Random,
    ): Pick {
        if (total <= 0) return Pick(-1, emptyList(), 0)
        val valid = queue.filter { it in 0 until total }.distinct()
        var q = valid
        var p = pos
        if (q.size != queue.size || p !in q.indices) {
            val avoid = lastShown?.let { s -> texts.indexOfFirst { it == s }.takeIf { it >= 0 } }
            q = shuffled(total, rnd, avoid)
            p = 0
        }
        return Pick(q[p], q, p + 1)
    }
}
