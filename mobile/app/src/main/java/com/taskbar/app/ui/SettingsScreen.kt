package com.taskbar.app.ui

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.BuildConfig
import com.taskbar.app.R
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.Levels
import com.taskbar.app.data.model.ReminderStrength
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import com.taskbar.app.data.repo.LinkState
import com.taskbar.app.server.AuthState
import com.taskbar.app.server.PairingState
import com.taskbar.app.notify.DailyReminderScheduler
import com.taskbar.app.server.SyncService
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** v5.15.27 M7：把「起床/睡前提醒」的下一次触发时刻说人话（今天 07:30 / 明天 07:30） */
private fun nextDailyText(h: Int, m: Int): String {
    val now = java.util.Calendar.getInstance()
    val next = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, h)
        set(java.util.Calendar.MINUTE, m)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    val sameDay = next.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return (if (sameDay) "今天 " else "明天 ") + String.format("%02d:%02d", h, m)
}

/** 下一级等级名（用于"距 XX 还差 N 分"） */
private fun nextLevelName(currentLv: Int): String = when (currentLv) {
    1 -> "风华游侠"
    2 -> "破浪骑士"
    3 -> "群星行者"
    4 -> "传奇勇者"
    else -> "下一级"
}

@Composable
fun SettingsScreen(vm: TaskViewModel, navController: androidx.navigation.NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val trackLimit by vm.trackLimit.collectAsState()

    // ====== 提醒方式（共享 prefs 直读直写；四通道多选 + 端选择 + 升级） ======
    val prefs = remember { ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE) }
    // v5.15.3：起床/睡前时间选择对话框状态
    var showMorningPicker by remember { mutableStateOf(false) }
    var showNightPicker by remember { mutableStateOf(false) }
    // 多选通道（通知栏/振动/提示音/铃声） + 端选择（不提醒/仅手机/仅电脑/双端）
    var channels by remember { mutableStateOf(listOf<String>()) }
    var reminderScope by remember { mutableStateOf(ReminderStrength.SCOPE_MOBILE) }
    var vibratePattern by remember { mutableStateOf(prefs.getString("reminder_vibrate_pattern", "0,300,200,300,200,300") ?: "0,300,200,300,200,300") }
    var ringUri by remember { mutableStateOf(prefs.getString("reminder_ring_uri", "") ?: "") }
    // v5.15.21 M3：已移除「未处理自动升级」功能 → 对应的 escalateOn / escalateMinutes
    //   两个 state 与 saveEscalate() 一并删除（prefs 里的旧键保留，不主动清理，避免影响回退）。

    // 初始化：读 prefs 里的配置（兼容旧单值）
    LaunchedEffect(Unit) {
        val (ch, sc) = ReminderStrength.parseConfig(prefs.getString("reminder_strength", "notify"))
        channels = ch
        reminderScope = sc
    }

    fun saveConfig() {
        prefs.edit().putString("reminder_strength", ReminderStrength.serializeConfig(channels, reminderScope)).apply()
    }
    fun toggleChannel(c: String) {
        channels = if (c in channels) channels - c else channels + c
        if (channels.isNotEmpty() && reminderScope == ReminderStrength.SCOPE_NONE) reminderScope = ReminderStrength.SCOPE_MOBILE
        saveConfig()
    }
    fun saveScope(s: String) {
        reminderScope = s
        if (s != ReminderStrength.SCOPE_NONE && channels.isEmpty()) channels = listOf(ReminderStrength.NOTIFY)
        saveConfig()
    }
    fun saveVibratePattern(s: String) {
        vibratePattern = s
        prefs.edit().putString("reminder_vibrate_pattern", s).apply()
    }
    // v5.15.21 M3：saveEscalate() 已随「未处理自动升级」功能一并移除

    // 铃声选择器（系统 RingtonePicker → 回调拿 Uri）
    val ringtonePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                ringUri = uri.toString()
                prefs.edit().putString("reminder_ring_uri", uri.toString()).apply()
                ToastHelper.show(ctx, "已选择铃声")
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())  // 整页可滚（修复底部 5/10/30 chip 被裁切）
            .padding(12.dp)
    ) {
        // 顶部：返回 + 标题
        Row(
            Modifier.fillMaxWidth().padding(4.dp, 8.dp, 4.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                TGIcon(R.drawable.ic_back, contentDescription = "返回", tint = TGColors.Ink, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
            Text("设置", color = TGColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(6.dp))
        // v5.21.2（boss：「用户名称的设置放在设置页面的最上面」）—— 从「配对」下面提到第一组
        // ===== v5.15.28 M10：昵称编辑放在设置界面 =====
        // ===== v5.15.29 L5（boss：没编辑过就用**当前等级名**，编辑过就用用户编辑的）=====
        // ===== v5.15.29 L6（boss：想改成更高等级的名称时，在**这张卡里**提示一句，但不拦保存）=====
        TGCard(Modifier.fillMaxWidth()) {
            val pointsNow by vm.totalPoints.collectAsState()
            val curLevelName = Levels.of(pointsNow).name
            var nick by remember { mutableStateOf("") }
            var custom by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf(false) }
            val nickFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { nick = vm.getSetting("nickname", "") }
                runCatching { custom = vm.getSetting("nickname_custom", "") == "1" }
                draft = nick
            }
            // 展示值：编辑过 → 用户的；没编辑过 → 当前等级名（升级会自动跟着变）
            val shown = if (custom && nick.isNotBlank()) nick else curLevelName
            // 输入值 == 某个「高于当前等级」的等级名 → 给提示（**不拦保存**）
            val higher = remember(draft, pointsNow, editing) {
                if (editing) Levels.higherLevelNamed(draft, pointsNow) else null
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("用户名", color = TGColors.InkMute, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                if (!editing) {
                    Text(shown, color = TGColors.GoldDeep, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { draft = if (custom) nick else ""; editing = true }) {
                        Text("修改", color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text("清空即跟随等级名", color = TGColors.InkMute, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                }
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(12) },
                        singleLine = true,
                        placeholder = { Text(curLevelName, color = TGColors.InkMute, fontSize = 14.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.weight(1f).focusRequester(nickFocus)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        val v = draft.trim().take(12)
                        if (v.isEmpty()) {
                            // 清空 = 回到「跟随当前等级名」
                            nick = ""
                            custom = false
                            vm.setSyncedSetting("nickname", "")
                            vm.setSyncedSetting("nickname_custom", "")
                            prefs.edit().putString("nickname", "").apply()
                            editing = false
                            ToastHelper.show(ctx, "已恢复为等级名「$curLevelName」")
                        } else {
                            nick = v
                            custom = true
                            vm.setSyncedSetting("nickname", v)
                            vm.setSyncedSetting("nickname_custom", "1")
                            prefs.edit().putString("nickname", v).apply()
                            editing = false
                            ToastHelper.show(ctx, "用户名已改为「$v」")
                        }
                    }) { Text("确定", color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { draft = nick; editing = false }) {
                        Text("取消", color = TGColors.InkMute, fontSize = 14.sp)
                    }
                }
                // L6：只在编辑昵称的这张卡里出现的提示
                if (higher != null) {
                    Spacer(Modifier.height(8.dp))
                    // v5.21.x：原来这里是「您可以提前摘取高处的果实，但通往成功的道路仍在您的前方，
                    //   愿你早日到达。」—— 比喻 + 说教 + 居高临下，正是 boss 要删的那类文字。
                    //   改成一句客观事实：说清这个称号属于哪一级，不评价用户。
                    Text(
                        "「${higher.name}」是 ${higher.lv} 级称号，当前等级还没到。",
                        color = TGColors.GoldDeep,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(TGColors.Gold.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 提醒方式（四通道多选：通知栏/振动/提示音/铃声 + 端选择 + 演示键）——全局默认
        TGCard(Modifier.fillMaxWidth()) {
            Text("提醒方式（默认）", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("同时勾选多个时，按最高档提醒", color = TGColors.InkMute, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            // 四通道多选 + 演示键
            listOf(
                ReminderStrength.NOTIFY to "通知栏",
                ReminderStrength.VIBRATE to "振动",
                ReminderStrength.BEEP to "提示音",
                ReminderStrength.RING to "铃声"
            ).forEach { (v, l) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Checkbox(
                        checked = v in channels,
                        onCheckedChange = { toggleChannel(v) },
                        colors = CheckboxDefaults.colors(checkedColor = TGColors.Gold)
                    )
                    Text(l, color = TGColors.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    // 演示键：点哪个演示哪个效果（不修改 channels 选中状态）
                    TextButton(onClick = { com.taskbar.app.notify.NotificationHelper.demoReminder(ctx, v) }) {
                        Text("演示", color = TGColors.GoldDeep, fontSize = 12.sp)
                    }
                }
            }

            // 端选择（单选：不提醒/仅手机/仅电脑/双端）——分两行排，防挤压
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = TGColors.BorderSoft)
            Spacer(Modifier.height(8.dp))
            Text("提醒范围", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            val scopeOptions = listOf(
                ReminderStrength.SCOPE_NONE to "不提醒",
                ReminderStrength.SCOPE_MOBILE to "仅手机端",
                ReminderStrength.SCOPE_PC to "仅电脑端",
                ReminderStrength.SCOPE_BOTH to "双端提醒"
            )
            // 每行两个，分两行（避免一行挤成竖排）
            scopeOptions.chunked(2).forEach { rowOpts ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowOpts.forEach { (v, l) ->
                        FilterChip(selected = reminderScope == v, onClick = { saveScope(v) }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (reminderScope == ReminderStrength.SCOPE_PC || reminderScope == ReminderStrength.SCOPE_BOTH) {
                Spacer(Modifier.height(4.dp))
                Text("需双端连接后同步提醒（电脑端也要开启提醒）", color = TGColors.Azure, fontSize = 11.sp)
            }

            // 振动档：显示周期选择（选中振动时）
            // v5.21.x 双端统一 + 减元素：原来这里是**两行几乎同名**的控件 ——
            //   上行「短震/中震/长震」（选中），下行「演示短震/演示中震/演示长震」（试听）。
            //   名字重复、作用重叠，一屏白多出三个按钮。
            //   合并成一行：点档位 = 选中并直接震一次（试听），功能一个没少。
            if (ReminderStrength.VIBRATE in channels) {
                Spacer(Modifier.height(8.dp))
                Text("振动周期", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "短震" to "0,300,200,300",
                        "中震" to "0,500,300,500,300,500",
                        "长震" to "0,1000,500,1000,500,1000"
                    ).forEach { (label, pattern) ->
                        FilterChip(
                            selected = vibratePattern == pattern,
                            onClick = {
                                saveVibratePattern(pattern)
                                com.taskbar.app.notify.NotificationHelper.demoReminder(ctx, ReminderStrength.VIBRATE)
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 响铃档：显示铃声选择（选中铃声时，自定义铃声入口一目了然）
            if (ReminderStrength.RING in channels) {
                Spacer(Modifier.height(8.dp))
                Text("铃声（自定义）", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                // v5.15.6：铃声显示名用 RingtoneManager.getRingtone(Uri).getTitle() 拿系统真名
            //   旧版 ringUri.substringAfterLast('/') 在 content://media/... Uri 取到数字 ID 显示乱码
            val displayName = remember(ringUri) {
                if (ringUri.isBlank()) "系统默认铃声"
                else runCatching { RingtoneManager.getRingtone(ctx, android.net.Uri.parse(ringUri))?.getTitle(ctx) ?: null }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?: ringUri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: ringUri
            }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, color = TGColors.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            if (ringUri.isNotBlank()) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(ringUri))
                        }
                        ringtonePicker.launch(intent)
                    }) { Text("选择", color = TGColors.GoldDeep) }
                }
            }

            // v5.15.21 M3（boss：设置界面取消未处理自动升级功能）—— 整段已移除。
            //   相关 state（escalateOn / escalateMinutes / saveEscalate）已不再渲染。
            //   要恢复：从 git 历史取回本段 UI + 下面注释里的 state 声明。
        }

        Spacer(Modifier.height(10.dp))
        // v5.21.0：买断状态（未买断时同时追踪上限受免费额度限制）
        val isPro by vm.isPro.collectAsState()
        // 追踪上限：直接显示当前值 + 更改按钮（点更改弹窗输入新值）
        var showLimitDialog by remember { mutableStateOf(false) }
        TGCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同时追踪上限", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$trackLimit", color = TGColors.GoldDeep, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Text("个任务", color = TGColors.InkMute, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                TextButton(onClick = {
                    if (isPro) showLimitDialog = true
                    else ToastHelper.show(ctx, "买断后可自由调上限；免费版同时追踪 1 个任务")
                }) {
                    Text("更改", color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // 更改追踪上限弹窗（自家风格）
        if (showLimitDialog) {
            LimitDialog(
                current = trackLimit,
                onDismiss = { showLimitDialog = false },
                onConfirm = { n ->
                    vm.setTrackLimit(n.coerceIn(1, 10))
                    showLimitDialog = false
                    ToastHelper.show(ctx, "追踪上限已设为 $n")
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        // v5.21.2（boss：「买断授权」这名字太生硬 + 要有仪式感）——
        //   ① 卡片改名「完整版」：说的是"你会得到什么"，而不是"你要付钱"
        //   ② 左侧一枚菱形徽记：未点亮 = 赤陶细边镂空；已点亮 = 赤陶实心（沿用任务条的菱形语言）
        //   ③ 文案从 激活/已激活 → 点亮/已点亮：点亮的是完整版，不是买了个功能
        var showLicenseDialog by remember { mutableStateOf(false) }
        var litInfo by remember { mutableStateOf<com.taskbar.app.billing.License.Info?>(null) }
        TGCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(13.dp)
                        .then(
                            if (isPro) Modifier.background(TGColors.Gold, RoundedCornerShape(3.dp))
                            else Modifier.border(1.2.dp, TGColors.Gold.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        )
                        .graphicsLayer { rotationZ = 45f }
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("完整版", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (!isPro) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "一次买断",
                                color = TGColors.GoldDeep, fontSize = 10.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TGColors.Gold.copy(alpha = 0.12f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isPro) "已点亮 · 谢谢你的支持"
                        else "还没点亮 · 免费版任务不限量，同时追踪 1 个",
                        color = if (isPro) TGColors.Jade else TGColors.InkMute,
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = { showLicenseDialog = true }) {
                    Text(
                        if (isPro) "查看" else "点亮",
                        color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (showLicenseDialog) {
            LicenseDialog(
                current = if (isPro) vm.licenseInfo() else null,
                onDismiss = { showLicenseDialog = false },
                onSubmit = { code ->
                    val info = vm.redeemLicense(code)
                    showLicenseDialog = false
                    if (info != null) {
                        // 点亮成功 → 交给仪式感弹层，不用一句 Toast 敷衍过去
                        litInfo = info
                    } else {
                        ToastHelper.show(ctx, "这个授权码没验证通过，核对一下再试")
                    }
                }
            )
        }
        litInfo?.let { info -> LicenseLitOverlay(info = info) { litInfo = null } }
        Spacer(Modifier.height(10.dp))
        // 配对状态（桌面端 mDNS 自动发现后点配对即记录在此）
        // v5.15.19：boss「手机端没有显示已连接」—— 新增【实时连接状态】。
        //   原先只有"已配对"这一个历史记录，电脑端断开后依旧显示"已配对"，
        //   用户无法判断当前到底连没连上。现在真值来源是 WS 连接本身（LinkState）。
        TGCard(Modifier.fillMaxWidth()) {
            Text("配对", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            var pairedDevice by remember { mutableStateOf("") }
            // 实时连接状态：直接订阅 WS 连接状态流，电脑端连上/断开即时反映
            val connected by LinkState.flow.collectAsState()
            val lifecycleOwner = LocalLifecycleOwner.current
            val coScope = rememberCoroutineScope()
            DisposableEffect(lifecycleOwner) {
                var job: Job? = null
                val refresh = {
                    job?.cancel()
                    job = coScope.launch {
                        // v5.15 P0：设置页停留期间每 3s 轮询配对状态 —— 桌面端配对/解除后
                        // 手机端不必切走再切回才刷新（之前只 onResume 读一次，用户停在
                        // 设置页等桌面配对时永远显示"未配对"）
                        while (true) {
                            pairedDevice = vm.getSetting("paired_device", "")
                            kotlinx.coroutines.delay(3000)
                        }
                    }
                }
                // v5.17.0：进入设置页 = 打开「配对模式」。
                //   配对申请只在配对模式打开时受理（门1）—— 人没看着手机时，
                //   局域网里的设备连申请都递不进来，更不会弹窗。
                PairingState.setArmed(true)
                refresh()
                val observer = LifecycleEventObserver { _, e ->
                    if (e == Lifecycle.Event.ON_RESUME) {
                        PairingState.setArmed(true)
                        refresh()
                    }
                    if (e == Lifecycle.Event.ON_PAUSE) {
                        PairingState.setArmed(false)
                        job?.cancel()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    PairingState.setArmed(false)
                    job?.cancel()
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
            if (connected) {
                // v5.15.19：以【实时连接】为准（不再依赖 paired_device 这条历史记录）。
                //   电脑端连上 = 已连接；即便 paired_device 还没写进来也能正确显示。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(TGColors.Jade)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已连接",
                        color = TGColors.Jade,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        coScope.launch { AuthState.clear() }   // v5.16.0：密钥一并作废
                        vm.setSetting("paired_device", "")
                        pairedDevice = ""
                    }) { Text("解除配对", color = TGColors.Crimson) }
                }
                Text(
                    // v5.17.0 文案精简：上面已亮绿点 + 写「已连接」，不必再补"实时同步中"
                    if (pairedDevice.isNotEmpty()) "已配对：$pairedDevice"
                    else "电脑端已连上本机",
                    color = TGColors.InkMute, fontSize = 11.sp
                )
            } else if (pairedDevice.isNotEmpty()) {
                // 配对过但当前没连上 → 明确告诉用户"未连接"（旧版这里仍显示"已配对"，误导）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(TGColors.InkFaint)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "未连接",
                        color = TGColors.InkMute,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        coScope.launch { AuthState.clear() }   // v5.16.0：密钥一并作废
                        vm.setSetting("paired_device", "")
                        pairedDevice = ""
                    }) { Text("解除配对", color = TGColors.Crimson) }
                }
                Text(
                    // v5.17.0 文案精简：上面那行已经明确显示「未连接」了，不用再说一遍
                    "已配对：$pairedDevice",
                    color = TGColors.InkMute, fontSize = 11.sp
                )
            } else {
                // ══ v5.17.0 配对改版（boss：取消配对码，改「一端发申请、另一端弹窗确认」）══
                //   实际上这一段的"锁"不止在 UI：手机端只有在**这个页面停留时**才受理配对申请
                //   （PairingState.setArmed，见上面的 DisposableEffect），
                //   受理之后才会弹出下面的确认框。
                val armed by PairingState.armed.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (armed) TGColors.Jade else TGColors.InkFaint)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (armed) "等待电脑申请配对…" else "未连接",
                        color = if (armed) TGColors.Jade else TGColors.InkMute,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "在电脑端点「扫描设备」，选中本机后点「配对」",
                    color = TGColors.InkMute, fontSize = 11.sp
                )
            }
        }

        // ══════════ v5.17.0 配对确认框 ══════════
        // boss 要求："一端发出申请，另一端弹出确认窗口进行确认"。
        // 这个弹窗就是那"另一端"——**只有人在这里点「允许」，配对才会成立**。
        // 6 位数字（SAS）由两端各自独立计算，用来核对信道没被动过手脚。
        val pairScope = rememberCoroutineScope()
        val pendingPair by PairingState.pending.collectAsState()
        val req = pendingPair
        if (req != null) {
            AlertDialog(
                // 不允许点外部消失：这是安全决策，必须显式选「允许」或「拒绝」
                onDismissRequest = { },
                title = { Text("配对请求", color = TGColors.Ink, fontSize = 17.sp) },
                text = {
                    Column {
                        Text(
                            "「" + req.name + "」请求与本机配对",
                            color = TGColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("来源 " + req.ip, color = TGColors.InkMute, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "如果不是你发起的，请点「拒绝」。",
                            color = TGColors.Crimson, fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pairScope.launch { PairingState.approve(req.id) }
                    }) { Text("允许", color = TGColors.Jade, fontWeight = FontWeight.Medium) }
                },
                dismissButton = {
                    TextButton(onClick = { PairingState.deny(req.id) }) {
                        Text("拒绝", color = TGColors.Crimson)
                    }
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        // v5.15.3：起床/睡前每日提醒（AlarmManager 每日定时，TaskBarApp prefs 持久化）
        TGCard(Modifier.fillMaxWidth()) {
            Text("每日提醒", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            // v5.21.x：删掉「按设定时间每天提醒一次，帮你规律作息」——
            //   「每日提醒」+ 起床/睡前两行已自解释，后半句是永远正确的废话。
            Spacer(Modifier.height(8.dp))
            // 起床
            // v5.15.27 M7b ⭐ 真 bug：开关状态原来是**普通 val**（直接读 prefs），
            //   关掉时 onCheckedChange 只写了 prefs 且 showMorningPicker=false 不构成状态变化
            //   → 不触发重组 → Switch 视觉上**纹丝不动**（看着像"点不动/没反应"）。
            //   改成 Compose state，回调里显式赋值。
            var morningOn by remember { mutableStateOf(prefs.getBoolean("daily_morning_on", false)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("☀ 起床", color = TGColors.GoldDeep, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    String.format("%02d:%02d", prefs.getInt("daily_morning_h", 7), prefs.getInt("daily_morning_m", 30)),
                    color = TGColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = morningOn,
                    onCheckedChange = { on ->
                        morningOn = on                      // v5.15.27 M7b：状态驱动重组（否则关了看不出变化）
                        prefs.edit().putBoolean("daily_morning_on", on).apply()
                        if (on) {
                            DailyReminderScheduler.scheduleNext(ctx, DailyReminderScheduler.REQUEST_MORNING,
                                prefs.getInt("daily_morning_h", 7), prefs.getInt("daily_morning_m", 30), "早上好")
                        } else {
                            DailyReminderScheduler.cancel(ctx, DailyReminderScheduler.REQUEST_MORNING)
                        }
                        showMorningPicker = on  // 打开时展开时间选择
                        if (on) { /* 时间默认已在行内显示，点时间文字改 */ }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = TGColors.Gold)
                )
            }
            if (morningOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showMorningPicker = true }) { Text("修改起床时间", color = TGColors.GoldDeep, fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    // v5.15.27 M7：把"下一次什么时候响"直接写出来 —— 一眼能看出这功能是活的
                    Text(
                        "下次：" + nextDailyText(prefs.getInt("daily_morning_h", 7), prefs.getInt("daily_morning_m", 30)),
                        color = TGColors.Jade, fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
            // 睡前
            Spacer(Modifier.height(4.dp))
            var nightOn by remember { mutableStateOf(prefs.getBoolean("daily_night_on", false)) }   // v5.15.27 M7b 同上
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("☾ 睡前", color = TGColors.Azure, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    String.format("%02d:%02d", prefs.getInt("daily_night_h", 22), prefs.getInt("daily_night_m", 30)),
                    color = TGColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = nightOn,
                    onCheckedChange = { on ->
                        nightOn = on                        // v5.15.27 M7b
                        prefs.edit().putBoolean("daily_night_on", on).apply()
                        if (on) {
                            DailyReminderScheduler.scheduleNext(ctx, DailyReminderScheduler.REQUEST_NIGHT,
                                prefs.getInt("daily_night_h", 22), prefs.getInt("daily_night_m", 30), "夜深了")
                        } else {
                            DailyReminderScheduler.cancel(ctx, DailyReminderScheduler.REQUEST_NIGHT)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = TGColors.Gold)
                )
            }
            if (nightOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showNightPicker = true }) { Text("修改睡前时间", color = TGColors.Azure, fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "下次：" + nextDailyText(prefs.getInt("daily_night_h", 22), prefs.getInt("daily_night_m", 30)),
                        color = TGColors.Jade, fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // 后台保活引导（iQOO/小米等 ROM 会冻结后台 → 桌面连不上，引导用户放行）
        // v5.15.27 M8（boss：「点了之后已经后台保活了，能不能让 app 检测一下是否已经保活然后显示出来，
        //   不然一直显示那个保活键，让人总忍不住去点」）——
        //   用 PowerManager.isIgnoringBatteryOptimizations 真查一次；已放行就**不再显示按钮**，
        //   改成 Jade 色的「已保活 ✓」状态条；从系统设置页返回（ON_RESUME）时自动刷新。
        TGCard(Modifier.fillMaxWidth()) {
            val powerMgr = remember {
                ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            }
            fun checkAlive(): Boolean = runCatching {
                powerMgr.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(false)
            var alive by remember { mutableStateOf(checkAlive()) }
            val lcOwner = LocalLifecycleOwner.current
            DisposableEffect(lcOwner) {
                val obs = LifecycleEventObserver { _, e ->
                    if (e == Lifecycle.Event.ON_RESUME) alive = checkAlive()
                }
                lcOwner.lifecycle.addObserver(obs)
                onDispose { lcOwner.lifecycle.removeObserver(obs) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台保活", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    if (alive) {
                        Text(
                            "系统不会再冻结本应用",
                            color = TGColors.Jade, fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                        // M4（boss：保活那里的小字给个链接，点击自动找到自启动设置）——
                        //   先试厂商「自启动管理」页；失败退到本应用的系统详情页（各家 ROM 的自启动都在那儿）
                        Text(
                            "若仍连不上，点这里去系统的「自启动 / 后台高耗电」白名单 ›",
                            color = TGColors.Azure, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                val ok = runCatching {
                                    ctx.startActivity(android.content.Intent().apply {
                                        setClassName(
                                            "com.vivo.permissionmanager",
                                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                                        )
                                    })
                                    true
                                }.getOrDefault(false)
                                if (!ok) runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:" + ctx.packageName)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            }
                        )
                    } else {
                        Text("手机息屏/锁屏后桌面连不上？部分系统会冻结后台", color = TGColors.InkMute, fontSize = 11.sp)
                        Text("在系统设置里允许本应用后台运行 + 自启动", color = TGColors.InkMute, fontSize = 11.sp)
                    }
                }
                if (alive) {
                    // 已保活 → 只显示状态胶囊，按钮撤掉（不再诱导反复点击）
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(TGColors.Jade.copy(alpha = 0.16f))
                            .border(1.dp, TGColors.Jade.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("已保活 ✓", color = TGColors.Jade, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // 未保活 → 跳系统电池优化设置页
                    TextButton(onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            }
                            ctx.startActivity(intent)
                        } catch (_: Exception) {}
                    }) { Text("忽略电池优化", color = TGColors.GoldDeep, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 同步服务器（给电脑端连接用）
        TGCard(Modifier.fillMaxWidth()) {
            Text("同步服务器", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            val ip = remember { SyncService.getLocalIp() ?: "未连接 WiFi" }
            Text("地址: $ip:${BuildConfig.SERVER_PORT}", color = TGColors.Ink, fontSize = 14.sp)
            Text("电脑端输入此地址即可连接（同 WiFi 或手机热点）", color = TGColors.InkMute, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        // 导出备份
        TGCard(Modifier.fillMaxWidth()) {
            Text("数据备份", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    val msg = withContext(Dispatchers.IO) { exportJson(ctx) }
                    ToastHelper.show(ctx, msg, Toast.LENGTH_LONG)
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)) {
                // v5.21.x 按钮主次统一：这里原来是**玉青实底**——全 App 唯一一个用非主色
                //   做的实底主按钮（玉青在别处一律表示"已完成/已连接"状态）。
                //   实底主按钮统一回主色（与「点亮」「确定」一致），玉青只留给成功态。
                Text("导出 JSON 备份", color = Color.White)
            }
            // v5.21.x：删掉「备份文件保存在应用私有目录，可通过文件管理器查看」——
            //   导出成功的 Toast 已经给出完整路径，用户不关心存放机制（boss 点名）。
        }

        // v5.15.3：起床/睡前时间选择对话框
        if (showMorningPicker) {
            TimePickDialog(
                initialHour = prefs.getInt("daily_morning_h", 7),
                initialMinute = prefs.getInt("daily_morning_m", 30),
                title = "起床提醒时间",
                onDismiss = { showMorningPicker = false },
                onConfirm = { h, m ->
                    prefs.edit().putInt("daily_morning_h", h).putInt("daily_morning_m", m).apply()
                    if (prefs.getBoolean("daily_morning_on", false)) {
                        DailyReminderScheduler.scheduleNext(ctx, DailyReminderScheduler.REQUEST_MORNING, h, m, "早上好")
                    }
                    showMorningPicker = false
                }
            )
        }
        if (showNightPicker) {
            TimePickDialog(
                initialHour = prefs.getInt("daily_night_h", 22),
                initialMinute = prefs.getInt("daily_night_m", 30),
                title = "睡前提醒时间",
                onDismiss = { showNightPicker = false },
                onConfirm = { h, m ->
                    prefs.edit().putInt("daily_night_h", h).putInt("daily_night_m", m).apply()
                    if (prefs.getBoolean("daily_night_on", false)) {
                        DailyReminderScheduler.scheduleNext(ctx, DailyReminderScheduler.REQUEST_NIGHT, h, m, "夜深了")
                    }
                    showNightPicker = false
                }
            )
        }
    }
}

/** v5.15.3：时间选择对话框（Material3 TimePicker） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    initialHour: Int, initialMinute: Int, title: String,
    onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TGColors.Ink, fontWeight = FontWeight.Medium) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("确定", color = TGColors.GoldDeep, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TGColors.InkMute) } }
    )
}

private suspend fun exportJson(ctx: android.content.Context): String {
    return try {
        val app = ctx.applicationContext as TaskBarApp
        val payload = app.repo.buildFullSyncPayload()
        val json = Json { encodeDefaults = true; prettyPrint = true }
            .encodeToString(com.taskbar.app.data.model.FullSyncPayload.serializer(), payload)
        val dir = File(ctx.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val file = File(dir, "taskguide-backup-${System.currentTimeMillis()}.json")
        file.writeText(json, Charsets.UTF_8)
        "已导出到: ${file.absolutePath}"
    } catch (e: Exception) {
        "导出失败: ${e.message}"
    }
}

/** 更改追踪上限弹窗（自家风格：数字输入 + 滑动步进 + 确定/取消） */
@Composable
fun LimitDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var num by remember { mutableStateOf(current.toString()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TGColors.Card)
                .border(1.5.dp, TGColors.Gold, RoundedCornerShape(16.dp))
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("更改同时追踪上限", color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("1~10 个（同时在追踪的任务数）", color = TGColors.InkMute, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 减号
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(TGColors.BgPaperDeep)
                        .clickable {
                            val n = (num.toIntOrNull() ?: 1) - 1
                            num = n.coerceIn(1, 10).toString()
                        },
                    contentAlignment = Alignment.Center
                ) { Text("−", color = TGColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                // 当前值
                OutlinedTextField(
                    value = num,
                    onValueChange = { num = it.filter { c -> c.isDigit() }.take(2).ifEmpty { "1" } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(70.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                )
                // 加号
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(TGColors.BgPaperDeep)
                        .clickable {
                            val n = (num.toIntOrNull() ?: 1) + 1
                            num = n.coerceIn(1, 10).toString()
                        },
                    contentAlignment = Alignment.Center
                ) { Text("+", color = TGColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TGColors.InkSoft)
                ) { Text("取消") }
                Button(
                    onClick = { onConfirm(num.toIntOrNull() ?: 1) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)
                ) { Text("确定", color = TGColors.Ink, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/**
 * v5.21.2：完整版弹窗。
 *   current == null → 「点亮完整版」：先说清你能得到什么，再让用户粘贴授权码
 *   current != null → 已点亮：展示授权对象 / 订单号 / 签发日期
 * 视觉沿用项目既有语言（米白卡 + 极细赤陶边 + 菱形点标记），不新造风格。
 */
@Composable
fun LicenseDialog(
    current: com.taskbar.app.billing.License.Info?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val lctx = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TGColors.Card)
                .border(1.5.dp, TGColors.Gold, RoundedCornerShape(16.dp))
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text(
                if (current != null) "完整版已点亮" else "点亮完整版",
                color = TGColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            if (current != null) {
                Text("授权对象：${current.to}", color = TGColors.InkSoft, fontSize = 13.sp)
                Text("订单号：${current.order}", color = TGColors.InkMute, fontSize = 12.sp)
                Text("签发日期：${current.issuedAt}", color = TGColors.InkMute, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("一次买断，永久有效，不用联网。", color = TGColors.Jade, fontSize = 12.sp)
            } else {
                Text("一次买断，永久有效，不用联网。", color = TGColors.InkMute, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                // 先说清「你会得到什么」（原先这段挤在一句话里，读着像功能清单）
                listOf("同时追踪多个任务", "导出备份", "外观主题").forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(TGColors.Gold, RoundedCornerShape(2.dp))
                                .graphicsLayer { rotationZ = 45f }
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(line, color = TGColors.InkSoft, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    placeholder = { Text("把授权码粘贴到这里", color = TGColors.InkMute, fontSize = 13.sp) },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                // 139 个字符靠手输太苦 → 给一个明确的「粘贴」键（读系统剪贴板）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        runCatching { clip.getText()?.text?.let { code = it.trim() } }
                    }) { Text("粘贴", color = TGColors.GoldDeep, fontSize = 13.sp) }
                }
                Text(
                    "还没有授权码？点这里去爱发电支持一下",
                    color = TGColors.GoldDeep, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        runCatching {
                            lctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(com.taskbar.app.billing.License.PAY_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TGColors.InkSoft)
                ) { Text("关闭") }
                if (current == null) {
                    Button(
                        onClick = { onSubmit(code) },
                        modifier = Modifier.weight(1f),
                        enabled = code.isNotBlank()
                    ) { Text("点亮") }
                }
            }
        }
    }
}

