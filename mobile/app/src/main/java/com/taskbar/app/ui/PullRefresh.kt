package com.taskbar.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * v5.30.0：主页下拉刷新（boss 指定功能）。
 *
 * 为什么自写而不用库：项目用的是 compose-bom 2024.02.00（material3 **1.2.0**），
 * 该版本的 `PullToRefreshContainer` 还是实验 API 且签名在 1.3 大改 ——
 * 升库要牵动 518 处颜色引用的同一批依赖，风险不值当。自写 60 行，手感可控。
 *
 * 行为：内容已滚到顶、还继续往下拨 → 累积下拉位移（带阻尼）；
 *   松手时位移超过阈值 → 触发 onRefresh；刷新中( refreshing=true )不再重复触发。
 * 刷新的实际动作（与电脑端做几轮同步）由调用方给，本组件只管手势与动效。
 */
@Composable
fun PullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val threshold = with(density) { 62.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val refreshingNow = rememberUpdatedState(refreshing)
    val cbNow = rememberUpdatedState(onRefresh)

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset, available: Offset, source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0 && !refreshingNow.value) {
                    // 阻尼 0.45：越拉越沉，避免一拉就到底的廉价感
                    pull = (pull + available.y * 0.45f).coerceAtMost(threshold * 1.7f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 往回推（上滑）先收下拉位移，再交给列表滚动
                if (source == NestedScrollSource.Drag && available.y < 0 && pull > 0f) {
                    val d = pull.coerceAtMost(-available.y)
                    pull -= d
                    return Offset(0f, -d)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull >= threshold && !refreshingNow.value) cbNow.value()
                if (pull > 0f) {
                    val start = pull
                    val steps = 10
                    repeat(steps) { i ->
                        pull = start * (1f - (i + 1f) / steps)
                        delay(11)
                    }
                    pull = 0f
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier.fillMaxSize().nestedScroll(connection)) {
        content()

        // 指示器：跟手下滑；刷新中固定在顶端
        val visible = pull > 1f || refreshing
        if (visible) {
            val offsetY = if (refreshing) with(density) { 10.dp.toPx() } else pull - with(density) { 46.dp.toPx() }
            val armed = pull >= threshold
            val spin by rememberInfiniteTransition(label = "pull-spin").animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "pull-spin-angle"
            )
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, offsetY.roundToInt()) }
                    .clip(RoundedCornerShape(14.dp))
                    .background(TGColors.Card.copy(alpha = 0.96f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                TGIcon(
                    R.drawable.ic_check_circle,
                    contentDescription = null,
                    tint = TGColors.Gold,
                    size = 15.dp,
                    modifier = Modifier.rotate(if (refreshing) spin else pull / threshold * 180f)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = when {
                        refreshing -> "正在与电脑同步…"
                        armed -> "松开，与电脑同步"
                        else -> "下拉同步"
                    },
                    color = if (refreshing || armed) TGColors.GoldDeep else TGColors.InkMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
