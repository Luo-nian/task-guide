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
import androidx.compose.ui.text.font.FontFamily
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
/**
 * @param readOnly v5.15.22 M9（boss：「日历里点任务只能看详情，不能编辑、不能完成、
 *   不能有任何功能按键，否则可以通过日历刷分」）—— 只读模式只渲染信息，
 *   整个操作区（追踪/完成/延迟/编辑/删除）、添加步骤、步骤打勾键全部不渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(vm: TaskViewModel, navController: NavController, uuid: String, readOnly: Boolean = false) {
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

    Column(Modifier.fillMaxSize()) {
        // v5.15.16：左上角返回键（boss：不要完全依赖系统返回；系统返回依然可用）
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Text(if (readOnly) "任务详情 · 只读" else "任务详情", color = TGColors.InkSoft, fontSize = 14.sp)
        }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            // 标题区
            TGCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(task.type); Spacer(Modifier.width(6.dp)); PriorityChip(task.priority)
                }
                Spacer(Modifier.height(8.dp))
                // v5.15.22 D5：详情任务名是本页主角 → 衬线 + 21sp
                Text(task.title, color = TGColors.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
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
        // 操作按钮（v5.15.22 M9：只读模式整块不渲染 —— 防日历刷分）
        if (!readOnly) item {
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
                    }
                    // v5.15.27 M12b：没有步骤时**不再显示「进度 0/0」** ——
                    //   空进度条没有任何信息量，只是噪音（与上面「步骤 (0/0)」同一处意图）
                }
            }
        }
        // 步骤内嵌子模块
        item {
            TGCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        // v5.15.27 M12（boss：「查看任务详情页 如果没有步骤 不要显示步骤（0/0））」）——
                        //   没有步骤时标题只写「步骤」，别摆一个 0/0 的空进度
                        if (steps.isEmpty()) "步骤" else "步骤 (${steps.count { it.status == "done" }}/${steps.size})",
                        color = TGColors.Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // 添加步骤：点按钮弹窗设置（不再内嵌表单，避免一进来就看到一堆输入框）
                    // v5.15.22 M9：只读模式不给这个入口
                    if (!readOnly) {
                        TextButton(onClick = { showAddStepDialog = true }) {
                            TGIcon(R.drawable.ic_add, contentDescription = null, tint = TGColors.GoldDeep, size = 16.dp)
                            Spacer(Modifier.width(3.dp))
                            Text("添加步骤", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (steps.isEmpty()) {
                    // v5.15.22 M9：只读模式下不给"添加步骤"的引导（入口已隐藏）
                    Text(
                        if (readOnly) "这个任务还没有步骤（只读模式）" else "还没有步骤，点右上角「添加步骤」拆解它",
                        color = TGColors.InkMute, fontSize = 12.sp
                    )
                } else {
                    steps.forEachIndexed { i, step -> StepRow(step, vm, seq = i + 1, readOnly = readOnly); Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
    }   // v5.15.16：关闭新增的 Column（左上角返回键那一行）

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
            },
            onJsonImport = { steps2 ->
                if (steps2.isNotEmpty()) {
                    steps2.forEach { (t, l, v) ->
                        if (t.isNotBlank()) vm.addStep(uuid, t.trim(), l.trim(), v.trim())
                    }
                    ToastHelper.show(ctx, "已添加 ${steps2.size} 个步骤")
                }
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
    titleText: String = "添加步骤",
    onJsonImport: ((List<Triple<String, String, String>>) -> Unit)? = null
) {
    var title by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    // 插入位置：null = 追加到最后；0..count-1 = 插入到第 N 步之前
    var insertAt by remember { mutableStateOf<Int?>(null) }
    // JSON 模式切换（弹窗内嵌 AI 拆解粘贴）
    var jsonMode by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titleText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // JSON 模式切换（仅在有 onJsonImport 回调时显示）
                if (onJsonImport != null) {
                    TextButton(onClick = { jsonMode = !jsonMode }) {
                        Text(
                            if (jsonMode) "单步" else "JSON",
                            color = TGColors.GoldDeep,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        text = {
            if (jsonMode && onJsonImport != null) {
                // JSON 粘贴模式：多行输入 + 解析+应用 + 复制示例
                Column {
                    Text(
                        "把 AI 拆好的 JSON 粘到下面，解析后批量加成本任务的步骤。",
                        color = TGColors.InkMute, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        placeholder = { Text("""{"title":"步骤名","steps":[{"title":"...","attr_label":"距离","attr_value":"5km"}, ...]}""", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val example = """{"title":"示例任务","steps":[{"title":"通读第 1 章","attr_label":"","attr_value":""},{"title":"整理笔记","attr_label":"用时","attr_value":"30 分钟"}]}"""
                            // 复制到剪贴板
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("taskguide steps json", example))
                            ToastHelper.show(ctx, "已复制示例 JSON")
                        }) {
                            Text("复制示例", color = TGColors.GoldDeep, fontSize = 13.sp)
                        }
                    }
                }
            } else {
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
                        FilterChip(
                            selected = insertAt == null,
                            onClick = { insertAt = null },
                            label = { Text("最后") }
                        )
                        (0 until currentCount).forEach { i ->
                            FilterChip(
                                selected = insertAt == i,
                                onClick = { insertAt = i },
                                label = { Text("第${i + 1}步前") }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("选填，如「距离 5km」", color = TGColors.InkMute, fontSize = 12.sp)
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
            }
        },
        confirmButton = {
            if (jsonMode && onJsonImport != null) {
                TextButton(onClick = {
                    val steps = com.taskbar.app.ui.parseStepsJson(jsonText).second
                    if (steps.isNotEmpty()) onJsonImport(steps)
                }) {
                    Text("解析并应用", color = TGColors.GoldDeep, fontWeight = FontWeight.Medium)
                }
            } else {
                TextButton(onClick = { if (title.isNotBlank()) onConfirm(title, label, value, insertAt) }) {
                    Text("确认添加", color = TGColors.GoldDeep, fontWeight = FontWeight.Medium)
                }
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
    // v5.15.16：预设只做「选中」（boss：点七天直接就延迟了，确定键意义何在）
    var preset by remember { mutableStateOf<Long?>(null) }
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
                        FilterChip(
                            selected = preset == millis,
                            onClick = { preset = if (preset == millis) null else millis },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("或自定义延迟", color = TGColors.InkMute, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = num, onValueChange = { num = it.filter { c -> c.isDigit() }.take(4).ifEmpty { "1" }; preset = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        singleLine = true,
                        modifier = Modifier.width(90.dp).focusRequester(focusRequester)
                    )
                    // 单位选择：分钟/小时/天/月
                    listOf("分钟", "小时", "天", "月").forEach { u ->
                        FilterChip(selected = unit == u && preset == null, onClick = { unit = u; preset = null }, label = { Text(u) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("比如 5分钟、2小时、3天、2个月 都可以", color = TGColors.InkMute, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(preset ?: toMillis(num.toIntOrNull() ?: 1, unit)) }) {
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
    // v5.15.12：任务类型与电脑端统一为 4 类（daily/goal/time-limited/once）
    //   boss：「手机端和电脑端的任务类型还存在不一样的？命名和数量都不一样 同步一下啊你！」
    var catSel by remember { mutableStateOf("daily") }
    // 目标任务是否启用截止时间（默认关闭，避免"不提醒也摆一堆日期控件"）
    var goalDeadlineOn by remember { mutableStateOf(false) }
    // 由分类派生的内部 type（保持与桌面端 category_to_type 完全一致的映射）
    val type: String = when (catSel) {
        "daily" -> TaskType.HABIT
        "goal" -> TaskType.GOAL
        "time-limited" -> TaskType.REPEAT
        else -> TaskType.ONCE
    }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var category by remember { mutableStateOf("") }
    // 提醒时间：直接存 dueAt，null = 不提醒
    var dueAt by remember { mutableStateOf<Long?>(null) }
    // 习惯周期：null=不重复；"daily"=每天；"every2d"=隔1天；"weekly:1,3,5"=周一三五
    var habitRule by remember { mutableStateOf<String?>(null) }
    // 提醒方式/端：新建时直接在 remember 里算好默认值。
    // v5.15.10：旧写法是在 LaunchedEffect 里读 prefs 再回写 state → 首帧后必然触发一次
    //   全屏重组（实测进入本页 90th 550~600ms 的卡顿来源）。现在首帧就把默认值算出来。
    val reminderDefaults = remember {
        val g = ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE)
            .getString("reminder_strength", "notify") ?: "notify"
        ReminderStrength.parseConfig(g)
    }
    // 提醒方式：四通道多选 + 端选择（存储为 serializeConfig 格式）
    var reminderChannels by remember { mutableStateOf(reminderDefaults.first) }
    var reminderScope by remember { mutableStateOf(reminderDefaults.second) }
    // 限时任务不允许"不提醒"
    LaunchedEffect(catSel) {
        if (catSel == "time-limited" && reminderScope == ReminderStrength.SCOPE_NONE) {
            reminderScope = ReminderStrength.SCOPE_MOBILE
        }
    }
    // v5.15.12：切换端后，如果该端一个通道都没勾 → 自动补一个默认（避免"选了端却没有任何方式"）
    LaunchedEffect(reminderScope) {
        when (reminderScope) {
            ReminderStrength.SCOPE_MOBILE ->
                if (reminderChannels.none { !ReminderStrength.isPcChannel(it) }) reminderChannels = reminderChannels + ReminderStrength.NOTIFY
            ReminderStrength.SCOPE_PC ->
                if (reminderChannels.none { ReminderStrength.isPcChannel(it) }) reminderChannels = reminderChannels + ReminderStrength.POPUP
            ReminderStrength.SCOPE_BOTH -> {
                if (reminderChannels.none { !ReminderStrength.isPcChannel(it) }) reminderChannels = reminderChannels + ReminderStrength.NOTIFY
                if (reminderChannels.none { ReminderStrength.isPcChannel(it) }) reminderChannels = reminderChannels + ReminderStrength.POPUP
            }
        }
    }
    // 里程碑目标次数
    var milestoneTarget by remember { mutableStateOf("1") }
    // 新建/编辑时临时添加的步骤（title, attrLabel, attrValue）——保存后批量入库
    var pendingSteps by remember { mutableStateOf(listOf<Triple<String, String, String>>()) }
    var showAddStepInline by remember { mutableStateOf(false) }
    var showJsonImport by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(editUuid) {
        // 只有"编辑已有任务"才需要读一次 DB 回填（新建走上面的 remember 默认值）
        if (editUuid != null) {
            val t = vm.observeTaskFlow(editUuid).first() ?: return@LaunchedEffect
            editing = t
            title = t.title; desc = t.desc; priority = t.priority
            // v5.15.12：类型回填 —— 优先读 category（新口径），旧数据按 type 反推
            catSel = when {
                t.category == "daily" || t.category == "goal" ||
                    t.category == "time-limited" || t.category == "once" -> t.category
                t.type == TaskType.HABIT -> "daily"
                t.type == TaskType.GOAL || t.type == TaskType.MILESTONE -> "goal"
                t.type == TaskType.REPEAT -> "time-limited"
                else -> "once"
            }
            category = t.category; dueAt = t.dueAt; habitRule = t.repeatRule
            if (catSel == "goal" && t.dueAt != null) goalDeadlineOn = true
            // 编辑：解析任务已存配置
            val (ch, sc) = ReminderStrength.parseConfig(t.reminderStrength)
            reminderChannels = ch
            reminderScope = sc
            milestoneTarget = t.target.toString()
        }
    }

    val presetCategories = listOf("学习", "生活", "锻炼")
    // v5.15.10：分类从 ViewModel 预读值初始化（不再在 LaunchedEffect 里查询+回写 → 少一次全屏重组）
    val vmCategories by vm.customCategories.collectAsState()
    var customCategories by remember(vmCategories) { mutableStateOf(vmCategories) }
    var showAddCategory by remember { mutableStateOf(false) }
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
                // v5.15.13：右上角不再放 JSON（步骤区里已有「添加 JSON」入口）
                // v5.15.16：编辑页右上角加删除键（红垃圾桶 + 二次确认）
                actions = {
                    if (editUuid != null) {
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            TGIcon(R.drawable.ic_delete, contentDescription = "删除任务", tint = TGColors.Crimson, size = 18.dp)
                        }
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

                // 类型（与电脑端同一套：每日 / 目标 / 限时 / 次数）
                Text("类型", color = TGColors.InkSoft, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(
                        "daily" to "每日任务", "goal" to "目标任务",
                        "time-limited" to "限时任务", "once" to "次数任务"
                    )) { (v, l) ->
                        FilterChip(selected = catSel == v, onClick = { catSel = v }, label = { Text(l) })
                    }
                }
                Text(
                    when (catSel) {
                        "daily" -> "每天固定时间提醒，第二天自动回到待办"
                        "goal" -> "默认不限时，也可以设置截止时间"
                        "time-limited" -> "必须选截止时间，到期前会提醒"
                        else -> "每完成一次记一次数，满次数结算"
                    },
                    color = TGColors.InkMute, fontSize = 11.sp
                )
                if (catSel == "once") {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("次数", color = TGColors.InkSoft, fontSize = 13.sp)
                        OutlinedTextField(
                            value = milestoneTarget,
                            onValueChange = { v -> milestoneTarget = v.filter { c -> c.isDigit() }.take(3).ifEmpty { "1" } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(90.dp),
                            singleLine = true
                        )
                        Text("次", color = TGColors.InkSoft, fontSize = 12.sp)
                    }
                } else if (catSel == "daily") {
                    // v5.15.13：每日任务只选「每天几点」（原来给整张日期选择器，还会出现"明天"）
                    Spacer(Modifier.height(6.dp))
                    Text("每天提醒时间", color = TGColors.InkSoft, fontSize = 13.sp)
                    DailyTimeEditor(dueAt = dueAt, onChange = { dueAt = it })
                } else if (catSel == "time-limited") {
                    Spacer(Modifier.height(6.dp))
                    Text("截止时间", color = TGColors.InkSoft, fontSize = 13.sp)
                    DueAtEditor(dueAt = dueAt, onChange = { dueAt = it })
                } else {
                    // 目标任务：默认不限时，需要时再打开截止时间
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("截止时间（可选）", color = TGColors.InkSoft, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = goalDeadlineOn, onCheckedChange = { on ->
                            goalDeadlineOn = on
                            if (!on) dueAt = null
                        })
                    }
                    if (goalDeadlineOn) DueAtEditor(dueAt = dueAt, onChange = { dueAt = it })
                }

                // 优先级
                Text("优先级", color = TGColors.InkSoft, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // v5.15.22 M7（boss：点到「高」「中」「低」时颜色应该有所区分）——
                    //   原来三个档位共用一个 FilterChip 配色，选中态长得一模一样；
                    //   换成按优先级上色的 PriorityFilterChip。
                    listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW).forEach { v ->
                        PriorityFilterChip(priority = v, selected = priority == v, onClick = { priority = v })
                    }
                }


                // 习惯类型：显示周期设置
                if (type == TaskType.HABIT) {
                    HabitCycleEditor(rule = habitRule, onChange = { habitRule = it })
                }

                // 提醒（v5.15.10：字段顺序与电脑端「新建任务」弹窗完全一致 ——
                //   端（不提醒/仅手机/仅电脑/双端）→ 方式（四通道多选）→ 时间；
                //   选「不提醒」时方式与时间整块收起，与电脑端同一套逻辑）
                // v5.15.23 M13（boss：「目标任务如果没有设置截止时间，那下面的提醒方式之类的也没必要了」）——
                //   目标类型且未开截止时间 → 整块提醒设置不渲染。
                if (!(catSel == "goal" && !goalDeadlineOn)) run {
                    Text("提醒", color = TGColors.InkSoft, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    // 1) 端（单选）——每行两个，防挤压
                    // v5.15.13：限时任务必须提醒 → 不提供「不提醒」选项
                    val scopeOpts = buildList {
                        if (catSel != "time-limited") add(ReminderStrength.SCOPE_NONE to "不提醒")
                        add(ReminderStrength.SCOPE_MOBILE to "仅手机")
                        add(ReminderStrength.SCOPE_PC to "仅电脑")
                        add(ReminderStrength.SCOPE_BOTH to "双端")
                    }
                    scopeOpts.chunked(2).forEach { rowOpts ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowOpts.forEach { (v, l) ->
                                FilterChip(selected = reminderScope == v, onClick = { reminderScope = v }, label = { Text(l) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (reminderScope != ReminderStrength.SCOPE_NONE) {
                        // 2) 方式 —— v5.15.12：按端分组（boss：选电脑端/双端时下面却只有手机端的方式）
                        Spacer(Modifier.height(6.dp))
                        Text("提醒方式（可多选）", color = TGColors.InkSoft, fontSize = 13.sp)
                        if (reminderScope == ReminderStrength.SCOPE_MOBILE || reminderScope == ReminderStrength.SCOPE_BOTH) {
                            Spacer(Modifier.height(4.dp))
                            Text("手机端", color = TGColors.GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            listOf(
                                ReminderStrength.NOTIFY to "通知栏",
                                ReminderStrength.VIBRATE to "振动",
                                ReminderStrength.RING to "响铃"
                            ).forEach { (v, l) -> ReminderChannelRow(v, l, reminderChannels) { reminderChannels = it } }
                        }
                        if (reminderScope == ReminderStrength.SCOPE_PC || reminderScope == ReminderStrength.SCOPE_BOTH) {
                            Spacer(Modifier.height(4.dp))
                            Text("电脑端", color = TGColors.Azure, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            listOf(
                                ReminderStrength.POPUP to "弹窗提醒",
                                ReminderStrength.FULLSCREEN to "全屏提醒",
                                ReminderStrength.INAPP to "应用内提示"
                            ).forEach { (v, l) -> ReminderChannelRow(v, l, reminderChannels) { reminderChannels = it } }
                        }
                        // v5.15.13：去掉无意义的"提前多久提醒/默认在设定时刻提醒"说明
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
                // 完成积分预览（v5.15.21 P2：次数任务按降分规则显示真实可得积分）
                Text(
                    "完成任务可得 ${com.taskbar.app.data.model.RewardRules.forTask(type, priority, milestoneTarget.toIntOrNull() ?: 1)} 积分",
                    color = TGColors.GoldDeep,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (title.isBlank()) { ToastHelper.show(ctx, "给任务起个名字吧"); return@Button }
                        // v5.15.12：类型 → (type / category / 重复规则 / 截止) 映射（与电脑端 add_task 一致）
                        if (catSel == "time-limited" && dueAt == null) {
                            ToastHelper.show(ctx, "限时任务请先选择截止时间")
                            return@Button
                        }
                        val finalCat = catSel
                        val finalDue = if (catSel == "once") null else dueAt
                        val finalRepeat = if (catSel == "daily") "daily" else null
                        val deadline = if (catSel == "time-limited") dueAt else null

                        // 提醒配置序列化（四通道 + 端选择）；全不选=null（不提醒）
                        val remCfg = if (reminderChannels.isEmpty() || reminderScope == ReminderStrength.SCOPE_NONE) {
                            null
                        } else {
                            ReminderStrength.serializeConfig(reminderChannels, reminderScope)
                        }
                        if (editing != null) {
                            vm.updateTask(editing!!.copy(
                                title = title.trim(), desc = desc, type = type,
                                priority = priority, category = finalCat, dueAt = finalDue,
                                repeatRule = finalRepeat, deadline = deadline,
                                reminderStrength = remCfg,
                                target = milestoneTarget.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            ))
                            // 批量添加新建的步骤
                            pendingSteps.forEach { (t, l, v) ->
                                if (t.isNotBlank()) vm.addStep(editing!!.uuid, t.trim(), l.trim(), v.trim())
                            }
                        } else {
                            vm.createTask(type, title.trim(), desc, finalCat, priority, finalDue, finalRepeat, deadline,
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

    // 删除任务二次确认
    if (showDeleteConfirm) {
        TGConfirmDialog(
            title = "删除任务",
            message = "确定删除这个任务吗？删除后无法恢复。",
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                editUuid?.let { vm.deleteTask(it) }
                navController.popBackStack()
            },
            onDismiss = { showDeleteConfirm = false }
        )
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

/** v5.15.12：提醒通道行（手机端/电脑端共用） */
@Composable
private fun ReminderChannelRow(
    value: String,
    label: String,
    selected: List<String>,
    onChange: (List<String>) -> Unit
) {
    val checked = value in selected
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable {
            onChange(if (checked) selected - value else selected + value)
        }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onChange(if (checked) selected - value else selected + value) },
            colors = CheckboxDefaults.colors(checkedColor = TGColors.Gold)
        )
        Text(label, color = TGColors.Ink, fontSize = 13.sp)
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
            Text("把 AI 拆好的 JSON 粘进来，自动生成步骤", color = TGColors.InkMute, fontSize = 11.sp)
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
                    ToastHelper.show(ctx, "已复制，发给 AI 让它照格式拆解")
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
/**
 * v5.15.13：每日任务专用「每天几点」选择器 —— 只挑时刻，不出现日期（boss：
 * 「每日任务的提醒时间设置为什么会有明天这种选项？都说是每日啊」）。
 * 内部把时刻换算成"今天该时刻（已过则明天）"的时间戳存进 dueAt。
 */
