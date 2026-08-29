package com.taskguide.app

import android.app.Application
import androidx.room.Room
import com.taskguide.app.data.db.AppDatabase
import com.taskguide.app.data.model.Setting
import com.taskguide.app.data.repo.TaskRepository
import com.taskguide.app.notify.ReminderScheduler
import com.taskguide.app.server.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskGuideApp : Application() {

    lateinit var repo: TaskRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        val db = Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()
        repo = TaskRepository(db)

        // 首次安装初始化默认设置
        appScope.launch {
            initDefaultSettings()
            // 重新调度所有未完成提醒
            ReminderScheduler.rescheduleAll(repo, this@TaskGuideApp)
        }

        // 启动同步前台服务（Ktor 服务器常驻）
        SyncService.start(this)
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
        lateinit var instance: TaskGuideApp
            private set
    }
}
