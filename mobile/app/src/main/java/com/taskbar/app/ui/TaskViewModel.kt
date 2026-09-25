package com.taskbar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskbar.app.TaskBarApp
import com.taskbar.app.billing.License
import com.taskbar.app.data.model.DEFAULT_TRACK_LIMIT
import com.taskbar.app.data.model.HabitLog
import com.taskbar.app.data.model.TrackStartResult
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.repo.HabitStat
import com.taskbar.app.data.repo.TaskRepository
import com.taskbar.app.widget.TrackWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /** v5.15.22 M3：全部打卡日志（历史页按天展开用） */
    val habitLogs: StateFlow<List<HabitLog>> = repo.observeAllHabitLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** v5.15.23 M6/M7：仓库视图全部任务（所有任务页 + 日历 + 分类筛选） */
    val allForWarehouse: StateFlow<List<Task>> = repo.observeAllForWarehouse()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val habits: StateFlow<List<Task>> = repo.observeHabits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 未来任务（明天及以后到期） */
    val futureTasks: StateFlow<List<Task>> = repo.observeFutureTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** v5.21.0：买断状态（授权码离线验签，不需要联网） */
    private val _isPro = MutableStateFlow(License.isPro(ctx))
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    /** 设置里存的那个上限值（买断用户可自由调 1~10） */
    private val settingsTrackLimit: StateFlow<Int> = repo.observeTrackLimit()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_TRACK_LIMIT)

    /**
     * v5.21.0：**生效的**同时追踪上限。
     *   未买断 → 免费额度（[License.FREE_TRACK_LIMIT]，现为 1）
     *   已买断 → 设置里的值（默认 3，可调 1~10）
     * 这样"同时追踪上限"就是买断的权益点，而**任务数量仍然不限**。
     */
    val trackLimit: StateFlow<Int> =
        combine(settingsTrackLimit, _isPro) { limit, pro ->
            if (pro) limit else License.FREE_TRACK_LIMIT
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), License.FREE_TRACK_LIMIT)

    /** v5.21.0：兑换授权码；成功即刷新买断状态（返回 null = 码无效） */
    fun redeemLicense(code: String): License.Info? {
        val info = License.redeem(ctx, code)
        _isPro.value = info != null
        return info
    }

    /** v5.21.0：当前已激活的信息（未激活给 null） */
    fun licenseInfo(): License.Info? = License.info(ctx)

    /** 总积分（积分系统） */
    val totalPoints: StateFlow<Int> = repo.observeSetting("total_points")
        .map { it?.toIntOrNull() ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** v5.15.7：桌面端自定义头像（base64 data URL）—— 跨端同步过来后手机直接显示 */
    val avatarImg: StateFlow<String?> = repo.observeSetting("avatar_img")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * v5.15.7：本地改设置并推给电脑端（如手机换 emoji 头像 → 桌面同步显示同款 emoji）。
     * 值未变化时不推（repo 内部已判重）。
     */
    fun setSyncedSetting(key: String, value: String) = viewModelScope.launch {
        repo.setSettingSynced(key, value)
    }

    // ===== 完成庆祝事件（游戏化正反馈：弹层显示积分/升级） =====
    data class CompletionEvent(
        val title: String,
        val points: Int,
        val newLevel: Int? = null,
        val newLevelName: String? = null,
        /** v5.15.21 P1（boss：次数任务完成后只弹一个**不用点击**的积分提示）——
         *  次数任务完成频繁，用无遮罩、自动消失的轻提示取代大弹窗。 */
        val lightweight: Boolean = false
    )
    private val _completion = MutableStateFlow<CompletionEvent?>(null)
    val completion: StateFlow<CompletionEvent?> = _completion.asStateFlow()
    fun consumeCompletion() { _completion.value = null }

    fun steps(taskUuid: String) = repo.observeSteps(taskUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 所有步骤聚合为 Map<taskUuid, List<Step>>（性能优化：主页 TaskRow 从 Map 查
     * 步骤而非各自订阅 Flow，9 任务从 9 个 Flow → 1 个 Flow）
     */
    val stepsByUuid: StateFlow<Map<String, List<Step>>> = repo.observeAllSteps()
        .map { it.groupBy { step -> step.taskUuid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * v5.22.5（体验测试最高频问题）：**任务增删改之后立刻重排这一条的提醒**。
     *
     *   原来全项目的排程入口只有三处：冷启动（TaskBarApp.onCreate）、开机（BootReceiver）、
     *   上一条闹钟响过（ReminderAlarmReceiver）。而手机的同步前台服务常驻、进程不死，
     *   于是「新建任务当晚不响」「把 20:00 改成 22:00 之后旧闹钟照响、新时间不响」
     *   「删掉的任务到点还弹」——24 份报告里有 79 条指向这个链路。
     *
     *   单条重排而不是全量 rescheduleAll：后者会顺带触发"已过期未完成 → 立即提醒"，
     *   每次点一下按钮就弹一堆通知反而更糟。
     */
    private fun rescheduleOne(uuid: String, removed: Boolean = false) = viewModelScope.launch {
        val ctx = getApplication<android.app.Application>()
        runCatching {
            com.taskbar.app.notify.ReminderScheduler.cancel(ctx, uuid)
            if (removed) return@runCatching
            val t = repo.observeTask(uuid).first() ?: return@runCatching
            if (t.deleted != 0) return@runCatching
            // v5.27.0：负责人路由 —— 派给别人的任务不在本机排（rescheduleAll/fireNow 已同判，
            // 这里是写操作后的单任务重排路径，漏了会把别人名下的任务排回本机）
            val ownerTrim = t.owner.trim()
            if (ownerTrim.isNotEmpty() && ownerTrim != repo.selfName()) return@runCatching
            val due = t.dueAt ?: return@runCatching
            val now = System.currentTimeMillis()
            val daily = com.taskbar.app.notify.ReminderScheduler.isDailyKindForReminder(t)
            // 已完成：非每日型直接不排；每日型只有"今天已完成"才跳过今天（明天还要响）
            if (t.trackStatus == com.taskbar.app.data.model.TrackStatus.DONE) {
                if (!daily) return@runCatching
                val today0 = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                if ((t.doneAt ?: 0L) >= today0) {
                    // 今天已经打过卡 → 排到下一次（明天/下一个周期日）
                }
            }
            val strength = t.reminderStrength ?: ctx
                .getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE)
                .getString("reminder_strength", "notify") ?: "notify"
            val next = if (daily)
                com.taskbar.app.notify.ReminderScheduler.nextOccurrenceFor(due, t.repeatRule, now)
            else due
            // v5.24.0：提前提醒（与 rescheduleAll 同一口径）
            val fireAt = next - (t.remindAheadMin.coerceAtLeast(0) * 60_000L)
            if (fireAt > now) com.taskbar.app.notify.ReminderScheduler.schedule(ctx, uuid, fireAt, strength)
        }
    }

    // ===== 任务操作 =====
    fun createTask(
        type: String, title: String, desc: String, category: String,
        priority: String, dueAt: Long?, repeatRule: String?, deadline: Long?,
        reminderStrength: String? = null, target: Int = 1, remindAheadMin: Int = 0,
        owner: String = "",
        onCreated: (String) -> Unit = {}
    ) = viewModelScope.launch {
        val task = repo.createTask(type, title, desc, category, priority, dueAt, repeatRule, deadline,
            reminderStrength = reminderStrength, target = target, remindAheadMin = remindAheadMin,
            owner = owner)
        refreshWidget()
        rescheduleOne(task.uuid)
        onCreated(task.uuid)
    }

    fun updateTask(task: Task) = viewModelScope.launch {
        repo.updateTask(task)
        refreshWidget()
        rescheduleOne(task.uuid)          // 改了时间/提醒方式要立刻生效（否则旧闹钟照响）
    }

    fun deleteTask(uuid: String) = viewModelScope.launch {
        repo.deleteTask(uuid)
        refreshWidget()
        rescheduleOne(uuid, removed = true)   // 删了就别再响（原来删掉的任务到点还会弹）
    }

    // ===== v5.15.23 M11：多选批量操作 =====
    /** 批量取消（删除）任务 */
    fun deleteTasks(uuids: List<String>) = viewModelScope.launch {
        uuids.forEach { repo.deleteTask(it) }
        refreshWidget()
        uuids.forEach { rescheduleOne(it, removed = true) }
    }

    /** 批量恢复到待办；overdueHalf=true 时按"逾期恢复"规则扣半分（至少 1 分） */
    fun restoreTasks(uuids: List<String>, overdueHalf: Boolean = false) = viewModelScope.launch {
        uuids.forEach { repo.restoreTask(it, overdueHalf) }
        refreshWidget()
        uuids.forEach { rescheduleOne(it) }
    }

    /**
     * v5.15.24 F11：撤销批量删除（Undo 用）。
     * 只恢复传入的这一批 uuid —— 不读"最近删除"，避免撤销误伤其他任务。
     */
    fun undeleteTasks(uuids: List<String>) = viewModelScope.launch {
        repo.undeleteTasks(uuids)
        refreshWidget()
        uuids.forEach { rescheduleOne(it) }
    }

    /** 开始追踪；结果细分（v5.28.1）：UI 据此分流——只有 LIMIT_REACHED 才弹名额弹窗 */
    fun startTracking(uuid: String, onResult: (TrackStartResult) -> Unit = {}) = viewModelScope.launch {
        val result = repo.startTracking(uuid)
        onResult(result)
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
        val points = repo.getTotalPoints()
        // 完成庆祝：弹层显示获得积分 + 升级信息（层层递进正反馈）
        // v5.15.22 M10（boss：点完成只弹积分、任务还挂着）——
        //   原先是**无条件**弹窗（只要 task 不为 null），于是点一个"其实已完成/今日已打卡"的任务，
        //   repo 内部静默 return 不加分，界面却照样弹"获得积分"，让人以为完成了。
        //   现在只有积分**真的增加**才弹，并且显示的是实际增量而非任务标称值。
        if (task != null && points > pointsBefore) {
            val before = com.taskbar.app.data.model.Levels.of(pointsBefore)
            val after = com.taskbar.app.data.model.Levels.of(points)
            val lv = if (after.lv > before.lv) after.lv to after.name else null
            // v5.15.21 P1：次数任务（target>1，且不是里程碑）→ 轻量自动消失提示
            val isCountTask = task.target > 1 &&
                task.type != com.taskbar.app.data.model.TaskType.MILESTONE
            _completion.value = CompletionEvent(
                task.title, points - pointsBefore, lv?.first, lv?.second,
                lightweight = isCountTask
            )
        }
        refreshWidget()
        rescheduleOne(uuid)      // 完成后撤掉今天的闹钟；每日型会排到下一次
    }

    /** v5.15.23 M10：overdueHalf=true 时按"逾期恢复"规则扣半分（至少 1 分） */
    fun restoreTask(uuid: String, overdueHalf: Boolean = false) = viewModelScope.launch {
        repo.restoreTask(uuid, overdueHalf)
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

    // ===== v5.28.0 C1：习惯补卡（每月 2 次）=====
    /** 返回 null=补卡成功；其他=失败原因（UI 直接展示） */
    suspend fun makeupHabitAndReturn(taskUuid: String, date: String): String? =
        repo.makeupHabit(taskUuid, date)

    suspend fun habitDatesOf(taskUuid: String): List<String> = repo.habitDates(taskUuid)
    suspend fun makeupUsedOf(taskUuid: String): Int = repo.makeupUsedThisMonth(taskUuid)

    /** 进入页面时初始化 checkedToday 用 */
    suspend fun repoIsCheckedToday(taskUuid: String, date: String): Boolean =
        repo.isHabitCheckedToday(taskUuid, date)

    fun observeHabitStreak(taskUuid: String) = repo.observeHabitStreak(taskUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 今日已打卡的 habit uuid 集合（Flow 实时刷新，点完卡后 UI 立刻更新） */
    val todayCheckedHabits: StateFlow<Set<String>> = repo.observeTodayCheckedHabitUuids()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** v5.15.2：全习惯 streak 聚合 map（HabitScreen/TaskListScreen 一次订阅，替代每行独立 Flow） */
    val allHabitStreaks: StateFlow<Map<String, Int>> = repo.observeAllHabitStreaks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * v5.15.24 F1/F6：习惯统计（连续天数 + 强度分）。
     * 强度分 = Loop Habit Score 式指数衰减，纯计算不落库 → 双端无 schema/同步风险。
     */
    val allHabitStats: StateFlow<Map<String, HabitStat>> = repo.observeAllHabitStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** v5.27.1：当前正在追踪数（实时）——设置页"当前 N 个"实况用 */
    val trackingCount: StateFlow<Int> = repo.observeTrackingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    suspend fun habitStreak(taskUuid: String) = repo.habitStreak(taskUuid)

    /** 所有习惯最长连续天数（"我的"页"坚持"统计） */
    suspend fun maxHabitStreak(): Int = repo.maxHabitStreak()

    // ==================== v5.30.0 主页下拉刷新 ====================

    /**
     * 与电脑端做几轮对齐（下拉刷新触发）。
     *
     * 手机是服务器，**没法主动"连"电脑**，所以正确的做法是：
     *   给所有已连接的电脑端 WS 广播 `syncnow` → 电脑端收到后立刻 full_sync(拉) + push_full(推)
     *   → 双向收敛。广播几轮 = boss 要的"进行几次双端同步"。
     *
     * 全程在调用方协程里跑（IO 线程），轮次之间只 delay，不阻塞 UI 线程 → 无卡顿。
     * 本机数据不用手动刷：Room 的 Flow 订阅在电脑端写入后会自动推新数据到界面。
     *
     * @return 给用户看的一句话结果
     */
    suspend fun manualPullSync(rounds: Int = 3): String {
        val clients = com.taskbar.app.server.WS_CLIENT_COUNT.get()
        if (clients <= 0) {
            return "电脑端未连接 —— 已刷新本机数据（电脑端打开后会自动对齐）"
        }
        var sent = 0
        repeat(rounds) { i ->
            if (com.taskbar.app.server.PULL_BUS.tryEmit(com.taskbar.app.server.SYNCNOW)) sent++
            if (i < rounds - 1) kotlinx.coroutines.delay(650)
        }
        kotlinx.coroutines.delay(500)
        return "已与电脑同步 $sent 轮（$clients 台连接）"
    }

    // ===== 设置 =====
    fun setTrackLimit(limit: Int) = viewModelScope.launch { repo.setTrackLimit(limit) }
    fun setSetting(key: String, value: String) = viewModelScope.launch { repo.setSetting(key, value) }
    suspend fun getSetting(key: String, default: String = ""): String = repo.getSetting(key, default)

    // ==================== v5.27.0 协作三件套 ====================

    /** 已配对设备列表（设置页设备管理） */
    fun observeDevices() = repo.pairedDeviceDao.observeAll()

    /** 解绑单台设备（该设备 token 立即失效，其它设备不受影响） */
    fun unbindDevice(deviceId: String) = viewModelScope.launch {
        com.taskbar.app.server.AuthState.unbind(deviceId)
    }

    /** 本机身份名（负责人路由的匹配基准；空设置回落默认） */
    fun observeSelfName(): StateFlow<String> =
        repo.observeSetting("self_name").map { it ?: "" }
            .map { it.ifBlank { "我的手机" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "我的手机")

    /** 变更记录流（任务详情页「动态」） */
    fun observeChangeLogs(taskUuid: String) = repo.changeLogDao.observeByTask(taskUuid)

    // ===== 分类 =====
    suspend fun getCustomCategories(): List<String> = repo.getCustomCategories()
    fun addCustomCategory(name: String) = viewModelScope.launch {
        repo.addCustomCategory(name); refreshCustomCategories()
    }
    fun removeCustomCategory(name: String) = viewModelScope.launch {
        repo.removeCustomCategory(name); refreshCustomCategories()
    }

    /**
     * v5.15.10：自定义分类改为 ViewModel 持有 + 启动预读。
     * 旧写法是「新建任务页 LaunchedEffect 里 DB 查询 → 回写 state」，
     * 进页面后必然触发一次**全屏二次重组**（实测首帧 90th 550~600ms 卡顿的元凶之一）。
     */
    private val _customCategories = MutableStateFlow<List<String>>(emptyList())
    val customCategories: StateFlow<List<String>> = _customCategories.asStateFlow()
    private fun refreshCustomCategories() = viewModelScope.launch {
        _customCategories.value = runCatching { repo.getCustomCategories() }.getOrDefault(emptyList())
    }
    init { refreshCustomCategories() }

    private fun refreshWidget() {
        TrackWidgetProvider.refreshAll(ctx)
    }
}
