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
    // v5.15.23 M2（boss：「按下的弹跳反馈现在弹的太快了，反馈性不强」）——
    //   100ms/0.84 → 180ms/0.86：给一点"按下去"的过程感，不再是瞬闪。
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    )
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
    // v5.15.23 M2：同 PressIcon —— 180ms/0.86，按下过程更清晰（boss 说"日历"键按下去弹太快）
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    )
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
/** 一套调色板（键名与 TGColors 的属性一一对应）。v5.22.2 新增夜间版。 */
internal data class TgPalette(
    val BgPaper: Color, val BgPaperDeep: Color, val Panel: Color, val PanelSolid: Color,
    val Card: Color, val Bar: Color, val BarHover: Color, val Selected: Color,
    val Ink: Color, val InkSoft: Color, val InkMute: Color, val InkFaint: Color,
    val Black: Color, val BlackSoft: Color,
    val Gold: Color, val GoldLight: Color, val GoldDeep: Color,
    val Orange: Color, val OrangeLight: Color,
    val Jade: Color, val JadeDeep: Color, val Crimson: Color, val Azure: Color, val Violet: Color,
    val BorderSoft: Color, val BorderMid: Color, val BorderStrong: Color
)

internal val TgLightPalette = TgPalette(
    BgPaper = Color(0xFFF5F0E8), BgPaperDeep = Color(0xFFEAE2D2),
    Panel = Color(0xFFFCF8EE), PanelSolid = Color(0xFFF9F2E2), Card = Color(0xFFFFFCF3),
    Bar = Color(0xFFE8DDC2), BarHover = Color(0xFFE4D5AF), Selected = Color(0xFFE6D9BC),
    Ink = Color(0xFF3B2A20), InkSoft = Color(0xFF6B574A), InkMute = Color(0xFF9C8B7A), InkFaint = Color(0xFFC2B59E),
    Black = Color(0xFF3B2A20), BlackSoft = Color(0xFF5A463A),
    Gold = Color(0xFFC1603F), GoldLight = Color(0xFFE8A87C), GoldDeep = Color(0xFF8E4424),
    Orange = Color(0xFFD4795A), OrangeLight = Color(0xFFE8A87C),
    Jade = Color(0xFF7D9B76), JadeDeep = Color(0xFF3F6B4A), Crimson = Color(0xFFB85638),
    Azure = Color(0xFF6B8E9E), Violet = Color(0xFFA38FA0),
    BorderSoft = Color(0x38C1603F), BorderMid = Color(0x598E4424), BorderStrong = Color(0x8C8E4424)
)

/** 夜间调色板：**暖深咖底色**（不是纯黑，和白天同一套"纸+赤陶"气质），
 *  文字用米白；赤陶提亮到 #E0714A 保证深底上可读；边框反过来用"亮赤陶 + alpha"。 */
internal val TgNightPalette = TgPalette(
    BgPaper = Color(0xFF17120E), BgPaperDeep = Color(0xFF100C09),
    Panel = Color(0xFF1F1813), PanelSolid = Color(0xFF241C16), Card = Color(0xFF261E18),
    Bar = Color(0xFF2E241C), BarHover = Color(0xFF392D23), Selected = Color(0xFF3B2D22),
    Ink = Color(0xFFF2E9DC), InkSoft = Color(0xFFCDBBA7), InkMute = Color(0xFF9A8674), InkFaint = Color(0xFF6E5D4D),
    Black = Color(0xFF100C09), BlackSoft = Color(0xFF2A1F17),
    Gold = Color(0xFFE0714A), GoldLight = Color(0xFFF2A87E), GoldDeep = Color(0xFFEDA47C),
    Orange = Color(0xFFE8836A), OrangeLight = Color(0xFFF2A87E),
    Jade = Color(0xFF93B98A), JadeDeep = Color(0xFF8FBE88), Crimson = Color(0xFFE0704C),
    Azure = Color(0xFF93B2C0), Violet = Color(0xFFBCA7B9),
    BorderSoft = Color(0x3DE08A63), BorderMid = Color(0x5CE08A63), BorderStrong = Color(0x8CE08A63)
)

// ---- 外观模式（light / night / auto）与"当前是否夜间"两个全局状态 ----
private var tgThemeModeState = mutableStateOf("light")
private val tgIsNightState = mutableStateOf(false)

/** 外观：`light` 白天 ｜ `night` 夜间 ｜ `auto` 跟随系统（与电脑端同一套选项） */
object TgAppearance {
    const val LIGHT = "light"
    const val NIGHT = "night"
    const val AUTO = "auto"
    private const val PREF = "taskguide_prefs"
    private const val KEY = "ui_theme"

