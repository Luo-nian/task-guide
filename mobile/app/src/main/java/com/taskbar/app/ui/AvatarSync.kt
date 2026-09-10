package com.taskbar.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * v5.15.10：头像工具
 *  - 桌面端自定义头像跨端同步的 base64 解码
 *  - 手机端上传图片 → 只取中间圆形区域 → 256x256 PNG → data URL（再推给电脑端）
 */
object AvatarUtil {

    /** data:image/png;base64,xxx → ImageBitmap（解析失败返回 null） */
    fun decodeBase64(raw: String?): ImageBitmap? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val pure = raw.substringAfter("base64,", raw).trim()
            if (pure.isEmpty()) return null
            val bytes = Base64.decode(pure, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            bmp.asImageBitmap()
        }.getOrNull()
    }

    /**
     * 相册 Uri → **中间方形**裁切 → 缩放 → **中间圆形**蒙版（圆外透明）→ 256x256 PNG → data URL。
     * 圆形外像素全透明，两端都直接显示成圆头像，不会出现方形边角。
     */
    fun uriToCircularDataUrl(ctx: android.content.Context, uri: Uri, outSize: Int = 256): String? {
        return runCatching {
            // 1) 读边界 + 降采样（防 OOM / 减少解码耗时）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > outSize * 2 && bounds.outHeight / sample > outSize * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src: Bitmap = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // 2) 中间方形（短边居中）
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            if (square !== src) src.recycle()

            // 3) 缩放 + 圆形蒙版
            val scaled = Bitmap.createScaledBitmap(square, outSize, outSize, true)
            if (scaled !== square) square.recycle()
            val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            val clip = android.graphics.Path().apply {
                addCircle(outSize / 2f, outSize / 2f, outSize / 2f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clip)
            canvas.drawBitmap(scaled, Rect(0, 0, outSize, outSize), RectF(0f, 0f, outSize.toFloat(), outSize.toFloat()), paint)
            scaled.recycle()

            // 4) PNG → base64 data URL
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            out.recycle()
            "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}

/**
 * v5.15.10：解码挪到后台线程。
 * 旧实现用 remember 在组合期直接 BitmapFactory 解码 —— 电脑端推来的大图会堵 UI 线程
 * （实测进入「我的」页首帧 90th = 300ms 就是这么来的）。现在首帧先返回 null，解码完再刷新。
 */
@Composable
fun rememberAvatarBitmap(raw: String?): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(initialValue = null, raw) {
        value = withContext(Dispatchers.Default) { AvatarUtil.decodeBase64(raw) }
    }
    return bmp
}
