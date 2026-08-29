package com.taskbar.app.ui

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
import com.taskbar.app.data.model.Priority
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.Task

// ==================== 配色（原神任务界面风格：磨砂黑 + 米白 + 暖金 + 紫菱） ====================
object TGColors {
    val Ink = Color(0xFF161B24)          // 深灰黑磨砂底
    val InkLight = Color(0xFF1E2736)     // 深靛蓝（面板/任务条）
    val InkLighter = Color(0xFF2A3246)
    val Gold = Color(0xFFE0C06E)         // 暖金
    val GoldLight = Color(0xFFF0D98C)    // 浅金高亮（主标题）
    val OrangeWarm = Color(0xFFE8A15C)   // 暖橘黄（副文本/距离）
    val Purple = Color(0xFFA78BFA)       // 紫（委托/任务标识）
    val PurpleDeep = Color(0xFF7C5CD6)
    val Cinnabar = Color(0xFFC0392B)     // 朱砂（高优先级/警告）
    val Jade = Color(0xFF3E9C7E)         // 玉青（完成）
    val Paper = Color(0xFFF5F0E0)        // 米白（主文）
    val Fog = Color(0xFFA6AEBF)          // 浅灰辅助
    val CardBg = Color(0xCC1E2736)       // 半透明任务条
    val CardBorder = Color(0x59E0C06E)   // 淡金边
    val SelectedBg = Color(0xEBF5F0E0)   // 选中：米白高亮
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

/** 奖励项（图1奖励区：图标+数量） */
@Composable
fun RewardItem(icon: String, label: String, highlight: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight) TGColors.Gold.copy(alpha = 0.12f)
                else Color.White.copy(alpha = 0.04f)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (highlight) TGColors.GoldLight else TGColors.Paper, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
