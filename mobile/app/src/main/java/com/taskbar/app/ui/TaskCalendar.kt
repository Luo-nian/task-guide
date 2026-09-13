package com.taskbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.R
import com.taskbar.app.data.model.Task

/**
 * v5.15.21 R4（boss：手机端要能「日历形式」查看历史任务和所有任务）——
 * 与电脑端的"记账式"互补：日历按月铺开，每天格子右上显示当天任务数，点某天看当天清单。
 *
 * @param tasks       要展示的任务集合（历史 = 已完成；所有任务 = 追踪+今日+未来）
 * @param dateOf      取该任务的"归属日期"时间戳（历史用完成时间，其余用截止/期限/创建时间）
 * @param onTaskClick 点击某条任务
 * @param emptyHint   选中日无任务时的提示文案
 */
@Composable
fun TaskCalendarView(
    tasks: List<Task>,
    dateOf: (Task) -> Long?,
    onTaskClick: (Task) -> Unit,
    emptyHint: String = "这一天没有任务",
    // v5.15.22 M3：可选状态文案（历史页把每日任务按天展开后，用来标"未完成"）
    statusOf: ((Task) -> String)? = null
) {
    var monthOffset by remember { mutableStateOf(0) }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // 当前展示月份的 1 号
    val firstCal = remember(monthOffset) {
        java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MONTH, monthOffset)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
    }
    val year = firstCal.get(java.util.Calendar.YEAR)
    val monthIdx = firstCal.get(java.util.Calendar.MONTH)      // 0-based
    val todayKey = remember { calDayKey(System.currentTimeMillis()) }

    // 默认选中今天
    LaunchedEffect(monthOffset) {
        if (selectedKey == null) selectedKey = todayKey
    }

    // 任务按天分组
    val byDay = remember(tasks) {
        val m = HashMap<String, MutableList<Task>>()
        for (t in tasks) {
            val ts = dateOf(t) ?: continue
            m.getOrPut(calDayKey(ts)) { mutableListOf() }.add(t)
        }
        m
    }

    // 网格：周一为第一列
    val lead = (firstCal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val daysInMonth = firstCal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    val rows = (lead + daysInMonth + 6) / 7

    Column(Modifier.fillMaxWidth()) {
        // ── 月份切换 ──
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressIcon(onClick = { monthOffset -= 1; selectedKey = null }) {
                TGIcon(R.drawable.ic_back, contentDescription = "上个月", tint = TGColors.InkSoft, size = 18.dp)
            }
            Text(
                "$year 年 ${monthIdx + 1} 月",
                color = TGColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            PressIcon(onClick = { monthOffset += 1; selectedKey = null }) {
                Box(Modifier.graphicsLayer { rotationZ = 180f }) {
                    TGIcon(R.drawable.ic_back, contentDescription = "下个月", tint = TGColors.InkSoft, size = 18.dp)
                }
            }
        }

        // ── 星期表头 ──
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                Text(
                    w,
                    color = TGColors.InkMute,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(2.dp))

        // ── 日期格 ──
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c
                    val dayNum = idx - lead + 1
                    if (dayNum < 1 || dayNum > daysInMonth) {
                        Spacer(Modifier.weight(1f).height(46.dp))
                    } else {
                        val key = "%04d-%02d-%02d".format(year, monthIdx + 1, dayNum)
                        val n = byDay[key]?.size ?: 0
                        val isSel = key == selectedKey
                        val isToday = key == todayKey
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    when {
                                        isSel -> TGColors.Gold.copy(alpha = 0.22f)
                                        n > 0 -> TGColors.Gold.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (isToday) TGColors.Gold.copy(alpha = 0.6f) else Color.Transparent,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { selectedKey = key },
                            contentAlignment = Alignment.Center
                        ) {
                            // 日期数字（格子居中）
                            Text(
                                "$dayNum",
                                color = if (isSel || n > 0) TGColors.Ink else TGColors.InkMute,
                                fontSize = 13.sp,
                                fontWeight = if (isSel || isToday) FontWeight.Bold else FontWeight.Normal
                            )
                            // v5.15.22 M2（boss：日期对应的任务数量有点小而且位置偏下）——
                            //   角标从"日期下方居中"改到**格子右上角**，字号 9→10.5，颜色加深更醒目。
                            if (n > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 1.dp, end = 1.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(TGColors.GoldDeep.copy(alpha = if (isSel) 0.95f else 0.72f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        "$n",
                                        color = Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = TGColors.BorderSoft)
        Spacer(Modifier.height(8.dp))

        // ── 选中日的任务清单 ──
        val selList = selectedKey?.let { byDay[it] }.orEmpty()
        Text(
            selectedKey ?: "",
            color = TGColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        if (selList.isEmpty()) {
            Text(emptyHint, color = TGColors.InkMute, fontSize = 12.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in selList) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TGColors.Card)
                            .border(1.dp, TGColors.BorderMid, RoundedCornerShape(10.dp))
                            .clickable { onTaskClick(t) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t.title,
                            color = TGColors.Ink,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // v5.15.22 M3：状态标签（未完成 = 朱砂红，一眼能看出那天漏了）
                        statusOf?.let { f ->
                            val st = f(t)
                            if (st.isNotEmpty()) {
                                val missed = st.contains("未")
                                Text(
                                    st,
                                    color = if (missed) TGColors.Crimson else TGColors.InkMute,
                                    fontSize = 10.sp,
                                    fontWeight = if (missed) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                        Text(
                            CAT_FILTER_LABEL[catKeyOf(t)] ?: "",
                            color = TGColors.InkMute,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 时间戳 → yyyy-MM-dd（本地时区） */
private fun calDayKey(ts: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    return "%04d-%02d-%02d".format(
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH)
    )
}
