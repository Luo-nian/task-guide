package com.taskbar.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable

// ==================== 枚举常量 ====================
object TaskType { const val ONCE = "once"; const val REPEAT = "repeat"; const val NOTE = "note"; const val HABIT = "habit"; const val GOAL = "goal" }
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
    @ColumnInfo(name = "deleted") val deleted: Int = 0
)

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
