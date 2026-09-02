package com.taskbar.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ==================== 配色（v5.0 撞色方案：冰川蓝主 + 赤陶高亮 + 紫罗兰辅助，告别黑金） ====================
// 设计思路：暖色米底 + 冷色主调（冰川蓝）+ 暖色高亮（赤陶橙）+ 神秘紫辅助，三色撞色
// 旧黑金方案（v4.9 之前）已在 git 历史 b17503e 留档，可一键回退
object TGColors {
    // ---- 背景（米色暖底） ----
    val BgPaper     = Color(0xFFF4EFE3)   // 主背景 米色（略带冷调）
    val BgPaperDeep = Color(0xFFE8DFCD)   // 深一档米色
    val Panel       = Color(0xFFFBF6EA)   // 面板
    val PanelSolid  = Color(0xFFF8F2E2)   // 面板实色
    val Card        = Color(0xFFFFFCF0)   // 卡片白米色
    val Bar         = Color(0xFFE8DDC2)   // 任务条
    val BarHover    = Color(0xFFE4D5AF)   // 任务条按下
    val Selected    = Color(0xFFDDD0B0)   // 选中

    // ---- 文字（冷色深蓝灰，告别深褐） ----
    val Ink         = Color(0xFF1F2A37)   // 主文字 深海岩（冷色字在暖底上）
    val InkSoft     = Color(0xFF4A5765)   // 副文字
    val InkMute     = Color(0xFF8895A3)   // 三级文字
    val InkFaint    = Color(0xFFB8C0C8)   // 四级/分隔

    // ---- 招牌位（深色板/卡：用深海岩作墨黑替代，告别 #16120C 纯黑） ----
    val Black       = Color(0xFF1A2530)   // 招牌位深底（深海岩，比纯黑柔和）
    val BlackSoft   = Color(0xFF2A3A4A)   // 深蓝灰

    // ---- 冰川蓝（主色，取代金色作"点缀+描边+主调"） ----
    val Gold        = Color(0xFF4A8FB5)   // 主色 冰川蓝（图标/描边/主调）
    val GoldLight   = Color(0xFF7CB8D9)   // 浅冰川蓝 填充底/高亮
    val GoldDeep    = Color(0xFF2E6A8E)   // 深冰川蓝 浅底上的可读蓝字

    // ---- 赤陶（强调色，取代金色"廉价"感——冷蓝+暖橙撞色） ----
    val Orange      = Color(0xFFD4795A)   // 赤陶（强调/重要提示）
    val OrangeLight = Color(0xFFE8A87C)   // 浅赤陶 桃黏土

    // ---- 装饰色（微调：去掉游戏页游感，加深一档更稳重） ----
    val Jade        = Color(0xFF5B9274)   // 玉青 完成（更沉稳的绿）
    val Crimson     = Color(0xFFB85638)   // 朱砂 紧急/高优先级
    val Azure       = Color(0xFF4F86B5)   // 冰川蓝旧名（保留兼容）
    val Violet      = Color(0xFF9B7FB5)   // 紫罗兰 Dusty Purple（取代"游戏紫"）

    // ---- 边框（含 alpha） ----
    val BorderSoft   = Color(0x384A8FB5)   // 冰川蓝透明
    val BorderMid    = Color(0x592E6A8E)
    val BorderStrong = Color(0x8C2E6A8E)
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
private val TGBackgroundBrush = Brush.verticalGradient(
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
@Composable
fun CompletionCelebration(
    title: String,
    points: Int,
    newLevel: Int?,
    newLevelName: String?,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        kotlinx.coroutines.delay(2800)
        onDismiss()
    }
    val scale by animateFloatAsState(if (visible) 1f else 0.92f, tween(280))
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(280))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1822).copy(alpha = 0.55f * alpha))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = scale; scaleY = scale; this.alpha = alpha
            }
        ) {
            // 任务名小字（柔和米色）
            Text(
                title,
                color = Color(0xFFEFE8DC).copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(20.dp))
            // 奖励块：白米底 + 冰川蓝描边 + 极简排版
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))   // 圆角小一点更现代
                    .background(Color(0xFFF6F0E1))
                    .border(1.dp, TGColors.GoldLight, RoundedCornerShape(4.dp))
                    .padding(horizontal = 40.dp, vertical = 26.dp)
            ) {
                // 极简横线 + "获得"小字
                Text(
                    "获  得",
                    color = TGColors.InkMute,
                    fontSize = 11.sp,
                    letterSpacing = 4.sp
                )
                Spacer(Modifier.height(10.dp))
                // 大数字 + 积分（冰川蓝，深海岩作高对比）
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "+",
                        color = TGColors.GoldDeep,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "$points",
                        color = TGColors.Black,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
                    )
                }
                Text(
                    "积分",
                    color = TGColors.InkMute,
                    fontSize = 11.sp,
                    letterSpacing = 3.sp
                )
            }
            // 升级横幅（冰川蓝→赤陶撞色横条——柔和高亮）
            if (newLevel != null && newLevelName != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))   // 几乎无圆角=横条感
                        .background(Brush.horizontalGradient(listOf(TGColors.Gold, TGColors.Orange)))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("Lv.$newLevel", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(width = 1.dp, height = 12.dp)
                            .background(Color.White.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        newLevelName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
