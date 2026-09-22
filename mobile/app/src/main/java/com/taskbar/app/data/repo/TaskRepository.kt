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
import com.taskbar.app.data.model.TrackingInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 变更总线：Repository 写操作后发射，服务器层订阅并推送给电脑端 */
object ChangeBus {
    // v5.26.0（boss：「手机上点了完成健身任务 电脑端还是没有完成 过了大概两分钟才同步上来 太慢了」）——
    //   根因：原来 replay = 0 → **没人在听的时候事件直接消失**。
    //   桌面端的订阅者是在 WS 连上那一刻才建立的；只要那一刻 WS 没连（重连间隙、刚开机、
    //   网络抖动），这条变更就**永久丢失** —— 只能等桌面端下次重连做全量拉取才收敛，
    //   这正是"要等两分钟"的来源（15s 轮询拉的是桌面本地库，拉不到手机上的新变更）。
    //   加 replay：WS 重连后自动补发最近 256 条，桌面端 apply_change 是 LWW 幂等的，重放无害。
    private val _events = MutableSharedFlow<ChangeOp>(
        replay = 256,
        extraBufferCapacity = 128
    )
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

    /** v5.18.4：提醒调度专用（不过滤已完成）—— 见 TaskDao.observeRemindable 的说明 */
    fun observeRemindable(): Flow<List<Task>> = taskDao.observeRemindable()

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
                // v5.24.0：**「每周一三五 / 隔天」的任务不该天天出现在今日待办里**。
                //   v5.23.0 已让提醒按星期算准，但列表 SQL 不看 weekday →
                //   设了"每周三"的任务周一到周日都躺在今日（多份体验测试报告一致命中）。
                //   SQL 里算星期几不划算，这里在内存里过滤（列表规模很小）。
                .filter { shouldShowOnDay(it, dayStart) }
        }
    }

    /**
     * v5.24.0：按 repeatRule 判断这条任务"今天该不该出现在今日列表"。
     * 只对 `weekly:…` / `everyNd` 生效；其它规则（daily / 无规则）一律照常显示。
     */
    internal fun shouldShowOnDay(t: Task, dayStart: Long): Boolean {
        val rule = t.repeatRule ?: return true
        if (!rule.startsWith("weekly:") && !rule.startsWith("every")) return true
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
        // Calendar：周日=1…周六=7 → 本项目规则 1=周一…7=周日
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val iso = if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
        if (rule.startsWith("weekly:")) {
            val days = rule.removePrefix("weekly:").split(",")
                .mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
            return days.isEmpty() || iso in days
        }
        val n = rule.removePrefix("every").removeSuffix("d").toIntOrNull() ?: return true
        if (n <= 1) return true
        val base = t.dueAt ?: return true
        val baseDay = java.util.Calendar.getInstance().apply {
            timeInMillis = base
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val diffDays = (dayStart - baseDay) / 86_400_000L
        return diffDays >= 0 && diffDays % n == 0L
    }

    /** v5.15.22：今天 0 点（毫秒）—— 每日任务跨天折算的基准（completeTask 也要用同一基准） */
    private fun todayStart(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 跨天的每日型任务：在返回给 UI 之前折算成"今天的待办"（不落库）
     *  v5.15.23 C-006（boss：习惯/每日任务"完成不掉"）——
     *  原来只认 `category == "daily"`，但习惯任务从电脑端建的时候 category 是自定义分类
     *  （'锻炼'/'学习'/'生活'…）→ 这类习惯**永远不折算**：完成后卡在 done 态，
     *  主页/仓库里想再点一次完成会被 `trackStatus == DONE` 静默拦掉（表现为"点了去不掉"）。
     *  统一判定：`category == "daily"` 或 `type == HABIT` 都算每日型。 */
    private fun isDailyType(t: Task): Boolean = t.category == "daily" || t.type == TaskType.HABIT

    private fun normalizeDailyReset(t: Task, dayStart: Long): Task {
        if (!isDailyType(t) || t.trackStatus != TrackStatus.DONE) return t
        val doneToday = (t.doneAt ?: 0L) >= dayStart
        if (doneToday) return t
        return t.copy(
            trackStatus = TrackStatus.PENDING,
            done = 0,
            doneAt = null
        )
    }
    fun observeTracking(): Flow<List<Task>> = taskDao.observeTracking()
    /** v5.15.22 M3：归档 = 已完成 ∪ 已逾期未完成（含往期没打卡的每日任务） */
    fun observeArchive(): Flow<List<Task>> = taskDao.observeArchiveWithOverdue(todayStart())
    /** v5.15.22 M3：全部打卡日志（历史页按天展开"哪天打卡了/哪天漏了"用） */
    fun observeAllHabitLogs(): Flow<List<HabitLog>> = habitDao.observeAllHabitLogs()
    /** v5.15.23 M6/M7：仓库视图的全部任务（所有任务页 / 日历 / 分类筛选统一数据源）——
     *  逐行做每日型折算，跨天的每日任务仍以"今天的待办"呈现。 */
    fun observeAllForWarehouse(): Flow<List<Task>> = taskDao.observeAllNonDeleted().map { list ->
        val dayStart = todayStart()
        list.map { normalizeDailyReset(it, dayStart) }
    }

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

    // ==================== 通知栏追踪卡（v5.18.0）====================

    /**
     * 追踪状态或步骤变化 → 通知栏内容。
     *
     * 订阅两个 Flow 的理由：通知正文写的是**当前步骤**，
     * 所以推进步骤（step 变化）也必须触发刷新，不能只盯 task.track_status。
     */
    fun observeTrackingFeed(): Flow<TrackingInfo> =
        combine(taskDao.observeTracking(), stepDao.observeAll()) { tasks, _ -> tasks }
            .map { tasks -> buildTrackingInfo(tasks) }

    /** 一次性快照（服务冷启动时用；也用于 app 启动时把状态直接传给 SyncService） */
    suspend fun trackingSnapshot(): TrackingInfo = buildTrackingInfo(taskDao.getTracking())

    private suspend fun buildTrackingInfo(tasks: List<Task>): TrackingInfo {
        if (tasks.isEmpty()) return TrackingInfo.NONE
        // 取最近更新的那个追踪任务作为通知主体
        val first = tasks.first()
        val steps = stepDao.getByTask(first.uuid)
        val cur = steps.firstOrNull { it.status == StepStatus.DOING }
            ?: steps.firstOrNull { it.status != StepStatus.DONE }
        val step = if (cur == null) "" else {
            val idx = steps.indexOfFirst { it.uuid == cur.uuid } + 1
            "$idx/${steps.size} · ${cur.title}"
        }
        return TrackingInfo(title = first.title, step = step, count = tasks.size)
    }

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
        target: Int = 1,
        remindAheadMin: Int = 0
    ): Task {
        val t = now()
        // v5.15.21 P2：次数任务（category=once）与里程碑共用 target 承载"次数"。
        //   旧实现 `if (type == MILESTONE)` 只给里程碑保留 target → **次数任务的次数被丢掉**
        //   （手机端新建「喝水 8 次」存进去只剩 1 次，与电脑端不一致）。
        val cnt = if (type == com.taskbar.app.data.model.TaskType.MILESTONE || category == "once")
            target.coerceAtLeast(1) else 1
        // 积分按类型+优先级规则计算（RewardRules）；次数任务（cnt>1）按 v5.15.21 P2 降分
        val task = Task(
            uuid = newUuid(), type = type, title = title, desc = desc,
            category = category, priority = priority, dueAt = dueAt,
            repeatRule = repeatRule, deadline = deadline,
            trackStatus = TrackStatus.PENDING,
            rewardPoints = com.taskbar.app.data.model.RewardRules.forTask(type, priority, cnt),
            reminderStrength = reminderStrength,
            progress = 0, target = cnt,
            remindAheadMin = remindAheadMin.coerceIn(0, 7 * 24 * 60),
            createdAt = t, updatedAt = t
        )
        taskDao.upsert(task)
        emitWithData(ChangeOp("upsert", "task", task.uuid))
        return task
    }

    suspend fun updateTask(task: Task) {
        // 编辑时同步按当前类型/优先级重算积分，保证规则一致；次数任务（target>1）按 P2 降分
        val recalculated = task.copy(
            rewardPoints = com.taskbar.app.data.model.RewardRules.forTask(task.type, task.priority, task.target),
            updatedAt = now()
        )
        taskDao.upsert(recalculated)
        emitWithData(ChangeOp("upsert", "task", recalculated.uuid))
    }

    suspend fun deleteTask(uuid: String) {
        val t = now()
        // v5.18.1：步骤的删除要**逐条推** —— 原来只推了任务的 delete，
        //   电脑端那批步骤仍是 deleted=0（孤儿步骤），只能等下次全量同步才被覆盖。
        val steps = stepDao.getByTask(uuid)
        taskDao.softDelete(uuid, t)
        // 关联步骤软删除
        steps.forEach {
            stepDao.softDelete(it.uuid, t)
            emitWithData(ChangeOp("delete", "step", it.uuid))
        }
        emitWithData(ChangeOp("delete", "task", uuid))
    }

    /**
     * v5.15.24 F11（调研 3.2 可考虑：批量操作后滑入式撤销）——
     * 撤销批量删除：把刚软删掉的任务/步骤原样恢复。
     * 只恢复**传入的这一批 uuid**（不牵连其他），保证撤销不会误伤。
     */
    suspend fun undeleteTasks(uuids: List<String>) {
        if (uuids.isEmpty()) return
        val t = now()
        for (u in uuids) {
            taskDao.undelete(u, t)
            stepDao.getByTask(u).forEach { stepDao.undelete(it.uuid, t) }
            emitWithData(ChangeOp("upsert", "task", u))
        }
    }

    // ==================== 追踪状态机 ====================
    /**
     * 开始追踪：
     * 1. 若已达上限 → 返回 false（不挤掉旧任务，由 UI 提示用户先取消别的追踪或调高上限）
     * 2. 当前任务转 tracking
     * 3. 第一个 todo 步骤转 doing（若有步骤）
     */
    suspend fun startTracking(uuid: String): Boolean {
        // v5.26.0（boss：「为什么我可以追踪已经完成的任务」）——
        //   原来这里只看追踪上限，**不校验任务本身的状态** → 已完成/已归档的任务
        //   点「追踪」照样进追踪列表，然后就是"点完成没反应、点取消追踪才回去"的死循环。
        val target = taskDao.getByUuid(uuid)
        if (target == null) return false
        if (target.done == 1 || target.trackStatus == TrackStatus.DONE) return false
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
        val raw = taskDao.getByUuid(uuid) ?: return
        val t = now()
        // v5.15.22 M10 修复（boss：「学英语10分钟」点完成只弹积分、任务还挂着）——
        //   根因：每日任务（category=daily）跨天后，normalizeDailyReset 只在**读取时**把它折算回
        //   "今天的待办"（不落库），所以 DB 里它仍是 DONE；直接走下面的
        //   `if (trackStatus == DONE) return` 会**静默提前返回**（不加积分、不改状态），
        //   而 VM 的弹窗是无条件设置的 → 用户看到"弹了积分但任务没动"。
        //   这里先把 daily 任务按同一条规则折算回未完成，再走正常完成流程。
        val task = normalizeDailyReset(raw, todayStart())
        // v5.22.5：次数任务"每推进一次给多少分"（默认 0 = 走原来的整笔发放）
        var grantPoints = 0
        if (task.type == TaskType.HABIT) {
            // 习惯：今日已打卡则不再加分；写 habit_log + 加积分，任务保持非 done
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(t))
            val already = habitDao.isChecked(uuid, date)
            if (!already) {
                habitDao.insert(HabitLog(taskUuid = uuid, checkDate = date, createdAt = t))
                emitWithData(ChangeOp("upsert", "habit", uuid))
            }
            // v5.15.23 C-006（boss：「这些任务都完成不掉，点了但是去不掉」）——
            //   根因：习惯完成只写 log、**不解除追踪态**；下次再点完成时 `isChecked → return`
            //   静默返回 → 任务永远赖在"追踪中"，而追踪页/仓库给的完成键怎么点都没反应。
            //   修法：打卡即代表这一轮结束 → 顺手把追踪态落回待办（幂等，不改积分）。
            if (task.trackStatus == TrackStatus.TRACKING) {
                taskDao.upsert(task.copy(trackStatus = TrackStatus.PENDING, updatedAt = t))
                emitWithData(ChangeOp("upsert", "task", uuid))
            }
            if (already) return
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
        } else if (task.target > 1) {
            // ⭐ v5.22.5（24 份代入式体验测试里，这条被 19 份独立命中）：**次数任务原来点一次就归档**。
            //   「喝水 8 次」「跑够 20 单」「今天 20 组」全都做不到 —— 点一下直接完成，
            //   而积分是按"多次"算完再减半的值一次性发掉，用户还觉得自己白干一半。
            //   改为累计推进：每推进一次给该次份额，满次数才归档、末次补齐余数
            //   （总额仍等于 rewardPoints，**不留刷分空间**）。
            if (task.trackStatus == TrackStatus.DONE) return
            val newProgress = task.progress + 1
            val total = task.rewardPoints.coerceAtLeast(1)
            val per = (total / task.target).coerceAtLeast(1)
            grantPoints = if (newProgress >= task.target)
                (total - per * (task.target - 1)).coerceAtLeast(1)
            else per
            if (newProgress >= task.target) {
                taskDao.upsert(task.copy(progress = newProgress, trackStatus = TrackStatus.DONE, done = 1, doneAt = t, updatedAt = t))
            } else {
                taskDao.upsert(task.copy(progress = newProgress, updatedAt = t))
            }
        } else {
            // 普通任务：归档 + 加积分
            if (task.trackStatus == TrackStatus.DONE) return
            taskDao.upsert(task.copy(trackStatus = TrackStatus.DONE, done = 1, doneAt = t, updatedAt = t))
        }
        // 积分奖励（v5.15.7：写值 + 打时间戳 + 推电脑端，双端"最新为主"一致）
        bumpPoints(if (grantPoints > 0) grantPoints else task.rewardPoints)
        // 步骤全 done（习惯/普通都一样）
        stepDao.getByTask(uuid).filter { it.status != StepStatus.DONE }.forEach {
            stepDao.updateStatus(it.uuid, StepStatus.DONE, t, t)
            emitWithData(ChangeOp("upsert", "step", it.uuid))
        }
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    /** 从归档恢复（取消完成）—— 同步扣回完成任务时奖励的积分 */
    /**
     * 从归档恢复（取消完成）—— 同步扣回完成任务时奖励的积分。
     * v5.15.23 M10（boss：「逾期任务也可以恢复，但是积分减半，不足一分按一分算」）——
     * @param overdueHalf true = 这是"逾期未完成"的任务（本来就没给过分），
     *   恢复代价按奖励的一半扣（至少 1 分），比"已完成再恢复"轻一档。
     */
    suspend fun restoreTask(uuid: String, overdueHalf: Boolean = false) {
        val task = taskDao.getByUuid(uuid) ?: return
        val t = now()
        taskDao.upsert(task.copy(trackStatus = TrackStatus.PENDING, done = 0, doneAt = null, updatedAt = t))
        // 扣积分（防刷分：完成→恢复→完成 来回刷）+ 推电脑端
        val cost = if (overdueHalf) maxOf(1, task.rewardPoints / 2) else task.rewardPoints
        bumpPoints(-cost)
        emitWithData(ChangeOp("upsert", "task", uuid))
    }

    /** v5.15.23 C-006：一次性修复"今天已打卡、却还挂在追踪中"的习惯
     *  （这类行会让完成键点了没反应 —— boss 的「完成不掉」）。
     *  修完自动推给电脑端，双端一起干净。 */
    suspend fun repairStuckTrackedHabits() {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = df.format(java.util.Date())
        val stuck = taskDao.getAllNonDeleted().filter {
            it.type == TaskType.HABIT &&
                it.trackStatus == TrackStatus.TRACKING &&
                habitDao.isChecked(it.uuid, today)
        }
        if (stuck.isEmpty()) return
        val t = now()
        stuck.forEach { h ->
            taskDao.upsert(h.copy(trackStatus = TrackStatus.PENDING, updatedAt = t))
            emitWithData(ChangeOp("upsert", "task", h.uuid))
        }
    }

    // ==================== v5.15.24 F1/F6：习惯统计（连续天数 + 强度分） ====================
    /**
     * v5.15.24 F6（调研专题三：streak 边界规则必须**显式化**）——
     * 全项目**唯一**的连续天数算法，三处调用点共用这一份。
     * 旧代码有 3 份各自实现且规则不同（只有 1 份在 v5.15.23 修过）→ 同一习惯在
     * 主页/所有任务页/我的页可能显示不同数字，属真实 bug 面。
     *
     * 规则（写死，不再各处自由发挥）：
     *  1. 锚点 = 今天（今天已打卡）或 昨天（今天还没打卡 → **今天没打卡不算断链**）；
     *     连昨天都没有 → 0（真断了）。
     *  2. 从锚点往前逐日递减计数，遇到缺失日立即停止。
     *  3. 跨日以本机本地日期 0 点为界（跨午夜未打卡按"当天尚未结算"处理）。
     */
    private fun streakOf(
        dateStrs: List<String>,
        today: java.time.LocalDate = java.time.LocalDate.now()
    ): Int {
        val dates = dateStrs
            .mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            .distinct()
            .sortedDescending()
        if (dates.isEmpty()) return 0
        var cursor = when (dates.first()) {
            today -> today
            today.minusDays(1) -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        for (d in dates) {
            if (d == cursor) { streak++; cursor = cursor.minusDays(1) }
            else if (d.isBefore(cursor)) break
        }
        return streak
    }

    /**
     * v5.15.24 F1（调研 3.1 强烈建议：Loop Habit Tracker 的 Habit Score）——
     * 习惯**强度分** 0..100（指数衰减累计）：
     *   每打卡一次 +1；两次打卡之间每空一天 ×0.9；到今天仍未打卡也结算一次。
     * 与 streak 并列展示：断一天只让强度缓降，不会像 streak 那样直接归零，
     * 缓解"一次失误就放弃"的心理崩塌（调研原文的动机）。
     *
     * 纯计算、**不落库、不加同步字段** → 零 schema / 零同步协议风险。
     * 极限 1/(1-0.9) = 10 → 映射 100%。
     */
    fun habitStrength(
        dateStrs: List<String>,
        today: java.time.LocalDate = java.time.LocalDate.now()
    ): Int {
        val dates = dateStrs
            .mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            .distinct()
            .sorted()
        if (dates.isEmpty()) return 0
        val decay = 0.9
        var score = 0.0
        var prev: java.time.LocalDate? = null
        for (d in dates) {
            if (prev != null) {
                val gap = java.time.temporal.ChronoUnit.DAYS.between(prev, d).coerceAtLeast(1)
                if (gap > 1) score *= Math.pow(decay, (gap - 1).toDouble())
            }
            score += 1.0
            prev = d
        }
        val gapToToday = java.time.temporal.ChronoUnit.DAYS.between(prev!!, today)
        if (gapToToday > 0) score *= Math.pow(decay, gapToToday.toDouble())
        return ((score / 10.0) * 100).toInt().coerceIn(0, 100)
    }

    /** 全部习惯的 连续天数 + 强度分（一次订阅全部日志 → 每行不再各自建 Flow） */
    fun observeAllHabitStats(): Flow<Map<String, HabitStat>> =
        habitDao.observeAllHabitLogs().map { logs ->
            val today = java.time.LocalDate.now()
            logs.groupBy { it.taskUuid }.mapValues { (_, l) ->
                val ds = l.map { it.checkDate }
                HabitStat(streak = streakOf(ds, today), strength = habitStrength(ds, today))
            }
        }

    /** 实时观察习惯连续天数（Flow 驱动，打卡后自动刷新；算法统一走 streakOf） */
    fun observeHabitStreak(taskUuid: String): Flow<Int> =
        habitDao.observeCheckDatesByTask(taskUuid).map { streakOf(it) }

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

    /** v5.15.2：聚合版 —— 一次订阅所有 habit_logs → 每习惯 streak（算法统一走 streakOf） */
    fun observeAllHabitStreaks(): Flow<Map<String, Int>> =
        observeAllHabitStats().map { m -> m.mapValues { it.value.streak } }

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

    suspend fun habitStreak(taskUuid: String): Int =
        streakOf(habitDao.getByTask(taskUuid).map { it.checkDate })

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

    // ==================== 本地备份导入（v5.25.0） ====================

    /**
     * v5.25.0：导入本地备份。
     *
     * 欠账来源：多轮体验测试反复点名「只能导出、不能导入」——
     *   换手机 / 重装 = 数据全丢，且 App 认不出你是老用户（等级、连续天数一起归零）。
     *
     * 语义：**合并**，不覆盖本机更新的内容（同 uuid 以 updated_at 新者胜）。
     *   - 不写 `auth_secret`（本机配对密钥不能被别人的备份顶掉）
     *   - `total_points` 只增不减（避免「恢复备份反而掉级」）
     *   - 习惯打卡按 (task_uuid, check_date) 去重，不会重复计数
     *
     * @return Pair(新增条数, 更新条数)
     */
    suspend fun importFullSyncPayload(p: FullSyncPayload): Pair<Int, Int> {
        var added = 0
        var updated = 0

        val localTasks = taskDao.getAllNonDeleted().associateBy { it.uuid }
        val newTasks = mutableListOf<Task>()
        val updTasks = mutableListOf<Task>()
        p.tasks.forEach { t ->
            val cur = localTasks[t.uuid]
            when {
                cur == null -> { newTasks.add(t); added++ }
                t.updatedAt > cur.updatedAt -> { updTasks.add(t); updated++ }
            }
        }
        if (newTasks.isNotEmpty()) taskDao.upsertAll(newTasks)
        if (updTasks.isNotEmpty()) taskDao.upsertAll(updTasks)

        val localSteps = stepDao.getAllNonDeleted().associateBy { it.uuid }
        val newSteps = mutableListOf<Step>()
        val updSteps = mutableListOf<Step>()
        p.steps.forEach { st ->
            val cur = localSteps[st.uuid]
            when {
                cur == null -> { newSteps.add(st); added++ }
                st.updatedAt > cur.updatedAt -> { updSteps.add(st); updated++ }
            }
        }
        if (newSteps.isNotEmpty()) stepDao.upsertAll(newSteps)
        if (updSteps.isNotEmpty()) stepDao.upsertAll(updSteps)

        // 习惯打卡：DAO 是 OnConflictStrategy.IGNORE，已存在的同一天不会重复计数
        p.habit_logs.forEach { habitDao.insert(it) }

        p.settings
            .filterNot { it.key == "auth_secret" || it.key.startsWith("sync_ts_") }
            .forEach { kv ->
                if (kv.key == "total_points") {
                    val cur = settingsDao.get("total_points")?.toIntOrNull() ?: 0
                    val inc = kv.value.toIntOrNull() ?: 0
                    if (inc > cur) settingsDao.set(com.taskbar.app.data.model.Setting("total_points", inc.toString()))
                } else {
                    settingsDao.set(com.taskbar.app.data.model.Setting(kv.key, kv.value))
                }
            }

        return added to updated
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
                "task" -> {
                    taskDao.softDelete(change.uuid, now())
                    // v5.18.1：级联软删步骤（兜底"只推了 task delete"的对端）
                    val t = now()
                    stepDao.getByTask(change.uuid).forEach { stepDao.softDelete(it.uuid, t) }
                }
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

/**
 * v5.15.24 F1：习惯统计（连续天数 + 强度分）。
 * 顶层数据类，仅用于仓库层 → UI 的传递，**不落库**。
 */
data class HabitStat(val streak: Int, val strength: Int)
