// 任务栏 桌面端 Rust 后端 v4
// Tauri 2 + rusqlite + 同步客户端
// v4: 补齐前端实际调用但后端缺失的命令（get_level / get_daily_progress /
//     add_step / import_steps），修正 advance_step、add_task 参数契约，
//     22:00 未完成提醒改为后端 tick + 按日去重（原前端 setInterval 会漏触发）
#![allow(dead_code)]

use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::WindowEvent;
use tauri::Manager;
use chrono::{Datelike, Local, TimeZone, Timelike};

mod sync;
pub mod tbcrypto;   // v5.17.0 传输加密 / 请求签名（ChaCha20 + HMAC-SHA256）

// =============== 数据结构 ===============
// 协议说明：手机端（Kotlin kotlinx）Task/Step 序列化为 camelCase；桌面端收数据必须 camelCase。
// 桌面端 -> 前端 app.js 的 API 响应按 app.js 读取习惯用 snake_case（与 preview-server mock 一致）。
// 故 serialize/deserialize 分别指定，避免两端任一方向字段名错位。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all(serialize = "snake_case", deserialize = "camelCase"))]
pub struct Task {
    pub uuid: String,
    #[serde(rename = "type")]
    pub task_type: String,
    pub title: String,
    pub desc: String,
    pub category: String,        // 用户分类：daily / goal / time-limited / once
    pub priority: String,
    pub due_at: Option<i64>,
    pub repeat_rule: Option<String>,
    pub deadline: Option<i64>,   // 限时任务的截止时间
    pub track_status: String,
    pub done: i64,
    pub done_at: Option<i64>,
    pub delayed_count: i64,
    // v5.15.7 P0 修复：手机端 Task 没有 count/doneCount（用 progress/target 表示里程碑进度），
    //   缺 `#[serde(default)]` 会让整包反序列化失败 → full_sync 与 ws 推送全部静默失效
    //   （"双端数据差很多"的真正根因）。这里默认值 + alias 兜住手机端字段。
    #[serde(default = "default_count", alias = "target")]
    pub count: i64,           // 次数任务总数（count<=1 表示非次数任务）
    #[serde(default, alias = "progress")]
    pub done_count: i64,      // 次数任务已完成次数
    pub reward_points: i64,
    #[serde(default)]
    pub reminder_strength: Option<String>,  // 每任务提醒强度：standard|repeat|alarm；null=跟随默认
    pub created_at: i64,
    pub updated_at: i64,
    pub deleted: i64,
    // v5.15.12：该任务下的步骤总数（列表查询用子查询带出）
    //   前端据此判断「没有步骤 -> 推进键淡化不可点」（boss 明确要求）
    #[serde(default)]
    pub step_total: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all(serialize = "snake_case", deserialize = "camelCase"))]
pub struct Step {
    pub uuid: String,
    pub task_uuid: String,
    pub title: String,
    pub status: String,
    pub attr_label: String,
    pub attr_value: String,
    pub sort_order: i64,
    pub done_at: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
    pub deleted: i64,
}

#[derive(Serialize, Clone)]
pub struct TrackCard {
    pub task: Task,
    pub current_step: Option<Step>,
    pub done_steps: i64,
    pub total_steps: i64,
}

#[derive(Serialize, Clone)]
pub struct ProgressInfo {
    pub done_today: i64,
    pub total_today: i64,
}

#[derive(Serialize, Clone)]
pub struct ReminderInfo {
    pub title: String,
    pub due_at: i64,
    pub remaining_ms: i64,
}

// =============== 数据库连接 + 状态 ===============
pub struct AppState {
    pub db: Arc<Mutex<Connection>>,
    pub server_url: Arc<Mutex<String>>,
    // v5.15 P0：device_id 改为「配对手机的唯一标识（ANDROID_ID，来自 mDNS TXT）」，
    // 用于手机 IP 变化时自动重连识别同一台手机。旧语义（桌面自己 uuid）已废弃。
    pub device_id: Arc<Mutex<String>>,
    pub config_dir: Arc<Mutex<String>>,
}

// =============== 数据库迁移 ===============
// 旧库没有 count / done_count 列（次数任务），CREATE TABLE IF NOT EXISTS 不会补列，
// 必须显式 ALTER，否则次数任务在老库上会静默失效。
fn migrate(conn: &Connection) {
    let has = |col: &str| -> bool {
        let mut stmt = match conn.prepare("PRAGMA table_info(tasks)") { Ok(s) => s, Err(_) => return false };
        let names: Vec<String> = stmt.query_map([], |r| r.get::<_, String>(1))
            .map(|rows| rows.filter_map(|x| x.ok()).collect())
            .unwrap_or_default();
        names.iter().any(|n| n == col)
    };
    if !has("count") {
        let _ = conn.execute_batch("ALTER TABLE tasks ADD COLUMN count INTEGER NOT NULL DEFAULT 1;");
        log::info!("[migrate] tasks 新增列 count");
    }
    if !has("done_count") {
        let _ = conn.execute_batch("ALTER TABLE tasks ADD COLUMN done_count INTEGER NOT NULL DEFAULT 0;");
        log::info!("[migrate] tasks 新增列 done_count");
    }
    if !has("reminder_strength") {
        let _ = conn.execute_batch("ALTER TABLE tasks ADD COLUMN reminder_strength TEXT DEFAULT NULL;");
        log::info!("[migrate] tasks 新增列 reminder_strength");
    }
    // boss #41：桌面的"任务重复"实际是同 title 但 uuid 不同的脏数据（占位 UUID 0000000X + 真实 UUID）
    //   旧版 schema.sql 没正确执行 → uuid 列无 UNIQUE 约束 + 部分 add_task 用了占位 uuid
    //   修：1) 占位 uuid 且存在同名真 uuid 任务 → 直接删占位
    //       2) 无同名的占位 → 重新生成 uuid v4
    //       3) 建 UNIQUE INDEX 防未来重复
    let placeholders_before: i64 = conn.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND (length(uuid) < 32 OR uuid LIKE '0000000%' OR uuid = '')",
        [], |r| r.get::<_, i64>(0)
    ).unwrap_or(0);
    log::info!("[migrate] 检测到占位 uuid 任务 {} 条", placeholders_before);

    // 用 alias DELETE + EXISTS（同 title + 不同 id + 真 uuid）
    let deleted_placeholder = conn.execute(
        "DELETE FROM tasks WHERE id IN (\
            SELECT t.id FROM tasks t \
            WHERE t.deleted=0 \
              AND (length(t.uuid) < 32 OR t.uuid LIKE '0000000%' OR t.uuid = '') \
              AND EXISTS (\
                  SELECT 1 FROM tasks t2 \
                  WHERE t2.id != t.id AND t2.deleted=0 AND t2.title = t.title \
                    AND length(t2.uuid) >= 32 AND t2.uuid NOT LIKE '0000000%' AND t2.uuid != ''\
              )\
         )",
        [],
    );
    match deleted_placeholder {
        Ok(n) => log::info!("[migrate] 删除占位 uuid 任务 {} 条", n),
        Err(e) => log::error!("[migrate] DELETE 占位任务失败: {}", e),
    }

    // 剩余占位 uuid（无同名真任务）→ 重新生成 uuid v4
    let orphan_count: i64 = {
        let mut stmt = conn.prepare(
            "SELECT id FROM tasks WHERE deleted=0 AND (length(uuid) < 32 OR uuid LIKE '0000000%' OR uuid = '')"
        ).unwrap();
        let ids: Vec<i64> = stmt.query_map([], |r| r.get(0)).unwrap()
            .filter_map(|r| r.ok()).collect();
        let n = ids.len() as i64;
        for id in ids {
            let new_uuid = uuid::Uuid::new_v4().to_string();
            let _ = conn.execute("UPDATE tasks SET uuid=?1 WHERE id=?2", params![new_uuid, id]);
        }
        n
    };
    log::info!("[migrate] 重新生成 {} 条孤立占位任务 uuid", orphan_count);

    // 保险：按 uuid 去重 + 建 UNIQUE 索引
    let _ = conn.execute_batch(
        "DELETE FROM tasks WHERE id NOT IN (SELECT MIN(id) FROM tasks GROUP BY uuid);"
    );
    let has_uuid_unique: bool = conn
        .prepare("SELECT 1 FROM sqlite_master WHERE type='index' AND tbl_name='tasks' AND sql LIKE '%uuid%UNIQUE%'")
        .ok()
        .and_then(|mut s| s.query_row([], |_| Ok(())).ok())
        .is_some();
    if !has_uuid_unique {
        let _ = conn.execute_batch("CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_uuid_unique ON tasks(uuid);");
        log::info!("[migrate] tasks.uuid 加 UNIQUE 索引防重复");
    }
    // steps 同理
    let has_step_uuid_unique: bool = conn
        .prepare("SELECT 1 FROM sqlite_master WHERE type='index' AND tbl_name='steps' AND sql LIKE '%uuid%UNIQUE%'")
        .ok()
        .and_then(|mut s| s.query_row([], |_| Ok(())).ok())
        .is_some();
    if !has_step_uuid_unique {
        let _ = conn.execute_batch("CREATE UNIQUE INDEX IF NOT EXISTS idx_steps_uuid_unique ON steps(uuid);");
        log::info!("[migrate] steps.uuid 加 UNIQUE 索引");
    }
}

// =============== 日期 / 等级 工具 ===============
// 返回今天 00:00:00 ~ 明日 00:00:00 的毫秒区间（本地时区）
fn today_range() -> (i64, i64) {
    let now = Local::now();
    let start = Local.with_ymd_and_hms(now.year(), now.month(), now.day(), 0, 0, 0)
        .single()
        .map(|d| d.timestamp_millis())
        .unwrap_or_else(|| now.timestamp_millis());
    (start, start + 86_400_000)
}

// 等级表必须与前端 app.js 的 LEVELS 保持一致，否则真客户端与预览会显示不同等级
// boss：5 个 title 全删为空串（前端的 LEVELS title 也已删），与界面渲染处 `lv.title || ''` + display:none 联动
fn level_of(points: i64) -> serde_json::Value {
    // (等级, 本级下限, 名称, 标语, 图标名)
    const TABLE: [(i64, i64, &str, &str, &str); 5] = [
        (1,   0, "历练学徒", "",                          "lv1"),
        (2,  20, "风华游侠", "",                          "lv2"),
        (3,  60, "破浪骑士", "",                          "lv3"),
        (4, 120, "群星行者", "",                          "lv4"),
        (5, 200, "传奇勇者", "",                          "lv5"),
    ];
    let mut cur = &TABLE[0];
    for row in TABLE.iter() { if points >= row.1 { cur = row; } }
    // min/max 取自相邻等级：本级下限 → 下一级下限（末级用 999 兜底）
    let idx = (cur.0 - 1) as usize;
    let max = if idx + 1 < TABLE.len() { TABLE[idx + 1].1 } else { 999 };
    serde_json::json!({
        "lv": cur.0, "name": cur.2, "title": cur.3, "ico": cur.4,
        "min": cur.1, "max": max
    })
}

/// 解析 "HH:MM"（容忍 "H:M" 与纯 "HH"）；用于 night_notify_time 这类用户可改的时间点。
/// 越界（>23 时 / >59 分）返回 None，由调用方回落到默认值。
fn parse_hhmm(s: &str) -> Option<(u32, u32)> {
    let t = s.trim();
    let mut it = t.split(':');
    let h: u32 = it.next()?.trim().parse().ok()?;
    let m: u32 = match it.next() {
        Some(x) => x.trim().parse().ok()?,
        None => 0,
    };
    if h > 23 || m > 59 { return None; }
    Some((h, m))
}

fn read_setting(db: &Connection, key: &str, default: &str) -> String {
    db.query_row("SELECT value FROM settings WHERE key=?1", params![key], |r| r.get::<_, String>(0))
        .unwrap_or_else(|_| default.to_string())
}

/// Task.count 反序列化默认值：1 = 非次数任务（与落库时的 COALESCE(count,1) 口径一致）
fn default_count() -> i64 { 1 }

// 开关值兼容：前端可能写 "1" / "true" / "on"，统一按真值判断
fn is_on(v: &str) -> bool {
    matches!(v.trim().to_lowercase().as_str(), "1" | "true" | "on" | "yes")
}

