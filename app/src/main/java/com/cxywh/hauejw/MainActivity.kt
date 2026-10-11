package com.cxywh.hauejw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.attach(getExternalFilesDir(null) ?: filesDir)   // 吞掉的失败落到 diag.log，adb 可直取
        val prefs = Prefs(this)
        C.palette = C.byId(prefs.theme)

        setContent {
            val p = C.palette
            // 状态栏图标跟着主题走：浅色主题用深色图标，暗色/玻璃主题用浅色图标
            LaunchedEffect(p.dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !p.dark
                    isAppearanceLightNavigationBars = !p.dark
                }
            }
            Box(Modifier.fillMaxSize().background(p.bg)) {
                // 不再铺满整屏：顶部避开状态栏（任务栏），底部避开导航栏
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Root(prefs)
                }
            }
        }
    }
}
