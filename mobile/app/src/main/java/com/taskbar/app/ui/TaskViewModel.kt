package com.taskbar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.repo.TaskRepository
import com.taskbar.app.widget.TrackWidgetProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TaskRepository = (app as TaskBarApp).repo
    private val ctx = app.applicationContext

    val mainList: StateFlow<List<Task>> = repo.observeMainListToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tracking: StateFlow<List<Task>> = repo.observeTracking()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archive: StateFlow<List<Task>> = repo.observeArchive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val habits: StateFlow<List<Task>> = repo.observeHabits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 未来任务（明天及以后到期） */
    val futureTasks: StateFlow<List<Task>> = repo.observeFutureTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trackLimit: StateFlow<Int> = repo.observeTrackLimit()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    /** 总积分（积分系统） */
    val totalPoints: StateFlow<Int> = repo.observeSetting("total_points")
        .map { it?.toIntOrNull() ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun steps(taskUuid: String) = repo.observeSteps(taskUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ===== 任务操作 =====
    fun createTask(
        type: String, title: String, desc: String, category: String,
        priority: String, dueAt: Long?, repeatRule: String?, deadline: Long?,
        reminderStrength: String? = null
    ) = viewModelScope.launch {
        repo.createTask(type, title, desc, category, priority, dueAt, repeatRule, deadline, reminderStrength = reminderStrength)
        refreshWidget()
    }

    fun updateTask(task: Task) = viewModelScope.launch {
        repo.updateTask(task)
        refreshWidget()
    }

    fun deleteTask(uuid: String) = viewModelScope.launch {
        repo.deleteTask(uuid)
        refreshWidget()
    }

    /** 开始追踪；已达上限返回 false（UI 据此弹提示） */
    fun startTracking(uuid: String, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ok = repo.startTracking(uuid)
        onResult(ok)
        refreshWidget()
    }

    fun stopTracking(uuid: String) = viewModelScope.launch {
        repo.stopTracking(uuid)
        refreshWidget()
    }

    fun completeTask(uuid: String) = viewModelScope.launch {
        repo.completeTask(uuid)
        refreshWidget()
    }

    fun restoreTask(uuid: String) = viewModelScope.launch {
        repo.restoreTask(uuid)
        refreshWidget()
    }

    // ===== 步骤操作 =====
    fun addStep(taskUuid: String, title: String, attrLabel: String, attrValue: String, insertAt: Int? = null) =
        viewModelScope.launch {
            repo.addStep(taskUuid, title, attrLabel, attrValue, insertAt)
            refreshWidget()
        }

    fun advanceStep(stepUuid: String) = viewModelScope.launch {
        repo.advanceStep(stepUuid)
        refreshWidget()
    }

    /** 按任务 UUID 推进（自动找当前 doing 或第一个 todo 步骤）；步骤全 done 时 no-op */
    fun advanceStepByTask(taskUuid: String) = viewModelScope.launch {
        repo.advanceStepByTask(taskUuid)
        refreshWidget()
    }

    fun skipStep(stepUuid: String) = viewModelScope.launch {
        repo.skipStep(stepUuid)
        refreshWidget()
    }

    fun deleteStep(uuid: String) = viewModelScope.launch {
        repo.deleteStep(uuid)
        refreshWidget()
    }

    // ===== 延迟 =====
    fun delayTask(uuid: String, days: Int) = viewModelScope.launch {
        repo.delayTask(uuid, days)
        refreshWidget()
    }

    // ===== 习惯打卡 =====
    fun checkHabit(taskUuid: String, date: String) = viewModelScope.launch {
        repo.checkHabit(taskUuid, date)
    }

    /** 打卡并返回是否真打卡（false=今天已打过） */
    suspend fun checkHabitAndReturn(taskUuid: String, date: String): Boolean =
        repo.checkHabit(taskUuid, date)

    /** 进入页面时初始化 checkedToday 用 */
    suspend fun repoIsCheckedToday(taskUuid: String, date: String): Boolean =
        repo.isHabitCheckedToday(taskUuid, date)

    fun observeHabitStreak(taskUuid: String) = repo.observeHabitStreak(taskUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    suspend fun habitStreak(taskUuid: String) = repo.habitStreak(taskUuid)

    // ===== 设置 =====
    fun setTrackLimit(limit: Int) = viewModelScope.launch { repo.setTrackLimit(limit) }
    fun setSetting(key: String, value: String) = viewModelScope.launch { repo.setSetting(key, value) }
    suspend fun getSetting(key: String, default: String = ""): String = repo.getSetting(key, default)

    // ===== 分类 =====
    suspend fun getCustomCategories(): List<String> = repo.getCustomCategories()
    fun addCustomCategory(name: String) = viewModelScope.launch { repo.addCustomCategory(name) }

    private fun refreshWidget() {
        TrackWidgetProvider.refreshAll(ctx)
    }
}
