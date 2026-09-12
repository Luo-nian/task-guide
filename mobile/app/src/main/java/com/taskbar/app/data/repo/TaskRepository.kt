package com.taskbar.app.data.repo

import com.taskbar.app.data.db.AppDatabase
import com.taskbar.app.data.model.ChangeOp
import com.taskbar.app.data.model.DEFAULT_TRACK_LIMIT
import com.taskbar.app.data.model.FullSyncPayload
import com.taskbar.app.data.model.HabitLog
import com.taskbar.app.data.model.Priority
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.StepStatus
import com.taskbar.app.data.model.SyncMeta
import com.taskbar.app.data.model.SettingChange
import com.taskbar.app.data.model.SettingKV
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TaskType
import com.taskbar.app.data.model.TrackCardItem
import com.taskbar.app.data.model.TrackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 变更总线：Repository 写操作后发射，服务器层订阅并推送给电脑端 */
object ChangeBus {
    private val _events = MutableSharedFlow<ChangeOp>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()
    fun tryEmit(op: ChangeOp) { _events.tryEmit(op) }
}

/**
 * v5.15.19：电脑端实时连接状态（boss：「手机端没有显示已连接」）。
 *
 * 修复的问题：手机端原先只有"已配对（曾经配过谁）"这一个**历史记录**，
 * 电脑端断开后依旧显示"已配对"，用户无法判断当前到底连没连上。
 *
 * WS 服务端（ApiRoutes 的 webSocket("/ws")）在电脑端连上/断开时会改动这里，
 * 设置页订阅 [flow] 实时刷新。真值来源是 WS 连接本身，而不是缓存设置。
 */
object LinkState {
    private val _flow = MutableStateFlow(false)

    /** true = 至少有一个电脑端正通过 WS 连着本机 */
    val flow: StateFlow<Boolean> = _flow.asStateFlow()

    val isConnected: Boolean get() = _flow.value

    /** 电脑端连接建立/断开时调用（由 WS 服务端维护引用计数） */
    fun set(connected: Boolean) { _flow.value = connected }
}

class TaskRepository(private val db: AppDatabase) {

    private val taskDao = db.taskDao()
    private val stepDao = db.stepDao()
    private val habitDao = db.habitLogDao()
    private val settingsDao = db.settingsDao()
    private val syncDao = db.syncMetaDao()

    private fun now() = System.currentTimeMillis()
    private fun newUuid() = UUID.randomUUID().toString()

    /** 同步用 Json：encodeDefaults=true 才能把默认值字段也序列化给桌面端 */
    private val syncJson = kotlinx.serialization.json.Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // ==================== 观察 ====================
    fun observeMainList(): Flow<List<Task>> = taskDao.observeMainList()

    suspend fun getTask(uuid: String): Task? = taskDao.getByUuid(uuid)

