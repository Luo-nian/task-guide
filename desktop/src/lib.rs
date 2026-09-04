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
use chrono::{Datelike, Local, TimeZone, Timelike};

mod sync;

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
    pub reward_points: i64,
    #[serde(default)]
    pub reminder_strength: Option<String>,  // 每任务提醒强度：standard|repeat|alarm；null=跟随默认
    pub created_at: i64,
    pub updated_at: i64,
    pub deleted: i64,
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

fn read_setting(db: &Connection, key: &str, default: &str) -> String {
    db.query_row("SELECT value FROM settings WHERE key=?1", params![key], |r| r.get::<_, String>(0))
        .unwrap_or_else(|_| default.to_string())
}

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
    if Local::now().hour() < 22 {
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
    Ok(Task {
        uuid: r.get("uuid")?,
        task_type: r.get("type")?,
        title: r.get("title")?,
        desc: r.get("desc")?,
        category: r.get("category")?,
        priority: r.get("priority")?,
        due_at: r.get("due_at")?,
        repeat_rule: r.get("repeat_rule")?,
        deadline: r.get("deadline")?,
        track_status: r.get("track_status")?,
        done: r.get("done")?,
        done_at: r.get("done_at")?,
        delayed_count: r.get("delayed_count")?,
        reward_points: r.get("reward_points")?,
        reminder_strength: r.get("reminder_strength").ok(),
        created_at: r.get("created_at")?,
        updated_at: r.get("updated_at")?,
        deleted: r.get("deleted")?,
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
            "SELECT * FROM steps WHERE task_uuid=?1 AND status='doing' AND deleted=0 ORDER BY sort_order LIMIT 1"
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
    let mut stmt = db.prepare(
        "SELECT * FROM tasks WHERE track_status!='done' AND deleted=0 ORDER BY \
         CASE track_status WHEN 'tracking' THEN 0 ELSE 1 END, \
         CASE WHEN deadline IS NOT NULL THEN 0 ELSE 1 END, \
         deadline IS NULL, deadline ASC, due_at ASC"
    ).unwrap();
    stmt.query_map([], row_to_task).unwrap().filter_map(|r| r.ok()).collect()
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
    let now = chrono::Local::now().timestamp_millis();
    let start = now - 86_400_000;
    let total: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND (due_at BETWEEN ?1 AND ?2 OR track_status='tracking')",
        params![start, now], |r| r.get(0)
    ).unwrap_or(0);
    let done: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND track_status='done' AND done_at BETWEEN ?1 AND ?2",
        params![start, now], |r| r.get(0)
    ).unwrap_or(0);
    ProgressInfo { done_today: done, total_today: total }
}

