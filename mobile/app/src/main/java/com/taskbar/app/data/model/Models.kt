package com.taskbar.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ==================== 枚举常量 ====================
object TaskType {
    const val ONCE = "once"; const val REPEAT = "repeat"; const val NOTE = "note"
    const val HABIT = "habit"; const val GOAL = "goal"
    /** 里程碑：可多次推进的大任务，达到目标次数才真正完成归档（如"坚持跑步 10 次"） */
    const val MILESTONE = "milestone"
}
object Priority { const val HIGH = "high"; const val MEDIUM = "medium"; const val LOW = "low" }
object TrackStatus { const val PENDING = "pending"; const val TRACKING = "tracking"; const val DONE = "done" }
object StepStatus { const val TODO = "todo"; const val DOING = "doing"; const val DONE = "done" }

/** 同时追踪上限的默认值 */
const val DEFAULT_TRACK_LIMIT = 3

// ==================== 任务实体 ====================
@Entity(tableName = "tasks", indices = [Index("uuid", unique = true)])
@Serializable
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "type") val type: String = TaskType.ONCE,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "desc") val desc: String = "",
    @ColumnInfo(name = "category") val category: String = "",
    @ColumnInfo(name = "priority") val priority: String = Priority.MEDIUM,
    @ColumnInfo(name = "due_at") val dueAt: Long? = null,
    @ColumnInfo(name = "repeat_rule") val repeatRule: String? = null,
    @ColumnInfo(name = "deadline") val deadline: Long? = null,
    @ColumnInfo(name = "track_status") val trackStatus: String = TrackStatus.PENDING,
    @ColumnInfo(name = "done") val done: Int = 0,
    @ColumnInfo(name = "done_at") val doneAt: Long? = null,
    @ColumnInfo(name = "delayed_count") val delayedCount: Int = 0,
    @ColumnInfo(name = "reward_points") val rewardPoints: Int = 10,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted") val deleted: Int = 0,
    /** 提醒强度：null=跟随设置默认；standard=普通；repeat=5分钟重复3次；alarm=闹钟式 */
    @ColumnInfo(name = "reminder_strength") val reminderStrength: String? = null,
    /** 里程碑任务当前进度（完成 N 次推进） */
    @ColumnInfo(name = "progress") val progress: Int = 0,
    /** 里程碑任务目标次数（progress >= target 才算真正完成） */
    @ColumnInfo(name = "target") val target: Int = 1
)

// ==================== 提醒方式（四通道多选：通知栏/振动/提示音/铃声 + 端选择 + 未受理升级） ====================
object ReminderStrength {
    /** 跟随全局默认（旧值兼容：per-task 为空串时用设置里的全局值） */
    const val INHERIT = ""

    /** 通知栏弹窗：顶部横幅 + 默认提示音，最轻 */
    const val NOTIFY = "notify"

    /** 振动提醒：自定义周期持续振动 */
    const val VIBRATE = "vibrate"

    /** 提示音：系统默认通知音（不振动） */
    const val BEEP = "beep"

    /** 响铃提醒：播放自定义铃声 + 振动 */
    const val RING = "ring"

    // ---- 提醒范围（端选择，单选） ----
    const val SCOPE_NONE = "none"      // 不提醒
    const val SCOPE_MOBILE = "mobile"  // 仅手机端
    const val SCOPE_PC = "pc"          // 仅电脑端
    const val SCOPE_BOTH = "both"      // 双端提醒

    /** 通道从轻到重（决定最高档/升级顺序） */
    val CHANNEL_ORDER = listOf(NOTIFY, VIBRATE, BEEP, RING)

    /** 旧值兼容映射（DB 里可能存 standard/repeat/alarm） */
    fun migrateLegacy(s: String?): String = when (s) {
        null, "" -> ""
        "standard" -> NOTIFY           // 普通通知 → 通知栏弹窗
        "repeat"   -> VIBRATE          // 5 分钟重复 → 振动
        "alarm"    -> RING             // 闹钟式强提醒 → 响铃
        else -> s
    }

    /**
     * 解析提醒配置 → (channels 列表, scope)。
     * 兼容：null/空 → (空, mobile)；旧单值 notify/vibrate/ring → (单元素, mobile)；
     * 新格式 {"ch":"notify,vibrate","scope":"both"} → 多通道+端选择
     */
    fun parseConfig(s: String?): Pair<List<String>, String> {
        val raw = migrateLegacy(s)
        if (raw.isEmpty()) return emptyList<String>() to SCOPE_MOBILE
        if (raw.startsWith("{")) {
            return try {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
                val ch = obj["ch"]?.jsonPrimitive?.content?.split(",")
                    ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList<String>()
                val scope = obj["scope"]?.jsonPrimitive?.content ?: SCOPE_MOBILE
                ch to scope
            } catch (_: Exception) {
                emptyList<String>() to SCOPE_MOBILE
            }
        }
        // 旧单值
        return listOf(raw) to SCOPE_MOBILE
    }

