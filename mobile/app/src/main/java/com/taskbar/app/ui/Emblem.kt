package com.taskbar.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
// ⚠️ `Modifier.size(Dp)` 是 foundation.layout 的扩展函数，**必须单独 import**，
//    只 import Modifier 是不够的（本版编译真踩到：报 Unresolved reference: size）
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============ v5.15.29 L2/L4 · 等级徽章（第八版定案 · 十枚语义实物 · 无外壳） ============
 *
 * 数据与电脑端 `desktop/ui/app3.js` 的 `LEVEL_EMBLEM` **逐字一致**（同一份 SVG 片段，
 * 占位符 `{M}{D}{H}{G}{S}{O}` 也相同），保证双端图形绝不漂移 ——
 * 要改某枚徽章，两端同时改这一处字符串即可。
 *
 * 这里自带一个**迷你 SVG 渲染器**（只支持本项目用到的子集）：
 *   `<path d>`（M/L/Q/A/Z）、`<circle>`、`<ellipse>`（可 rotate）、`<rect>`（可圆角）、
 *   `<g transform="translate(x,y) rotate(d)">`，以及 fill / stroke / stroke-width /
 *   opacity / stroke-linecap / stroke-dasharray 六个属性。
 * 坐标系与 SVG 相同：原点在中心，可视范围 -30 ~ +30（桌面端 viewBox="-30 -30 60 60"）。
 */

// 调色板（与桌面端 EMBLEM_PAL 一致）
private val EM_M = Color(0xFFE8CB7F)   // 主金
private val EM_D = Color(0xFFB8892B)   // 暗金
private val EM_H = Color(0xFFF7E7BB)   // 高光
private val EM_G = Color(0xFFC9A227)   // 金
private val EM_S = Color(0xFFD9DEE6)   // 银（水/冰/云系专用）
private val EM_O = Color(0xFF0A0A0D)   // 描边（近黑）

