package com.cxywh.hauejw

// 设置面板与通用小组件。
// 从 MainActivity 拆出来：主文件只留 Activity、Root 与课表主体，这里放侧栏各面板。
// 这些组件用到的私有辅助已在各自文件内放开为 internal。

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.app.NotificationManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
internal fun PaneShell(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    // 让开键盘：窗口已被系统按输入法高度缩过，但输入法自己画的圆角与阴影还会再压住底边约 41px
    // （实测：面板底边 1548，输入法可见上沿 1507）。面板本身永远贴着底部，直接留出这段余量。
    Box(Modifier.fillMaxSize().imePadding().padding(bottom = 20.dp)) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onClose() })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(shape)
                .background(C.card)
                .then(if (C.palette.glass) Modifier.border(1.dp, C.cardBorder, shape) else Modifier)
                .heightIn(max = 620.dp)
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

@Composable
internal fun PaneTitle(text: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.fg)
        Spacer(Modifier.weight(1f))
        Text("关闭", fontSize = 12.5.sp, color = C.dim,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClose() }.padding(6.dp))
    }
}

@Composable
internal fun RowItem(title: String, sub: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp)
    ) {
        Text(title, fontSize = 14.sp, color = if (danger) C.danger else C.fg, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, fontSize = 11.sp, color = C.dim)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.line))
}

@Composable
internal fun Chip(text: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Text(
        text, fontSize = if (compact) 12.sp else 12.5.sp,
        color = if (selected) Color.White else C.fg,
        modifier = Modifier.padding(end = if (compact) 5.dp else 6.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) C.accent else C.bg)
            .clickable { onClick() }
            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 7.dp),
    )
}

@Composable
internal fun WeeksPane(maxWeek: Int, current: Int, todayWeek: Int?, prefs: Prefs, onPick: (Int) -> Unit) {
    PaneShell({ onPick(current) }) {
        PaneTitle("切换周次", { onPick(current) })
        Column {
            for (rowStart in 1..maxWeek step 5) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    for (w in rowStart until minOf(rowStart + 5, maxWeek + 1)) {
                        val isCurrent = w == current
                        val isThisWeek = w == todayWeek
                        Column(
                            Modifier.weight(1f).padding(end = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) C.accentTint else C.bg)
                                .clickable { prefs.lastWeek = w; onPick(w) }
                                .padding(vertical = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "$w",
                                fontSize = 13.5.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) C.accent else C.fg,
                            )
                            Text(
                                if (isThisWeek) "本周" else " ",
                                fontSize = 8.5.sp, color = C.accent,
                            )
                        }
                    }
                    for (w in minOf(rowStart + 5, maxWeek + 1)..rowStart + 4) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ─────────────────────── 常用功能：分组卡片 ───────────────────────

/** 一张功能卡：标题 + 一句话说明，整块可点 */
private data class Tool(
    val title: String, val sub: String, val danger: Boolean = false, val onClick: () -> Unit,
)

@Composable
private fun ToolCard(t: Tool, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (t.danger) C.dangerBg else C.card)
            .border(1.dp, if (t.danger) Color.Transparent else C.cardBorder, RoundedCornerShape(12.dp))
            .clickable { t.onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(
            t.title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
            color = if (t.danger) C.danger else C.fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            t.sub, fontSize = 11.sp, color = C.dim,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
        )
    }
}

/** 两列铺卡片；一组里剩单张时让它占满整行，别在右边留一块空 */
@Composable
private fun ToolGrid(items: List<Tool>) {
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            ToolCard(row[0], Modifier.weight(1f))
            if (row.size > 1) {
                Spacer(Modifier.width(8.dp))
                ToolCard(row[1], Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text, fontSize = 11.5.sp, color = C.dim,
        modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 7.dp),
    )
}

