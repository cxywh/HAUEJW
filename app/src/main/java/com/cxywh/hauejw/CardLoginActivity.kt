package com.cxywh.hauejw

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 校园卡门户登录。
 *
 * 为什么要绕一层 WebView：这个学校的登录是「学号 + 安全键盘 + 图形验证码」，
 * 原生复刻要自己下验证码图片、复刻键盘的 uuid 协议，每换一次登录方式就得改代码。
 * 这里直接把门户原样打开，用户在熟悉的页面上自己登录，App 只在登录成功后从
 * sessionStorage 里取 access_token —— 密码、验证码、键盘全程不经过本应用。
 *
 * 每次打开都先把 WebView 的 cookie / localStorage / 缓存清空再来：
 * 失败过几次之后站点会把这个浏览器实例判成异常（整页 403），
 * 而同一台手机的普通浏览器仍然正常，说明脏的是这份存储，不是网络。
 */
class CardLoginActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var hintBar: TextView
    private var done = false
    private var reloadCount = 0


    /** 学号：只用来做本地加密存储的键名，不影响登录本身 */
    private val sno: String get() = intent?.getStringExtra(EXTRA_SNO).orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F2124"))
            setPadding(dp(16), dp(12), dp(10), dp(12))
        }
        val title = TextView(this).apply {
            text = "登录校园卡"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val reload = TextView(this).apply {
            text = "刷新"
            setTextColor(Color.parseColor("#B9BDC4"))
            textSize = 13f
            setPadding(dp(12), 0, dp(6), 0)
            setOnClickListener { hintBar.visibility = View.GONE; web.reload() }
        }
        val close = TextView(this).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#B9BDC4"))
            textSize = 13f
            setPadding(dp(12), 0, dp(6), 0)
            setOnClickListener { finish() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(reload)
        bar.addView(close)

        hintBar = TextView(this).apply {
            text = "站点暂时拒绝了访问（403），通常几分钟后自行恢复，期间点「刷新」不会更快。"
            setTextColor(Color.parseColor("#FFD8A8"))
            setBackgroundColor(Color.parseColor("#3A2E20"))
            textSize = 12f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // WebView 默认 UA 带 "; wv)" 与 "Version/4.0"，部分 WAF 会判成爬虫
            settings.userAgentString = UA
            addJavascriptInterface(Bridge(), "CardBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    val doc = request.isForMainFrame
                    if (doc || u.contains("captcha") || u.contains("berserker-auth") || u.contains("berserker-secure")) {
                        diag("REQ " + request.method + " " + u)
                    }
                    return null
                }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    injectSniffer(view ?: return)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    injectSniffer(view ?: return)
                    injectPoller(view ?: return)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if ((errorResponse?.statusCode ?: 0) == 403) {
                        getSharedPreferences("card", MODE_PRIVATE).edit()
                            .putBoolean(KEY_POISONED, true).apply()
                        if (request?.isForMainFrame == true) {
                            resetSession("会话被站点拒绝，正在换一个新会话…")
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        hintBar.text = "打不开站点：" + (error?.description ?: "") + "，检查网络后点「刷新」。"
                        hintBar.visibility = View.VISIBLE
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage?): Boolean = true
            }
        }

        root.addView(bar)
        root.addView(hintBar)
        root.addView(FrameLayout(this).apply { addView(web) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        // 只在上一轮被站点拒过（403）时才清 cookie 与 localStorage；
        // 平时不清，也不清 HTTP 缓存 —— 页面一冷启动要拉 120 多个分包，
        // 学校的 WAF 按请求频率拦，重新下载一整遍正好把限流窗口撞满。
        val prefs = getSharedPreferences("card", MODE_PRIVATE)
        // 手上没有可用令牌时也清一次：否则 WebView 里可能残留上一轮"已登录但令牌没抓到"的会话，
        // 打开后直接进首页、再也不发登录请求，我们就永远拿不到 token
        val hasToken = sno.isNotBlank() && CardFee.load(this, sno) != null
        if (prefs.getBoolean(KEY_POISONED, false) || !hasToken) {
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { WebStorage.getInstance().deleteAllData() }
            prefs.edit().putBoolean(KEY_POISONED, false).apply()
            diag("clean profile")
        }
        runCatching { java.io.File(getExternalFilesDir(null) ?: filesDir, "card.log").delete() }
        diag("UA=" + web.settings.userAgentString)

        val target = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") } ?: CardFee.PORTAL
        web.postDelayed({ web.loadUrl(target) }, 250)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 换一个新会话：清 cookie 与 localStorage（不清 HTTP 缓存，重载很轻）后再载入。
     * 触发场景：整页 403、验证码请求 403。实测同一时刻同一出口 IP 下，
     * 不带 cookie 的 curl 是 200、带着旧会话的 WebView 是 403 —— 脏的是会话，不是网络。
     */
    private fun resetSession(reason: String) {
        if (reloadCount >= 2) return
        reloadCount++
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { WebStorage.getInstance().deleteAllData() }
        hintBar.text = reason
        hintBar.visibility = View.VISIBLE
        diag("reset session: " + reason)
        web.postDelayed({ web.reload() }, 1200)
    }


    /**
     * 诊断日志。这台设备上 adb logcat 取不到应用日志，
     * 改写到 Android/data/com.cxywh.hauejw/files/card.log，用 adb shell cat 读。
     * 只记 URL、状态码、请求头、错误响应体开头，不记任何输入内容。
     */
    private fun diag(text: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = java.io.File(dir, "card.log")
            if (f.length() > 512 * 1024) f.delete()
            f.appendText(System.currentTimeMillis().toString() + " " + text + "\n")
        } catch (e: Exception) {
            // 诊断失败不影响登录
        }
    }


    /** 挂钩 XHR / fetch，把 4xx/5xx 请求打到顶栏提示；正常请求不吭声 */
    private fun injectSniffer(view: WebView) {
        val js = """
            (function(){
              if (window.__haueNetHooked) return;
              window.__haueNetHooked = true;
              function report(method, url, status, body, secret){
                try {
                  if (!window.CardBridge) return;
                  if (secret) {
                    window.CardBridge.log('TOKEN ' + method + ' ' + url + ' -> ' + status);
                    window.CardBridge.onLoginResponse(String(body || ''));
                  }
                  else window.CardBridge.log(method + ' ' + url + ' -> ' + status + ' ' + String(body || '').slice(0,120));
                } catch(e){}
              }
              var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return oo.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function(){
                var self = this;
                this.addEventListener('loadend', function(){
                  var token = /oauth\/token/.test(String(self.__u || ''));
                  var b = '';
                  try { if (self.status >= 400 || token) b = self.responseText || ''; } catch(e){}
                  report(self.__m, self.__u, self.status, b, token);
                });
                return os.apply(this, arguments);
              };
              if (window.fetch) {
                var of = window.fetch;
                window.fetch = function(){
                  var args = arguments;
                  return of.apply(this, arguments).then(function(r){
                    try {
                      var token = /oauth\/token/.test(String(args[0]));
                      r.clone().text().then(function(t){ report('FETCH', String(args[0]), r.status, (r.status >= 400 || token) ? t : '', token); });
                    } catch(e){}
                    return r;
                  });
                };
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * 每 500ms 看一次 sessionStorage。
     * 门户自己登录成功后会写 access_token，这里只负责发现它，不参与登录过程。
     */
    private fun injectPoller(view: WebView) {
        val js = """
            (function(){
              if (window.__haueCardPoll) return;
              window.__haueCardPoll = setInterval(function(){
                try {
                  var t = sessionStorage.getItem('access_token') || localStorage.getItem('access_token') || '';
                  if (t) {
                    var r = sessionStorage.getItem('refreshObj') || '';
                    if (window.CardBridge) window.CardBridge.onToken(t, r);
                    clearInterval(window.__haueCardPoll);
                  }
                } catch (e) {}
              }, 500);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun log(text: String) {
            diag("net " + text)
            // 失败一次后站点会刷新验证码，这一刷常常被 403 打死，页面就此卡住；
            // 自动重载登录页一次，等于重建会话重新取验证码
            if (text.contains("captcha") && (text.contains("-> 403") || text.contains("-> 5"))) {
                runOnUiThread { resetSession("验证码被站点拒绝，正在换会话重取…") }
            }
            val bad = text.contains("-> 4") || text.contains("-> 5") ||
                text.contains("Forbidden") || text.contains("403")
            if (!bad) return
            runOnUiThread {
                hintBar.text = "站点返回：" + text.take(90)
                hintBar.visibility = View.VISIBLE
            }
        }

        /** 登录响应体：直接从中取 access_token，不写日志（里面是凭据） */
        @JavascriptInterface
        fun onLoginResponse(body: String) {
            if (done) return
            val a = CardFee.parseToken(body) ?: return
            done = true
            runOnUiThread { finishWith(a) }
        }

        @JavascriptInterface
        fun onToken(token: String, refreshObj: String) {
            if (done || token.isBlank()) return
            val url = web.url ?: return
            if (!url.contains(CardFee.HOST)) return
            done = true
            runOnUiThread { finishWith(authFrom(token, refreshObj)) }
        }
    }

    /** sessionStorage 兜底路径：只有 token 字符串，过期时间从 refreshObj 里捞 */
    private fun authFrom(token: String, refreshObj: String): CardFee.Auth {
        var refresh = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"")
            .find(refreshObj)?.groupValues?.get(1).orEmpty()
        if (refresh.isBlank()) {
            // refreshObj 可能被转义成 \" 形式
            refresh = Regex("refresh_token\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]+)")
                .find(refreshObj)?.groupValues?.get(1).orEmpty()
        }
        val exp = Regex("expires_in\\\\?\"\\s*:\\s*\\\\?\"?(\\d+)")
            .find(refreshObj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val loginTime = Regex("login_time\\\\?\"\\s*:\\s*(\\d{10,})")
            .find(refreshObj)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
        return CardFee.Auth(token, refresh, if (exp > 0) loginTime + exp * 1000 else 0L)
    }

    private fun finishWith(auth: CardFee.Auth) {
        if (done && isFinishing) return
        CardFee.auth = auth
        if (sno.isNotBlank()) CardFee.save(this, sno, auth)
        diag("token captured len=" + auth.token.length + " refresh=" + auth.refresh.length)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SNO, sno))
        finish()
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SNO = "sno"

        /** 可选：直接打开的页面（例如某个费项的电控页面），默认打开门户首页 */
        const val EXTRA_URL = "url"

        /** 上一次被站点 403 过；下次打开先清 cookie 与 localStorage 再来 */
        private const val KEY_POISONED = "poisoned"

        /** 干净的移动 Chrome UA：不带 wv / Version/4.0 */
        private const val UA = "Mozilla/5.0 (Linux; Android 14; REA-AN00) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
