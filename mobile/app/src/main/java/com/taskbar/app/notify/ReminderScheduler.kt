package com.taskbar.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.TrackStatus
import com.taskbar.app.server.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 提醒/接收器内部用的 IO 协程作用域（避免 GlobalScope 警告） */
internal val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

object ReminderScheduler {

    private const val PREFIX = "reminder_"
    internal const val KEY_UUID = "task_uuid"
    internal const val KEY_REPEAT = "repeat_count"
    internal const val MAX_REPEAT = 3

    fun tagFor(uuid: String) = PREFIX + uuid

    /**
     * v5.18.3：每日型任务（每日任务 / 习惯 / 重复）的判断。
     * 这类任务的 due_at 表达的是"每天几点"，不是绝对截止时间。
     */
    private fun isDailyKindForReminder(t: com.taskbar.app.data.model.Task): Boolean =
        t.category == "daily" ||
            t.type == com.taskbar.app.data.model.TaskType.HABIT ||
            t.type == com.taskbar.app.data.model.TaskType.REPEAT ||
            t.repeatRule == "daily"

    /**
     * v5.18.3：把"每天那个时刻"折算成下一次发生时间（今天该时刻已过 → 明天同名时刻）。
     * 只取 due_at 的「时:分」，日期用今天/明天 —— 这样每日任务的提醒不会因为
     * due_at 不推进而永远落在过去。
     */
    private fun nextDailyOccurrence(dueAt: Long, now: Long): Long {
        val src = java.util.Calendar.getInstance()
        src.timeInMillis = dueAt
        val dst = java.util.Calendar.getInstance()
        dst.timeInMillis = now
        dst.set(java.util.Calendar.HOUR_OF_DAY, src.get(java.util.Calendar.HOUR_OF_DAY))
        dst.set(java.util.Calendar.MINUTE, src.get(java.util.Calendar.MINUTE))
        dst.set(java.util.Calendar.SECOND, 0)
        dst.set(java.util.Calendar.MILLISECOND, 0)
        if (dst.timeInMillis <= now) dst.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return dst.timeInMillis
    }

    /** 为带 due_at 的任务调度到点提醒 */
    fun schedule(context: Context, taskUuid: String, dueAt: Long, strength: String) {
        val delay = dueAt - System.currentTimeMillis()
        if (delay <= 0) return
        val data = workDataOf(
            KEY_UUID to taskUuid,
            "strength" to strength,
            KEY_REPEAT to 0
        )
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(tagFor(taskUuid))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    fun cancel(context: Context, taskUuid: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(taskUuid))
    }

    /** 启动/开机后：重新调度所有未完成带 due_at 的任务 */
    suspend fun rescheduleAll(repo: com.taskbar.app.data.repo.TaskRepository, context: Context) {
        val now = System.currentTimeMillis()
        // 全局默认提醒方式存 SharedPreferences（与 SettingsScreen/NotificationHelper 一致）
        val defaultStrength = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
            .getString("reminder_strength", "notify") ?: "notify"
        val tasks = repo.observeMainList().first()
        tasks.forEach { t ->
            if (t.dueAt != null && t.trackStatus != com.taskbar.app.data.model.TrackStatus.DONE) {
                // per-task 强度覆盖全局默认
                val strength = t.reminderStrength ?: defaultStrength
                var due = t.dueAt
                // 习惯/重复任务：到期日撞上法定节假日 → 顺延到下一个工作日 9 点（节假日休息）
                if ((t.type == com.taskbar.app.data.model.TaskType.HABIT || t.type == com.taskbar.app.data.model.TaskType.REPEAT)
                    && com.taskbar.app.data.model.ChineseHolidays.isHoliday(due)) {
                    due = com.taskbar.app.data.model.ChineseHolidays.nextWorkday(due)
                }
                // v5.18.3（boss：「为什么每日任务会出现逾期情况 不是零点刷新吗」）——
                //   每日型任务的 due_at 是"每天几点提醒"且**创建后从不推进**（全仓无滚动逻辑），
                //   直接拿它比 now 会永远落在过去 → 每次冷启动/开机都走进下面那个
                //   "已过期未完成 → 立即提醒"分支，等于天天把每日任务当逾期。
                //   这里先折算成"今天那个时刻"（今天已过 → 明天那个时刻）。
                val dailyKind = isDailyKindForReminder(t)
                if (dailyKind) due = nextDailyOccurrence(due, now)
                if (due > now) {
                    schedule(context, t.uuid, due, strength)
                } else if (!dailyKind) {
                    // 已过期未完成 → 立即提醒（未完成警告）
                    // ⚠️ 每日型任务不走这里：它没有"逾期"这回事
                    NotificationHelper.showReminder(context, t.uuid, t.title, strength)
                }
            }
        }
    }
}