    suspend fun getTotalPoints(): Int =
        settingsDao.get("total_points")?.toIntOrNull() ?: 0
    /** 主列表（不含未来任务）：未来任务由 FutureTasksSection 独占显示，避免重复 */
    fun observeMainListToday(): Flow<List<Task>> {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 23)
        c.set(java.util.Calendar.MINUTE, 59)
        c.set(java.util.Calendar.SECOND, 59)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val dayEnd = c.timeInMillis
        // v5.15.18 P0：今天 0 点。用于把"昨天完成的每日任务"在**读取时**折算回待办，
        //   而不是写库重置（写库会 bump updated_at 污染同步 LWW）。
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return taskDao.observeMainListToday(dayStart, dayEnd).map { list ->
            list.map { t -> normalizeDailyReset(t, dayStart) }
        }
    }

    /** 跨天的每日任务：在返回给 UI 之前折算成"今天的待办"（不落库） */
    private fun normalizeDailyReset(t: Task, dayStart: Long): Task {
        if (t.category != "daily" || t.trackStatus != TrackStatus.DONE) return t
        val doneToday = (t.doneAt ?: 0L) >= dayStart
        if (doneToday) return t
        return t.copy(
            trackStatus = TrackStatus.PENDING,
            done = 0,
            doneAt = null
        )
    }
    fun observeTracking(): Flow<List<Task>> = taskDao.observeTracking()
    fun observeArchive(): Flow<List<Task>> = taskDao.observeArchive()
    fun observeHabits(): Flow<List<Task>> = taskDao.observeHabits()
    fun observeTask(uuid: String): Flow<Task?> = taskDao.observeByUuid(uuid)
    fun observeSteps(taskUuid: String): Flow<List<Step>> = stepDao.observeByTask(taskUuid)

    /** 所有步骤的 Flow（性能优化：用于主页聚合展示，避免每任务独立 Flow 订阅） */
    fun observeAllSteps(): Flow<List<Step>> = stepDao.observeAll()

    /** 未来任务：今天 23:59:59 之后的未完成任务 */
    fun observeFutureTasks(): Flow<List<Task>> {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 23)
        c.set(java.util.Calendar.MINUTE, 59)
        c.set(java.util.Calendar.SECOND, 59)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return taskDao.observeFutureTasks(c.timeInMillis)
    }

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
        reminderStrength: String? = null,
        target: Int = 1
    ): Task {
        val t = now()
        // 积分按类型+优先级规则计算（RewardRules）
        val task = Task(
            uuid = newUuid(), type = type, title = title, desc = desc,
            category = category, priority = priority, dueAt = dueAt,
            repeatRule = repeatRule, deadline = deadline,
            trackStatus = TrackStatus.PENDING,
            rewardPoints = com.taskbar.app.data.model.RewardRules.forTask(type, priority),
            reminderStrength = reminderStrength,
            progress = 0, target = if (type == com.taskbar.app.data.model.TaskType.MILESTONE) target.coerceAtLeast(1) else 1,
            createdAt = t, updatedAt = t
        )
        taskDao.upsert(task)
        emitWithData(ChangeOp("upsert", "task", task.uuid))
        return task
    }

    suspend fun updateTask(task: Task) {
        // 编辑时同步按当前类型/优先级重算积分，保证规则一致
        val recalculated = task.copy(
            rewardPoints = com.taskbar.app.data.model.RewardRules.forTask(task.type, task.priority),
            updatedAt = now()
        )
        taskDao.upsert(recalculated)
        emitWithData(ChangeOp("upsert", "task", recalculated.uuid))
    }

    suspend fun deleteTask(uuid: String) {
        val t = now()
        taskDao.softDelete(uuid, t)
        // 关联步骤软删除
        stepDao.getByTask(uuid).forEach { stepDao.softDelete(it.uuid, t) }
        emitWithData(ChangeOp("delete", "task", uuid))
    }

    // ==================== 追踪状态机 ====================
    /**
     * 开始追踪：
     * 1. 若已达上限 → 返回 false（不挤掉旧任务，由 UI 提示用户先取消别的追踪或调高上限）
     * 2. 当前任务转 tracking
     * 3. 第一个 todo 步骤转 doing（若有步骤）
     */
    suspend fun startTracking(uuid: String): Boolean {
        val limit = getTrackLimit()
        val current = taskDao.countTracking()
        if (current >= limit) return false
        val t = now()
        taskDao.updateTrackStatus(uuid, TrackStatus.TRACKING, t)
        // 第一个 todo 步骤转 doing
        stepDao.getFirstUndone(uuid)?.let { first ->
            stepDao.updateStatus(first.uuid, StepStatus.DOING, null, t)
            emitWithData(ChangeOp("upsert", "step", first.uuid))
        }
        emitWithData(ChangeOp("upsert", "task", uuid))
        return true
    }

    suspend fun stopTracking(uuid: String) {
        taskDao.updateTrackStatus(uuid, TrackStatus.PENDING, now())
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    /** 直接完成任务（无步骤或一键完成），累加积分。
     *  habit 走"今日打卡 + 加积分"逻辑，不归档任务（明天继续在今日栏）。
     *  milestone 走"进度+1"逻辑，达到 target 才归档；未达到继续留在主页。 */
    suspend fun completeTask(uuid: String) {
        val task = taskDao.getByUuid(uuid) ?: return
        val t = now()
        if (task.type == TaskType.HABIT) {
            // 习惯：今日已打卡则不再处理；写 habit_log + 加积分，任务保持非 done
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(t))
            if (habitDao.isChecked(uuid, date)) return
            habitDao.insert(HabitLog(taskUuid = uuid, checkDate = date, createdAt = t))
            emitWithData(ChangeOp("upsert", "habit", uuid))
        } else if (task.type == TaskType.MILESTONE) {
            // 里程碑：进度 +1；达到目标次数才归档（大任务，可多次推进）
            // 防止重复完成刷分：已归档则不再加分
            if (task.trackStatus == TrackStatus.DONE) return
            val newProgress = task.progress + 1
            if (newProgress >= task.target) {
                taskDao.upsert(task.copy(progress = newProgress, trackStatus = TrackStatus.DONE, done = 1, doneAt = t, updatedAt = t))
            } else {
                taskDao.upsert(task.copy(progress = newProgress, updatedAt = t))
            }
            emitWithData(ChangeOp("upsert", "task", uuid))
        } else {
            // 普通任务：归档 + 加积分
            if (task.trackStatus == TrackStatus.DONE) return
            taskDao.upsert(task.copy(trackStatus = TrackStatus.DONE, done = 1, doneAt = t, updatedAt = t))
        }
        // 积分奖励（v5.15.7：写值 + 打时间戳 + 推电脑端，双端"最新为主"一致）
        bumpPoints(task.rewardPoints)
        // 步骤全 done（习惯/普通都一样）
        stepDao.getByTask(uuid).filter { it.status != StepStatus.DONE }.forEach {
            stepDao.updateStatus(it.uuid, StepStatus.DONE, t, t)
            emitWithData(ChangeOp("upsert", "step", it.uuid))
        }
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    /** 从归档恢复（取消完成）—— 同步扣回完成任务时奖励的积分 */
    suspend fun restoreTask(uuid: String) {
        val task = taskDao.getByUuid(uuid) ?: return
        val t = now()
        taskDao.upsert(task.copy(trackStatus = TrackStatus.PENDING, done = 0, doneAt = null, updatedAt = t))
        // 扣回积分（防刷分：完成→恢复→完成 来回刷）+ 推电脑端
        bumpPoints(-task.rewardPoints)
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    /** 实时观察习惯连续天数（Flow 驱动，打卡后自动刷新） */
    fun observeHabitStreak(taskUuid: String): Flow<Int> {
        return habitDao.observeCheckDatesByTask(taskUuid).map { dates ->
            if (dates.isEmpty()) return@map 0
            val today = java.time.LocalDate.now()
            var streak = 0
            var cursor = today
            // dates 已经是 DESC 排序
            for (dateStr in dates) {
                val d = try { java.time.LocalDate.parse(dateStr) } catch (_: Exception) { continue }
                if (d == cursor) { streak++; cursor = cursor.minusDays(1) }
                else if (d.isBefore(cursor)) break
            }
            streak
        }
    }

    // ==================== 步骤推进 ====================
    /**
     * 推进当前步骤：当前 doing 步骤 → done，下一个 todo → doing
     * 若全部完成 → 自动完成任务（归档 + 加积分 + 取消追踪）
     */
    suspend fun advanceStep(stepUuid: String) {
        val step = stepDao.getByUuid(stepUuid) ?: return
        val t = now()
        stepDao.updateStatus(step.uuid, StepStatus.DONE, t, t)
        emitWithData(ChangeOp("upsert", "step", step.uuid))

        // 找下一个未完成步骤
        val next = stepDao.getFirstUndone(step.taskUuid)
        if (next != null) {
            stepDao.updateStatus(next.uuid, StepStatus.DOING, null, t)
            emitWithData(ChangeOp("upsert", "step", next.uuid))
        } else {
            // 所有步骤都完成了 → 自动完成任务（completeTask 内部会归档 + 加积分 + 步骤补 done）
            completeTask(step.taskUuid)
        }
    }

    /** 按任务 UUID 推进：自动找 DOING 步骤；没有则找第一个 TODO；全 done 时兜底自动完成任务 */
    suspend fun advanceStepByTask(taskUuid: String) {
        val step = stepDao.getCurrentStep(taskUuid) ?: stepDao.getFirstUndone(taskUuid)
        if (step != null) {
            advanceStep(step.uuid)
            return
        }
        // 步骤全 done：若任务还没归档，自动完成（兜底，防止遗留半完成状态）
        val task = taskDao.getByUuid(taskUuid) ?: return
        if (task.trackStatus != TrackStatus.DONE && task.type != TaskType.HABIT) {
            completeTask(taskUuid)
        }
    }

    /** 跳过当前步骤：doing → todo，下一个 todo → doing */
    suspend fun skipStep(stepUuid: String) {
        val step = stepDao.getByUuid(stepUuid) ?: return
        val t = now()
        stepDao.updateStatus(step.uuid, StepStatus.TODO, null, t)
        emitWithData(ChangeOp("upsert", "step", step.uuid))
        stepDao.getFirstUndone(step.taskUuid)?.let { next ->
            stepDao.updateStatus(next.uuid, StepStatus.DOING, null, t)
            emitWithData(ChangeOp("upsert", "step", next.uuid))
        }
    }

    /**
     * 添加步骤。insertAt=null 追加到末尾；insertAt>=0 插入到该位置
     * （0=新第 1 步，原第 1 步顺延为第 2 步，依次后移）
     */
    suspend fun addStep(
        taskUuid: String,
        title: String,
        attrLabel: String = "",
        attrValue: String = "",
        insertAt: Int? = null
    ): Step {
        val t = now()
        val count = stepDao.countByTask(taskUuid)
        val pos = insertAt?.coerceIn(0, count) ?: count
        // 把 pos 及之后的步骤整体后移一位
        if (pos < count) {
            stepDao.shiftSortOrder(taskUuid, pos, 1, t)
        }
        val step = Step(
            uuid = newUuid(), taskUuid = taskUuid, title = title,
            attrLabel = attrLabel, attrValue = attrValue, sortOrder = pos,
            createdAt = t, updatedAt = t
        )
        stepDao.upsert(step)
        emitWithData(ChangeOp("upsert", "step", step.uuid))
        return step
    }

    suspend fun updateStep(step: Step) {
        val updated = step.copy(updatedAt = now())
        stepDao.upsert(updated)
        emitWithData(ChangeOp("upsert", "step", updated.uuid))
    }

    suspend fun deleteStep(uuid: String) {
        stepDao.softDelete(uuid, now())
        emitWithData(ChangeOp("delete", "step", uuid))
    }

    /** 步骤进度：已完成 / 总数 */
    suspend fun stepProgress(taskUuid: String): Pair<Int, Int> {
        val done = stepDao.countDoneByTask(taskUuid)
        val total = stepDao.countByTask(taskUuid)
        return done to total
    }

    // ==================== 延迟任务（任意量级：分钟/小时/天/月） ====================
    suspend fun delayTask(uuid: String, delayMillis: Long) {
        val task = taskDao.getByUuid(uuid) ?: return
        if (task.dueAt == null) return
        val t = now()
        val newDue = task.dueAt + delayMillis
        taskDao.upsert(task.copy(dueAt = newDue, delayedCount = task.delayedCount + 1, updatedAt = t))
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    // ==================== 仓库置顶/置底（把任务在今天/未来之间移动） ====================

    /** 置顶：把仓库（未来/非今天）任务移到主页——due_at 改为现在，主页今天可见 */
    suspend fun pinToHome(uuid: String) {
        val task = taskDao.getByUuid(uuid) ?: return
        if (task.trackStatus == TrackStatus.DONE) return
        val t = now()
        taskDao.upsert(task.copy(dueAt = t, updatedAt = t))
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    /** 置底：把通过置顶挪到主页的任务移回仓库——due_at 改为明天 9 点（非今天） */
    suspend fun unpinToRepo(uuid: String) {
        val task = taskDao.getByUuid(uuid) ?: return
        if (task.trackStatus == TrackStatus.DONE) return
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 9)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        taskDao.upsert(task.copy(dueAt = c.timeInMillis, updatedAt = now()))
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    // ==================== 习惯打卡 ====================
    suspend fun checkHabit(taskUuid: String, date: String): Boolean {
        if (habitDao.isChecked(taskUuid, date)) return false
        val log = HabitLog(taskUuid = taskUuid, checkDate = date, createdAt = now())
        habitDao.insert(log)
        emitWithData(ChangeOp("upsert", "habit", log.taskUuid))
        return true
    }

    /** v5.15.2：聚合版 —— 一次订阅所有 habit_logs → 每习惯 streak（HabitScreen 每行不再各自 Flow） */
    fun observeAllHabitStreaks(): Flow<Map<String, Int>> {
        return habitDao.observeAllHabitLogs().map { logs ->
            val byTask = logs.groupBy { it.taskUuid }
            byTask.mapValues { (_, taskLogs) ->
                val dates = taskLogs.map { it.checkDate }.sortedDescending()
                if (dates.isEmpty()) 0
                else {
                    val today = java.time.LocalDate.now()
                    var streak = 0
                    var cursor = today
                    for (dateStr in dates) {
                        val d = try { java.time.LocalDate.parse(dateStr) } catch (_: Exception) { continue }
                        if (d == cursor) { streak++; cursor = cursor.minusDays(1) }
                        else if (d.isBefore(cursor)) break
                    }
                    streak
                }
            }
        }
    }

    /** 同步查询某天是否已打卡（用于 UI 进入时初始化状态） */
    suspend fun isHabitCheckedToday(taskUuid: String, date: String): Boolean =
        habitDao.isChecked(taskUuid, date)

    /** 实时观察今日已打卡的 habit uuid 集合（点完卡后 Flow 立刻 emit → UI 实时更新） */
    fun observeTodayCheckedHabitUuids(): Flow<Set<String>> {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return habitDao.observeAllHabitLogs().map { logs ->
            val today = df.format(java.util.Date())
            logs.filter { it.checkDate == today }.map { it.taskUuid }.toSet()
        }
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

    /** 所有习惯里最长的连续坚持天数（"我的"页展示用） */
    suspend fun maxHabitStreak(): Int {
        val habits = taskDao.getAllHabits()
        if (habits.isEmpty()) return 0
        return habits.maxOf { habitStreak(it.uuid) }
    }

    // ==================== 设置 ====================
    suspend fun getTrackLimit(): Int =
        settingsDao.get("track_limit")?.toIntOrNull() ?: DEFAULT_TRACK_LIMIT

    fun observeTrackLimit(): Flow<Int> =
        settingsDao.observe("track_limit").map { it?.toIntOrNull() ?: DEFAULT_TRACK_LIMIT }

    suspend fun setTrackLimit(limit: Int) = settingsDao.set(
        com.taskbar.app.data.model.Setting("track_limit", limit.toString())
    )

    suspend fun getSetting(key: String, default: String = ""): String =
        settingsDao.get(key) ?: default

    /** 用户自定义分类列表（逗号分隔存 settings；学习/生活/锻炼为内置预设，不入此列表） */
    suspend fun getCustomCategories(): List<String> =
        settingsDao.get("category_list")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    suspend fun addCustomCategory(name: String) {
        val name2 = name.trim()
        if (name2.isEmpty()) return
        val cur = getCustomCategories()
        if (name2 in cur) return
        settingsDao.set(com.taskbar.app.data.model.Setting("category_list", (cur + name2).joinToString(",")))
    }

    /** 删除自定义分类（从 settings category_list 移除） */
    suspend fun removeCustomCategory(name: String) {
        val cur = getCustomCategories()
        if (name !in cur) return
        settingsDao.set(com.taskbar.app.data.model.Setting("category_list", (cur - name).joinToString(",")))
    }

    /** 观察设置值变化（Flow） */
    fun observeSetting(key: String): Flow<String?> = settingsDao.observe(key)

    suspend fun setSetting(key: String, value: String) = settingsDao.set(
        com.taskbar.app.data.model.Setting(key, value)
    )

    // ==================== 设置跨端同步（v5.15.7） ====================

    /** 某设置项的同步时间戳（"最新为主"依据） */
    suspend fun settingTs(key: String): Long =
        settingsDao.get("sync_ts_$key")?.toLongOrNull() ?: 0L

    /**
     * 本地修改设置：写值 + 打时间戳 + 推给电脑端。
     * 值没变则直接返回（避免无意义的推送和"假更新"时间戳盖掉远端的真实修改）。
     */
    suspend fun setSettingSynced(key: String, value: String, push: Boolean = true) {
        if (settingsDao.get(key) == value) return
        val ts = now()
        settingsDao.set(com.taskbar.app.data.model.Setting(key, value))
        settingsDao.set(com.taskbar.app.data.model.Setting("sync_ts_$key", ts.toString()))
        if (push) {
            ChangeBus.tryEmit(ChangeOp("upsert", "setting", key,
                syncJson.encodeToString(SettingChange.serializer(), SettingChange(key, value, ts))
            ))
        }
    }

    /** 收到电脑端的设置变更：远端时间戳不旧于本地才覆盖（last-write-wins） */
    suspend fun applySettingFromSync(key: String, value: String, ts: Long) {
        if (ts < settingTs(key)) return
        settingsDao.set(com.taskbar.app.data.model.Setting(key, value))
        settingsDao.set(com.taskbar.app.data.model.Setting("sync_ts_$key", ts.toString()))
    }

    /** 积分变化（完成任务/恢复任务）：写值 + 打时间戳 + 推电脑端 */
    private suspend fun bumpPoints(delta: Int, absolute: Int? = null) {
        val next = absolute ?: ((getTotalPoints() + delta).coerceAtLeast(0))
        setSettingSynced("total_points", next.toString())
    }

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
        // v5.15.7：设置也全量带上（积分/等级、头像…），sync_ts_* 内部行不外发
        val all = settingsDao.getAll()
        val tsMap = all.filter { it.key.startsWith("sync_ts_") }
            .associate { it.key.removePrefix("sync_ts_") to (it.value.toLongOrNull() ?: 0L) }
        val settings = all.filterNot { it.key.startsWith("sync_ts_") }
            .map { SettingKV(it.key, it.value, tsMap[it.key] ?: 0L) }
        return FullSyncPayload(tasks, steps, habits, settings, System.currentTimeMillis())
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
        // v5.15.7 P0：Room 的 @Upsert 按自增主键 id 定位行（不是 uuid 唯一索引）。
        //   远端 JSON 里没有本地 id（=0），直接 upsert 会 INSERT 撞 uuid 唯一索引 → IGNORE，
        //   再按 id=0 UPDATE 又找不到行 → 静默什么都不做（手机端永远收不到电脑端的状态）。
        //   这里显式沿用本地行的 id。
        taskDao.upsert(if (local != null) task.copy(id = local.id) else task.copy(id = 0))
    }

    suspend fun upsertStepFromSync(step: Step) {
        val local = stepDao.getByUuid(step.uuid)
        if (local != null && local.updatedAt > step.updatedAt) return
        // 同上：保留本地 id，否则远端步骤变更永远落不了库
        stepDao.upsert(if (local != null) step.copy(id = local.id) else step.copy(id = 0))
    }

    suspend fun applyChange(change: ChangeOp) {
        when (change.op) {
            "upsert" -> when (change.entity) {
                "task" -> change.data?.let {
                    // 用宽松 Json（ignoreUnknownKeys）：桌面端将来加字段也不会让整批同步 500
                    val t = syncJson.decodeFromString<Task>(it)
                    upsertTaskFromSync(t)
                }
                "step" -> change.data?.let {
                    val s = syncJson.decodeFromString<Step>(it)
                    upsertStepFromSync(s)
                }
                "habit" -> change.data?.let {
                    // v5.15.7：电脑端打卡 → 写 habit_logs（幂等，已存在则忽略）
                    runCatching {
                        val h = syncJson.decodeFromString<HabitLog>(it)
                        habitDao.insert(h.copy(id = 0))
                    }
                }
                // v5.15.7：电脑端的设置变更（积分/等级、头像、昵称…）→ 最新为主
                "setting" -> change.data?.let {
                    runCatching {
                        val sc = syncJson.decodeFromString<SettingChange>(it)
                        applySettingFromSync(sc.key, sc.value, sc.updatedAt)
                    }
                }
            }
            "delete" -> when (change.entity) {
                "task" -> taskDao.softDelete(change.uuid, now())
                "step" -> stepDao.softDelete(change.uuid, now())
            }
        }
    }

    /**
     * v5.15.7 P0：推送变更时必须带上实体完整数据。
     * 之前只发 {op,entity,uuid} 不带 data，桌面端 apply_change 里 `if let Some(d) = &op.data`
     * 直接跳过 → 手机上完成任务/开始追踪，电脑端永远不动（boss 反馈的"双端数据差很多"根因之一）。
     */
    private suspend fun emitWithData(op: ChangeOp) {
        ChangeBus.tryEmit(withData(op))
    }

    private suspend fun withData(op: ChangeOp): ChangeOp {
        if (op.op == "delete" || op.data != null) return op
        return when (op.entity) {
            "task" -> taskDao.getByUuid(op.uuid)?.let {
                op.copy(data = syncJson.encodeToString(Task.serializer(), it))
            } ?: op
            "step" -> stepDao.getByUuid(op.uuid)?.let {
                op.copy(data = syncJson.encodeToString(Step.serializer(), it))
            } ?: op
            "habit" -> habitDao.getByTask(op.uuid).maxByOrNull { it.createdAt }?.let {
                op.copy(data = syncJson.encodeToString(HabitLog.serializer(), it))
            } ?: op
            "setting" -> {
                val v = settingsDao.get(op.uuid) ?: return op
                val ts = settingsDao.get("sync_ts_${op.uuid}")?.toLongOrNull() ?: now()
                op.copy(data = syncJson.encodeToString(
                    SettingChange.serializer(), SettingChange(op.uuid, v, ts)
                ))
            }
            else -> op
        }
    }
}
