package com.taskbar.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val ctx = LocalContext.current
    val trackLimit by vm.trackLimit.collectAsState()

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
                        if (isTracking) vm.stopTracking(uuid) else vm.startTracking(uuid) { ok ->
                            if (!ok) ToastHelper.show(ctx, "追踪已达上限($trackLimit 个)，先取消别的追踪或在设置里调高上限")
                        }
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
                    // 进度：有步骤时显示真实步骤进度；习惯显示连续天数
                    if (task.type == TaskType.HABIT) {
                        var streak by remember(task.uuid) { mutableStateOf(0) }
                        LaunchedEffect(task.uuid) { streak = vm.habitStreak(task.uuid) }
                        RewardItem(R.drawable.ic_trophy, "坚持 $streak 天")
                    } else if (steps.isNotEmpty()) {
                        RewardItem(R.drawable.ic_trend, "步骤 ${steps.count { it.status == "done" }}/${steps.size}")
                    } else {
                        RewardItem(R.drawable.ic_trend, "进度 0/0")
                    }
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
        DelayDialog(onDismiss = { showDelayDialog = false }, onConfirm = { millis ->
            vm.delayTask(uuid, millis); showDelayDialog = false
        })
    }

    if (showAddStepDialog) {
        AddStepDialog(
            currentCount = steps.size,
            onDismiss = { showAddStepDialog = false },
            onConfirm = { title, label, value, insertAt ->
                if (title.isNotBlank()) vm.addStep(uuid, title.trim(), label.trim(), value.trim(), insertAt)
                showAddStepDialog = false
            }
        )
    }
}

