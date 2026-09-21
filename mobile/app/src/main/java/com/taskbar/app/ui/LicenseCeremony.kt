package com.taskbar.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** 遮罩：暖深咖啡（不用蓝黑，跟暖米白纸的底色是一家人） */
private val CeremonyScrim = Color(0xFF241610)

/** 弹层正文字色：米白（与「完成庆祝」同色系） */
private val CeremonyCream = Color(0xFFF7F1E6)

/**
 * v5.21.3：点亮完整版后的仪式弹层（由 SettingsScreen 迁出，boss：「重做一下，搞好看点」）。
 *
 * 沿用「完成庆祝」（[CompletionCelebration]）那套语言：**深底遮罩 + 米白圆 + 一圈极细赤陶边 +
 * 深色徽记 + 呼吸光晕**；主体留白，赤陶只做点缀。
 *
 * 徽记极度克制：一枚米白圆盘、一圈极细赤陶边、中央一枚赤陶菱形（点亮时自下而上被注满）。
 * 不堆齿边 / 外环 / 扩散环等装饰——重点是"点亮了"这件事本身，别的都退让。
 *
 * ⚠️ 必须用 Dialog 承载：本函数在调用点处于设置页的 `Column(verticalScroll)` 内，
 *   直接用 `Modifier.fillMaxSize()` 拿到的是"内容高度"而非屏幕高度 → 遮罩铺不满屏、
 *   会退化成夹在两张设置卡之间的行内卡片。别改回普通 Box。
 */
@Composable
fun LicenseLitOverlay(info: com.taskbar.app.billing.License.Info, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val scrim by animateFloatAsState(if (visible) 1f else 0f, tween(280), label = "litScrim")
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "litScale"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CeremonyScrim.copy(alpha = 0.92f * scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 34.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = scrim
                    }
            ) {
                CeremonySeal(lit = visible)

                Spacer(Modifier.height(28.dp))
                Text(
                    "完整版",
                    color = TGColors.GoldLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 6.sp
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "已点亮",
                    color = CeremonyCream,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp
                )

                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .width(44.dp)
                        .height(1.dp)
                        .background(TGColors.Gold.copy(alpha = 0.35f))
                )
                Spacer(Modifier.height(18.dp))

                // 只给"对授权方真有用"的信息，不表态、不承诺、不道谢
                Text(
                    "永久有效，不用联网，换设备也能用。",
                    color = CeremonyCream.copy(alpha = 0.86f),
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    "有问题或想要的功能，发邮件 2845661076@qq.com。",
                    color = CeremonyCream.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))
                Text(
                    "授权给 ${info.to}",
                    color = TGColors.GoldLight.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
                if (info.order.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "订单号 ${info.order}",
                        color = CeremonyCream.copy(alpha = 0.36f),
                        fontSize = 10.5.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(Modifier.height(30.dp))
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TGColors.Gold,
                        contentColor = Color(0xFF2A170D)
                    ),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 10.dp)
                ) {
                    Text("好的", fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
                }
            }
        }
    }
}

/**
 * 徽记：米白圆盘 + 唯一一圈极细赤陶边 + 中央赤陶菱形（点亮时自下而上被注满）。
 * 呼吸光晕只动透明度（家族语言里"让圆盘浮起来"的那束光），不掉帧；不堆任何额外装饰。
 */
@Composable
private fun CeremonySeal(lit: Boolean) {
    val halo = rememberInfiniteTransition(label = "litSeal")
    val haloAlpha by halo.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.54f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "litHalo"
    )

    // 菱形：自下而上"注满"的进度
    val fill = remember { Animatable(0f) }
    LaunchedEffect(lit) {
        if (!lit) return@LaunchedEffect
        delay(220)
        fill.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
    }

    Canvas(Modifier.size(140.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val diskR = 56.dp.toPx()
        val gold = TGColors.Gold

        // 呼吸光晕（半径不动，只动透明度 → 不掉帧）
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(gold.copy(alpha = haloAlpha * 0.40f), Color.Transparent),
                center = c,
                radius = 72.dp.toPx()
            ),
            radius = 72.dp.toPx(),
            center = c
        )

        // 米白圆盘
        drawCircle(color = TGColors.Card, radius = diskR, center = c)
        // 唯一一圈极细赤陶边
        drawCircle(
            color = gold.copy(alpha = 0.5f),
            radius = diskR,
            center = c,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 中央菱形：点亮时自下而上被注满
        val r = 26.dp.toPx()
        val diamond = Path().apply {
            moveTo(c.x, c.y - r)
            lineTo(c.x + r, c.y)
            lineTo(c.x, c.y + r)
            lineTo(c.x - r, c.y)
            close()
        }
        if (fill.value > 0f) {
            clipRect(top = c.y + r - 2f * r * fill.value) { drawPath(diamond, gold) }
        }
        drawPath(diamond, color = gold.copy(alpha = 0.85f), style = Stroke(width = 1.6.dp.toPx()))
    }
}