    val mode: String get() = tgThemeModeState.value

    /** 进 UI 之前调用一次，恢复上次选的外观 */
    fun load(ctx: Context) {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, LIGHT) ?: LIGHT
        tgThemeModeState.value = if (v == NIGHT || v == AUTO) v else LIGHT
    }

    fun set(ctx: Context, mode: String) {
        if (mode != LIGHT && mode != NIGHT && mode != AUTO) return
        tgThemeModeState.value = mode
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }
}

/**
 * 全局调色板入口。**属性全部改成 getter 读当前调色板** ——
 * 这样切外观时，所有读过 TGColors.X 的界面都会自动重组，引用处无需改动。
 */
object TGColors {
    private val p: TgPalette get() = if (tgIsNightState.value) TgNightPalette else TgLightPalette

    // ---- 背景 ----
    val BgPaper get() = p.BgPaper            // 主背景
    val BgPaperDeep get() = p.BgPaperDeep    // 深一档
    val Panel get() = p.Panel                // 面板
    val PanelSolid get() = p.PanelSolid      // 面板实色
    val Card get() = p.Card                  // 卡片
    val Bar get() = p.Bar                    // 任务条
    val BarHover get() = p.BarHover          // 任务条按下
    val Selected get() = p.Selected          // 选中

    // ---- 文字 ----
    val Ink get() = p.Ink                    // 主文字
    val InkSoft get() = p.InkSoft            // 副文字
    val InkMute get() = p.InkMute            // 三级
    val InkFaint get() = p.InkFaint          // 四级/分隔

    // ---- 招牌位深底 ----
    val Black get() = p.Black
    val BlackSoft get() = p.BlackSoft

    // ---- 主色：赤陶 ----
    val Gold get() = p.Gold
    val GoldLight get() = p.GoldLight
    val GoldDeep get() = p.GoldDeep

    // ---- 强调色 ----
    val Orange get() = p.Orange
    val OrangeLight get() = p.OrangeLight

    // ---- 装饰色 ----
    val Jade get() = p.Jade
    val JadeDeep get() = p.JadeDeep
    val Crimson get() = p.Crimson
    val Azure get() = p.Azure
    val Violet get() = p.Violet

    // ---- 边框 ----
    val BorderSoft get() = p.BorderSoft
    val BorderMid get() = p.BorderMid
    val BorderStrong get() = p.BorderStrong
}

private val TGColorSchemeLight = lightColorScheme(
    primary = TgLightPalette.Gold,
    onPrimary = TgLightPalette.Black,
    primaryContainer = TgLightPalette.Selected,
    onPrimaryContainer = TgLightPalette.Ink,
    secondary = TgLightPalette.Jade,
    onSecondary = Color.White,
    background = TgLightPalette.BgPaper,
    onBackground = TgLightPalette.Ink,
    surface = TgLightPalette.Card,
    onSurface = TgLightPalette.Ink,
    surfaceVariant = TgLightPalette.Panel,
    onSurfaceVariant = TgLightPalette.InkSoft,
    outline = TgLightPalette.BorderMid,
    error = TgLightPalette.Crimson,
    onError = Color.White
)

/** v5.22.2 夜间配色：暖深咖底 + 米白字 + 提亮赤陶；按钮上是**深字**（赤陶偏亮） */
private val TGColorSchemeNight = darkColorScheme(
    primary = TgNightPalette.Gold,
    onPrimary = Color(0xFF1A120D),
    primaryContainer = TgNightPalette.Selected,
    onPrimaryContainer = TgNightPalette.Ink,
    secondary = TgNightPalette.Jade,
    onSecondary = Color(0xFF14200F),
    background = TgNightPalette.BgPaper,
    onBackground = TgNightPalette.Ink,
    surface = TgNightPalette.Card,
    onSurface = TgNightPalette.Ink,
    surfaceVariant = TgNightPalette.Panel,
    onSurfaceVariant = TgNightPalette.InkSoft,
    outline = TgNightPalette.BorderMid,
    error = TgNightPalette.Crimson,
    onError = Color(0xFF1A120D)
)

private val TGColorScheme: ColorScheme
    get() = if (tgIsNightState.value) TGColorSchemeNight else TGColorSchemeLight

/** 顶部背景渐变（对应桌面端 body::before）—— v5.22.2 起随外观切换 */
val TGBackgroundBrush: Brush
    get() = if (tgIsNightState.value) {
        Brush.verticalGradient(listOf(Color(0xFF221A14), Color(0xFF1B1410), Color(0xFF150F0B)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFAF0D8), Color(0xFFF5EFE0), Color(0xFFEFE1B8)))
    }

