package com.taskbar.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.taskbar.app.data.db.AppDatabase
import com.taskbar.app.data.model.Setting
import com.taskbar.app.data.repo.TaskRepository
import com.taskbar.app.notify.ReminderScheduler
import com.taskbar.app.server.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class TaskBarApp : Application() {

    lateinit var repo: TaskRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()

        // Room 初始化失败也不能 throw —— application 一抛就被系统杀，整个 app 闪退
        // 兜底：磁盘库失败时降级为内存库，保证 repo 永远有值（TaskViewModel 构造才不崩）
        val db = try {
            Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME)
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
                .build()
        } catch (e: Exception) {
            Log.e("TaskBarApp", "磁盘库初始化失败，降级内存库", e)
            Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build()
        }
        repo = TaskRepository(db)

        // 首次安装初始化默认设置（异步，失败不影响启动）
        appScope.launch {
            runCatching { initDefaultSettings() }
            runCatching { ReminderScheduler.rescheduleAll(repo, this@TaskBarApp) }
        }
        // v5.15.27 M7（boss：「设置里那个每日提醒 现在好像没什么用了」）——
        //   真因：起床/睡前提醒只在 **BOOT_COMPLETED（重启手机）** 时 restore。
        //   而 AlarmManager 的闹钟在"重装/强停/被系统清理"后会全部丢失 →
        //   不重启手机就永远不再响，表现为"这个功能好像没用了"。
        //   现在每次 App 冷启动都补一次恢复 → 只要打开过 App，提醒就一直是活的。
        appScope.launch {
            runCatching { com.taskbar.app.notify.DailyReminderScheduler.restoreAll(this@TaskBarApp) }
        }
        // v5.15.10：后台预热等级图标（PathParser 解析 10 档 ~30 段），
        //   否则首次进「我的」页会在首帧解析 → 实测 90th 300ms 的卡顿来源之一
        appScope.launch {
            runCatching { com.taskbar.app.ui.LevelGlyphs.prewarm() }
        }
        // 预热自定义分类（新建任务页直接读，避免进页面后再回写状态触发二次全屏重组）
        appScope.launch {
            runCatching { repo.getCustomCategories() }
        }

        // v5.18.0：追踪中任务 → 通知栏常驻卡（boss：「只有追踪中的任务可以上去」）
        //   用 Flow 订阅而不是在各个"开始/停止追踪"的入口逐个埋点 ——
        //   这样电脑端同步过来的追踪变化、步骤推进、打卡解除追踪，全都能覆盖到。
        appScope.launch {
            runCatching {
                repo.observeTrackingFeed().collect { info ->
                    SyncService.refreshTracking(info)
                }
            }.onFailure { Log.e("TaskBarApp", "追踪通知观察器失败", it) }
        }

        // 注意：SyncService 不在此启动！原因：
        // - 启动期任何前台服务异常都可能让 application 被系统杀（Android 14 起尤其严格）
        // - 改为在 MainActivity.onCreate 的 LaunchedEffect 里延迟启动（UI 起来后再启）
        // - 这样闪退时至少能看到崩溃提示，crash.log 也能记到现场
    }

    /**
     * 全局未捕获异常处理：写文件 + 继续走系统默认处理（弹 ANR/崩溃）
     * 下次问题排查时可通过 adb pull /data/data/com.taskbar.app/files/crash.log 拉日志
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(filesDir, "crash.log")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                logFile.appendText(
                    """
                    ===
                    TIME: ${java.util.Date()}
                    THREAD: ${thread.name}
                    ${sw.toString()}

                    """.trimIndent()
                )
            } catch (_: Exception) { /* 写日志失败就忽略 */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private suspend fun initDefaultSettings() {
        val defaults = listOf(
            "track_limit" to "3",
            "reminder_strength" to "notify",
            "auto_start" to "1",
            "delay_options" to "custom",
            "theme" to "frosted",
            "server_port" to "8899",
            "total_points" to "0"
        )
        defaults.forEach { (k, v) ->
            if (repo.getSetting(k, "") == "") repo.setSetting(k, v)
        }
    }

    companion object {
        lateinit var instance: TaskBarApp
            private set

        /**
         * v5.22.5：点提醒通知要直接打开的那条任务。
         *   通知里本来就带了 `task_uuid`（NotificationHelper 已 putExtra），
         *   但 MainActivity **从来没读过 intent** → 点通知只回主页，落不到那条任务上
         *   （多份体验测试报告提到）。这里由 Activity 写入、Compose 侧消费后清空。
         */
        val pendingOpenTask = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}