class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uuid = inputData.getString(ReminderScheduler.KEY_UUID) ?: return Result.success()
        val strength = inputData.getString("strength") ?: "notify"
        val repeatCount = inputData.getInt(ReminderScheduler.KEY_REPEAT, 0)

        val app = applicationContext as TaskBarApp
        val t = app.repo.observeTask(uuid).first() ?: return Result.success()

        // 已完成则不提醒
        if (t.trackStatus == TrackStatus.DONE) return Result.success()

        NotificationHelper.showReminder(applicationContext, uuid, t.title, strength)

        // repeat 模式（旧版兼容）：5 分钟后再提醒一次，最多 MAX_REPEAT 次
        if (strength == "repeat" && repeatCount < ReminderScheduler.MAX_REPEAT) {
            val data = workDataOf(
                ReminderScheduler.KEY_UUID to uuid,
                "strength" to strength,
                ReminderScheduler.KEY_REPEAT to (repeatCount + 1)
            )
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setInputData(data)
                .addTag(ReminderScheduler.tagFor(uuid))
                .build()
            WorkManager.getInstance(applicationContext).enqueue(req)
        }

        // 未受理升级：N 分钟后若还没处理，按最高档升一级（notify→vibrate→beep→ring）
        val prefs = applicationContext.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        val escalateOn = prefs.getBoolean("reminder_escalate_enabled", true)
        if (escalateOn && strength != "repeat") {   // 旧 repeat 已有独立链式，不叠加升级
            val minutes = prefs.getInt("reminder_escalate_minutes", 5)
            val (channels, scope) = com.taskbar.app.data.model.ReminderStrength.parseConfig(strength)
            val next = com.taskbar.app.data.model.ReminderStrength.escalateNext(channels)
            if (next != null) {
                // 升级到更高一档（保留原有通道 + 追加更高档，只升一级）
                val upgraded = com.taskbar.app.data.model.ReminderStrength.serializeConfig(
                    (channels + next).distinct(), scope
                )
                val data = workDataOf(
                    ReminderScheduler.KEY_UUID to uuid,
                    "strength" to upgraded,
                    ReminderScheduler.KEY_REPEAT to 0
                )
                val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
                    .setInputData(data)
                    .addTag(ReminderScheduler.tagFor(uuid))
                    .build()
                WorkManager.getInstance(applicationContext).enqueue(req)
            }
        }
        return Result.success()
    }
}

class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra("task_uuid") ?: return
        val app = context.applicationContext as TaskBarApp
        when (intent.action) {
            ACTION_DONE -> {
                ioScope.launch {
                    app.repo.completeTask(uuid)
                    ReminderScheduler.cancel(context, uuid)
                    cancelNotification(context, uuid)
                }
            }
            ACTION_DELAY -> {
                ioScope.launch {
                    app.repo.delayTask(uuid, 1)
                    val t = app.repo.observeTask(uuid).first()
                    t?.let {
                        // 保留原任务的 per-task 强度；未设置才用全局默认（prefs）
                        val defaultStrength = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
                            .getString("reminder_strength", "notify") ?: "notify"
                        val strength = it.reminderStrength ?: defaultStrength
                        ReminderScheduler.schedule(context, uuid, it.dueAt!!, strength)
                    }
                    cancelNotification(context, uuid)
                }
            }
            ACTION_DELAY_5M -> {
                ioScope.launch {
                    app.repo.delayTask(uuid, 5L * 60_000L)
                    val t = app.repo.observeTask(uuid).first()
                    t?.let {
                        val defaultStrength = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
                            .getString("reminder_strength", "notify") ?: "notify"
                        val strength = it.reminderStrength ?: defaultStrength
                        ReminderScheduler.schedule(context, uuid, it.dueAt!!, strength)
                    }
                    cancelNotification(context, uuid)
                }
            }
        }
    }

    private fun cancelNotification(context: Context, uuid: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(uuid.hashCode())
    }

    companion object {
        const val ACTION_DONE = "com.taskbar.app.ACTION_DONE"
        const val ACTION_DELAY = "com.taskbar.app.ACTION_DELAY"
        const val ACTION_DELAY_5M = "com.taskbar.app.ACTION_DELAY_5M"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 开机后启动同步服务
        SyncService.start(context)
        // 重新调度所有提醒（在协程里）
        val app = context.applicationContext as TaskBarApp
        ioScope.launch {
            ReminderScheduler.rescheduleAll(app.repo, context)
        }
        // v5.15.3：恢复起床/睡前每日提醒
        DailyReminderScheduler.restoreAll(context)
    }
}
