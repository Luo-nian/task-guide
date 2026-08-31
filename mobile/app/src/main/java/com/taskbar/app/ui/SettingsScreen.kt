package com.taskbar.app.ui

import android.content.Intent
import android.media.RingtoneManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    var limitText by remember { mutableStateOf(trackLimit.toString()) }

    // ====== 提醒方式（共享 prefs 直读直写） ======
    val prefs = remember { ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE) }
    var strength by remember { mutableStateOf(prefs.getString("reminder_strength", "notify") ?: "notify") }
    var vibratePattern by remember { mutableStateOf(prefs.getString("reminder_vibrate_pattern", "0,300,200,300,200,300") ?: "0,300,200,300,200,300") }
    var ringUri by remember { mutableStateOf(prefs.getString("reminder_ring_uri", "") ?: "") }
    var escalateOn by remember { mutableStateOf(prefs.getBoolean("reminder_escalate_enabled", true)) }
    var escalateMinutes by remember { mutableIntStateOf(prefs.getInt("reminder_escalate_minutes", 5)) }
    var customVibrateText by remember { mutableStateOf("") }

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

    fun saveStrength(v: String) {
        strength = ReminderStrength.migrateLegacy(v).ifEmpty { ReminderStrength.NOTIFY }
        prefs.edit().putString("reminder_strength", strength).apply()
    }
    fun saveVibratePattern(s: String) {
        vibratePattern = s
        prefs.edit().putString("reminder_vibrate_pattern", s).apply()
    }
    fun saveEscalate(on: Boolean, mins: Int) {
        escalateOn = on; escalateMinutes = mins
        prefs.edit().putBoolean("reminder_escalate_enabled", on).putInt("reminder_escalate_minutes", mins).apply()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
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
        // 提醒方式（三档：通知栏弹窗 / 振动 / 响铃）
        TGCard(Modifier.fillMaxWidth()) {
            Text("提醒方式", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("任务到点用哪种方式提醒你，可在新建/编辑任务时单独覆盖", color = TGColors.InkMute, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            listOf(
                ReminderStrength.NOTIFY  to ReminderStrength.label(ReminderStrength.NOTIFY),
                ReminderStrength.VIBRATE to ReminderStrength.label(ReminderStrength.VIBRATE),
                ReminderStrength.RING    to ReminderStrength.label(ReminderStrength.RING)
            ).forEach { (v, l) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    RadioButton(
                        selected = strength == v,
                        onClick = { saveStrength(v) },
                        colors = RadioButtonDefaults.colors(selectedColor = TGColors.Gold)
                    )
                    Text(l, color = TGColors.Ink, fontSize = 13.sp)
                }
            }

            // 振动档：显示周期选择
            if (strength == ReminderStrength.VIBRATE) {
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
                            onClick = { saveVibratePattern(pattern); customVibrateText = "" },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customVibrateText,
                        onValueChange = { customVibrateText = it.filter { c -> c.isDigit() || c == ',' }.take(40) },
                        placeholder = { Text("自定义毫秒模式", color = TGColors.InkMute, fontSize = 11.sp) },
                        label = { Text("自定义", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        if (customVibrateText.isNotBlank()) saveVibratePattern(customVibrateText)
                    }) { Text("应用", color = TGColors.GoldDeep) }
                }
                Text("格式：逗号分隔毫秒，如 0,300,200,300", color = TGColors.InkMute, fontSize = 10.sp)
            }

            // 响铃档：显示选择按钮
            if (strength == ReminderStrength.RING) {
                Spacer(Modifier.height(8.dp))
                Text("铃声", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

            // 升级机制（所有档位都适用）
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = TGColors.BorderSoft)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("未处理自动升级", color = TGColors.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Switch(checked = escalateOn, onCheckedChange = { saveEscalate(it, escalateMinutes) }, colors = SwitchDefaults.colors(checkedThumbColor = TGColors.Gold))
            }
            Text("提醒发出后 X 分钟你没处理，就升级到更强的振动/响铃提醒", color = TGColors.InkMute, fontSize = 11.sp)
            if (escalateOn) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 30).forEach { m ->
                        FilterChip(selected = escalateMinutes == m, onClick = { saveEscalate(true, m) }, label = { Text("$m 分钟") })
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // 追踪上限
        TGCard(Modifier.fillMaxWidth()) {
            Text("同时追踪上限", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.width(80.dp),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val n = limitText.toIntOrNull() ?: 3
                    vm.setTrackLimit(n.coerceIn(1, 10))
                }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)) {
                    Text("保存", color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text("当前: $trackLimit", color = TGColors.InkMute, fontSize = 13.sp)
            }
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
