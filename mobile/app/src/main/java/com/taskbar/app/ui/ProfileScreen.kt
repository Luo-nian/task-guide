package com.taskbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.taskbar.app.R
import com.taskbar.app.data.model.Levels

/** 下一级等级名（"我的"页等级卡用） */
private fun nextLevelName(currentLv: Int): String = when (currentLv) {
    1 -> "风华游侠"
    2 -> "破浪骑士"
    3 -> "群星行者"
    4 -> "传奇勇者"
    else -> "下一级"
}

/** "我的"页：等级卡 + 今日进度/坚持统计 + 设置入口 */
@Composable
fun ProfileScreen(vm: TaskViewModel, navController: NavController) {
    val points by vm.totalPoints.collectAsState()
    val todayCount by vm.mainList.collectAsState()
    val level = Levels.of(points)
    // 习惯最长坚持天数（正反馈统计）
    var maxStreak by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { maxStreak = runCatching { vm.maxHabitStreak() }.getOrDefault(0) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "我的",
            color = TGColors.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(4.dp, 12.dp)
        )

        // ===== 等级卡：黑金镜面金属（斜扫高光 + 镜面反射渐变），Lv 越高光泽越强 =====
        val gloss = 0.30f + (level.lv - 1) * 0.06f   // 镜面光泽强度随等级（克制，不挡字）
        Box(Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(14.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val r = 14.dp.toPx()
                // 1) 底层：深咖→金 斜向渐变（金属板底色）
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1A1206),   // 左上暗
                            Color(0xFF4A3510),   // 
                            Color(0xFF8A6A20),   // 金
                            Color(0xFF3A2A0C)    // 右下回暗
                        ),
                        start = Offset(0f, 0f), end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(r, r)
                )
                // 2) 斜扫高光带（真正的"镜面金属"感：一道 45° 亮白带从左上扫到右下）
                //    中心亮带 + 两侧渐暗，模拟灯光在金属表面反射
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFF6B5220).copy(alpha = 0.0f),
                            0.32f to Color(0xFFFFFFFF).copy(alpha = 0.05f * gloss),
                            0.46f to Color(0xFFFFFFFF).copy(alpha = 0.30f * gloss),  // 高光最亮点
                            0.53f to Color(0xFFFFF3C0).copy(alpha = 0.20f * gloss),
                            0.62f to Color(0xFFFFFFFF).copy(alpha = 0.15f * gloss),
                            0.78f to Color(0xFFFFE9A8).copy(alpha = 0.05f * gloss),
                            1.00f to Color(0xFF3A2A0C).copy(alpha = 0.0f)
                        ),
                        start = Offset(0f, 0f), end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(r, r)
                )
                // 3) 底部镜面反光（金属卡下缘常见的一道微弱反光）
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFFFFF0C0).copy(alpha = 0.10f * gloss), Color.Transparent)
                    ),
                    topLeft = Offset(0f, h * 0.82f),
                    size = Size(w, h * 0.18f),
                    cornerRadius = CornerRadius(r, r)
                )
                // 4) 细金描边（镜面+描边立体感）
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(TGColors.GoldLight.copy(alpha = 0.8f), TGColors.Gold.copy(alpha = 0.35f), TGColors.GoldLight.copy(alpha = 0.9f)),
                        start = Offset(0f, 0f), end = Offset(0f, h)
                    ),
                    topLeft = Offset(0.8f.dp.toPx(), 0.8f.dp.toPx()),
                    size = Size(w - 1.6f.dp.toPx(), h - 1.6f.dp.toPx()),
                    cornerRadius = CornerRadius(r, r), style = Stroke(width = 1.dp.toPx())
                )
            }
            // 内容层：文字用深咖（金底上比白字更贵气）+ 阴影托底
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(2.dp)).graphicsLayer { rotationZ = 45f })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Lv.${level.lv} · ${level.name}",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            style = LocalTextStyle.current.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 1f), 0f))
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(level.title, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(level.progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF2A1D08), Color(0xFF8A6A20)))))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("$points / ${level.max}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, style = LocalTextStyle.current.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 1f), 0f)))
                    Spacer(Modifier.height(3.dp))
                    Text(if (level.toNext > 0) "距下一级差 ${level.toNext}" else "已登顶", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 统计卡：今日进度 + 习惯坚持（正反馈层层递进）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TGCard(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TGIcon(R.drawable.ic_trend, contentDescription = null, tint = TGColors.Azure, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("今日进度", color = TGColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${todayCount.size} 个待完成",
                    color = TGColors.Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("今天要做的事", color = TGColors.InkMute, fontSize = 10.sp)
            }
            TGCard(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TGIcon(R.drawable.ic_trophy, contentDescription = null, tint = TGColors.Jade, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("习惯坚持", color = TGColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "$maxStreak 天",
                    color = TGColors.Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("最长连续打卡", color = TGColors.InkMute, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        // 设置入口（点击进入子页面）
        TGCard(Modifier.fillMaxWidth().clickable { navController.navigate("settings") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TGIcon(R.drawable.ic_settings, contentDescription = null, tint = TGColors.GoldDeep, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("设置", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = TGColors.InkMute, fontSize = 20.sp)
            }
        }
    }
}