@Composable
internal fun ToolsPane(
    onTheme: () -> Unit, onWeeks: () -> Unit, onChanges: () -> Unit, onRemind: () -> Unit,
    onScores: () -> Unit,
    onExams: () -> Unit,
    onAccounts: () -> Unit,
    onWidget: () -> Unit,
    onEcho: () -> Unit,
    onLoginAgain: () -> Unit, onLogout: () -> Unit, onClose: () -> Unit,
) {
    PaneShell(onClose) {
        PaneTitle("常用功能", onClose)

        GroupLabel("学习")
        ToolGrid(
            listOf(
                Tool("我的成绩", "GPA、学分、成绩明细", onClick = onScores),
                Tool("考试信息", "时间、地点、座位", onClick = onExams),
                Tool("周次总览", "整学期每周怎么排", onClick = onWeeks),
                Tool("本地修改", "改课名、去掉某次课", onClick = onChanges),
                Tool("课前提醒", "提前多久、怎么提醒", onClick = onRemind),
            )
        )

        GroupLabel("桌面")
        ToolGrid(
            listOf(
                Tool("桌面小组件", "开关、形态、添加", onClick = onWidget),
                Tool("回声洞", "底部那句话、编辑内容", onClick = onEcho),
            )
        )

        GroupLabel("账号与外观")
        ToolGrid(
            listOf(
                Tool("外观主题", "浅色、深色", onClick = onTheme),
                Tool("切换账号", "多个账号各存各的", onClick = onAccounts),
                Tool("重新登录", "清掉登录态再登一次", onClick = onLoginAgain),
                Tool("退出并清除缓存", "本地修改会保留", danger = true, onClick = onLogout),
            )
        )
    }
}

// ─────────────────────── 桌面小组件 ───────────────────────

/** 渲染时记下的实际尺寸（"320x165|110x110"，dp）→ 给人看的一句话；空串返回 null */
private fun sizeText(raw: String): String? {
    val pairs = raw.split("|").mapNotNull { s ->
        val p = s.split("x")
        val w = p.getOrNull(0)?.toIntOrNull()
        val h = p.getOrNull(1)?.toIntOrNull()
        if (w != null && h != null) "${dpToCells(w)}×${dpToCells(h)} 格" to "$w×$h dp" else null
    }
    if (pairs.isEmpty()) return null
    return "桌面上的卡片现在是 " + pairs.joinToString("、") { it.first + "（约 " + it.second + "）" }
}

/** 卡片画法选项：id、显示名、要请桌面调成的格宽（0 = 不动桌面上的尺寸，只换画法） */
private val WIDGET_FORMS = listOf(
    Triple("auto", "自动", 0),
    Triple("wide", "4×2 详细", 4),
    Triple("small", "2×2 简版", 2),
)

@Composable
internal fun WidgetPane(prefs: Prefs, onEcho: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(prefs.widgetOn) }
    var form by remember { mutableStateOf(prefs.widgetForm) }
    var sizeHint by remember { mutableStateOf(sizeText(prefs.lastWidgetSize)) }

    PaneShell(onClose) {
        PaneTitle("桌面小组件", onClose)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("启用小组件", fontSize = 13.5.sp, color = C.fg)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    on = v
                    prefs.widgetOn = v
                    HaueWidget.refresh(ctx)
                },
            )
        }
        Text(
            "关掉后不再定时刷新，卡片上显示「已关闭」；桌面上那一块要移除，请在桌面长按它。",
            fontSize = 11.sp, color = C.dim,
        )

        Spacer(Modifier.height(14.dp))
        Text("卡片画法", fontSize = 13.5.sp, color = C.fg)
        sizeHint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.5.sp, color = C.dim)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WIDGET_FORMS.forEach { (id, label, cells) ->
                Chip(label, form == id) {
                    form = id
                    prefs.widgetForm = id
                    HaueWidget.resize(ctx, cells) // 顺手请桌面调尺寸；「自动」是 0，不动它
                    HaueWidget.refresh(ctx)
                    sizeHint = sizeText(prefs.lastWidgetSize)
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(
            "「自动」跟着桌面上的实际宽度走：窄到 2 格就自动变简版。" +
                "想改桌面那块的大小，长按卡片拖它的边或角——系统不给应用这个权限，只能手拖；" +
                "拖成 2 格后，「自动」会跟着换成简版。",
            fontSize = 11.sp, color = C.dim,
        )

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { HaueWidget.pin(ctx) },
            colors = appButtonColors(),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("添加到桌面") }
        Spacer(Modifier.height(5.dp))
        Text("桌面上没有它时点这里，系统会弹出添加面板。", fontSize = 11.sp, color = C.dim)

        Spacer(Modifier.height(12.dp))
        RowItem("回声洞", "小组件底部那句话：开关与内容编辑", onClick = onEcho)
    }
}

