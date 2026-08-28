package com.taskguide.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskguide.app.TaskGuideApp
import com.taskguide.app.data.model.Step
import com.taskguide.app.data.model.Task
import com.taskguide.app.data.repo.TaskRepository
import com.taskguide.app.widget.TrackWidgetProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TaskRepository = (app as TaskGuideApp).repo
    private val ctx = app.applicationContext

    val mainList: StateFlow<List<Task>> = repo.observeMainList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tracking: StateFlow<List<Task>> = repo.observeTracking()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archive: StateFlow<List<Task>> = repo.observeArchive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val habits: StateFlow<List<Task>> = repo.observeHabits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trackLimit: StateFlow<Int> = repo.observeTrackLimit()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    fun steps(taskUuid: String) = repo.observeSteps(taskUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ===== 任务操作 =====
    fun createTask(
        type: String, title: String, desc: String, category: String,
        priority: String, dueAt: Long?, repeatRule: String?, deadline: Long?
    ) = viewModelScope.launch {
        repo.createTask(type, title, desc, category, priority, dueAt, repeatRule, deadline)
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

    fun startTracking(uuid: String) = viewModelScope.launch {
        repo.startTracking(uuid)
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
    fun addStep(taskUuid: String, title: String, attrLabel: String, attrValue: String) =
        viewModelScope.launch {
            repo.addStep(taskUuid, title, attrLabel, attrValue)
            refreshWidget()
        }

    fun advanceStep(stepUuid: String) = viewModelScope.launch {
        repo.advanceStep(stepUuid)
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

    suspend fun habitStreak(taskUuid: String) = repo.habitStreak(taskUuid)

    // ===== 设置 =====
    fun setTrackLimit(limit: Int) = viewModelScope.launch { repo.setTrackLimit(limit) }
    fun setSetting(key: String, value: String) = viewModelScope.launch { repo.setSetting(key, value) }
    suspend fun getSetting(key: String, default: String = ""): String = repo.getSetting(key, default)

    private fun refreshWidget() {
        TrackWidgetProvider.refreshAll(ctx)
    }
}
