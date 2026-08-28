package com.taskguide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskguide.app.data.model.Priority
import com.taskguide.app.data.model.Step
import com.taskguide.app.data.model.Task

// ==================== 配色（毛玻璃 + 淡金，参考原神任务追踪） ====================
object TGColors {
    val Ink = Color(0xFF1F2430)          // 墨黑底
    val InkLight = Color(0xFF2A3040)
    val InkLighter = Color(0xFF3A4456)
    val Gold = Color(0xFFC9A227)         // 鎏金
    val GoldLight = Color(0xFFE8D9A0)    // 淡金
    val Cinnabar = Color(0xFFC0392B)     // 朱砂（高优先级/警告）
    val Jade = Color(0xFF2E8B7A)         // 玉青（完成）
    val Paper = Color(0xFFF5EFE0)        // 宣纸
    val Fog = Color(0xFF8A93A6)          // 雾灰
    val CardBg = Color(0xCC2A3040)       // 半透明卡片底（毛玻璃感）
    val CardBorder = Color(0x55C9A227)   // 淡金边
}

private val TGColorScheme = darkColorScheme(
    primary = TGColors.GoldLight,
    onPrimary = TGColors.Ink,
    secondary = TGColors.Gold,
    background = TGColors.Ink,
    onBackground = TGColors.Paper,
    surface = TGColors.InkLight,
    onSurface = TGColors.Paper,
    surfaceVariant = TGColors.InkLighter,
    onSurfaceVariant = TGColors.Fog,
    error = TGColors.Cinnabar
)

@Composable
fun TaskGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TGColorScheme,
        typography = Typography().run {
            copy(
                bodyLarge = bodyLarge.copy(fontWeight = FontWeight.Normal),
                bodyMedium = bodyMedium.copy(fontWeight = FontWeight.Normal),
                titleLarge = titleLarge.copy(color = TGColors.GoldLight, fontWeight = FontWeight.SemiBold),
                titleMedium = titleMedium.copy(color = TGColors.GoldLight, fontWeight = FontWeight.SemiBold)
            )
        },
        content = content
    )
}

// ==================== 通用组件 ====================

/** 毛玻璃卡片背景（半透明 + 淡金细边） */
@Composable
fun TGCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TGColors.CardBg)
            .padding(14.dp)
    ) {
        // 边框感：用细线（Compose 无 border 简单写法，用 Box 叠加省略，靠背景区分）
        content()
    }
}

/** 优先级标签（高=朱砂 / 中=鎏金 / 低=雾灰） */
@Composable
fun PriorityChip(priority: String) {
    val (color, text) = when (priority) {
        Priority.HIGH -> TGColors.Cinnabar to "高"
        Priority.LOW -> TGColors.Fog to "低"
        else -> TGColors.Gold to "中"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** 任务类型标签 */
@Composable
fun TypeChip(type: String) {
    val (color, text) = when (type) {
        "goal" -> TGColors.GoldLight to "目标"
        "habit" -> TGColors.Jade to "习惯"
        "repeat" -> TGColors.Fog to "重复"
        "note" -> TGColors.Fog to "速记"
        else -> TGColors.Paper to "单次"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** 空状态提示 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TGColors.Fog, fontSize = 14.sp)
    }
}