// ─────────────────────── 回声洞：设置 ───────────────────────

@Composable
internal fun EchoPane(prefs: Prefs, onEdit: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(prefs.echoOn) }
    var note by remember { mutableStateOf<String?>(null) }
    val count = remember { Echo.parse(Echo.readRaw(ctx)).size }

    PaneShell(onClose) {
        PaneTitle("回声洞", onClose)
        Text("在课表小组件底部单独占一块，轮流显示你自己写的话。", fontSize = 11.5.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("显示回声洞", fontSize = 13.5.sp, color = C.fg)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    on = v
                    prefs.echoOn = v
                    HaueWidget.refresh(ctx)
                },
            )
        }
        Text("小组件总开关关着时也不显示（在「桌面小组件」里）。", fontSize = 11.sp, color = C.dim)

        Spacer(Modifier.height(14.dp))
        RowItem(
            "编辑内容",
            if (count == 0) "现在一句都识别不出来" else "已识别 $count 句",
            onClick = onEdit,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "换句节奏：先播约半秒的故障动画，整句显示完整后停 5 秒，然后换下一条。" +
                "长句子会自动缩小字号，把小组件拖高一点能放更大的字。",
            fontSize = 11.5.sp, color = C.dim,
        )

        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { HaueWidget.echoNow(ctx); note = "已让小组件换一条（会播一次故障动画）" },
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("立刻换一条", color = C.accent) }
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.5.sp, color = C.okFg)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "文件位置（也可以用文件管理器直接改）：\nAndroid/data/com.cxywh.hauejw/files/" + Echo.FILE_NAME,
            fontSize = 10.5.sp, color = C.dim,
        )
    }
}

// ─────────────────────── 回声洞：内容编辑 ───────────────────────

@Composable
internal fun EchoEditorPane(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(Echo.readRaw(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }
    val list = remember(text) { Echo.parse(text) }

    // 文件不在（装完还没保存过）就先按包内默认内容建出来，用户去文件管理器能直接找到它
    LaunchedEffect(Unit) { Echo.ensureFile(ctx) }

    PaneShell(onClose) {
        PaneTitle("编辑回声洞内容", onClose)

        Text(
            "写法：\"要显示的内容\";   引号里才是正文，分号或换行断句。\n" +
                "中英文引号和分号都认，也认「」；整句一个引号都没写就整句照显。",
            fontSize = 11.sp, color = C.dim,
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("jwzs.txt") },
            // 收在 8~12 行：键盘弹起时框里的字、「已识别 N 句」和保存按钮能一起留在键盘上方，
            // 边改边看得见（整块面板会随键盘上移）；再多就靠框内自己滚
            minLines = 8, maxLines = 12,
            colors = appFieldColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (list.isEmpty()) "现在一句也识别不出来" else "已识别 ${list.size} 句",
            fontSize = 11.5.sp, color = if (list.isEmpty()) C.warnFg else C.dim,
        )
        list.take(2).forEach { s ->
            Text("· " + s, fontSize = 11.sp, color = C.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.5.sp, color = C.okFg)
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = {
                    val cut = Echo.save(ctx, text)
                    note = if (cut) "已保存（超出上限的部分被截掉了）" else "已保存，小组件马上换用新内容"
                    HaueWidget.refresh(ctx)
                },
                colors = appButtonColors(),
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text("保存") }
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = {
                    val d = Echo.defaultText(ctx)
                    Echo.save(ctx, d)
                    text = d
                    note = "已恢复成默认内容"
                    HaueWidget.refresh(ctx)
                },
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text("恢复默认", color = C.dim) }
        }
    }
}