// 22:00 未完成提醒判定。
// 用「日期去重 + 已过 22 点」而非「整点命中」，这样 22:30 才开机、
// 或夜里休眠被唤醒，依然能补上提醒（原实现要求 minutes===0，极易漏）。
#[tauri::command]
fn check_night_notify(state: tauri::State<AppState>) -> serde_json::Value {
    let db = state.db.lock().unwrap();
    if !is_on(&read_setting(&db, "night_notify", "1")) {
        return serde_json::json!({ "pending": false });
    }
    let today = Local::now().format("%Y-%m-%d").to_string();
    // 今天已经提醒过就不再打扰
    if read_setting(&db, "last_night_notify", "") == today {
        return serde_json::json!({ "pending": false });
    }
    // v5.21.2（boss：「这个时间也让用户自己设置，因为有人早睡早起」）——
    //   触发点从写死的 22:00 改成读设置 night_notify_time（'HH:MM'，默认 22:00）。
    //   判定仍是「已过该点 + 今天未提醒」这种宽松口径，所以整点那一分钟没开机也不会漏。
    let (nh, nm) = parse_hhmm(&read_setting(&db, "night_notify_time", "22:00")).unwrap_or((22, 0));
    let now0 = Local::now();
    if now0.hour() < nh || (now0.hour() == nh && now0.minute() < nm) {
        return serde_json::json!({ "pending": false });
    }

    let (day_start, day_end) = today_range();
    let titles: Vec<String> = match db.prepare(
        "SELECT title FROM tasks WHERE deleted=0 AND track_status!='done' AND \
         (category='daily' OR (category='time-limited' AND COALESCE(due_at,deadline) IS NOT NULL \
          AND COALESCE(due_at,deadline) >= ?1 AND COALESCE(due_at,deadline) < ?2)) \
         ORDER BY created_at LIMIT 20"
    ) {
        Ok(mut stmt) => stmt.query_map(params![day_start, day_end], |r| r.get::<_, String>(0))
            .map(|rows| rows.filter_map(|x| x.ok()).collect())
            .unwrap_or_default(),
        Err(e) => { log::warn!("[night] 查询未完成每日任务失败：{}", e); Vec::new() }
    };

    if titles.is_empty() {
        return serde_json::json!({ "pending": false });
    }
    serde_json::json!({ "pending": true, "titles": titles, "count": titles.len() })
}

// 前端弹过提醒后调用，写入当天日期，避免一晚上反复弹
#[tauri::command]
fn dismiss_night_notify(state: tauri::State<AppState>) {
    let today = Local::now().format("%Y-%m-%d").to_string();
    let db = state.db.lock().unwrap();
    let _ = db.execute(
        "INSERT INTO settings(key,value) VALUES('last_night_notify',?1) \
         ON CONFLICT(key) DO UPDATE SET value=?1",
        params![today]
    );
}

// =============== 行解析 ===============
pub fn row_to_task(r: &rusqlite::Row) -> rusqlite::Result<Task> {
    // v5.15.7 加固：任一列读失败就让整行读不出来 → push_change 会误判为"已删除"并把手机端任务删掉。
    //   所以除 uuid 外全部容错（数值列用 Option 兜 NULL，字符串列用默认值）。
    Ok(Task {
        uuid: r.get("uuid")?,
        task_type: r.get("type").unwrap_or_else(|_| "once".to_string()),
        title: r.get("title").unwrap_or_default(),
        desc: r.get("desc").unwrap_or_default(),
        category: r.get("category").unwrap_or_default(),
        priority: r.get("priority").unwrap_or_else(|_| "medium".to_string()),
        due_at: r.get("due_at").unwrap_or(None),
        repeat_rule: r.get("repeat_rule").unwrap_or(None),
        deadline: r.get("deadline").unwrap_or(None),
        track_status: r.get("track_status").unwrap_or_else(|_| "pending".to_string()),
        done: r.get::<_, Option<i64>>("done").unwrap_or(None).unwrap_or(0),
        done_at: r.get("done_at").unwrap_or(None),
        delayed_count: r.get::<_, Option<i64>>("delayed_count").unwrap_or(None).unwrap_or(0),
        count: r.get::<_, Option<i64>>("count").unwrap_or(None).unwrap_or(1),
        done_count: r.get::<_, Option<i64>>("done_count").unwrap_or(None).unwrap_or(0),
        reward_points: r.get::<_, Option<i64>>("reward_points").unwrap_or(None).unwrap_or(10),
        reminder_strength: r.get("reminder_strength").ok(),
        created_at: r.get::<_, Option<i64>>("created_at").unwrap_or(None).unwrap_or(0),
        updated_at: r.get::<_, Option<i64>>("updated_at").unwrap_or(None).unwrap_or(0),
        deleted: r.get::<_, Option<i64>>("deleted").unwrap_or(None).unwrap_or(0),
        step_total: r.get::<_, Option<i64>>("step_total").unwrap_or(None).unwrap_or(0),
    })
}

pub fn row_to_step(r: &rusqlite::Row) -> rusqlite::Result<Step> {
    Ok(Step {
        uuid: r.get("uuid")?,
        task_uuid: r.get("task_uuid")?,
        title: r.get("title")?,
        status: r.get("status")?,
        attr_label: r.get("attr_label")?,
        attr_value: r.get("attr_value")?,
        sort_order: r.get("sort_order")?,
        done_at: r.get("done_at")?,
        created_at: r.get("created_at")?,
        updated_at: r.get("updated_at")?,
        deleted: r.get("deleted")?,
    })
}

// =============== 工具：分类 → task_type + due_at ===============
fn category_to_type(cat: &str) -> &str {
    match cat {
        "daily" => "habit",
        "goal" => "goal",
        "time-limited" => "once",
        "once" => "once",
        _ => "once",
    }
}

// deadlineKey → ms 时长（None 表示不限）
fn deadline_key_to_ms(k: &str) -> Option<i64> {
    match k {
        "none" => None,
        "5min" => Some(5 * 60 * 1000),
        "60min" => Some(60 * 60 * 1000),
        "6h" => Some(6 * 3600 * 1000),
        "1d" => Some(86400 * 1000),
        "3d" => Some(3 * 86400 * 1000),
        "7d" => Some(7 * 86400 * 1000),
        "30d" => Some(30 * 86400 * 1000),
        // 自定义：custom:<毫秒>
        _ if k.starts_with("custom:") => {
            k["custom:".len()..].parse::<i64>().ok().filter(|v| *v > 0)
        }
        _ => None,
    }
}

fn tracking_max(db: &Connection) -> i64 {
    db.query_row("SELECT value FROM settings WHERE key='tracking_max'", [], |r| r.get::<_, String>(0))
        .ok().and_then(|s| s.parse::<i64>().ok()).unwrap_or(3)
}

// =============== Tauri Commands ===============

#[tauri::command]
fn get_track_cards(state: tauri::State<AppState>) -> Vec<TrackCard> {
    let db = state.db.lock().unwrap();
    let mut stmt = db.prepare(
        "SELECT * FROM tasks WHERE track_status='tracking' AND deleted=0 ORDER BY updated_at DESC"
    ).unwrap();
    let tasks: Vec<Task> = stmt.query_map([], row_to_task).unwrap().filter_map(|r| r.ok()).collect();
    drop(stmt);
    tasks.into_iter().map(|t| {
        let cur: Option<Step> = db.prepare(
            // v5.14h.13：step 状态只有 done/todo，原查 'doing' 永远查不到 → widget 永远"全部已完成"
            // 改为：取第一个未完成（todo）的 step 作为当前步
            "SELECT * FROM steps WHERE task_uuid=?1 AND status='todo' AND deleted=0 ORDER BY sort_order LIMIT 1"
        ).unwrap().query_map(params![t.uuid], row_to_step).ok()
            .and_then(|mut rows| rows.next().and_then(|r| r.ok()));
        let total: i64 = db.query_row(
            "SELECT COUNT(*) FROM steps WHERE task_uuid=?1 AND deleted=0", params![t.uuid], |r| r.get(0)
        ).unwrap_or(0);
        let done: i64 = db.query_row(
            "SELECT COUNT(*) FROM steps WHERE task_uuid=?1 AND status='done' AND deleted=0", params![t.uuid], |r| r.get(0)
        ).unwrap_or(0);
        TrackCard { task: t, current_step: cur, done_steps: done, total_steps: total }
    }).collect()
}

#[tauri::command]
fn get_today_tasks(state: tauri::State<AppState>) -> Vec<Task> {
    let db = state.db.lock().unwrap();
    // v5.12 P0：daily 任务 lazy 重置 —— 昨日(或更早)完成的每日任务自动回到待办。
    //
    // v5.15.18 P0 重写（boss：「手机端点了很多任务完成，电脑端还显示未完成」）：
    //   旧实现在这里执行 `UPDATE tasks SET ..., updated_at=now WHERE category='daily'
    //   AND track_status='done' AND done_at < 今天0点` —— 跨天零点会把一批 daily 任务的
    //   updated_at 批量刷成"现在"。而同步用 LWW(最新为主)，于是这些任务的桌面时间戳
    //   比手机端真实操作更新（实测桌面 09-11 00:00:11 vs 手机 09-10 22:58）
    //   → 同步时判"本地赢" → **永久拒绝手机端的完成状态**，桌面卡在未完成。
    //   （实测 14 条任务中招）
    //
    //   改为【读时归一】：根本不写库，只在返回结果里把"跨天的 daily 完成态"折算成 pending。
    //   好处：① 不污染 updated_at，LWW 不再被假时间戳抢占，手机端改动能正常同步；
    //         ② 不再向手机端推送重置变更，杜绝同步回环；
    //         ③ 两端各自按本地"今天"折算，天然一致。
    let (day_start, _) = today_range();
    // v5.14h.13：daily 任务跨天重置时，其下步骤同步回滚为 todo
    //   （否则 widget/详情会显示"全部已完成"）
    // v5.15.18：**刻意不更新 steps.updated_at** —— 避免同样的 LWW 污染问题。
    let stale: Vec<String> = {
        let mut s = db.prepare(
            "SELECT uuid FROM tasks WHERE category='daily' AND track_status='done' AND deleted=0 \
             AND (done_at IS NULL OR done_at < ?1)"
        ).unwrap();
        let it = s.query_map(params![day_start], |r| r.get::<_, String>(0)).unwrap();
        it.filter_map(|x| x.ok()).collect()
    };
    if !stale.is_empty() {
        let mut upd = db.prepare(
            "UPDATE steps SET status='todo' WHERE task_uuid=?1 AND status='done' AND deleted=0"
        ).unwrap();
        for u in &stale { let _ = upd.execute(params![u]); }
    }
    let mut stmt = db.prepare(
        "SELECT tasks.*, (SELECT COUNT(*) FROM steps s WHERE s.task_uuid=tasks.uuid AND s.deleted=0) AS step_total \
         FROM tasks WHERE tasks.deleted=0 AND ( \
             tasks.track_status!='done' \
             OR (tasks.category='daily' AND (tasks.done_at IS NULL OR tasks.done_at < ?1)) \
         ) ORDER BY \
         CASE track_status WHEN 'tracking' THEN 0 ELSE 1 END, \
         CASE WHEN deadline IS NOT NULL THEN 0 ELSE 1 END, \
         deadline IS NULL, deadline ASC, due_at ASC"
    ).unwrap();
    let mut list: Vec<Task> = stmt.query_map(params![day_start], row_to_task)
        .unwrap().filter_map(|r| r.ok()).collect();
    // 读时折算：跨天的 daily 完成态 → 今日待办里的 pending
    for t in list.iter_mut() {
        if t.category == "daily" && t.track_status == "done" {
            let done_today = t.done_at.map(|d| d >= day_start).unwrap_or(false);
            if !done_today {
                t.track_status = "pending".to_string();
                t.done = 0;
                t.done_count = 0;
                t.done_at = None;
            }
        }
    }
    list
}

#[tauri::command]
fn get_archive(state: tauri::State<AppState>) -> Vec<Task> {
    let db = state.db.lock().unwrap();
    let mut stmt = db.prepare(
        "SELECT * FROM tasks WHERE track_status='done' AND deleted=0 \
         ORDER BY updated_at DESC"
    ).unwrap();
    stmt.query_map([], row_to_task).unwrap().filter_map(|r| r.ok()).collect()
}

