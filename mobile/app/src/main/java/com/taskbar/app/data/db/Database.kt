package com.taskbar.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskbar.app.data.model.HabitLog
import com.taskbar.app.data.model.Setting
import com.taskbar.app.data.model.Step
import com.taskbar.app.data.model.SyncMeta
import com.taskbar.app.data.model.Task
import com.taskbar.app.data.model.TaskType
import com.taskbar.app.data.model.TrackStatus
import kotlinx.coroutines.flow.Flow

// ==================== Task DAO ====================
@Dao
interface TaskDao {
    @Upsert
    suspend fun upsert(task: Task)

    @Upsert
    suspend fun upsertAll(tasks: List<Task>)

    @Query("SELECT * FROM tasks WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): Task?

    @Query("SELECT * FROM tasks WHERE uuid = :uuid LIMIT 1")
    fun observeByUuid(uuid: String): Flow<Task?>

    /** 主任务列表：不含已完成、不含软删除，追踪中优先，再按优先级、提醒时间排序 */
    @Query("""
        SELECT * FROM tasks
        WHERE track_status != 'done' AND deleted = 0
        ORDER BY
            CASE track_status WHEN 'tracking' THEN 0 ELSE 1 END,
            CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
            due_at IS NULL, due_at ASC
    """)
    fun observeMainList(): Flow<List<Task>>

    /** 主列表（不含未来任务）：未来任务（due_at > dayEnd）由 FutureTasksSection 独占显示，
     *  避免主区和折叠区重复。dayEnd 通常是今天 23:59:59。 */
    @Query("""
        SELECT * FROM tasks
        WHERE track_status != 'done' AND deleted = 0
          AND (due_at IS NULL OR due_at <= :dayEnd)
        ORDER BY
            CASE track_status WHEN 'tracking' THEN 0 ELSE 1 END,
            CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
            due_at IS NULL, due_at ASC
    """)
    fun observeMainListToday(dayEnd: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE track_status = 'tracking' AND deleted = 0 ORDER BY updated_at DESC")
    fun observeTracking(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE track_status = 'tracking' AND deleted = 0 ORDER BY updated_at DESC")
    suspend fun getTracking(): List<Task>

    @Query("SELECT COUNT(*) FROM tasks WHERE track_status = 'tracking' AND deleted = 0")
    suspend fun countTracking(): Int

    @Query("SELECT * FROM tasks WHERE track_status = 'done' AND deleted = 0 ORDER BY done_at DESC")
    fun observeArchive(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE type = 'habit' AND deleted = 0 ORDER BY due_at ASC")
    fun observeHabits(): Flow<List<Task>>

    /** 未来任务（due_at 在明天之后，未完成，非习惯），按提醒时间升序 */
    @Query("""
        SELECT * FROM tasks
        WHERE track_status != 'done' AND deleted = 0
          AND due_at IS NOT NULL AND due_at > :dayEnd AND type != 'habit'
        ORDER BY due_at ASC
    """)
    fun observeFutureTasks(dayEnd: Long): Flow<List<Task>>

    /** 即将到点的提醒（due_at <= now 且未完成） */
    @Query("SELECT * FROM tasks WHERE due_at IS NOT NULL AND due_at <= :now AND track_status != 'done' AND deleted = 0")
    suspend fun getDueTasks(now: Long): List<Task>

    /** 增量同步：取 updated_at > since 的全部（含软删除） */
    @Query("SELECT * FROM tasks WHERE updated_at > :since")
    suspend fun getChangedSince(since: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE deleted = 0")
    suspend fun getAllNonDeleted(): List<Task>

    @Query("UPDATE tasks SET deleted = 1, updated_at = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("UPDATE tasks SET track_status = :status, updated_at = :now WHERE uuid = :uuid")
    suspend fun updateTrackStatus(uuid: String, status: String, now: Long)

    @Query("SELECT DISTINCT category FROM tasks WHERE category != '' AND deleted = 0")
    suspend fun getCategories(): List<String>
}

// ==================== Step DAO ====================
@Dao
interface StepDao {
    @Upsert
    suspend fun upsert(step: Step)

    @Upsert
    suspend fun upsertAll(steps: List<Step>)

    @Query("SELECT * FROM steps WHERE task_uuid = :taskUuid AND deleted = 0 ORDER BY sort_order ASC")
    fun observeByTask(taskUuid: String): Flow<List<Step>>

    @Query("SELECT * FROM steps WHERE task_uuid = :taskUuid AND deleted = 0 ORDER BY sort_order ASC")
    suspend fun getByTask(taskUuid: String): List<Step>

    @Query("SELECT * FROM steps WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): Step?

    /** 当前进行中步骤（doing），取最早的 */
    @Query("SELECT * FROM steps WHERE task_uuid = :taskUuid AND status = 'doing' AND deleted = 0 ORDER BY sort_order ASC LIMIT 1")
    suspend fun getCurrentStep(taskUuid: String): Step?

    /** 第一个未完成步骤（todo 或 doing） */
    @Query("SELECT * FROM steps WHERE task_uuid = :taskUuid AND status != 'done' AND deleted = 0 ORDER BY sort_order ASC LIMIT 1")
    suspend fun getFirstUndone(taskUuid: String): Step?

    @Query("SELECT COUNT(*) FROM steps WHERE task_uuid = :taskUuid AND deleted = 0")
    suspend fun countByTask(taskUuid: String): Int

    @Query("SELECT COUNT(*) FROM steps WHERE task_uuid = :taskUuid AND status = 'done' AND deleted = 0")
    suspend fun countDoneByTask(taskUuid: String): Int

    @Query("UPDATE steps SET status = :status, done_at = :doneAt, updated_at = :now WHERE uuid = :uuid")
    suspend fun updateStatus(uuid: String, status: String, doneAt: Long?, now: Long)

    @Query("UPDATE steps SET deleted = 1, updated_at = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT * FROM steps WHERE updated_at > :since")
    suspend fun getChangedSince(since: Long): List<Step>

    @Query("SELECT * FROM steps WHERE deleted = 0")
    suspend fun getAllNonDeleted(): List<Step>
}

// ==================== HabitLog DAO ====================
@Dao
interface HabitLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: HabitLog): Long

    @Query("SELECT * FROM habit_logs WHERE task_uuid = :taskUuid ORDER BY check_date DESC")
    suspend fun getByTask(taskUuid: String): List<HabitLog>

    @Query("SELECT EXISTS(SELECT 1 FROM habit_logs WHERE task_uuid = :taskUuid AND check_date = :date)")
    suspend fun isChecked(taskUuid: String, date: String): Boolean

    /** 习惯打卡日期 Flow（供 streak 实时刷新） */
    @Query("SELECT check_date FROM habit_logs WHERE task_uuid = :taskUuid ORDER BY check_date DESC")
    fun observeCheckDatesByTask(taskUuid: String): Flow<List<String>>

    @Query("SELECT * FROM habit_logs WHERE created_at > :since")
    suspend fun getChangedSince(since: Long): List<HabitLog>

    @Query("SELECT * FROM habit_logs")
    suspend fun getAll(): List<HabitLog>
}

// ==================== Settings DAO ====================
@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun set(setting: Setting)

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<Setting>
}

// ==================== SyncMeta DAO ====================
@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE device = :device LIMIT 1")
    suspend fun get(device: String): SyncMeta?

    @Upsert
    suspend fun upsert(meta: SyncMeta)
}

// ==================== Database ====================
@Database(
    entities = [Task::class, Step::class, HabitLog::class, SyncMeta::class, Setting::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun stepDao(): StepDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun settingsDao(): SettingsDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val NAME = "taskguide.db"

        /** v2 → v3：加每任务提醒强度字段 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN reminder_strength TEXT DEFAULT NULL")
            }
        }
    }
}
