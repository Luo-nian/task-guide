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
        val defaultStrength = repo.getSetting("reminder_strength", "standard")
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
                if (due > now) {
                    schedule(context, t.uuid, due, strength)
                } else {
                    // 已过期未完成 → 立即提醒（未完成警告）
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
        val strength = inputData.getString("strength") ?: "standard"
        val repeatCount = inputData.getInt(ReminderScheduler.KEY_REPEAT, 0)

        val app = applicationContext as TaskBarApp
        val t = app.repo.observeTask(uuid).first() ?: return Result.success()

        // 已完成则不提醒
        if (t.trackStatus == TrackStatus.DONE) return Result.success()

        NotificationHelper.showReminder(applicationContext, uuid, t.title, strength)

        // repeat 模式：5 分钟后再提醒一次，最多 MAX_REPEAT 次
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
                        val strength = app.repo.getSetting("reminder_strength", "standard")
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
    }
}
