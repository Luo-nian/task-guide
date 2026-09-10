package com.taskbar.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * v5.15.7：桌面端自定义头像跨端同步
 *
 * 桌面端在个人信息页裁剪上传的头像以 `data:image/png;base64,xxxx` 形式存在 settings.avatar_img，
 * 通过 settings 同步通道推到手机。这里把它解码成 Compose 能画的 ImageBitmap。
 * 解析失败（空串 / 非图片 / 半截数据）返回 null，调用方回退到 emoji 或内置图标。
 */
fun decodeAvatarBase64(raw: String?): ImageBitmap? {
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

/** 按原始字符串缓存解码结果（同一张图不会每帧重复解码） */
@Composable
fun rememberAvatarBitmap(raw: String?): ImageBitmap? = remember(raw) { decodeAvatarBase64(raw) }
