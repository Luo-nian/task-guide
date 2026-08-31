package com.taskbar.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.taskbar.app.R
import com.taskbar.app.data.model.Priority
import com.taskbar.app.data.model.ReminderStrength
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
    if (taskState == null) {
        EmptyState("加载中…", Modifier.fillMaxSize())
        return
    }
    val task = taskState!!

    var showDelayDialog by remember { mutableStateOf(false) }
    var showAddStepDialog by remember { mutableStateOf(false) }
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "步骤 (${steps.count { it.status == "done" }}/${steps.size})",
                        color = TGColors.Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // 添加步骤：点按钮弹窗设置（不再内嵌表单，避免一进来就看到一堆输入框）
                    TextButton(onClick = { showAddStepDialog = true }) {
                        TGIcon(R.drawable.ic_add, contentDescription = null, tint = TGColors.GoldDeep, size = 16.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("添加步骤", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (steps.isEmpty()) {
                    Text("还没有步骤，点右上角「添加步骤」拆解它", color = TGColors.InkMute, fontSize = 12.sp)
                } else {
                    steps.forEachIndexed { i, step -> StepRow(step, vm, seq = i + 1); Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }

    if (showDelayDialog) {
        DelayDialog(onDismiss = { showDelayDialog = false }, onConfirm = { days ->
            vm.delayTask(uuid, days); showDelayDialog = false
        })
    }

    if (showAddStepDialog) {
        AddStepDialog(
            onDismiss = { showAddStepDialog = false },
            onConfirm = { title, label, value ->
                if (title.isNotBlank()) vm.addStep(uuid, title.trim(), label.trim(), value.trim())
                showAddStepDialog = false
            }
        )
    }
}

/** 添加步骤弹窗：步骤名 + 可选属性/值，确认后一次性添加 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStepDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 弹窗打开自动聚焦步骤名输入框
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加步骤", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("步骤名 *") },
                    placeholder = { Text("例如：通读第 1 章") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text("属性与值是选填的，比如「距离 5km」「用时 30分钟」，不用可以留空", color = TGColors.InkMute, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = label, onValueChange = { label = it },
                        label = { Text("属性(选填)") },
                        placeholder = { Text("如 距离") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = value, onValueChange = { value = it },
                        label = { Text("值(选填)") },
                        placeholder = { Text("如 5km") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onConfirm(title, label, value) }) {
                Text("确认添加", color = TGColors.GoldDeep, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 延迟对话框：预设天数 + 自定义输入（自动聚焦，可直接编辑） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelayDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("1") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 弹窗打开自动聚焦，输入框可直接编辑（修复之前点了没反应）
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("延迟任务") },
        text = {
            Column {
                Text("输入要延迟的天数", color = TGColors.InkMute, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { c -> c.isDigit() }.take(3).ifEmpty { "1" } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
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
    // 提醒时间：直接存 dueAt，null = 不提醒
    var dueAt by remember { mutableStateOf<Long?>(null) }
    // 习惯周期：null=不重复；"daily"=每天；"every2d"=隔1天；"weekly:1,3,5"=周一三五
    var habitRule by remember { mutableStateOf<String?>(null) }
    // 提醒强度：null=跟随设置；或选 standard/repeat/alarm
    var reminderStrength by remember { mutableStateOf<String?>(null) }

    var editing by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(editUuid) {
        if (editUuid != null) {
            val t = vm.observeTaskFlow(editUuid).first() ?: return@LaunchedEffect
            editing = t
            title = t.title; desc = t.desc; type = t.type; priority = t.priority
            category = t.category; dueAt = t.dueAt; habitRule = t.repeatRule
            reminderStrength = t.reminderStrength
        }
    }

    val categoryPresets = listOf("学习", "生活", "锻炼")

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

            // 类型
            Text("类型", color = TGColors.InkSoft, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(TaskType.ONCE to "单次", TaskType.REPEAT to "重复", TaskType.NOTE to "速记", TaskType.HABIT to "习惯", TaskType.GOAL to "目标")) { (v, l) ->
                    FilterChip(selected = type == v, onClick = { type = v }, label = { Text(l) })
                }
            }

            // 优先级
            Text("优先级", color = TGColors.InkSoft, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Priority.HIGH to "高", Priority.MEDIUM to "中", Priority.LOW to "低").forEach { (v, l) ->
                    FilterChip(selected = priority == v, onClick = { priority = v }, label = { Text(l) })
                }
            }

            // 分类（预设 + 自定义）
            Text("分类", color = TGColors.InkSoft, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                categoryPresets.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("自定义分类") },
                placeholder = { Text("如：副业、家庭、副业...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 习惯类型：显示周期设置
            if (type == TaskType.HABIT) {
                HabitCycleEditor(rule = habitRule, onChange = { habitRule = it })
            }

            // 提醒时间（非速记/非习惯）
            if (type != TaskType.NOTE && type != TaskType.HABIT) {
                Text("提醒时间", color = TGColors.InkSoft, fontSize = 13.sp)
                DueAtEditor(dueAt = dueAt, onChange = { dueAt = it })

                // 提醒强度
                Text("提醒方式", color = TGColors.InkSoft, fontSize = 13.sp)
                Column {
                    listOf<String?>(null, ReminderStrength.STANDARD, ReminderStrength.REPEAT, ReminderStrength.ALARM).forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { reminderStrength = v }) {
                            RadioButton(selected = reminderStrength == v, onClick = { reminderStrength = v })
                            Text(
                                if (v == null) "跟随默认设置" else ReminderStrength.label(v),
                                color = if (v == null) TGColors.InkMute else TGColors.Ink,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (title.isBlank()) return@Button
                    // 习惯类型的 dueAt 不使用（习惯没 dueAt 概念），周期走 repeatRule
                    val finalDue = if (type == TaskType.HABIT) null else dueAt
                    val finalRepeat = when (type) {
                        TaskType.HABIT -> habitRule
                        TaskType.REPEAT -> "daily"
                        else -> null
                    }
                    val deadline = if (type == TaskType.GOAL) System.currentTimeMillis() + 30L * 86_400_000L else null

                    if (editing != null) {
                        vm.updateTask(editing!!.copy(
                            title = title.trim(), desc = desc, type = type,
                            priority = priority, category = category, dueAt = finalDue,
                            repeatRule = finalRepeat, deadline = deadline,
                            reminderStrength = reminderStrength
                        ))
                    } else {
                        vm.createTask(type, title.trim(), desc, category, priority, finalDue, finalRepeat, deadline, reminderStrength = reminderStrength)
                    }
                    navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)
            ) { Text("保存", color = TGColors.Ink) }
        }
    }
}

// ==================== 提醒时间编辑器（日期 + 时间 + 清除） ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueAtEditor(dueAt: Long?, onChange: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val dfDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dfTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 快捷预设
        AssistChip(onClick = { onChange(System.currentTimeMillis() + 3_600_000L) }, label = { Text("1小时后") })
        AssistChip(onClick = { onChange(startOfTomorrow()) }, label = { Text("明天") })
        AssistChip(onClick = { onChange(System.currentTimeMillis() + 3L * 86_400_000L) }, label = { Text("3天后") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 自定义日期
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
            Text(if (dueAt != null) dfDate.format(Date(dueAt)) else "选日期")
        }
        // 自定义时间
        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
            Text(if (dueAt != null) dfTime.format(Date(dueAt)) else "选时间")
        }
        if (dueAt != null) {
            TextButton(onClick = { onChange(null) }) { Text("清除", color = TGColors.Crimson) }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dueAt ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = state.selectedDateMillis ?: return@TextButton
                    val cal = java.util.Calendar.getInstance()
                    val now = java.util.Calendar.getInstance()
                    cal.timeInMillis = date
                    // 编辑已有提醒：保留原时分；新建：用现在的时分
                    if (dueAt != null) {
                        val old = java.util.Calendar.getInstance().apply { timeInMillis = dueAt!! }
                        cal.set(java.util.Calendar.HOUR_OF_DAY, old.get(java.util.Calendar.HOUR_OF_DAY))
                        cal.set(java.util.Calendar.MINUTE, old.get(java.util.Calendar.MINUTE))
                    } else {
                        cal.set(java.util.Calendar.HOUR_OF_DAY, now.get(java.util.Calendar.HOUR_OF_DAY))
                        cal.set(java.util.Calendar.MINUTE, now.get(java.util.Calendar.MINUTE))
                    }
                    onChange(cal.timeInMillis)
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = if (dueAt != null) hourOf(dueAt) else java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = if (dueAt != null) minuteOf(dueAt) else java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = dueAt ?: System.currentTimeMillis()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, state.hour)
                    cal.set(java.util.Calendar.MINUTE, state.minute)
                    onChange(cal.timeInMillis)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }
}

private fun startOfTomorrow(): Long {
    val c = java.util.Calendar.getInstance()
    c.add(java.util.Calendar.DAY_OF_YEAR, 1)
    c.set(java.util.Calendar.HOUR_OF_DAY, 9)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun hourOf(ts: Long): Int {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = ts
    return c.get(java.util.Calendar.HOUR_OF_DAY)
}
private fun minuteOf(ts: Long): Int {
    val c = java.util.Calendar.getInstance(); c.timeInMillis = ts
    return c.get(java.util.Calendar.MINUTE)
}

// ==================== 习惯周期编辑器 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitCycleEditor(rule: String?, onChange: (String?) -> Unit) {
    var mode by remember(rule) { mutableStateOf(parseRule(rule)) }  // "daily" | "every" | "weekly" | null
    var everyN by remember(rule) { mutableStateOf(parseEveryN(rule).toString()) }  // 隔 N 天中的 N
    var selectedDays by remember(rule) { mutableStateOf(parseWeeklyDays(rule)) }  // 1-7 (周一到周日)

    Text("习惯周期", color = TGColors.InkSoft, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = mode == "daily", onClick = { mode = "daily"; onChange("daily") }, label = { Text("每天") })
        FilterChip(selected = mode == "every", onClick = { mode = "every"; onChange("every${everyN.coerceToInt(2)}d") }, label = { Text("隔天") })
        FilterChip(selected = mode == "weekly", onClick = { mode = "weekly"; onChange(serializeWeekly(selectedDays)) }, label = { Text("每周") })
    }
    if (mode == "every") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每 ", color = TGColors.Ink)
            OutlinedTextField(
                value = everyN,
                onValueChange = { v -> everyN = v.filter { c -> c.isDigit() }.take(2).ifEmpty { "2" } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(60.dp),
                singleLine = true
            )
            Text(" 天一次", color = TGColors.Ink)
        }
    }
    if (mode == "weekly") {
        val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dayNames.forEachIndexed { i, name ->
                val dow = i + 1
                FilterChip(
                    selected = dow in selectedDays,
                    onClick = {
                        val newDays = if (dow in selectedDays) selectedDays - dow else selectedDays + dow
                        selectedDays = newDays
                        onChange(serializeWeekly(newDays))
                    },
                    label = { Text(name) }
                )
            }
        }
    }
}

private fun String.coerceToInt(default: Int): Int = toIntOrNull()?.coerceAtLeast(1) ?: default

private fun parseRule(rule: String?): String = when {
    rule == "daily" -> "daily"
    rule == null -> "daily"
    rule.startsWith("every") -> "every"
    rule.startsWith("weekly") -> "weekly"
    else -> "daily"
}
private fun parseEveryN(rule: String?): Int {
    if (rule == null || !rule.startsWith("every")) return 2
    return rule.removePrefix("every").removeSuffix("d").toIntOrNull()?.coerceAtLeast(1) ?: 2
}
private fun parseWeeklyDays(rule: String?): Set<Int> {
    if (rule == null || !rule.startsWith("weekly")) return emptySet()
    val rest = rule.removePrefix("weekly").removePrefix(":")
    return rest.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
}
private fun serializeWeekly(days: Set<Int>): String? {
    if (days.isEmpty()) return null
    return "weekly:" + days.sorted().joinToString(",")
}

// ViewModel 扩展：暴露单个 task 的 Flow（供详情页用）
private fun TaskViewModel.observeTaskFlow(uuid: String) =
    (getApplication<com.taskbar.app.TaskBarApp>()).repo.observeTask(uuid)
