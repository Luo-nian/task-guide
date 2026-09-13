package com.taskbar.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.taskbar.app.R
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.StepStatus
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TaskType
import com.taskbar.app.data.model.TrackStatus
import com.taskbar.app.data.repo.LinkState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

// ==================== 主页（今天要完成的任务） ====================
// 分区顺序：正在追踪 → 今日任务（待办）→ 习惯（放最下面）
// 全部完成（普通任务清空 + 习惯都打卡）→ 主页显示随机语录
/** v5.15.16：今日任务分组标题（与电脑端 titleMap 同口径） */
// v5.15.21 R4：改为 internal —— 日历视图组件（TaskCalendar.kt，独立文件）也要用这份分类中文名映射
internal val CAT_FILTER_LABEL = mapOf(
    "daily" to "每日任务", "goal" to "目标任务",
    "time-limited" to "限时任务", "once" to "次数任务"
)

private val DONE_QUOTES = listOf(
    "今天的任务都已经完成啦，可以去休息休息，或者继续完成非今日的任务哦",
    "全部搞定！今天的进度 100%，干得漂亮",
    "该做的都做完了，去喝口水奖励一下自己吧",
    "任务清空！剩下的时间都是你的",
    "完美收工！明天也继续保持哦"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(vm: TaskViewModel, navController: NavController) {
    val allTasks by vm.mainList.collectAsState()
    // v5.15.12：按任务类型筛选查看（boss：手机端所有任务也应该可以分类查看）
    // v5.15.16：这个筛选条 = 电脑端侧边栏的等价物，只用来"在今日待办里单看某一类"
    var catFilter by remember { mutableStateOf("all") }
    val tasks = remember(allTasks, catFilter) {
        if (catFilter == "all") allTasks else allTasks.filter { catKeyOf(it) == catFilter }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // 追踪 tab 已在底部 NavigationBar，这里只保留添加 FAB
        floatingActionButton = {
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
            // 类型筛选条（= 电脑端侧边栏：只在今日待办里单看某一类）
            // v5.15.16（boss）：主页首项不叫"全部"，叫"今日"（这里本来就是今日任务）
            CategoryFilterRow(catFilter, { catFilter = it }, allLabel = "今日")
            if (tasks.isEmpty()) {
                EmptyState("还没有任务\n点右下角加号，添加第一个", Modifier.fillMaxHeight(0.45f))
            } else {
                // 追踪置顶逻辑：当次在主页点追踪 → 不立即置顶（任务原位+金框+追踪键涟漪）；
                // 退出主页（切子界面/重启）再回来 → 追踪任务才置顶显示
                var showPinSection by remember { mutableStateOf(true) }  // 普通 remember（不用 rememberSaveable），避免 app 重建后状态粘住导致追踪任务永久不置顶
                val tracking = tasks.filter { it.trackStatus == TrackStatus.TRACKING }
                // 列表状态：显式持有，回到主页时若有追踪任务则强制滚回顶部（否则停在旧位置看不到置顶的追踪区）
                val listState = rememberLazyListState()
                val backStackEntry by navController.currentBackStackEntryAsState()
                LaunchedEffect(backStackEntry?.destination?.route) {
                    // 重新进入主页 → 恢复置顶（刚追踪的"不置顶"只影响当次）
                    if (backStackEntry?.destination?.route == "home") {
                        showPinSection = true
                        // v5.15.16（boss）：从别的界面回到主页，只要有追踪任务 → 页面坐标默认回到最上面
                        if (tracking.isNotEmpty()) listState.scrollToItem(0)
                    }
                }

                // v5.15.16（boss）：每日任务不再单列"打卡区"，直接归入今日任务；今日任务内部按分类分组。
                // 步骤聚合：一次 Flow 订阅获取全部步骤 Map，TaskRow 不再各自订阅（性能优化）
                val stepsByUuid by vm.stepsByUuid.collectAsState()
                // 今日已打卡的习惯 uuid 集合（订阅 habit_logs Flow，点完卡后 UI 立刻更新）
                val checkedHabits by vm.todayCheckedHabits.collectAsState()
                // v5.15.2：聚合 streak（HabitRow 不再每行独立 Flow → 切换卡顿优化）
                val streaksMap by vm.allHabitStreaks.collectAsState()
                // todo：置顶时排除追踪中（追踪在置顶区显示），不置顶时追踪任务混在列表里
                // v5.15.16（boss）：每日任务打完卡当天就不再留在主页（明天自动重新出现）
                val todo = (if (showPinSection) tasks.filter { it.trackStatus != TrackStatus.TRACKING } else tasks)
                    .filter { !(it.type == TaskType.HABIT && it.uuid in checkedHabits) }
                // 今日任务按分类分组（每日/目标/限时/次数）——与电脑端今日待办同一口径
                val groupedTodo = remember(todo) {
                    listOf("daily", "goal", "time-limited", "once").map { k ->
                        k to todo.filter { catKeyOf(it) == k }
                    }.filter { it.second.isNotEmpty() }
                }
                // 全部完成：无追踪、无待办
                val allDone = tracking.isEmpty() && todo.isEmpty()

                if (allDone) {
                    // 随机语录（每次到达此状态重新随机）
                    val quote = remember(allDone) { DONE_QUOTES.random() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TGIcon(R.drawable.ic_check_circle, contentDescription = null, tint = TGColors.Jade, size = 44.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                quote,
                                color = TGColors.InkSoft,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 12.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // 底部留 150dp 给浮动追踪键（~70dp 圆钮+数字）+ 系统导航栏（~50dp）+ 缓冲
                        contentPadding = PaddingValues(bottom = 150.dp)
                    ) {
                        if (showPinSection && tracking.isNotEmpty()) {
                            item(key = "hdr-tracking") { SectionHeader("正在追踪 (${tracking.size})", TGColors.Violet) }
                            items(tracking, key = { "tr-${it.uuid}" }) { task ->
                                TaskRow(task, stepsByUuid[task.uuid].orEmpty(), vm,
                                    onClick = { navController.navigate("detail/${task.uuid}") },
                                    onEdit = { navController.navigate("edit/${task.uuid}") },
                                    onJustTracked = { showPinSection = false },
                                    onJustStopped = { showPinSection = false })   // v5.15.21 P3
                            }
                        }
                        // 今日任务：按分类分组渲染（每日/目标/限时/次数）
                        groupedTodo.forEach { (catKey, list) ->
                            item(key = "hdr-todo-$catKey") {
                                SectionHeader("${CAT_FILTER_LABEL[catKey] ?: catKey} · 今日 (${list.size})", TGColors.GoldDeep)
                            }
                            items(list, key = { "t-$catKey-${it.uuid}" }) { task ->
                                if (task.type == TaskType.HABIT) {
                                    // v5.15.21 M1：传入追踪态，习惯行按"追踪/取消追踪 + 完成"渲染
                                    HabitRow(task, vm, checkedToday = task.uuid in checkedHabits, streak = streaksMap[task.uuid] ?: 0,
                                        tracking = task.trackStatus == TrackStatus.TRACKING,
                                        onJustStopped = { showPinSection = false })   // v5.15.21 P3
                                } else {
                                    TaskRow(task, stepsByUuid[task.uuid].orEmpty(), vm,
                                        onClick = { navController.navigate("detail/${task.uuid}") },
                                        onEdit = { navController.navigate("edit/${task.uuid}") },
                                        onJustTracked = { showPinSection = false },
                                    onJustStopped = { showPinSection = false })   // v5.15.21 P3
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 习惯行：完成=今日打卡（卡片沉底显示"今日已完成"，明天自动重新出现） */
// v5.15.2：streak 由父级聚合 Map 传入（原函数内每行 remember{vm.observeHabitStreak} 独立 Flow → 卡顿）
@Composable
private fun HabitRow(task: Task, vm: TaskViewModel, checkedToday: Boolean, streak: Int = 0, tracking: Boolean = false,
                     /* v5.15.21 P3：取消追踪当次不重排（与追踪对称） */
                     onJustStopped: () -> Unit = {}) {
    val ctx = LocalContext.current
    val bg = if (checkedToday) TGColors.Jade.copy(alpha = 0.10f) else TGColors.Card
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                width = if (checkedToday) 1.dp else 1.dp,
                /* v5.15.21 M6：任务行边框加重（BorderSoft → BorderMid） */
                color = if (checkedToday) TGColors.Jade.copy(alpha = 0.45f) else TGColors.BorderMid,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(TGColors.Jade, shape = RoundedCornerShape(3.dp))
                .graphicsLayer { rotationZ = 45f }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(TaskType.HABIT)
                Spacer(Modifier.width(6.dp))
                Text("连续 $streak 天", color = TGColors.Jade, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                task.title,
                color = if (checkedToday) TGColors.InkMute else TGColors.Ink,
                fontSize = 17.sp,   // v5.15.21 M7：15 → 17sp，比标签(11sp)明显更大
                fontWeight = FontWeight.Medium,
                textDecoration = if (checkedToday) TextDecoration.LineThrough else null
            )
        }
        if (checkedToday) {
            // 完成态：实心绿圆 + 白勾
            Box(
                Modifier
                    .size(26.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(TGColors.Jade),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // v5.15.21 M1（boss：取消打卡键，每日任务也变成追踪键和推进/完成键）：
            //   习惯任务没有步骤 → 按"无步骤"规则给两个键（追踪 + 完成），与 TaskRow 无步骤分支一致。
            //   完成键就是原来的"打卡"语义（habit 的 completeTask 会写 habit_log），只是不再单独做一个绿胶囊。
            // v5.15.21 M1：去掉 else 分支里重复声明的 ctx（已提到函数顶部）
            // v5.15.22 M5：追踪态下取消键带涟漪呼吸（与桌面端 rippleBreath 同语义）
            if (tracking) {
                TrackRippleKey(onClick = {
                    vm.stopTracking(task.uuid)
                    onJustStopped()   // v5.15.21 P3
                }, iconSize = 22.dp)
            } else {
                PressIcon(onClick = {
                    vm.startTracking(task.uuid) { ok ->
                        if (!ok) ToastHelper.show(ctx, "追踪已达上限，先取消别的追踪或在设置里调高上限")
                    }
                }) {
                    TGIcon(R.drawable.ic_track, contentDescription = "追踪", tint = TGColors.Azure, size = 22.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
            }
        }
    }
}

/** 列表分区标题（带色块引导）
 *  v5.15.18 J2（boss）：与电脑端 J1 同款 —— 标题末尾的「 (N)」不再混在文字里，
 *    自动拆出来渲染成**紧贴标题的圆角胶囊小标签**（金色系），两端观感一致。
 *    这里统一处理，6 个调用点不用逐个改。 */
@Composable
private fun SectionHeader(title: String, color: Color) {
    val m = Regex("""^(.*?)\s*\((\d+)\)$""").find(title)
    val name = m?.groupValues?.get(1) ?: title
    val count = m?.groupValues?.get(2)
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        // v5.15.22 D5：分区标题加重（boss：一眼区分各部分）
        Text(name, color = TGColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TGColors.Gold.copy(alpha = 0.16f))
                    .border(1.dp, TGColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text(count, color = TGColors.GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: Task,
    steps: List<Step>,                 // 从外层 stepsByUuid Map 传入（性能：省去内部 Flow 订阅）
    vm: TaskViewModel,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onJustTracked: () -> Unit = {},
    /* v5.15.21 P3（boss：取消追踪时不要一下放到其他位置）—— 与 onJustTracked 对称，
       取消当次不重排，等下次进入主页再归位。 */
    onJustStopped: () -> Unit = {},
    onPin: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasSteps = steps.isNotEmpty()
    val trackLimit by vm.trackLimit.collectAsState()
    val tracking = task.trackStatus == TrackStatus.TRACKING
    var lastClick by remember { mutableStateOf(0L) }
    var clicksInWindow by remember { mutableStateOf(0) }
    var showFinish by remember { mutableStateOf(false) }

    // 当前步骤（doing 优先，否则第一个未完成）
    val currentStep = steps.firstOrNull { it.status == StepStatus.DOING }
        ?: steps.firstOrNull { it.status != StepStatus.DONE }
    val currentStepIndex = currentStep?.let { steps.indexOf(it) }

    // v5.15.12：按住 320ms 未松手 → 进入编辑（比系统 ~500ms 更快）
    val pressSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by pressSrc.collectIsPressedAsState()
    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(320)
            onEdit()
        }
    }

    // 置顶框（非黑金）：追踪中 = 天蓝框 + 顶部蓝金渐变条（原神风清爽）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TGColors.Card)
            .border(
                width = if (tracking) 1.5.dp else 1.dp,
                /* v5.15.21 M6：任务行边框加重 */
                color = if (tracking) TGColors.Azure.copy(alpha = 0.65f) else TGColors.BorderMid,
                shape = RoundedCornerShape(12.dp)
            )
            // v5.15.12：长按 320ms 触发编辑（系统默认 ~500ms，boss 觉得太久）
            //   实现：监听「按下」状态 + 320ms delay —— 松手即取消（不用 AwaitPointerEventScope 的
            //   超时方案，那是 @RestrictsSuspension scope，withTimeout/协程定时器都不可用）
            .clickable(
                interactionSource = pressSrc,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick
            )
    ) {
        // 顶部渐变条（仅追踪中置顶框）
        if (tracking) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(TGColors.Azure, TGColors.GoldLight, TGColors.Azure)))
            )
        }

        if (tracking) {
            // ===== 追踪中布局：任务名上移 / 步骤大字占原任务名位置 / 分类 chips 右移排一排 =====
            Row(
                // v5.15.22 M6：end 6→12，与 HabitRow 的 12dp 内边距一致 → 追踪/完成两键跨行同列
                Modifier.padding(12.dp, 8.dp, 12.dp, 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(TGColors.Violet, shape = RoundedCornerShape(3.dp))
                        .graphicsLayer { rotationZ = 45f }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    task.title,
                    color = TGColors.Ink,
                    /* v5.15.21 M7（boss：任务标签和任务名字比重不对，任务名应该大一点）15 → 17sp */
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 操作：停止追踪 + 推进/完成
                // v5.15.16（boss）：取消追踪 = 红色实心追踪键（追踪键镂空处填满），不再用 ✕
                // v5.15.22 M5：追踪态下取消键带涟漪呼吸；M6：键间距 10dp、图标 22dp（跨行同列）
                TrackRippleKey(onClick = { vm.stopTracking(task.uuid); onJustStopped() }, iconSize = 22.dp)   // v5.15.21 P3
                Spacer(Modifier.width(10.dp))
                if (hasSteps) {
                    // 剩余未完成步骤 > 3 时，连按 3 次推进键才弹"完成"键（步骤少直接推进就行，不需要这个机制）
                    val remainingSteps = steps.count { it.status != com.taskbar.app.data.model.StepStatus.DONE }
                    // v5.15.16（M9）：只剩最后一步 → 推进键直接变「完成」键
                    if (remainingSteps <= 1) {
                        PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                            TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
                        }
                    } else {
                        PressIcon(onClick = {
                            vm.advanceStepByTask(task.uuid)
                            if (remainingSteps > 3) {
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
                            }
                        }) {
                            TGIcon(R.drawable.ic_forward, contentDescription = "推进", tint = TGColors.GoldDeep, size = 22.dp)
                        }
                        if (showFinish) {
                            PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                                TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
                            }
                        }
                    }
                } else {
                    PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                        TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
                    }
                }
            }
            // 当前步骤大字（占据原本任务名的位置，一眼看到现在做到哪一步）
            if (currentStep != null && currentStepIndex != null) {
                Row(
                    Modifier.padding(start = 36.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▶", color = TGColors.Azure, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "步骤 ${currentStepIndex + 1}/${steps.size}：${currentStep.title}",
                        color = TGColors.Azure,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // 分类 chips 右移排一排（类型/分类/优先级/追踪中）
            Row(
                Modifier.padding(start = 36.dp, end = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TypeChip(task.type)
                if (task.category.isNotBlank()) CategoryChip(task.category)
                PriorityChip(task.priority)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TGColors.Azure.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("追踪中", color = TGColors.Azure, fontSize = 11.sp)
                }
            }
            if (showFinish) {
                Text(
                    "步骤都完成了吗？可以直接点完成哦",
                    color = TGColors.Jade,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 36.dp, end = 6.dp, bottom = 10.dp)
                )
            }
        } else {
            // ===== 未追踪布局：任务名原位（不显示步骤），chips 左上 =====
            Row(
                // v5.15.22 M6：end 6→12，与 HabitRow 对齐
                Modifier.padding(12.dp, 12.dp, 12.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧菱形标识
                val diamondColor = when {
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
                        if (task.category.isNotBlank()) {
                            CategoryChip(task.category)
                            Spacer(Modifier.width(6.dp))
                        }
                        PriorityChip(task.priority)
                        if (task.type == TaskType.HABIT && task.dueAt == null) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TGColors.Jade.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("每日任务", color = TGColors.Jade, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(task.title, color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    task.dueAt?.let {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TGIcon(R.drawable.ic_clock, contentDescription = null, tint = TGColors.Orange, size = 12.dp)
                            Spacer(Modifier.width(3.dp))
                            Text(dateFmt.format(Date(it)), color = TGColors.InkSoft, fontSize = 11.sp)
                        }
                    }
                    // 里程碑进度（未追踪也显示 2/5 进度感）
                    if (task.type == TaskType.MILESTONE) {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("进度 ${task.progress}/${task.target}", color = TGColors.Crimson, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { if (task.target > 0) task.progress.toFloat() / task.target else 0f },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = TGColors.Crimson,
                                trackColor = TGColors.BgPaperDeep
                            )
                        }
                    }
                }
                // 右侧操作：追踪 + 主操作（完成/推进）
                if (tracking) {
                    // v5.15.16（boss）：取消追踪 = 红色实心追踪键（追踪键镂空处填满），不再用 ✕
                    // v5.15.22 M5：追踪态下取消键带涟漪呼吸；M6：图标统一 22dp
                    TrackRippleKey(onClick = { vm.stopTracking(task.uuid); onJustStopped() }, iconSize = 22.dp)
                } else {
                    PressIcon(onClick = {
                        vm.startTracking(task.uuid) { ok ->
                            if (!ok) {
                                ToastHelper.show(ctx, "追踪已达上限($trackLimit 个)，先取消别的追踪或在设置里调高上限")
                            } else {
                                onJustTracked()
                            }
                        }
                    }) {
                        TGIcon(R.drawable.ic_track, contentDescription = "追踪", tint = TGColors.Azure, size = 22.dp)
                    }
                }
                // v5.15.22 M6：与 HabitRow 相同的 10dp 键间距 → 追踪/完成两键跨行同列
                Spacer(Modifier.width(10.dp))
                if (hasSteps) {
                    // 剩余未完成步骤 > 3 时，连按 3 次推进键才弹"完成"键（步骤少直接推进就行，不需要这个机制）
                    val remainingSteps = steps.count { it.status != com.taskbar.app.data.model.StepStatus.DONE }
                    // v5.15.16（M9）：只剩最后一步 → 推进键直接变「完成」键
                    if (remainingSteps <= 1) {
                        PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                            TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
                        }
                    } else {
                        PressIcon(onClick = {
                            vm.advanceStepByTask(task.uuid)
                            if (remainingSteps > 3) {
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
                            }
                        }) {
                            TGIcon(R.drawable.ic_forward, contentDescription = "推进", tint = TGColors.GoldDeep, size = 22.dp)
                        }
                        if (showFinish) {
                            PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                                TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
                            }
                        }
                    }
                } else {
                    PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                        TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 22.dp)
                    }
                }
            }
            if (showFinish) {
                Text(
                    "步骤都完成了吗？可以直接点完成哦",
                    color = TGColors.Jade,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 36.dp, end = 6.dp, bottom = 10.dp)
                )
            }
            // 仓库操作行（所有任务页专用：置顶到主页 / 移回仓库）
            if (onPin != null || onUnpin != null) {
                Row(
                    Modifier.padding(start = 30.dp, end = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    onPin?.let {
                        TextButton(onClick = it, modifier = Modifier.height(32.dp)) {
                            Text("↑ 置顶到主页", color = TGColors.GoldDeep, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    onUnpin?.let {
                        TextButton(onClick = it, modifier = Modifier.height(32.dp)) {
                            Text("↓ 移回仓库", color = TGColors.InkMute, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** v5.15.16：任务类型筛选条（4 类 + 首项），主页与「所有任务」页共用
 *  boss：「主页上面的分类就相当于电脑端的侧边栏，只是为了今日待办中单独查看某一个分类的任务」
 *  allLabel：首项文案。主页列表全是"今日"的任务 → 传"今日"；「所有任务」页含未来任务 → 传"全部" */
@Composable
private fun CategoryFilterRow(selected: String, onSelect: (String) -> Unit, allLabel: String = "全部") {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(listOf(
            "all" to allLabel, "daily" to "每日", "goal" to "目标",
            "time-limited" to "限时", "once" to "次数"
        )) { (k, l) ->
            FilterChip(selected = selected == k, onClick = { onSelect(k) }, label = { Text(l) })
        }
    }
}

/** 分类小标签（与 TypeChip 风格统一：金色鲜明底 + 深金字，不违和） */
@Composable
private fun CategoryChip(category: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TGColors.Gold.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(category, color = TGColors.GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ==================== 追踪详情视图 ====================
@Composable
fun TrackScreen(vm: TaskViewModel) {
    val tracking by vm.tracking.collectAsState()
    val limit by vm.trackLimit.collectAsState()
    // v5.15.16（M4）：步骤只订阅一次（聚合 Map），不再让每张卡各自起 Flow + 查库。
    //   原来切到追踪页要重建 N 个 Flow（WhileSubscribed 会重新查 DB）→ 肉眼可见的卡顿。
    val stepsByUuid by vm.stepsByUuid.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "追踪中 (${tracking.size}/$limit)",
            color = TGColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif,
            modifier = Modifier.padding(4.dp, 12.dp)
        )

        if (tracking.isEmpty()) {
            EmptyState("没有追踪中的任务\n去主页点追踪图标", Modifier.fillMaxSize())
        } else {
            // 追踪任务竖排列表，每个任务一张卡，卡内可收起步骤栏
            // v5.15.16（M10）：卡片间距加大 + 卡片本身带边框 → 有步骤时也不会混成一大坨
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(tracking, key = { it.uuid }) { task ->
                    TrackTaskCard(task, stepsByUuid[task.uuid].orEmpty(), vm)
                }
            }
        }
    }
}

/** 追踪任务卡：标题 + 进度 + 可收起步骤栏（步骤带序号），无步骤可直接添加 */
@Composable
private fun TrackTaskCard(task: Task, steps: List<Step>, vm: TaskViewModel) {
    // v5.15.16（M4）：steps 由外层聚合 Map 传入（原内部 vm.steps(uuid) 每次进页重建 Flow）
    val ctx = LocalContext.current
    var expanded by remember(task.uuid) { mutableStateOf(true) }
    var showAddStep by remember(task.uuid) { mutableStateOf(false) }
    val doneCount = steps.count { it.status == StepStatus.DONE }
    val total = steps.size

    // v5.15.16（M10）：卡片边框加重为 1.5dp + 天蓝描边 + 左侧色条，卡片之间 14dp 间距，
    //   有步骤时也能一眼看出"这是三个任务"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TGColors.Card)
            .border(1.5.dp, TGColors.Azure.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
    ) {
        // 卡头：点击展开/收起
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(5.dp, 22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TGColors.Azure)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(10.dp)
                    .background(TGColors.Violet, shape = RoundedCornerShape(3.dp))
                    .graphicsLayer { rotationZ = 45f }
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                    // 底部追加步骤入口（JSON 入口已移到 AddStepDialog 内）
                    TextButton(onClick = { showAddStep = true }) {
                        TGIcon(R.drawable.ic_add, contentDescription = null, tint = TGColors.GoldDeep, size = 15.dp)
                        Spacer(Modifier.width(3.dp))
                        Text("添加步骤", color = TGColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        // v5.15.16（M11）：追踪页也要有「取消追踪 / 推进」两个键
        // v5.15.21 M9（boss：追踪页的取消追踪键和完成键还是以前的版本，改）——
        //   旧实现是 TextButton + 灰色 ✕（ic_close），与主页/详情页早已统一的
        //   「朱砂红实心靶心 = 取消追踪」「玉青勾 = 完成」「金箭头 = 推进」不一致。
        //   这里改成同款圆形图标键（PressIcon），三处观感一致。
        val remaining = steps.count { it.status != StepStatus.DONE }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
            // v5.15.21 M9b（boss：追踪键和完成键一列对齐一下，不然看着难受）——
            //   旧版是 TextButton + 长度不同的文字（"取消追踪" vs "完成"），图标起点参差；
            //   现在两个键都是 40dp 圆形（PressIcon 内 IconButton）+ 固定间距 + 整体右对齐，
            //   图标自然落在同一条竖线上。左端 Spacer 不再需要（改用 End 对齐）。
            // v5.15.22 M6：键间距 14→10，与主页 TaskRow/HabitRow 完全一致
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 取消追踪：朱砂红实心靶心（追踪键镂空处填满）——与主页 TaskRow / HabitRow 一致
            // v5.15.22 M5：追踪态下取消键带涟漪呼吸
            TrackRippleKey(onClick = { vm.stopTracking(task.uuid) }, iconSize = 24.dp)
            // 推进 / 完成：只剩最后一步（或无步骤）时直接变「完成」，与主页规则一致
            if (total == 0 || remaining <= 1) {
                PressIcon(onClick = { vm.completeTask(task.uuid) }) {
                    TGIcon(R.drawable.ic_check_circle, contentDescription = "完成", tint = TGColors.Jade, size = 24.dp)
                }
            } else {
                PressIcon(onClick = { vm.advanceStepByTask(task.uuid) }) {
                    TGIcon(R.drawable.ic_forward, contentDescription = "推进", tint = TGColors.GoldDeep, size = 24.dp)
                }
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
            },
            onJsonImport = { steps2 ->
                if (steps2.isNotEmpty()) {
                    steps2.forEach { (t, l, v) ->
                        if (t.isNotBlank()) vm.addStep(task.uuid, t.trim(), l.trim(), v.trim())
                    }
                    ToastHelper.show(ctx, "已添加 ${steps2.size} 个步骤")
                }
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

/** 通用步骤行（详情页等使用），seq 非空时显示序号
 *  v5.15.22 M9：readOnly=true 时只渲染状态，不给任何可点击的按钮（日历进详情防刷分） */
@Composable
fun StepRow(step: Step, vm: TaskViewModel, seq: Int? = null, readOnly: Boolean = false) {
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
        } else if (readOnly) {
            Box(
                Modifier
                    .size(24.dp)
                    .border(1.5.dp, TGColors.InkFaint, androidx.compose.foundation.shape.CircleShape)
            )
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
    // v5.15.2：切换卡顿优化 —— 整页只订阅 2 个聚合 Flow（原每行独立 observeHabitStreak Flow + DB 查询）
    val streaks by vm.allHabitStreaks.collectAsState()
    val checkedSet by vm.todayCheckedHabits.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("每日任务", color = TGColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 12.dp))
        if (habits.isEmpty()) {
            EmptyState("还没有习惯\n添加一个 type=habit 的任务", Modifier.fillMaxSize())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(habits, key = { it.uuid }) { habit ->
                    val streak = streaks[habit.uuid] ?: 0
                    val checkedToday = habit.uuid in checkedSet
                    val scope = rememberCoroutineScope()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TGColors.Card)
                            .border(1.dp, TGColors.BorderMid, RoundedCornerShape(12.dp))   // v5.15.21 M6
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
                                    // v5.15.2：checkedToday 派生自聚合 Flow，db 写入后自动刷新，无需手动置位
                                    if (ok) {
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

// ==================== 所有任务（追踪/待办/未来 全量，不含已完成——已完成只进历史任务） ====================
@Composable
fun AllTasksScreen(vm: TaskViewModel, navController: NavController) {
    val tracking by vm.tracking.collectAsState()
    val main by vm.mainList.collectAsState()
    val future by vm.futureTasks.collectAsState()
    val ctx = LocalContext.current
    val stepsByUuid by vm.stepsByUuid.collectAsState()
    val todo = main.filter { it.trackStatus != TrackStatus.TRACKING }
    // 今日已打卡的每日任务（habit 类型 + checkedToday），挪到这里显示
    val todayCheckedHabits by vm.todayCheckedHabits.collectAsState()
    // v5.15.16（M2）：所有任务页也要能分类查看 + 右上角搜索
    var catFilter by remember { mutableStateOf("all") }
    var searchOn by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    // v5.15.21 R4：列表 / 日历 切换
    var calView by remember { mutableStateOf(false) }
    fun match(t: Task) = (keyword.isBlank() || t.title.contains(keyword, ignoreCase = true))
    fun byCat(list: List<Task>) = list.filter { catFilter == "all" || catKeyOf(it) == catFilter }
    val trackingF = byCat(tracking).filter(::match)
    val todoF = byCat(todo).filter(::match)
    val futureF = byCat(future).filter(::match)
    val todayCheckedList = byCat(todo.filter { it.type == TaskType.HABIT && it.uuid in todayCheckedHabits })
        .filter(::match)
    // 置顶/置底确认弹窗状态：(uuid, action) action = "pin" | "unpin"
    var pendingRepoAction by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 顶栏：返回 + 标题 + 右上角搜索
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 8.dp, 4.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
            Text("所有任务", color = TGColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // v5.15.21 R4（boss：手机端要能日历形式查看所有任务）—— 列表 / 日历 切换
            PressPill(onClick = { calView = !calView; if (calView) searchOn = false }) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(TGColors.Gold.copy(alpha = 0.16f))
                        .border(1.dp, TGColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (calView) "列表" else "日历",
                        color = TGColors.GoldDeep, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            // 右上角搜索键（点开/收起搜索框）
            PressIcon(onClick = { searchOn = !searchOn; if (!searchOn) keyword = "" }) {
                TGIcon(
                    drawable = if (searchOn) R.drawable.ic_close else R.drawable.ic_search,
                    contentDescription = if (searchOn) "关闭搜索" else "搜索",
                    tint = TGColors.GoldDeep, size = 22.dp
                )
            }
        }
        // 搜索框（展开时）
        if (searchOn) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索任务名…", color = TGColors.InkFaint, fontSize = 14.sp) },
                singleLine = true,
                leadingIcon = { TGIcon(R.drawable.ic_search, contentDescription = null, tint = TGColors.InkMute, size = 18.dp) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
        }
        // 分类筛选条（与主页同一套口径；本页含未来任务，首项保持"全部"）
        CategoryFilterRow(catFilter, { catFilter = it })
        if (trackingF.isEmpty() && todoF.isEmpty() && futureF.isEmpty() && todayCheckedList.isEmpty()) {
            EmptyState(if (keyword.isNotBlank()) "没有匹配「" + keyword + "」的任务" else "还没有任何任务", Modifier.fillMaxSize())
        } else if (calView) {
            // v5.15.21 R4：日历视图（按截止/期限时间铺开，无时间则用创建时间）
            val allForCal = (trackingF + todoF + futureF + todayCheckedList).distinctBy { it.uuid }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                TaskCalendarView(
                    tasks = allForCal,
                    dateOf = { it.dueAt ?: it.deadline ?: it.createdAt },
                    // v5.15.22 M9：日历点任务 → 只读详情（boss：不能有任何功能按键，防刷分）
                    onTaskClick = { navController.navigate("ro/${it.uuid}") },
                    emptyHint = "这一天没有任务"
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // v5.15.16（boss）：追踪中的任务必须置顶（原来"今日打卡"占着第一位）
                if (trackingF.isNotEmpty()) {
                    item(key = "hdr-t") { SectionHeader("正在追踪 (${trackingF.size})", TGColors.Violet) }
                    items(trackingF, key = { "t-${it.uuid}" }) { task ->
                        TaskRow(task, stepsByUuid[task.uuid].orEmpty(), vm,
                            onClick = { navController.navigate("detail/${task.uuid}") },
                            onEdit = { navController.navigate("edit/${task.uuid}") },
                            onUnpin = { pendingRepoAction = task.uuid to "unpin" })
                    }
                }
                if (todayCheckedList.isNotEmpty()) {
                    item(key = "hdr-today") { SectionHeader("今日打卡 (${todayCheckedList.size})", TGColors.Jade) }
                    items(todayCheckedList, key = { "today-${it.uuid}" }) { task ->
                        HabitRow(task, vm, checkedToday = true)
                    }
                }
                if (todoF.isNotEmpty()) {
                    item(key = "hdr-todo") { SectionHeader("待办 (${todoF.size})", TGColors.GoldDeep) }
                    items(todoF, key = { "todo-${it.uuid}" }) { task ->
                        TaskRow(task, stepsByUuid[task.uuid].orEmpty(), vm,
                            onClick = { navController.navigate("detail/${task.uuid}") },
                            onEdit = { navController.navigate("edit/${task.uuid}") },
                            onUnpin = { pendingRepoAction = task.uuid to "unpin" })
                    }
                }
                if (futureF.isNotEmpty()) {
                    item(key = "hdr-f") { SectionHeader("未来任务 (${futureF.size})", TGColors.Azure) }
                    items(futureF, key = { "f-${it.uuid}" }) { task ->
                        TaskRow(task, stepsByUuid[task.uuid].orEmpty(), vm,
                            onClick = { navController.navigate("detail/${task.uuid}") },
                            onEdit = { navController.navigate("edit/${task.uuid}") },
                            onPin = { pendingRepoAction = task.uuid to "pin" })
                    }
                }
            }
        }
    }

    // 置顶/置底确认弹窗（防误触）
    pendingRepoAction?.let { (uuid, action) ->
        if (action == "pin") {
            TGConfirmDialog(
                title = "置顶到主页",
                message = "确定把任务移到主页吗？\n它将出现在主页的今天任务里",
                confirmText = "置顶",
                confirmColor = TGColors.GoldDeep,
                onConfirm = {
                    vm.pinToHome(uuid)
                    ToastHelper.show(ctx, "已将任务移至主页")
                    pendingRepoAction = null
                },
                onDismiss = { pendingRepoAction = null }
            )
        } else {
            TGConfirmDialog(
                title = "移回仓库",
                message = "确定把任务移回仓库吗？\n它将不再出现在主页（明天 9 点到期）",
                confirmText = "移回",
                confirmColor = TGColors.Crimson,
                onConfirm = {
                    vm.unpinToRepo(uuid)
                    ToastHelper.show(ctx, "已移回任务仓库")
                    pendingRepoAction = null
                },
                onDismiss = { pendingRepoAction = null }
            )
        }
    }
}

// ==================== 历史任务（已完成） ====================
@Composable
fun HistoryScreen(vm: TaskViewModel, navController: NavController) {
    val archive by vm.archive.collectAsState()
    // v5.15.22 M3：打卡日志 —— 每日任务按天展开（哪天打了=已完成，哪天漏了=未完成）
    val logs by vm.habitLogs.collectAsState()
    // v5.15.21 R4：false=列表（记账式流水）｜true=日历视图
    // v5.15.22 M1（boss：历史任务应该是默认日历形式）→ 初值改 true
    var calView by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 8.dp, 4.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
            Text("历史任务", color = TGColors.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
            Spacer(Modifier.weight(1f))
            // v5.15.21 R4（boss：手机端要能日历形式查看历史任务）—— 列表 / 日历 切换
            PressPill(onClick = { calView = !calView }) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(TGColors.Gold.copy(alpha = 0.16f))
                        .border(1.dp, TGColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (calView) "列表" else "日历",
                        color = TGColors.GoldDeep, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (archive.isEmpty()) {
            EmptyState("还没有已完成的任务\n完成的任务会自动收进这里", Modifier.fillMaxSize())
        } else if (calView) {
            // v5.15.21 R4：日历视图（按完成日期铺开）
            // v5.15.22 M3：每日任务按天展开（漏掉的那天也有一条"未完成"）→ 日历数量才真实
            // v5.15.22 M9：日历点任务 → 只读详情（防刷分）
            val entries = remember(archive, logs) { expandHistoryByDay(archive, logs) }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                TaskCalendarView(
                    tasks = entries,
                    dateOf = { it.doneAt ?: it.dueAt ?: it.deadline ?: it.createdAt },
                    onTaskClick = { navController.navigate("ro/${it.uuid}") },
                    emptyHint = "这一天没有完成的任务",
                    statusOf = { t -> if (t.done == 1) "已完成" else "未完成" }
                )
            }
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

/**
 * v5.15.22 M3（boss：「历史任务不是已完成任务，已逾期未完成的每日任务也算进去」）——
 * 把归档按天展开：
 *  · 每日/习惯任务：从创建日（最多回溯 60 天）到昨天，逐天生成一条 —— 有打卡记录 = 已完成，
 *    没打卡 = 未完成（这就是"某天漏了"也能在日历上看到数量的来源）
 *  · 普通任务：已完成 → doneAt 那天；未完成且已逾期 → 归在 dueAt/deadline/创建那天
 * 今天的状态是"活的"（主页还在管），不参与展开。
 */
private fun expandHistoryByDay(archive: List<Task>, logs: List<com.taskbar.app.data.model.HabitLog>): List<Task> {
    val todayStart = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val logMap = HashMap<String, HashSet<String>>()
    for (l in logs) logMap.getOrPut(l.taskUuid) { HashSet() }.add(l.checkDate)
    val out = ArrayList<Task>(archive.size + 64)
    for (t in archive) {
        val isDaily = t.type == TaskType.HABIT || t.category == "daily"
        if (!isDaily) { out.add(t); continue }
        // 今天已打卡 → 保留今天这条"活"的完成记录
        if (t.done == 1 && (t.doneAt ?: 0L) >= todayStart) out.add(t)
        // 往期逐天展开（最多回溯 60 天，防止老任务把列表撑爆）
        val start = maxOf(t.createdAt, todayStart - 60L * 24 * 3600 * 1000)
        val cur = java.util.Calendar.getInstance().apply {
            timeInMillis = start
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val days = logMap[t.uuid]
        while (cur.timeInMillis < todayStart) {
            val key = dayFmt.format(cur.time)
            val checked = days?.contains(key) == true
            out.add(
                if (checked) t.copy(done = 1, trackStatus = TrackStatus.DONE, doneAt = cur.timeInMillis)
                else t.copy(done = 0, trackStatus = TrackStatus.PENDING, doneAt = cur.timeInMillis)
            )
            cur.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
    }
    return out
}

/** 已完成/逾期未完成任务行（完成=划线+时间+恢复；未完成=朱砂标记，无恢复键） */
@Composable
private fun DoneTaskRow(task: Task, vm: TaskViewModel) {
    val isDone = task.done == 1 && task.trackStatus == TrackStatus.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDone) TGColors.Card.copy(alpha = 0.7f) else TGColors.Crimson.copy(alpha = 0.06f))
            .border(
                1.dp,
                if (isDone) TGColors.BorderMid else TGColors.Crimson.copy(alpha = 0.45f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (!isDone) {
                Text("逾期未完成", color = TGColors.Crimson, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                task.title,
                color = if (isDone) TGColors.InkMute else TGColors.Ink,
                fontSize = 14.sp,
                textDecoration = if (isDone) TextDecoration.LineThrough else null
            )
            if (isDone) {
                task.doneAt?.let { Text("完成于 ${dateFmt.format(Date(it))}", color = TGColors.InkMute, fontSize = 11.sp) }
            } else {
                Text("历史里只做记录，不计积分", color = TGColors.InkMute, fontSize = 11.sp)
            }
        }
        // v5.15.22 M3：未完成的任务没有"恢复"可言（它本来就还没做完）
        if (isDone) {
            TextButton(onClick = { vm.restoreTask(task.uuid) }) { Text("恢复", color = TGColors.GoldDeep) }
        }
    }
}


// ==================== 顶部栏（合并主页/我的/追踪的标题栏）+ 人物边框头像（我的入口） ====================
@Composable
fun AppTopBar(currentRoute: String?, vm: TaskViewModel, navController: NavController) {
    Surface(color = TGColors.PanelSolid, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        when (currentRoute) {
            "home" -> {
                val points by vm.totalPoints.collectAsState()
                val trackingCount by vm.tracking.collectAsState()
                // v5.15.19：主页顶部也显示电脑端连接状态（boss「手机端没有显示已连接」）
                val linked by LinkState.flow.collectAsState()
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
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Serif
                    )
                    Spacer(Modifier.width(6.dp))
                    // 连接状态小圆点：连上=鼠尾草绿，未连=极淡（不打扰，只做状态提示）
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (linked) TGColors.Jade else TGColors.InkFaint)
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
                    // 历史任务：仅图标（去字，简洁）
                    PressIcon(onClick = { navController.navigate("history") }) {
                        TGIcon(R.drawable.ic_clock, contentDescription = "历史任务", tint = TGColors.GoldDeep, size = 22.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    // 所有任务：换成 ic_inbox 全部列表+勾选样式（去字，更像"所有任务"）
                    PressIcon(onClick = { navController.navigate("all") }) {
                        TGIcon(R.drawable.ic_inbox, contentDescription = "所有任务", tint = TGColors.GoldDeep, size = 22.dp)
                    }
                }
            }
            "profile" -> barWithTitle("我的", navController, showBack = true)  // 我的页：顶部加返回箭头（不依赖系统返回键）
            "track" -> barWithTitle("追踪中", navController, showBack = false)    // 底部导航栏已有
            else -> barWithTitle("任务栏", navController)
        }
    }
}

@Composable
private fun barWithTitle(title: String, navController: NavController, showBack: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp, 10.dp, 12.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(title, color = TGColors.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
    }
}

/** 人物边框头像（克制：圆形卡色底 + 金边 + 墨色人形），点开进入我的 */
@Composable
fun AvatarFrame(onClick: () -> Unit) {
    // v5.15：自定义 emoji 头像（taskguide_prefs avatar_emoji）—— 注册 prefs 监听，profile 改动后即时刷新
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE) }
    var emoji by remember { mutableStateOf(prefs.getString("avatar_emoji", "") ?: "") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == "avatar_emoji" || key == null) emoji = sp.getString("avatar_emoji", "") ?: ""
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // v5.15.7：电脑端自定义头像（settings.avatar_img）跨端同步后顶栏也显示
    val repo = remember { com.taskbar.app.TaskBarApp.instance.repo }
    val syncedAvatar by remember(repo) { repo.observeSetting("avatar_img") }
        .collectAsState(initial = null)
    val syncedBmp = rememberAvatarBitmap(syncedAvatar)
    PressIcon(onClick = onClick) {
        Box(
            Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(TGColors.Card)
                .border(1.5.dp, TGColors.Gold, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (syncedBmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = syncedBmp,
                    contentDescription = "我的",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (emoji.isNotEmpty()) {
                Text(emoji, fontSize = 16.sp)
            } else {
                TGIcon(
                    drawable = R.drawable.ic_avatar,
                    contentDescription = "我的",
                    tint = TGColors.Ink,
                    size = 20.dp
                )
            }
        }
    }
}

/** 底部中央"追踪"按钮：圆形青蓝 + 原神风任务标记（无文字、无红角标，与顶栏"追踪中"Azure 风格一致）
 *  一次性涟漪：点时触发 700ms 扩散，0 持续循环 → 滚动不掉帧
 *  追踪数显示在按钮正下方小字（Azure 深色），不用红色角标（boss 嫌丑）
 *  历史：黑底→金边→纯金实底→渐变金+涟漪→准星→圆形青蓝+红角标(丑)→现在青蓝圆钮+下方小字 */
@Composable
fun CenterTrackingButton(navController: NavController, trackingCount: Int = 0) {
    val ripple = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 72.dp),  // 让出系统导航栏
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (ripple.value > 0f) {
                Box(
                    Modifier.size(64.dp).drawBehind {
                        val maxR = 34.dp.toPx()
                        val radius = 26.dp.toPx() + (maxR - 26.dp.toPx()) * ripple.value
                        val alpha = (1f - ripple.value) * 0.45f
                        drawCircle(
                            color = TGColors.Azure.copy(alpha = alpha),
                            radius = radius,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                        )
                    }
                )
            }
            // 圆形青蓝按钮 + 白色任务标记 + 右上红色数量角标（v5.15 fix：原下方小字易截断）
            Box {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(TGColors.Azure)
                        .clickable {
                            scope.launch {
                                ripple.snapTo(0f)
                                ripple.animateTo(1f, tween(700))
                            }
                            navController.navigate("track")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    TGIcon(
                        drawable = R.drawable.ic_mark,
                        contentDescription = "追踪",
                        tint = Color.White,
                        size = 24.dp
                    )
                }
                // v5.15.5：追踪数 pill 角标（自绘 Box+Text，绕开 Material3 Badge 在 webview 渲染时偶发数字缺失 bug）
                if (trackingCount > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-4).dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(TGColors.Crimson)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (trackingCount > 99) "99+" else trackingCount.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

/** v5.15.12：任务类型归类（与桌面端 app3.js 的 catOf 逐条一致，保证两端分类口径相同） */
fun catKeyOf(t: com.taskbar.app.data.model.Task): String = when {
    t.category == "daily" || t.category == "goal" ||
        t.category == "time-limited" || t.category == "once" -> t.category
    t.type == com.taskbar.app.data.model.TaskType.HABIT -> "daily"
    t.type == com.taskbar.app.data.model.TaskType.GOAL ||
        t.type == com.taskbar.app.data.model.TaskType.MILESTONE -> "goal"
    t.type == com.taskbar.app.data.model.TaskType.REPEAT -> "time-limited"
    else -> "once"
}
