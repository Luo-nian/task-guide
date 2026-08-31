package com.taskbar.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.taskbar.app.MainActivity
import com.taskbar.app.data.model.ReminderStrength

/**
 * 通知帮助类：三档提醒方式
 * - notify（通知栏弹窗）：顶部横幅 + 默认提示音
 * - vibrate（振动提醒）：自定义周期持续振动（settings reminder_vibrate_pattern）
 * - ring（响铃提醒）：播放自定义铃声（settings reminder_ring_uri）
 *
 * 未受理升级：通知发出 N 分钟后若任务仍未 done/track，则升级到更强通道（vibrate/ring）
 */
object NotificationHelper {

    const val CHANNEL_NOTIFY = "ch_notify"      // 通知栏弹窗
    const val CHANNEL_VIBRATE = "ch_vibrate"    // 振动
    const val CHANNEL_RING = "ch_ring"          // 响铃
    const val CHANNEL_SERVICE = "ch_service"    // 同步前台服务

    private const val DEFAULT_VIBRATE_PATTERN = "0,300,200,300,200,300"   // 短震三连
    private const val DEFAULT_RING_URI = "content://settings/system/notification_sound"  // 系统默认

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 1. 通知栏弹窗
        if (nm.getNotificationChannel(CHANNEL_NOTIFY) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_NOTIFY, "通知栏提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "到点任务顶部横幅提醒"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
        }
        // 2. 振动提醒（自定义振动 pattern + 无声）
        if (nm.getNotificationChannel(CHANNEL_VIBRATE) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_VIBRATE, "振动提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "按自定义周期持续振动"
                enableVibration(true)
                vibrationPattern = parseVibratePattern(getVibratePatternSetting(context))
                setSound(null, null)  // 振动档不应响铃
            })
        }
        // 3. 响铃提醒（自定义铃声 + 振动）
        if (nm.getNotificationChannel(CHANNEL_RING) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_RING, "响铃提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "播放自定义铃声 + 振动"
                enableVibration(true)
                vibrationPattern = parseVibratePattern(DEFAULT_VIBRATE_PATTERN)
                val ringUri = getRingUriSetting(context)
                if (ringUri != null) setSound(ringUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
        }
        // 4. 同步前台服务通道
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_SERVICE, "同步服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "任务指南同步服务运行中" })
        }
    }

    /** 解析振动 pattern 字符串 "0,300,200,300" → longArray */
    private fun parseVibratePattern(s: String?): LongArray {
        if (s.isNullOrBlank()) return longArrayOf(0, 300, 200, 300, 200, 300)
        return try {
            s.split(",").map { it.trim().toLong() }.toLongArray().takeIf { it.isNotEmpty() }
                ?: longArrayOf(0, 300, 200, 300, 200, 300)
        } catch (_: Exception) {
            longArrayOf(0, 300, 200, 300, 200, 300)
        }
    }

    private fun getVibratePatternSetting(context: Context): String {
        val prefs = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        return prefs.getString("reminder_vibrate_pattern", DEFAULT_VIBRATE_PATTERN) ?: DEFAULT_VIBRATE_PATTERN
    }

    private fun getRingUriSetting(context: Context): Uri? {
        val prefs = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        val s = prefs.getString("reminder_ring_uri", null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    /** 根据 strength 选 channel，并返回是否设置完整（全屏/锁屏可见） */
    private fun channelFor(strength: String): Pair<String, Boolean> {
        return when (ReminderStrength.migrateLegacy(strength)) {
            ReminderStrength.NOTIFY -> CHANNEL_NOTIFY to false
            ReminderStrength.VIBRATE -> CHANNEL_VIBRATE to true
            ReminderStrength.RING -> CHANNEL_RING to true
            else -> CHANNEL_NOTIFY to false
        }
    }

    fun showReminder(
        context: Context,
        taskUuid: String,
        title: String,
        strength: String
    ) {
        ensureChannels(context)
        val (channel, withFullScreen) = channelFor(strength)

        val openIntent = PendingIntent.getActivity(
            context, taskUuid.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra("task_uuid", taskUuid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = PendingIntent.getBroadcast(
            context, taskUuid.hashCode() + 1,
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DONE
                putExtra("task_uuid", taskUuid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val delayIntent = PendingIntent.getBroadcast(
            context, taskUuid.hashCode() + 2,
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DELAY
                putExtra("task_uuid", taskUuid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("到点了，去做吧")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "完成", doneIntent)
            .addAction(0, "延迟1天", delayIntent)
            .setPriority(if (withFullScreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)

        if (withFullScreen) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(openIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(taskUuid.hashCode(), builder.build())
    }

    /** 前台服务常驻通知 */
    fun buildServiceNotification(context: Context, text: String = "同步服务运行中") =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("任务指南")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
}