package com.taskbar.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.taskbar.app.MainActivity

/**
 * 通知帮助类：3 档强度
 * - standard: 一条普通通知
 * - repeat:   通知后 5 分钟再发（链式，最多 3 次）
 * - alarm:    高优先级 + 长震动 + 全屏 Intent
 */
object NotificationHelper {

    const val CHANNEL_STANDARD = "ch_standard"
    const val CHANNEL_ALARM = "ch_alarm"
    const val CHANNEL_SERVICE = "ch_service"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_STANDARD) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_STANDARD, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "到点任务提醒" })
        }
        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ALARM, "强提醒（闹钟）", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "重要任务的闹钟式强提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_SERVICE, "同步服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "任务指南同步服务运行中" })
        }
    }

    fun showReminder(
        context: Context,
        taskUuid: String,
        title: String,
        strength: String
    ) {
        ensureChannels(context)
        val isAlarm = strength == "alarm"
        val channel = if (isAlarm) CHANNEL_ALARM else CHANNEL_STANDARD

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
            .setPriority(if (isAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)

        if (isAlarm) {
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