#[tauri::command]
fn get_progress(state: tauri::State<AppState>) -> ProgressInfo {
    let db = state.db.lock().unwrap();
    // v5.26.1（boss 实测「今日待办有 2 个但列表只有 1 个」）：口径与 get_today_tasks 对齐，
    //   仪表盘/列表/侧栏三处永远同口径（旧口径 due 近24h OR tracking 与列表不一致）。
    let (day_start, day_end) = today_range();
    let remaining: i64 = db.query_row("SELECT COUNT(*) FROM tasks WHERE deleted=0 AND (track_status!='done' OR (category='daily' AND (done_at IS NULL OR done_at < ?1)))", params![day_start], |r| r.get(0)).unwrap_or(0);
    let total: i64 = db.query_row("SELECT COUNT(*) FROM tasks WHERE deleted=0 AND ((track_status!='done' OR (category='daily' AND (done_at IS NULL OR done_at < ?1))) OR (track_status='done' AND done_at IS NOT NULL AND done_at >= ?1 AND done_at < ?2))", params![day_start, day_end], |r| r.get(0)).unwrap_or(0);
    let done = total - remaining;
    ProgressInfo { done_today: done, total_today: total }
}

#[tauri::command]
fn get_daily_progress(state: tauri::State<AppState>) -> serde_json::Value {
    let (day_start, day_end) = today_range();
    let now = Local::now().timestamp_millis();
    let db = state.db.lock().unwrap();

    // 待办项数（每个 task 算 1 项，不再按 count 累加；boss：52 项过载是次数任务 count 累加导致）
    let pending: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND track_status!='done' \
         AND (category='daily' OR (category='time-limited' AND COALESCE(due_at,deadline) IS NOT NULL \
          AND COALESCE(due_at,deadline) >= ?1 AND COALESCE(due_at,deadline) < ?2))",
        params![day_start, day_end], |r| r.get(0)
    ).unwrap_or(0);

    // 已完成项数：今日 done 的 daily / time-limited
    let finished: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND track_status='done' \
         AND done_at IS NOT NULL AND done_at >= ?1 AND done_at < ?2 AND \
         (category='daily' OR category='time-limited')",
        params![day_start, day_end], |r| r.get(0)
    ).unwrap_or(0);

    // 次数任务（count>1 且 daily 类别）的累计 done_count / count，
    // 单独返回给 widget 显示"4/8 50%"，不计入主进度条项数
    let count_done: i64 = db.query_row(
        "SELECT COALESCE(SUM(done_count),0) FROM tasks WHERE deleted=0 AND count > 1 \
         AND category='daily' AND \
         (track_status != 'done' OR (done_at IS NOT NULL AND done_at >= ?1 AND done_at < ?2))",
        params![day_start, day_end], |r| r.get(0)
    ).unwrap_or(0);
    let count_total: i64 = db.query_row(
        "SELECT COALESCE(SUM(count),0) FROM tasks WHERE deleted=0 AND count > 1 \
         AND category='daily' AND \
         (track_status != 'done' OR (done_at IS NOT NULL AND done_at >= ?1 AND done_at < ?2))",
        params![day_start, day_end], |r| r.get(0)
    ).unwrap_or(0);

    let total = pending + finished;
    let done = finished;

    let week: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND done_at IS NOT NULL AND done_at >= ?1",
        params![now - 7 * 86_400_000], |r| r.get(0)
    ).unwrap_or(0);

    let style = read_setting(&db, "progress_style", "bar");
    serde_json::json!({
        "done": done, "total": total, "over": 0, "week": week, "style": style,
        "count_done": count_done, "count_total": count_total
    })
}

// 等级信息（等级卡 / 顶栏徽章数据源）
#[tauri::command]
fn get_level(state: tauri::State<AppState>) -> serde_json::Value {
    let db = state.db.lock().unwrap();
    let points: i64 = read_setting(&db, "total_points", "0").parse().unwrap_or(0);
    let style = read_setting(&db, "progress_style", "bar");
    let mut lv = level_of(points);
    lv["points"] = serde_json::json!(points);
    lv["style"] = serde_json::json!(style);
    lv
}

#[tauri::command]
fn get_next_reminder(state: tauri::State<AppState>) -> Option<ReminderInfo> {
    let db = state.db.lock().unwrap();
    let now = chrono::Local::now().timestamp_millis();
    db.query_row(
        "SELECT title, due_at FROM tasks WHERE due_at>?1 AND track_status!='done' AND deleted=0 ORDER BY due_at ASC LIMIT 1",
        params![now],
        |r| Ok(ReminderInfo { title: r.get(0)?, due_at: r.get(1)?, remaining_ms: r.get::<_, i64>(1)? - now })
    ).ok()
}

#[tauri::command]
fn get_habits_status(state: tauri::State<AppState>) -> Vec<serde_json::Value> {
    let db = state.db.lock().unwrap();
    let mut stmt = db.prepare(
        "SELECT uuid, title FROM tasks WHERE type='habit' AND deleted=0"
    ).unwrap();
    let today = chrono::Local::now().format("%Y-%m-%d").to_string();
    stmt.query_map([], |r| {
        let uuid: String = r.get(0)?;
        let title: String = r.get(1)?;
        let checked: i64 = db.query_row(
            "SELECT COUNT(*) FROM habit_logs WHERE task_uuid=?1 AND check_date=?2",
            params![uuid, &today], |r| r.get(0)
        ).unwrap_or(0);
        Ok(serde_json::json!({ "uuid": uuid, "title": title, "checked": checked > 0 }))
    }).unwrap().filter_map(|r| r.ok()).collect()
}

// 手动新增步骤（前端「点此手动添加」）
#[tauri::command]
fn add_step(state: tauri::State<AppState>, task_uuid: String, title: String) -> serde_json::Value {
    let now = Local::now().timestamp_millis();
    let step_uuid = {
        let db = state.db.lock().unwrap();
        insert_step(&db, &task_uuid, &title, "", "", now)
    };
    sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
    serde_json::json!({ "status": "ok", "uuid": step_uuid })
}

// 批量导入步骤（任务详情卡「添加 JSON」= 粘贴外部 AI 拆解结果）：
// 每步支持可选 attr_label/attr_value（显示为金橙色小标签），空 title 的步骤被跳过
#[tauri::command]
fn import_steps(
    state: tauri::State<AppState>,
    task_uuid: String,
    steps: Vec<serde_json::Value>,
) -> serde_json::Value {
    let now = Local::now().timestamp_millis();
    let mut added: i64 = 0;
    let mut uuids: Vec<String> = Vec::new();
    for s in &steps {
        let title = s.get("title").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
        if title.is_empty() { continue; }
        let attr_label = s.get("attr_label").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let attr_value = s.get("attr_value").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let step_uuid = {
            let db = state.db.lock().unwrap();
            insert_step(&db, &task_uuid, &title, &attr_label, &attr_value, now)
        };
        sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
        uuids.push(step_uuid);
        added += 1;
    }
    serde_json::json!({ "status": "ok", "added": added, "uuids": uuids })
}

// 步骤插入公共逻辑：自动排 sort_order，返回新 uuid
fn insert_step(db: &Connection, task_uuid: &str, title: &str, attr_label: &str, attr_value: &str, now: i64) -> String {
    let step_uuid = uuid::Uuid::new_v4().to_string();
    let sort: i64 = db.query_row(
        "SELECT COALESCE(MAX(sort_order),0)+1 FROM steps WHERE task_uuid=?1",
        params![task_uuid], |r| r.get(0)
    ).unwrap_or(0);
    db.execute(
        "INSERT INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,done_at,created_at,updated_at,deleted) \
         VALUES (?1,?2,?3,'todo',?4,?5,?6,NULL,?7,?7,0)",
        params![&step_uuid, task_uuid, title, attr_label, attr_value, sort, now]
    ).ok();
    step_uuid
}

#[tauri::command]
fn advance_step(
    state: tauri::State<AppState>,
    step_uuid: String,
    task_uuid: Option<String>,
    status: Option<String>,
) {
    let now = chrono::Local::now().timestamp_millis();
    let want_done = status.as_deref().map(|s| s != "todo").unwrap_or(true);
    let mut points_awarded = false;   // v5.15.7：本步骤推进是否结算了积分（用于推 setting 同步）
    let _task_uuid = {
        let db = state.db.lock().unwrap();
        // 前端会传 taskUuid，用它可以少一次查询；不传时回落到按 step 反查
        let t_uuid: String = match &task_uuid {
            Some(t) if !t.is_empty() => t.clone(),
            _ => db.query_row(
                "SELECT task_uuid FROM steps WHERE uuid=?1", params![&step_uuid], |r| r.get(0)
            ).unwrap_or_default(),
        };
        if want_done {
            db.execute("UPDATE steps SET status='done', done_at=?1, updated_at=?2 WHERE uuid=?3",
                params![now, now, &step_uuid]).ok();
        } else {
            // 取消完成：状态回退到 todo，并清掉 done_at
            db.execute("UPDATE steps SET status='todo', done_at=NULL, updated_at=?1 WHERE uuid=?2",
                params![now, &step_uuid]).ok();
        }
        let next: Option<String> = db.query_row(
            "SELECT uuid FROM steps WHERE task_uuid=?1 AND status!='done' AND deleted=0 ORDER BY sort_order LIMIT 1",
            params![&t_uuid], |r| r.get(0)
        ).ok();
        if !want_done {
            // 取消步骤：不自动推进下一步，也不把已完成的任务回退
        } else if let Some(nu) = next {
            db.execute("UPDATE steps SET status='doing', updated_at=?1 WHERE uuid=?2",
                params![now, &nu]).ok();
        } else {
            // 全部步骤完成 → 任务完成 + 加积分（只在任务尚未完成时执行，避免重复加分）
            let already: i64 = db.query_row(
                "SELECT COUNT(*) FROM tasks WHERE uuid=?1 AND track_status='done'",
                params![&t_uuid], |r| r.get(0)
            ).unwrap_or(0);
            if already == 0 {
                db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2 WHERE uuid=?3",
                    params![now, now, &t_uuid]).ok();
                let rp: i64 = db.query_row(
                    "SELECT reward_points FROM tasks WHERE uuid=?1", params![&t_uuid], |r| r.get(0)
                ).unwrap_or(10);
                db.execute(
                    "INSERT INTO settings(key,value) VALUES('total_points',?1) \
                     ON CONFLICT(key) DO UPDATE SET value=printf('%d', CAST(value AS INTEGER) + ?2)",
                    params![(rp + 0).to_string(), rp]
                ).ok();
                points_awarded = true;
            }
        }
        t_uuid
    };
    sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
    if points_awarded { push_setting_sync(&state.db, &state.server_url, "total_points"); }
}

