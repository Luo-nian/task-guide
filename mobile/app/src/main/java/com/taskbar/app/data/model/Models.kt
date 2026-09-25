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

/**
 * v5.28.1：开始追踪的细分结果。
 * ⭐ 背景（C-031 续）：v5.28.0 的 A3 弹窗把 startTracking 的一切 false 都当"名额满"，
 *   实际 false 有三种原因（任务不存在/任务已完成/名额满）——每日习惯「健身」昨天打卡后
 *   done=1（每日折算只在读取层，DB 不落库），今天点追踪被 v5.26.0 校验拦下，
 *   却弹「追踪名额已满（1/3）」→ boss 实测炸雷。UI 必须按原因分流。
 */
enum class TrackStartResult { OK, LIMIT_REACHED, ALREADY_DONE, NOT_FOUND }

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
    /**
     * v5.15.21 P2：**次数任务**语义对齐（电脑端叫 count / done_count）。
     *
     * 背景：手机端「次数」输入（TaskDetailScreen 次数框）与里程碑共用这两个字段，
     * 电脑端同样用 `count` 承载"次数/目标次数"、`done_count` 承载"已完成次数"。
     * **语义一致，只是 JSON 字段名不同** —— 电脑端是
     * `serialize=snake_case, deserialize=camelCase`，且靠 `alias` 兜手机端的 target/progress。
     *
     * 问题：手机端序列化发 `target`/`progress`，电脑端能靠 alias 收下；
     * 但**电脑端发的 `count`/`done_count` 手机端收不到**（Kotlin 属性名不匹配）→
     * 次数任务的次数同步不过来，手机端算积分时也拿不到次数（双端积分不一致）。
     *
     * 修法：给这两个字段指定 `@SerialName`，使 JSON 名与电脑端**双向一致**。
     * 数据库列名保持 target/progress 不变 → **无需 DB 迁移**。
     */
    /** 里程碑任务当前进度 / 次数任务已完成次数（JSON: done_count） */
    @ColumnInfo(name = "progress")
    @kotlinx.serialization.SerialName("done_count")
    val progress: Int = 0,
    /** 里程碑目标次数 / 次数任务总次数（JSON: count） */
    @ColumnInfo(name = "target")
    @kotlinx.serialization.SerialName("count")
    val target: Int = 1,

    /**
     * v5.24.0：**提前提醒分钟数**（0 = 到点才提醒）。
     *   多份体验测试命中：产检、交片、服药这类"错过就麻烦"的事，只有到点响一次，
     *   而"提前量"此前是个**没有任何消费点**的死设置 —— 用户以为设了，实际没人读。
     */
    @ColumnInfo(name = "remind_ahead_min") val remindAheadMin: Int = 0,

    /**
     * v5.27.0：**负责人**（协作三件套）。存「设备身份名」：
     *   空 = 自己/全员（向后兼容，所有设备都收提醒）；
     *   非空 = 只有身份名匹配的设备响提醒（见 ReminderScheduler / 桌面端 get_next_reminder）。
     * Pro 专属：免费版编辑页不显示该控件。
     */
    @ColumnInfo(name = "owner") val owner: String = ""
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

    // ---- 电脑端通道（v5.15.12）----
    // boss：选「仅电脑端 / 双端」时不该只显示手机端的振动与铃声；
    //   桌面端用自己的提醒 UI（自绘弹窗 / 全屏 / 应用内卡片），不用系统通知。
    /** 桌面弹窗提醒（桌面端自绘卡片） */
    const val POPUP = "popup"
    /** 桌面全屏提醒（重要任务，铺满屏幕） */
    const val FULLSCREEN = "fullscreen"
    /** 桌面应用内提示（列表内高亮 + 角标） */
    const val INAPP = "inapp"

    // ---- 提醒范围（端选择，单选） ----
    const val SCOPE_NONE = "none"      // 不提醒
    const val SCOPE_MOBILE = "mobile"  // 仅手机端
    const val SCOPE_PC = "pc"          // 仅电脑端
    const val SCOPE_BOTH = "both"      // 双端提醒

    /** 通道从轻到重（决定最高档/升级顺序） */
    val CHANNEL_ORDER = listOf(NOTIFY, VIBRATE, BEEP, RING)

    /** 电脑端通道（弹窗 → 应用内 → 全屏，由轻到重） */
    val PC_CHANNEL_ORDER = listOf(INAPP, POPUP, FULLSCREEN)

    /** 该通道是否属于电脑端 */
    fun isPcChannel(ch: String): Boolean = ch == POPUP || ch == FULLSCREEN || ch == INAPP

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
/**
 * v5.15.7：等级徽章配色改为**与桌面端 style.css 的 v5.14h.6"金色徽章层"逐值一致**
 * （之前手机端自己一套调色板 → Lv7 手机是浅紫圆+⚓ emoji、电脑是金色盘+深色锚线稿，boss 反馈"两边长得不一样"）。
 *
 * 桌面口径（.rank-lvN）：
 *   - 盘面：radial-gradient(circle at cx cy, 亮金 → 中金 55% → 深金)
 *   - 内侧双环：2px 亮环 + 2px 深金环（inset box-shadow）
 *   - 图标：深棕线条（--rco），光晕随等级增强
 */