/**
 * v5.22.3（boss：「4 句激励语留不留 —— 留 甚至要更多，记住不要有ai味 可以参考名人名言」）——
 * 完成/升级弹层底部那句短激励语。取舍标准：
 *   · **短**（弹层空间小，超过 12 字就挤）
 *   · **不装**：不写"让…有成就感""相信你可以的"这类话（那是 AI 味）
 *   · 一半是平实口语（自己写的），一半是**公有领域的古典名句**（出处确定，不瞎挂名字）
 */
internal val CELEBRATE_LINES = listOf(
    // —— 平实口语 ——
    "又清掉一件",
    "这一下很值",
    "干净利落",
    "先做五分钟，果然有用",
    "不用等状态，做了就有",
    "今天的份额到手",
    "完成比完美容易",
    "起步最难，已经过了",
    "一件一件来，就快了",
    "记下来，就不用一直惦记",
    "做过的事，不会白做",
    "明天会轻松一点",
    // —— 古典名句（出处均可考） ——
    "不积跬步，无以至千里",      // 荀子·劝学
    "千里之行，始于足下",        // 老子·道德经
    "锲而不舍，金石可镂",        // 荀子·劝学
    "业精于勤，荒于嬉",          // 韩愈·进学解
    "欲穷千里目，更上一层楼",    // 王之涣·登鹳雀楼
    "天行健，君子以自强不息",    // 周易·乾卦
)

@Composable
fun TaskGuideTheme(content: @Composable () -> Unit) {
    // v5.22.2（boss：「手机端也要切换外观啊 夜间模式加进来」）——
    //   在进 MaterialTheme 之前先把"当前是否夜间"定下来，这样下面的 content 读到的
    //   TGColors 就已经是切好的那套；写入前判一下避免重组死循环。
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val night = when (tgThemeModeState.value) {
        TgAppearance.NIGHT -> true
        TgAppearance.AUTO -> sysDark
        else -> false
    }
    if (tgIsNightState.value != night) tgIsNightState.value = night
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
    // 外圈：扩散环 0→1 循环（Restart 是涟漪本身的设计 —— 两端 alpha 都是 0，重启无突跳）
    val phase by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripplePhase"
    )
    Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).drawBehind {
                // v5.15.23 M5（boss：「涟漪应该更内圈一点，要的是直接在按键上荡出来的波纹涟漪效果，
                //   现在更像在按键外的一圈」）—— 把两圈都收进 40dp 按键圆内（按钮半径 20dp）：
                //   内圈 12.5dp 常驻呼吸（贴着图标），外圈 14→19.5dp 在按钮面上向外荡开再消失。
                // v5.15.26 M5（boss：「那个小菱形的 菱形涟漪不太明显 只要一个涟漪就行，
                //   不用太明显 但是现在的不像是涟漪」）——
                //   去掉原来那个**常驻的内圈呼吸环**（它更像"一圈静止不动的线"，所以"不像涟漪"），
                //   只留**一圈**从图标处荡开、边扩散边变细变淡的环 —— 这才是涟漪本身。
                val p = phase
                drawCircle(
                    color = TGColors.Azure.copy(alpha = (1f - p) * 0.50f),
                    radius = 12.5f.dp.toPx() + (7f.dp.toPx()) * p,        // 12.5dp → 19.5dp（不出按键）
                    style = Stroke(width = (2.0f - 0.9f * p).dp.toPx())   // 边荡边变细
                )
            }
        )
        PressIcon(onClick = onClick) {
            TGIcon(
                drawable = R.drawable.ic_track_fill,
                contentDescription = "取消追踪",
                tint = TGColors.Azure,
                size = iconSize
            )
        }
    }
}

/**
 * v5.15.22 M7（boss：「设置优先级时，点到『高』『中』『低』三个键颜色应该有所区分」）——
 * 选中态用该优先级自己的色填充（高=朱砂 / 中=深赤陶 / 低=灰），未选中是它的淡底描边。
 * v5.15.23 M12：高与中的色相拉开（朱砂红 vs 琥珀金），未选中态也加深边框提高辨识度。
 */
