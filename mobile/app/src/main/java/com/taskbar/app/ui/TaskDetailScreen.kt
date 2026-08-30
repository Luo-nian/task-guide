package com.taskbar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.taskbar.app.R
import com.taskbar.app.data.model.Priority
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TaskType
import com.taskbar.app.data.model.TrackStatus
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================== 任务详情 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(vm: TaskViewModel, navController: NavController, uuid: String) {
    val taskState by vm.observeTaskFlow(uuid).collectAsStateWithLifecycle(initialValue = null)
    val steps by vm.steps(uuid).collectAsStateWithLifecycle(initialValue = emptyList())
    val task = taskState ?: run {
        EmptyState("加载中…", Modifier.fillMaxSize()); return
    }

    var showDelayDialog by remember { mutableStateOf(false) }
    var newStepTitle by remember { mutableStateOf("") }
    var newStepAttrLabel by remember { mutableStateOf("") }
    var newStepAttrValue by remember { mutableStateOf("") }
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            // 标题区
            TGCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(task.type); Spacer(Modifier.width(6.dp)); PriorityChip(task.priority)
                }
                Spacer(Modifier.height(8.dp))
                Text(task.title, color = TGColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (task.desc.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp)); Text(task.desc, color = TGColors.InkSoft, fontSize = 13.sp)
                }
                task.dueAt?.let {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TGIcon(R.drawable.ic_bell, contentDescription = null, tint = TGColors.GoldDeep, size = 13.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("提醒 ${dateFmt.format(Date(it))}", color = TGColors.GoldDeep, fontSize = 12.sp)
                    }
                }
                task.deadline?.let {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TGIcon(R.drawable.ic_clock, contentDescription = null, tint = TGColors.Crimson, size = 13.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("截止 ${dateFmt.format(Date(it))}", color = TGColors.Crimson, fontSize = 12.sp)
                    }
                }
            }
        }
        // 操作按钮
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isTracking = task.trackStatus == TrackStatus.TRACKING
                Button(
                    onClick = {
                        if (isTracking) vm.stopTracking(uuid) else vm.startTracking(uuid)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTracking) TGColors.Selected else TGColors.Gold,
                        contentColor = if (isTracking) TGColors.Violet else TGColors.Ink
                    )
                ) {
                    TGIcon(
                        R.drawable.ic_track,
                        contentDescription = null,
                        tint = if (isTracking) TGColors.Violet else TGColors.Ink,
                        size = 16.dp
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (isTracking) "当前追踪中·停止" else "追踪目标",
                        fontWeight = FontWeight.Medium
                    )
                }
                Button(
                    onClick = { vm.completeTask(uuid); navController.popBackStack() },
                    colors = ButtonDefaults.buttonColors(containerColor = TGColors.Jade)
                ) { Text("完成", color = Color.White) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { showDelayDialog = true }) { Text("延迟") }
                OutlinedButton(onClick = { navController.navigate("edit/$uuid") }) { Text("编辑") }
                OutlinedButton(
                    onClick = { vm.deleteTask(uuid); navController.popBackStack() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TGColors.Crimson)
                ) { Text("删除") }
            }
        }
        // 奖励区
        item {
            TGCard(Modifier.fillMaxWidth()) {
                Text("完成任务可获得", color = TGColors.InkMute, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RewardItem(R.drawable.ic_coin, "+${task.rewardPoints}", highlight = true)
                    RewardItem(R.drawable.ic_trend, "进度")
                    RewardItem(R.drawable.ic_trophy, "坚持")
                }
            }
        }
        // 步骤内嵌子模块
        item {
            TGCard(Modifier.fillMaxWidth()) {
                Text(
                    "步骤 (${steps.count { it.status == "done" }}/${steps.size})",
                    color = TGColors.Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                steps.forEach { step -> StepRow(step, vm); Spacer(Modifier.height(4.dp)) }
                Spacer(Modifier.height(8.dp))
                // 新增步骤输入
                OutlinedTextField(value = newStepTitle, onValueChange = { newStepTitle = it },
                    label = { Text("步骤名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = newStepAttrLabel, onValueChange = { newStepAttrLabel = it },
                        label = { Text("属性(如距离)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newStepAttrValue, onValueChange = { newStepAttrValue = it },
                        label = { Text("值(如5km)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    if (newStepTitle.isNotBlank()) {
                        vm.addStep(uuid, newStepTitle.trim(), newStepAttrLabel.trim(), newStepAttrValue.trim())
                        newStepTitle = ""; newStepAttrLabel = ""; newStepAttrValue = ""
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)) {
                    Text("添加步骤", color = TGColors.Ink)
                }
            }
        }
    }

    if (showDelayDialog) {
        DelayDialog(onDismiss = { showDelayDialog = false }, onConfirm = { days ->
            vm.delayTask(uuid, days); showDelayDialog = false
        })
    }
}

/** 延迟对话框：自定义天数 */
@Composable
private fun DelayDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("延迟任务") },
        text = {
            Column {
                Text("输入延迟天数", color = TGColors.InkMute, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 5, 7, 14).forEach { d ->
                        AssistChip(onClick = { text = d.toString() }, label = { Text("${d}天") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.toIntOrNull() ?: 1) }) { Text("确定", color = TGColors.GoldDeep) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 添加/编辑任务 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskScreen(vm: TaskViewModel, navController: NavController, editUuid: String?) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TaskType.ONCE) }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var category by remember { mutableStateOf("") }
    var delayIdx by remember { mutableStateOf(0) }  // 提醒快捷选项索引

    val delayOptions = listOf("不提醒", "1小时后", "明天", "3天后", "7天后")
    val delayMillis = listOf(null, 3_600_000L, 86_400_000L, 3 * 86_400_000L, 7 * 86_400_000L)

    var editing by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(editUuid) {
        if (editUuid != null) {
            val t = vm.observeTaskFlow(editUuid).first() ?: return@LaunchedEffect
            editing = t
            title = t.title; desc = t.desc; type = t.type; priority = t.priority; category = t.category
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (editUuid == null) "新建任务" else "编辑任务", color = TGColors.Ink) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 20.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TGColors.PanelSolid,
                    titleContentColor = TGColors.Ink,
                    navigationIconContentColor = TGColors.Ink
                )
            )
        }
    ) { p ->
        Column(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("标题 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(desc, { desc = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            Text("类型", color = TGColors.InkSoft, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(TaskType.ONCE to "单次", TaskType.REPEAT to "重复", TaskType.NOTE to "速记", TaskType.HABIT to "习惯", TaskType.GOAL to "目标")) { (v, l) ->
                    FilterChip(selected = type == v, onClick = { type = v }, label = { Text(l) })
                }
            }
            Text("优先级", color = TGColors.InkSoft, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Priority.HIGH to "高", Priority.MEDIUM to "中", Priority.LOW to "低").forEach { (v, l) ->
                    FilterChip(selected = priority == v, onClick = { priority = v }, label = { Text(l) })
                }
            }
            OutlinedTextField(category, { category = it }, label = { Text("分类(学习/生活/锻炼...)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (type != TaskType.NOTE && type != TaskType.HABIT) {
                Text("提醒", color = TGColors.InkSoft, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    delayOptions.forEachIndexed { idx, l ->
                        FilterChip(selected = delayIdx == idx, onClick = { delayIdx = idx }, label = { Text(l) })
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (title.isBlank()) return@Button
                    val dueAt = if (type != TaskType.NOTE && type != TaskType.HABIT) {
                        delayMillis[delayIdx]?.let { System.currentTimeMillis() + it }
                    } else null
                    val repeatRule = if (type == TaskType.REPEAT) "daily" else null
                    val deadline = if (type == TaskType.GOAL) System.currentTimeMillis() + 30L * 86_400_000L else null

                    if (editing != null) {
                        vm.updateTask(editing!!.copy(
                            title = title.trim(), desc = desc, type = type,
                            priority = priority, category = category, dueAt = dueAt,
                            repeatRule = repeatRule, deadline = deadline
                        ))
                    } else {
                        vm.createTask(type, title.trim(), desc, category, priority, dueAt, repeatRule, deadline)
                    }
                    navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)
            ) { Text("保存", color = TGColors.Ink) }
        }
    }
}

// ViewModel 扩展：暴露单个 task 的 Flow（供详情页用）
private fun TaskViewModel.observeTaskFlow(uuid: String) =
    (getApplication<com.taskbar.app.TaskBarApp>()).repo.observeTask(uuid)
