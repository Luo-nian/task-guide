package com.taskguide.app.data.repo

import com.taskguide.app.data.db.AppDatabase
import com.taskguide.app.data.model.ChangeOp
import com.taskguide.app.data.model.DEFAULT_TRACK_LIMIT
import com.taskguide.app.data.model.HabitLog
import com.taskguide.app.data.model.Priority
import com.taskguide.app.data.model.Step
import com.taskguide.app.data.model.StepStatus
import com.taskguide.app.data.model.SyncMeta
import com.taskguide.app.data.model.Task
import com.taskguide.app.data.model.TaskType
import com.taskguide.app.data.model.TrackCardItem
import com.taskguide.app.data.model.TrackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 变更总线：Repository 写操作后发射，服务器层订阅并推送给电脑端 */
object ChangeBus {
    private val _events = MutableSharedFlow<ChangeOp>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()
    fun tryEmit(op: ChangeOp) { _events.tryEmit(op) }
}

class TaskRepository(private val db: AppDatabase) {

    private val taskDao = db.taskDao()
    private val stepDao = db.stepDao()
    private val habitDao = db.habitLogDao()
    private val settingsDao = db.settingsDao()
    private val syncDao = db.syncMetaDao()

    private fun now() = System.currentTimeMillis()
    private fun newUuid() = UUID.randomUUID().toString()

    // ==================== 观察 ====================
    fun observeMainList(): Flow<List<Task>> = taskDao.observeMainList()
    fun observeTracking(): Flow<List<Task>> = taskDao.observeTracking()
    fun observeArchive(): Flow<List<Task>> = taskDao.observeArchive()
    fun observeHabits(): Flow<List<Task>> = taskDao.observeHabits()
    fun observeTask(uuid: String): Flow<Task?> = taskDao.observeByUuid(uuid)
    fun observeSteps(taskUuid: String): Flow<List<Step>> = stepDao.observeByTask(taskUuid)

    suspend fun getTrackCards(): List<TrackCardItem> =
        taskDao.getTracking().map { TrackCardItem(it, stepDao.getCurrentStep(it.uuid)) }

    // ==================== 任务 CRUD ====================
    suspend fun createTask(
        type: String,
        title: String,
        desc: String = "",
        category: String = "",
        priority: String = Priority.MEDIUM,
        dueAt: Long? = null,
        repeatRule: String? = null,
        deadline: Long? = null,
        rewardPoints: Int = 10
    ): Task {
        val t = now()
        val task = Task(
            uuid = newUuid(), type = type, title = title, desc = desc,
            category = category, priority = priority, dueAt = dueAt,
            repeatRule = repeatRule, deadline = deadline,
            trackStatus = TrackStatus.PENDING, rewardPoints = rewardPoints,
            createdAt = t, updatedAt = t
        )
        taskDao.upsert(task)
        emit(ChangeOp("upsert", "task", task.uuid))
        return task
    }

    suspend fun updateTask(task: Task) {
        val updated = task.copy(updatedAt = now())
        taskDao.upsert(updated)
        emit(ChangeOp("upsert", "task", updated.uuid))
    }

    suspend fun deleteTask(uuid: String) {
        val t = now()
        taskDao.softDelete(uuid, t)
        // 关联步骤软删除
        stepDao.getByTask(uuid).forEach { stepDao.softDelete(it.uuid, t) }
        emit(ChangeOp("delete", "task", uuid))
    }

    // ==================== 追踪状态机 ====================
    /**
     * 开始追踪：
     * 1. 若已达上限，把最早的追踪中任务转回 pending
     * 2. 当前任务转 tracking
     * 3. 第一个 todo 步骤转 doing（若有步骤）
     */
    suspend fun startTracking(uuid: String): Boolean {
        val limit = getTrackLimit()
        val current = taskDao.countTracking()
        if (current >= limit) {
            // 收回最早的追踪中任务
            val oldest = taskDao.getTracking().minByOrNull { it.updatedAt } ?: return false
            taskDao.updateTrackStatus(oldest.uuid, TrackStatus.PENDING, now())
            emit(ChangeOp("upsert", "task", oldest.uuid))
        }
        val t = now()
        taskDao.updateTrackStatus(uuid, TrackStatus.TRACKING, t)
        // 第一个 todo 步骤转 doing
        stepDao.getFirstUndone(uuid)?.let { first ->
            stepDao.updateStatus(first.uuid, StepStatus.DOING, null, t)
            emit(ChangeOp("upsert", "step", first.uuid))
        }
        emit(ChangeOp("upsert", "task", uuid))
        return true
    }

    suspend fun stopTracking(uuid: String) {
        taskDao.updateTrackStatus(uuid, TrackStatus.PENDING, now())
        emit(ChangeOp("upsert", "task", uuid))
    }