#[tauri::command]
fn complete_task(state: tauri::State<AppState>, task_uuid: String) -> serde_json::Value {
    let now = chrono::Local::now().timestamp_millis();
    // 习惯任务：完成 = 今日打卡（写 habit_logs）+ 加分，任务保持不归档（与手机端一致）
    // 修死锁：每段 db 锁用独立 scope 即时释放，push_change 必须在锁外调用
    // （std::sync::Mutex 非可重入，同线程二次 lock 会永久阻塞 → habit 完成卡 12s+）
    let is_habit: bool = {
        let db = state.db.lock().unwrap();
        db.query_row(
            "SELECT type='habit' FROM tasks WHERE uuid=?1", params![&task_uuid], |r| r.get(0)
        ).unwrap_or(false)
    }; // 锁释放
    if is_habit {
        let date = chrono::Local::now().format("%Y-%m-%d").to_string();
        let already: i64 = {
            let db = state.db.lock().unwrap();
            db.query_row(
                "SELECT COUNT(*) FROM habit_logs WHERE task_uuid=?1 AND check_date=?2",
                params![&task_uuid, &date], |r| r.get(0)
            ).unwrap_or(0)
        }; // 锁释放
        // boss #37：前端 widget 需要区分"刚刚打卡成功" vs "今日已打过卡"
        // 返回 already_done 让前端弹不同提示（避免重复弹奖励）
        if already == 0 {
            {
                let db = state.db.lock().unwrap();
                db.execute("INSERT INTO habit_logs(task_uuid,check_date,created_at) VALUES(?1,?2,?3)",
                    params![&task_uuid, &date, now]).ok();
                // v5.12 P0：习惯今日打卡 → 任务置 done（从今日列表消失 → 归入已完成/归档），
                // 次日 get_today_tasks lazy 重置回 pending（每日任务"当日完成/次日回归"闭环）
                db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2 WHERE uuid=?3",
                    params![now, now, &task_uuid]).ok();
            } // 锁释放
            let rp: i64 = {
                let db = state.db.lock().unwrap();
                db.query_row(
                    "SELECT reward_points FROM tasks WHERE uuid=?1", params![&task_uuid], |r| r.get(0)
                ).unwrap_or(5)
            }; // 锁释放
            {
                let db = state.db.lock().unwrap();
                db.execute(
                    "INSERT INTO settings(key,value) VALUES('total_points',?1) \
                     ON CONFLICT(key) DO UPDATE SET value=printf('%d', CAST(value AS INTEGER) + ?2)",
                    params![rp.to_string(), rp]
                ).ok();
            } // 锁释放
        }
        // 所有锁都已释放：安全 push_change
        sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
        if already == 0 { push_setting_sync(&state.db, &state.server_url, "total_points"); }
        return serde_json::json!({ "status": "ok", "partial": false, "habit": true, "already_done": already > 0 });
    }
    // 次数任务（count>1）：先累加 done_count，满额才算真正完成并结算积分
    let info: (i64, i64, i64) = {
        let db = state.db.lock().unwrap();
        db.query_row(
            "SELECT COALESCE(count,1), COALESCE(done_count,0), COALESCE(reward_points,10) \
             FROM tasks WHERE uuid=?1", params![&task_uuid], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))
        ).unwrap_or((1, 0, 10))
    };
    let (cnt, done_cnt, rp) = info;

    if cnt > 1 && done_cnt + 1 < cnt {
        {
            let db = state.db.lock().unwrap();
            db.execute("UPDATE tasks SET done_count=done_count+1, updated_at=?1 WHERE uuid=?2",
                params![now, &task_uuid]).ok();
        }
        sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
        return serde_json::json!({ "status": "ok", "partial": true, "done_count": done_cnt + 1, "count": cnt });
    }

    {
        let db = state.db.lock().unwrap();
        db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2, \
                    done_count=?3 WHERE uuid=?4",
            params![now, now, cnt, &task_uuid]).ok();
        db.execute("UPDATE steps SET status='done', done_at=?1, updated_at=?2 WHERE task_uuid=?3 AND status!='done'",
            params![now, now, &task_uuid]).ok();
    }
    // v5.11.8 暴击判定：非 habit 任务完整完成时 5% 概率触发，经验 × 2
    // 零依赖 RNG：SystemTime 纳秒戳 % 100 < 5（真随机性不必要）
    let is_critical: bool = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos() % 100 < 5)
        .unwrap_or(false);
    let final_rp = if is_critical { rp * 2 } else { rp };
    // 累加积分
    {
        let db = state.db.lock().unwrap();
        db.execute(
            "INSERT INTO settings(key,value) VALUES('total_points',?1) \
             ON CONFLICT(key) DO UPDATE SET value=printf('%d', CAST(value AS INTEGER) + ?2)",
            params![final_rp.to_string(), final_rp]
        ).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    push_setting_sync(&state.db, &state.server_url, "total_points");
    serde_json::json!({
        "status": "ok",
        "partial": false,
        "is_critical": is_critical,
        "base_exp": rp,
        "final_exp": final_rp
    })
}

#[tauri::command]
fn delete_task(state: tauri::State<AppState>, task_uuid: String) {
    let now = chrono::Local::now().timestamp_millis();
    // v5.18.1：先把步骤 uuid 收集起来 —— 步骤的删除也要逐条推给手机端。
    //   原来只推了任务的 delete，手机端那批步骤仍是 deleted=0（"孤儿步骤"）：
    //   界面看不见（步骤按 task_uuid 查，父任务已删），但两库数据不一致，
    //   只能等下一次全量同步才被覆盖掉。
    let step_uuids: Vec<String> = {
        let db = state.db.lock().unwrap();
        let mut v: Vec<String> = Vec::new();
        if let Ok(mut st) = db.prepare("SELECT uuid FROM steps WHERE task_uuid=?1") {
            if let Ok(rows) = st.query_map(params![&task_uuid], |r| r.get::<_, String>(0)) {
                for r in rows.flatten() { v.push(r); }
            }
        }
        db.execute("UPDATE tasks SET deleted=1, updated_at=?1 WHERE uuid=?2", params![now, &task_uuid]).ok();
        db.execute("UPDATE steps SET deleted=1, updated_at=?1 WHERE task_uuid=?2", params![now, &task_uuid]).ok();
        v
    };
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    for su in step_uuids {
        sync::push_change(&state.db, &state.server_url, "step", &su);
    }
}

#[tauri::command]
fn add_task(
    state: tauri::State<AppState>,
    title: String,
    category: Option<String>,
    deadline_key: Option<String>,
    priority: Option<String>,
    count: Option<i64>,
    desc: Option<String>,       // v5.15.12：备注（手机端一直有，桌面端补上）
    reminder: Option<String>,   // v5.15.12：{"ch":"notify,popup","scope":"both","min":15}
    due_at: Option<i64>,        // v5.15.12：每日任务的"每天几点提醒"时刻
) -> serde_json::Value {
    let now = chrono::Local::now().timestamp_millis();
    let uuid = uuid::Uuid::new_v4().to_string();
    let cat = category.unwrap_or_else(|| "once".into());
    let typ = category_to_type(&cat).to_string();
    let prio = priority.unwrap_or_else(|| "medium".into());
    let ddl = deadline_key.as_deref().unwrap_or("none");
    let deadline_ms = deadline_key_to_ms(ddl);
    let deadline = deadline_ms.map(|d| now + d);
    // v5.15.12：due_at 三态 —— 限时任务 = 截止时刻；每日任务 = 用户设定的每天提醒时刻；其余为空
    let due = if ddl != "none" { deadline } else { due_at };
    let repeat_rule: Option<&str> = if cat == "daily" { Some("daily") } else { None };
    // 次数任务：前端传 count（如「喝水 8 次」），默认 1
    let cnt = count.unwrap_or(1).max(1);
    // 积分规则与手机端 RewardRules 对齐：类型基础分 + 优先级加成
    // v5.15.21 P2：次数任务（cnt>1）降分
    let rp = reward_points_for(&typ, &prio, cnt);
    {
        let db = state.db.lock().unwrap();
        // 限时任务同时写 deadline 与 due_at：前端渲染读 due_at，跨端同步用 deadline，
        // 只写一列会导致「今日到期」判定与详情页显示对不上
        db.execute(
            "INSERT INTO tasks (uuid,type,title,desc,category,priority,due_at,deadline,repeat_rule,count,done_count,track_status,reward_points,reminder_strength,created_at,updated_at) \
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,0,'pending',?11,?12,?13,?13)",
            params![&uuid, &typ, &title, desc.unwrap_or_default(), &cat, &prio, due, deadline, repeat_rule, cnt, rp, reminder, now]
        ).ok();
        // v5.13k：通用步骤化——count > 1 时自动生成 N 个步骤（覆盖所有 type）
        //   之前 v4.13.5 做法是用户手动 add_step，boss 决定所有任务都用步骤推进更统一
        if cnt > 1 {
            for i in 1..=cnt {
                let step_uuid = uuid::Uuid::new_v4().to_string();
                let _ = db.execute(
                    "INSERT INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,created_at,updated_at,deleted) \
                     VALUES (?1,?2,?3,'todo','','',?4,?5,?5,0)",
                    params![step_uuid, &uuid, format!("步骤 {}/{}", i, cnt), i - 1, now]
                );
            }
        }
    }
    sync::push_change(&state.db, &state.server_url, "task", &uuid);
    serde_json::json!({ "uuid": uuid, "status": "ok" })
}

/** 积分规则（与手机端 com.taskbar.app.data.model.RewardRules 对齐）：
 *  类型基础分 once8/repeat10/habit5/note2/goal15 + 优先级加成 high7/medium4/low1 */
/// v5.15.12：编辑任务（桌面端此前完全没有编辑入口，boss 反馈"编辑任务不能写备注"）
#[tauri::command]
fn update_task(
    state: tauri::State<AppState>,
    task_uuid: String,
    title: Option<String>,
    category: Option<String>,
    deadline_key: Option<String>,
    priority: Option<String>,
    count: Option<i64>,
    desc: Option<String>,
    reminder: Option<String>,
    due_at: Option<i64>,
) -> serde_json::Value {
    let now = chrono::Local::now().timestamp_millis();
    let cat = category.unwrap_or_else(|| "once".into());
    let typ = category_to_type(&cat).to_string();
    let prio = priority.unwrap_or_else(|| "medium".into());
    let ddl = deadline_key.as_deref().unwrap_or("none");
    let deadline = deadline_key_to_ms(ddl).map(|d| now + d);
    let due = if ddl != "none" { deadline } else { due_at };
    let repeat_rule: Option<&str> = if cat == "daily" { Some("daily") } else { None };
    let cnt = count.unwrap_or(1).max(1);
    {
        let db = state.db.lock().unwrap();
        db.execute(
            "UPDATE tasks SET type=?2, title=COALESCE(?3,title), desc=?4, category=?5, priority=?6, \
             due_at=?7, deadline=?8, repeat_rule=?9, count=?10, reminder_strength=?11, updated_at=?12 \
             WHERE uuid=?1",
            params![&task_uuid, &typ, title, desc.unwrap_or_default(), &cat, &prio, due, deadline, repeat_rule, cnt, reminder, now]
        ).ok();
        // 次数调小后把已完成次数夹到范围内，避免出现 5/3 这种越界显示
        let _ = db.execute(
            "UPDATE tasks SET done_count=MIN(done_count, count) WHERE uuid=?1",
            params![&task_uuid]
        );
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    serde_json::json!({ "uuid": task_uuid, "status": "ok" })
}

fn reward_points_for(task_type: &str, priority: &str, count: i64) -> i64 {
    let mut base = match task_type {
        "repeat" => 10,
        "goal" => 15,
        "habit" => 5,
        "note" => 2,
        _ => 8, // once
    };
    let mut bonus = match priority {
        "high" => 7,
        "low" => 1,
        _ => 4, // medium
    };
    // v5.15.21 P2（boss：「次数任务的积分应该少一点」）：
    //   次数任务（count > 1，如「喝水 8 次」）**可重复完成、每次都给分**，
    //   若与单次型任务同分，累计收益过高不合理。
    //   规则：基础分减半 + 优先级加成减半（中优先级 once: 8+4=12 → 4+2=6）。
    if count > 1 {
        base = (base / 2).max(1);
        bonus = bonus / 2;
    }
    base + bonus
}

#[tauri::command]
fn set_display_mode(window: tauri::Window, mode: String) {
    match mode.as_str() {
        "top" => { window.set_always_on_top(true).ok(); window.show().ok(); }
        "normal" => { window.set_always_on_top(false).ok(); window.show().ok(); }
        "tray" => { window.hide().ok(); }
        _ => {}
    }
}

// v4.10 顶栏三键命令：最小化 / 切换最大化 / 隐藏（保留后台，前端不再被 X 强行退出）
#[tauri::command]
fn win_minimize(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") { let _ = w.minimize(); }
}
#[tauri::command]
fn win_toggle_maximize(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        if w.is_maximized().unwrap_or(false) {
            let _ = w.unmaximize();
        } else {
            let _ = w.maximize();
        }
    }
}
#[tauri::command]
fn win_hide(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") { let _ = w.hide(); }
}
// v5.14d：JS 兜底拖动（boss 反馈主窗+挂件 data-tauri-drag-region 在 WebView2 偶发失效）
//   前端 mousedown 调这个 IPC，调用 start_dragging() 让 OS 进入拖动循环（Win10/11 都支持）
#[tauri::command]
fn win_start_dragging(app: tauri::AppHandle, label: String) {
    if let Some(w) = app.get_webview_window(&label) {
        let _ = w.start_dragging();
    }
}

// =============== v5.13c 独立桌面挂件（widget 独立小窗，不依赖主窗口） ===============
#[tauri::command]
fn show_widget(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("widget") {
        let _ = w.show();
        let _ = w.set_focus();
    }
}
#[tauri::command]
fn hide_widget(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("widget") {
        let _ = w.hide();
    }
}
#[tauri::command]
fn show_main_window(app: tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.set_focus();
        let _ = w.unminimize();
    }
}
// v5.13g 顶栏挂件开关需要查询 widget 窗可见性
#[tauri::command]
fn get_widget_visible(app: tauri::AppHandle) -> bool {
    if let Some(w) = app.get_webview_window("widget") {
        w.is_visible().unwrap_or(false)
    } else { false }
}

