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
                .addMigrations(AppDatabase.MIGRATION_2_3)
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
            "reminder_strength" to "standard",
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
    }
}
