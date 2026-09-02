package com.taskbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

        // 等级卡（暗色金属高级卡：深底 + 金属描边 + 顶部光带 + 水印等级数字）
        val p = level.palette
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(p.bgDeep)
                .then(
                    if (level.lv >= 4) {
                        // Lv4+ 加外圈微光描边（金辉更显）
                        Modifier.border(1.dp, p.metal.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    } else {
                        Modifier.border(1.dp, p.metal.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    }
                )
                .shadow(10.dp, RoundedCornerShape(16.dp))
        ) {
            // 左上→右下的微妙金属光泽（不是亮彩，是暗底上的光线）
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                p.metal.copy(alpha = 0.10f),
                                androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f)
                            )
                        )
                    )
            )
            // 顶部细光带（金属高光线，随等级变亮）
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                p.metalLight.copy(alpha = if (level.lv >= 3) 0.9f else 0.55f),
                                androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    )
            )
            // 右侧超大水印等级数字
            Text(
                "Lv.${level.lv}",
                color = p.metal.copy(alpha = if (level.lv >= 4) 0.14f else 0.08f),
                fontSize = if (level.lv >= 4) 74.sp else 64.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
            )
            Column(Modifier.padding(16.dp)) {
                // 徽章行：菱形色标 + "历练学徒" 小字
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                Brush.linearGradient(listOf(p.metalLight, p.metal)),
                                androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "ADVENTURER LV.${level.lv}",
                        color = p.metal.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 等级名大字（金属渐变感：用金属色）
                Text(
                    level.name,
                    color = p.metalLight,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    level.title,
                    color = p.onDeepSoft,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                // 进度条（暗底 + 金属渐变填充）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(level.progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(p.metal, p.metalLight)
                                    )
                                )
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$points / ${level.max}",
                        color = p.onDeep,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (level.toNext > 0) {
                    Text(
                        "距「${nextLevelName(level.lv)}」还差 ${level.toNext} 分",
                        color = p.onDeepSoft.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                } else {
                    Text("已登顶 · 所有荣誉加身", color = p.metalLight.copy(alpha = 0.9f), fontSize = 11.sp)
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