    /** 序列化多通道 + 端选择为存储字符串 */
    fun serializeConfig(channels: List<String>, scope: String): String {
        val ch = channels.distinct().joinToString(",")
        return "{\"ch\":\"$ch\",\"scope\":\"$scope\"}"
    }

    /** 多选通道中的最高档（实际通知按最高档发） */
    fun highest(channels: List<String>): String? = CHANNEL_ORDER.lastOrNull { it in channels }

    /** 未受理升级：按当前最高档升一级（ring 已是最高返回 null） */
    fun escalateNext(channels: List<String>): String? {
        val h = highest(channels) ?: return VIBRATE
        val idx = CHANNEL_ORDER.indexOf(h)
        return if (idx < CHANNEL_ORDER.lastIndex) CHANNEL_ORDER[idx + 1] else null
    }

    /** 给 UI 显示的"人话" */
    fun label(v: String?): String = when (migrateLegacy(v)) {
        INHERIT -> "跟随默认设置"
        NOTIFY  -> "通知栏弹窗（顶部横幅 + 提示音）"
        VIBRATE -> "振动提醒（按自定义周期持续震动）"
        BEEP    -> "提示音（系统默认提示音）"
        RING    -> "响铃提醒（播放自定义铃声）"
        else    -> v ?: ""
    }

    fun shortLabel(v: String?): String = when (migrateLegacy(v)) {
        INHERIT -> "默认"
        NOTIFY  -> "通知"
        VIBRATE -> "振动"
        BEEP    -> "提示音"
        RING    -> "响铃"
        else    -> v ?: ""
    }
}

// ==================== 等级体系（与桌面端 preview-server.js / Rust 端口径一致） ====================
data class LevelInfo(
    val lv: Int,
    val name: String,
    val title: String,
    val min: Int,
    val max: Int,
    /** 0f~1f 本级进度 */
    val progress: Float,
    /** 距离下一级还差多少分 */
    val toNext: Int
)

object Levels {
    private val LEVELS = listOf(
        LevelInfo(1, "历练学徒", "敢开始，就已经赢了一半", 0, 20, 0f, 0),
        LevelInfo(2, "风华游侠", "汗水从不会辜负你", 20, 60, 0f, 0),
        LevelInfo(3, "破浪骑士", "风浪越大，越显本色", 60, 120, 0f, 0),
        LevelInfo(4, "群星行者", "你走过的每一步都算数", 120, 200, 0f, 0),
        LevelInfo(5, "传奇勇者", "你就是自己的传说", 200, 999, 0f, 0)
    )

    /** 按积分算当前等级。
     *  进度条 = 总积分在本级上限中的占比（30 分时 Lv2 上限 60 → 50%），与 UI 的 "30 / 60" 文本一致。 */
    fun of(points: Int): LevelInfo {
        var cur = LEVELS[0]
        for (lv in LEVELS) if (points >= lv.min) cur = lv
        val isMax = cur == LEVELS.last()
        val p = (points.toFloat() / cur.max).coerceIn(0f, 1f)
        // 最高级没有"下一级"，toNext=0（UI 显示"已是最高等级"）
        val toNext = if (isMax) 0 else (cur.max - points).coerceAtLeast(0)
        return cur.copy(progress = p, toNext = toNext)
    }
}

// ==================== 积分规则（按任务类型 + 优先级） ====================
object RewardRules {
    /** 类型基础分：单次 8 / 重复 10 / 习惯 5 / 速记 2 / 目标 15 / 里程碑 20 */
    fun base(type: String): Int = when (type) {
        TaskType.REPEAT -> 10
        TaskType.GOAL -> 15
        TaskType.MILESTONE -> 20
        TaskType.HABIT -> 5
        TaskType.NOTE -> 2
        else -> 8   // ONCE
    }

    /** 优先级加成：高 +7 / 中 +4 / 低 +1 */
    fun bonus(priority: String): Int = when (priority) {
        Priority.HIGH -> 7
        Priority.LOW -> 1
        else -> 4    // MEDIUM
    }

    /** 完成任务可得积分 = 类型基础分 + 优先级加成 */
    fun forTask(type: String, priority: String): Int = base(type) + bonus(priority)
}

