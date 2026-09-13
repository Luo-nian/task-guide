package com.taskbar.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.R
import com.taskbar.app.data.model.Priority

// ==================== 全局 Toast（自家风格 + 防抖 + 单例覆盖，杜绝连点刷屏/截断） ====================
object ToastHelper {
    private var lastMsg: String = ""
    private var lastAt: Long = 0
    private var toast: Toast? = null

    /** 同一内容 2.5s 内只提示一次；新提示会顶掉旧提示（防排队堆积）。
     *  样式：圆角米底 + 金边 + 深褐字（自家软件风格，不用系统灰底黑字） */
    fun show(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) {
        val now = System.currentTimeMillis()
        if (msg == lastMsg && now - lastAt < 2500) return
        lastMsg = msg
        lastAt = now
        toast?.cancel()
        val t = Toast.makeText(ctx.applicationContext, msg, duration)
        val tv = android.widget.TextView(ctx.applicationContext).apply {
            text = msg
            setTextColor(0xFF3A2E1A.toInt())
            textSize = 14f
            maxLines = 3
            setPadding(48, 28, 48, 28)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0xFFFFFBEF.toInt())
                setStroke(2, 0xFFC9A227.toInt())
            }
        }
        t.view = tv
        t.show()
        toast = t
    }
}

/**
 * 带按压缩放反馈的 IconButton（按下缩小 0.84，松开回弹）。
 * 所有图标按钮统一用它，保证"按下有反馈"。
 */
@Composable
fun PressIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, tween(100))
    IconButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) { content() }
}

/**
 * 带按压缩放反馈的**自适应尺寸**按钮（按下缩小 0.84，松开回弹）。
 *
 * 与 [PressIcon] 的区别：PressIcon 内部是 Material3 `IconButton`，而后者会强制
 * `.size(40.dp)`。文字按钮用它会撞死宽度 —— 左右 padding 16dp 吃掉 32dp 后只剩
 * 8dp，「打卡」被裁成只剩提手旁「扌」。
 * 所以文字/胶囊类按钮一律用本组件（Box + clickable，尺寸跟着内容走）。
 */
@Composable
fun PressPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, tween(100))
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ==================== 配色（v5.4 暖土撞色：赤陶+桃黏土+鼠尾草绿，告别冰川蓝冷感） ====================
// 设计思路：2026 流行 Terracotta Modern 撞色——米白底 + 暖赤陶主 + 桃黏土强调 + 鼠尾草绿完成 + 浓缩咖啡深底
// 蓝调（冰川蓝）取消，整体偏暖不冷；金调（之前的金色仍保留少量做"荣誉"色，不作主色）
// 旧冰川蓝方案 v5.0/v5.3 在 git 142292a 留档可一键回退
object TGColors {
    // ---- 背景（暖米白底，更柔） ----
    val BgPaper     = Color(0xFFF5F0E8)   // 主背景 暖白米色（Terracotta Modern 暖白 #F5F0E8）
    val BgPaperDeep = Color(0xFFEAE2D2)   // 深一档
    val Panel       = Color(0xFFFCF8EE)   // 面板
    val PanelSolid  = Color(0xFFF9F2E2)   // 面板实色
    val Card        = Color(0xFFFFFCF3)   // 卡片
    val Bar         = Color(0xFFE8DDC2)   // 任务条
    val BarHover    = Color(0xFFE4D5AF)   // 任务条按下
    val Selected    = Color(0xFFE6D9BC)   // 选中

    // ---- 文字（暖深咖啡，告别蓝灰冷感） ----
    val Ink         = Color(0xFF3B2A20)   // 主文字 浓缩咖啡（Terracotta Modern #3B2A20）
    val InkSoft     = Color(0xFF6B574A)   // 副文字
    val InkMute     = Color(0xFF9C8B7A)   // 三级
    val InkFaint    = Color(0xFFC2B59E)   // 四级/分隔

    // ---- 招牌位（深底：浓缩咖啡） ----
    val Black       = Color(0xFF3B2A20)   // 招牌位深底（深咖啡，告别纯黑/蓝灰）
    val BlackSoft   = Color(0xFF5A463A)   // 浅深咖

    // ---- 主色：赤陶（取代冰川蓝作"主调/描边/点缀"） ----
    val Gold        = Color(0xFFC1603F)   // 主色 赤陶 Terracotta Modern #C1603F
    val GoldLight   = Color(0xFFE8A87C)   // 浅赤陶 桃黏土 Peach Clay
    val GoldDeep    = Color(0xFF8E4424)   // 深赤陶 浅底可读字

