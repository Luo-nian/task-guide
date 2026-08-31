package com.taskbar.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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

// ==================== 主页（今天要完成的任务） ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(vm: TaskViewModel, navController: NavController) {
    val tasks by vm.mainList.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            // 克制 FAB：金底 + 墨色加号（去掉黑底，保留金色点缀）
            FloatingActionButton(
                onClick = { navController.navigate("add") },
                containerColor = TGColors.Gold,
                contentColor = TGColors.Black
            ) {
                TGIcon(R.drawable.ic_add, contentDescription = "添加", tint = TGColors.Black, size = 24.dp)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (tasks.isEmpty()) {
                EmptyState("还没有任务\n点右下角加号，添加第一个", Modifier.fillMaxHeight(0.45f))
            } else {
                // 追踪中任务置顶分区 + 其余待办分区
                val tracking = tasks.filter { it.trackStatus == TrackStatus.TRACKING }
                val rest = tasks.filter { it.trackStatus != TrackStatus.TRACKING }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (tracking.isNotEmpty()) {
                        item(key = "hdr-tracking") { SectionHeader("正在追踪 (${tracking.size})", TGColors.Violet) }
                        items(tracking, key = { it.uuid }) { task ->
                            TaskRow(task, vm, onClick = { navController.navigate("detail/${task.uuid}") }, onEdit = { navController.navigate("edit/${task.uuid}") })
                        }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "hdr-rest") { SectionHeader("待办 (${rest.size})", TGColors.GoldDeep) }
                        items(rest, key = { it.uuid }) { task ->
                            TaskRow(task, vm, onClick = { navController.navigate("detail/${task.uuid}") }, onEdit = { navController.navigate("edit/${task.uuid}") })
                        }
                    }
                }
            }
        }
    }
}

/** 列表分区标题（带色块引导） */
@Composable
private fun SectionHeader(title: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(title, color = TGColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(task: Task, vm: TaskViewModel, onClick: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 步骤列表（决定按钮形态 + 推进连按逻辑 + 当前步骤小字）
    val steps by remember(task.uuid) { vm.steps(task.uuid) }.collectAsState(emptyList())
    val hasSteps = steps.isNotEmpty()
    val trackLimit by vm.trackLimit.collectAsState()
    val tracking = task.trackStatus == TrackStatus.TRACKING
    var lastClick by remember { mutableStateOf(0L) }
    var clicksInWindow by remember { mutableStateOf(0) }
    var showFinish by remember { mutableStateOf(false) }

    // 当前步骤（小字展示）：doing 优先，否则第一个未完成
    val currentStep = steps.firstOrNull { it.status == StepStatus.DOING }
        ?: steps.firstOrNull { it.status != StepStatus.DONE }
    val currentStepIndex = currentStep?.let { steps.indexOf(it) }

    // 追踪中卡片：金色边框静态标记（动画去掉，性能优先：滑动卡顿主因之一）
    val trackingBorderWidth = if (tracking) 1.5.dp else 1.dp
    val trackingBorderColor = if (tracking) TGColors.Gold else TGColors.BorderSoft

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TGColors.Card)
            .border(
                width = trackingBorderWidth,
                color = trackingBorderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onEdit)
    ) {
        Row(
            Modifier.padding(12.dp, 12.dp, 6.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧菱形标识：追踪中紫色（静态，避免重组）
            val diamondColor = when {
                tracking -> TGColors.Violet
                task.priority == "high" -> TGColors.Crimson
                task.priority == "low" -> TGColors.InkFaint
                else -> TGColors.Gold
            }
            Box(
                Modifier
                    .size(12.dp)
                    .background(diamondColor, shape = RoundedCornerShape(3.dp))
                    .graphicsLayer { rotationZ = 45f }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(task.type)
                    Spacer(Modifier.width(6.dp))
                    PriorityChip(task.priority)
                    if (tracking) {
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
                    if (task.type == TaskType.HABIT && task.dueAt == null) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TGColors.Jade.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("习惯", color = TGColors.Jade, fontSize = 11.sp)
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
            // 右侧操作：所有任务统一 [追踪/停止] + [主操作]
            // 有步骤 → 推进键（5s 内连按 3 次弹出完成键 + 3s 提示）；无步骤 → 完成键
            // 习惯任务无独立打卡按钮（完成即打卡）
            if (tracking) {
                PressIcon(onClick = { vm.stopTracking(task.uuid) }) {
                    TGIcon(R.drawable.ic_track, contentDescription = "停止追踪", tint = TGColors.Violet, size = 20.dp)
                }
            } else {
                PressIcon(onClick = {
                    vm.startTracking(task.uuid) { ok ->
                        if (!ok) {
                            ToastHelper.show(ctx, "追踪已达上限($trackLimit 个)，先取消别的追踪或在设置里调高上限")
                        }
                    }
                }) {
                    TGIcon(R.drawable.ic_track, contentDescription = "追踪", tint = TGColors.Azure, size = 20.dp)
                }
            }
            if (hasSteps) {
                // 有步骤：推进键（推进当前步骤），连按 3 次显示完成键
                PressIcon(onClick = {
                    vm.advanceStepByTask(task.uuid)
                    val now = System.currentTimeMillis()
                    if (now - lastClick > 5000) clicksInWindow = 0
                    lastClick = now
                    clicksInWindow++
                    if (clicksInWindow >= 3 && !showFinish) {
                        showFinish = true
                        scope.launch {
                            kotlinx.coroutines.delay(3000)
                            showFinish = false
                            clicksInWindow = 0
                        }
                    }
                }) {
                    TGIcon(R.drawable.ic_forward, contentDescription = "推进", tint = TGColors.GoldDeep, size = 20.dp)
                }
                if (showFinish) {
                    PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                        TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 20.dp)
                    }
                }
            } else {
                // 无步骤：完成键（习惯任务即打卡）
                PressIcon(onClick = {
                    vm.completeTask(task.uuid)
                    if (task.type == TaskType.HABIT) {
                        ToastHelper.show(ctx, "已打卡 ✓")
                    }
                }) {
                    TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 20.dp)
                }
            }
        }
        // 当前步骤小字（有步骤时提示正在追踪哪个步骤）
        if (currentStep != null && currentStepIndex != null) {
            Row(
                Modifier.padding(start = 36.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("▶", color = TGColors.GoldDeep, fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    "步骤 ${currentStepIndex + 1}/${steps.size}：${currentStep.title}",
                    color = TGColors.GoldDeep,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 推进 3 次后的提示条
        if (showFinish) {
            Text(
                "步骤都完成了吗？可以直接点完成哦",
                color = TGColors.Jade,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 10.dp)
            )
        }
    }
}

// ==================== 追踪详情视图 ====================
@Composable
fun TrackScreen(vm: TaskViewModel) {
    val tracking by vm.tracking.collectAsState()
    val limit by vm.trackLimit.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "追踪中 (${tracking.size}/$limit)",
            color = TGColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(4.dp, 12.dp)
        )

        if (tracking.isEmpty()) {
            EmptyState("没有追踪中的任务\n去主页点追踪图标", Modifier.fillMaxSize())
        } else {
            // 追踪任务竖排列表，每个任务一张卡，卡内可收起步骤栏
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tracking, key = { it.uuid }) { task ->
                    TrackTaskCard(task, vm)
                }
            }
        }
    }
}

