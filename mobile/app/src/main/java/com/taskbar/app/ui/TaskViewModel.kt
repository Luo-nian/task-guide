package com.taskbar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.repo.TaskRepository
import com.taskbar.app.widget.TrackWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // ===== 完成庆祝事件（游戏化正反馈：弹层显示积分/升级） =====
    data class CompletionEvent(
        val title: String,
        val points: Int,
        val newLevel: Int? = null,
        val newLevelName: String? = null
    )
    private val _completion = MutableStateFlow<CompletionEvent?>(null)
    val completion: StateFlow<CompletionEvent?> = _completion.asStateFlow()
    fun consumeCompletion() { _completion.value = null }

    fun steps(taskUuid: String) = repo.observeSteps(taskUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ===== 任务操作 =====
    fun createTask(
        type: String, title: String, desc: String, category: String,
        priority: String, dueAt: Long?, repeatRule: String?, deadline: Long?,
        reminderStrength: String? = null, target: Int = 1,
        onCreated: (String) -> Unit = {}
    ) = viewModelScope.launch {
        val task = repo.createTask(type, title, desc, category, priority, dueAt, repeatRule, deadline,
            reminderStrength = reminderStrength, target = target)
        refreshWidget()
        onCreated(task.uuid)
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
        val task = repo.getTask(uuid)
        val pointsBefore = repo.getTotalPoints()
        repo.completeTask(uuid)
        // 完成庆祝：弹层显示获得积分 + 升级信息（层层递进正反馈）
        if (task != null) {
            val points = repo.getTotalPoints()
            val before = com.taskbar.app.data.model.Levels.of(pointsBefore)
            val after = com.taskbar.app.data.model.Levels.of(points)
            val lv = if (after.lv > before.lv) after.lv to after.name else null
            _completion.value = CompletionEvent(task.title, task.rewardPoints, lv?.first, lv?.second)
        }
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
    fun delayTask(uuid: String, delayMillis: Long) = viewModelScope.launch {
        repo.delayTask(uuid, delayMillis)
        refreshWidget()
    }

    // ===== 仓库置顶/置底 =====
    fun pinToHome(uuid: String) = viewModelScope.launch {
        repo.pinToHome(uuid)
        refreshWidget()
    }

    fun unpinToRepo(uuid: String) = viewModelScope.launch {
        repo.unpinToRepo(uuid)
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

    /** 所有习惯最长连续天数（"我的"页"坚持"统计） */
    suspend fun maxHabitStreak(): Int = repo.maxHabitStreak()

    // ===== 设置 =====
    fun setTrackLimit(limit: Int) = viewModelScope.launch { repo.setTrackLimit(limit) }
    fun setSetting(key: String, value: String) = viewModelScope.launch { repo.setSetting(key, value) }
    suspend fun getSetting(key: String, default: String = ""): String = repo.getSetting(key, default)

    // ===== 分类 =====
    suspend fun getCustomCategories(): List<String> = repo.getCustomCategories()
    fun addCustomCategory(name: String) = viewModelScope.launch { repo.addCustomCategory(name) }
    fun removeCustomCategory(name: String) = viewModelScope.launch { repo.removeCustomCategory(name) }

    private fun refreshWidget() {
        TrackWidgetProvider.refreshAll(ctx)
    }
}