// ==================== 法定节假日（中国，内置表；用于提醒顺延） ====================
object ChineseHolidays {
    // 2026/2027 法定节假日（含调休补班的周末也算工作日，这里只存"放假"日期）
    // 格式：yyyy-MM-dd
    val HOLIDAYS_2026 = setOf(
        // 元旦：1/1-1/3
        "2026-01-01", "2026-01-02", "2026-01-03",
        // 春节：2/15-2/21（除夕 2/15）
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20", "2026-02-21",
        // 清明节：4/4-4/6
        "2026-04-04", "2026-04-05", "2026-04-06",
        // 劳动节：5/1-5/5
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
        // 端午节：6/19-6/21
        "2026-06-19", "2026-06-20", "2026-06-21",
        // 中秋节：9/25-9/27
        "2026-09-25", "2026-09-26", "2026-09-27",
        // 国庆节：10/1-10/7
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07"
    )

    val HOLIDAYS_2027 = setOf(
        // 元旦
        "2027-01-01", "2027-01-02", "2027-01-03",
        // 春节（预计 2/6-2/12，实际以官方公告为准）
        "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-02-10", "2027-02-11", "2027-02-12",
        // 清明节
        "2027-04-04", "2027-04-05", "2027-04-06",
        // 劳动节
        "2027-05-01", "2027-05-02", "2027-05-03",
        // 端午节
        "2027-06-09", "2027-06-10", "2027-06-11",
        // 中秋节
        "2027-09-15", "2027-09-16", "2027-09-17",
        // 国庆节
        "2027-10-01", "2027-10-02", "2027-10-03", "2027-10-04", "2027-10-05", "2027-10-06", "2027-10-07"
    )

    private val ALL = HOLIDAYS_2026 + HOLIDAYS_2027

    fun isHoliday(dateStr: String): Boolean = dateStr in ALL

    fun isHoliday(ts: Long): Boolean {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return isHoliday(fmt.format(java.util.Date(ts)))
    }

    /** 从某个时间点起，找到下一个工作日（当天是周末/节假日则顺延），并置为该日 9:00 */
    fun nextWorkday(fromTs: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = fromTs
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        repeat(14) {
            // 跳过周六/周日和法定节假日
            val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
            val isWeekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
            if (!isWeekend && !isHoliday(fmt.format(c.time))) {
                c.set(java.util.Calendar.HOUR_OF_DAY, 9)
                c.set(java.util.Calendar.MINUTE, 0)
                c.set(java.util.Calendar.SECOND, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                return c.timeInMillis
            }
            c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        // 兜底：14 天内都没工作日（不太可能），返回原始时间
        return fromTs
    }
}

// ==================== 步骤实体 ====================
@Entity(
    tableName = "steps",
    foreignKeys = [ForeignKey(entity = Task::class, parentColumns = ["uuid"], childColumns = ["task_uuid"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("uuid", unique = true), Index("task_uuid")]
)
@Serializable
data class Step(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "task_uuid") val taskUuid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "status") val status: String = StepStatus.TODO,
    @ColumnInfo(name = "attr_label") val attrLabel: String = "",
    @ColumnInfo(name = "attr_value") val attrValue: String = "",
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "done_at") val doneAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted") val deleted: Int = 0
)

// ==================== 习惯打卡记录 ====================
@Entity(
    tableName = "habit_logs",
    foreignKeys = [ForeignKey(entity = Task::class, parentColumns = ["uuid"], childColumns = ["task_uuid"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("task_uuid"), Index(value = ["task_uuid", "check_date"], unique = true)]
)
@Serializable
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_uuid") val taskUuid: String,
    @ColumnInfo(name = "check_date") val checkDate: String,   // YYYY-MM-DD
    @ColumnInfo(name = "created_at") val createdAt: Long
)

// ==================== 同步元信息 ====================
@Entity(tableName = "sync_meta")
data class SyncMeta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "device") val device: String,
    @ColumnInfo(name = "last_sync") val lastSync: Long = 0,
    @ColumnInfo(name = "last_change_id") val lastChangeId: Long = 0
)

// ==================== 设置（键值对） ====================
@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)

// ==================== 同步传输 DTO ====================
@Serializable
data class ChangeOp(val op: String, val entity: String, val uuid: String, val data: String? = null)

@Serializable
data class FullSyncPayload(
    val tasks: List<Task>,
    val steps: List<Step>,
    val habit_logs: List<HabitLog>,
    val server_time: Long
)

@Serializable
data class IncrementalPayload(
    val changes: List<ChangeOp>,
    val server_time: Long
)

@Serializable
data class ChangesRequest(val changes: List<ChangeOp>, val client_time: Long)

@Serializable
data class WsMessage(
    val op: String,           // upsert | delete | reminder | ping | pong
    val entity: String = "",
    val uuid: String = "",
    val data: String = ""
)

// ==================== 查询结果对象 ====================
data class TrackCardItem(
    val task: Task,
    val currentStep: Step?
)
