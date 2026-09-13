package com.taskbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.R
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TrackStatus

/**
 * v5.15.21 R4（boss：手机端要能「日历形式」查看历史任务和所有任务）——
 * 与电脑端的"记账式"互补：日历按月铺开，每天格子右上显示当天任务数，点某天看当天清单。
 *
 * @param tasks       要展示的任务集合（历史 = 已完成；所有任务 = 追踪+今日+未来）
 * @param dateOf      取该任务的"归属日期"时间戳（历史用完成时间，其余用截止/期限/创建时间）
 * @param onTaskClick 点击某条任务
 * @param emptyHint   选中日无任务时的提示文案
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TaskCalendarView(
    tasks: List<Task>,
    dateOf: (Task) -> Long?,
    onTaskClick: (Task) -> Unit,
    emptyHint: String = "这一天没有任务",
    // v5.15.22 M3：可选状态文案（历史页把每日任务按天展开后，用来标"未完成"）
    statusOf: ((Task) -> String)? = null,
    /**
     * v5.15.24 F3b：「这条算不算完成」——**必须由调用方给**。
     * 根因：习惯任务打卡后 trackStatus 仍是 pending（打卡记在 habit_logs，不动 trackStatus），
     * 所以内部默认的 `trackStatus == done` 会把今天已打卡的习惯误判成"未完成"，
     * 连带"整日清空 → 玉青"也永远不亮。调用方需要把"今日已打卡"一并算进来。
     */
    doneOf: (Task) -> Boolean = { it.trackStatus == TrackStatus.DONE },
    // v5.15.23 M11：多选（日历形式也要能多选）
    selecting: Boolean = false,
    isSelected: (Task) -> Boolean = { false },
    onToggleSelect: ((Task) -> Unit)? = null,
    onLongSelect: ((Task) -> Unit)? = null
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

    // v5.15.23 V1（boss：「所有任务页面更多是视觉上的问题」）——
    //   日历本体装进一张白卡：有明确边界，和下方"当日清单"形成两个清晰区块，
    //   不再是一堆数字散在米色背景上。
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TGColors.Card)
            .border(1.dp, TGColors.BorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
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
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Serif,
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
                    color = TGColors.InkSoft,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
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
                        val dayTasks = byDay[key].orEmpty()
                        val n = dayTasks.size
                        // v5.15.24 F3（调研 3.1 强烈建议：日历按"完成密度"表达）——
                        //   底色三档继续表示"忙闲"（1~3 / 4~10 / 10+）；
                        //   这里再补一层"清空"信号：当天任务全部完成 → 日期数字转玉青。
                        //   **不加任何新元素**，避免破坏 boss 已经认可的简洁观感。
                        val allDone = n > 0 && dayTasks.all { doneOf(it) }
                        val isSel = key == selectedKey
                        val isToday = key == todayKey
                        Box(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(9.dp))
                                // v5.15.23 M6（boss：日历按任务数量染色分 1~3 / 4~10 / 10+ 三档，
                                //   但不能影响看清日期）—— 底色只做分层，日期文字始终深色；
                                //   M6b 去掉数字角标后，把三档色调再拉开一档，让"哪几天忙"一眼可辨。
                                .background(
                                    when {
                                        isSel -> TGColors.Gold.copy(alpha = 0.38f)
                                        n >= 10 -> TGColors.Crimson.copy(alpha = 0.30f)
                                        n >= 4 -> TGColors.Orange.copy(alpha = 0.24f)
                                        n >= 1 -> TGColors.Gold.copy(alpha = 0.15f)
                                        else -> Color.Transparent
                                    }
                                )
                                // v5.15.23 V1：既有极淡网格边（不再是"数字浮在背景上"），
                                //   又让"今天"（金边+金点）与"选中"（更浓的赤陶底）两种强调分开
                                .border(
                                    if (isToday) 1.5.dp else 0.8.dp,
                                    when {
                                        isToday -> TGColors.GoldDeep.copy(alpha = 0.8f)
                                        else -> TGColors.BorderSoft
                                    },
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { selectedKey = key },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // 日期数字（格子居中）
                                Text(
                                    "$dayNum",
                                    // v5.15.23 M6：无任务的日期半透明；有任务的保持深色清晰可读
                                    color = when {
                                        allDone -> TGColors.JadeDeep       // v5.15.24 F3：整日清空
                                        n > 0 -> TGColors.Ink
                                        isSel || isToday -> TGColors.InkSoft
                                        else -> TGColors.InkMute.copy(alpha = 0.45f)
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (isSel || isToday || n > 0) FontWeight.Bold else FontWeight.Normal
                                )
                                // v5.15.23 M6（boss：当天日期就在日期下面加一个点）
                                if (isToday) {
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (allDone) TGColors.JadeDeep else TGColors.GoldDeep)
                                    )
                                }
                            }
                            // v5.15.23 M6b（boss：「把数字形式显示任务取消，现在的太丑了」）——
                            //   去掉日期格右上角的数量角标；数量信息改由**底色深浅三档**表达
                            //   （1~3 / 4~10 / 10+），想具体看某天有几条就点那一天看清单。
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selectedKey ?: "",
                color = TGColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            if (selList.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(TGColors.Gold.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) {
                    Text("${selList.size} 项", color = TGColors.GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (selList.isEmpty()) {
            // v5.15.23 M6（boss：空提示应该弄大一点，放在那一块的中间）
            Box(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    emptyHint,
                    color = TGColors.InkMute,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in selList) {
                    val selNow = selecting && isSelected(t)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TGColors.Card)
                            .border(
                                if (selNow) 2.dp else 1.dp,
                                if (selNow) TGColors.Jade else TGColors.BorderMid,
                                RoundedCornerShape(10.dp)
                            )
                            .combinedClickable(
                                onClick = { if (selecting) onToggleSelect?.invoke(t) else onTaskClick(t) },
                                onLongClick = { onLongSelect?.invoke(t) }
                            )
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selecting) {
                            SelectBadge(selected = selNow)
                            Spacer(Modifier.width(8.dp))
                        }
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
