package com.taskbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.taskbar.app.data.model.Priority
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.StepStatus
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TaskType
import com.taskbar.app.data.model.TrackStatus
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("add") },
                containerColor = TGColors.Gold,
                contentColor = TGColors.Ink
            ) { Icon(Icons.Filled.Add, contentDescription = "添加") }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 顶栏：标题 + 积分（原神风格）
            val points by vm.totalPoints.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "◆ 任务指南",
                    color = TGColors.GoldLight,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(TGColors.Gold.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("◆ $points", color = TGColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (tasks.isEmpty()) {
                EmptyState("还没有任务\n点右下角 + 添加第一个", Modifier.fillMaxSize())
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
            .background(TGColors.CardBg)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧菱形标识（紫=追踪中 / 灰=待办，原神风格）
        Box(
            Modifier
                .size(12.dp)
                .background(
                    if (task.trackStatus == TrackStatus.TRACKING) TGColors.Purple else TGColors.Fog.copy(alpha = 0.45f),
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
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(TGColors.Purple.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("追踪中", color = TGColors.Purple, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(task.title, color = TGColors.Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            task.dueAt?.let {
                Spacer(Modifier.height(2.dp))
                Text("⏰ ${dateFmt.format(Date(it))}", color = TGColors.OrangeWarm, fontSize = 11.sp)
            }
        }
        // 追踪/完成按钮
        if (task.trackStatus != TrackStatus.TRACKING && task.type != TaskType.HABIT) {
            IconButton(onClick = { vm.startTracking(task.uuid) }) {
                Icon(Icons.Filled.TrackChanges, contentDescription = "追踪", tint = TGColors.Gold)
            }
        }
        IconButton(onClick = { vm.completeTask(task.uuid) }) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "完成", tint = TGColors.Jade)
        }
    }
}

// ==================== 追踪详情视图（原神图一样式） ====================
@Composable
fun TrackScreen(vm: TaskViewModel, navController: NavController) {
    val tracking by vm.tracking.collectAsState()
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    // 默认选第一个
    val currentUuid = selectedUuid ?: tracking.firstOrNull()?.uuid
    val steps by vm.steps(currentUuid ?: "").collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("追踪中 (${tracking.size}/${vm.trackLimit.collectAsState().value})",
            color = TGColors.GoldLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(4.dp, 12.dp))

        if (tracking.isEmpty()) {
            EmptyState("没有追踪中的任务\n去今天列表里点追踪图标", Modifier.fillMaxSize())
            return@Column
        }

        // 左边：追踪任务横向列表（手机竖屏改为顶部横滚）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tracking, key = { it.uuid }) { task ->
                val selected = task.uuid == currentUuid
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) TGColors.Gold.copy(alpha = 0.3f) else TGColors.CardBg)
                        .clickable { selectedUuid = task.uuid }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (task.title.length > 10) task.title.take(10) + "…" else task.title,
                        color = if (selected) TGColors.GoldLight else TGColors.Paper,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val current = tracking.firstOrNull { it.uuid == currentUuid }
        if (current != null) {
            // 任务标题 + 进度
            val doneCount = steps.count { it.status == StepStatus.DONE }
            val total = steps.size
            Text(current.title, color = TGColors.Paper, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = if (total == 0) 0f else doneCount.toFloat() / total,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = TGColors.Gold,
                    trackColor = TGColors.InkLighter
                )
                Text("$doneCount / $total 步骤", color = TGColors.Fog, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
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

@Composable
fun StepRow(step: Step, vm: TaskViewModel) {
    val isDone = step.status == StepStatus.DONE
    val isDoing = step.status == StepStatus.DOING
    val bg = when {
        isDone -> TGColors.Jade.copy(alpha = 0.15f)
        isDoing -> TGColors.Gold.copy(alpha = 0.2f)
        else -> TGColors.CardBg
    }
    val indicator = when {
        isDone -> TGColors.Jade
        isDoing -> TGColors.Gold
        else -> TGColors.Fog
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态点
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(indicator))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title,
                color = if (isDone) TGColors.Fog else TGColors.Paper,
                fontSize = 14.sp,
                textDecoration = if (isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            if (step.attrValue.isNotEmpty()) {
                Text("${step.attrLabel}: ${step.attrValue}", color = TGColors.GoldLight, fontSize = 11.sp)
            }
        }
        if (!isDone) {
            IconButton(onClick = { vm.advanceStep(step.uuid) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Check, contentDescription = "完成", tint = TGColors.Jade)
            }
        }
    }
}

// ==================== 习惯打卡 ====================
@Composable
fun HabitScreen(vm: TaskViewModel) {
    val habits by vm.habits.collectAsState()
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("习惯打卡", color = TGColors.GoldLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 12.dp))
        if (habits.isEmpty()) {
            EmptyState("还没有习惯\n添加一个 type=habit 的任务", Modifier.fillMaxSize())
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(habits, key = { it.uuid }) { habit ->
                var streak by remember(habit.uuid) { mutableStateOf(0) }
                LaunchedEffect(habit.uuid) { streak = vm.habitStreak(habit.uuid) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TGColors.CardBg).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(habit.title, color = TGColors.Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("连续 $streak 天", color = TGColors.Gold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { vm.checkHabit(habit.uuid, today) },
                        colors = ButtonDefaults.buttonColors(containerColor = TGColors.Jade)
                    ) { Text("打卡", color = TGColors.Paper) }
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
        Text("已完成 (${archive.size})", color = TGColors.GoldLight, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 12.dp))
        if (archive.isEmpty()) {
            EmptyState("还没有已完成的任务", Modifier.fillMaxSize())
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(archive, key = { it.uuid }) { task ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TGColors.CardBg.copy(alpha = 0.6f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, color = TGColors.Fog, fontSize = 14.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                        task.doneAt?.let { Text("完成于 ${dateFmt.format(Date(it))}", color = TGColors.Fog, fontSize = 11.sp) }
                    }
                    TextButton(onClick = { vm.restoreTask(task.uuid) }) { Text("恢复", color = TGColors.Gold) }
                }
            }
        }
    }
}