@Composable
internal fun ImportPane(
    tt: Timetable, edits: EditLayer,
    week: Int, maxWeek: Int,
    onPickFile: () -> Unit,
    onExport: (String, String) -> Unit,
    onExportImage: (String, ByteArray) -> Unit,
    onUseServer: () -> Unit,
    onDropImported: () -> Unit,
    onClearEdits: () -> Unit,
    onChanges: () -> Unit,
    onClose: () -> Unit,
) {
    PaneShell(onClose) {
        PaneTitle("导入 / 导出 / 编辑", onClose)
        Text(
            "当前显示：" + if (tt.source == Source.IMPORTED) "导入的课表（${tt.courses.size} 节）" else "教务系统课表（${tt.courses.size} 节）",
            fontSize = 12.sp, color = C.dim,
        )
        Spacer(Modifier.height(10.dp))
        RowItem("从文件导入课表", "支持 JSON / CSV / ICS", onClick = onPickFile)
        RowItem("导出为图片", onClick = {
            runCatching { TimetableImage.render(tt, week, maxWeek) }.onFailure { e -> Diag.record("Panes.ImportPane", e) }
                .onSuccess { onExportImage("课表-第${week}周.png", it) }
        })
        RowItem("导出为 JSON", "可被本 App 无损再导入",
            onClick = { onExport("课表.json", TimetableCodec.toJson(tt)) })
        Spacer(Modifier.height(6.dp))
        RowItem("本地修改（${edits.ops.size} 条）", onClick = onChanges)
        RowItem("清空本地修改", "恢复成原始课表", danger = true, onClick = onClearEdits)
        if (tt.source == Source.IMPORTED) {
            RowItem("切回教务系统课表", null, onClick = onUseServer)
        } else {
            RowItem("删除导入的课表", danger = true, onClick = onDropImported)
        }
    }
}

@Composable
internal fun ChangesPane(edits: EditLayer, base: Timetable, onClearFor: (String) -> Unit, onClearAll: () -> Unit, onClose: () -> Unit) {
    PaneShell(onClose) {
        PaneTitle("本地修改（${edits.ops.size} 条）", onClose)
        if (edits.isEmpty) {
            Text("还没有做过修改。长按课表里的任意一节课即可改名、移除或调时间。", fontSize = 12.5.sp, color = C.dim)
        } else {
            val names = base.courses.associate { it.key to it.name }
            edits.ops.groupBy { it.key }.forEach { (key, list) ->
                Text(names[key] ?: key, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = C.fg)
                list.forEach { op ->
                    Text("· " + op.describe(names[key] ?: "该课程"), fontSize = 11.5.sp, color = C.dim)
                }
                Text(
                    "撤销这门课的修改", fontSize = 12.sp, color = C.danger,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(C.dangerBg)
                        .clickable { onClearFor(key) }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(6.dp))
            RowItem("清空全部修改", null, danger = true, onClick = onClearAll)
        }
    }
}

@Composable
internal fun RemindPane(prefs: Prefs, tt: Timetable?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(prefs.remindEnabled) }
    var minutes by remember { mutableStateOf(prefs.remindMinutes) }
    var style by remember { mutableStateOf(prefs.remindStyle) }
    var note by remember { mutableStateOf<String?>(null) }
    var permitted by remember {
        mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled())
    }
    // Android 13+ 不申请 POST_NOTIFICATIONS 就默认关闭，开关开了也发不出来
    val askPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permitted = granted && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        note = if (permitted) "通知权限已开启。"
        else "系统没给通知权限，提醒发不出来 —— 可到「本应用通知设置」里手动打开。"
    }
    val buttonColors = appButtonColors()

    PaneShell(onClose) {
        PaneTitle("课前提醒", onClose)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开启提醒", fontSize = 14.sp, color = C.fg)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    if (on && !permitted) askPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
        }
        Spacer(Modifier.height(12.dp))

        Text("提醒方式", fontSize = 14.sp, color = C.fg)
        Spacer(Modifier.height(8.dp))
        Row {
            Chip("消息通知", style == Reminder.STYLE_NOTIFY) { style = Reminder.STYLE_NOTIFY }
            Spacer(Modifier.width(8.dp))
            Chip("闹钟", style == Reminder.STYLE_ALARM) { style = Reminder.STYLE_ALARM }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            if (style == Reminder.STYLE_ALARM) "到点整屏弹出并响铃，点「知道了」才消失。"
            else "到点只弹一条横幅通知，不打断你手头的事。",
            fontSize = 11.sp, color = C.dim,
        )
        Spacer(Modifier.height(14.dp))

        Text("提前时间", fontSize = 14.sp, color = C.fg)
        Spacer(Modifier.height(8.dp))
        Row {
            listOf(10, 20, 30, 45).forEach { m ->
                Chip("${m}分", minutes == m) { minutes = m }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 状态：明确说清"现在到底会不会响"，别让开关变成假的
        val n = remember(tt, minutes, style, enabled) {
            if (tt != null && enabled) Reminder.plan(tt, minutes, style).size else 0
        }
        val status = when {
            !enabled -> "当前关闭，不会提醒。"
            !permitted -> "开关已开，但系统没有通知权限，提醒发不出来。"
            n == 0 -> "已开启，但未来两周没排到课（课表可能还没加载）。"
            else -> "已开启：已排 $n 条提醒（未来两周），每节课各发一条。" +
                if (style == Reminder.STYLE_ALARM) "方式：闹钟（整屏 + 铃声）。"
                else "方式：消息（横幅通知）。"
        }
        Text(status, fontSize = 11.5.sp, color = if (enabled && !permitted) C.danger else C.dim)
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.5.sp, color = C.okFg)
        }
        Spacer(Modifier.height(12.dp))

        if (!permitted) {
            Button(
                onClick = { askPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
                colors = buttonColors,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) { Text("允许通知权限") }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (!permitted) askPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                else {
                    Reminder.testIn(ctx, prefs, style, 5)
                    note = if (style == Reminder.STYLE_ALARM)
                        "已排一条测试提醒：5 秒后会整屏弹出并响铃。"
                    else
                        "已排一条测试提醒：5 秒后会弹一条通知。收不到就是系统在拦（多半是省电策略）。"
                }
            },
            colors = buttonColors,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("发一条测试提醒（5 秒后）") }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                prefs.remindEnabled = enabled
                prefs.remindMinutes = minutes
                prefs.remindStyle = style
                Reminder.reschedule(ctx, tt, prefs)
                onClose()
            },
            colors = buttonColors,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("保存") }

        Spacer(Modifier.height(12.dp))
        Text(
            "提醒由系统闹钟唤醒后发通知，不需要 App 挂在后台，已排满未来两周。",
            fontSize = 11.sp, color = C.dim,
        )

        // 只在「全屏接管」确实没授权时才提示 —— 已授权就不占地方、不说废话
        if (Build.VERSION.SDK_INT >= 34 &&
            !NotificationManagerCompat.from(ctx).canUseFullScreenIntent()
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                "闹钟模式还差一项「全屏接管」授权：现在会亮屏响铃，但不会自动弹出界面。",
                fontSize = 11.sp, color = C.danger,
            )
            TextButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { e -> Diag.record("Panes.RemindPane", e) }
                },
            ) { Text("去允许全屏接管", fontSize = 12.sp, color = C.accent) }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { e -> Diag.record("Panes.RemindPane", e) }
            },
        ) { Text("打开本应用的通知设置", fontSize = 12.sp, color = C.accent) }
    }
}