@Composable
private fun DailyTimeEditor(dueAt: Long?, onChange: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val cal = remember(dueAt) {
        java.util.Calendar.getInstance().apply {
            if (dueAt != null) timeInMillis = dueAt else { set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0) }
        }
    }
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    fun at(h: Int, m: Int): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, h)
        set(java.util.Calendar.MINUTE, m)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(showPicker) {
        if (showPicker) {
            android.app.TimePickerDialog(ctx, { _, h, m -> onChange(at(h, m)) }, hour, minute, true).show()
            showPicker = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(TGColors.Gold.copy(alpha = 0.16f))
                .border(1.dp, TGColors.Gold, RoundedCornerShape(999.dp))
                .clickable { showPicker = true }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                "每天 " + String.format("%02d:%02d", hour, minute),
                color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Text("点这里改时间", color = TGColors.InkMute, fontSize = 11.sp)
    }
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf(8 to 0, 12 to 0, 18 to 0, 20 to 0, 22 to 0)) { (h, m) ->
            FilterChip(
                selected = (hour == h && minute == m),
                onClick = { onChange(at(h, m)) },
                label = { Text(String.format("%02d:00", h)) }
            )
        }
    }
}

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
        FilterChip(
            selected = mode == "weekly",
            onClick = {
                mode = "weekly"
                // v5.15.22 M8 修复（boss：每天→隔天→每周 会被重置回"每天"）——
                //   根因：selectedDays 为空时 serializeWeekly() 返回 null，rule 变成 null；
                //   而 parseRule(null) 是 "daily"，加上下面的 state 都是 remember(rule)，
                //   key 从 "every2d" 变成 null → 三个 state 全部重建 → mode 被重置成 "daily"。
                //   （所以"从每天直接点每周"看起来正常 —— 那时 rule 本来就是 null，key 没变。）
                //   修法：空集时先填空一个默认选择（一/三/五），保证 rule 真变成 weekly:…
                if (selectedDays.isEmpty()) selectedDays = setOf(1, 3, 5)
                onChange(serializeWeekly(selectedDays))
            },
            label = { Text("每周") }
        )
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
    // 取消外层 AlertDialog 默认外框（用 androidx Dialog + 自定义 Column，去掉"外面套了一个框"的别扭感）
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)   // 不撑满屏幕，左右留出空间让弹窗"浮"起来
                .clip(RoundedCornerShape(8.dp))   // 圆角小一点（去掉 16dp "很卡"的圆角）
                .background(TGColors.Card)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            // 标题：小字间距，紧凑
            Text(
                title,
                color = TGColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.height(6.dp))
            // 消息：行高宽松
            Text(
                message,
                color = TGColors.InkSoft,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 取消：白底字
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = TGColors.InkSoft)
                ) { Text("取消", fontSize = 14.sp) }
                // 确认：实心引导色（按 confirmColor 提示用户该按哪个——boss 要的颜色引导）
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) { Text(confirmText, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            }
        }
    }
}