private val EMBLEM: Map<Int, String> = mapOf(
    // 1 历练学徒 · 嫩芽破土
    1 to """
        <path d="M-20,20 Q0,13 20,20 L20,26 L-20,26 Z" fill="{D}" stroke="{O}" stroke-width="1"/>
        <path d="M0,21 Q-2,10 0,-2" fill="none" stroke="{D}" stroke-width="4.2" stroke-linecap="round"/>
        <path d="M0,-2 Q-15,-9 -19,-1 Q-10,4 0,3 Z" fill="{M}" stroke="{O}" stroke-width="1.1"/>
        <path d="M0,-5 Q13,-14 19,-5 Q10,1 0,1 Z" fill="{M}" stroke="{O}" stroke-width="1.1"/>
        <path d="M-1,-2 Q-11,-7 -16,-3" fill="none" stroke="{D}" stroke-width="1" opacity=".8"/>
        <path d="M1,-5 Q10,-11 16,-6" fill="none" stroke="{D}" stroke-width="1" opacity=".8"/>
    """.trimIndent(),
    // 2 风华游侠 · 弓与箭
    2 to """
        <path d="M6,-21 Q-18,0 6,21 Q-11,0 6,-21 Z" fill="{M}" stroke="{O}" stroke-width="1.1"/>
        <path d="M6,-21 L6,21" fill="none" stroke="{H}" stroke-width="1.6"/>
        <path d="M-16,0 L17,0" fill="none" stroke="{M}" stroke-width="3.4" stroke-linecap="round"/>
        <path d="M17,-5.5 L27,0 L17,5.5 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>
        <path d="M-16,-1 L-25,-7.5 L-11,-3.4 Z" fill="{D}"/>
        <path d="M-16,1 L-25,7.5 L-11,3.4 Z" fill="{D}"/>
    """.trimIndent(),
    // 3 破浪骑士 · 盾与波浪（浪用银系）
    3 to """
        <path d="M0,-23 L16,-17 L16,1 Q16,16 0,25 Q-16,16 -16,1 L-16,-17 Z" fill="{M}" stroke="{O}" stroke-width="1.3"/>
        <path d="M0,-23 L0,25" stroke="{D}" stroke-width="1.3" opacity=".7"/>
        <path d="M-11,0 Q-5.5,-6 0,0 Q5.5,6 11,0" fill="none" stroke="{S}" stroke-width="3" stroke-linecap="round"/>
        <path d="M-11,9 Q-5.5,3 0,9 Q5.5,15 11,9" fill="none" stroke="{S}" stroke-width="3" stroke-linecap="round" opacity=".8"/>
    """.trimIndent(),
    // 4 群星行者 · 交叉星轨 + 四芒星
    4 to """
        <path d="M-27,15 Q0,-17 27,15" fill="none" stroke="{M}" stroke-width="2.6" stroke-linecap="round"/>
        <path d="M-27,-15 Q0,17 27,-15" fill="none" stroke="{M}" stroke-width="2.6" stroke-linecap="round" opacity=".7"/>
        <circle cx="-18" cy="1" r="2.4" fill="{H}"/>
        <circle cx="18" cy="1" r="2.4" fill="{H}"/>
        <path d="M0,-17 L4.8,-4.8 L17,0 L4.8,4.8 L0,17 L-4.8,4.8 L-17,0 L-4.8,-4.8 Z" fill="{H}" stroke="{O}" stroke-width="1.1"/>
    """.trimIndent(),
    // 5 传奇勇者 · 交叉双剑
    5 to """
        <g transform="translate(-9,11) rotate(33)">
            <path d="M0,-24 L4.4,-14 L4.4,3 L-4.4,3 L-4.4,-14 Z" fill="{M}" stroke="{O}" stroke-width="1"/>
            <rect x="-11" y="3" width="22" height="4.4" rx="2.2" fill="{G}" stroke="{O}" stroke-width=".8"/>
            <rect x="-2.6" y="7.4" width="5.2" height="8.4" rx="2" fill="{D}" stroke="{O}" stroke-width=".7"/>
            <circle cx="0" cy="17.4" r="3.2" fill="{H}" stroke="{O}" stroke-width=".8"/>
        </g>
        <g transform="translate(9,11) rotate(-33)">
            <path d="M0,-24 L4.4,-14 L4.4,3 L-4.4,3 L-4.4,-14 Z" fill="{M}" stroke="{O}" stroke-width="1"/>
            <rect x="-11" y="3" width="22" height="4.4" rx="2.2" fill="{G}" stroke="{O}" stroke-width=".8"/>
            <rect x="-2.6" y="7.4" width="5.2" height="8.4" rx="2" fill="{D}" stroke="{O}" stroke-width=".7"/>
            <circle cx="0" cy="17.4" r="3.2" fill="{H}" stroke="{O}" stroke-width=".8"/>
        </g>
        <path d="M0,-4 L4,2 L0,8 L-4,2 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>
    """.trimIndent(),
    // 6 苍穹守护者 · 双翼之盾
    6 to """
        <path d="M-5,3 Q-14,-5 -26,-3 Q-18,1 -11,6 Q-19,4 -27,8 Q-19,10 -9,12 Q-19,14 -25,19 Q-15,16 -6,13 Z" fill="{M}" stroke="{O}" stroke-width="1"/>
        <path d="M5,3 Q14,-5 26,-3 Q18,1 11,6 Q19,4 27,8 Q19,10 9,12 Q19,14 25,19 Q15,16 6,13 Z" fill="{M}" stroke="{O}" stroke-width="1"/>
        <path d="M0,-24 L16,-18 L16,0 Q16,15 0,23 Q-16,15 -16,0 L-16,-18 Z" fill="{G}" stroke="{O}" stroke-width="1.3"/>
        <path d="M0,-18 L10,-14 L10,0 Q10,10 0,16 Q-10,10 -10,0 L-10,-14 Z" fill="none" stroke="{H}" stroke-width="1.2" opacity=".55"/>
        <path d="M0,-9 L6,0 L0,9 L-6,0 Z" fill="{H}" stroke="{O}" stroke-width="1"/>
    """.trimIndent(),
    // 7 深渊征服者 · 漩涡 + 三叉戟
    7 to """
        <path d="M0,-2 A2,2 0 0 1 0,2 A6,6 0 0 1 0,-6 A10,10 0 0 1 0,10 A14,14 0 0 1 0,-14" fill="none" stroke="{M}" stroke-width="3.6" stroke-linecap="round" opacity=".92"/>
        <rect x="-2.6" y="-9" width="5.2" height="34" rx="2.4" fill="{M}" stroke="{O}" stroke-width=".9"/>
        <path d="M0,-26 L3.6,-18 L0,-10 L-3.6,-18 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>
        <path d="M-12,-20 Q-12,-12 -3,-9 M12,-20 Q12,-12 3,-9" fill="none" stroke="{M}" stroke-width="3.4" stroke-linecap="round"/>
        <circle cx="0" cy="26" r="3.4" fill="{H}" stroke="{O}" stroke-width=".8"/>
    """.trimIndent(),
    // 8 星辰霸主 · 权杖 + 八芒星 + 双伴星
    8 to """
        <path d="M0,-25 L5.4,-15 L0,-5 L-5.4,-15 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>
        <path d="M-16,-15 L-5,-15 M16,-15 L5,-15" stroke="{H}" stroke-width="2.4" stroke-linecap="round"/>
        <rect x="-5" y="-8.5" width="10" height="5" rx="2.4" fill="{G}" stroke="{O}" stroke-width=".9"/>
        <path d="M0,-3.5 L0,22" stroke="{M}" stroke-width="4.6" stroke-linecap="round"/>
        <path d="M0,-3.5 L0,22" stroke="{H}" stroke-width="1.5" opacity=".55"/>
        <circle cx="0" cy="26" r="4.2" fill="{D}" stroke="{O}" stroke-width="1"/>
        <path d="M-23,-23 L-19,-19 L-23,-15 L-27,-19 Z" fill="{H}"/>
        <path d="M23,-23 L27,-19 L23,-15 L19,-19 Z" fill="{H}"/>
    """.trimIndent(),
    // 9 天命传奇 · 命轮 + 六芒星
    9 to """
        <circle r="25" fill="none" stroke="{M}" stroke-width="2.6"/>
        <circle r="20" fill="none" stroke="{M}" stroke-width="3" stroke-dasharray="1.4 6.3" opacity=".9"/>
        <path d="M0,-16 L4.3,-6 L15,-8 L7.5,0 L15,8 L4.3,6 L0,16 L-4.3,6 L-15,8 L-7.5,0 L-15,-8 L-4.3,-6 Z" fill="{H}" stroke="{O}" stroke-width="1"/>
        <circle r="3" fill="{G}" stroke="{O}" stroke-width=".8"/>
    """.trimIndent(),
    // 10 寰宇传说 · 中心恒星 + 三条轨道 + 卫星
    10 to """
        <ellipse rx="26" ry="10" fill="none" stroke="{M}" stroke-width="2.2"/>
        <ellipse rx="26" ry="10" fill="none" stroke="{M}" stroke-width="2.2" opacity=".72" transform="rotate(60)"/>
        <ellipse rx="26" ry="10" fill="none" stroke="{M}" stroke-width="2.2" opacity=".5" transform="rotate(120)"/>
        <circle cx="26" cy="0" r="3.4" fill="{H}" stroke="{O}" stroke-width=".9"/>
        <circle cx="-13" cy="22.5" r="2.8" fill="{G}" stroke="{O}" stroke-width=".9"/>
        <circle cx="13" cy="-22.5" r="2.4" fill="{G}" stroke="{O}" stroke-width=".9"/>
        <circle r="12" fill="{H}" opacity=".2"/>
        <circle r="8" fill="#FBEECB" stroke="{G}" stroke-width="1.2"/>
    """.trimIndent()
)

