package com.taskbar.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
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
    internal const val EXTRA_STRENGTH = "strength"
    internal const val ACTION_ALARM = "com.taskbar.app.ACTION_REMINDER_ALARM"

    fun tagFor(uuid: String) = PREFIX + uuid

    /**
     * v5.18.3：每日型任务（每日任务 / 习惯 / 重复）的判断。
     * 这类任务的 due_at 表达的是"每天几点"，不是绝对截止时间。
     */
    internal fun isDailyKindForReminder(t: com.taskbar.app.data.model.Task): Boolean =
        t.category == "daily" ||
            t.type == com.taskbar.app.data.model.TaskType.HABIT ||
            t.type == com.taskbar.app.data.model.TaskType.REPEAT ||
            t.repeatRule == "daily"

    /** 今天 0 点（毫秒）—— 与 TaskRepository.todayStart() 同一口径 */
    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * v5.18.3：把"每天那个时刻"折算成下一次发生时间（今天该时刻已过 → 明天同名时刻）。
     * 只取 due_at 的「时:分」，日期用今天/明天 —— 这样每日任务的提醒不会因为
     * due_at 不推进而永远落在过去。
     */
    internal fun nextDailyOccurrence(dueAt: Long, now: Long): Long {
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

    /**
     * v5.22.5（24 份代入式体验测试报告的**最高频问题**）：按任务的周期规则折算「下一次发生时刻」。
     *   原实现只认 `daily`：`weekly:1,3,5` 与 `everyNd` 虽然在 UI 里能选、也能存进库，
     *   但全仓**没有任何消费方**（排提醒、主页列表、连续天数都不认）——
     *   于是「每周一三五 6:30 体能」天天出现、天天提醒、天天判未完成，
     *   而真正需要它的场景（进货日、教研日、排班）全线失效。
     */
    internal fun nextOccurrenceFor(dueAt: Long, repeatRule: String?, now: Long): Long {
        val rule = repeatRule ?: return dueAt
        if (rule == "daily") return nextDailyOccurrence(dueAt, now)
        if (rule.startsWith("weekly:")) {
            val days = rule.removePrefix("weekly:").split(",")
                .mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
            if (days.isEmpty()) return nextDailyOccurrence(dueAt, now)
            val src = java.util.Calendar.getInstance().apply { timeInMillis = dueAt }
            val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
            c.set(java.util.Calendar.HOUR_OF_DAY, src.get(java.util.Calendar.HOUR_OF_DAY))
            c.set(java.util.Calendar.MINUTE, src.get(java.util.Calendar.MINUTE))
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
            var guard = 0
            while (guard < 8) {
                // Calendar.DAY_OF_WEEK：周日=1…周六=7；本项目的规则是 1=周一…7=周日
                val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
                val iso = if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
                if (iso in days) return c.timeInMillis
                c.add(java.util.Calendar.DAY_OF_MONTH, 1)
                guard++
            }
            return c.timeInMillis
        }
        if (rule.startsWith("every")) {
            val n = rule.removePrefix("every").removeSuffix("d").toIntOrNull() ?: return dueAt
            if (n <= 1) return nextDailyOccurrence(dueAt, now)
            val c = java.util.Calendar.getInstance().apply { timeInMillis = nextDailyOccurrence(dueAt, now) }
            var guard = 0
            while (c.timeInMillis <= now && guard < 400) {
                c.add(java.util.Calendar.DAY_OF_MONTH, n)
                guard++
            }
            return c.timeInMillis
        }
        return dueAt
    }

    /** 为带 due_at 的任务调度到点提醒 */
    fun schedule(context: Context, taskUuid: String, dueAt: Long, strength: String) {
        // v5.22.5：时刻已过**不再静默丢弃**。
        //   原来 `if (delay <= 0) return` —— 于是「把任务时间从 20:00 改到 22:00」之后
        //   重新排程时若那一刻已过，提醒就**无声消失**（报告里反复出现"改了时间就不响了"）。
        //   现在改为：已过 → 1.5 秒后立刻响一次，至少让用户看到它。
        val now = System.currentTimeMillis()
        val fireAt = if (dueAt > now) dueAt else now + 1500L
        val delay = fireAt - now
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
        // v5.18.4：改用「唯一名 + REPLACE」入队。
        //   原来用 enqueue()，而 rescheduleAll 在**每次冷启动**都会跑 →
        //   每开一次 App 就给同一任务再排一条同样的提醒。
        //   真机实测：一个任务的 reminder_<uuid> 标签下堆了 20 条 WorkSpec，
        //   表现就是"开几次 App，到点就响几次"。REPLACE 保证同一任务只有一条。
        WorkManager.getInstance(context)
            .enqueueUniqueWork(tagFor(taskUuid), ExistingWorkPolicy.REPLACE, req)

        // ⭐⭐ v5.18.5 关键修复：闹钟类功能必须用 AlarmManager，不能靠 WorkManager。
        //   实测（2026-09-20）：WorkManager 排的任务**到点 15 小时仍未执行**
        //   （计划触发 09-20 09:00，次日 00:12 查仍是 state=0「等待中」、run_attempt=0），
        //   而手机自 09-16 就开着、App 也在电池白名单里 → 不是关机、不是省电限制，
        //   而是 **OneTimeWorkRequest 本质是"不精确的后台任务"，系统可以无限期推迟**
        //   （vivo/OriginOS 尤其激进）。等于"提醒永远不响"。
        //   这里与 DailyReminderScheduler（起床/睡前提醒）保持同一套做法。
        //   WorkManager 那条**保留作兜底**：两路都触发时通知 id 相同（uuid.hashCode()），
        //   后一次只是覆盖前一次，用户只看到一条。
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPending(context, taskUuid, strength)
        // v5.26.2：升级为 setAlarmClock（闹钟级）——
        //   ① 勿扰模式默认放行"闹钟"类（第五轮 4 个身份死于被勿扰压住）
        //   ② Doze 深度休眠也照响 ③ 时钟 App 里能看到这次提醒（误排可见）
        //   setExactAndAllowWhileIdle 保留为降级路径（部分 ROM 限制 setAlarmClock 时仍可用）。
        val showPi = android.app.PendingIntent.getActivity(
            context, 10001,
            (context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: android.content.Intent()).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showPi), pi)
        } catch (e: Exception) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi) }
            catch (_: Exception) {
                try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi) } catch (_: Exception) {}
            }
        }
    }

    fun cancel(context: Context, taskUuid: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(taskUuid))
        // v5.18.5：闹钟一并撤销 —— 否则「点完成 / 删除任务」之后到点还会响一次
        //  （PendingIntent 相等性只看 action/component/requestCode，不看 extras，所以传什么都匹配）
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPending(context, taskUuid, ""))
        }
    }

    /** v5.18.5：闹钟用的 PendingIntent（requestCode 用 uuid 哈希，与通知 id 同源） */
    private fun alarmPending(context: Context, uuid: String, strength: String): PendingIntent {
        val i = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(KEY_UUID, uuid)
            putExtra(EXTRA_STRENGTH, strength)
        }
        return PendingIntent.getBroadcast(
            context, uuid.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 启动/开机后：重新调度所有未完成带 due_at 的任务 */
    suspend fun rescheduleAll(repo: com.taskbar.app.data.repo.TaskRepository, context: Context) {
        val now = System.currentTimeMillis()
        // 全局默认提醒方式存 SharedPreferences（与 SettingsScreen/NotificationHelper 一致）
        val defaultStrength = context.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
            .getString("reminder_strength", "notify") ?: "notify"
        // v5.27.0：负责人路由 —— 本机身份名（self_name），owner 非空且不是本机 → 不排不提醒
        val selfName = repo.selfName()
        // v5.18.4：不能用 observeMainList() —— 它的 SQL 自带 `track_status != 'done'`，
        //   会让"昨天完成的每日任务"在 SQL 层就被滤掉（见 TaskDao.observeRemindable 注释）。
        val tasks = repo.observeRemindable().first()
        tasks.forEach { t ->
            // v5.27.0：派给别人的任务不在本机响（负责人设备才响；owner 空 = 自己/全员）
            val owner = t.owner.trim()
            if (owner.isNotEmpty() && owner != selfName) return@forEach
            // v5.18.4：每日型任务「昨天已完成」在原始库里仍然是 DONE ——
            //   每日折算（normalizeDailyReset）只发生在 UI 读取层，rescheduleAll 拿的是原始行。
            //   于是新的一天里它被判成 DONE 直接跳过 → **今天的提醒根本排不上**，
            //   表现就是"完成过一次之后，提醒再也不响"（全仓只有这一个排程入口，冷启动才跑）。
            //   这里与 UI 同一口径：每日型任务只有 doneAt 落在今天 0 点之后才算已完成。
            val dailyKind = isDailyKindForReminder(t)
            val effectivelyDone = t.trackStatus == com.taskbar.app.data.model.TrackStatus.DONE &&
                (!dailyKind || (t.doneAt ?: 0L) >= startOfToday())
            if (t.dueAt != null && !effectivelyDone) {
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
                if (dailyKind) due = nextOccurrenceFor(due, t.repeatRule, now)
                // v5.24.0：**提前提醒** —— 把响铃时刻往前挪 remindAheadMin 分钟
                val fireAt = due - (t.remindAheadMin.coerceAtLeast(0) * 60_000L)
                if (fireAt > now) {
                    schedule(context, t.uuid, fireAt, strength)
                } else if (!dailyKind) {
                    // 已过期未完成 → 立即提醒（未完成警告）
                    // ⚠️ 每日型任务不走这里：它没有"逾期"这回事
                    NotificationHelper.showReminder(context, t.uuid, t.title, strength)
                }
            }
        }
    }

    /**
     * v5.18.5：**到点提醒的真正逻辑** —— 系统闹钟（主）与 WorkManager（兜底）共用这一套。
     * 抽出来是为了不出现两份会走偏的实现（本项目吃过多份实现不同步的亏）。
     */
    suspend fun fireNow(ctx: Context, uuid: String, strength: String, repeatCount: Int) {
        val app = ctx.applicationContext as TaskBarApp
        val t = app.repo.observeTask(uuid).first() ?: return

        // 已完成则不提醒
        if (t.trackStatus == TrackStatus.DONE) return

        // v5.18.4：已删除（软删）的任务不再提醒。
        //   observeByUuid 是 `SELECT * ... LIMIT 1`，**不过滤 deleted**；而删除任务
        //   并不会取消已排的提醒（删除路径里没有 cancel）→ 删掉的任务到点还会弹一次。
        //   这里补一道守卫，顺带让历史遗留的"孤儿"提醒条目自动失效（触发后自行作废）。
        if (t.deleted != 0) return

        // v5.27.0：负责人路由兜底 —— 排程后 owner 可能被改到别人头上，触发那一刻再判一次。
        //   owner 非空且不是本机身份名 → 本机不响（同步照常，由负责人那台设备提醒）。
        val selfName = app.repo.selfName()
        val owner = t.owner.trim()
        if (owner.isNotEmpty() && owner != selfName) return

        NotificationHelper.showReminder(ctx.applicationContext, uuid, t.title, strength)

        // v5.26.2：错过补看 —— 记下「这条提醒响过了」。
        //   场景（第五轮消防员/听障用户）：手机摸不到/没感知到，提醒响过即失效。
        //   回到 App 时主页置顶显示「你错过了 N 条提醒」，点开看清单。
        //   任务完成后横幅自动消失（只统计未完成的）；「知道了」清旗标。
        ctx.applicationContext.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
            .edit().putLong("fired_" + uuid, System.currentTimeMillis()).apply()

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
            WorkManager.getInstance(ctx.applicationContext).enqueue(req)
        }

        // 未受理升级：N 分钟后若还没处理，按最高档升一级（notify→vibrate→beep→ring）
        val prefs = ctx.applicationContext.getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
        // v5.24.0：**默认关闭**。原来默认 true，而设置页里那个开关早已被删除 →
        //   用户无法关闭它，夜里没处理的通知会自动升级成振动/响铃
        //   （夜班作息用户直接被吵醒；体验测试报告命中，且这属于"用户看不见也关不掉的行为"）。
        val escalateOn = prefs.getBoolean("reminder_escalate_enabled", false)
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
                WorkManager.getInstance(ctx.applicationContext).enqueue(req)
            }
        }
        return
    }

}

