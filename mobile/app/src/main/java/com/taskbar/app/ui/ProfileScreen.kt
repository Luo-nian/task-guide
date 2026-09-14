package com.taskbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.taskbar.app.R
import com.taskbar.app.data.model.Levels
import kotlinx.coroutines.launch

/** 下一级等级名（"我的"页等级卡用） */
private fun nextLevelName(currentLv: Int): String = when (currentLv) {
    1 -> "风华游侠"
    2 -> "破浪骑士"
    3 -> "群星行者"
    4 -> "传奇勇者"
    else -> "下一级"
}

/** "我的"页：等级卡 + 今日进度/坚持统计 + 设置入口 */
@Composable
fun ProfileScreen(vm: TaskViewModel, navController: NavController) {
    val points by vm.totalPoints.collectAsState()
    val todayCount by vm.mainList.collectAsState()
    val level = Levels.of(points)
    // 习惯最长坚持天数（正反馈统计）
    var maxStreak by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { maxStreak = runCatching { vm.maxHabitStreak() }.getOrDefault(0) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "我的",
            color = TGColors.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(4.dp, 12.dp)
        )

        // ===== v5.15：自定义头像 + 8 内置头像选择（本地 prefs avatar_emoji 持久化） =====
        // v5.15.7：电脑端裁剪上传的自定义头像（settings.avatar_img，跨端同步）优先级最高
        val ctx = LocalContext.current
        val prefs = remember { ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE) }
        var avatarEmoji by remember { mutableStateOf(prefs.getString("avatar_emoji", "") ?: "") }
        val syncedAvatar by vm.avatarImg.collectAsState()
        val syncedBmp = rememberAvatarBitmap(syncedAvatar)
        // v5.15.10：手机端也能自定义头像 —— 相册选图 → 只取中间圆形区域 → 256px PNG → 推电脑端
        val scope = rememberCoroutineScope()
        var uploading by remember { mutableStateOf(false) }
        // v5.15.12：选完图先进「裁剪界面」，由用户自己拖动/缩放决定圆形范围（boss：不要自动裁）
        // v5.15.14：改用 rememberSaveable 存 uri 字符串 —— 部分 ROM 的文件选择器返回时会重建 Activity，
        //   普通 remember 会丢状态 → 表现为"选完图什么都没发生"（实测踩到）
        var cropUriStr by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) cropUriStr = uri.toString()
        }
        // 裁剪确认 → 生成圆形 PNG → 走设置同步通道推给电脑端
        cropUriStr?.let { us ->
            val u = android.net.Uri.parse(us)
            AvatarCropDialog(
                uri = u,
                onCancel = { cropUriStr = null },
                onConfirm = { dataUrl ->
                    cropUriStr = null
                    uploading = true
                    scope.launch {
                        vm.setSyncedSetting("avatar_img", dataUrl)
                        prefs.edit().putString("avatar_emoji", "").apply()
                        avatarEmoji = ""
                        uploading = false
                    }
                }
            )
        }
        val avatarPalette = listOf("#E8CB7F", "#D8B45A", "#C9A227", "#8CE0C8", "#5BA3D0", "#B49BE0", "#E07BD0", "#FF8A5B")
        val avatarEmojis = listOf("🦊", "🐯", "🦉", "🐺", "🐼", "🦁", "🐲", "🦅")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(TGColors.Card)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 大头像（自定义图 > 选中 emoji > 人形），点它 = 从相册选图
            Box(
                Modifier.size(68.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (avatarEmoji.isNotEmpty() || syncedBmp != null) Color(0xFF1A1206) else TGColors.GoldLight)
                    .border(2.dp, TGColors.Gold, androidx.compose.foundation.shape.CircleShape)
                    .clickable { pickImage.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (syncedBmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = syncedBmp,
                        contentDescription = "头像",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (avatarEmoji.isNotEmpty()) Text(avatarEmoji, fontSize = 32.sp)
                else TGIcon(R.drawable.ic_avatar, "头像", tint = TGColors.Ink, size = 34.dp)
            }
            Spacer(Modifier.width(10.dp))
            // 相册上传入口（自动裁中间圆形区域 + 同步电脑端）
            Column {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(TGColors.Gold.copy(alpha = 0.18f))
                        .border(1.dp, TGColors.Gold, RoundedCornerShape(999.dp))
                        .clickable(enabled = !uploading) { pickImage.launch("image/*") }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        // v5.15.21 M4（boss：头像旁边的按键改为"上传头像"）
                        if (uploading) "处理中…" else "上传头像",
                        color = TGColors.GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                // v5.15.12：boss 要求去掉这行提示文案（改为裁剪界面里说明）

            }
            Spacer(Modifier.width(10.dp))
            // 8 内置头像网格（横向）—— 选中即换头像：同时清掉自定义图
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(avatarEmojis) { i, e ->
                    val bg = avatarPalette[i % avatarPalette.size]
                    Box(
                        Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(android.graphics.Color.parseColor(bg)))
                            .border(if (e == avatarEmoji && syncedBmp == null) 2.dp else 0.dp, TGColors.Ink, androidx.compose.foundation.shape.CircleShape)
                            .clickable {
                                avatarEmoji = e
                                prefs.edit().putString("avatar_emoji", e).apply()
                                // v5.15.7：推给电脑端（桌面显示同款 emoji）+ 清掉自定义图，双端头像一致
                                vm.setSyncedSetting("avatar_emoji", e)
                                vm.setSyncedSetting("avatar_img", "")
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 17.sp) }
                }
            }
        }
        // ===== v5.15.27 M9（boss：「为什么手机端不能修改用户名」+「昵称编辑在我的页面写」）=====
        //   昵称此前只能在电脑端改。现在放在「我的」页头像卡下面。
        // v5.15.27 M9b（boss 二次修订）：
        //   ① 标题就叫「用户名」（不要「（昵称）」）；② 去掉「电脑端的问候语…」那句小字；
        //   ③ 交互改成**「修改」式**（默认只显示当前值 + 修改按钮，点修改才出现输入框 + 确定/取消），
        //      与设置页「同时追踪上限 → 更改」同一种交互语言 —— 不要常态可编辑的输入框。
        Spacer(Modifier.height(10.dp))
        TGCard(Modifier.fillMaxWidth()) {
            var nick by remember { mutableStateOf("") }
            var draft by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                runCatching { nick = vm.getSetting("nickname", "boss") }
                draft = nick
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("用户名", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (!editing) {
                    Text(nick, color = TGColors.GoldDeep, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { draft = nick; editing = true }) {
                        Text("修改", color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(12) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        val v = draft.trim().ifEmpty { "boss" }
                        nick = v
                        // 与电脑端同一把 key：nickname（走同步通道，桌面端立刻能看到）
                        vm.setSyncedSetting("nickname", v)
                        prefs.edit().putString("nickname", v).apply()
                        editing = false
                        ToastHelper.show(ctx, "用户名已改为「$v」")
                    }) { Text("确定", color = TGColors.GoldDeep, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { draft = nick; editing = false }) {
                        Text("取消", color = TGColors.InkMute, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ===== 等级卡：黑金镜面金属（斜扫高光 + 镜面反射渐变），Lv 越高光泽越强 =====
        val gloss = 0.30f + (level.lv - 1) * 0.06f   // 镜面光泽强度随等级（克制，不挡字）
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height; val r = 14.dp.toPx()
                // 1) 底层：深咖→金 斜向渐变（金属板底色）
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1A1206),   // 左上暗
                            Color(0xFF4A3510),   // 
                            Color(0xFF8A6A20),   // 金
                            Color(0xFF3A2A0C)    // 右下回暗
                        ),
                        start = Offset(0f, 0f), end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(r, r)
                )
                // 2) 斜扫高光带（真正的"镜面金属"感：一道 45° 亮白带从左上扫到右下）
                //    中心亮带 + 两侧渐暗，模拟灯光在金属表面反射
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0xFF6B5220).copy(alpha = 0.0f),
                            0.32f to Color(0xFFFFFFFF).copy(alpha = 0.05f * gloss),
                            0.46f to Color(0xFFFFFFFF).copy(alpha = 0.30f * gloss),  // 高光最亮点
                            0.53f to Color(0xFFFFF3C0).copy(alpha = 0.20f * gloss),
                            0.62f to Color(0xFFFFFFFF).copy(alpha = 0.15f * gloss),
                            0.78f to Color(0xFFFFE9A8).copy(alpha = 0.05f * gloss),
                            1.00f to Color(0xFF3A2A0C).copy(alpha = 0.0f)
                        ),
                        start = Offset(0f, 0f), end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(r, r)
                )
                // 3) 底部镜面反光（金属卡下缘常见的一道微弱反光）
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFFFFF0C0).copy(alpha = 0.10f * gloss), Color.Transparent)
                    ),
                    topLeft = Offset(0f, h * 0.82f),
                    size = Size(w, h * 0.18f),
                    cornerRadius = CornerRadius(r, r)
                )
                // 4) 细金描边（镜面+描边立体感）
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(TGColors.GoldLight.copy(alpha = 0.8f), TGColors.Gold.copy(alpha = 0.35f), TGColors.GoldLight.copy(alpha = 0.9f)),
                        start = Offset(0f, 0f), end = Offset(0f, h)
                    ),
                    topLeft = Offset(0.8f.dp.toPx(), 0.8f.dp.toPx()),
                    size = Size(w - 1.6f.dp.toPx(), h - 1.6f.dp.toPx()),
                    cornerRadius = CornerRadius(r, r), style = Stroke(width = 1.dp.toPx())
                )
            }
            // 内容层：文字用深咖（金底上比白字更贵气）+ 阴影托底
            // v5.15.8：卡片高度自适应；等级标语独占整行宽度（之前被右列挤压 → "深渊在凝视，而你在…"）
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // v5.15.7/8：等级徽章与桌面端同渲染 ——
                //   金色放射盘（radial 亮金→深金）+ 内侧双金环 + 外发光 + lucide 线稿图标（--rco 深棕描边）
                val pal = level.palette
                val glyphPaths = LevelGlyphs.PATHS[level.lv].orEmpty()
                val hasGlyph = glyphPaths.any { !it.isEmpty }
                val levelEmoji = when (level.lv) {
                    1 -> "\uD83C\uDF31"  // 🌱 萌芽
                    2 -> "\uD83E\uDD6B"  // 🦫 风华游侠
                    3 -> "\u26F5"        // ⛵ 破浪骑士
                    4 -> "\uD83C\uDF19"  // 🌙 群星行者
                    5 -> "\uD83C\uDFC6"  // 🏆 传奇勇者
                    6 -> "\uD83D\uDEE1"  // 🛡 苍穹守护者
                    7 -> "\u2693"        // ⚓ 深渊征服者
                    8 -> "\u2600\uFE0F"  // ☀️ 星辰霸主
                    9 -> "\uD83D\uDC41"  // 👁 天命传奇
                    10 -> "\uD83C\uDF0C" // 🌌 寰宇传说
                    else -> "${level.lv}"
                }
                Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        val c = center
                        // 外发光（近似桌面 box-shadow 0 0 Npx）
                        drawCircle(pal.glow.copy(alpha = pal.glow.alpha * 0.30f), radius = r + 3.dp.toPx(), center = c)
                        drawCircle(pal.glow.copy(alpha = pal.glow.alpha * 0.16f), radius = r + 6.dp.toPx(), center = c)
                        // 盘面：radial-gradient(circle at cx cy, 亮金 → 中金 55% → 深金)
                        //   半径取"最远角"（CSS radial-gradient 默认 farthest-corner），与桌面同渲染
                        val fx = maxOf(pal.cx, 1f - pal.cx) * size.width
                        val fy = maxOf(pal.cy, 1f - pal.cy) * size.height
                        val gradR = kotlin.math.sqrt(fx * fx + fy * fy)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = pal.stops.zip(pal.plate).toTypedArray(),
                                center = Offset(size.width * pal.cx, size.height * pal.cy),
                                radius = gradR
                            ),
                            radius = r, center = c
                        )
                        // 内侧双环：2px 亮环贴边 + 2px 深金环（桌面 inset 0 0 0 2px / 4px）
                        drawCircle(pal.ringLight, radius = r - 1.dp.toPx(), center = c,
                            style = Stroke(width = 2.dp.toPx()))
                        drawCircle(pal.ringDark, radius = r - 3.dp.toPx(), center = c,
                            style = Stroke(width = 2.dp.toPx()))
                        // 图标：与桌面同一套 lucide 线稿（24 视口 / stroke 2.2 / 深棕 --rco）
                        if (hasGlyph) {
                            val glyph = 36.dp.toPx()          // 桌面：48px 徽章 + 36px 图标
                            val k = glyph / 24f
                            withTransform({
                                translate(c.x - glyph / 2f, c.y - glyph / 2f)
                                scale(k, k, Offset.Zero)
                            }) {
                                glyphPaths.forEach { p ->
                                    drawPath(
                                        path = p, color = pal.iconColor,
                                        style = Stroke(
                                            width = 2.2f,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                                        )
                                    )
                                }
                            }
                        }
                    }
                    // 兜底：线稿解析失败（极端情况）才退回 emoji，避免徽章空白
                    if (!hasGlyph) Text(levelEmoji, fontSize = 30.sp)
                }
                Spacer(Modifier.width(16.dp))
                // 等级名：整行剩余宽度独占（"Lv.N" 挪到右列小字，避免 18sp 名字被挤到截断）
                Text(
                    level.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    style = LocalTextStyle.current.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 1f), 0f))
                )
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Lv.${level.lv}", color = Color(0xFFE8CB7F), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(1.dp))
                    Text("$points / ${level.max}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, style = LocalTextStyle.current.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 1f), 0f)))
                    Spacer(Modifier.height(3.dp))
                    Text(if (level.toNext > 0) "距下一级 ${level.toNext}" else "已登顶", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                }
                }
                // 等级标语：整行整宽，不再被挤压截断
                Spacer(Modifier.height(10.dp))
                Text(
                    level.title,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(level.progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF7A5516), Color(0xFFC9A227), Color(0xFFD8B45A)))))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 统计卡：今日进度 + 习惯坚持（正反馈层层递进）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TGCard(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TGIcon(R.drawable.ic_trend, contentDescription = null, tint = TGColors.Azure, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("今日进度", color = TGColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${todayCount.size} 个待完成",
                    color = TGColors.Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("今天要做的事", color = TGColors.InkMute, fontSize = 10.sp)
            }
            TGCard(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TGIcon(R.drawable.ic_trophy, contentDescription = null, tint = TGColors.Jade, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("习惯坚持", color = TGColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "$maxStreak 天",
                    color = TGColors.Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("最长连续打卡", color = TGColors.InkMute, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        // 设置入口（点击进入子页面）
        TGCard(Modifier.fillMaxWidth().clickable { navController.navigate("settings") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TGIcon(R.drawable.ic_settings, contentDescription = null, tint = TGColors.GoldDeep, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("设置", color = TGColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = TGColors.InkMute, fontSize = 20.sp)
            }
        }
    }
}