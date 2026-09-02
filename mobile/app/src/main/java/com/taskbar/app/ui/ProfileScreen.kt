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

        // ===== 等级卡：黑金（boss 定稿：墨黑底+金字+流沙+金属反光边缘）=====
        // 全等级统一黑金；Lv 越高流沙粒子越多、金光越亮（珍贵感递增）
        val particleCount = 6 + level.lv * 2   // Lv1=8 … Lv5=16 粒金沙
        val rimGlow = (level.lv - 1) / 4f      // 0.0~1.0 边缘金光强度随等级增
        Box(
            Modifier.fillMaxWidth().height(96.dp)
                .clip(RoundedCornerShape(14.dp))
        ) {
            // 背景：Canvas 自绘墨黑底 + 金属反光边缘 + 流沙金粒
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val h = this.size.height
                val r = 14.dp.toPx()
                // 底色：上暗下微暖墨黑（纵深）
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF0B0805), Color(0xFF14100A), Color(0xFF201809))
                    ),
                    cornerRadius = CornerRadius(r, r)
                )
                // 顶部光带（流沙光源）
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            TGColors.GoldLight.copy(alpha = 0.28f + 0.4f * rimGlow),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, 1.4.dp.toPx())
                )
                // 金沙粒：集中上半部，向下渐淡（流沙沉底感），伪随机稳定
                for (i in 0 until particleCount) {
                    val fx = ((i * 137L) % 1000L) / 1000f
                    val fy = ((i * 271L) % 1000L) / 1000f * 0.6f
                    val px_ = fx * w
                    val py_ = 4.dp.toPx() + fy * h
                    val radius = (1.0 + (i % 3) * 0.5).dp.toPx()
                    val alpha = (0.9f - fy) * (0.35f + 0.5f * rimGlow)
                    drawCircle(
                        color = TGColors.GoldLight.copy(alpha = alpha.coerceIn(0.04f, 1f)),
                        radius = radius,
                        center = Offset(px_, py_)
                    )
                }
                // 右侧角光（大金沙，贵重感）
                drawCircle(
                    color = TGColors.GoldLight.copy(alpha = 0.15f + 0.25f * rimGlow),
                    radius = 3.dp.toPx(),
                    center = Offset(w - 22.dp.toPx(), 10.dp.toPx())
                )
                // 边缘金属反光描边（画在最后：上下亮中间暗）
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            TGColors.GoldLight.copy(alpha = 0.55f + 0.4f * rimGlow),
                            TGColors.Gold.copy(alpha = 0.2f),
                            TGColors.Gold.copy(alpha = 0.28f),
                            TGColors.GoldLight.copy(alpha = 0.75f + 0.25f * rimGlow)
                        )
                    ),
                    topLeft = Offset(0.6.dp.toPx(), 0.6.dp.toPx()),
                    size = Size(w - 1.2.dp.toPx(), h - 1.2.dp.toPx()),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            // 内容层（叠在金沙背景上）
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .background(
                                        Brush.verticalGradient(listOf(TGColors.GoldLight, TGColors.Gold)),
                                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                                    )
                                    .graphicsLayer { rotationZ = 45f }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Lv.${level.lv} · ${level.name}",
                                color = TGColors.GoldLight,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            level.title,
                            color = TGColors.Gold.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        // 金色进度条
                        Box(
                            Modifier.fillMaxWidth().height(4.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                .background(TGColors.GoldLight.copy(alpha = 0.14f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(level.progress.coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                    .background(
                                        Brush.horizontalGradient(listOf(TGColors.GoldDeep, TGColors.GoldLight))
                                    )
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "$points",
                            color = TGColors.GoldLight,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "/ ${level.max} 分",
                            color = TGColors.Gold.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (level.toNext > 0) "距下一级差 ${level.toNext}" else "已登顶",
                            color = TGColors.Gold.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
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