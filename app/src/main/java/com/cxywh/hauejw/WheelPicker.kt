package com.cxywh.hauejw

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

// 两列固定 38dp 高、5 行可见；列表做成常量，别在重组里反复 toList()
private val ITEM_H = 38.dp
private val COL_W = 86.dp
private const val ROWS = 5
private val HOURS = (0..23).toList()
private val MINUTES = (0..59).toList()

/**
 * 拨轮选择器：上下拖动选值，值在中间一行高亮，两侧按距离淡出。
 *
 * 列表首尾各复制一份，滚动不会顶到边界，中间那份就是真实区间。
 * 外部改 [value] 时会对齐过去；拖动过程中不回写，避免和手感打架。
 */
@Composable
internal fun Wheel(
    values: List<Int>,
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = ITEM_H,
    rows: Int = ROWS,
    fontSize: androidx.compose.ui.unit.TextUnit = 24.sp,
) {
    val n = values.size
    if (n == 0) return
    val total = n * 3
    // 起始就落在中间那份的 value 上：firstVisible 就是被居中那一项
    val state = rememberLazyListState(initialFirstVisibleItemIndex = n + value.coerceIn(0, n - 1))
    val snap = rememberSnapFlingBehavior(state)

    // 当前落在中间的那一项
    val center by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val vc = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - vc) }?.index ?: n
        }
    }

    val onValueNow by rememberUpdatedState(onValue)
    LaunchedEffect(state, n) {
        snapshotFlow { center }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx in 0 until total) onValueNow(values[idx % n])
            }
    }

    // 外部值变化时对齐（拖动中不打扰）
    LaunchedEffect(value, n) {
        if (state.isScrollInProgress) snapshotFlow { state.isScrollInProgress }.first { !it }
        val cur = ((center % n) + n) % n
        if (cur != value) state.scrollToItem((n + value).coerceIn(0, total - 1))
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(C.selBg, RoundedCornerShape(10.dp))
        )
        LazyColumn(
            state = state,
            flingBehavior = snap,
            contentPadding = PaddingValues(vertical = itemHeight * (rows / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(total) { i ->
                val v = values[i % n]
                val d = abs(i - center)
                val on = d == 0
                Box(
                    Modifier.fillMaxWidth().height(itemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = v.toString().padStart(2, '0'),
                        fontSize = if (on) fontSize else fontSize * 0.7f,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        color = when {
                            on -> C.accent
                            d == 1 -> C.fg.copy(alpha = 0.75f)
                            else -> C.dim.copy(alpha = 0.4f)
                        },
                    )
                }
            }
        }
    }
}

/** 时:分 两组拨轮 */
@Composable
internal fun TimeWheel(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wheel(HOURS, hour, onHour, Modifier.width(COL_W).height(ITEM_H * ROWS))
        Text(":", fontSize = 22.sp, color = C.dim, modifier = Modifier.padding(horizontal = 4.dp))
        Wheel(MINUTES, minute, onMinute, Modifier.width(COL_W).height(ITEM_H * ROWS))
    }
}
