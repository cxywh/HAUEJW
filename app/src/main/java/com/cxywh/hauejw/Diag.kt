package com.cxywh.hauejw

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简本地诊断：把被 runCatching 吞掉的失败记下来 —— 否则它们在代码里等于不存在，
 * 「偶发失败」永远查不出来。
 *
 * 安全约定（硬性，不要放宽）：
 *   只记「时间 + 位置标签 + 异常类名」。**不记异常消息、不记请求参数、不记账号密码。**
 *   异常消息里经常带 URL、查询参数、账号，记进去就等于把凭据写进日志文件 ——
 *   类的名字（UnknownHostException / SSLHandshakeException / JSONException …）
 *   已经足够定位问题出在哪一类了。
 *
 * 内存里保留最近 [MAX] 条，同时追加到应用私有目录的 diag.log（同样截断）。
 * 需要排查时用 adb 取（私有目录，别的应用读不到）：
 *   adb exec-out cat /sdcard/Android/data/com.cxywh.hauejw/files/diag.log
 */
object Diag {

    /** 保留条数上限（内存与文件同用） */
    const val MAX = 200

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val ring = ArrayDeque<String>()
    private var sink: File? = null

    /** 启动时接上文件出口。传 null 表示只用内存缓冲 */
    fun attach(dir: File?) {
        sink = dir?.let { File(it, "diag.log") }
    }

    /**
     * 记录一次被吞掉的失败。
     * 只取异常类名；t 为 null 时记 "?"（有些失败只有 Result 没有 Throwable）。
     */
    fun record(tag: String, t: Throwable?) {
        val line = "${stamp.format(Date())} [$tag] ${t?.javaClass?.simpleName ?: "?"}"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > MAX) ring.removeFirst()
        }
        runCatching {
            val f = sink ?: return
            f.appendText(line + "\n")
            val all = f.readLines()
            if (all.size > MAX) f.writeText(all.takeLast(MAX).joinToString("\n") + "\n")
        }
    }

    /** 当前缓冲区（最早的在前）。给排查用，不做界面展示 */
    fun dump(): List<String> = synchronized(ring) { ring.toList() }

    /** 清空内存与文件 */
    fun clear() {
        synchronized(ring) { ring.clear() }
        runCatching { sink?.writeText("") }
    }
}