/// ══════════════════════════════════════════════════════════════════
/// v5.17.0 配对改版（boss：取消配对码 → 「一端发申请、另一端弹窗确认」）
/// ══════════════════════════════════════════════════════════════════
///
/// 流程：
///   ① `pair_request`：电脑发申请 → 手机弹确认框
///      （手机端只有在**人正看着设置页**时才受理，且同 IP 限流、同时只允许一个 pending）
///   ② 人在手机上核对 6 位数字并点「允许」← **这一步才是真正的安全边界**
///   ③ `pair_poll`：电脑轮询拿到 master → 写入 pairing.json → 立刻全量同步
///
/// ⭐ 安全边界在「人在手机上点允许」那一下 —— 不再有需要用户抄写的验证码。
///    SAS = sha256(cNonce|sNonce|sessionId) 前 6 位。数字不一致即说明信道上有人改过东西。
#[tauri::command]
fn pair_request(url: String, device_name: Option<String>, device_id: Option<String>) -> String {
    let base = crate::sync::server_base(&url);
    if base.is_empty() { return "error:手机地址为空".to_string(); }
    let pc_name = device_name
        .filter(|s| !s.trim().is_empty())
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .unwrap_or_else(|| "BOOS PC".to_string());
    let body = serde_json::json!({
        "deviceName": pc_name,
        "deviceId": device_id.unwrap_or_default(),
        // 两端加密实现互校：版本错配会在这里立刻暴露，而不是等同步静默失败（对用户不可见）
        "kat": crate::tbcrypto::kat_probe(),
    });
    let resp = crate::sync::lan_client()
        .post(format!("{}/api/pair/request", base))
        .timeout(std::time::Duration::from_secs(6))
        .json(&body)
        .send();
    match resp {
        Ok(r) if r.status().is_success() => {
            let txt = r.text().unwrap_or_default();
            let v: serde_json::Value =
                serde_json::from_str(&txt).unwrap_or(serde_json::Value::Null);
            let sid = v.get("sessionId").and_then(|x| x.as_str()).unwrap_or("");
            if sid.is_empty() {
                return "error:手机端返回异常，请重试".to_string();
            }
            serde_json::json!({ "status": "pending", "sessionId": sid }).to_string()
        }
        Ok(r) if r.status().as_u16() == 403 => {
            let txt = r.text().unwrap_or_default();
            if txt.contains("not_armed") {
                "error:手机端不在配对状态 —— 请先在手机上打开「设置」页，再点配对".to_string()
            } else {
                "error:手机端拒绝了申请（可能已有待确认的配对，或请求过于频繁）".to_string()
            }
        }
        Ok(r) => {
            // 注意：reqwest 的 Response::text(self) 会取得所有权，status 必须先取出
            let code = r.status();
            let txt = r.text().unwrap_or_default();
            if txt.contains("crypto_mismatch") {
                "error:两端加密实现不一致，请把电脑端和手机端升级到同一版本".to_string()
            } else {
                format!("error:配对申请失败（HTTP {}）", code)
            }
        }
        Err(e) => format!("error:连不上手机：{}", e),
    }
}

/// 轮询配对结果。`approved` 时拿到 master 并落盘，随后立即全量同步。
#[tauri::command]
fn pair_poll(state: tauri::State<AppState>, url: String, session_id: String) -> String {
    let base = crate::sync::server_base(&url);
    if base.is_empty() { return "error:手机地址为空".to_string(); }
    let resp = crate::sync::lan_client()
        .get(format!("{}/api/pair/poll?sessionId={}", base, session_id))
        .timeout(std::time::Duration::from_secs(6))
        .send();
    match resp {
        Ok(r) if r.status().is_success() => {
            let txt = r.text().unwrap_or_default();
            let v: serde_json::Value =
                serde_json::from_str(&txt).unwrap_or(serde_json::Value::Null);
            let st = v.get("status").and_then(|x| x.as_str()).unwrap_or("expired");
            if st != "approved" {
                return serde_json::json!({ "status": st }).to_string();
            }
            let master = v.get("master").and_then(|x| x.as_str()).unwrap_or("");
            if !crate::sync::set_master_hex(master) {
                return "error:手机端下发的密钥无效".to_string();
            }
            *state.server_url.lock().unwrap() = url;
            save_pairing_to_disk(&state);
            let fp: String = master.chars().take(4).collect::<String>().to_uppercase();
            let db = state.db.clone();
            let u = state.server_url.clone();
            std::thread::spawn(move || { let _ = sync::full_sync(&db, &u); });
            serde_json::json!({ "status": "approved", "fingerprint": fp }).to_string()
        }
        Ok(r) => format!("error:HTTP {}", r.status()),
        Err(e) => format!("error:连不上手机：{}", e),
    }
}

#[tauri::command]
fn connect_server(state: tauri::State<AppState>, url: String, device_id: Option<String>) -> String {
    // v5.15 P0：记住配对的手机 deviceId（mDNS TXT 的 ANDROID_ID），
    // 手机 IP 变化后 auto_reconnect 靠它重新识别同一台手机。
    if let Some(did) = device_id {
        if !did.is_empty() {
            *state.device_id.lock().unwrap() = did.clone();
            // v5.15.19：同步到进程级，供 ws_loop 反 POST /api/pair 时带上
            sync::set_paired_device_id(&did);
        }
    }
    *state.server_url.lock().unwrap() = url.clone();
    save_pairing_to_disk(&state);
    // v5.16.0：此处原会"反向 POST /api/pair"告知手机端已配对。
    //   配对已改为「弹窗确认」（见 pair_request / pair_poll），故该逻辑已移除，
    //   connect_server 只负责"用已有密钥续连"。
    // 启动时也尝试立即同步一次
    let db_arc = state.db.clone();
    let url_arc2 = state.server_url.clone();
    std::thread::spawn(move || {
        let _ = sync::full_sync(&db_arc, &url_arc2);
    });
    "已连接".to_string()
}

#[tauri::command]
fn disconnect_server(state: tauri::State<AppState>) {
    // ⚠️ v5.17.0 顺手修掉一处"从来没生效过"的逻辑：
    //   旧实现**先**把 server_url 置空、**再**读它拼 base —— base 永远是空串，
    //   于是"通知手机端解除配对"这段从来没真正发出过（手机端会一直显示已配对）。
    let base_for_clear = {
        let g = state.server_url.lock().unwrap();
        sync::server_base(&g)
    };
    *state.server_url.lock().unwrap() = String::new();
    let _ = clear_pairing_from_disk(&state);
    // 断开时反向通知手机端解除配对（v5.17.0：带签名 + 加密）
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(500));
        if base_for_clear.is_empty() { return; }
        let _ = crate::sync::secure_post(&base_for_clear, "/api/pair/clear", "{}", 3);
    });
}

#[tauri::command]
fn get_server_url(state: tauri::State<AppState>) -> String {
    state.server_url.lock().unwrap().clone()
}

#[tauri::command]
fn get_total_points(state: tauri::State<AppState>) -> i64 {
    let db = state.db.lock().unwrap();
    db.query_row("SELECT value FROM settings WHERE key='total_points'", [],
        |r| r.get::<_, String>(0))
        .ok().and_then(|s| s.parse::<i64>().ok()).unwrap_or(0)
}

#[tauri::command]
fn get_task_detail(state: tauri::State<AppState>, task_uuid: String) -> serde_json::Value {
    let db = state.db.lock().unwrap();
    let task: Option<Task> = db.query_row(
        "SELECT * FROM tasks WHERE uuid=?1", params![task_uuid], row_to_task
    ).ok();
    let steps: Vec<Step> = {
        let mut stmt = db.prepare(
            "SELECT * FROM steps WHERE task_uuid=?1 AND deleted=0 ORDER BY sort_order"
        ).unwrap();
        stmt.query_map(params![task_uuid], row_to_step).unwrap().filter_map(|r| r.ok()).collect()
    };
    serde_json::json!({ "task": task, "steps": steps })
}

#[tauri::command]
fn start_tracking(state: tauri::State<AppState>, task_uuid: String) -> Result<(), String> {
    let now = chrono::Local::now().timestamp_millis();
    // 同 complete_task 死锁修复：每段 db 操作独立 scope 让锁立即释放，
    // sync::push_change 必须在完全无锁状态下调用（std::sync::Mutex 非可重入）
    let max: i64 = {
        let db = state.db.lock().unwrap();
        tracking_max(&db)
    }; // 锁释放
    let cur: i64 = {
        let db = state.db.lock().unwrap();
        db.query_row(
            "SELECT COUNT(*) FROM tasks WHERE track_status='tracking' AND deleted=0", [], |r| r.get(0)
        ).unwrap_or(0)
    }; // 锁释放
    if cur >= max {
        return Err(format!("已达追踪上限（{}）", max));
    }
    {
        let db = state.db.lock().unwrap();
        db.execute("UPDATE tasks SET track_status='tracking', updated_at=?1 WHERE uuid=?2",
            params![now, &task_uuid]).ok();
    } // 锁释放
    let next: Option<String> = {
        let db = state.db.lock().unwrap();
        db.query_row(
            "SELECT uuid FROM steps WHERE task_uuid=?1 AND status!='done' AND deleted=0 ORDER BY sort_order LIMIT 1",
            params![&task_uuid], |r| r.get(0)
        ).ok()
    }; // 锁释放
    if let Some(nu) = next {
        {
            let db = state.db.lock().unwrap();
            db.execute("UPDATE steps SET status='doing', updated_at=?1 WHERE uuid=?2",
                params![now, &nu]).ok();
        } // 锁释放
    }
    // 所有锁都已释放：安全 push_change
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    Ok(())
}

/// v5.15.11：延迟任务 —— 把 due_at（没有则从现在起算）往后推 minutes 分钟，
///   有 deadline 也一起顺延，delayed_count +1，然后推给手机。
/// 死锁铁律：所有 db 锁用独立 scope 即时释放，push_change 必须在完全无锁时调用。
#[tauri::command]
fn delay_task(state: tauri::State<AppState>, task_uuid: String, minutes: i64) -> Result<serde_json::Value, String> {
    if minutes <= 0 { return Err("延迟时长必须大于 0".into()); }
    let now = chrono::Local::now().timestamp_millis();
    let delta = minutes * 60_000;
    let new_due: i64 = {
        let db = state.db.lock().unwrap();
        let row = db.query_row(
            "SELECT due_at, deadline, COALESCE(delayed_count,0) FROM tasks WHERE uuid=?1 AND deleted=0",
            params![&task_uuid],
            |r| Ok((r.get::<_, Option<i64>>(0)?, r.get::<_, Option<i64>>(1)?, r.get::<_, i64>(2)?)),
        ).ok();
        let (due, deadline, dc) = row.ok_or_else(|| "任务不存在".to_string())?;
        let base = due.or(deadline).unwrap_or(now);
        let nd = base + delta;
        db.execute(
            "UPDATE tasks SET due_at=?1, \
             deadline=CASE WHEN deadline IS NULL THEN NULL ELSE deadline + ?2 END, \
             delayed_count=?3, updated_at=?4 WHERE uuid=?5",
            params![nd, delta, dc + 1, now, &task_uuid],
        ).map_err(|e| e.to_string())?;
        nd
    }; // 锁释放
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    Ok(serde_json::json!({ "status": "ok", "due_at": new_due, "delayed_minutes": minutes }))
}

/// v5.15.11：按分类取**全部**未完成任务（侧边栏分类页用）。
///   旧实现是从「今日任务」里筛，非今天的同分类任务在分类页里看不到（boss："点侧边栏什么都没有"）。
#[tauri::command]
fn get_tasks_by_category(state: tauri::State<AppState>, category: String) -> Vec<Task> {
    let db = state.db.lock().unwrap();
    // v5.15.21 D5 根因修复（boss 第二次反馈："点侧边栏只看某个分类，没显示任何任务，
    //   但详情/总览里其实有"）：
    //   前端 catOf() 对**自定义分类**（如"工作"/"生活"）会按 type 推断归类
    //   （habit→daily / goal→goal / repeat→time-limited / note|once→once），
    //   而这里原先只 `WHERE category=?1` 精确匹配字段 → 自定义分类的任务永远查不到，
    //   于是总览能看到（前端推断）、分类页却是"这一类暂时没有任务"。
    //   现在 SQL 与前端 catOf 完全等价：字段命中 OR（字段不在白名单 且 type 推断命中）。
    let mut stmt = match db.prepare(
        "SELECT tasks.*, (SELECT COUNT(*) FROM steps s WHERE s.task_uuid=tasks.uuid AND s.deleted=0) AS step_total \
         FROM tasks WHERE deleted=0 AND track_status!='done' AND ( \
             category = ?1 \
             OR ( \
                 COALESCE(category,'') NOT IN ('daily','goal','time-limited','once') \
                 AND ?1 = CASE type \
                     WHEN 'habit' THEN 'daily' \
                     WHEN 'goal' THEN 'goal' \
                     WHEN 'repeat' THEN 'time-limited' \
                     WHEN 'note' THEN 'once' \
                     WHEN 'once' THEN 'once' \
                     ELSE 'once' END \
             ) \
         ) \
         ORDER BY COALESCE(due_at, deadline, created_at), CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END"
    ) { Ok(s) => s, Err(_) => return vec![] };
    let rows = stmt.query_map(params![&category], row_to_task);
    match rows {
        Ok(r) => r.filter_map(|x| x.ok()).collect(),
        Err(_) => vec![],
    }
}