@Composable
fun PriorityFilterChip(priority: String, selected: Boolean, onClick: () -> Unit) {
    val (color, label) = when (priority) {
        Priority.HIGH -> PRIORITY_COLOR_HIGH to "高"
        Priority.LOW -> TGColors.InkMute to "低"
        else -> PRIORITY_COLOR_MED to "中"
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

/**
 * v5.15.23 M11（boss：长按任务多选，右上三键=取消/恢复/全选，滑动可连续多选）——
 * 多选状态容器：列表页与日历页共用。
 */
@Stable
class MultiSelectState {
    var selecting by mutableStateOf(false)
        private set
    val ids = mutableStateListOf<String>()

    fun begin(uuid: String) {
        selecting = true
        if (!ids.contains(uuid)) ids.add(uuid)
    }
    fun toggle(uuid: String) {
        if (ids.contains(uuid)) ids.remove(uuid) else ids.add(uuid)
    }
    fun isSelected(uuid: String): Boolean = ids.contains(uuid)
    fun selectAll(all: List<String>) { ids.clear(); ids.addAll(all) }
    fun clearAll() { ids.clear() }
    fun exit() { ids.clear(); selecting = false }
}

/** 选中态的小勾标（右上角），未选中显示空心圈 */
@Composable
fun SelectBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected) TGColors.Jade else Color.White.copy(alpha = 0.9f))
            .border(
                1.5.dp,
                if (selected) TGColors.Jade else TGColors.InkFaint,
                androidx.compose.foundation.shape.CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 多选模式下右上角的三个键：[取消（删除）] [恢复] [全选/取消全选]（最右为全选）。
 * 两个动作键都会先弹二次确认（确认/取消）。
 */
@Composable
fun MultiSelectBar(
    state: MultiSelectState,
    allIds: List<String>,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    showCount: Boolean = true
) {
    val allSelected = allIds.isNotEmpty() && state.ids.size >= allIds.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        // v5.15.23 M11b：计数可以搬到标题位（顶栏窄，避免按钮被挤成两行）
        if (showCount) {
            Text(
                "已选 ${state.ids.size}",
                color = TGColors.InkSoft, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(end = 5.dp)
            )
        }
        // 取消（= 删除选中任务）
        PressPill(onClick = { if (state.ids.isNotEmpty()) onDelete() }) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TGColors.Crimson.copy(alpha = if (state.ids.isEmpty()) 0.08f else 0.14f))
                    .border(1.dp, TGColors.Crimson.copy(alpha = if (state.ids.isEmpty()) 0.25f else 0.5f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("取消", color = if (state.ids.isEmpty()) TGColors.InkFaint else TGColors.Crimson,
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.width(6.dp))
        // 恢复
        PressPill(onClick = { if (state.ids.isNotEmpty()) onRestore() }) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TGColors.Jade.copy(alpha = if (state.ids.isEmpty()) 0.08f else 0.16f))
                    .border(1.dp, TGColors.Jade.copy(alpha = if (state.ids.isEmpty()) 0.25f else 0.55f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text("恢复", color = if (state.ids.isEmpty()) TGColors.InkFaint else TGColors.Jade,
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.width(6.dp))
        // 最右上角：全选 / 取消全选
        PressPill(onClick = { if (allSelected) state.clearAll() else state.selectAll(allIds) }) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TGColors.Gold.copy(alpha = 0.16f))
                    .border(1.dp, TGColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    if (allSelected) "取消全选" else "全选",
                    color = TGColors.GoldDeep, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        PressIcon(onClick = { state.exit() }) {
            TGIcon(R.drawable.ic_close, contentDescription = "退出多选", tint = TGColors.InkSoft, size = 20.dp)
        }
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

/** 优先级标签（高=朱砂红 / 中=琥珀金 / 低=灰）
 *  v5.15.23 M12（boss：「优先级 高和中的颜色区别不够大」）——
 *  原来高(Crimson 赤陶红)与中(GoldDeep 深赤陶)同色系，肉眼几乎分不出；
 *  改成明度/色相拉开：高=深朱砂红、中=琥珀金。 */
@Composable
fun PriorityChip(priority: String) {
    val (color, text) = when (priority) {
        Priority.HIGH -> PRIORITY_COLOR_HIGH to "高"
        Priority.LOW -> TGColors.InkMute to "低"
        else -> PRIORITY_COLOR_MED to "中"
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

/** v5.15.23 M12：优先级三档专用色（高=深朱砂红、中=琥珀金、低=灰）——显示标签与编辑键共用 */
val PRIORITY_COLOR_HIGH = Color(0xFFB0361F)
val PRIORITY_COLOR_MED = Color(0xFFC08A28)

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
                // v5.22.5：完成提示的底色固定为深褐（夜间 TGColors.Ink 是米白 → 白字压米白不可读）
                .background(Color(0xFF3B2A20).copy(alpha = 0.94f))
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
                remember { CELEBRATE_LINES.random() },
                color = TGColors.GoldLight.copy(alpha = 0.8f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            // v5.21.x：删掉「点击任意位置关闭」—— 弹层 2.4s 自动消失，点任意处也能关，
            //   这句既不是必需信息、又要占一行版面（boss：「很多文字提示没必要」）。
        }
    }
}
