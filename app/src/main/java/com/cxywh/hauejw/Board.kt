package com.cxywh.hauejw

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

internal sealed interface Screen {
    data object Loading : Screen
    data class Login(val error: String? = null, val busy: Boolean = false) : Screen
    data class Main(
        val base: Timetable,
        val edits: EditLayer = EditLayer.EMPTY,
        val refreshing: Boolean = false,
        val notice: String? = null,
        val noticeOk: Boolean = false,
    ) : Screen
}
internal fun Screen.Main.board(): Timetable = base.withCourses(edits.apply(base.courses))

@Composable
internal fun Root(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // 老版本只认一个账号（键名没有学号后缀）：首次启动把它挪到当前学号名下，省得重输一遍密码
    LaunchedEffect(Unit) {
        // 老版本把会话 Cookie 加密存过一份，现在已无人读取：顺手清掉，少一份落盘的凭据
        SecretStore.purgeSessions(ctx)
        // 回声洞文件不在就先建出来（写的是包内默认内容）——装完即存在，用户自己用文件管理器就能改
        Echo.ensureFile(ctx)
        if (prefs.username.isNotBlank()) {
            SecretStore.migrateLegacy(ctx, prefs.username)
            migrateSingleAccountData(ctx.getSharedPreferences("hauejw", android.content.Context.MODE_PRIVATE), prefs.username)
            if (SecretStore.has(ctx, prefs.username)) prefs.addAccount(prefs.username)
        }
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }

    // 横幅（「已更新…」/「更新失败…」）显示 3 秒后自动消失，别一直挂在课表上面
    val shownNotice = (screen as? Screen.Main)?.notice
    LaunchedEffect(shownNotice) {
        if (shownNotice != null) {
            delay(3000)
            val cur = screen
            if (cur is Screen.Main && cur.notice == shownNotice) {
                screen = cur.copy(notice = null, noticeOk = false)
            }
        }
    }
    var username by remember { mutableStateOf(prefs.username) }

    fun applyMain(base: Timetable, edits: EditLayer = prefs.edits, notice: String? = null, ok: Boolean = false, refreshing: Boolean = false) {
        screen = Screen.Main(base, edits, refreshing = refreshing, notice = notice, noticeOk = ok)
    }

    fun refreshFrom(current: Timetable?, e0: EditLayer) {
        scope.launch {
            val prev = current
            if (prev != null) applyMain(prev, e0, refreshing = true) else screen = Screen.Loading
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    // 会话过期时接口会回空/登录页：用本机加密存的密码补登一次再重试（密码不出设备）
                    try {
                        JwApi.fetchTimetable()
                    } catch (e: Exception) {
                        if (!relogin(ctx)) throw e
                        JwApi.fetchTimetable()
                    }
                }.onFailure { e -> Diag.record("MainActivity.refreshFrom", e) }
            }
            res.fold(
                onSuccess = { (tt, raw) ->
                    prefs.timetableCache = raw
                    prefs.timetableSavedAt = System.currentTimeMillis()
                    prefs.source = Source.SERVER
                    if (tt.student.id.isNotBlank()) {
                        prefs.username = tt.student.id
                        username = tt.student.id
                    }
                    applyMain(tt, prefs.edits, notice = "已更新（服务端课表）", ok = true)
                },
                onFailure = { e ->
                    if (prev != null) {
                        // 登录失效 / 断网时照样能看课表，并明确告诉用户看的是哪一次存下来的
                        applyMain(
                            prev, e0,
                            notice = "更新失败：" + (e.message ?: "网络异常") +
                                "；显示的是 " + tsText(prefs.timetableSavedAt) + " 保存的课表（登录失效也能看）",
                        )
                    } else if (e is JwApi.LoginException) {
                        screen = Screen.Login(error = e.message)
                    } else {
                        screen = Screen.Login(error = "无法连接教务系统：" + (e.message ?: "网络异常"))
                    }
                },
            )
        }
    }

    /** 切换账号：先换当前账号，再读这个账号自己的课表缓存并后台刷新（补登会用新账号的密码） */
    fun switchAccount(id: String) {
        if (id.isBlank() || id == prefs.username) return
        scope.launch {
            prefs.username = id
            username = id
            // 账号决定了小组件读哪份缓存，所以这里必须显式刷一次
            // （下面两条分支里有一条是 screen = Loading，走不到课表页的 LaunchedEffect）
            HaueWidget.refresh(ctx)
            val cached = prefs.loadServer()
            if (cached != null) applyMain(cached, prefs.edits, refreshing = true, notice = "已切换到 $id", ok = true)
            else screen = Screen.Loading
            refreshFrom(cached, prefs.edits)
        }
    }

    LaunchedEffect(Unit) {
        val base = withContext(Dispatchers.IO) { prefs.loadBase() }
        if (base != null) {
            // 启动只读本地缓存：课表不常变，不再每次开 App 都去打服务端。
            // 需要刷新时由用户点底部「更新课表」触发（会话过期会自动补登）。
            applyMain(base, prefs.edits)
        } else {
            screen = Screen.Login()
        }
    }

    when (val s = screen) {
        is Screen.Loading -> Box(Modifier.fillMaxSize().background(C.screenBg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = C.accent)
                Spacer(Modifier.height(12.dp))
                Text("正在读取课表…", color = C.dim, fontSize = 13.sp)
            }
        }

        is Screen.Login -> LoginScreen(
            prefs = prefs,
            error = s.error,
            busy = s.busy,
            onLogin = { u, pw, rememberPw ->
                screen = Screen.Login(busy = true)
                scope.launch {
                    var diag = ""
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            val home = JwApi.login(u, pw)
                            val menus = JwApi.parseMenus(home)
                            // 诊断文本：万一直达页对不上，看这个就知道学校真实的菜单地址长什么样
                            diag = "clickMenu 命中：${JwApi.countClickMenu(home)}\n" +
                                "解析到菜单：${menus.size} 条\n" +
                                menus.entries.joinToString("\n") { "${it.key}\t${it.value}" } +
                                "\n--- clickMenu 原始三元组（功能号 | 地址 | 名称）---\n" +
                                JwApi.rawMenuTriples(home).joinToString("\n") +
                                "\n--- 页面里出现的 /jwglxt 地址（前 60 个）---\n" +
                                JwApi.allPaths(home).take(60).joinToString("\n")
                            val fetched = JwApi.fetchTimetable()
                            Triple(menus, fetched.first, fetched.second)
                        }.onFailure { e -> Diag.record("MainActivity.refreshFrom", e) }
                    }
                    res.fold(
                        onSuccess = { r ->
                            val (menus, tt, raw) = r
                            // 课表落到本地：之后登录失效、断网、开学前都能看
                            prefs.timetableCache = raw
                            prefs.timetableSavedAt = System.currentTimeMillis()
                            prefs.rememberUsername = true
                            prefs.username = u
                            prefs.source = Source.SERVER
                            if (menus.isNotEmpty()) {
                                val jo = JSONObject()
                                menus.forEach { (k, v) -> jo.put(k, v) }
                                prefs.menuJson = jo.toString()
                            }
                            if (diag.isNotBlank()) {
                                runCatching {
                                }.onFailure { e -> Diag.record("MainActivity.refreshFrom", e) }
                            }
                            // 记住密码：登录成功之后才写，避免把打错的密码存下来
                            prefs.rememberPassword = rememberPw
                            val pwSaved = if (rememberPw) SecretStore.save(ctx, u, pw) else true
                            if (rememberPw) prefs.addAccount(u)
                            else { SecretStore.forget(ctx, u); prefs.removeAccount(u) }
                            username = u
                            applyMain(
                                tt, prefs.edits,
                                notice = if (rememberPw && !pwSaved) "登录成功；但这台设备不支持安全保存密码，请勿勾选「记住密码」"
                                else "登录成功，课表已保存到本机",
                                ok = true,
                            )
                        },
                        onFailure = { e -> screen = Screen.Login(error = e.message ?: "登录失败") },
                    )
                }
            },
            onExit = { (ctx as? android.app.Activity)?.finish() },
            onUseImported = { tt -> applyMain(tt, prefs.edits) },
            imported = prefs.loadImported(),
        )

        is Screen.Main -> BoardScreen(
            prefs = prefs,
            base = s.base,
            edits = s.edits,
            refreshing = s.refreshing,
            notice = s.notice,
            noticeOk = s.noticeOk,
            onRefresh = { refreshFrom(s.base, s.edits) },
            onEdit = { op ->
                val next = s.edits.plus(op)
                prefs.saveEdits(next)
                applyMain(s.base, next, notice = "已更新课表", ok = true)
            },
            onClearFor = { key ->
                val next = s.edits.clearFor(key)
                prefs.saveEdits(next)
                applyMain(s.base, next, notice = "已撤销这门课的本地修改", ok = true)
            },
            onClearEdits = {
                prefs.clearEdits()
                applyMain(s.base, EditLayer.EMPTY, notice = "已清空全部本地修改", ok = true)
            },
            onImport = { tt, warn ->
                prefs.saveImported(tt)
                // 导入的课表自带学期/周次时，本地修改按新课程重算，故清空
                prefs.clearEdits()
                val extra = if (warn.isEmpty()) "" else "；${warn.size} 条提示：" + warn.first().take(40)
                applyMain(tt, EditLayer.EMPTY, notice = "已导入 ${tt.courses.size} 节课程$extra", ok = true)
            },
            onUseServer = {
                val srv = prefs.loadServer()
                if (srv == null) {
                    applyMain(s.base, s.edits, notice = "还没有服务端课表，请先「更新课表」登录拉取")
                } else {
                    prefs.source = Source.SERVER
                    applyMain(srv, prefs.edits, notice = "已切回服务端课表", ok = true)
                }
            },
            onDropImported = {
                prefs.clearImported()
                val srv = prefs.loadServer()
                if (srv != null) applyMain(srv, prefs.edits, notice = "已删除导入的课表", ok = true)
                else applyMain(s.base, s.edits, notice = "已删除导入的课表")
            },
            onLogout = {
                scope.launch {
                    withContext(Dispatchers.IO) { JwApi.logout() }
                    prefs.timetableCache = null
                    HaueWidget.refresh(ctx)
                    val imported = prefs.loadImported()
                    if (imported != null) applyMain(imported, prefs.edits, notice = "已退出登录，仍显示导入的课表")
                    else { screen = Screen.Login() }
                }
            },
            onLoginAgain = { screen = Screen.Login() },
            onSwitchAccount = { id ->
                if (id != prefs.username) {
                    // 存过密码的直接切；没存过的去登录页，学号已填好，只差输密码
                    if (SecretStore.has(ctx, id)) switchAccount(id)
                    else { prefs.username = id; username = id; HaueWidget.refresh(ctx); screen = Screen.Login() }
                }
            },
            onAddAccount = { screen = Screen.Login() },
        )
    }
}
@Composable
internal fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.fg, unfocusedTextColor = C.fg,
    cursorColor = C.accent,
    focusedBorderColor = C.accent, unfocusedBorderColor = C.lineStrong,
    focusedLabelColor = C.accent, unfocusedLabelColor = C.dim,
    focusedPlaceholderColor = C.dim, unfocusedPlaceholderColor = C.dim,
)