// 每日进度（今日任务完成度）—— 前端进度条 / 祝福弹窗的数据源
// 口径：daily 全部 + 今日到期的 time-limited；已完成与未完成都要计入 total，
// 否则每完成一项 total 就减 1，进度条永远停在 0%（预览服务器的旧实现就有这个 bug）。
#[tauri::command]
fn get_daily_progress(state: tauri::State<AppState>) -> serde_json::Value {
    let (day_start, day_end) = today_range();
    let now = Local::now().timestamp_millis();
    let db = state.db.lock().unwrap();

    // 普通任务（count<=1）分开统计，次数任务单独按「次数」计
    // 未完成：daily 全部 + 今日到期的限时任务
    let pending: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND track_status!='done' \
         AND COALESCE(count,1) <= 1 AND \
         (category='daily' OR (category='time-limited' AND COALESCE(due_at,deadline) IS NOT NULL \
          AND COALESCE(due_at,deadline) >= ?1 AND COALESCE(due_at,deadline) < ?2))",
        params![day_start, day_end], |r| r.get(0)
    ).unwrap_or(0);

    // 已完成：done_at 落在今天且属于 daily / time-limited
    let finished: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND track_status='done' \
         AND COALESCE(count,1) <= 1 AND \
         done_at IS NOT NULL AND done_at >= ?1 AND done_at < ?2 AND \
         (category='daily' OR category='time-limited')",
        params![day_start, day_end], |r| r.get(0)
    ).unwrap_or(0);

    // 次数任务（如「喝水 8 次」）：按 done_count / count 计入，
    // 未完成的全部计入，已完成的只算今天完成的（否则历史次数任务会天天累加）
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

    let total = pending + finished + count_total;
    let mut done = finished + count_done;
    let mut over = 0;
    if done > total { over = done - total; done = total; }

    let week: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE deleted=0 AND done_at IS NOT NULL AND done_at >= ?1",
        params![now - 7 * 86_400_000], |r| r.get(0)
    ).unwrap_or(0);

    let style = read_setting(&db, "progress_style", "bar");
    serde_json::json!({ "done": done, "total": total, "over": over, "week": week, "style": style })
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
            }
        }
        t_uuid
    };
    sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
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
        if already == 0 {
            {
                let db = state.db.lock().unwrap();
                db.execute("INSERT INTO habit_logs(task_uuid,check_date,created_at) VALUES(?1,?2,?3)",
                    params![&task_uuid, &date, now]).ok();
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
        return serde_json::json!({ "status": "ok", "partial": false, "habit": true });
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
    // 累加积分
    {
        let db = state.db.lock().unwrap();
        db.execute(
            "INSERT INTO settings(key,value) VALUES('total_points',?1) \
             ON CONFLICT(key) DO UPDATE SET value=printf('%d', CAST(value AS INTEGER) + ?2)",
            params![rp.to_string(), rp]
        ).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
    serde_json::json!({ "status": "ok", "partial": false })
}

#[tauri::command]
fn delete_task(state: tauri::State<AppState>, task_uuid: String) {
    let now = chrono::Local::now().timestamp_millis();
    {
        let db = state.db.lock().unwrap();
        db.execute("UPDATE tasks SET deleted=1, updated_at=?1 WHERE uuid=?2", params![now, &task_uuid]).ok();
        db.execute("UPDATE steps SET deleted=1, updated_at=?1 WHERE task_uuid=?2", params![now, &task_uuid]).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
}

#[tauri::command]
fn add_task(
    state: tauri::State<AppState>,
    title: String,
    category: Option<String>,
    deadline_key: Option<String>,
    priority: Option<String>,
    count: Option<i64>,
) -> serde_json::Value {
    let now = chrono::Local::now().timestamp_millis();
    let uuid = uuid::Uuid::new_v4().to_string();
    let cat = category.unwrap_or_else(|| "once".into());
    let typ = category_to_type(&cat).to_string();
    let prio = priority.unwrap_or_else(|| "medium".into());
    let ddl = deadline_key.as_deref().unwrap_or("none");
    let deadline_ms = deadline_key_to_ms(ddl);
    let deadline = deadline_ms.map(|d| now + d);
    // 次数任务：前端传 count（如「喝水 8 次」），默认 1
    let cnt = count.unwrap_or(1).max(1);
    // 积分规则与手机端 RewardRules 对齐：类型基础分 + 优先级加成
    let rp = reward_points_for(&typ, &prio);
    {
        let db = state.db.lock().unwrap();
        // 限时任务同时写 deadline 与 due_at：前端渲染读 due_at，跨端同步用 deadline，
        // 只写一列会导致「今日到期」判定与详情页显示对不上
        db.execute(
            "INSERT INTO tasks (uuid,type,title,category,priority,due_at,deadline,count,done_count,track_status,reward_points,created_at,updated_at) \
             VALUES (?1,?2,?3,?4,?5,?6,?6,?7,0,'pending',?9,?8,?8)",
            params![&uuid, &typ, &title, &cat, &prio, &deadline, cnt, now, rp]
        ).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &uuid);
    serde_json::json!({ "uuid": uuid, "status": "ok" })
}

/** 积分规则（与手机端 com.taskbar.app.data.model.RewardRules 对齐）：
 *  类型基础分 once8/repeat10/habit5/note2/goal15 + 优先级加成 high7/medium4/low1 */
fn reward_points_for(task_type: &str, priority: &str) -> i64 {
    let base = match task_type {
        "repeat" => 10,
        "goal" => 15,
        "habit" => 5,
        "note" => 2,
        _ => 8, // once
    };
    let bonus = match priority {
        "high" => 7,
        "low" => 1,
        _ => 4, // medium
    };
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
fn win_minimize(window: tauri::Window) { let _ = window.minimize(); }
#[tauri::command]
fn win_toggle_maximize(window: tauri::Window) {
    if window.is_maximized().unwrap_or(false) {
        let _ = window.unmaximize();
    } else {
        let _ = window.maximize();
    }
}
#[tauri::command]
fn win_hide(window: tauri::Window) { let _ = window.hide(); }

#[tauri::command]
fn connect_server(state: tauri::State<AppState>, url: String) -> String {
    *state.server_url.lock().unwrap() = url.clone();
    save_pairing_to_disk(&state);
    // 启动时也尝试立即同步一次
    let db_arc = state.db.clone();
    let url_arc = state.server_url.clone();
    std::thread::spawn(move || {
        let _ = sync::full_sync(&db_arc, &url_arc);
    });
    "已连接".to_string()
}

#[tauri::command]
fn disconnect_server(state: tauri::State<AppState>) {
    *state.server_url.lock().unwrap() = String::new();
    let _ = clear_pairing_from_disk(&state);
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

#[tauri::command]
fn stop_tracking(state: tauri::State<AppState>, task_uuid: String) {
    let now = chrono::Local::now().timestamp_millis();
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

#[tauri::command]
fn set_setting(state: tauri::State<AppState>, key: String, value: String) {
    let db = state.db.lock().unwrap();
    db.execute(
        "INSERT INTO settings(key,value) VALUES(?1,?2) \
         ON CONFLICT(key) DO UPDATE SET value=?2",
        params![&key, &value]
    ).ok();
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
            let rp_v = rp.unwrap_or_else(|| reward_points_for(typ, prio));
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

#[tauri::command]
fn save_pairing(state: tauri::State<AppState>, url: String, device_id: String) {
    let _ = device_id;
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
                if !addr.is_empty() {
                    out.push(serde_json::json!({
                        "name": name,
                        "addr": addr,
                        "port": port,
                        "url": format!("http://{}:{}", addr, port)
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
        "url": url, "deviceId": device, "savedAt": chrono::Local::now().timestamp_millis()
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
    if let Some(d) = v.get("deviceId").and_then(|x| x.as_str()) {
        *state.device_id.lock().unwrap() = d.to_string();
    }
    Some(url)
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
pub fn run() {
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
         ('daily_refresh','1'),('night_notify','1');"
    );
    let conn = Arc::new(Mutex::new(conn));

    let config_dir = std::env::var("TASKGUIDE_HOME")
        .unwrap_or_else(|_| format!("{}/.taskguide", std::env::var("USERPROFILE").unwrap_or_else(|_| ".".into())));

    let device_id = uuid::Uuid::new_v4().to_string();

    let state = AppState {
        db: conn.clone(),
        server_url: Arc::new(Mutex::new(String::new())),
        device_id: Arc::new(Mutex::new(device_id)),
        config_dir: Arc::new(Mutex::new(config_dir.clone())),
    };

    // 启动时尝试读取已配对的 url
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

    // 启动紧急任务 tick 线程
    let state_tick = state_clone(&state);
    start_emergency_tick(&state_tick);

    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent, None
        ))
        .plugin(tauri_plugin_notification::init())
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            get_track_cards, get_today_tasks, get_archive, get_progress, get_next_reminder,
            get_habits_status, get_task_detail, get_total_points,
            get_level, get_daily_progress, check_night_notify, dismiss_night_notify,
            advance_step, add_step, import_steps, complete_task, delete_task, add_task, start_tracking, stop_tracking,
            set_display_mode, set_window_size,
            connect_server, disconnect_server, get_server_url,
            set_setting, get_setting, save_pairing, load_pairing,
            discover_devices, seed_default_tasks
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

// helper：把 &AppState 转成只引用结构供 tauri::State::new 调用
fn state_clone(s: &AppState) -> AppState {
    AppState {
        db: s.db.clone(),
        server_url: s.server_url.clone(),
        device_id: s.device_id.clone(),
        config_dir: s.config_dir.clone(),
    }
}