/** 追踪任务卡：标题 + 进度 + 可收起步骤栏（步骤带序号），无步骤可直接添加 */
@Composable
private fun TrackTaskCard(task: Task, vm: TaskViewModel) {
    val steps by remember(task.uuid) { vm.steps(task.uuid) }.collectAsState(emptyList())
    var expanded by remember(task.uuid) { mutableStateOf(true) }
    var showAddStep by remember(task.uuid) { mutableStateOf(false) }
    val doneCount = steps.count { it.status == StepStatus.DONE }
    val total = steps.size

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TGColors.Card)
            .border(1.dp, TGColors.BorderSoft, RoundedCornerShape(12.dp))
    ) {
        // 卡头：点击展开/收起
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(TGColors.Violet, shape = RoundedCornerShape(3.dp))
                    .graphicsLayer { rotationZ = 45f }
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (total > 0) {
                    Text("$doneCount / $total 步骤", color = TGColors.InkMute, fontSize = 11.sp)
                }
            }
            Text(if (expanded) "收起 ▾" else "展开 ▸", color = TGColors.GoldDeep, fontSize = 12.sp)
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { doneCount.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = TGColors.Gold,
                trackColor = TGColors.BgPaperDeep
            )
            Spacer(Modifier.height(8.dp))
        }
        // 步骤下拉栏
        if (expanded) {
            if (steps.isEmpty()) {
                // 无步骤：直接在这里加步骤（不用再去详情页）
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("还没有步骤，拆解一下？", color = TGColors.InkMute, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAddStep = true }) {
                        TGIcon(R.drawable.ic_add, contentDescription = null, tint = TGColors.GoldDeep, size = 15.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("添加步骤", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    steps.forEachIndexed { index, step ->
                        TrackStepLine(index + 1, step, vm)
                    }
                    // 底部追加步骤入口
                    TextButton(onClick = { showAddStep = true }, modifier = Modifier.padding(top = 2.dp)) {
                        TGIcon(R.drawable.ic_add, contentDescription = null, tint = TGColors.GoldDeep, size = 15.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("添加步骤", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showAddStep) {
        AddStepDialog(
            currentCount = steps.size,
            onDismiss = { showAddStep = false },
            onConfirm = { title, label, value, insertAt ->
                if (title.isNotBlank()) vm.addStep(task.uuid, title.trim(), label.trim(), value.trim(), insertAt)
                showAddStep = false
            }
        )
    }
}

/** 追踪卡内的步骤行（带序号，当前步骤高亮） */
@Composable
private fun TrackStepLine(seq: Int, step: Step, vm: TaskViewModel) {
    val isDone = step.status == StepStatus.DONE
    val isDoing = step.status == StepStatus.DOING
    val bg = when {
        isDone -> TGColors.Jade.copy(alpha = 0.08f)
        isDoing -> TGColors.Gold.copy(alpha = 0.14f)
        else -> TGColors.BgPaperDeep.copy(alpha = 0.5f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号
        Text(
            "$seq",
            color = if (isDoing) TGColors.GoldDeep else TGColors.InkMute,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp)
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                color = if (isDone) TGColors.InkMute else TGColors.Ink,
                fontSize = 14.sp,
                textDecoration = if (isDone) TextDecoration.LineThrough else null
            )
            if (isDoing) {
                Text("进行中…", color = TGColors.GoldDeep, fontSize = 10.sp)
            }
            if (step.attrValue.isNotEmpty()) {
                Text("${step.attrLabel}: ${step.attrValue}", color = TGColors.GoldDeep, fontSize = 11.sp)
            }
        }
        if (isDone) {
            // 已完成：实心绿圆 + 白勾（一眼看出是完成态，不是按钮）
            Box(
                Modifier
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(TGColors.Jade),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // 未完成：空心圆环 + 勾（明显是"可点击完成"的按钮）
            PressIcon(onClick = { vm.advanceStep(step.uuid) }, modifier = Modifier.size(32.dp)) {
                Box(
                    Modifier
                        .size(22.dp)
                        .border(1.5.dp, if (isDoing) TGColors.Gold else TGColors.Jade, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    TGIcon(R.drawable.ic_check, contentDescription = "完成", tint = if (isDoing) TGColors.Gold else TGColors.Jade, size = 14.dp)
                }
            }
        }
    }
}

/** 通用步骤行（详情页等使用），seq 非空时显示序号 */
@Composable
fun StepRow(step: Step, vm: TaskViewModel, seq: Int? = null) {
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
        if (seq != null) {
            Text(
                "$seq",
                color = if (isDoing) TGColors.GoldDeep else TGColors.InkMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp)
            )
        } else {
            // 状态点
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(indicator))
        }
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
        if (isDone) {
            // 已完成：实心绿圆 + 白勾（与追踪卡一致）
            Box(
                Modifier
                    .size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(TGColors.Jade),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // 未完成：空心圆环 + 勾（可点击完成）
            PressIcon(onClick = { vm.advanceStep(step.uuid) }, modifier = Modifier.size(36.dp)) {
                Box(
                    Modifier
                        .size(24.dp)
                        .border(1.5.dp, if (isDoing) TGColors.Gold else TGColors.Jade, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    TGIcon(R.drawable.ic_check, contentDescription = "完成", tint = if (isDoing) TGColors.Gold else TGColors.Jade, size = 15.dp)
                }
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
                                        ToastHelper.show(ctx, "已打卡 ✓")
                                    } else {
                                        ToastHelper.show(ctx, "今天已打过卡了")
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
                                color = if (checkedToday) TGColors.InkMute else Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 所有任务（追踪/待办/未来/已完成 全量） ====================
@Composable
fun AllTasksScreen(vm: TaskViewModel, navController: NavController) {
    val tracking by vm.tracking.collectAsState()
    val main by vm.mainList.collectAsState()
    val future by vm.futureTasks.collectAsState()
    val archive by vm.archive.collectAsState()
    val todo = main.filter { it.trackStatus != TrackStatus.TRACKING }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 顶栏：返回 + 标题
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 8.dp, 4.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
            Text("所有任务", color = TGColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (tracking.isEmpty() && todo.isEmpty() && future.isEmpty() && archive.isEmpty()) {
            EmptyState("还没有任何任务", Modifier.fillMaxSize())
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (tracking.isNotEmpty()) {
                    item(key = "hdr-t") { SectionHeader("正在追踪 (${tracking.size})", TGColors.Violet) }
                    items(tracking, key = { "t-${it.uuid}" }) { task ->
                        TaskRow(task, vm, onClick = { navController.navigate("detail/${task.uuid}") }, onEdit = { navController.navigate("edit/${task.uuid}") })
                    }
                }
                if (todo.isNotEmpty()) {
                    item(key = "hdr-todo") { SectionHeader("待办 (${todo.size})", TGColors.GoldDeep) }
                    items(todo, key = { "todo-${it.uuid}" }) { task ->
                        TaskRow(task, vm, onClick = { navController.navigate("detail/${task.uuid}") }, onEdit = { navController.navigate("edit/${task.uuid}") })
                    }
                }
                if (future.isNotEmpty()) {
                    item(key = "hdr-f") { SectionHeader("未来任务 (${future.size})", TGColors.Azure) }
                    items(future, key = { "f-${it.uuid}" }) { task ->
                        TaskRow(task, vm, onClick = { navController.navigate("detail/${task.uuid}") }, onEdit = { navController.navigate("edit/${task.uuid}") })
                    }
                }
                if (archive.isNotEmpty()) {
                    item(key = "hdr-a") { SectionHeader("已完成 (${archive.size})", TGColors.Jade) }
                    items(archive, key = { "a-${it.uuid}" }) { task ->
                        DoneTaskRow(task, vm)
                    }
                }
            }
        }
    }
}

// ==================== 历史任务（已完成） ====================
@Composable
fun HistoryScreen(vm: TaskViewModel, navController: NavController) {
    val archive by vm.archive.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 8.dp, 4.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
            Text("历史任务", color = TGColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (archive.isEmpty()) {
            EmptyState("还没有已完成的任务\n完成的任务会自动收进这里", Modifier.fillMaxSize())
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(archive, key = { it.uuid }) { task ->
                    DoneTaskRow(task, vm)
                }
            }
        }
    }
}

/** 已完成任务行（划线 + 完成时间 + 恢复） */
@Composable
private fun DoneTaskRow(task: Task, vm: TaskViewModel) {
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


// ==================== 顶部栏（合并主页/我的/追踪的标题栏）+ 人物边框头像（我的入口） ====================
@Composable
fun AppTopBar(currentRoute: String?, vm: TaskViewModel, navController: NavController) {
    Surface(color = TGColors.PanelSolid, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        when (currentRoute) {
            "home" -> {
                val points by vm.totalPoints.collectAsState()
                Row(
                    Modifier.fillMaxWidth().padding(8.dp, 10.dp, 12.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 人物边框头像 → 我的
                    AvatarFrame(onClick = { navController.navigate("profile") })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "任务栏",
                        color = TGColors.Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    // 积分 pill：米底深金字（克制，不抢眼）
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(TGColors.BgPaperDeep)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TGIcon(R.drawable.ic_coin, contentDescription = null, tint = TGColors.GoldDeep, size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("$points", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                    PressIcon(onClick = { navController.navigate("history") }) {
                        TGIcon(R.drawable.ic_archive, contentDescription = "历史任务", tint = TGColors.InkSoft, size = 22.dp)
                    }
                    Spacer(Modifier.width(4.dp))
                    PressIcon(onClick = { navController.navigate("all") }) {
                        TGIcon(R.drawable.ic_list, contentDescription = "所有任务", tint = TGColors.InkSoft, size = 22.dp)
                    }
                }
            }
            "profile" -> barWithTitle("我的", navController)
            "track" -> barWithTitle("追踪中", navController)
            else -> barWithTitle("任务栏", navController)
        }
    }
}

@Composable
private fun barWithTitle(title: String, navController: NavController) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp, 10.dp, 12.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
            TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
        }
        Spacer(Modifier.width(4.dp))
        Text(title, color = TGColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 人物边框头像（克制：圆形卡色底 + 金边 + 墨色人形），点开进入我的 */
@Composable
fun AvatarFrame(onClick: () -> Unit) {
    PressIcon(onClick = onClick) {
        Box(
            Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(TGColors.Card)
                .border(1.5.dp, TGColors.Gold, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            TGIcon(
                drawable = R.drawable.ic_avatar,
                contentDescription = "我的",
                tint = TGColors.Ink,
                size = 20.dp
            )
        }
    }
}

/** 底部中央"追踪"大按钮（金色实底 + 深褐字 + 阴影——一眼看出是可点按钮）
 *  历史：黑底太黑看不清 → 卡色金边又太素 → 现在金色实底高对比 */
@Composable
fun CenterTrackingButton(navController: NavController) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        PressIcon(onClick = { navController.navigate("track") }) {
            Row(
                Modifier
                    .height(50.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(25.dp))
                    .background(TGColors.Gold)
                    .shadow(
                        elevation = 6.dp,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(25.dp),
                        ambientColor = TGColors.Gold.copy(alpha = 0.35f),
                        spotColor = TGColors.Gold.copy(alpha = 0.35f)
                    )
                    .padding(horizontal = 26.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TGIcon(
                    drawable = R.drawable.ic_track,
                    contentDescription = "追踪",
                    tint = TGColors.Ink,
                    size = 20.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "追踪",
                    color = TGColors.Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