@Composable
internal fun LoginScreen(
    prefs: Prefs,
    error: String?,
    busy: Boolean,
    onLogin: (String, String, Boolean) -> Unit,
    onExit: () -> Unit,
    onUseImported: (Timetable) -> Unit,
    imported: Timetable?,
) {
    val ctx = LocalContext.current
    var user by remember { mutableStateOf(prefs.username) }
    var remember by remember { mutableStateOf(prefs.rememberUsername) }
    var rememberPw by remember { mutableStateOf(prefs.rememberPassword) }
    // 之前勾过「记住密码」的话，这里用系统密钥库里的密钥解密还原（取不到就是空）
    var pw by remember { mutableStateOf(if (prefs.rememberPassword) SecretStore.load(ctx, prefs.username).orEmpty() else "") }
    var pwNote by remember { mutableStateOf<String?>(null) }
    // 记住过的账号（最近用的排前面），点一下直接用存好的密码登录
    var savedAccounts by remember { mutableStateOf(mergeAccounts(prefs.accounts, SecretStore.accounts(ctx))) }

    val onAccent = C.onAccent

    Column(
        Modifier.fillMaxSize().background(C.screenBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text("教务助手", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Text("河南工程学院 教务系统", fontSize = 13.sp, color = C.dim)
        Spacer(Modifier.height(28.dp))

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card).padding(18.dp)) {
            OutlinedTextField(
                value = user, onValueChange = { user = it.trim() }, label = { Text("学号") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = appFieldColors(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pw, onValueChange = { pw = it }, label = { Text("密码") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), colors = appFieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = remember, onCheckedChange = { remember = it })
                Spacer(Modifier.width(8.dp))
                Text("记住学号", fontSize = 13.sp, color = C.dim)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rememberPw,
                    onCheckedChange = { on ->
                        rememberPw = on
                        if (!on) {
SecretStore.forget(ctx, prefs.username)
                        prefs.removeAccount(prefs.username)
                        pw = ""
                        pwNote = "已清除这个账号保存的密码"
                        } else {
                            pwNote = null
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("记住密码", fontSize = 13.sp, color = C.dim)
                    Text(
                        "AES 加密后存本机，密钥在系统密钥库，卸载或清数据即失效",
                        fontSize = 10.sp, color = C.dim,
                    )
                }
            }
            pwNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = C.okFg)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    prefs.rememberUsername = remember
                    prefs.username = if (remember) user else ""
                    onLogin(user, pw, rememberPw)
                },
                enabled = !busy && user.isNotBlank() && pw.isNotBlank(),
                colors = appButtonColors(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(color = onAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (busy) "登录中…" else "登录", fontSize = 15.sp)
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = C.danger, fontSize = 12.5.sp)
            }
        }

        if (savedAccounts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("已记住的账号（点一下直接登录）", fontSize = 11.5.sp, color = C.dim)
            Spacer(Modifier.height(8.dp))
            savedAccounts.forEach { acc ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(11.dp)).background(C.card)
                        .clickable {
                            val saved = SecretStore.load(ctx, acc)
                            user = acc
                            if (saved.isNullOrBlank()) {
                                pw = ""
                                pwNote = "这个账号没保存密码，请手动输入"
                            } else {
                                pw = saved
                                prefs.rememberUsername = true
                                prefs.username = acc
                                onLogin(acc, saved, true)
                            }
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(acc, fontSize = 13.5.sp, color = C.fg, modifier = Modifier.weight(1f))
                    Text(
                        "×", fontSize = 16.sp, color = C.dim,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable {
                                SecretStore.forget(ctx, acc)
                                prefs.removeAccount(acc)
                                if (prefs.username == acc) { prefs.username = ""; prefs.rememberPassword = false }
                                savedAccounts = savedAccounts.filter { it != acc }
                            }
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                SecretStore.clearAll(ctx)
                prefs.accounts = emptyList()
                savedAccounts = emptyList()
                prefs.rememberPassword = false
                pw = ""
                pwNote = "已清除全部账号记忆"
            }) { Text("清除全部账号记忆", color = C.dim, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "账号密码与教务系统一致。密码经 RSA 加密后提交；\n勾选「记住密码」才在本机加密保存，随时可取消。",
            fontSize = 11.5.sp, color = C.dim, textAlign = TextAlign.Center,
        )

        if (imported != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                "不登录也能看课表：打开已导入的课表（${imported.courses.size} 节）",
                fontSize = 13.sp, color = C.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.card)
                    .clickable { onUseImported(imported) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onExit) { Text("退出", color = C.dim, fontSize = 13.sp) }
    }
}
@Composable
internal fun appButtonColors() = ButtonDefaults.buttonColors(
    containerColor = C.accent,
    contentColor = C.onAccent,
    disabledContainerColor = C.accent.copy(alpha = 0.55f),
    disabledContentColor = C.onAccent.copy(alpha = 0.85f),
)

// ────────────────────────── 课表主界面 ──────────────────────────

internal sealed interface Pane {
    data object Weeks : Pane
    data object Tools : Pane
    data object Scores : Pane
    data object Exams : Pane
    data object Import : Pane
    data object Remind : Pane
    data object AllCourses : Pane
    data object Changes : Pane
    data object Theme : Pane
    data object Menus : Pane
    data object Echo : Pane
    data object EchoEditor : Pane
    data object Widget : Pane
    data object Fee : Pane
    data class Detail(val course: Course) : Pane
    data class EditMenu(val course: Course) : Pane
    data class Rename(val course: Course) : Pane
    data class Move(val course: Course) : Pane

    /** 课表页的账号切换弹层 */
    data object Accounts : Pane
}
@Composable
internal fun BoardScreen(
    prefs: Prefs,
    base: Timetable,
    edits: EditLayer,
    refreshing: Boolean,
    notice: String?,
    noticeOk: Boolean,
    onRefresh: () -> Unit,
    onEdit: (EditOp) -> Unit,
    onClearFor: (String) -> Unit,
    onClearEdits: () -> Unit,
    onImport: (Timetable, List<String>) -> Unit,
    onUseServer: () -> Unit,
    onDropImported: () -> Unit,
    onLogout: () -> Unit,
    onLoginAgain: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val tt = base.withCourses(edits.apply(base.courses))
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 课表一变（打开 App / 更新课表 / 改过课）就重排提醒：
    // 系统闹钟开机和升级时会被清空、条数也有上限，所以每次进 App 重排一次最稳。
    LaunchedEffect(tt, prefs.remindEnabled, prefs.remindMinutes) {
        withContext(Dispatchers.IO) { Reminder.reschedule(ctx, tt, prefs) }
    }

    // 课表 / 主题一变就刷新桌面小组件：小组件读的是同一份本地缓存，
    // 这里只是让它"立刻"跟上，不必等半小时一次的兜底闹钟。
    // 账号边界（切号 / 退出）不在这里 —— 那条路可能根本走不到课表页，由 Root 里显式刷。
    LaunchedEffect(tt, prefs.theme) {
        HaueWidget.refresh(ctx)
    }

    // 当前时间（每 30 秒刷新一次，用于「现在」指示线）
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(30_000) }
    }
    val today = now.toLocalDate()
    val todayWeek = WeekUtils.weekOf(tt.term, today)
    val todayDow = WeekUtils.dowOf(today)

    val maxWeek = maxOf(tt.term.maxWeek, tt.maxWeekInUse, 1)
    var week by remember(tt.term.startDate, tt.term.maxWeek) {
        mutableStateOf(todayWeek ?: prefs.lastWeek.takeIf { it in 1..maxWeek } ?: 1)
    }
    var selectedDay by remember { mutableStateOf(todayDow) }
    var pane by remember { mutableStateOf<Pane?>(null) }
    var busyMsg by remember { mutableStateOf<String?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }

    // 本地操作横幅（导出 / 导入等结果）也 3 秒后自动消失——
    // 它和 screen 里的 notice 是两个状态，只处理 notice 会漏掉这一半。
    LaunchedEffect(localNotice) {
        if (localNotice != null) {
            delay(3000)
            localNotice = null
        }
    }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingPng by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    // 导入：选文件 → 解析 → 落盘
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyMsg = "正在解析文件…"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw TimetableCodec.ImportException("读不到文件内容")
                    TimetableCodec.parse(text, tt.term)
                }.onFailure { e -> Diag.record("MainActivity.BoardScreen", e) }
            }
            busyMsg = null
            r.fold(
                onSuccess = { res -> pane = null; onImport(res.tt, res.warnings) },
                onFailure = { e -> localNotice = "导入失败：" + (e.message ?: "文件格式不支持") },
            )
        }
    }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val p = pendingExport
        pendingExport = null
        if (uri == null || p == null) { busyMsg = null; return@rememberLauncherForActivityResult }
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(p.second.toByteArray(Charsets.UTF_8)) }
            true
        }.onFailure { e -> Diag.record("MainActivity.BoardScreen", e) }.getOrDefault(false)
        pane = null
        busyMsg = null
        localNotice = if (ok) "已导出：${p.first}" else "导出失败，换个位置再试"
    }

    // 图片导出单独一条通道：SAF 需要按 MIME 声明，PNG 不能走 text/plain
    val createImg = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val p = pendingPng
        pendingPng = null
        if (uri == null || p == null) { busyMsg = null; return@rememberLauncherForActivityResult }
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(p.second) }
            true
        }.onFailure { e -> Diag.record("MainActivity.BoardScreen", e) }.getOrDefault(false)
        pane = null
        busyMsg = null
        localNotice = if (ok) "已导出图片：${p.first}" else "导出失败，换个位置再试"
    }

    Box(
        Modifier.fillMaxSize().background(C.screenBg)
            // 左右滑动切换周次。必须挂最外层并用 Initial 传递阶段：网格自己做了一层横向滚动，
            // 子级滚动容器在事件派发中永远先于父级拿到拖动，挂在里面根本收不到。
            // 这里只观察位移、不消费事件，所以卡片点击、内部滚动、沉浸式弹层（pane != null 时直接退出）都不受影响。
            .pointerInput(pane, week, maxWeek) {
                if (pane != null) return@pointerInput
                val threshold = 56.dp.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dx = 0f
                        var dy = 0f
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            dx += ch.positionChange().x
                            dy += ch.positionChange().y
                        }
                        // 横向意图要压过纵向，否则上下滚动课表会误切周次
                        if (abs(dx) > abs(dy)) {
                            val nw = swipeWeek(week, dx, threshold, maxWeek)
                            if (nw != week) { week = nw; prefs.lastWeek = nw }
                        }
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── 标题栏 ──
            Row(
                Modifier.fillMaxWidth().background(C.card).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("课程表", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.fg)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (tt.source == Source.IMPORTED) C.warnBg else C.accentTint)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (tt.source == Source.IMPORTED) "导入" else "教务",
                                fontSize = 10.sp,
                                color = if (tt.source == Source.IMPORTED) C.warnFg else C.accent,
                            )
                        }
                        if (refreshing) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                        }
                    }
                    Text(
                        listOf(
                            tt.term.title,
                            tt.student.name.ifBlank { "" },
                            tt.student.className.ifBlank { tt.student.id },
                            if (tt.source == Source.SERVER && prefs.timetableSavedAt > 0)
                                "存于 " + tsText(prefs.timetableSavedAt) else "",
                        ).filter { it.isNotBlank() }.joinToString("　"),
                        fontSize = 11.sp, color = C.dim,
                    )
                }
                Text(
                    "切换", fontSize = 12.5.sp, color = C.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.accentTint)
                        .clickable { pane = Pane.Accounts }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }

            // ── 周次栏 ──
            Row(
                Modifier.fillMaxWidth().background(C.card).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Arrow("‹", enabled = week > 1) { week = (week - 1).coerceAtLeast(1); prefs.lastWeek = week }
                Box(
                    Modifier.padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { pane = Pane.Weeks }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("第 $week 周", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.fg)
                        Spacer(Modifier.width(4.dp))
                        Text("▾", fontSize = 11.sp, color = C.dim)
                        if (week == todayWeek) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.accent).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                Text("本周", fontSize = 9.sp, color = Color.White)
                            }
                        }
                    }
                }
                Arrow("›", enabled = week < maxWeek) { week = (week + 1).coerceAtMost(maxWeek); prefs.lastWeek = week }
                Spacer(Modifier.weight(1f))
                Text(
                    weekRangeText(tt.term, week),
                    fontSize = 11.5.sp, color = C.dim,
                )
                if (todayWeek != null && week != todayWeek) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "回到本周", fontSize = 11.5.sp, color = C.accent,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.accentTint)
                            .clickable { week = todayWeek; prefs.lastWeek = todayWeek }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            val shownNotice = localNotice ?: notice
            if (shownNotice != null) {
                Text(
                    shownNotice, fontSize = 11.5.sp,
                    color = if (localNotice == null && noticeOk) C.okFg else C.danger,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (localNotice == null && noticeOk) C.okBg else C.dangerBg)
                        .clickable { localNotice = null }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }

            // ── 课表主体 ──
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val dayW: Dp = ((maxWidth - GridMetrics.leftWidth) / 7).coerceAtLeast(46.dp)
                    val hScroll = rememberScrollState()
                    Column {
                        DayHeader(tt.term, week, selectedDay, todayWeek, todayDow, dayW, hScroll) { selectedDay = it }
                        Grid(
                            tt, week, selectedDay, todayWeek, todayDow, dayW, hScroll, now,
                            onTap = { pane = Pane.Detail(it) },
                            onLong = { pane = Pane.EditMenu(it) },
                        )
                    }
                }
                PracticeRow(tt, week) { pane = Pane.Detail(it) }
                if (!tt.hasClass(week)) {
                    Text(
                        "第 $week 周没有课",
                        fontSize = 12.5.sp, color = C.dim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    )
                }
                if (!edits.isEmpty) {
                    Text(
                        "本地有 ${edits.ops.size} 条修改（长按课程可继续编辑）",
                        fontSize = 11.sp, color = C.warnFg,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── 底部栏 ──
            Row(Modifier.fillMaxWidth().background(C.card).padding(vertical = 10.dp)) {
                BottomItem("↻", "更新课表", Modifier.weight(1f)) { onRefresh() }
                Divider()
                BottomItem("＋", "导入/编辑", Modifier.weight(1f)) { pane = Pane.Import }
                Divider()
                BottomItem("≡", "常用功能", Modifier.weight(1f)) { pane = Pane.Tools }
            }
        }

        busyMsg?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(C.card).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(msg, fontSize = 13.sp, color = C.fg)
                }
            }
        }

        when (val p = pane) {
            null -> {}
            is Pane.Weeks -> WeeksPane(maxWeek, week, todayWeek, prefs) { week = it; pane = null }
            is Pane.Tools -> ToolsPane(
                onScores = { pane = Pane.Scores },
                onExams = { pane = Pane.Exams },
                onTheme = { pane = Pane.Theme },
                onWeeks = { pane = Pane.Weeks },
                onChanges = { pane = Pane.Changes },
                onRemind = { pane = Pane.Remind },
                onWidget = { pane = Pane.Widget },
                onEcho = { pane = Pane.Echo },
                onFee = { pane = Pane.Fee },
                onAccounts = { pane = Pane.Accounts },
                onLoginAgain = { pane = null; onLoginAgain() },
                onLogout = { pane = null; onLogout() },
                onClose = { pane = null },
            )
            is Pane.Scores -> ScoresPane { pane = null }
            is Pane.Exams -> ExamsPane { pane = null }
            is Pane.Import -> ImportPane(
                tt = tt, edits = edits,
                week = week, maxWeek = maxWeek,
                onPickFile = { openDoc.launch(arrayOf("*/*")) },
                onExport = { name, content -> pendingExport = name to content; createDoc.launch(name) },
                onExportImage = { name, bytes -> pendingPng = name to bytes; createImg.launch(name) },
                onUseServer = { pane = null; onUseServer() },
                onDropImported = { pane = null; onDropImported() },
                onClearEdits = { pane = null; onClearEdits() },
                onChanges = { pane = Pane.Changes },
                onClose = { pane = null },
            )
            is Pane.Changes -> ChangesPane(
                edits, base,
                onClearFor = { key -> pane = null; onClearFor(key) },
                onClearAll = { pane = null; onClearEdits() },
                onClose = { pane = null },
            )
            is Pane.Remind -> RemindPane(prefs, tt) { pane = null }
            is Pane.AllCourses -> AllCoursesPane(tt) { pane = null }
            is Pane.Menus -> MenusPane(prefs.menus) { pane = null }
            is Pane.Theme -> ThemePane(
                prefs,
                onPick = { id ->
                    prefs.theme = id
                    C.palette = C.byId(id)
                    // 小组件的颜色是渲染时现取的，得推它一把才会跟着换
                    HaueWidget.refresh(ctx)
                },
                onClose = { pane = null },
            )
            is Pane.Widget -> WidgetPane(
                prefs = prefs,
                onEcho = { pane = Pane.Echo },
                onClose = { pane = null; HaueWidget.refresh(ctx) },
            )
            is Pane.Echo -> EchoPane(
                prefs = prefs,
                onEdit = { pane = Pane.EchoEditor },
            ) { pane = null; HaueWidget.refresh(ctx) }
            is Pane.EchoEditor -> EchoEditorPane { pane = null; HaueWidget.refresh(ctx) }
            is Pane.Fee -> FeePane(prefs) { pane = null }
            is Pane.Accounts -> AccountPane(
                prefs = prefs,
                current = tt.student.id.ifBlank { prefs.username },
                onSwitch = { id -> pane = null; onSwitchAccount(id) },
                onAdd = { pane = null; onAddAccount() },
                onClose = { pane = null },
            )
            is Pane.Detail -> DetailPane(
                p.course, week,
                onEdit = { pane = Pane.EditMenu(p.course) },
                onClose = { pane = null },
            )
            is Pane.EditMenu -> EditMenuPane(
                course = p.course, week = week, edits = edits,
                onRename = { pane = Pane.Rename(p.course) },
                onMove = { pane = Pane.Move(p.course) },
                onOp = { op -> pane = null; onEdit(op) },
                onClearFor = { pane = null; onClearFor(p.course.key) },
                onClose = { pane = null },
            )
            is Pane.Rename -> RenamePane(
                p.course, edits,
                onDone = { newName -> pane = null; onEdit(EditOp(EditKind.RENAME, p.course.key, name = newName)) },
                onClose = { pane = null },
            )
            is Pane.Move -> MovePane(p.course, week, onDone = { op -> pane = null; onEdit(op) }, onClose = { pane = null })
        }
    }

    BackHandler(enabled = pane != null) { pane = null }
}
internal fun weekRangeText(term: Term, week: Int): String {
    val a = WeekUtils.dateOf(term, week, 1) ?: return ""
    val b = WeekUtils.dateOf(term, week, 7) ?: return ""
    return "${a.monthValue}月${a.dayOfMonth}日 - ${b.monthValue}月${b.dayOfMonth}日"
}
internal fun tsText(at: Long): String =
    if (at <= 0L) "上次"
    else java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")
        .format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault()))

