package com.taskbar.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.taskbar.app.R
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.StepStatus
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TaskType
import com.taskbar.app.data.model.TrackStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

// ==================== 今天任务列表 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(vm: TaskViewModel, navController: NavController) {
    val tasks by vm.mainList.collectAsState()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("add") },
                containerColor = TGColors.Gold,
                contentColor = TGColors.Ink
            ) {
                TGIcon(R.drawable.ic_add, contentDescription = "添加", tint = TGColors.Ink, size = 24.dp)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 顶栏：图标 + 标题 + 积分
            val points by vm.totalPoints.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    TGIcon(R.drawable.ic_today, contentDescription = null, tint = TGColors.GoldDeep, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "任务指南",
                        color = TGColors.Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(TGColors.Selected)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TGIcon(R.drawable.ic_coin, contentDescription = null, tint = TGColors.GoldDeep, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("$points", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (tasks.isEmpty()) {
                EmptyState("还没有任务\n点右下角加号，添加第一个", Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(tasks, key = { it.uuid }) { task ->
                        TaskRow(task, vm) { navController.navigate("detail/${task.uuid}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, vm: TaskViewModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TGColors.Card)
            .border(1.dp, TGColors.BorderSoft, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧菱形标识（紫=追踪中 / 浅=待办）
        Box(
            Modifier
                .size(12.dp)
                .background(
                    if (task.trackStatus == TrackStatus.TRACKING) TGColors.Violet else TGColors.InkFaint,
                    shape = RoundedCornerShape(3.dp)
                )
                .graphicsLayer { rotationZ = 45f }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(task.type)
                Spacer(Modifier.width(6.dp))
                PriorityChip(task.priority)
                if (task.trackStatus == TrackStatus.TRACKING) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TGColors.Violet.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("追踪中", color = TGColors.Violet, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(task.title, color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            task.dueAt?.let {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TGIcon(R.drawable.ic_clock, contentDescription = null, tint = TGColors.Orange, size = 12.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(dateFmt.format(Date(it)), color = TGColors.InkSoft, fontSize = 11.sp)
                }
            }
        }
        // 追踪/完成按钮
        if (task.trackStatus != TrackStatus.TRACKING && task.type != TaskType.HABIT) {
            IconButton(onClick = { vm.startTracking(task.uuid) }) {
                TGIcon(R.drawable.ic_track, contentDescription = "追踪", tint = TGColors.Azure, size = 20.dp)
            }
        }
        IconButton(onClick = { vm.completeTask(task.uuid) }) {
            TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 20.dp)
        }
    }
}

// ==================== 追踪详情视图 ====================
@Composable
fun TrackScreen(vm: TaskViewModel, navController: NavController) {
    val tracking by vm.tracking.collectAsState()
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    // 用 remember 缓存有效 uuid：selectedUuid 变化或 tracking 列表头部变化时才重算
    // 这样 steps Flow 不会因为无意义的重组而重建（修频闪）
    val effectiveUuid = remember(tracking, selectedUuid) {
        selectedUuid ?: tracking.firstOrNull()?.uuid
    }
    // 用 produceState + effectiveUuid 作为 key：uuid 真变才重启 collect
    val steps by produceState(initialValue = emptyList<com.taskbar.app.data.model.Step>(), effectiveUuid) {
        if (effectiveUuid != null) {
            vm.steps(effectiveUuid).collect { value = it }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "追踪中 (${tracking.size}/${vm.trackLimit.collectAsState().value})",
            color = TGColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(4.dp, 12.dp)
        )

        if (tracking.isEmpty()) {
            EmptyState("没有追踪中的任务\n去今天列表里点追踪图标", Modifier.fillMaxSize())
        } else {
            // 左边：追踪任务横向列表（手机竖屏改为顶部横滚）
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tracking, key = { it.uuid }) { task ->
                    val selected = task.uuid == effectiveUuid
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) TGColors.Selected else TGColors.Card)
                            .border(1.dp, if (selected) TGColors.BorderMid else TGColors.BorderSoft, RoundedCornerShape(10.dp))
                            .clickable { selectedUuid = task.uuid }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (task.title.length > 10) task.title.take(10) + "…" else task.title,
                            color = if (selected) TGColors.GoldDeep else TGColors.Ink,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            val current = tracking.firstOrNull { it.uuid == effectiveUuid }
            if (current != null) {
                // 任务标题 + 进度
                val doneCount = steps.count { it.status == StepStatus.DONE }
                val total = steps.size
                Text(current.title, color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (total > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = if (total == 0) 0f else doneCount.toFloat() / total,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = TGColors.Gold,
                        trackColor = TGColors.BgPaperDeep
                    )
                    Text("$doneCount / $total 步骤", color = TGColors.InkMute, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(12.dp))
                // 右边：步骤列表
                if (steps.isEmpty()) {
                    EmptyState("该任务还没拆解步骤\n去详情页添加步骤", Modifier.fillMaxWidth().padding(20.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(steps, key = { it.uuid }) { step -> StepRow(step, vm) }
                    }
                }
            }
        }
    }
}

@Composable
fun StepRow(step: Step, vm: TaskViewModel) {
    val isDone = step.status == StepStatus.DONE
    val isDoing = step.status == StepStatus.DOING
    val bg = when {
        isDone -> TGColors.Jade.copy(alpha = 0.12f)
        isDoing -> TGColors.Gold.copy(alpha = 0.16f)
        else -> TGColors.Card
    }
    val indicator = when {
        isDone -> TGColors.Jade
        isDoing -> TGColors.Gold
        else -> TGColors.InkFaint
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态点
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(indicator))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                color = if (isDone) TGColors.InkMute else TGColors.Ink,
                fontSize = 14.sp,
                textDecoration = if (isDone) TextDecoration.LineThrough else null
            )
            if (step.attrValue.isNotEmpty()) {
                Text("${step.attrLabel}: ${step.attrValue}", color = TGColors.GoldDeep, fontSize = 11.sp)
            }
        }
        if (!isDone) {
            IconButton(onClick = { vm.advanceStep(step.uuid) }, modifier = Modifier.size(36.dp)) {
                TGIcon(R.drawable.ic_check, contentDescription = "完成", tint = TGColors.Jade, size = 20.dp)
            }
        }
    }
}

// ==================== 习惯打卡 ====================
@Composable
fun HabitScreen(vm: TaskViewModel) {
    val habits by vm.habits.collectAsState()
    val ctx = LocalContext.current
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("习惯打卡", color = TGColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 12.dp))
        if (habits.isEmpty()) {
            EmptyState("还没有习惯\n添加一个 type=habit 的任务", Modifier.fillMaxSize())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(habits, key = { it.uuid }) { habit ->
                    val streak by vm.observeHabitStreak(habit.uuid).collectAsState()
                    val scope = rememberCoroutineScope()
                    var checkedToday by remember(habit.uuid) { mutableStateOf(false) }
                    LaunchedEffect(habit.uuid) {
                        // 每次打卡后 habit_logs 变化，streak 自动重算；
                        // checkedToday 也要重新检测（如果用户跨天进来需要刷新）
                        checkedToday = vm.repoIsCheckedToday(habit.uuid, today)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TGColors.Card)
                            .border(1.dp, TGColors.BorderSoft, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(habit.title, color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (checkedToday) "今日已打卡 · 连续 $streak 天" else "连续 $streak 天",
                                color = if (checkedToday) TGColors.Jade else TGColors.GoldDeep,
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            enabled = !checkedToday,
                            onClick = {
                                scope.launch {
                                    val ok = vm.checkHabitAndReturn(habit.uuid, today)
                                    if (ok) {
                                        checkedToday = true
                                        Toast.makeText(ctx, "已打卡 ✓", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(ctx, "今天已打过卡了", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (checkedToday) TGColors.BgPaperDeep else TGColors.Jade,
                                disabledContainerColor = TGColors.BgPaperDeep
                            )
                        ) {
                            Text(
                                if (checkedToday) "已打卡" else "打卡",
                                color = if (checkedToday) TGColors.InkMute else androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 已完成归档区 ====================
@Composable
fun ArchiveScreen(vm: TaskViewModel) {
    val archive by vm.archive.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("已完成 (${archive.size})", color = TGColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 12.dp))
        if (archive.isEmpty()) {
            EmptyState("还没有已完成的任务", Modifier.fillMaxSize())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(archive, key = { it.uuid }) { task ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TGColors.Card.copy(alpha = 0.7f))
                            .border(1.dp, TGColors.BorderSoft, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                color = TGColors.InkMute,
                                fontSize = 14.sp,
                                textDecoration = TextDecoration.LineThrough
                            )
                            task.doneAt?.let { Text("完成于 ${dateFmt.format(Date(it))}", color = TGColors.InkMute, fontSize = 11.sp) }
                        }
                        TextButton(onClick = { vm.restoreTask(task.uuid) }) { Text("恢复", color = TGColors.GoldDeep) }
                    }
                }
            }
        }
    }
}