/** 添加步骤弹窗：步骤名 + 可选属性/值 + 插入位置（默认最后），确认后一次性添加 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStepDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, Int?) -> Unit,
    currentCount: Int = 0,
    titleText: String = "添加步骤"
) {
    var title by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    // 插入位置：null = 追加到最后；0..count-1 = 插入到第 N 步之前
    var insertAt by remember { mutableStateOf<Int?>(null) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 弹窗打开自动聚焦步骤名输入框
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
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
                // 插入位置选择
                Text("插入位置", color = TGColors.InkSoft, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 最后（默认）
                    FilterChip(
                        selected = insertAt == null,
                        onClick = { insertAt = null },
                        label = { Text("最后") }
                    )
                    // 每个现有位置前插入：第1步..第count步
                    (0 until currentCount).forEach { i ->
                        FilterChip(
                            selected = insertAt == i,
                            onClick = { insertAt = i },
                            label = { Text("第${i + 1}步前") }
                        )
                    }
                }
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
            TextButton(onClick = { if (title.isNotBlank()) onConfirm(title, label, value, insertAt) }) {
                Text("确认添加", color = TGColors.GoldDeep, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 延迟对话框：预设（分钟~月）+ 数字 + 单位（分钟/小时/天/月），支持任意量级（如 5分钟 / 2个月） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelayDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var num by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("天") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    fun unitMillis(u: String): Long = when (u) {
        "分钟" -> 60_000L
        "小时" -> 3_600_000L
        "月" -> 30L * 86_400_000L
        else -> 86_400_000L  // 天
    }
    fun toMillis(n: Int, u: String) = n.toLong() * unitMillis(u)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("延迟任务") },
        text = {
            Column {
                // 预设（LazyRow 防挤屏，修复"5天旁边按键点不了划不了"）
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(
                        "5分钟" to 5L * 60_000L,
                        "1小时" to 3_600_000L,
                        "1天" to 86_400_000L,
                        "7天" to 7L * 86_400_000L,
                        "30天" to 30L * 86_400_000L
                    )) { (label, millis) ->
                        AssistChip(onClick = { onConfirm(millis) }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("或自定义延迟", color = TGColors.InkMute, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = num, onValueChange = { num = it.filter { c -> c.isDigit() }.take(4).ifEmpty { "1" } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        singleLine = true,
                        modifier = Modifier.width(90.dp).focusRequester(focusRequester)
                    )
                    // 单位选择：分钟/小时/天/月
                    listOf("分钟", "小时", "天", "月").forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("比如 5分钟、2小时、3天、2个月 都可以", color = TGColors.InkMute, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(toMillis(num.toIntOrNull() ?: 1, unit)) }) {
                Text("确定", color = TGColors.GoldDeep, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 添加/编辑任务 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskScreen(vm: TaskViewModel, navController: NavController, editUuid: String?) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TaskType.ONCE) }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var category by remember { mutableStateOf("") }
    // 提醒时间：直接存 dueAt，null = 不提醒
    var dueAt by remember { mutableStateOf<Long?>(null) }
    // 习惯周期：null=不重复；"daily"=每天；"every2d"=隔1天；"weekly:1,3,5"=周一三五
    var habitRule by remember { mutableStateOf<String?>(null) }
    // 提醒方式：四通道多选 + 端选择（存储为 serializeConfig 格式）
    var reminderChannels by remember { mutableStateOf(listOf<String>()) }
    var reminderScope by remember { mutableStateOf(ReminderStrength.SCOPE_MOBILE) }
    // 里程碑目标次数
    var milestoneTarget by remember { mutableStateOf("1") }
    // 新建/编辑时临时添加的步骤（title, attrLabel, attrValue）——保存后批量入库
    var pendingSteps by remember { mutableStateOf(listOf<Triple<String, String, String>>()) }
    var showAddStepInline by remember { mutableStateOf(false) }
    var showJsonImport by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(editUuid) {
        if (editUuid != null) {
            val t = vm.observeTaskFlow(editUuid).first() ?: return@LaunchedEffect
            editing = t
            title = t.title; desc = t.desc; type = t.type; priority = t.priority
            category = t.category; dueAt = t.dueAt; habitRule = t.repeatRule
            // 编辑：解析任务已存配置
            val (ch, sc) = ReminderStrength.parseConfig(t.reminderStrength)
            reminderChannels = ch
            reminderScope = sc
            milestoneTarget = t.target.toString()
        } else {
            // 新建：初始选中全局默认（不再"跟随默认设置"，直接显示全局档）
            val global = ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE)
                .getString("reminder_strength", "notify") ?: "notify"
            val (ch, sc) = ReminderStrength.parseConfig(global)
            reminderChannels = ch
            reminderScope = sc
        }
    }

    val presetCategories = listOf("学习", "生活", "锻炼")
    var customCategories by remember { mutableStateOf(listOf<String>()) }
    var showAddCategory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 读用户自定义分类（持久化在 settings）
        customCategories = vm.getCustomCategories()
    }
    // 展示用分类全集：预设 + 自定义 + 当前选中（防止历史自定义分类不显示）
    val allCategories = remember(customCategories, category) {
        (presetCategories + customCategories +
            if (category.isNotBlank() && category !in presetCategories && category !in customCategories) listOf(category) else emptyList()
        ).distinct()
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
                // 右上角"JSON"：一键粘贴 AI 拆解好的步骤（新建页才显示，编辑页也可用）
                actions = {
                    TextButton(onClick = { showJsonImport = true }) {
                        Text("JSON", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
        // 用 LazyColumn 让整页可滚动（修复内容超屏后"保存"按钮看不到的问题）
        LazyColumn(
            Modifier.padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                OutlinedTextField(title, { title = it }, label = { Text("标题 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(desc, { desc = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

                // 类型
                Text("类型", color = TGColors.InkSoft, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(
                        TaskType.ONCE to "单次", TaskType.REPEAT to "重复", TaskType.NOTE to "速记",
                        TaskType.HABIT to "习惯", TaskType.GOAL to "目标", TaskType.MILESTONE to "里程碑"
                    )) { (v, l) ->
                        FilterChip(selected = type == v, onClick = { type = v }, label = { Text(l) })
                    }
                }
                if (type == TaskType.MILESTONE) {
                    Spacer(Modifier.height(6.dp))
                    Text("里程碑是能多次推进的大任务，达到目标次数才算真正完成（如：坚持跑步 10 次）", color = TGColors.InkMute, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目标次数", color = TGColors.InkSoft, fontSize = 13.sp)
                        OutlinedTextField(
                            value = milestoneTarget,
                            onValueChange = { v -> milestoneTarget = v.filter { c -> c.isDigit() }.take(3).ifEmpty { "1" } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(90.dp),
                            singleLine = true
                        )
                        Text("次", color = TGColors.InkSoft, fontSize = 13.sp)
                    }
                }

                // 优先级
                Text("优先级", color = TGColors.InkSoft, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Priority.HIGH to "高", Priority.MEDIUM to "中", Priority.LOW to "低").forEach { (v, l) ->
                        FilterChip(selected = priority == v, onClick = { priority = v }, label = { Text(l) })
                    }
                }

                // 分类（预设 + 自定义持久化 + 分类+；长按进入删除模式；横排可滑动）
                Text("分类", color = TGColors.InkSoft, fontSize = 13.sp)
                var deleteMode by remember { mutableStateOf(false) }
                var pendingDelete by remember { mutableStateOf<String?>(null) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    allCategories.forEach { c ->
                        // 分类 chip：点击选中；长按任意分类进入删除模式；删除模式下点击弹确认
                        Box {
                            CategoryChipButton(
                                label = c,
                                selected = category == c && !deleteMode,
                                onClick = { if (deleteMode) pendingDelete = c else category = c },
                                onLongClick = { deleteMode = true }
                            )
                            if (deleteMode) {
                                // 红色圆形 ×（右上角）
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-3).dp)
                                        .size(16.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(TGColors.Crimson),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("×", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    // 分类+：删除模式下变红色"完成"，点击退出删除模式
                    CategoryChipButton(
                        label = if (deleteMode) "完成" else "分类+",
                        selected = false,
                        onClick = { if (deleteMode) deleteMode = false else showAddCategory = true },
                        onLongClick = { deleteMode = true },
                        highlightColor = if (deleteMode) TGColors.Crimson else TGColors.GoldDeep
                    )
                }
                if (deleteMode) {
                    Text("点击分类右上角的红色 × 可以删除分类", color = TGColors.Crimson, fontSize = 11.sp)
                } else if (category.isBlank()) {
                    Text("点击分类或「分类+」新建自己的分类", color = TGColors.InkMute, fontSize = 11.sp)
                }
                // 自家风格确认弹窗（删除分类）
                pendingDelete?.let { c ->
                    TGConfirmDialog(
                        title = "删除分类",
                        message = "确定要删除分类「$c」吗？\n删除后该分类下已建的任务不受影响",
                        confirmText = "确定删除",
                        onConfirm = {
                            vm.removeCustomCategory(c)
                            if (category == c) category = ""
                            customCategories = customCategories - c
                            pendingDelete = null
                        },
                        onDismiss = { pendingDelete = null }
                    )
                }

                // 习惯类型：显示周期设置
                if (type == TaskType.HABIT) {
                    HabitCycleEditor(rule = habitRule, onChange = { habitRule = it })
                }

                // 提醒时间（非速记/非习惯）
                if (type != TaskType.NOTE && type != TaskType.HABIT) {
                    Text("提醒时间", color = TGColors.InkSoft, fontSize = 13.sp)
                    DueAtEditor(dueAt = dueAt, onChange = { dueAt = it })

                    Text("提醒方式", color = TGColors.InkSoft, fontSize = 13.sp)
                    Text("可多选组合（默认已按你的全局设置选中），点通道可切换", color = TGColors.InkMute, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    // 四通道多选
                    listOf(
                        ReminderStrength.NOTIFY to "通知栏",
                        ReminderStrength.VIBRATE to "振动",
                        ReminderStrength.BEEP to "提示音",
                        ReminderStrength.RING to "铃声"
                    ).forEach { (v, l) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                reminderChannels = if (v in reminderChannels) reminderChannels - v else reminderChannels + v
                            }
                        ) {
                            Checkbox(
                                checked = v in reminderChannels,
                                onCheckedChange = {
                                    reminderChannels = if (v in reminderChannels) reminderChannels - v else reminderChannels + v
                                },
                                colors = CheckboxDefaults.colors(checkedColor = TGColors.Gold)
                            )
                            Text(l, color = TGColors.Ink, fontSize = 13.sp)
                        }
                    }
                    // 端选择（单选）——每行两个，分两行防挤压
                    Spacer(Modifier.height(4.dp))
                    val scopeOpts = listOf(
                        ReminderStrength.SCOPE_NONE to "不提醒",
                        ReminderStrength.SCOPE_MOBILE to "仅手机",
                        ReminderStrength.SCOPE_PC to "仅电脑",
                        ReminderStrength.SCOPE_BOTH to "双端"
                    )
                    scopeOpts.chunked(2).forEach { rowOpts ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowOpts.forEach { (v, l) ->
                                FilterChip(selected = reminderScope == v, onClick = { reminderScope = v }, label = { Text(l) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (reminderScope == ReminderStrength.SCOPE_PC || reminderScope == ReminderStrength.SCOPE_BOTH) {
                        Spacer(Modifier.height(4.dp))
                        Text("需双端连接后同步提醒", color = TGColors.Azure, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 步骤（保存前先拆解好，保存后自动批量入库）
                Text("步骤（选填）", color = TGColors.InkSoft, fontSize = 13.sp)
                if (pendingSteps.isNotEmpty()) {
                    pendingSteps.forEachIndexed { i, (t, l, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("${i + 1}. $t", color = TGColors.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (v.isNotEmpty()) Text("$l: $v", color = TGColors.GoldDeep, fontSize = 11.sp)
                            IconButton(onClick = { pendingSteps = pendingSteps.filterIndexed { idx, _ -> idx != i } }) {
                                TGIcon(R.drawable.ic_close, contentDescription = "删除步骤", tint = TGColors.Crimson, size = 14.dp)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showAddStepInline = true }, modifier = Modifier.weight(1f)) {
                        TGIcon(R.drawable.ic_add, contentDescription = null, tint = TGColors.GoldDeep, size = 14.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("添加步骤", color = TGColors.GoldDeep, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { showJsonImport = true }, modifier = Modifier.weight(1f)) {
                        TGIcon(R.drawable.ic_forward, contentDescription = null, tint = TGColors.GoldDeep, size = 14.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("添加 JSON", color = TGColors.GoldDeep, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 完成积分预览
                Text(
                    "完成任务可得 ${com.taskbar.app.data.model.RewardRules.forTask(type, priority)} 积分",
                    color = TGColors.GoldDeep,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (title.isBlank()) return@Button
                        // 必选分类：没选分类不允许保存（boss 需求：不能无分类建任务）
                        if (category.isBlank()) {
                            ToastHelper.show(ctx, "请先选择分类")
                            return@Button
                        }
                        // 习惯类型的 dueAt 不使用（习惯没 dueAt 概念），周期走 repeatRule
                        val finalDue = if (type == TaskType.HABIT) null else dueAt
                        val finalRepeat = when (type) {
                            TaskType.HABIT -> habitRule
                            TaskType.REPEAT -> "daily"
                            else -> null
                        }
                        val deadline = if (type == TaskType.GOAL) System.currentTimeMillis() + 30L * 86_400_000L else null

                        // 提醒配置序列化（四通道 + 端选择）；全不选=null（不提醒）
                        val remCfg = if (reminderChannels.isEmpty() || reminderScope == ReminderStrength.SCOPE_NONE) {
                            null
                        } else {
                            ReminderStrength.serializeConfig(reminderChannels, reminderScope)
                        }
                        if (editing != null) {
                            vm.updateTask(editing!!.copy(
                                title = title.trim(), desc = desc, type = type,
                                priority = priority, category = category, dueAt = finalDue,
                                repeatRule = finalRepeat, deadline = deadline,
                                reminderStrength = remCfg,
                                target = milestoneTarget.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            ))
                            // 批量添加新建的步骤
                            pendingSteps.forEach { (t, l, v) ->
                                if (t.isNotBlank()) vm.addStep(editing!!.uuid, t.trim(), l.trim(), v.trim())
                            }
                        } else {
                            vm.createTask(type, title.trim(), desc, category, priority, finalDue, finalRepeat, deadline,
                                reminderStrength = remCfg,
                                target = milestoneTarget.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                onCreated = { uuid ->
                                    pendingSteps.forEach { (t, l, v) ->
                                        if (t.isNotBlank()) vm.addStep(uuid, t.trim(), l.trim(), v.trim())
                                    }
                                }
                            )
                            // 新建非今日任务 → 提示放进任务仓库（主页只显示今天要做的）
                            val dayEnd = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
                                set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            if (finalDue != null && finalDue > dayEnd) {
                                val df = SimpleDateFormat("MM月dd日", Locale.getDefault())
                                ToastHelper.show(ctx, "已放入任务仓库（${df.format(Date(finalDue))} 到期，主页只显示今天任务）", Toast.LENGTH_LONG)
                            }
                        }
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)
                ) { Text("保存", color = TGColors.Ink) }
            }
        }
    }

    if (showAddCategory) {
        AddCategoryDialog(
            onDismiss = { showAddCategory = false },
            onConfirm = { name ->
                val n = name.trim()
                if (n.isNotEmpty()) {
                    vm.addCustomCategory(n)
                    customCategories = (customCategories + n).distinct()
                    category = n
                }
                showAddCategory = false
            }
        )
    }

    // 步骤弹窗（手动添加）
    if (showAddStepInline) {
        AddStepDialog(
            currentCount = pendingSteps.size,
            onDismiss = { showAddStepInline = false },
            onConfirm = { t, l, v, _ ->
                if (t.isNotBlank()) pendingSteps = pendingSteps + Triple(t.trim(), l.trim(), v.trim())
                showAddStepInline = false
            }
        )
    }
    // JSON 粘贴弹窗
    if (showJsonImport) {
        JsonImportDialog(
            onDismiss = { showJsonImport = false },
            onApply = { jsonTitle, steps ->
                if (jsonTitle != null && title.isBlank()) title = jsonTitle
                pendingSteps = pendingSteps + steps
                ToastHelper.show(ctx, "已添加 ${steps.size} 个步骤")
                showJsonImport = false
            }
        )
    }
}

/** JSON 粘贴弹窗：把 AI 拆解好的 JSON 粘进来一键生成步骤（右上角"JSON"入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsonImportDialog(
    onDismiss: () -> Unit,
    onApply: (String?, List<Triple<String, String, String>>) -> Unit
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val sample = """{
  "title": "（可选）任务标题",
  "steps": [
    { "title": "第一步", "attr_label": "用时", "attr_value": "30分钟" },
    { "title": "第二步" },
    { "title": "第三步", "attr_label": "距离", "attr_value": "5km" }
  ]
}"""
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TGColors.Card)
                .border(1.5.dp, TGColors.Gold, RoundedCornerShape(16.dp))
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("添加 JSON 步骤", color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("把 AI 拆解好的 JSON 粘贴进来，自动生成步骤", color = TGColors.InkMute, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("{\n  \"title\": \"...\",\n  \"steps\": [...]\n}") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                maxLines = 12
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(sample))
                    ToastHelper.show(ctx, "示例已复制，去 AI 那边照着格式拆解")
                }) { Text("复制示例", color = TGColors.GoldDeep, fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("取消", color = TGColors.InkSoft) }
                Spacer(Modifier.width(6.dp))
                Button(onClick = {
                    val (jt, steps) = parseStepsJson(text)
                    if (steps.isEmpty()) {
                        ToastHelper.show(ctx, "JSON 格式不对，请点「复制示例」对照格式")
                    } else {
                        onApply(jt, steps)
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)) {
                    Text("解析添加", color = TGColors.Ink, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 解析步骤 JSON：{"title":?, "steps":[{"title","attr_label","attr_value"}]} */