    /** 直接完成任务（无步骤或一键完成），累加积分 */
    suspend fun completeTask(uuid: String) {
        val task = taskDao.getByUuid(uuid) ?: return
        val t = now()
        taskDao.upsert(task.copy(trackStatus = TrackStatus.DONE, done = 1, doneAt = t, updatedAt = t))
        // 积分奖励：total_points += reward_points
        val current = settingsDao.get("total_points")?.toIntOrNull() ?: 0
        settingsDao.set(com.taskguide.app.data.model.Setting("total_points", (current + task.rewardPoints).toString()))
        stepDao.getByTask(uuid).filter { it.status != StepStatus.DONE }.forEach {
            stepDao.updateStatus(it.uuid, StepStatus.DONE, t, t)
            emit(ChangeOp("upsert", "step", it.uuid))
        }
        emit(ChangeOp("upsert", "task", uuid))
    }

    /** 从归档恢复（取消完成） */
    suspend fun restoreTask(uuid: String) {
        val task = taskDao.getByUuid(uuid) ?: return
        val t = now()
        taskDao.upsert(task.copy(trackStatus = TrackStatus.PENDING, done = 0, doneAt = null, updatedAt = t))
        emit(ChangeOp("upsert", "task", uuid))
    }

    // ==================== 步骤推进 ====================
    /**
     * 推进当前步骤：当前 doing 步骤 → done，下一个 todo → doing
     * 若全部完成 → 任务自动完成
     */
    suspend fun advanceStep(stepUuid: String) {
        val step = stepDao.getByUuid(stepUuid) ?: return
        val t = now()
        stepDao.updateStatus(step.uuid, StepStatus.DONE, t, t)
        emit(ChangeOp("upsert", "step", step.uuid))

        // 找下一个未完成步骤
        val next = stepDao.getFirstUndone(step.taskUuid)
        if (next == null) {
            // 全部完成 → 任务完成
            completeTask(step.taskUuid)
        } else {
            stepDao.updateStatus(next.uuid, StepStatus.DOING, null, t)
            emit(ChangeOp("upsert", "step", next.uuid))
        }
    }

    /** 跳过当前步骤：doing → todo，下一个 todo → doing */
    suspend fun skipStep(stepUuid: String) {
        val step = stepDao.getByUuid(stepUuid) ?: return
        val t = now()
        stepDao.updateStatus(step.uuid, StepStatus.TODO, null, t)
        emit(ChangeOp("upsert", "step", step.uuid))
        stepDao.getFirstUndone(step.taskUuid)?.let { next ->
            stepDao.updateStatus(next.uuid, StepStatus.DOING, null, t)
            emit(ChangeOp("upsert", "step", next.uuid))
        }
    }

    suspend fun addStep(taskUuid: String, title: String, attrLabel: String = "", attrValue: String = ""): Step {
        val t = now()
        val order = stepDao.countByTask(taskUuid)
        val step = Step(
            uuid = newUuid(), taskUuid = taskUuid, title = title,
            attrLabel = attrLabel, attrValue = attrValue, sortOrder = order,
            createdAt = t, updatedAt = t
        )
        stepDao.upsert(step)
        emit(ChangeOp("upsert", "step", step.uuid))
        return step
    }

    suspend fun updateStep(step: Step) {
        val updated = step.copy(updatedAt = now())
        stepDao.upsert(updated)
        emit(ChangeOp("upsert", "step", updated.uuid))
    }

    suspend fun deleteStep(uuid: String) {
        stepDao.softDelete(uuid, now())
        emit(ChangeOp("delete", "step", uuid))
    }

    /** 步骤进度：已完成 / 总数 */
    suspend fun stepProgress(taskUuid: String): Pair<Int, Int> {
        val done = stepDao.countDoneByTask(taskUuid)
        val total = stepDao.countByTask(taskUuid)
        return done to total
    }

    // ==================== 延迟任务（自定义天数） ====================
    suspend fun delayTask(uuid: String, days: Int) {
        val task = taskDao.getByUuid(uuid) ?: return
        if (task.dueAt == null) return
        val t = now()
        val newDue = task.dueAt + days * 86_400_000L
        taskDao.upsert(task.copy(dueAt = newDue, delayedCount = task.delayedCount + 1, updatedAt = t))
        emit(ChangeOp("upsert", "task", uuid))
    }

    // ==================== 习惯打卡 ====================
    suspend fun checkHabit(taskUuid: String, date: String): Boolean {
        if (habitDao.isChecked(taskUuid, date)) return false
        val log = HabitLog(taskUuid = taskUuid, checkDate = date, createdAt = now())
        habitDao.insert(log)
        emit(ChangeOp("upsert", "habit", log.taskUuid))
        return true
    }

