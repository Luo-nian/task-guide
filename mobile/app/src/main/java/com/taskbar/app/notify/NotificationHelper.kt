package com.taskbar.app.notify

import android.app.Notification
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
import com.taskbar.app.R
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
    const val CHANNEL_BEEP = "ch_beep"          // 提示音
    const val CHANNEL_RING = "ch_ring"          // 响铃
    /**
     * 追踪中任务的常驻通知通道（前台服务用）。
     * v5.18.0 前这条通道叫"同步服务"、常驻文案是"同步服务运行中" ——
     * boss 明确不要看到它：现在通知只承载**追踪中的任务**，没有追踪就不显示通知。
     */
    const val CHANNEL_SERVICE = "ch_service"

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
        // 3. 提示音（系统默认通知音，不振动）
        if (nm.getNotificationChannel(CHANNEL_BEEP) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_BEEP, "提示音提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "播放系统默认提示音"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
        }
        // 4. 响铃提醒（自定义铃声 + 振动）
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
        // 5. 追踪中任务的常驻通知通道
        //    注意：这里不用 getNotificationChannel()==null 判断 —— 老装机上该通道已存在
        //    且名字还是"同步服务"，必须重新创建一次才能把名称/描述改过来
        //    （系统只允许更新名称与描述，不会重置用户改过的开关/重要性）。
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SERVICE, "任务追踪", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在追踪的任务"
            setShowBadge(false)
        })
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

    fun getVibratePatternSetting(context: Context): String {
        val prefs = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        return prefs.getString("reminder_vibrate_pattern", DEFAULT_VIBRATE_PATTERN) ?: DEFAULT_VIBRATE_PATTERN
    }

    private fun getRingUriSetting(context: Context): Uri? {
        val prefs = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        val s = prefs.getString("reminder_ring_uri", null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    /** 根据提醒配置选 channel（多通道时取最高档），并返回是否全屏/锁屏可见 */
    private fun channelFor(strength: String): Pair<String, Boolean> {
        val (channels, _) = ReminderStrength.parseConfig(strength)
        return when (ReminderStrength.highest(channels)) {
            ReminderStrength.NOTIFY -> CHANNEL_NOTIFY to false
            ReminderStrength.VIBRATE -> CHANNEL_VIBRATE to true
            ReminderStrength.BEEP -> CHANNEL_BEEP to false
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

        val delay5mIntent = PendingIntent.getBroadcast(
            context, taskUuid.hashCode() + 3,
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DELAY_5M
                putExtra("task_uuid", taskUuid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // v5.25.0：锁屏隐私档（默认开）。
        //   背景：第四轮「ICU 医生」等专业场景用户指出 —— 原来 setVisibility(VISIBILITY_PUBLIC)
        //   会把**任务名明晃晃显示在锁屏上**（「给 3 床 测血糖」这种），旁边的人全看得见。
        //   这是专业/家庭场景的死线，所以默认隐藏内容，设置页可关。
        val hideOnLock = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
            .getBoolean("lock_hide_content", true)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (hideOnLock) "你有任务到点了" else title)
            .setContentText(if (hideOnLock) "解锁查看详情" else "到点了，去做吧")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setVisibility(
                if (hideOnLock) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PUBLIC
            )
            .addAction(0, "完成", doneIntent)
            .addAction(0, "5分钟", delay5mIntent)
            .addAction(0, "延迟1天", delayIntent)
            .setPriority(if (withFullScreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)

        if (withFullScreen) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(openIntent, true)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(taskUuid.hashCode(), builder.build())
    }

    /** 演示提醒效果（设置页"演示"键用）：立即发一条对应通道的测试通知 */
    /**
     * 演示某通道的提醒效果（每个通道触发真实效果，不再全部只发通知）
     * - VIBRATE：真振动 Vibrator（用设置里"振动周期"pattern）
     * - NOTIFY：弹通知 + 系统默认通知提示音（通道已 setSound）
     * - BEEP：弹通知 + 短促提示音（通道 IMPORTANCE_DEFAULT 默认声）
     * - RING：弹通知 + 自定义铃声（通道 setSound 用户选择）
     */
    fun demoReminder(context: Context, strength: String) {
        val label = ReminderStrength.label(strength).removePrefix("通知栏弹窗（").removePrefix("振动提醒（")
            .removePrefix("提示音（").removePrefix("响铃提醒（").removeSuffix("）")

        when (ReminderStrength.migrateLegacy(strength)) {
            ReminderStrength.VIBRATE -> {
                // 真振动：用 Vibrator 系统服务（绕过通知通道的通道级振动）
                val pattern = getVibratePatternSetting(context)
                triggerVibrate(context, pattern)
            }
            ReminderStrength.NOTIFY -> {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(99999, NotificationCompat.Builder(context, CHANNEL_NOTIFY)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("提醒效果演示")
                    .setContentText("这是「$label」的效果（顶部横幅 + 提示音）")
                    .setAutoCancel(true)
                    .build())
            }
            ReminderStrength.BEEP -> {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(99998, NotificationCompat.Builder(context, CHANNEL_BEEP)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("提醒效果演示")
                    .setContentText("这是「$label」的效果（短促提示音）")
                    .setAutoCancel(true)
                    .build())
            }
            ReminderStrength.RING -> {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(99997, NotificationCompat.Builder(context, CHANNEL_RING)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("提醒效果演示")
                    .setContentText("这是「$label」的效果（自定义铃声）")
                    .setAutoCancel(true)
                    .build())
            }
            else -> {
                // 兜底：弹通用通知
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(99999, NotificationCompat.Builder(context, CHANNEL_NOTIFY)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("提醒效果演示")
                    .setContentText("这是「$label」的效果")
                    .setAutoCancel(true)
                    .build())
            }
        }
    }

    /**
     * 真振动：用系统 Vibrator 服务触发（不依赖通知通道）
     * 解析 "0,300,200,300" 格式的振动模式 → 实际振动
     */
    fun triggerVibrate(context: Context, pattern: String) {   // v5.15.3：公开（每日提醒也用它）
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } ?: return

        val longArray = parseVibratePattern(pattern)
        if (longArray.isNotEmpty()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArray, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArray, -1)
            }
        }
    }

    /**
     * 追踪中任务的常驻通知（boss R30：下拉通知栏一直挂着任务名，有步骤时下面小字写当前步骤）。
     *
     * 尺寸约束：通知栏只有一两行 —— 所以
     *   标题 = 任务名；正文 = "2/5 · 洗菜"（当前步骤）；多任务时右下角补"共 N 个追踪中"。
     * 没有追踪任务时**不会**调用这个函数（SyncService 会直接把前台态撤掉，通知随之消失）。
     */
    fun buildTrackingNotification(
        context: Context,
        title: String,
        sub: String = "",
        count: Int = 1,
    ): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_track)
            .setContentTitle(title)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
        if (sub.isNotBlank()) b.setContentText(sub)
        if (count > 1) b.setSubText("共 $count 个追踪中")
        return b.build()
    }
}