@Composable
internal fun AllCoursesPane(tt: Timetable, onClose: () -> Unit) {
    PaneShell(onClose) {
        PaneTitle("本学期全部课程（${tt.courses.size}）", onClose)
        tt.courses.forEach { c ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(c.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = C.fg)
                Text(c.fullText, fontSize = 11.5.sp, color = C.dim)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.line))
        }
    }
}

@Composable
internal fun MenusPane(menus: Map<String, String>, onClose: () -> Unit) {
    PaneShell(onClose) {
        PaneTitle("学校菜单入口（${menus.size} 项）", onClose)
        if (menus.isEmpty()) {
            Text(
                "还没抓到。登录成功后 App 会自动解析首页菜单并存下来；\n抓不到时「常用页面直达」用通用入口，可能对不上学校版本。",
                fontSize = 12.sp, color = C.dim,
            )
        } else {
            Text("这些是登录时从学校首页菜单里抓到的真实入口，直达页就用它们。", fontSize = 11.5.sp, color = C.dim)
            Spacer(Modifier.height(8.dp))
            menus.entries.sortedBy { it.key }.forEach { (name, path) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.fg)
                    Text(path, fontSize = 10.5.sp, color = C.dim)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.line))
            }
        }
    }
}

@Composable
internal fun ThemePane(prefs: Prefs, onPick: (String) -> Unit, onClose: () -> Unit) {
    val cur = prefs.theme
    PaneShell(onClose) {
        PaneTitle("外观主题", onClose)
        Text("选中立即生效，会记住选择。", fontSize = 11.5.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        Palettes.all.forEach { p ->
            val on = p.id == cur
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) C.accentTint else C.bg)
                    .clickable { onPick(p.id) }
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(p.bg, p.card, p.accent).forEach { c ->
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(c))
                        Spacer(Modifier.width(4.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(p.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.fg)
                    if (on) {
                        Spacer(Modifier.width(6.dp))
                        Text("使用中", fontSize = 10.sp, color = C.accent)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(p.desc, fontSize = 11.sp, color = C.dim)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "主题只影响显示，不影响课表数据。",
            fontSize = 11.sp, color = C.dim,
        )
    }
}

@Composable
internal fun DetailPane(c: Course, week: Int, onEdit: () -> Unit, onClose: () -> Unit) {
    PaneShell(onClose) {
        PaneTitle(c.name, onClose)
        DetailLine("教师", c.teacher)
        DetailLine("教室", c.roomText)
        DetailLine("时间", if (c.isPractice) "集中实践（整周）" else "${c.timeText}（${Slots.of(c.startSlot)?.begin ?: ""}-${Slots.of(c.endSlot)?.end ?: ""}）")
        DetailLine("周次", c.weekText)
        if (c.credit.isNotBlank()) DetailLine("学分", c.credit)
        if (c.category.isNotBlank()) DetailLine("类别", c.category + c.kind)
        if (c.exam.isNotBlank()) DetailLine("考核", c.exam)
        if (c.className.isNotBlank()) DetailLine("教学班", c.className)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onEdit,
            colors = appButtonColors(),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("编辑这节课") }
    }
}

@Composable
internal fun DetailLine(k: String, v: String) {
    if (v.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, fontSize = 12.5.sp, color = C.dim, modifier = Modifier.width(64.dp))
        Text(v, fontSize = 12.5.sp, color = C.fg)
    }
}

