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
     *  避免主区和折叠区重复。dayEnd 通常是今天 23:59:59。
     *  注意：追踪中的任务不受日期限制——追踪了=今天要做，无论 due_at 都显示在主页
     *
     *  v5.15.18 P0（boss：「应该放在主页（今日）里的任务，为什么跑到所有任务里去了？」
     *                 「手机端点了完任务，电脑端还显示没完成」）：
     *    原先只过滤 `track_status != 'done'`，**完全没有每日重置** → 昨天完成的每日任务
     *    永久卡在 done：从主页消失、只能去「所有任务」找，而且和电脑端（有重置）永久分歧。
     *    （实测 7 条 daily 任务：手机 done=1 / 电脑已重置为 pending）
     *    改为【读时归一】：category='daily' 且 done_at 早于今天 0 点的，仍然查出来
     *    （当作今天的待办），由 Repository 折算成 pending 返回。
     *    **刻意不写库** —— 写了会 bump updated_at 污染同步的 LWW，导致另一端改不动。 */
    @Query("""
        SELECT * FROM tasks
        WHERE deleted = 0
          AND (
                track_status != 'done'
                OR ((category = 'daily' OR type = 'habit') AND (done_at IS NULL OR done_at < :dayStart))
              )
          AND (track_status = 'tracking' OR due_at IS NULL OR due_at <= :dayEnd)
        ORDER BY
            CASE track_status WHEN 'tracking' THEN 0 ELSE 1 END,
            CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
            due_at IS NULL, due_at ASC
    """)
    fun observeMainListToday(dayStart: Long, dayEnd: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE track_status = 'tracking' AND deleted = 0 ORDER BY updated_at DESC")
    fun observeTracking(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE track_status = 'tracking' AND deleted = 0 ORDER BY updated_at DESC")
    suspend fun getTracking(): List<Task>

    @Query("SELECT COUNT(*) FROM tasks WHERE track_status = 'tracking' AND deleted = 0")
    suspend fun countTracking(): Int

    @Query("SELECT * FROM tasks WHERE track_status = 'done' AND deleted = 0 ORDER BY done_at DESC")
    fun observeArchive(): Flow<List<Task>>

    /**
     * v5.15.22 M3（boss：「历史任务不是已完成任务，已逾期未完成的每日任务也算进去」）——
     * 旧的 observeArchive 只取 track_status='done'，所以"某天有任务但没做完"的那天在
     * 历史日历里是 0 条（boss 报的「12、13 日没有任务数量」）。
     * 归档口径改为：已完成 ∪ 往期没做完的每日/习惯任务 ∪ 已逾期未完成的普通任务。
     * 用 created_at < dayStart 排除"今天刚建的"（还没到能算逾期的程度）。
     */
    @Query("""
        SELECT * FROM tasks
        WHERE deleted = 0 AND (
          track_status = 'done'
          OR (
            track_status != 'done' AND created_at < :dayStart AND (
              type = 'habit'
              OR (due_at   IS NOT NULL AND due_at   < :dayStart)
              OR (deadline IS NOT NULL AND deadline < :dayStart)
            )
          )
        )
        ORDER BY COALESCE(done_at, 0) DESC, created_at DESC
    """)
    fun observeArchiveWithOverdue(dayStart: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE type = 'habit' AND deleted = 0 ORDER BY due_at ASC")
    fun observeHabits(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE type = 'habit' AND deleted = 0")
    suspend fun getAllHabits(): List<Task>

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

    /** v5.15.23 M6/M7：「所有任务」页与日历的数据源 —— 全部未删除任务（含逾期/未来/
     *  自定义分类的目标限时/今天已打卡的习惯），不再只依赖"今日列表 + 未来列表"，
     *  这样点分类筛选（目标/限时）时不会因为今日列表里没有该类任务而整页空掉。
     *  调用方（repo）会再做一次每日型折算，保证跨天的每日任务仍显示为"今天的待办"。 */
    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY updated_at DESC")
    fun observeAllNonDeleted(): Flow<List<Task>>

    @Query("UPDATE tasks SET deleted = 1, updated_at = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    /** v5.15.24 F11：撤销删除 —— 把软删任务恢复（配合 Undo 条） */
    @Query("UPDATE tasks SET deleted = 0, updated_at = :now WHERE uuid = :uuid")
    suspend fun undelete(uuid: String, now: Long)

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

    /** 所有非软删除步骤（性能优化：主页用单个 Flow 订阅，替代每任务独立 Flow） */
    @Query("SELECT * FROM steps WHERE deleted = 0 ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<Step>>

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

    /** 插入步骤时：把 sort_order >= from 的步骤整体后移 delta（不碰软删除的） */
    @Query("UPDATE steps SET sort_order = sort_order + :delta, updated_at = :now WHERE task_uuid = :taskUuid AND sort_order >= :from AND deleted = 0")
    suspend fun shiftSortOrder(taskUuid: String, from: Int, delta: Int, now: Long)

    @Query("UPDATE steps SET deleted = 1, updated_at = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    /** v5.15.24 F11：撤销删除（步骤跟随任务一起恢复） */
    @Query("UPDATE steps SET deleted = 0, updated_at = :now WHERE uuid = :uuid")
    suspend fun undelete(uuid: String, now: Long)

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

    /** 全部 habit 打卡 (task_uuid + check_date)，客户端 map 出今日已打卡 uuid（点完卡后 Flow 立刻 emit → UI 实时更新） */
    @Query("SELECT * FROM habit_logs ORDER BY check_date DESC")
    fun observeAllHabitLogs(): Flow<List<HabitLog>>

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
    version = 4,
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

        /** v3 → v4：加里程碑任务进度字段（progress 当前进度 / target 目标次数） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tasks ADD COLUMN target INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