#[tauri::command]
fn stop_tracking(state: tauri::State<AppState>, task_uuid: String) {    let now = chrono::Local::now().timestamp_millis();
    {
        let db = state.db.lock().unwrap();
        db.execute("UPDATE tasks SET track_status='pending', updated_at=?1 WHERE uuid=?2",
            params![now, &task_uuid]).ok();
        // 当前 doing 的步骤回到 todo
        db.execute("UPDATE steps SET status='todo', updated_at=?1 WHERE task_uuid=?2 AND status='doing'",
            params![now, &task_uuid]).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
}

// =============== v5.13d 恢复历史任务（boss：历史里右键任务可恢复到今日待办） ===============
#[tauri::command]
fn restore_task(state: tauri::State<AppState>, task_uuid: String) -> Result<(), String> {
    let now = chrono::Local::now().timestamp_millis();
    // v5.14h.16：恢复任务时退积分（否则用户恢复后再完成 = 重复给分 = 刷分）
    // 读原 task.reward_points（上次完成时发放的积分），从 total_points 扣除
    let orig_rp: i64 = {
        let db = state.db.lock().unwrap();
        db.query_row(
            "SELECT COALESCE(reward_points,0) FROM tasks WHERE uuid=?1 AND deleted=0",
            params![&task_uuid], |r| r.get(0)
        ).unwrap_or(0)
    };
    {
        let db = state.db.lock().unwrap();
        db.execute(
            "UPDATE tasks SET track_status='pending', done=0, done_at=NULL, done_count=0, reward_points=0, updated_at=?1 \
             WHERE uuid=?2 AND deleted=0",
            params![now, &task_uuid]
        ).map_err(|e| e.to_string())?;
    } // 锁释放
    if orig_rp > 0 {
        // 退积分：total_points -= orig_rp（下界 0）
        {
            let db = state.db.lock().unwrap();
            db.execute(
                "UPDATE settings SET value=printf('%d', MAX(0, CAST(value AS INTEGER) - ?1)) WHERE key='total_points'",
                params![orig_rp]
            ).ok();
        } // 锁释放
        // 同步积分变化给手机（手机端 total_points 来自这里）
        push_setting_sync(&state.db, &state.server_url, "total_points");
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    Ok(())
}

#[tauri::command]
fn set_setting(state: tauri::State<AppState>, key: String, value: String) {
    // v5.15.7：只有值真的变了才写库 + 打时间戳 + 推手机
    //   （前端 persistSettingsServer 每次保存都会把 12 个 key 全量发一遍，
    //     若无条件推送，切任意开关都会把 base64 头像重复 POST 给手机）
    let changed = {
        let db = state.db.lock().unwrap();
        let old: Option<String> = db.query_row(
            "SELECT value FROM settings WHERE key=?1", params![&key], |r| r.get::<_, String>(0)
        ).ok();
        if old.as_deref() == Some(value.as_str()) {
            false
        } else {
            db.execute(
                "INSERT INTO settings(key,value) VALUES(?1,?2) \
                 ON CONFLICT(key) DO UPDATE SET value=?2",
                params![&key, &value]
            ).ok();
            true
        }
    }; // 锁释放
    if changed { push_setting_sync(&state.db, &state.server_url, &key); }
}

// v5.15.7：需要双端一致的设置项（积分/等级、头像、昵称、分类…）
const SYNC_SETTING_KEYS: [&str; 8] = [
    "total_points", "avatar_img", "avatar_idx", "avatar_emoji",
    "nickname", "tracking_max", "track_limit", "category_list",
];

/// 给本地某项 setting 打时间戳（"最新为主"）并推送给手机。
/// 必须在无 db 锁状态下调用（内部会 push_change → 再 lock）。
fn push_setting_sync(db: &Arc<Mutex<Connection>>, url: &Arc<Mutex<String>>, key: &str) {
    if !SYNC_SETTING_KEYS.contains(&key) { return; }
    {
        let conn = db.lock().unwrap();
        let v = conn.query_row(
            "SELECT value FROM settings WHERE key=?1", params![key], |r| r.get::<_, String>(0)
        ).unwrap_or_default();
        sync::write_setting_stamped(&conn, key, &v, chrono::Local::now().timestamp_millis());
    } // 锁释放
    sync::push_change(db, url, "setting", key);
}

// v4.10 示例任务 seed：首次启动时检测 task 表为空则注入 5 条
// 让用户开箱就有东西测（每日 / 限时 / 次数 三类全覆盖）。
// 依赖 exists_for_seed 标记，一个端 seed 之后另一个端 sync 时不会重复注入。
fn exists_for_seed(db: &Connection) -> bool {
    db.query_row("SELECT value FROM settings WHERE key='seed_v4'", [], |r| r.get::<_, String>(0))
        .ok().map(|s| s == "1").unwrap_or(false)
}
fn mark_seeded(db: &Connection) {
    db.execute(
        "INSERT INTO settings(key,value) VALUES('seed_v4','1') \
         ON CONFLICT(key) DO UPDATE SET value='1'", []
    ).ok();
}
// 把"这周X / 下周X" 转换成毫秒时间戳
fn next_weekday_ms(target_weekday: u32, hour: u32, minute: u32) -> i64 {
    let now = chrono::Local::now();
    let mut n = now;
    // chrono 的周一=0... 我们转成西方周一=1 的口径
    let cur = n.weekday().number_from_monday();
    let mut delta = (target_weekday + 7 - cur) % 7;
    if delta == 0 {
        // 避免"恰好今天" 的语义模糊：今天若 hour:minute 已过则算下一周
        let today_target = n.with_hour(hour).and_then(|d| d.with_minute(minute)).and_then(|d| d.with_second(0));
        if let Some(t) = today_target {
            if t.timestamp_millis() <= now.timestamp_millis() {
                delta = 7;
            }
        }
    }
    let target = n.checked_add_signed(chrono::Duration::days(delta as i64))
        .and_then(|d| d.with_hour(hour))
        .and_then(|d| d.with_minute(minute))
        .and_then(|d| d.with_second(0));
    target.map(|d| d.timestamp_millis()).unwrap_or(now.timestamp_millis())
}

#[tauri::command]
fn seed_default_tasks(state: tauri::State<AppState>) -> serde_json::Value {
    let now = chrono::Local::now().timestamp_millis();
    let count: i64 = {
        let db = state.db.lock().unwrap();
        if exists_for_seed(&db) { return serde_json::json!({ "seeded": false, "reason": "already" }); }
        let n: i64 = db.query_row("SELECT COUNT(*) FROM tasks WHERE deleted=0", [], |r| r.get(0))
            .unwrap_or(0);
        n
    };
    if count > 0 {
        // 表里已有数据（可能是 sync 后端带来的），仅打标不再注，避免污染用户库
        let db = state.db.lock().unwrap();
        mark_seeded(&db);
        return serde_json::json!({ "seeded": false, "reason": "non_empty" });
    }
    // 注 5 条
    let next_tue_18 = next_weekday_ms(2, 18, 0);   // 周二 18:00
    let next_thu_18 = next_weekday_ms(4, 18, 0);   // 周四 18:00
    let items: Vec<(&str, &str, &str, &str, Option<i64>, Option<i64>, Option<i64>)> = vec![
        // (title, category, type, priority, deadline_ms, count, reward_points)
        ("晨跑 30 分钟",       "daily",         "habit",  "medium", None,            None, Some(8)),
        ("每日读书 1 小时",     "daily",         "habit",  "high",   None,            None, Some(12)),
        ("写周报",             "time-limited",  "repeat", "medium", Some(next_tue_18),None, Some(14)),
        ("通读+做笔记+复习",   "once",          "once",   "high",   None,            Some(5), Some(15)),
        ("准备季度汇报",       "once",          "once",   "high",   Some(next_thu_18),Some(3), Some(15)),
    ];
    let inserted: usize = {
        let db = state.db.lock().unwrap();
        let mut n = 0usize;
        for (title, cat, typ, prio, deadline, cnt, rp) in &items {
            let uuid = uuid::Uuid::new_v4().to_string();
            let rp_v = {
                let cv = cnt.unwrap_or(1);
                rp.unwrap_or_else(|| reward_points_for(typ, prio, cv))
            };
            let cnt_v = cnt.unwrap_or(1);
            let deadline_v = deadline.unwrap_or(now);
            // daily：due_at 与 deadline 都置 None；repeat：deadline = due_at 都置 deadline
            let due_at = if *cat == "daily" { None } else { Some(deadline_v) };
            let dl = if *cat == "time-limited" { Some(deadline_v) } else { None };
            let due_sql = due_at.map(|v| v as i64);
            let dl_sql = dl.map(|v| v as i64);
            let res = db.execute(
                "INSERT INTO tasks (uuid,type,title,desc,category,priority,due_at,deadline,repeat_rule,count,done_count,track_status,reward_points,created_at,updated_at,deleted) \
                 VALUES (?1,?2,?3,'',?4,?5,?6,?7,?8,?9,0,'pending',?10,?11,?11,0)",
                params![
                    &uuid, typ, title, cat, prio,
                    due_sql, dl_sql,
                    if *cat == "daily" { Some("daily") } else { None },
                    cnt_v, rp_v, now
                ]
            );
            if res.is_ok() { n += 1; }
        }
        // 给"每日读书 1 小时" + "通读+做笔记+复习" 各加几个示例步骤（让用户能看到步骤 UI 怎么用）
        let book_uuid: Option<String> = db.query_row(
            "SELECT uuid FROM tasks WHERE title='每日读书 1 小时' LIMIT 1", [], |r| r.get(0)
        ).ok();
        if let Some(t) = book_uuid {
            for (i, (s_title, label, value)) in [
                ("通读章节", "用时", "30 分钟"),
                ("整理笔记", "结果", "≥5 条要点"),
                ("做自测题", "用时", "15 分钟"),
            ].iter().enumerate() {
                let su = uuid::Uuid::new_v4().to_string();
                let _ = db.execute(
                    "INSERT INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,created_at,updated_at,deleted) \
                     VALUES (?1,?2,?3,'todo',?4,?5,?6,?7,?7,0)",
                    params![su, t, s_title, label, value, i as i64, now]
                );
            }
        }
        let read_uuid: Option<String> = db.query_row(
            "SELECT uuid FROM tasks WHERE title='通读+做笔记+复习' LIMIT 1", [], |r| r.get(0)
        ).ok();
        if let Some(t) = read_uuid {
            for (i, (s_title, label, value)) in [
                ("通读第 1 章", "用时", "30 分钟"),
                ("整理笔记", "", ""),
                ("做自测题", "结果", "90 分以上"),
            ].iter().enumerate() {
                let su = uuid::Uuid::new_v4().to_string();
                let _ = db.execute(
                    "INSERT INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,created_at,updated_at,deleted) \
                     VALUES (?1,?2,?3,'todo',?4,?5,?6,?7,?7,0)",
                    params![su, t, s_title, label, value, i as i64, now]
                );
            }
        }
        mark_seeded(&db);
        n
    };
    serde_json::json!({ "seeded": inserted > 0, "inserted": inserted })
}

#[tauri::command]
fn get_setting(state: tauri::State<AppState>, key: String) -> Option<String> {
    let db = state.db.lock().unwrap();
    db.query_row("SELECT value FROM settings WHERE key=?1", params![&key], |r| r.get::<_, String>(0)).ok()
}

// v5.14h.13：实时 ws 连接状态查询（修 boss 反馈"明明一个网络但显示未配对"——
//   settings.pairing 只是缓存，与 ws_loop 实际连接脱钩）
#[tauri::command]
fn is_ws_connected() -> bool {
    sync::WS_CONNECTED.load(std::sync::atomic::Ordering::Relaxed)
}
// v5.14h.13：实时 ws 远端地址（连接中时 = 配置的 server_url；未连接 = 空）
#[tauri::command]
fn get_ws_peer(state: tauri::State<AppState>) -> String {
    if sync::WS_CONNECTED.load(std::sync::atomic::Ordering::Relaxed) {
        state.server_url.lock().unwrap().clone()
    } else {
        String::new()
    }
}

// v5.14h.15：推送头像 emoji 到手机（桌面切换 emoji 头像时自动同步到手机 prefs）
// v5.15.7：同时在本地 settings 记一份（带时间戳）—— 手机端全量同步时能拿到，
//   且手机端选 emoji 时桌面也能通过 settings.avatar_emoji 还原成同款头像
#[tauri::command]
fn push_avatar_emoji(state: tauri::State<AppState>, emoji: String) {
    {
        let conn = state.db.lock().unwrap();
        sync::write_setting_stamped(&conn, "avatar_emoji", &emoji, chrono::Local::now().timestamp_millis());
    } // 锁释放
    // 已配对时把 emoji 作为 setting 变更推给手机（/api/sync/changes，带 updated_at）
    sync::push_change(&state.db, &state.server_url, "setting", "avatar_emoji");
    let url_arc = state.server_url.clone();
    std::thread::spawn(move || {
        let base = {
            let g = url_arc.lock().unwrap();
            sync::server_base(&g)
        };
        if base.is_empty() { return; }
        // v5.17.0：带签名 + 加密（否则手机会 401）
        let body = serde_json::json!({"key":"avatar_emoji","value":emoji}).to_string();
        let _ = crate::sync::secure_post(&base, "/api/settings/upsert", &body, 4);
    });
}

#[tauri::command]
fn save_pairing(state: tauri::State<AppState>, url: String, device_id: String) {
    // v5.15 P0：持久化配对时把手机 deviceId 也存上（旧实现忽略了 device_id 参数）
    if !device_id.is_empty() {
        *state.device_id.lock().unwrap() = device_id.clone();
        // v5.15.19：同步到进程级，供 ws_loop 反 POST /api/pair 时带上
        sync::set_paired_device_id(&device_id);
    }
    *state.server_url.lock().unwrap() = url;
    save_pairing_to_disk(&state);
}

#[tauri::command]
fn load_pairing(state: tauri::State<AppState>) -> Option<String> {
    let url = state.server_url.lock().unwrap().clone();
    if url.is_empty() { None } else { Some(url) }
}

// =============== mDNS 自动发现手机（_taskguide._tcp.local.） ===============
#[tauri::command]
fn discover_devices(timeout_ms: u64) -> Vec<serde_json::Value> {
    use mdns_sd::{ServiceDaemon, ServiceEvent};
    let mut out: Vec<serde_json::Value> = Vec::new();
    let Ok(daemon) = ServiceDaemon::new() else { return out };
    let Ok(receiver) = daemon.browse("_taskguide._tcp.local.") else {
        let _ = daemon.shutdown();
        return out;
    };
    let deadline = std::time::Instant::now()
        + std::time::Duration::from_millis(timeout_ms.clamp(1000, 8000));
    while std::time::Instant::now() < deadline {
        match receiver.recv_timeout(std::time::Duration::from_millis(300)) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                let addr = info.get_addresses().iter()
                    .find(|a| a.is_ipv4())
                    .map(|a| a.to_string()).unwrap_or_default();
                let port = info.get_port();
                let name = info.get_fullname();
                // v5.15 P0：读 mDNS TXT 里的 deviceId/deviceName —— 手机端 MdnsRegistrar
                // 注册时带 deviceId(ANDROID_ID) + deviceName(型号)。桌面端用 deviceId
                // 识别"同一台手机"，这样手机 IP 变了（DHCP 重分配）也能自动重连。
                let device_id = info.get_property_val_str("deviceId").unwrap_or("").to_string();
                let device_name = info.get_property_val_str("deviceName").unwrap_or("").to_string();
                if !addr.is_empty() {
                    out.push(serde_json::json!({
                        "name": name,
                        "addr": addr,
                        "port": port,
                        "url": format!("http://{}:{}", addr, port),
                        "deviceId": device_id,
                        "deviceName": device_name
                    }));
                }
            }
            Ok(_) => {}
            Err(_) => std::thread::sleep(std::time::Duration::from_millis(50)),
        }
    }
    let _ = daemon.shutdown();
    out
}

