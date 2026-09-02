package com.taskbar.app.ui

import android.content.Intent
import android.media.RingtoneManager
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import com.taskbar.app.server.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

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
    // 多选通道（通知栏/振动/提示音/铃声） + 端选择（不提醒/仅手机/仅电脑/双端）
    var channels by remember { mutableStateOf(listOf<String>()) }
    var reminderScope by remember { mutableStateOf(ReminderStrength.SCOPE_MOBILE) }
    var vibratePattern by remember { mutableStateOf(prefs.getString("reminder_vibrate_pattern", "0,300,200,300,200,300") ?: "0,300,200,300,200,300") }
    var ringUri by remember { mutableStateOf(prefs.getString("reminder_ring_uri", "") ?: "") }
    var escalateOn by remember { mutableStateOf(prefs.getBoolean("reminder_escalate_enabled", true)) }
    var escalateMinutes by remember { mutableIntStateOf(prefs.getInt("reminder_escalate_minutes", 5)) }

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
    fun saveEscalate(on: Boolean, mins: Int) {
        escalateOn = on; escalateMinutes = mins
        prefs.edit().putBoolean("reminder_escalate_enabled", on).putInt("reminder_escalate_minutes", mins).apply()
    }

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
        // 提醒方式（四通道多选：通知栏/振动/提示音/铃声 + 端选择 + 演示键）——全局默认
        TGCard(Modifier.fillMaxWidth()) {
            Text("提醒方式（默认）", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("可多选组合，如「通知栏+铃声」；任务到点按最高档提醒", color = TGColors.InkMute, fontSize = 11.sp)
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
                            onClick = { saveVibratePattern(pattern) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "演示短震" to "0,300,200,300",
                        "演示中震" to "0,500,300,500,300,500",
                        "演示长震" to "0,1000,500,1000,500,1000"
                    ).forEach { (label, pattern) ->
                        TextButton(onClick = {
                            saveVibratePattern(pattern)
                            com.taskbar.app.notify.NotificationHelper.demoReminder(ctx, ReminderStrength.VIBRATE)
                        }) { Text(label, color = TGColors.GoldDeep, fontSize = 12.sp) }
                    }
                }
            }

            // 响铃档：显示铃声选择（选中铃声时，自定义铃声入口一目了然）
            if (ReminderStrength.RING in channels) {
                Spacer(Modifier.height(8.dp))
                Text("铃声（自定义）", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                val displayName = if (ringUri.isBlank()) "系统默认铃声" else ringUri.substringAfterLast('/')
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

            // 升级机制（按最高档只升一级）
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = TGColors.BorderSoft)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("未处理自动升级", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Switch(checked = escalateOn, onCheckedChange = { saveEscalate(it, escalateMinutes) }, colors = SwitchDefaults.colors(checkedThumbColor = TGColors.Gold))
            }
            Text("提醒发出后 X 分钟你没处理，就按最高档升一级（如通知→振动）", color = TGColors.InkMute, fontSize = 11.sp)
            if (escalateOn) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 30).forEach { m ->
                        // 升级分钟 chip：深色背景确保开关打开后按键不消失
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (escalateMinutes == m) TGColors.Ink else TGColors.Selected.copy(alpha = 0.85f))
                                .border(1.dp, if (escalateMinutes == m) TGColors.Gold else TGColors.GoldDeep.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                                .clickable { saveEscalate(true, m) }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "$m 分钟",
                                color = if (escalateMinutes == m) TGColors.GoldLight else TGColors.Ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
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
                TextButton(onClick = { showLimitDialog = true }) {
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
        // 配对状态（桌面端 mDNS 自动发现后点配对即记录在此）
        TGCard(Modifier.fillMaxWidth()) {
            Text("配对", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            var pairedDevice by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { pairedDevice = vm.getSetting("paired_device", "") }
            if (pairedDevice.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已配对：$pairedDevice", color = TGColors.Jade, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        vm.setSetting("paired_device", "")
                        pairedDevice = ""
                    }) { Text("解除配对", color = TGColors.Crimson) }
                }
            } else {
                Text("未配对", color = TGColors.InkMute, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("电脑端在设置里点「扫描设备」即可自动发现本机并配对，无需手动输地址", color = TGColors.InkMute, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        // 服务器地址（给电脑端连接用）
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
            }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Jade)) {
                Text("导出 JSON 备份", color = Color.White)
            }
            Text("备份文件保存在应用私有目录，可通过文件管理器查看", color = TGColors.InkMute, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
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
