// 任务栏 桌面端 Rust 后端 v3
// Tauri 2 + rusqlite + 同步客户端
#![allow(dead_code)]

use rusqlite::{Connection, params, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{Manager, WindowEvent};

mod sync;

// =============== 数据结构 ===============
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
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
    pub created_at: i64,
    pub updated_at: i64,
    pub deleted: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
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
        "60min" => Some(60 * 60 * 1000),
        "6h" => Some(6 * 3600 * 1000),
        "1d" => Some(86400 * 1000),
        "3d" => Some(3 * 86400 * 1000),
        "7d" => Some(7 * 86400 * 1000),
        "30d" => Some(30 * 86400 * 1000),
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

#[tauri::command]
fn advance_step(state: tauri::State<AppState>, step_uuid: String) {
    let now = chrono::Local::now().timestamp_millis();
    let task_uuid = {
        let db = state.db.lock().unwrap();
        db.execute("UPDATE steps SET status='done', done_at=?1, updated_at=?2 WHERE uuid=?3",
            params![now, now, &step_uuid]).ok();
        let task_uuid: String = db.query_row(
            "SELECT task_uuid FROM steps WHERE uuid=?1", params![&step_uuid], |r| r.get(0)
        ).unwrap_or_default();
        let next: Option<String> = db.query_row(
            "SELECT uuid FROM steps WHERE task_uuid=?1 AND status!='done' AND deleted=0 ORDER BY sort_order LIMIT 1",
            params![&task_uuid], |r| r.get(0)
        ).ok();
        if let Some(nu) = next {
            db.execute("UPDATE steps SET status='doing', updated_at=?1 WHERE uuid=?2",
                params![now, &nu]).ok();
        } else {
            // 全部步骤完成 → 任务完成 + 加积分
            db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2 WHERE uuid=?3",
                params![now, now, &task_uuid]).ok();
            // 累加积分
            let rp: i64 = db.query_row(
                "SELECT reward_points FROM tasks WHERE uuid=?1", params![&task_uuid], |r| r.get(0)
            ).unwrap_or(10);
            db.execute(
                "INSERT INTO settings(key,value) VALUES('total_points',?1) \
                 ON CONFLICT(key) DO UPDATE SET value=printf('%d', CAST(value AS INTEGER) + ?2)",
                params![(rp + 0).to_string(), rp]
            ).ok();
        }
        task_uuid
    };
    sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
}

#[tauri::command]
fn complete_task(state: tauri::State<AppState>, task_uuid: String) {
    let now = chrono::Local::now().timestamp_millis();
    let rp: i64 = {
        let db = state.db.lock().unwrap();
        let rp: i64 = db.query_row(
            "SELECT reward_points FROM tasks WHERE uuid=?1", params![&task_uuid], |r| r.get(0)
        ).unwrap_or(10);
        db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2 WHERE uuid=?3",
            params![now, now, &task_uuid]).ok();
        db.execute("UPDATE steps SET status='done', done_at=?1, updated_at=?2 WHERE task_uuid=?3 AND status!='done'",
            params![now, now, &task_uuid]).ok();
        rp
    };
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
) -> serde_json::Value {
    let now = chrono::Local::now().timestamp_millis();
    let uuid = uuid::Uuid::new_v4().to_string();
    let cat = category.unwrap_or_else(|| "once".into());
    let typ = category_to_type(&cat).to_string();
    let prio = priority.unwrap_or_else(|| "medium".into());
    let ddl = deadline_key.as_deref().unwrap_or("none");
    let deadline_ms = deadline_key_to_ms(ddl);
    let deadline = deadline_ms.map(|d| now + d);
    {
        let db = state.db.lock().unwrap();
        db.execute(
            "INSERT INTO tasks (uuid,type,title,category,priority,deadline,track_status,created_at,updated_at) \
             VALUES (?1,?2,?3,?4,?5,?6,'pending',?7,?7)",
            params![&uuid, &typ, &title, &cat, &prio, &deadline, now]
        ).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &uuid);
    serde_json::json!({ "uuid": uuid, "status": "ok" })
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
    let db = state.db.lock().unwrap();
    let max = tracking_max(&db);
    let cur: i64 = db.query_row(
        "SELECT COUNT(*) FROM tasks WHERE track_status='tracking' AND deleted=0", [], |r| r.get(0)
    ).unwrap_or(0);
    if cur >= max {
        return Err(format!("已达追踪上限（{}）", max));
    }
    db.execute("UPDATE tasks SET track_status='tracking', updated_at=?1 WHERE uuid=?2",
        params![now, &task_uuid]).ok();
    let next: Option<String> = db.query_row(
        "SELECT uuid FROM steps WHERE task_uuid=?1 AND status!='done' AND deleted=0 ORDER BY sort_order LIMIT 1",
        params![&task_uuid], |r| r.get(0)
    ).ok();
    if let Some(nu) = next {
        db.execute("UPDATE steps SET status='doing', updated_at=?1 WHERE uuid=?2",
            params![now, &nu]).ok();
    }
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
    let db_path = std::env::current_dir().unwrap().join("taskguide.db");
    let conn = Connection::open(&db_path).expect("打开数据库失败");
    conn.execute_batch(include_str!("../db/schema.sql")).expect("建表失败");
    let _ = conn.execute_batch(
        "INSERT OR IGNORE INTO settings(key,value) VALUES \
         ('theme','frosted'),('track_limit','3'),('tracking_max','3'),\
         ('emergency_on','1'),('eta_short_pct','30'),('eta_long_h','36'),\
         ('auto_start','1'),('delay_options','custom'),('total_points','0');"
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
            advance_step, complete_task, delete_task, add_task, start_tracking, stop_tracking,
            set_display_mode, set_window_size,
            connect_server, disconnect_server, get_server_url,
            set_setting, get_setting, save_pairing, load_pairing
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
