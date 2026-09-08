package com.taskbar.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * v5.15.3：起床/睡前每日提醒
 * - AlarmManager.setExactAndAllowWhileIdle 单发 → 触发后自动重排次日（避免 setRepeating 在 Android 12+ 不准）
 * - 通知渠道复用全局提醒方式（reminder_strength prefs），与任务提醒体验一致
 */
object DailyReminderScheduler {
    const val ACTION_DAILY = "com.taskbar.app.action.DAILY_REMIND"
    const val REQUEST_MORNING = 5001
    const val REQUEST_NIGHT = 5002
    private const val TAG = "DailyReminder"

    // id: morning / night；hour/minute：当天触发时刻
    fun scheduleNext(context: Context, id: Int, hour: Int, minute: Int, title: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 已过今天该时刻 → 排明天
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pending(context, id, title)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            Log.i(TAG, "已排 $title @ ${cal.time}")
        } catch (e: Exception) {
            // Android 12+ 无 SCHEDULE_EXACT_ALARM 授权时降级不精确
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi) }
            catch (_: Exception) {}
        }
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pending(context, id, "")) } catch (_: Exception) {}
    }

    private fun pending(context: Context, id: Int, title: String): PendingIntent {
        val i = Intent(context, DailyReminderReceiver::class.java).apply {
            action = ACTION_DAILY
            putExtra("id", id)
            putExtra("title", title)
        }
        return PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 开机/改设置后恢复已启用的每日提醒 */
    fun restoreAll(context: Context) {
        val prefs = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("daily_morning_on", false)) {
            scheduleNext(context, REQUEST_MORNING, prefs.getInt("daily_morning_h", 7), prefs.getInt("daily_morning_m", 30), "早上好")
        }
        if (prefs.getBoolean("daily_night_on", false)) {
            scheduleNext(context, REQUEST_NIGHT, prefs.getInt("daily_night_h", 22), prefs.getInt("daily_night_m", 30), "夜深了")
        }
    }
}

/** 每日提醒触发接收器：弹通知 + 重排次日 */
class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", DailyReminderScheduler.REQUEST_MORNING)
        val title = intent.getStringExtra("title") ?: "每日提醒"
        val prefs = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        val on = prefs.getBoolean(
            if (id == DailyReminderScheduler.REQUEST_MORNING) "daily_morning_on" else "daily_night_on",
            false
        )
        if (!on) return
        // 弹通知（通知渠道按全局默认提醒方式，若含振动/铃声则一并触发）
        val strength = prefs.getString("reminder_strength", "notify") ?: "notify"
        NotificationHelper.ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val label = when (id) {
            DailyReminderScheduler.REQUEST_MORNING -> "起床 · 新的一天开始啦"
            else -> "睡前 · 别忘了今天的事"
        }
        // 依据全局方式：默认走通知渠道；含振动补真振动；含铃声走 ring 渠道
        val ch = when {
            strength.contains("ring") -> NotificationHelper.CHANNEL_RING
            strength.contains("vibrate") -> {
                NotificationHelper.triggerVibrate(context, NotificationHelper.getVibratePatternSetting(context))
                NotificationHelper.CHANNEL_VIBRATE
            }
            strength.contains("beep") -> NotificationHelper.CHANNEL_BEEP
            else -> NotificationHelper.CHANNEL_NOTIFY
        }
        nm.notify(id, androidx.core.app.NotificationCompat.Builder(context, ch)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title + "，" + (if (id == DailyReminderScheduler.REQUEST_MORNING) "该起床啦" else "该休息啦"))
            .setContentText(label)
            .setAutoCancel(true)
            .build())
        // 重排次日
        val h = prefs.getInt(if (id == DailyReminderScheduler.REQUEST_MORNING) "daily_morning_h" else "daily_night_h", 7)
        val m = prefs.getInt(if (id == DailyReminderScheduler.REQUEST_MORNING) "daily_morning_m" else "daily_night_m", 30)
        DailyReminderScheduler.scheduleNext(context, id, h, m, title)
    }
}