class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uuid = inputData.getString(ReminderScheduler.KEY_UUID) ?: return Result.success()
        val strength = inputData.getString(ReminderScheduler.EXTRA_STRENGTH) ?: "notify"
        val repeatCount = inputData.getInt(ReminderScheduler.KEY_REPEAT, 0)
        // v5.18.5：逻辑已抽到 fireNow（与系统闹钟共用），这里只做转发
        ReminderScheduler.fireNow(applicationContext, uuid, strength, repeatCount)
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
            // v5.22.5：修两个被多份报告命中的硬伤 ——
            //   ① 「延迟1天」原代码是 `delayTask(uuid, 1)`，而 delayTask 的参数单位是**毫秒**
            //      → 实际只把截止时间往后推 1 毫秒，下一次冷启动/刷新时通知立刻又弹（按钮形同虚设）；
            //   ② `it.dueAt!!` 空断言 —— 通知还挂在栏里时把任务改成「次数任务/一次性」
            //      （dueAt 被置空），再点通知上的按钮 → **崩溃**。
            ACTION_DELAY -> handleDelay(context, uuid, 24L * 3600_000L, 0)
            ACTION_DELAY_5M -> handleDelay(context, uuid, 5L * 60_000L, 5)
        }
    }

    /**
     * v5.22.5：通知上的「延迟」统一走这里。
     *   - 每日型任务的 due_at 表达的是「每天几点」，**不能按毫秒平移**
     *     （原来会把"每天 9:00"静默改成"每天 9:00:00.001"，且所有后续折算全部漂移）
     *     → 改成按「下一次发生的那个时刻」重排；
     *   - 非每日型按天/分钟平移，并同步写库；
     *   - dueAt 为空（次数任务/一次性）时**不崩**，只清掉通知。
     */
    private fun handleDelay(context: Context, uuid: String, delayMillis: Long, keepMinutes: Int) {
        ioScope.launch {
            val repo = (context.applicationContext as TaskBarApp).repo
            val cur = repo.observeTask(uuid).first()
            if (cur == null) {
                cancelNotification(context, uuid)
                return@launch
            }
            val base = cur.dueAt
            if (base == null) {
                cancelNotification(context, uuid)
                return@launch
            }
            val strength = cur.reminderStrength ?: context
                .getSharedPreferences("taskguide_prefs", Context.MODE_PRIVATE)
                .getString("reminder_strength", "notify") ?: "notify"
            val now = System.currentTimeMillis()
            val daily = ReminderScheduler.isDailyKindForReminder(cur)
            val next = if (!daily) {
                repo.delayTask(uuid, delayMillis)
                base + delayMillis
            } else if (keepMinutes == 5) {
                now + 5L * 60_000L              // 「5分钟」对每日型就是 5 分钟后再来一次
            } else {
                ReminderScheduler.nextDailyOccurrence(base, now)   // 「延迟1天」= 推到明天那个时刻
            }
            if (next > now) ReminderScheduler.schedule(context, uuid, next, strength)
            cancelNotification(context, uuid)
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

/**
 * v5.18.5：系统闹钟到点 → 直接执行提醒（不再绕 WorkManager，避免被系统无限期推迟）。
 * 用 goAsync() 把广播生命周期延长到协程跑完。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra(ReminderScheduler.KEY_UUID) ?: return
        val strength = intent.getStringExtra(ReminderScheduler.EXTRA_STRENGTH) ?: "notify"
        val app = context.applicationContext
        val pending = goAsync()
        ioScope.launch {
            try {
                ReminderScheduler.fireNow(app, uuid, strength, 0)
                // v5.22.4（体验测试发现）——**每日型提醒响过一次就再也不排了**：
                //   全项目唯一的排程入口是"冷启动 / 开机"（TaskBarApp.onCreate），
                //   闹钟触发后不重排 → 只有每天都打开 App 的人才能收到第二天的提醒
                //   （这正好解释了测试里"有两天没响"）。触发后补排一次即可。
                runCatching {
                    (app as? com.taskbar.app.TaskBarApp)?.let { tba ->
                        ReminderScheduler.rescheduleAll(tba.repo, app)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ReminderAlarm", "闹钟触发提醒失败", e)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