#[tauri::command]
fn set_window_size(window: tauri::Window, w: f64, h: f64) {
    use tauri::PhysicalSize;
    let _ = window.set_size(tauri::Size::Physical(PhysicalSize::new(w as u32, h as u32)));
}

// =============== 配对持久化（读 ~/.taskguide/pairing.json） ===============
fn pairing_file(state: &AppState) -> std::path::PathBuf {
    let mut dir = std::path::PathBuf::from(state.config_dir.lock().unwrap().clone());
    if !dir.exists() { let _ = std::fs::create_dir_all(&dir); }
    dir.push("pairing.json");
    dir
}
fn save_pairing_to_disk(state: &AppState) {
    let path = pairing_file(state);
    let url = state.server_url.lock().unwrap().clone();
    let device = state.device_id.lock().unwrap().clone();
    if let Ok(json) = serde_json::to_string_pretty(&serde_json::json!({
        "url": url, "deviceId": device, "savedAt": chrono::Local::now().timestamp_millis(),
        // v5.17.0：长期密钥 master（配对时由手机端下发），重启后要恢复
        "master": crate::sync::master_hex()
    })) {
        let _ = std::fs::write(&path, json);
    }
}
fn clear_pairing_from_disk(state: &AppState) -> std::io::Result<()> {
    let path = pairing_file(state);
    if path.exists() { std::fs::remove_file(&path) } else { Ok(()) }
}
fn load_pairing_from_disk(state: &AppState) -> Option<String> {
    let path = pairing_file(state);
    if !path.exists() { return None; }
    let raw = std::fs::read_to_string(&path).ok()?;
    let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
    let url = v.get("url")?.as_str()?.to_string();
    // v5.17.0：恢复长期密钥（老 pairing.json 无 master 字段 → 需要重新配对一次）
    if let Some(m) = v.get("master").and_then(|x| x.as_str()) {
        if !crate::sync::set_master_hex(m) {
            crate::sync::sync_debug_log("pairing.json 的 master 格式非法，视为未配对");
        }
    }
    // v5.15 P0：读回手机 deviceId（若旧 pairing.json 只有 url 没有 deviceId，则保持空）
    if let Some(d) = v.get("deviceId").and_then(|x| x.as_str()) {
        if !d.is_empty() {
            *state.device_id.lock().unwrap() = d.to_string();
            // v5.15.19：同步到进程级，供 ws_loop 反 POST /api/pair 时带上
            sync::set_paired_device_id(d);
        }
    }
    Some(url)
}

// v5.14g 自动重连守护线程（v5.14a 基础上补强）：
// 每 5s：ping 已配对 url → 成功则清失败计数；失败（手机换 IP / 断网恢复 / 手机刚开机）→
// mDNS 重新发现 _taskguide._tcp. 中 deviceId 匹配的手机 → 自动重连（换 url + 反 POST /api/pair + full_sync）。
// 连续 ping 失败 6 次（约 30s）写 settings.reconnect_fail=1，前端读取后 toast 提示"配对手机不在网络"
// 断网→联网自动重连、"同一网络自动连接" 都由它兜底。
fn auto_reconnect_loop(
    db: Arc<Mutex<rusqlite::Connection>>,
    url: Arc<Mutex<String>>,
    device_id: Arc<Mutex<String>>,
    config_dir: String,
) {
    use mdns_sd::{ServiceDaemon, ServiceEvent};
    std::thread::sleep(std::time::Duration::from_secs(6)); // 等 ws_loop 先跑一次
    let mut fail_count: i32 = 0;
    let mut pull_tick: i32 = 0;   // v5.15.18：每 3 次 ping（15s）做一次兜底全量拉取
    loop {
        // v5.15.19：断连期把轮询间隔从 5s 收到 2s —— 手机刚连上 WiFi 时要尽快抓到，
        //   不能让用户等七八秒。连上后（fail_count==0）回落到 5s 省电。
        // v5.15.23 D2（boss：「现在很卡，不知道是不是因为一直扫描连接手机端」）——
        //   实测断连时这一轮 = 2s sleep + 3s ping 超时 + 最多 12s mDNS 扫描（还每轮新建/销毁
        //   ServiceDaemon），几乎是**持续满载**在扫。改为：轮询 4s；mDNS 重发现改为每 3 轮一次
        //   （≈12s 一次），扫描窗口封顶 5s。发现速度只慢一点点，CPU/组播开销降一个量级。
        let sleep_secs: u64 = if fail_count > 0 { 4 } else { 5 };
        std::thread::sleep(std::time::Duration::from_secs(sleep_secs));
        let cur_url = { url.lock().unwrap().clone() };
        if cur_url.is_empty() {
            // 未配对：清 fail（避免旧状态误导）
            fail_count = 0;
            continue;
        }
        let did = { device_id.lock().unwrap().clone() };

        // 1) ping 当前 url：通 → 清计数
        // v5.15.19：连续失败时把 ping 超时从 3s 收到 1.5s —— 目标 IP 已失效时，
        //   等待 3s 超时纯属浪费（每轮 5s 间隔 + 3s 超时 = 8s 才有一次扫描机会）。
        let ping_timeout = if fail_count >= 3 { 1500 } else { 3000 };
        let base = sync::server_base(&cur_url);
        let alive = crate::sync::lan_client()
            .get(format!("{}/api/ping", base))
            .timeout(std::time::Duration::from_millis(ping_timeout))
            .send()
            .map(|r| r.status().is_success())
            .unwrap_or(false);
        if alive {
            if fail_count != 0 {
                fail_count = 0;
                let conn = db.lock().unwrap();
                let _ = conn.execute("UPDATE settings SET value='0' WHERE key='reconnect_fail'", []);
            }
            // v5.15.18 P0：周期性兜底拉取。
            //   原先只有"WS 刚连上时补一次 full_sync"——若 WS 静默死掉（socket 没报错、
            //   ping 仍通，比如 WiFi 漫游/手机休眠），桌面端就**永久不再同步**。
            //   这里每 15s（3×5s）无条件拉一次全量，作为最终一致性兜底。
            //   LWW 只在两端都改过同一条时才判定，正常情况是幂等的空操作。
            pull_tick += 1;
            if pull_tick >= 3 {
                pull_tick = 0;
                if !cur_url.is_empty() {
                    if sync::full_sync(&db, &url).is_ok() { sync::notify_changed(); }
                }
            }
            continue;
        }
        fail_count += 1;
        // 连续 ~30s 连不上 → 前端 toast 提示（每 5 次写一次，避免刷屏）
        if fail_count % 6 == 0 {
            let conn = db.lock().unwrap();
            let _ = conn.execute("INSERT OR REPLACE INTO settings(key,value) VALUES('reconnect_fail','1')", []);
        }

        // 2) ping 不通 → mDNS 重新发现（同一 network 自动连）
        //
        // v5.15.19 根因修复（boss：「连上网后应短时间内自动扫描并连上，而不是等我手动点扫描设备」）：
        //   旧实现每轮只扫 4s 就放弃，且失败后要再等 5s(ping超时)+4s 下一轮 ——
        //   而 Android NSD 注册/广播有 1~5s 延迟，手机刚连上 WiFi 时那 4s 窗口极容易空手而归，
        //   于是连续几轮扫不到 → 前端持续弹「手机不在网络」→ 用户以为坏了只能手动点。
        //   改法：扫描窗口按失败轮次自适应放大（4s → 8s → 12s 封顶），
        //   一旦发现过设备就回落到最短窗口（说明 mDNS 通道正常，不用长扫）。
        // v5.15.23 D2：mDNS 重发现改为每 3 轮才做一次（≈12s），扫描窗口 4s→5s 封顶；
        //   中间那两轮只 ping（轻量），避免"一直在扫"把 CPU 吃满。
        if fail_count % 3 != 1 {
            continue;
        }
        let scan_secs: u64 = 5;
        log::info!("配对目标不可达({}), 尝试 mDNS 重发现 deviceId={} (扫描 {}s)", base, did, scan_secs);
        let Ok(daemon) = ServiceDaemon::new() else { continue };
        let Ok(receiver) = daemon.browse("_taskguide._tcp.local.") else {
            let _ = daemon.shutdown();
            continue;
        };
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(scan_secs);
        let mut found: Option<String> = None;         // 候选 url（含 deviceId 匹配的优先）
        let mut found_no_id: Option<String> = None;    // 兜底：没有 deviceId 但服务存在
        while std::time::Instant::now() < deadline {
            match receiver.recv_timeout(std::time::Duration::from_millis(200)) {
                Ok(ServiceEvent::ServiceResolved(info)) => {
                    let addr = info.get_addresses().iter()
                        .find(|a| a.is_ipv4()).map(|a| a.to_string()).unwrap_or_default();
                    if addr.is_empty() { continue; }
                    let port = info.get_port();
                    let cand_url = format!("http://{}:{}", addr, port);
                    let cand_did = info.get_property_val_str("deviceId").unwrap_or("").to_string();
                    if !did.is_empty() && cand_did == did {
                        found = Some(cand_url);
                        break;
                    }
                    if cand_did.is_empty() && found_no_id.is_none() {
                        found_no_id = Some(cand_url);   // 旧版手机端无 deviceId，兜底用
                    }
                }
                Ok(_) => {}
                Err(_) => {}
            }
        }
        let _ = daemon.shutdown();
        let new_url = found.or(found_no_id);
        if let Some(nu) = new_url {
            log::info!("mDNS 重新发现手机 {}，自动重连", nu);
            { *url.lock().unwrap() = nu.clone(); }
            // v5.14g：重连成功 → 清失败计数
            fail_count = 0;
            { let conn = db.lock().unwrap(); let _ = conn.execute("UPDATE settings SET value='0' WHERE key='reconnect_fail'", []); }
            // v5.17.0：此处原会"反 POST /api/pair"补写手机端的 paired_device。
            //   该接口已随配对改版移除（改为弹窗确认），且自动重连属于"已配对设备重连"，
            //   手机端本来就知道自己配过谁 —— 因此整段删除。
            // full_sync 拉手机数据到本地（last-write-wins 在 sync::full_sync 内做）
            let _ = sync::full_sync(&db, &url);
            // 更新 pairing.json 里的 url（保留 deviceId）
            let mut dir = std::path::PathBuf::from(config_dir.clone());
            if !dir.exists() { let _ = std::fs::create_dir_all(&dir); }
            dir.push("pairing.json");
            if let Ok(raw) = std::fs::read_to_string(&dir) {
                if let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&raw) {
                    v["url"] = serde_json::Value::String(nu);
                    let _ = std::fs::write(&dir, serde_json::to_string_pretty(&v).unwrap_or_default());
                }
            }
        }
    }
}