// ────────────────────────── 网格 ──────────────────────────

object GridMetrics {
    val slot: Dp = 54.dp
    val leftWidth: Dp = 44.dp
}
@Composable
internal fun DayHeader(
    term: Term, week: Int, selectedDay: Int, todayWeek: Int?, todayDow: Int,
    dayW: Dp, hScroll: androidx.compose.foundation.ScrollState, onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(C.card).horizontalScroll(hScroll)) {
        Box(Modifier.width(GridMetrics.leftWidth).height(44.dp))
        for (d in 1..7) {
            val date = WeekUtils.dateOf(term, week, d)
            val prev = if (d > 1) WeekUtils.dateOf(term, week, d - 1) else null
            val isSelected = d == selectedDay
            val isToday = week == todayWeek && d == todayDow
            // 跨月提示：每周一显示月份；月份发生变化的那天也显示
            val monthTag = when {
                date == null -> ""
                d == 1 -> "${date.monthValue}月"
                prev != null && prev.monthValue != date.monthValue -> "${date.monthValue}月"
                else -> ""
            }
            Column(
                Modifier.width(dayW).height(44.dp)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) C.accentTint else Color.Transparent)
                    .clickable { onSelect(d) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        WeekUtils.WEEK_LABELS[d - 1].removePrefix("周"),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) C.accent else C.dim,
                    )
                    if (monthTag.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(monthTag, fontSize = 8.5.sp, color = C.dim)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(50))
                        .background(if (isToday) C.accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date?.dayOfMonth?.toString() ?: "--",
                        fontSize = 12.5.sp,
                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) Color.White else C.fg,
                    )
                }
            }
        }
    }
}
@Composable
internal fun Grid(
    tt: Timetable, week: Int, selectedDay: Int, todayWeek: Int?, todayDow: Int,
    dayW: Dp, hScroll: androidx.compose.foundation.ScrollState, now: LocalDateTime,
    onTap: (Course) -> Unit, onLong: (Course) -> Unit,
) {
    val gridH = GridMetrics.slot * Slots.COUNT
    val isTodayCol = week == todayWeek
    val nowSlot = if (isTodayCol) Slots.nowSlot(now.toLocalTime()) else null

    Row(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
        // 左侧节次 + 时间（「中」「晚」标记画在这一列，正好落在节次之间的空档里）
        Box(Modifier.width(GridMetrics.leftWidth)) {
            Column(Modifier.fillMaxWidth()) {
                Slots.ALL.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth().height(GridMetrics.slot),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("${s.index}", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (nowSlot == s.index && isTodayCol) C.accent else C.fg)
                        Text(s.begin, fontSize = 8.5.sp, color = C.dim)
                        Text(s.end, fontSize = 8.5.sp, color = C.dim)
                    }
                }
            }
            listOf(4 to ("中" to C.noon), 8 to ("晚" to C.night)).forEach { (after, mark) ->
                val (label, col) = mark
                Box(
                    Modifier
                        .offset(x = 6.dp, y = GridMetrics.slot * after - 7.dp)
                        .size(32.dp, 14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(col.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 9.5.sp, color = col, fontWeight = FontWeight.Medium)
                }
            }
        }

        for (d in 1..7) {
            Box(
                Modifier.width(dayW).height(gridH)
                    .background(
                        when {
                            week == todayWeek && d == todayDow -> C.todayBg
                            d == selectedDay -> C.selBg
                            else -> C.card
                        }
                    )
            ) {
                // 节次分隔线：绝对定位画在 slot 边界上。
                // 关键：不能让分隔线的高度参与布局累加——否则每画一条线，后面的线就往下漂
                // 0.5~1dp，到第 4/8 节就和「中」「晚」标记、课程卡片边缘错开（视觉上不平齐）。
                for (i in 1 until Slots.COUNT) {
                    val strong = i == 4 || i == 8
                    val h = if (strong) 1.dp else 0.5.dp
                    Box(
                        Modifier.offset(y = GridMetrics.slot * i - h / 2)
                            .fillMaxWidth().height(h)
                            .background(if (strong) C.lineStrong else C.line)
                    )
                }
                // 「中」在 4、5 节之间，「晚」在 8、9 节之间。
                // 画在课程卡片下面——跨节次的课（比如 5-8 节）不会被一条线从中间切开。
                listOf(4 to C.noon, 8 to C.night).forEach { (after, col) ->
                    Box(
                        Modifier.offset(y = GridMetrics.slot * after - 1.dp)
                            .fillMaxWidth().height(2.dp)
                            .background(col.copy(alpha = 0.55f))
                    )
                }
                // 当前时间指示线
                if (nowSlot != null && d == todayDow) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = GridMetrics.slot * (nowSlot - 1) + 14.dp)
                            .height(1.5.dp).background(C.danger)
                    )
                }
                // 课程卡片
                tt.coursesOf(week, d).forEach { c ->
                    val start = (c.startSlot - 1).coerceIn(0, Slots.COUNT - 1)
                    val span = c.slotSpan.coerceAtMost(Slots.COUNT - start)
                    // 卡片：渐变底 + 柔和投影 + 12dp 圆角，文字分三层（课名/教室/教师）
                    val (fillA, fillB) = CourseColors.fillColors(c.name)
                    val (barA, barB) = CourseColors.barColors(c.name)
                    val inkCol = CourseColors.ink(c.name)
                    Box(
                        Modifier
                            .padding(horizontal = 2.5.dp)
                            .offset(y = GridMetrics.slot * start)
                            .height(GridMetrics.slot * span - 4.dp)
                            .fillMaxWidth()
                            .shadow(2.5.dp, RoundedCornerShape(12.dp), clip = false)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(fillA, fillB)))
                            // 暗色底下「深色相卡片 + 深底」会糊在一起，用极淡描边保证分离
                            .then(
                                if (C.palette.dark)
                                    Modifier.border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
                                else Modifier
                            )
                            .pointerInput(c.occKey) {
                                detectTapGestures(onTap = { onTap(c) }, onLongPress = { onLong(c) })
                            }
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.width(4.dp).fillMaxHeight()
                                    .background(Brush.verticalGradient(listOf(barA, barB)))
                            )
                            Column(Modifier.padding(start = 6.dp, end = 5.dp, top = 5.dp, bottom = 4.dp)) {
                                Text(
                                    c.name,
                                    fontSize = 11.sp, lineHeight = 13.sp,
                                    fontWeight = FontWeight.Bold, color = inkCol,
                                    // 窄列（7 天分屏后每列约 50dp）字少，所以短跨节也给 3 行，
                                    // 否则长课名只剩两三个字就被省略号切掉
                                    maxLines = if (span >= 4) 4 else if (span >= 2) 3 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (c.room.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        c.room,
                                        fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium,
                                        color = inkCol.copy(alpha = 0.72f),
                                        // 教室号必须单行：拆成「7B40 / 1」比截断更难读
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (span >= 2 && c.teacher.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        c.teacher,
                                        fontSize = 8.5.sp, lineHeight = 10.sp, color = inkCol.copy(alpha = 0.62f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(0.5.dp).height(gridH).background(C.line))
        }
    }
}
@Composable
internal fun PracticeRow(tt: Timetable, week: Int, onTap: (Course) -> Unit) {
    val list = tt.practicesOf(week)
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        list.forEach { c ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(C.warnBg)
                    .clickable { onTap(c) }.padding(10.dp)
            ) {
                Text("集中实践：${c.name}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.warnFg)
                Text(
                    listOf(c.teacher, c.weekText).filter { it.isNotBlank() }.joinToString("　"),
                    fontSize = 11.sp, color = C.warnFg,
                )
            }
        }
    }
}
@Composable
internal fun Divider() {
    Box(Modifier.width(0.5.dp).height(28.dp).background(C.line))
}
@Composable
internal fun BottomItem(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clickable { onClick() }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 14.sp, color = C.accent)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 13.sp, color = C.fg)
    }
}
