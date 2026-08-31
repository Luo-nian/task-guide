package com.taskbar.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskbar.app.data.model.Priority

// ==================== 全局 Toast（防抖 + 单例覆盖，杜绝连点刷屏/截断） ====================
object ToastHelper {
    private var lastMsg: String = ""
    private var lastAt: Long = 0
    private var toast: Toast? = null

    /** 同一内容 2.5s 内只提示一次；新提示会顶掉旧提示（防排队堆积） */
    fun show(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) {
        val now = System.currentTimeMillis()
        if (msg == lastMsg && now - lastAt < 2500) return
        lastMsg = msg
        lastAt = now
        toast?.cancel()
        toast = Toast.makeText(ctx.applicationContext, msg, duration).apply { show() }
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

// ==================== 配色（对齐桌面端 v4.6：暖色浅色 · 米底 + 深褐字 + 金线 + 玉青） ====================
// 色值与 desktop/ui/style.css 的 :root 变量一一对应，改配色时两边一起改
object TGColors {
    // ---- 背景 ----
    val BgPaper     = Color(0xFFF5EFE0)   // 主背景 米色（--bg-paper）
    val BgPaperDeep = Color(0xFFEDE3CC)   // 深一档米色（--bg-paper-deep）
    val Panel       = Color(0xFFFCF7EB)   // 面板（--panel 的实色近似）
    val PanelSolid  = Color(0xFFFAF5E6)   // 面板实色（--panel-solid）
    val Card        = Color(0xFFFFFBEF)   // 卡片白米色（--card）
    val Bar         = Color(0xFFEDE2C8)   // 任务条（--bar 的实色近似）
    val BarHover    = Color(0xFFE4D5AF)   // 任务条按下（--bar-hover）
    val Selected    = Color(0xFFF4E6B9)   // 选中（--selected）

    // ---- 文字 ----
    val Ink         = Color(0xFF3A2E1A)   // 主文字 深褐（--ink）
    val InkSoft     = Color(0xFF6B5D3E)   // 副文字（--ink-soft）
    val InkMute     = Color(0xFF9C8B6A)   // 三级文字（--ink-mute）
    val InkFaint    = Color(0xFFC2B58E)   // 四级/分隔（--ink-faint）

    // ---- 装饰色 ----
    val Gold        = Color(0xFFC9A227)   // 主金：图标/描边（--gold）
    val GoldLight   = Color(0xFFE6C77A)   // 浅金：填充底（--gold-light）
    val GoldDeep    = Color(0xFF9A7B1A)   // 深金：浅底上的可读金文字（--gold-deep）
    val Orange      = Color(0xFFD88A3F)   // 暖橘（--orange）
    val Jade        = Color(0xFF4A8B6F)   // 玉青 完成（--jade）
    val Crimson     = Color(0xFFB85638)   // 朱砂 紧急/高优先级（--crimson）
    val Azure       = Color(0xFF4F86B5)   // 蓝 追踪/重复（--azure）
    val Violet      = Color(0xFF8A6BC9)   // 紫 涟漪/目标（--violet）

    // ---- 边框（含 alpha，对应 --border-soft / mid / strong）----
    val BorderSoft   = Color(0x38C9A227)
    val BorderMid    = Color(0x599A7B1A)
    val BorderStrong = Color(0x8C9A7B1A)
}

private val TGColorScheme = lightColorScheme(
    primary = TGColors.Gold,
    onPrimary = Color.White,
    primaryContainer = TGColors.Selected,
    onPrimaryContainer = TGColors.Ink,
    secondary = TGColors.Jade,
    onSecondary = Color.White,
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
