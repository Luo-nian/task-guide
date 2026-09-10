package com.taskbar.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * v5.15.12：头像「手动选裁剪范围」界面（boss：手机端也应该选择裁切圆形的范围，而不是自动裁切）
 *
 * 交互：双指缩放 / 单指拖动 → 圆形取景框内即最终头像 → 点「使用」生成 256×256 圆形 PNG（data URL）
 * 说明：圆形外像素全透明，两端都直接显示成圆形头像，不会出现方形边角。
 */
/** 全屏 Dialog 包装（usePlatformDefaultWidth=false 才能铺满） */
@Composable
fun AvatarCropDialog(uri: Uri, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AvatarCropOverlay(uri = uri, onCancel = onCancel, onConfirm = onConfirm)
    }
}

@Composable
fun AvatarCropOverlay(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val ctx = LocalContext.current
    // 后台解码（限制最大边 1280，避免大图卡 UI）
    val srcBmp by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.Default) { decodeDownscaled(ctx, uri, 1280) }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    val img: ImageBitmap? = remember(srcBmp) { srcBmp?.asImageBitmap() }

    LaunchedEffect(srcBmp) { scale = 1f; offset = Offset.Zero }

    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("调整头像", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("拖动图片、双指缩放，圆圈内就是最终头像", color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF120E06))
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = Offset(offset.x + pan.x, offset.y + pan.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (img == null) {
                    Text("正在载入…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                } else {
                    ComposeCanvas(Modifier.fillMaxSize()) {
                        val d = min(size.width, size.height)
                        val w = img.width.toFloat()
                        val h = img.height.toFloat()
                        val base = max(d / w, d / h)          // cover 基准缩放
                        val s = base * scale
                        val cx = size.width / 2f + offset.x
                        val cy = size.height / 2f + offset.y
                        drawIntoCanvas { c ->
                            val native = c.nativeCanvas
                            native.save()
                            native.translate(cx, cy)
                            native.scale(s, s)
                            native.translate(-w / 2f, -h / 2f)
                            native.drawBitmap(srcBmp!!, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                            native.restore()
                        }
                        // 取景圆：圆外压暗 + 白色圆环
                        val r = d * 0.44f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val mask = Paint().apply { color = android.graphics.Color.argb(140, 0, 0, 0) }
                        drawIntoCanvas { c ->
                            val native = c.nativeCanvas
                            native.save()
                            val full = android.graphics.Path().apply {
                                addRect(0f, 0f, size.width, size.height, android.graphics.Path.Direction.CW)
                                addCircle(center.x, center.y, r, android.graphics.Path.Direction.CCW)
                            }
                            native.drawPath(full, mask)
                            native.restore()
                        }
                        drawCircle(
                            color = Color(0xFFE8CB7F),
                            radius = r,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A332A))
                ) { Text("取消", color = Color.White) }
                Button(
                    onClick = {
                        val b = srcBmp ?: return@Button
                        if (boxSize.width == 0) return@Button
                        busy = true
                        val dataUrl = cropToDataUrl(b, boxSize.width, boxSize.height, scale, offset)
                        busy = false
                        if (dataUrl != null) onConfirm(dataUrl)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TGColors.Gold)
                ) { Text(if (busy) "处理中…" else "使用", color = TGColors.Ink) }
            }
        }
    }
}

/** Uri → 后台降采样解码（最大边不超过 maxSide） */
private fun decodeDownscaled(ctx: android.content.Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 && bounds.outHeight / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

/**
 * 按界面上的「圆形取景框 + 用户缩放/平移」计算源图区域，生成 256×256 圆形 PNG data URL。
 * boxW/boxH = 裁剪框显示尺寸（px）；k = 用户缩放；off = 用户平移（显示坐标）
 */
private fun cropToDataUrl(src: Bitmap, boxW: Int, boxH: Int, k: Float, off: Offset, outSize: Int = 256): String? =
    runCatching {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val d = min(boxW.toFloat(), boxH.toFloat())
        val base = max(d / w, d / h)
        val s = base * k
        // 取景圆（显示坐标）：圆心 = 框中心，半径 = d * 0.44
        val rDisp = d * 0.44f
        // 显示中心 → 源图坐标
        val srcCx = w / 2f - off.x / s
        val srcCy = h / 2f - off.y / s
        val rSrc = rDisp / s
        if (rSrc <= 1f) return null

        val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val half = outSize / 2f
        val clip = android.graphics.Path().apply {
            addCircle(half, half, half, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clip)
        val scale = half / rSrc
        canvas.save()
        canvas.translate(half, half)
        canvas.scale(scale, scale)
        canvas.translate(-srcCx, -srcCy)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()

        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, bos)
        out.recycle()
        "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

/** 圆形头像预览（我的页用） */
@Composable
fun AvatarCirclePreview(bitmap: ImageBitmap, sizeDp: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = "头像",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(sizeDp.dp).clip(CircleShape).border(2.dp, TGColors.Gold, CircleShape)
    )
}
