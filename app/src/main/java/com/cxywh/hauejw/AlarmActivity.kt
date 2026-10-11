package com.cxywh.hauejw

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 「闹钟式」提醒的整屏界面 —— 由通知的 full-screen intent 拉起（锁屏也会亮屏弹出）。
 *
 * 必须自己站得住：不依赖课表、不依赖网络，要显示的信息全在 Intent 里。
 * 这就是它和「消息式」的本质区别：消息是一条横幅，你不理它就过去了；
 * 这是占屏提醒，你不点「知道了」它就一直在。
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 闹钟往往在清晨/夜里被拉起，进程可能刚起、C 还是默认亮色 —— 这里锁死暗色，不刺眼
        C.palette = Palettes.dark

        val name = intent.getStringExtra("name").orEmpty().ifBlank { "上课提醒" }
        val slot = intent.getStringExtra("slot").orEmpty()
        val time = intent.getStringExtra("start").orEmpty()
        val room = intent.getStringExtra("room").orEmpty()
        val teacher = intent.getStringExtra("teacher").orEmpty()
        val lead = intent.getIntExtra("lead", 0)

        setContent { AlarmScreen(name, slot, time, room, teacher, lead) { finish() } }
    }
}

@Composable
private fun AlarmScreen(
    name: String,
    slot: String,
    time: String,
    room: String,
    teacher: String,
    lead: Int,
    onOk: () -> Unit,
) {
    // 没人理会时自动收场，别一直亮着屏
    LaunchedEffect(Unit) { delay(90_000); onOk() }

    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (lead > 0) "还有 $lead 分钟上课" else "该上课了",
                fontSize = 20.sp, color = C.accent, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                name,
                fontSize = 30.sp, color = C.fg, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, lineHeight = 38.sp,
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(C.card).padding(18.dp)
            ) {
                Column {
                    if (slot.isNotBlank()) InfoRow("节次", slot)
                    if (time.isNotBlank()) InfoRow("开始", time)
                    if (room.isNotBlank()) InfoRow("教室", room)
                    if (teacher.isNotBlank()) InfoRow("教师", teacher)
                }
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onOk,
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.accent, contentColor = C.onAccent,
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("知道了", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, fontSize = 13.sp, color = C.dim, modifier = Modifier.width(52.dp))
        Text(v, fontSize = 15.sp, color = C.fg)
    }
}