@Composable
internal fun EditMenuPane(
    course: Course, week: Int, edits: EditLayer,
    onRename: () -> Unit, onMove: () -> Unit,
    onOp: (EditOp) -> Unit, onClearFor: () -> Unit, onClose: () -> Unit,
) {
    PaneShell(onClose) {
        PaneTitle("编辑这节课", onClose)
        Text(course.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.fg)
        Text(
            "第 ${week} 周 · ${course.timeText} · ${course.weekText}" + if (course.teacher.isNotBlank()) " · ${course.teacher}" else "",
            fontSize = 11.5.sp, color = C.dim,
        )
        Spacer(Modifier.height(12.dp))
        RowItem("重命名课程", "该课程所有周的显示名一起改", onClick = onRename)
        RowItem("从本周移除这节课", onClick = {
            onOp(EditOp(EditKind.REMOVE_THIS, course.key, week = week, day = course.dayOfWeek, start = course.startSlot, end = course.endSlot))
        })
        RowItem("从本周移除这门课", onClick = {
            onOp(EditOp(EditKind.REMOVE_WEEK_ALL, course.key, week = week))
        })
        RowItem("从本学期移除这门课", danger = true, onClick = {
            onOp(EditOp(EditKind.REMOVE_SEM, course.key))
        })
        RowItem("调整上课时间", "可选「只改这一次」或「本学期这门课全部」", onClick = onMove)
        if (edits.forKey(course.key).isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            RowItem("撤销这门课的本地修改", danger = true, onClick = onClearFor)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "这些改动只存在本机，不影响教务系统的数据；「导入/编辑 → 本地修改」里可随时撤销。",
            fontSize = 11.sp, color = C.dim,
        )
    }
}

@Composable
internal fun RenamePane(course: Course, edits: EditLayer, onDone: (String) -> Unit, onClose: () -> Unit) {
    val shown = edits.renamedKey(course.key) ?: course.name
    var text by remember { mutableStateOf(shown) }
    PaneShell(onClose) {
        PaneTitle("重命名课程", onClose)
        Text("原名：${course.name}", fontSize = 12.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("显示名称") }, singleLine = true,
            colors = appFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text("留空则保持原名；改名后课表里这门课的所有周都会显示新名字。", fontSize = 11.sp, color = C.dim)
        Spacer(Modifier.height(14.dp))
        Row {
            Button(
                onClick = { if (text.isNotBlank()) onDone(text.trim()) else onClose() },
                colors = appButtonColors(),
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text("保存") }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onClose, modifier = Modifier.weight(1f).height(44.dp)) { Text("取消", color = C.dim) }
        }
    }
}