    // ---- 强调色：桃黏土（辅助高亮） ----
    val Orange      = Color(0xFFD4795A)   // 暖橙偏赤陶（重要提示）
    val OrangeLight = Color(0xFFE8A87C)   // 桃黏土

    // ---- 装饰色（v5.4 全面去蓝化） ----
    val Jade        = Color(0xFF7D9B76)   // 鼠尾草绿 Muted Sage #7D9B76（取代"完成绿"）
    val Crimson     = Color(0xFFB85638)   // 朱砂 紧急/高优先级
    val Azure       = Color(0xFF6B8E9E)   // 浅灰蓝（保留少量做"重复任务"等标签，整体不蓝）
    val Violet      = Color(0xFFA38FA0)   // 灰紫（取代紫罗兰，低饱和不刺眼）

    // ---- 边框（含 alpha） ----
    val BorderSoft   = Color(0x38C1603F)   // 赤陶透明
    val BorderMid    = Color(0x598E4424)
    val BorderStrong = Color(0x8C8E4424)
}

private val TGColorScheme = lightColorScheme(
    primary = TGColors.Gold,
    onPrimary = TGColors.Black,          // 金底用墨黑字（黑金招牌）
    primaryContainer = TGColors.Selected,
    onPrimaryContainer = TGColors.Ink,
    secondary = TGColors.Jade,
    onSecondary = Color.White,           // 玉青底用白字（习惯按钮）
    secondaryContainer = TGColors.Selected,
    onSecondaryContainer = TGColors.Ink,
    background = TGColors.BgPaper,
    onBackground = TGColors.Ink,
    surface = TGColors.Panel,
    onSurface = TGColors.Ink,
    surfaceVariant = TGColors.BgPaperDeep,
    onSurfaceVariant = TGColors.InkSoft,
    outline = TGColors.BorderMid,
    error = TGColors.Crimson,
    onError = Color.White
)

/** 顶部暖光渐变（对应桌面端 body::before） */
val TGBackgroundBrush = Brush.verticalGradient(
    listOf(
        Color(0xFFFAF0D8),
        Color(0xFFF5EFE0),
        Color(0xFFEFE1B8)
    )
)

@Composable
fun TaskGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TGColorScheme,
        typography = Typography().run {
            copy(
                bodyLarge = bodyLarge.copy(color = TGColors.Ink, fontWeight = FontWeight.Normal),
                bodyMedium = bodyMedium.copy(color = TGColors.Ink, fontWeight = FontWeight.Normal),
                titleLarge = titleLarge.copy(color = TGColors.Ink, fontWeight = FontWeight.SemiBold),
                titleMedium = titleMedium.copy(color = TGColors.Ink, fontWeight = FontWeight.SemiBold)
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(TGBackgroundBrush)
        ) { content() }
    }
}

// ==================== 通用组件 ====================

/**
 * 统一图标入口：用矢量 drawable + 显式 tint，
 * 避免 emoji / Unicode 符号在不同机型上被 emoji 字体接管成彩色块。
 */
@Composable
fun TGIcon(
    drawable: Int,
    contentDescription: String?,
    tint: Color = TGColors.InkSoft,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    Icon(
        painter = painterResource(id = drawable),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

/**
 * v5.15.22 M5（boss：「追踪任务后，取消追踪键应该有涟漪UI」）——
 * 与电脑端 `@keyframes rippleBreath` 同语义：追踪态下，朱砂红「取消追踪」键外圈
 * 持续扩散一圈细环（半径外扩 + 透明度衰减），1.9s 一轮循环。
 * 尺寸与 [PressIcon] 的 40dp 完全一致 → 换上来不会破坏 M6 的按键分列对齐。
 */
@Composable
fun TrackRippleKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp
) {
    val tr = rememberInfiniteTransition(label = "trackRipple")
    val phase by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripplePhase"
    )
    Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).drawBehind {
                // v5.15.22 M5b（boss：两层涟漪 = 靠内圈再加一层）——
                //   内圈：紧贴图标的一圈细环，**常驻呼吸**（明暗随外圈节奏变化，不会消失）；
                //   外圈：从图标向外扩散、变淡、消失，再从头来。
                val strokeInner = 1.8f.dp.toPx()
                val alphaInner = 0.62f - 0.30f * phase          // 0.62 → 0.32 循环呼吸
                drawCircle(
                    color = TGColors.Crimson.copy(alpha = alphaInner),
                    radius = 15f.dp.toPx(),
                    style = Stroke(width = strokeInner)
                )
                val p = phase                                    // 外圈扩散环
                drawCircle(
                    color = TGColors.Crimson.copy(alpha = (1f - p) * 0.42f),
                    radius = 16f.dp.toPx() + (11f.dp.toPx()) * p,  // 16dp → 27dp
                    style = Stroke(width = 1.2f.dp.toPx())
                )
            }
        )
        PressIcon(onClick = onClick) {
            TGIcon(
                drawable = R.drawable.ic_track_fill,
                contentDescription = "取消追踪",
                tint = TGColors.Crimson,
                size = iconSize
            )
        }
    }
}