internal fun parseStepsJson(raw: String): Pair<String?, List<Triple<String, String, String>>> {
    val raw2 = raw.trim()
    if (raw2.isEmpty()) return null to emptyList()
    return try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw2).jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val arr = obj["steps"]?.jsonArray ?: return title to emptyList()
        val steps = arr.mapNotNull { el ->
            val o = el.jsonObject
            val t = o["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Triple(
                t,
                o["attr_label"]?.jsonPrimitive?.contentOrNull ?: "",
                o["attr_value"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
        title to steps
    } catch (_: Exception) {
        null to emptyList()
    }
}

/** 新建分类弹窗：命名后存入设置，下次新建任务可选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分类") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("分类名 *") },
                placeholder = { Text("如：副业、家庭") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("添加", color = TGColors.GoldDeep) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 提醒时间编辑器（快捷预设 + 可编辑日期 + 时间 + 清除） ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueAtEditor(dueAt: Long?, onChange: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val dfDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dfTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var showTimePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 手动输入日期（yyyy-MM-dd），带校验：格式错 → 提示 + 红框呼吸 + 只还原写错字段
    var dateInput by remember { mutableStateOf(if (dueAt != null) dfDate.format(Date(dueAt)) else "") }
    var dateError by remember { mutableStateOf(false) }

    // 红框呼吸闪烁动画（格式错误时提示）
    val errorAlpha = rememberInfiniteTransition(label = "dateErr").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "dateErrA"
    )
    val borderColor = if (dateError) TGColors.Crimson.copy(alpha = errorAlpha.value) else TGColors.BorderMid

    // 快捷预设（LazyRow 防挤屏；默认"不提醒"=初始状态）
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf(
            "不提醒" to null,
            "5分钟后" to (System.currentTimeMillis() + 5L * 60_000L),
            "1小时后" to (System.currentTimeMillis() + 3_600_000L),
            "明天" to startOfTomorrow(),
            "7天后" to (System.currentTimeMillis() + 7L * 86_400_000L),
            "30天后" to (System.currentTimeMillis() + 30L * 86_400_000L)
        )) { (label, ts) ->
            FilterChip(
                selected = (ts == null && dueAt == null) || (ts != null && dueAt != null && Math.abs(dueAt - ts) < 60_000L),
                onClick = {
                    dateError = false
                    onChange(ts)
                    dateInput = if (ts != null) dfDate.format(Date(ts)) else ""
                },
                label = { Text(label) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    /** 校验并修正日期输入：格式/范围错误 → 提示 + 只还原写错字段（如月=13 → 今天月） */
    fun applyDateInput(raw: String) {
        val s = raw.trim()
        val parts = s.split("-")
        if (parts.size != 3) {
            dateError = true
            ToastHelper.show(ctx, "您输入的日期格式不对，请重新输入")
            dateInput = dfDate.format(Date())
            return
        }
        val now = java.util.Calendar.getInstance()
        val y = parts[0].toIntOrNull()
        val m = parts[1].toIntOrNull()
        val d = parts[2].toIntOrNull()
        // 逐字段校验，错哪个还原哪个（用今天的值）
        var ok = true
        var ny = y; var nm = m; var nd = d
        if (y == null || y !in 2000..2100) { ny = now.get(java.util.Calendar.YEAR); ok = false }
        if (m == null || m !in 1..12) { nm = now.get(java.util.Calendar.MONTH) + 1; ok = false }
        if (d == null || d !in 1..31) { nd = now.get(java.util.Calendar.DAY_OF_MONTH); ok = false }
        val fixed = "%04d-%02d-%02d".format(ny, nm, nd)
        dateInput = fixed
        if (!ok) {
            dateError = true
            ToastHelper.show(ctx, "您输入的日期格式不对，已把写错的部分还原成今天")
            // 2 秒后恢复正常边框
            scope.launch { kotlinx.coroutines.delay(2000); dateError = false }
            return
        }
        dateError = false
        // 校验通过：更新提醒时间（保留原时分或当前时分）
        val cal = java.util.Calendar.getInstance()
        val base = if (dueAt != null) dueAt else System.currentTimeMillis()
        cal.timeInMillis = base
        cal.set(java.util.Calendar.YEAR, ny!!)
        cal.set(java.util.Calendar.MONTH, nm!! - 1)
        cal.set(java.util.Calendar.DAY_OF_MONTH, nd!!)
        onChange(cal.timeInMillis)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 手动输入日期（可编辑，校验后自动修正）
        OutlinedTextField(
            value = dateInput,
            onValueChange = {
                dateInput = it.filter { c -> c.isDigit() || c == '-' }.take(10)
                if (it.length >= 10) applyDateInput(it)
            },
            label = { Text("日期") },
            placeholder = { Text("选择日期") },
            isError = dateError,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor,
                unfocusedBorderColor = borderColor,
                errorBorderColor = TGColors.Crimson
            ),
            modifier = Modifier.weight(1f)
        )
        // 时间（系统 TimePicker 保留，boss 认可现状）
        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
            Text(if (dueAt != null) dfTime.format(Date(dueAt)) else "选时间")
        }
        if (dueAt != null) {
            TextButton(onClick = { onChange(null); dateInput = ""; dateError = false }) { Text("清除", color = TGColors.Crimson) }
        }
    }
    if (dateError) {
        Text("您输入的日期格式不对，请重新输入", color = TGColors.Crimson, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
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
        // LazyRow：7 个 chip 一行放不下时允许左右滑动（修复"只有一到六"挤屏）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(dayNames.size) { i ->
                val dow = i + 1
                val name = dayNames[i]
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

// ==================== 自家风格组件 ====================

/** 分类 chip：可长按（进入删除模式），highlightColor 控制文字色 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    highlightColor: Color = TGColors.GoldDeep
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) TGColors.Selected else TGColors.Card)
            .border(1.dp, if (selected) TGColors.Gold else TGColors.BorderSoft, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) highlightColor else TGColors.Ink, fontSize = 12.sp)
    }
}

/** 自家风格确认弹窗（米底金边 + 标题 + 消息 + 分色按钮），替代系统默认 AlertDialog */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TGConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    confirmColor: Color = TGColors.Crimson,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TGColors.Card)
                .border(1.5.dp, TGColors.Gold, RoundedCornerShape(16.dp))
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text(title, color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = TGColors.InkSoft, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 取消：金边米底
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TGColors.InkSoft)
                ) { Text("取消") }
                // 确认：按 confirmColor 引导
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = confirmColor)
                ) { Text(confirmText, color = Color.White, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