@Composable
internal fun MovePane(course: Course, week: Int, onDone: (EditOp) -> Unit, onClose: () -> Unit) {
    val span = course.slotSpan.coerceAtLeast(1)
    var day by remember { mutableStateOf(course.dayOfWeek.coerceIn(1, 7)) }
    var start by remember { mutableStateOf(course.startSlot.coerceIn(1, Slots.COUNT)) }
    var all by remember { mutableStateOf(false) }
    val end = (start + span - 1).coerceAtMost(Slots.COUNT)

    PaneShell(onClose) {
        PaneTitle("调整上课时间", onClose)
        Text("${course.name} · ${course.timeText}（共 $span 节）", fontSize = 12.sp, color = C.dim)
        Spacer(Modifier.height(6.dp))
        Text("当前浏览的是第 $week 周", fontSize = 11.5.sp, color = C.warnFg)
        Spacer(Modifier.height(12.dp))
        Text("调到星期", fontSize = 13.sp, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Row {
            (1..7).forEach { d ->
                Chip(WeekUtils.WEEK_LABELS[d - 1].removePrefix("周"), day == d) { day = d }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("开始节次（占用第 $start-$end 节）", fontSize = 13.sp, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            (1..Slots.COUNT).forEach { s ->
                Chip("$s", start == s, compact = true) { start = s }
            }
        }
        if (start + span - 1 > Slots.COUNT) {
            Spacer(Modifier.height(4.dp))
            Text("注意：会超出第 ${Slots.COUNT} 节，已按最后一节截断。", fontSize = 11.sp, color = C.danger)
        }
        Spacer(Modifier.height(10.dp))
        Text("作用范围", fontSize = 13.sp, color = C.fg)
        Spacer(Modifier.height(6.dp))
        Row {
            Chip(if (all) "只改这一次" else "只改这一次（第 $week 周）", !all) { all = false }
            Chip("本学期此课全部", all) { all = true }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                val s = start.coerceAtMost(Slots.COUNT)
                if (all) {
                    onDone(EditOp(EditKind.MOVE_ALL, course.key, toDay = day, toStart = s))
                } else {
                    onDone(
                        EditOp(
                            EditKind.MOVE_ONCE, course.key, week = week,
                            day = course.dayOfWeek,
                            start = course.startSlot, end = course.endSlot,
                            toDay = day, toStart = s,
                        )
                    )
                }
            },
            colors = appButtonColors(),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text(if (all) "整学期都改到 ${WeekUtils.WEEK_LABELS[day - 1]} 第${start}节起" else "把第 $week 周这一次改到 ${WeekUtils.WEEK_LABELS[day - 1]} 第${start}节起") }
    }
}

// ────────────────────────── 常用页面直达 ──────────────────────────

@Composable
internal fun Arrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 18.sp,
        color = if (enabled) C.fg else C.lineStrong,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
internal fun AccountPane(
    prefs: Prefs,
    current: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val saved = mergeAccounts(prefs.accounts, SecretStore.accounts(ctx))
    val cur = if (current.isNotBlank()) current else prefs.username

    PaneShell(onClose) {
        PaneTitle("切换账号", onClose)
        Spacer(Modifier.height(4.dp))
        Text(
            "每个账号的课表缓存和本地修改各存各的，互不影响；成绩与考试按当前登录的账号实时查询。",
            fontSize = 11.5.sp, color = C.dim,
        )
        Spacer(Modifier.height(10.dp))
        if (saved.isEmpty()) {
            Text(
                "还没有记住其他账号。用下面的入口登录一次并勾选「记住密码」，之后就能在这里一键切换。",
                fontSize = 12.5.sp, color = C.dim,
            )
            Spacer(Modifier.height(10.dp))
        } else {
            saved.forEach { id ->
                val isCur = id == cur
                RowItem(
                    if (isCur) "$id（当前）" else id,
                    if (isCur) null else "点一下切换到该账号",
                    onClick = { if (!isCur) onSwitch(id) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        RowItem("用其他账号登录", "登录页勾选「记住密码」，下次可一键切换", onClick = onAdd)
        Spacer(Modifier.height(6.dp))
        Text(
            "不想再记住某个账号：登录页账号行右侧的「×」可以单独忘掉它。",
            fontSize = 11.sp, color = C.dim,
        )
    }
}