    suspend fun habitStreak(taskUuid: String): Int {
        val logs = habitDao.getByTask(taskUuid).map { it.checkDate }.sortedDescending()
        if (logs.isEmpty()) return 0
        // 从今天往前数连续
        val today = java.time.LocalDate.now()
        var streak = 0
        var cursor = today
        for (dateStr in logs) {
            val d = try { java.time.LocalDate.parse(dateStr) } catch (_: Exception) { continue }
            if (d == cursor) { streak++; cursor = cursor.minusDays(1) }
            else if (d.isBefore(cursor)) break
        }
        return streak
    }

    // ==================== 设置 ====================
    suspend fun getTrackLimit(): Int =
        settingsDao.get("track_limit")?.toIntOrNull() ?: DEFAULT_TRACK_LIMIT

    fun observeTrackLimit(): Flow<Int> =
        settingsDao.observe("track_limit").map { it?.toIntOrNull() ?: DEFAULT_TRACK_LIMIT }

    suspend fun setTrackLimit(limit: Int) = settingsDao.set(
        com.taskguide.app.data.model.Setting("track_limit", limit.toString())
    )

    suspend fun getSetting(key: String, default: String = ""): String =
        settingsDao.get(key) ?: default

    suspend fun setSetting(key: String, value: String) = settingsDao.set(
        com.taskguide.app.data.model.Setting(key, value)
    )

    // ==================== 同步元信息 ====================
    suspend fun getLastSync(device: String): Long =
        syncDao.get(device)?.lastSync ?: 0L

    suspend fun setLastSync(device: String, ts: Long) {
        val existing = syncDao.get(device)
        syncDao.upsert(SyncMeta(
            id = existing?.id ?: 0, device = device, lastSync = ts,
            lastChangeId = existing?.lastChangeId ?: 0
        ))
    }

    // ==================== 同步入参（被服务器调用） ====================

    /** 构建全量同步数据（首次连接用） */
    suspend fun buildFullSyncPayload(): FullSyncPayload {
        val tasks = taskDao.getAllNonDeleted()
        val steps = stepDao.getAllNonDeleted()
        val habits = habitDao.getAll()
        return FullSyncPayload(tasks, steps, habits, System.currentTimeMillis())
    }

    /** 构建增量变更列表（since 之后的所有变更，含软删除 delete） */
    suspend fun buildIncrementalChanges(since: Long): List<ChangeOp> {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val changes = mutableListOf<ChangeOp>()
        taskDao.getChangedSince(since).forEach {
            val op = if (it.deleted == 1) "delete" else "upsert"
            val data = if (it.deleted == 0) json.encodeToString(Task.serializer(), it) else null
            changes.add(ChangeOp(op, "task", it.uuid, data))
        }
        stepDao.getChangedSince(since).forEach {
            val op = if (it.deleted == 1) "delete" else "upsert"
            val data = if (it.deleted == 0) json.encodeToString(Step.serializer(), it) else null
            changes.add(ChangeOp(op, "step", it.uuid, data))
        }
        habitDao.getChangedSince(since).forEach {
            changes.add(ChangeOp("upsert", "habit", it.taskUuid, json.encodeToString(HabitLog.serializer(), it)))
        }
        return changes
    }

    suspend fun upsertTaskFromSync(task: Task) {
        // 冲突处理：本地更新则跳过
        val local = taskDao.getByUuid(task.uuid)
        if (local != null && local.updatedAt > task.updatedAt) return
        taskDao.upsert(task)
    }

    suspend fun upsertStepFromSync(step: Step) {
        val local = stepDao.getByUuid(step.uuid)
        if (local != null && local.updatedAt > step.updatedAt) return
        stepDao.upsert(step)
    }

    suspend fun applyChange(change: ChangeOp) {
        when (change.op) {
            "upsert" -> when (change.entity) {
                "task" -> change.data?.let {
                    val t = kotlinx.serialization.json.Json.decodeFromString<Task>(it)
                    upsertTaskFromSync(t)
                }
                "step" -> change.data?.let {
                    val s = kotlinx.serialization.json.Json.decodeFromString<Step>(it)
                    upsertStepFromSync(s)
                }
                "habit" -> { /* habit_logs 通过 created_at 增量同步，这里忽略 */ }
            }
            "delete" -> when (change.entity) {
                "task" -> taskDao.softDelete(change.uuid, now())
                "step" -> stepDao.softDelete(change.uuid, now())
            }
        }
    }

    private fun emit(op: ChangeOp) {
        ChangeBus.tryEmit(op)
    }
}