/** 入场特效分档（与电脑端 emblemFxClass 完全一致；一次性，播完即止） */
private fun emblemFx(lv: Int): Int = when {
    lv <= 3 -> 0   // rise 自下升起
    lv <= 6 -> 1   // grow 由小长大
    lv <= 8 -> 2   // spin 旋入
    else -> 3      // pop  弹出带过冲
}
private fun emblemFxMs(lv: Int): Int = when (emblemFx(lv)) {
    0 -> 900
    1 -> 1050
    2 -> 950
    else -> 1100
}

/**
 * 等级徽章。**无外壳** —— 只有徽章本体，直接落在卡片/卡片上。
 * @param lv 等级 1~10
 * @param size 边长（徽章按 -30~+30 的正方形区域缩放填充）
 * @param animated 是否播一次性入场特效（列表里重复出现时可关掉）
 */
@Composable
fun LevelEmblem(
    lv: Int,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    animated: Boolean = true
) {
    val n = lv.coerceIn(1, 10)
    val svg = remember(n) { (EMBLEM[n] ?: EMBLEM.getValue(1)).replacePlaceholders() }

    val anim = remember(n) { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(n, animated) {
        if (animated) {
            anim.snapTo(0f)
            anim.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = emblemFxMs(n),
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                )
            )
        }
    }
    val p = anim.value

    // 四档参数：alpha / scale / rotationZ / translationY
    //   （用 var + when 赋值而非 val，避免 Kotlin 的 definite-assignment 限制）
    val fx = emblemFx(n)
    var alpha = 1f
    var scl = 1f
    var rot = 0f
    var dy = 0f
    when (fx) {
        0 -> { // rise
            alpha = min(1f, p * 1.6f); scl = 1f; rot = 0f; dy = (1f - p) * 9f
        }
        1 -> { // grow
            alpha = min(1f, p * 1.6f); scl = 0.62f + 0.38f * p; rot = 0f; dy = 0f
        }
        2 -> { // spin
            alpha = min(1f, p * 2.2f); scl = 0.8f + 0.2f * p; rot = -135f * (1f - p); dy = 0f
        }
        else -> { // pop（过冲到 1.12 再回落）
            alpha = min(1f, p * 2.6f)
            val u = p * 1.12f
            scl = if (p < 0.68f) 0.55f + (u - 0.55f) * (p / 0.68f)
            else 1.12f - 0.12f * ((p - 0.68f) / 0.32f)
            rot = 0f; dy = 0f
        }
    }

    Canvas(
        modifier
            .size(size)
            // 用**参数式** graphicsLayer（不是 lambda 版）——lambda 版里 receiver 属性
            // 会和同名局部变量打架（本版编译真踩到：Variable expected / Unresolved scaleX）
            .graphicsLayer(
                scaleX = scl,
                scaleY = scl,
                alpha = alpha,
                rotationZ = rot,
                translationY = dy
            )
    ) {
        // ⚠️ 必须写 `this.size`（DrawScope 的属性）—— 本函数的参数也叫 `size`（Dp），
        //    直接写 `size` 会被参数**遮蔽**，编译器解析成 Dp → 报 Unresolved width/height（真踩到）
        // 本坐标系与 SVG 相同：原点在中心、可视范围 -30 ~ +30
        //   → 先把原点平移到画布中心，再按"边长/60"整体缩放
        val drawSize = this.size
        val w = drawSize.width
        val h = drawSize.height
        val k = drawSize.minDimension / 60f
        withTransform({
            translate(w / 2f, h / 2f)
            scale(k, k, Offset.Zero)
        }) {
            drawSvgFragment(svg)
        }
    }
}

