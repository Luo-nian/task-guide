// 任务指南 桌面端 Rust 后端
// Tauri 2 + rusqlite + 同步客户端
#![allow(dead_code)]

use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use tauri::{Manager, WindowEvent};

mod sync;

// ==================== 数据结构（与手机端 schema 对应） ====================
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub uuid: String,
    #[serde(rename = "type")]
    pub task_type: String,
    pub title: String,
    pub desc: String,
    pub category: String,
    pub priority: String,
    pub due_at: Option<i64>,
    pub repeat_rule: Option<String>,
    pub deadline: Option<i64>,
    pub track_status: String,
    pub done: i64,
    pub done_at: Option<i64>,
    pub delayed_count: i64,
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

// ==================== 数据库操作 ====================
pub struct AppState {
    pub db: Arc<Mutex<Connection>>,
    pub server_url: Arc<Mutex<String>>, // http://ip:port
}

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

// ==================== Tauri Commands ====================

#[tauri::command]
fn get_track_cards(state: tauri::State<AppState>) -> Vec<TrackCard> {
    let db = state.db.lock().unwrap();
    let mut stmt = db.prepare(
        "SELECT * FROM tasks WHERE track_status='tracking' AND deleted=0 ORDER BY updated_at DESC"
    ).unwrap();
    let tasks: Vec<Task> = stmt.query_map([], row_to_task).unwrap()
        .filter_map(|r| r.ok()).collect();
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
         CASE track_status WHEN 'tracking' THEN 0 ELSE 1 END, due_at IS NULL, due_at ASC"
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
    {
        let db = state.db.lock().unwrap();
        // 当前步骤 → done
        db.execute("UPDATE steps SET status='done', done_at=?1, updated_at=?2 WHERE uuid=?3",
            params![now, now, &step_uuid]).ok();
        // 找下一个 todo
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
            // 全部完成 → 任务完成
            db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2 WHERE uuid=?3",
                params![now, now, &task_uuid]).ok();
        }
    }
    // 推送给手机
    sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
}

#[tauri::command]
fn complete_task(state: tauri::State<AppState>, task_uuid: String) {
    let now = chrono::Local::now().timestamp_millis();
    {
        let db = state.db.lock().unwrap();
        db.execute("UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?2 WHERE uuid=?3",
            params![now, now, &task_uuid]).ok();
        db.execute("UPDATE steps SET status='done', done_at=?1, updated_at=?2 WHERE task_uuid=?3 AND status!='done'",
            params![now, now, &task_uuid]).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
}

#[tauri::command]
fn add_task(state: tauri::State<AppState>, title: String) {
    let now = chrono::Local::now().timestamp_millis();
    let uuid = uuid::Uuid::new_v4().to_string();
    {
        let db = state.db.lock().unwrap();
        db.execute(
            "INSERT INTO tasks (uuid,type,title,track_status,created_at,updated_at) VALUES (?1,'once',?2,'pending',?3,?3)",
            params![&uuid, &title, now]
        ).ok();
    }
    sync::push_change(&state.db, &state.server_url, "task", &uuid);
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
    // 触发全量同步
    match sync::full_sync(&state) {
        Ok(_) => "已连接".to_string(),
        Err(e) => format!("连接失败: {}", e),
    }
}

#[tauri::command]
fn get_server_url(state: tauri::State<AppState>) -> String {
    state.server_url.lock().unwrap().clone()
}

// ==================== 启动 ====================
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // 初始化本地数据库
    let db_path = std::env::current_dir().unwrap().join("taskguide.db");
    let conn = Connection::open(&db_path).expect("打开数据库失败");
    conn.execute_batch(include_str!("../db/schema.sql")).expect("建表失败");
    // 默认设置（忽略已存在）
    let _ = conn.execute_batch(
        "INSERT OR IGNORE INTO settings(key,value) VALUES('theme','frosted'),('track_limit','3');"
    );

    let state = AppState {
        db: Arc::new(Mutex::new(conn)),
        server_url: Arc::new(Mutex::new(String::new())),
    };

    // 启动后台 WS 监听线程（轻量，断连自动重试）
    {
        let db_arc = state.db.clone();
        let url_arc = state.server_url.clone();
        std::thread::spawn(move || {
            sync::ws_loop(db_arc, url_arc);
        });
    }

    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent, None
        ))
        .plugin(tauri_plugin_notification::init())
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            get_track_cards, get_today_tasks, get_progress, get_next_reminder,
            get_habits_status, advance_step, complete_task, add_task,
            set_display_mode, connect_server, get_server_url
        ])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                // 关闭按钮 → 最小化到托盘
                api.prevent_close();
                window.hide().ok();
            }
        })
        .run(tauri::generate_context!())
        .expect("启动 Tauri 失败");
}