// =============== 紧急任务后台 tick（每 60s） ===============
fn start_emergency_tick(state: &AppState) {
    let db_arc = state.db.clone();
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(Duration::from_secs(60));
            let now = chrono::Local::now().timestamp_millis();
            let (eta_short_pct, eta_long_h, on) = {
                let db = db_arc.lock().unwrap();
                let on = db.query_row(
                    "SELECT value FROM settings WHERE key='emergency_on'", [], |r| r.get::<_, String>(0)
                ).unwrap_or_else(|_| "1".into()) == "1";
                let eta_short_pct: i64 = db.query_row(
                    "SELECT value FROM settings WHERE key='eta_short_pct'", [], |r| r.get::<_, String>(0)
                ).ok().and_then(|s| s.parse().ok()).unwrap_or(30);
                let eta_long_h: i64 = db.query_row(
                    "SELECT value FROM settings WHERE key='eta_long_h'", [], |r| r.get::<_, String>(0)
                ).ok().and_then(|s| s.parse().ok()).unwrap_or(36);
                (eta_short_pct, eta_long_h, on)
            };
            if !on { continue; }

            let candidates: Vec<(String, String, i64)> = {
                let db = db_arc.lock().unwrap();
                let mut stmt = db.prepare(
                    "SELECT uuid, title, deadline FROM tasks WHERE track_status != 'done' AND deleted=0 \
                     AND deadline IS NOT NULL AND deadline > ?1"
                ).unwrap();
                stmt.query_map(params![now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
                    .unwrap().filter_map(|x| x.ok()).collect()
            };

            for (uuid, title, deadline) in candidates {
                let total = (deadline - now).max(0);
                let total_h = total as f64 / 3_600_000.0;
                let remain = deadline - now;
                let mut trigger = false;
                let mut rule = String::new();
                if total_h <= 48.0 {
                    if total_h > 0.0 {
                        let pct = (remain as f64) / (total as f64);
                        if pct <= (eta_short_pct as f64) / 100.0 {
                            trigger = true;
                            rule = format!("≤48h: 阈值 {}%", eta_short_pct);
                        }
                    }
                } else {
                    let remain_h = remain as f64 / 3_600_000.0;
                    if remain_h <= eta_long_h as f64 {
                        trigger = true;
                        rule = format!(">48h: 剩 {}h", remain_h.round() as i64);
                    }
                }
                if trigger {
                    log::warn!("[emergency] {} - 剩 {}ms - {}", title, remain, rule);
                    // 实际弹窗在前端处理（前端有 settings 状态可读），后端只 log + 系统通知
                    let _ = uuid; // 占位
                }
            }
        }
    });
}

// =============== 启动 ===============
#[cfg_attr(mobile, tauri::mobile_entry_point)]
// v5.15.12：单实例保护 —— 防止「同时存在两个客户端」（boss：点挂件又打开了一个）
//
// 首个实例占住 127.0.0.1:53911；后来的实例连上去发 SHOW，让已有实例唤起主窗，然后自己退出。
// 纯 std::net，零新依赖。（验证用的第二实例可用 TASKGUIDE_NO_SINGLE_INSTANCE=1 旁路）
const INSTANCE_PORT: u16 = 53911;

fn single_instance_guard() -> bool {
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    if std::env::var("TASKGUIDE_NO_SINGLE_INSTANCE").is_ok() {
        return true;
    }
    match TcpListener::bind(("127.0.0.1", INSTANCE_PORT)) {
        Ok(listener) => {
            std::thread::spawn(move || {
                for stream in listener.incoming() {
                    if let Ok(mut st) = stream {
                        let mut buf = [0u8; 32];
                        let _ = st.read(&mut buf);
                        if let Some(h) = sync::APP_HANDLE.get() {
                            // 两份 clone：run_on_main_thread 借用一份，闭包 move 另一份
                            let app_cb = h.clone();
                            let app_run = h.clone();
                            let _ = app_run.run_on_main_thread(move || {
                                if let Some(w) = app_cb.get_webview_window("main") {
                                    let _ = w.show();
                                    let _ = w.unminimize();
                                    let _ = w.set_focus();
                                }
                            });
                        }
                    }
                }
            });
            true
        }
        Err(_) => {
            if let Ok(mut st) = TcpStream::connect(("127.0.0.1", INSTANCE_PORT)) {
                let _ = st.write_all(b"SHOW\n");
            }
            false
        }
    }
}

pub fn run() {
    // v5.15.12：已有实例在跑就不再起第二个进程（boss 反馈出现两个「任务栏」）
    if !single_instance_guard() {
        std::process::exit(0);
    }
    // db 固定放 exe 同目录（不随 CWD 漂移）：否则从快捷方式/其他工作目录启动时
    // 会在 CWD 下新建空库，造成多个分裂 taskguide.db（验证期实测踩坑）
    let exe_dir = std::env::current_exe().ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| std::env::current_dir().unwrap());
    let db_path = exe_dir.join("taskguide.db");
    let conn = Connection::open(&db_path).expect("打开数据库失败");
    conn.execute_batch(include_str!("../db/schema.sql")).expect("建表失败");
    migrate(&conn);
    // 清理已废弃的内置 AI 设置（外部 AI 改用「添加 JSON」粘贴导入）
    conn.execute("DELETE FROM settings WHERE key IN ('ai_api_key','ai_cloud_enabled')", []).ok();
    let _ = conn.execute_batch(
        "INSERT OR IGNORE INTO settings(key,value) VALUES \
         ('theme','frosted'),('track_limit','3'),('tracking_max','3'),\
         ('emergency_on','1'),('eta_short_pct','30'),('eta_long_h','36'),\
         ('auto_start','1'),('delay_options','custom'),('total_points','0'),\
         ('nickname','历练者'),('progress_style','bar'),\
         ('count_default','5'),('time_limit_min','5'),\
         ('daily_refresh','1'),('night_notify','1'),\
         ('reconnect_fail','0');"
    );
    let conn = Arc::new(Mutex::new(conn));

    let config_dir = std::env::var("TASKGUIDE_HOME")
        .unwrap_or_else(|_| format!("{}/.taskguide", std::env::var("USERPROFILE").unwrap_or_else(|_| ".".into())));

    let device_id = String::new();  // v5.15 P0：改为空；配对成功时写入手机 ANDROID_ID（见 connect_server）
    let state = AppState {
        db: conn.clone(),
        server_url: Arc::new(Mutex::new(String::new())),
        device_id: Arc::new(Mutex::new(device_id)),
        config_dir: Arc::new(Mutex::new(config_dir.clone())),
    };

    // 启动时尝试读取已配对的 url + 手机 deviceId
    if let Some(url) = load_pairing_from_disk(&state) {
        *state.server_url.lock().unwrap() = url.clone();
        log::info!("自动加载配对：{}", url);
    }

    // 启动 WS 后台同步线程
    {
        let db_arc = state.db.clone();
        let url_arc = state.server_url.clone();
        std::thread::spawn(move || {
            sync::ws_loop(db_arc, url_arc);
        });
    }

    // v5.15 P0：自动重连守护线程（配对后周期 ping → 失败 → mDNS 重新发现同 deviceId → 换 url 重连）
    {
        let db_arc = state.db.clone();
        let url_arc = state.server_url.clone();
        let did_arc = state.device_id.clone();
        let cfg = config_dir.clone();
        std::thread::spawn(move || {
            auto_reconnect_loop(db_arc, url_arc, did_arc, cfg);
        });
    }

    // 启动紧急任务 tick 线程
    let state_tick = state_clone(&state);
    start_emergency_tick(&state_tick);

    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent, None
        ))
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            // v5.15.7：保存 AppHandle —— ws_loop 收到手机端变更后 emit "sync-applied"，
            // 前端立刻重渲染（不再等 setInterval(render, 15000) 轮询）
            let _ = sync::APP_HANDLE.set(app.handle().clone());
            Ok(())
        })
        .manage(state)

        .invoke_handler(tauri::generate_handler![
            get_track_cards, get_today_tasks, get_archive, get_progress, get_next_reminder,
            get_habits_status, get_task_detail, get_total_points,
            get_tasks_by_category,
            get_level, get_daily_progress, check_night_notify, dismiss_night_notify,
            advance_step, add_step, import_steps, complete_task, delete_task, add_task, update_task, start_tracking, stop_tracking,
            delay_task,
            restore_task,
            set_display_mode, set_window_size,
            show_widget, hide_widget, show_main_window, get_widget_visible, win_minimize, win_toggle_maximize, win_hide, win_start_dragging,
            connect_server, disconnect_server, get_server_url,
            pair_request, pair_poll,   // v5.17.0 双向确认配对
            set_setting, get_setting, save_pairing, load_pairing,
            is_ws_connected, get_ws_peer, push_avatar_emoji,
            discover_devices, seed_default_tasks,
            notify_desktop
        ])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                window.hide().ok();
            }
        })
        .run(tauri::generate_context!())
        .expect("启动 Tauri 失败");
}


/// v5.26.1：发 Windows 系统通知（弹窗会被全屏软件挡住 → 12 个测试身份死于这一点）
#[tauri::command]
fn notify_desktop(app: tauri::AppHandle, title: String, body: String) -> Result<(), String> {
    use tauri_plugin_notification::NotificationExt;
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|e| e.to_string())
}

// helper：把 &AppState 转成只引用结构供 tauri::State::new 调用
fn state_clone(s: &AppState) -> AppState {
    AppState {
        db: s.db.clone(),
        server_url: s.server_url.clone(),
        device_id: s.device_id.clone(),
        config_dir: s.config_dir.clone(),
    }
}