// ============================ 迷你 SVG 渲染器（内部） ============================

private fun String.replacePlaceholders(): String {
    var s = this
    s = s.replace("{M}", "#E8CB7F").replace("{D}", "#B8892B").replace("{H}", "#F7E7BB")
    s = s.replace("{G}", "#C9A227").replace("{S}", "#D9DEE6").replace("{O}", "#0A0A0D")
    return s
}

private fun attrOf(tag: String, name: String): String? {
    val m = Regex("""(?<![-\w])""" + Regex.escape(name) + """="([^"]*)"""").find(tag) ?: return null
    return m.groupValues[1]
}

private fun colOf(v: String?): Color? {
    if (v == null || v == "none" || v.isEmpty()) return null
    if (!v.startsWith("#")) return null
    return try {
        Color(android.graphics.Color.parseColor(v))
    } catch (e: Exception) {
        null
    }
}

private class Xf(val tx: Float, val ty: Float, val rot: Float)

private fun parseTransform(s: String?): Xf {
    var tx = 0f; var ty = 0f; var rot = 0f
    if (s == null) return Xf(0f, 0f, 0f)
    Regex("""translate\(\s*([-\d.]+)[\s,]+([-\d.]+)\s*\)""").find(s)?.let {
        tx = it.groupValues[1].toFloatOrNull() ?: 0f
        ty = it.groupValues[2].toFloatOrNull() ?: 0f
    }
    Regex("""rotate\(\s*([-\d.]+)\s*\)""").find(s)?.let {
        rot = it.groupValues[1].toFloatOrNull() ?: 0f
    }
    return Xf(tx, ty, rot)
}

private fun DrawScope.drawSvgFragment(svg: String) {
    var i = 0
    while (i < svg.length) {
        val lt = svg.indexOf('<', i)
        if (lt < 0) return
        val gt = svg.indexOf('>', lt)
        if (gt < 0) return
        val tag = svg.substring(lt + 1, gt).trim()
        i = gt + 1
        when {
            tag.startsWith("g ") || tag.startsWith("g\"") -> {
                val close = svg.indexOf("</g>", i)
                if (close < 0) return
                val inner = svg.substring(i, close)
                i = close + 4
                val xf = parseTransform(attrOf(tag, "transform"))
                withTransform({
                    translate(xf.tx, xf.ty)
                    rotate(xf.rot, Offset.Zero)
                }) { drawSvgFragment(inner) }
            }
            tag.startsWith("path") -> drawPathEl(tag)
            tag.startsWith("circle") -> drawCircleEl(tag)
            tag.startsWith("ellipse") -> drawEllipseEl(tag)
            tag.startsWith("rect") -> drawRectEl(tag)
        }
    }
}

private fun strokeStyleOf(tag: String, sw: Float): Stroke? {
    if (sw <= 0f) return null
    val cap = if (attrOf(tag, "stroke-linecap") == "round") StrokeCap.Round else StrokeCap.Butt
    val dash = attrOf(tag, "stroke-dasharray")
    val pe = if (dash != null) {
        val parts = Regex("""[-\d.]+""").findAll(dash).mapNotNull { it.value.toFloatOrNull() }.toList()
        if (parts.size >= 2) PathEffect.dashPathEffect(floatArrayOf(parts[0], parts[1]), 0f) else null
    } else null
    return Stroke(width = sw, cap = cap, pathEffect = pe)
}

private fun DrawScope.opacityOf(tag: String): Float =
    attrOf(tag, "opacity")?.toFloatOrNull() ?: 1f

private fun DrawScope.drawPathEl(tag: String) {
    val d = attrOf(tag, "d") ?: return
    val path = buildSvgPath(d)
    val fill = colOf(attrOf(tag, "fill"))
    val stroke = colOf(attrOf(tag, "stroke"))
    val sw = attrOf(tag, "stroke-width")?.toFloatOrNull() ?: 0f
    val op = opacityOf(tag)
    if (fill != null) drawPath(path, fill.copy(alpha = fill.alpha * op))
    if (stroke != null) {
        val st = strokeStyleOf(tag, sw)
        if (st != null) drawPath(path, stroke.copy(alpha = stroke.alpha * op), style = st)
    }
}

private fun DrawScope.drawCircleEl(tag: String) {
    val r = attrOf(tag, "r")?.toFloatOrNull() ?: return
    val cx = attrOf(tag, "cx")?.toFloatOrNull() ?: 0f
    val cy = attrOf(tag, "cy")?.toFloatOrNull() ?: 0f
    val fill = colOf(attrOf(tag, "fill"))
    val stroke = colOf(attrOf(tag, "stroke"))
    val sw = attrOf(tag, "stroke-width")?.toFloatOrNull() ?: 0f
    val op = opacityOf(tag)
    val c = Offset(cx, cy)
    if (fill != null) drawCircle(fill.copy(alpha = fill.alpha * op), r, c)
    if (stroke != null) {
        val st = strokeStyleOf(tag, sw)
        if (st != null) drawCircle(stroke.copy(alpha = stroke.alpha * op), r, c, style = st)
    }
}

private fun DrawScope.drawEllipseEl(tag: String) {
    val rx = attrOf(tag, "rx")?.toFloatOrNull() ?: return
    val ry = attrOf(tag, "ry")?.toFloatOrNull() ?: return
    val cx = attrOf(tag, "cx")?.toFloatOrNull() ?: 0f
    val cy = attrOf(tag, "cy")?.toFloatOrNull() ?: 0f
    val rot = parseTransform(attrOf(tag, "transform")).rot
    val fill = colOf(attrOf(tag, "fill"))
    val stroke = colOf(attrOf(tag, "stroke"))
    val sw = attrOf(tag, "stroke-width")?.toFloatOrNull() ?: 0f
    val op = opacityOf(tag)
    withTransform({ rotate(rot, Offset.Zero) }) {
        val tl = Offset(cx - rx, cy - ry)
        val sz = Size(rx * 2f, ry * 2f)
        if (fill != null) drawOval(fill.copy(alpha = fill.alpha * op), tl, sz)
        if (stroke != null) {
            val st = strokeStyleOf(tag, sw)
            if (st != null) drawOval(stroke.copy(alpha = stroke.alpha * op), tl, sz, style = st)
        }
    }
}

private fun DrawScope.drawRectEl(tag: String) {
    val x = attrOf(tag, "x")?.toFloatOrNull() ?: 0f
    val y = attrOf(tag, "y")?.toFloatOrNull() ?: 0f
    val w = attrOf(tag, "width")?.toFloatOrNull() ?: return
    val h = attrOf(tag, "height")?.toFloatOrNull() ?: return
    val rr = attrOf(tag, "rx")?.toFloatOrNull() ?: 0f
    val fill = colOf(attrOf(tag, "fill"))
    val stroke = colOf(attrOf(tag, "stroke"))
    val sw = attrOf(tag, "stroke-width")?.toFloatOrNull() ?: 0f
    val op = opacityOf(tag)
    val tl = Offset(x, y)
    val sz = Size(w, h)
    if (fill != null) {
        if (rr > 0f) drawRoundRect(fill.copy(alpha = fill.alpha * op), tl, sz, CornerRadius(rr, rr))
        else drawRect(fill.copy(alpha = fill.alpha * op), tl, sz)
    }
    if (stroke != null) {
        val st = strokeStyleOf(tag, sw)
        if (st != null) {
            if (rr > 0f) drawRoundRect(stroke.copy(alpha = stroke.alpha * op), tl, sz, CornerRadius(rr, rr), style = st)
            else drawRect(stroke.copy(alpha = stroke.alpha * op), tl, sz, style = st)
        }
    }
}

/** SVG path 子集 → Compose Path（本项目的徽章只用到 M/L/Q/A/Z 且都是绝对坐标） */
private fun buildSvgPath(d: String): Path {
    val path = Path()
    var cx = 0f
    var cy = 0f
    for (m in Regex("""([MLQCZAmlqcza])([^MLQCZAmlqcza]*)""").findAll(d)) {
        val cmd = m.groupValues[1]
        val nums = Regex("""-?\d*\.?\d+""").findAll(m.groupValues[2])
            .mapNotNull { it.value.toFloatOrNull() }.toList()
        when (cmd) {
            "M", "L" -> {
                var k = 0
                while (k + 1 < nums.size) {
                    if (cmd == "M" && k == 0) path.moveTo(nums[k], nums[k + 1])
                    else path.lineTo(nums[k], nums[k + 1])
                    cx = nums[k]; cy = nums[k + 1]
                    k += 2
                }
            }
            "Q" -> {
                var k = 0
                while (k + 3 < nums.size) {
                    path.quadraticBezierTo(nums[k], nums[k + 1], nums[k + 2], nums[k + 3])
                    cx = nums[k + 2]; cy = nums[k + 3]
                    k += 4
                }
            }
            "Z", "z" -> path.close()
            "A" -> {
                var k = 0
                while (k + 6 < nums.size) {
                    val rx = nums[k]
                    val ry = nums[k + 1]
                    val largeArc = nums[k + 3] != 0f
                    val sweep = nums[k + 4] != 0f
                    val x = nums[k + 5]
                    val y = nums[k + 6]
                    arcInto(path, cx, cy, rx, ry, largeArc, sweep, x, y)
                    cx = x; cy = y
                    k += 7
                }
            }
        }
    }
    return path
}

/**
 * SVG 的 A 命令 → Compose arcTo。
 * 本项目所有弧都是正圆（rx == ry）且 x-axis-rotation = 0，按 SVG 规范 F.6.5 求圆心。
 */
private fun arcInto(
    path: Path,
    x0: Float, y0: Float,
    rx0: Float, ry0: Float,
    largeArc: Boolean, sweep: Boolean,
    x1: Float, y1: Float
) {
    val dx = x1 - x0
    val dy = y1 - y0
    val len = hypot(dx, dy)
    if (len < 1e-4f) return
    var r = min(rx0, ry0)
    val half = len / 2f
    if (r < half) r = half               // 半径不足时按规范放大到刚好够
    if (r <= 0f) { path.lineTo(x1, y1); return }
    val mx = (x0 + x1) / 2f
    val my = (y0 + y1) / 2f
    val h = sqrt(max(0f, r * r - half * half))
    val px = -dy / len                   // 垂直单位向量
    val py = dx / len
    val sign = if (largeArc == sweep) -1f else 1f
    val ccx = mx + sign * h * px
    val ccy = my + sign * h * py
    val a0 = atan2(y0 - ccy, x0 - ccx)
    val a1 = atan2(y1 - ccy, x1 - ccx)
    var delta = a1 - a0
    val twoPi = (2.0 * PI).toFloat()
    if (sweep) {
        while (delta < 0f) delta += twoPi
        while (delta > twoPi) delta -= twoPi
    } else {
        while (delta > 0f) delta -= twoPi
        while (delta < -twoPi) delta += twoPi
    }
    if (abs(delta) < 1e-5f) return
    val rect = Rect(ccx - r, ccy - r, ccx + r, ccy + r)
    path.arcTo(rect, Math.toDegrees(a0.toDouble()).toFloat(), Math.toDegrees(delta.toDouble()).toFloat(), false)
}
