package com.taskbar.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.BuildConfig
import com.taskbar.app.TaskBarApp
import com.taskbar.app.server.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

@Composable
fun SettingsScreen(vm: TaskViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val trackLimit by vm.trackLimit.collectAsState()
    var strength by remember { mutableStateOf("standard") }
    var limitText by remember { mutableStateOf(trackLimit.toString()) }

    LaunchedEffect(Unit) {
        strength = vm.getSetting("reminder_strength", "standard")
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("设置", color = TGColors.GoldLight, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp, 12.dp))

        // 我的积分（图1奖励区风格）
        val points by vm.totalPoints.collectAsState()
        TGCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("💠 我的积分", color = TGColors.GoldLight, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("◆ $points", color = TGColors.GoldLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text("完成任务可获得积分，完成后自动累加", color = TGColors.Fog, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        // 提醒强度
        TGCard(Modifier.fillMaxWidth()) {
            Text("提醒强度", color = TGColors.GoldLight, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            listOf("standard" to "标准通知", "repeat" to "重复提醒(5分钟)", "alarm" to "闹钟式强提醒").forEach { (v, l) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    RadioButton(selected = strength == v, onClick = { strength = v; vm.setSetting("reminder_strength", v) }, colors = RadioButtonDefaults.colors(selectedColor = TGColors.Gold))
                    Text(l, color = TGColors.Paper, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // 追踪上限
        TGCard(Modifier.fillMaxWidth()) {
            Text("同时追踪上限", color = TGColors.GoldLight, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)) { Text("保存", color = TGColors.Ink) }
                Spacer(Modifier.width(8.dp))
                Text("当前: $trackLimit", color = TGColors.Fog, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        // 服务器地址（给电脑端连接用）
        TGCard(Modifier.fillMaxWidth()) {
            Text("同步服务器", color = TGColors.GoldLight, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            val ip = remember { SyncService.getLocalIp() ?: "未连接 WiFi" }
            Text("地址: $ip:${BuildConfig.SERVER_PORT}", color = TGColors.Paper, fontSize = 14.sp)
            Text("电脑端输入此地址即可连接（同 WiFi 或手机热点）", color = TGColors.Fog, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        // 导出备份
        TGCard(Modifier.fillMaxWidth()) {
            Text("数据备份", color = TGColors.GoldLight, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    val msg = withContext(Dispatchers.IO) { exportJson(ctx) }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = TGColors.Jade)) { Text("导出 JSON 备份", color = TGColors.Paper) }
            Text("备份文件保存在应用私有目录，可通过文件管理器查看", color = TGColors.Fog, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