data class LevelPalette(
    /** 盘面放射渐变（与桌面 radial-gradient 逐段对应） */
    val plate: List<androidx.compose.ui.graphics.Color>,
    /** 各段位置（与 plate 等长，末位 1f） */
    val stops: List<Float>,
    /** 放射中心（占直径比例，桌面 circle at cx% cy%） */
    val cx: Float,
    val cy: Float,
    /** 内侧 2px 亮环（inset 0 0 0 2px） */
    val ringLight: androidx.compose.ui.graphics.Color,
    /** 内侧 2px 深金环（inset 0 0 0 4px） */
    val ringDark: androidx.compose.ui.graphics.Color,
    /** 图标线条色（--rco） */
    val iconColor: androidx.compose.ui.graphics.Color,
    /** 外发光（桌面 0 0 Npx rgba(...)） */
    val glow: androidx.compose.ui.graphics.Color
)

data class LevelInfo(
    val lv: Int,
    val name: String,
    val title: String,
    val min: Int,
    val max: Int,
    /** 0f~1f 本级进度 */
    val progress: Float,
    /** 距离下一级还差多少分 */
    val toNext: Int,
    /** 该等级主题色 */
    val palette: LevelPalette
)

object Levels {
    private fun c(v: Long) = androidx.compose.ui.graphics.Color(v)
    // v5.15.7：10 档逐值对齐桌面 .level-icon.rank-lvN（v5.14h.6 层）
    private val P1 = LevelPalette(
        plate = listOf(c(0xFFFFF6E0), c(0xFFEAD8AE), c(0xFFCFBA8D)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.35f, cy = 0.27f,
        ringLight = c(0xFFFFF8E6), ringDark = c(0xFFA9925F), iconColor = c(0xFF6B5230),
        glow = c(0x73A9925F)                      // rgba(169,146,95,.45)
    )
    private val P2 = LevelPalette(
        plate = listOf(c(0xFFFFEFC4), c(0xFFE5C986), c(0xFFC9A85F)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.35f, cy = 0.27f,
        ringLight = c(0xFFFFF4DA), ringDark = c(0xFFB09050), iconColor = c(0xFF5A4420),
        glow = c(0x80B09050)
    )
    private val P3 = LevelPalette(
        plate = listOf(c(0xFFFFEAB0), c(0xFFDFBC68), c(0xFFC0963C)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.35f, cy = 0.27f,
        ringLight = c(0xFFFFF0C8), ringDark = c(0xFFB9882E), iconColor = c(0xFF4A340E),
        glow = c(0x8CB9882E)
    )
    private val P4 = LevelPalette(
        plate = listOf(c(0xFFFFF2C0), c(0xFFE6C46E), c(0xFFC9992E)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.36f, cy = 0.28f,
        ringLight = c(0xFFFFF3CF), ringDark = c(0xFFC4932C), iconColor = c(0xFF3E2A08),
        glow = c(0x99C4932C)
    )
    private val P5 = LevelPalette(
        plate = listOf(c(0xFFFFEEB0), c(0xFFE0B25C), c(0xFFA8751E)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.37f, cy = 0.28f,
        ringLight = c(0xFFFFEFC2), ringDark = c(0xFFA8751E), iconColor = c(0xFF322206),
        glow = c(0xA6C9A227)
    )
    private val P6 = LevelPalette(
        plate = listOf(c(0xFFFFEBA4), c(0xFFDFAC50), c(0xFF9A6410)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.38f, cy = 0.28f,
        ringLight = c(0xFFFFECC0), ringDark = c(0xFF9A6410), iconColor = c(0xFF2A1C04),
        glow = c(0xB3D8B45A)
    )
    private val P7 = LevelPalette(
        plate = listOf(c(0xFFFFE899), c(0xFFE5B44C), c(0xFF8A5A12)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.38f, cy = 0.28f,
        ringLight = c(0xFFFFEAB8), ringDark = c(0xFF8A5A12), iconColor = c(0xFF221800),
        glow = c(0xC7E8BA5A)
    )
    private val P8 = LevelPalette(
        plate = listOf(c(0xFFFFE68C), c(0xFFE8AE3E), c(0xFF7A4C0E)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.39f, cy = 0.29f,
        ringLight = c(0xFFFFE8AE), ringDark = c(0xFF7A4C0E), iconColor = c(0xFF1A1200),
        glow = c(0xD9F0C864)
    )
    private val P9 = LevelPalette(
        plate = listOf(c(0xFFFFE98A), c(0xFFEFA840), c(0xFF8A5A12)), stops = listOf(0f, 0.55f, 1f),
        cx = 0.40f, cy = 0.30f,
        ringLight = c(0xFFFFEFC0), ringDark = c(0xFFB97A14), iconColor = c(0xFF141000),
        glow = c(0xE6F6D782)
    )
    private val P10 = LevelPalette(
        plate = listOf(c(0xFFFFF7C8), c(0xFFFFD769), c(0xFFE0A32A), c(0xFF8A5A12)),
        stops = listOf(0f, 0.40f, 0.70f, 1f),
        cx = 0.42f, cy = 0.28f,
        ringLight = c(0xFFFFF8DC), ringDark = c(0xFFC08A1E), iconColor = c(0xFF1A1200),
        glow = c(0xFFFFDC8C)
    )

    // v5.15.4：10 级曲线与桌面 LEVELS 完全对齐（历练学徒 0 / … / 寰宇传说 3200）
    private val LEVELS = listOf(
        LevelInfo(1, "历练学徒", "敢开始，就已经赢了一半", 0, 50, 0f, 0, P1),
        LevelInfo(2, "风华游侠", "汗水从不会辜负你", 50, 130, 0f, 0, P2),
        LevelInfo(3, "破浪骑士", "风浪越大，越显本色", 130, 250, 0f, 0, P3),
        LevelInfo(4, "群星行者", "你走过的每一步都算数", 250, 420, 0f, 0, P4),
        LevelInfo(5, "传奇勇者", "你就是自己的传说", 420, 660, 0f, 0, P5),
        LevelInfo(6, "苍穹守护者", "天穹之下皆为你我守护", 660, 1000, 0f, 0, P6),
        LevelInfo(7, "深渊征服者", "深渊在凝视，而你在前进", 1000, 1500, 0f, 0, P7),
        LevelInfo(8, "星辰霸主", "群星都将为你让路", 1500, 2200, 0f, 0, P8),
        LevelInfo(9, "天命传奇", "传说，由你亲手书写", 2200, 3200, 0f, 0, P9),
        LevelInfo(10, "寰宇传说", "整个宇宙，都是你的传说", 3200, 999999, 0f, 0, P10)
    )

    /** 按积分算当前等级。
     *  进度 = 本级内完成度（(points-min)/(max-min)），与 UI 的 "X 分 / 距下一级差 Y" 文本一致 */
    /**
     * v5.15.29 L6：判断 text 是否是**高于当前积分对应等级**的某个等级名称。
     * 是则返回那个等级（用于编辑昵称时的提示），否则返回 null。
     * 说明：**只用来给提示，不阻止保存** —— 编辑什么昵称都是用户的自由。
     */
    fun higherLevelNamed(text: String, points: Int): LevelInfo? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val cur = of(points)
        return LEVELS.firstOrNull { it.name == t && it.lv > cur.lv }
    }

    fun of(points: Int): LevelInfo {
        var cur = LEVELS[0]
        for (lv in LEVELS) if (points >= lv.min) cur = lv
        val isMax = cur == LEVELS.last()
        val range = (cur.max - cur.min).coerceAtLeast(1)
        val p = ((points - cur.min).toFloat() / range).coerceIn(0f, 1f)
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

    /** 完成任务可得积分 = 类型基础分 + 优先级加成
     *  v5.15.21 P2（boss：「次数任务的积分应该少一点」）：
     *  次数任务（count > 1）可重复完成、每次都给分 → 基础分与优先级加成各减半
     *  （中优先级 once: 8+4=12 → 4+2=6），避免累计收益过高。 */
    fun forTask(type: String, priority: String, count: Int = 1): Int {
        var b = base(type)
        var p = bonus(priority)
        if (count > 1) {
            b = (b / 2).coerceAtLeast(1)
            p /= 2
        }
        return b + p
    }
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

/**
 * v5.27.0：**已配对设备**（协作三件套 · 多客户端）。
 *
 * 旧版只存一个 `auth_secret`（settings 表），第二台设备配对 = 覆盖旧密钥 = 旧设备被踢。
 * 改为每台设备一行、各持独立密钥：
 *   - `device_id`：客户端配对时上报的 deviceId（legacy 旧行用空串）
 *   - `master_hex`：该设备专属的长期密钥（token 即 master，guard 按 token 反查设备身份）
 *   - 迁移（MIGRATION_5_6）：旧 auth_secret + paired_device → 插入一行 device_id=''，现有配对不失效
 * 不参与同步（和 auth_secret 同一安全边界）。
 */
@Entity(tableName = "paired_devices")
data class PairedDevice(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "master_hex") val masterHex: String,
    @ColumnInfo(name = "paired_at") val pairedAt: Long
)

/**
 * v5.27.0：**任务变更记录**（协作三件套 ·「谁改了什么」）。
 *
 * 只在手机端（服务器）记录与展示 —— 它看得到所有端提交的变更，
 * 正好匹配"派任务的人看动态"的主场景（远程照护：看得见他今天做了没）。
 * 不参与同步、不进备份导出。保留策略：每任务 ≤20 条、全局 ≤2000 条（写入时修剪）。
 */
@Entity(tableName = "change_logs", indices = [Index("task_uuid")])
data class ChangeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_uuid") val taskUuid: String,
    /** 操作者设备身份名（self_name 或来源设备名） */
    @ColumnInfo(name = "who") val who: String,
    /** 动作：create/update/delete/complete/restore/track_start/track_stop/habit_check/delay */
    @ColumnInfo(name = "action") val action: String,
    /** 人类可读摘要（如「完成了任务」「负责人改为 老公电脑」） */
    @ColumnInfo(name = "detail") val detail: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** v5.29.0：任务动态对外瘦身 DTO（桌面详情页「动态」区数据源，不暴露 id/task_uuid） */
@Serializable
data class ChangeLogDto(
    val who: String,
    val action: String,
    val detail: String = "",
    val at: Long
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

/** v5.15.7：设置类变更载荷（积分/等级、头像、昵称…），updatedAt 用于"最新为主" */
@Serializable
data class SettingChange(
    val key: String,
    val value: String,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long = 0
)

/** v5.15.7：全量同步里的设置项（桌面端 SettingKV 对齐） */
@Serializable
data class SettingKV(
    val key: String,
    val value: String,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long = 0
)

@Serializable
data class FullSyncPayload(
    val tasks: List<Task>,
    val steps: List<Step>,
    val habit_logs: List<HabitLog>,
    /** v5.15.7：设置全量（积分/头像等），老客户端不解析该字段也不报错 */
    val settings: List<SettingKV> = emptyList(),
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

// ==================== 聚合查询结果对象 ====================
data class TrackCardItem(
    val task: Task,
    val currentStep: Step?
)

/**
 * v5.18.0：通知栏追踪卡的数据。
 *
 * boss：「手机端的下滑通知栏里依旧有那个"同步服务运行中" 我不想看到这个
 *        只有追踪中的任务可以上去」
 * 通知条件：count > 0 才显示（标题 = 任务名，step = 当前步骤小字）。
 */
data class TrackingInfo(
    val title: String,
    val step: String,
    val count: Int,
) {
    companion object { val NONE = TrackingInfo("", "", 0) }
}

