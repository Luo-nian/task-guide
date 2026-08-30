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

        try {
            val db = Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME)
                .fallbackToDestructiveMigration()
                .build()
            repo = TaskRepository(db)
        } catch (e: Exception) {
            Log.e("TaskBarApp", "Room 初始化失败", e)
            throw e  // 数据库初始化失败必须崩（无法恢复）
        }

        // 首次安装初始化默认设置
        appScope.launch {
            runCatching { initDefaultSettings() }
            runCatching { ReminderScheduler.rescheduleAll(repo, this@TaskBarApp) }
        }

        // 启动同步前台服务（容错：失败不影响 app 启动）
        appScope.launch {
            runCatching { SyncService.start(this@TaskBarApp) }
                .onFailure { Log.e("TaskBarApp", "SyncService 启动失败", it) }
        }
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