/**
 * v5.15.22 M7（boss：「设置优先级时，点到『高』『中』『低』三个键颜色应该有所区分」）——
 * 选中态用该优先级自己的色填充（高=朱砂 / 中=深赤陶 / 低=灰），未选中是它的淡底描边。
 */
@Composable
fun PriorityFilterChip(priority: String, selected: Boolean, onClick: () -> Unit) {
    val (color, label) = when (priority) {
        Priority.HIGH -> TGColors.Crimson to "高"
        Priority.LOW -> TGColors.InkMute to "低"
        else -> TGColors.GoldDeep to "中"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.94f) else color.copy(alpha = 0.10f))
            .border(
                1.dp,
                if (selected) color else color.copy(alpha = 0.40f),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else color,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 卡片：白米底 + 淡金细边（对应桌面端 .card + --border-soft） */
@Composable
fun TGCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TGColors.Card)
            .border(1.dp, TGColors.BorderSoft, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content
    )
}

/** 优先级标签（高=朱砂 / 中=深金 / 低=灰） */
@Composable
fun PriorityChip(priority: String) {
    val (color, text) = when (priority) {
        Priority.HIGH -> TGColors.Crimson to "高"
        Priority.LOW -> TGColors.InkMute to "低"
        else -> TGColors.GoldDeep to "中"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** 任务类型标签 */
@Composable
fun TypeChip(type: String) {
    val (color, text) = when (type) {
        "goal" -> TGColors.Violet to "目标"
        "milestone" -> TGColors.Crimson to "里程碑"
        "habit" -> TGColors.Jade to "习惯"
        "repeat" -> TGColors.Azure to "重复"
        "note" -> TGColors.InkMute to "速记"
        else -> TGColors.GoldDeep to "单次"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** 空状态提示 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TGColors.InkMute, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/**
 * 奖励项（图标 + 数值）。
 * 注意：icon 传的是 drawable 资源 id（如 R.drawable.ic_coin），不是 emoji 字符串。
 */
@Composable
fun RewardItem(icon: Int, label: String, highlight: Boolean = false) {
    val tint = if (highlight) TGColors.GoldDeep else TGColors.InkSoft
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight) TGColors.Selected.copy(alpha = 0.6f)
                else TGColors.BgPaperDeep.copy(alpha = 0.55f)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        TGIcon(icon, contentDescription = null, tint = tint, size = 18.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 完成庆祝弹层（v5.0 精致化：去一刀999廉价感，用撞色横条+极简排版）
 * - 不再用刺眼金币+恭喜字样，改用"极简奖励块"：任务名小字 + 横线分隔 + 积分数字
 * - 升级用冰川蓝→赤陶撞色横条（柔和高亮而非刺眼金色）
 * - 弹出/关闭 280ms 平滑缩放，2.8s 自动消失
 */
/* 注：上面这段 KDoc 属于下方的 CompletionCelebration
   （v5.15.21 在它前面插入了 CompletionToast，故此处不再重复 @Composable）。 */
/** v5.15.21 P1（boss：次数任务完成后应该只弹出一个**不用点击**的积分获得提示）——
 *  次数任务每次都弹整屏庆祝会打断操作，这里给一个轻量提示条：
 *  无遮罩（不挡操作）、不需点击、约 1.6s 自动消失，位置贴底部操作区上方。 */
@Composable
fun CompletionToast(title: String, points: Int, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        kotlinx.coroutines.delay(1600)
        onDismiss()
    }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(200))
    val slide by animateFloatAsState(if (visible) 0f else 22f, tween(240))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(bottom = 130.dp)
                .graphicsLayer { alpha = a; translationY = slide }
                .clip(RoundedCornerShape(14.dp))
                .background(TGColors.Ink.copy(alpha = 0.94f))
                .border(1.dp, TGColors.Gold.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✓", color = TGColors.Jade, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, color = Color(0xFFF6F1E6), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("+$points 积分", color = TGColors.GoldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CompletionCelebration(
    title: String,
    points: Int,
    newLevel: Int?,
    newLevelName: String?,
    onDismiss: () -> Unit
) {
    val isLevelUp = newLevel != null && newLevelName != null

    // 入场动画：遮罩淡入 + 卡片缩放上浮
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        kotlinx.coroutines.delay(if (isLevelUp) 3200 else 2400)
        onDismiss()
    }
    val bgAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(220))
    val scale by animateFloatAsState(if (visible) 1f else 0.9f, tween(320))
    val slide by animateFloatAsState(if (visible) 0f else 18f, tween(320))

    // 光晕呼吸（用无限动画驱动外圈光晕的透明度）
    val halo = rememberInfiniteTransition(label = "halo")
    val haloAlpha by halo.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "haloAlpha"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B2430).copy(alpha = 0.62f * bgAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    alpha = bgAlpha
                    translationY = slide
                }
        ) {
            // ── 顶部徽记 ──
            // v5.15.18 L2（boss：「现在的页面不好看，改好看点，不用滥用主题色」）：
            //   原来是一整颗金色渐变球 + 很亮的金色光晕 → 金色到处都是。
            //   改成"米白圆 + 一圈极细金边 + 深金图标"，金色只做点缀，主体留白。
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer { this.alpha = haloAlpha * 0.45f }
                        .background(
                            Brush.radialGradient(
                                listOf(TGColors.Gold.copy(alpha = 0.22f), Color.Transparent)
                            ),
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFFFFFCF2))
                        .border(
                            1.5.dp,
                            TGColors.Gold.copy(alpha = 0.55f),
                            androidx.compose.foundation.shape.CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    TGIcon(
                        drawable = R.drawable.ic_coin,
                        contentDescription = null,
                        tint = TGColors.GoldDeep,
                        size = 28.dp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── 任务名：改成这张弹窗的**主角**（boss：「也要突出一下任务名字」） ──
            Text(
                title,
                color = Color(0xFFF5EFE2),
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                lineHeight = 29.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isLevelUp) "已完成 · 等级提升" else "已完成",
                color = TGColors.GoldLight.copy(alpha = 0.8f),
                fontSize = 11.sp,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(20.dp))

            // ── 积分卡：米白底 + 金色细描边 + 大数字 ──
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFFFFDF6), Color(0xFFF7EFDC)))
                    )
                    .border(1.dp, TGColors.Gold.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 36.dp, vertical = 16.dp)
            ) {
                Text(
                    "+",
                    color = TGColors.GoldDeep,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    "$points",
                    color = Color(0xFF1F1A10),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp
                )
                Text(
                    " 积分",
                    color = TGColors.InkMute,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 9.dp)
                )
            }

            // ── 升级横幅（仅升级时出现） ──
            // v5.15.18 L2：原来是大块金→橙渐变，同样属于"滥用主题色"。
            //   改成深墨底 + 细金边 + 米白字，克制且仍然显眼。
            if (isLevelUp) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2B2417))
                        .border(
                            1.dp,
                            TGColors.Gold.copy(alpha = 0.6f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text("Lv.$newLevel", color = TGColors.GoldLight, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(width = 1.dp, height = 13.dp).background(TGColors.Gold.copy(alpha = 0.4f)))
                    Spacer(Modifier.width(8.dp))
                    Text(newLevelName ?: "", color = Color(0xFFF0E8D8), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                }
            }
            // v5.15.21 M2（boss：获得积分界面太单薄，加点东西但不能太繁杂）——
            //   细金分隔线 + 一句短激励语，补"完成感"但不去抢任务名/积分的视觉主次。
            //   用 remember 固定随机值，避免重组时文字乱跳。
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .width(52.dp)
                    .height(1.dp)
                    .background(TGColors.Gold.copy(alpha = 0.32f))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                remember { listOf("又清掉一件，节奏不错", "稳扎稳打，继续保持", "这一下很值", "干净利落").random() },
                color = TGColors.GoldLight.copy(alpha = 0.8f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(14.dp))
            Text("点击任意位置关闭", color = Color(0xFFEDE4D3).copy(alpha = 0.5f), fontSize = 10.5.sp, letterSpacing = 1.sp)
        }
    }
}
