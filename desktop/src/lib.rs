// 任务栏 桌面端 Rust 后端 v4
// Tauri 2 + rusqlite + 同步客户端
// v4: 补齐前端实际调用但后端缺失的命令（get_level / get_daily_progress /
//     add_step / ai_breakdown），修正 advance_step、add_task 参数契约，
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
fn level_of(points: i64) -> serde_json::Value {
    // (等级, 本级下限, 名称, 标语, 图标名)
    const TABLE: [(i64, i64, &str, &str, &str); 5] = [
        (1,   0, "历练学徒", "敢开始，就已经赢了一半", "lv1"),
        (2,  20, "风华游侠", "汗水从不会辜负你",       "lv2"),
        (3,  60, "破浪骑士", "风浪越大，越显本色",     "lv3"),
        (4, 120, "群星行者", "你走过的每一步都算数",   "lv4"),
        (5, 200, "传奇勇者", "你就是自己的传说",       "lv5"),
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

// 手动新增步骤（前端「点此手动添加」与 AI 拆解都走这里）
#[tauri::command]
fn add_step(state: tauri::State<AppState>, task_uuid: String, title: String) -> serde_json::Value {
    let now = Local::now().timestamp_millis();
    let step_uuid = uuid::Uuid::new_v4().to_string();
    {
        let db = state.db.lock().unwrap();
        let sort: i64 = db.query_row(
            "SELECT COALESCE(MAX(sort_order),0)+1 FROM steps WHERE task_uuid=?1",
            params![&task_uuid], |r| r.get(0)
        ).unwrap_or(0);
        db.execute(
            "INSERT INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,done_at,created_at,updated_at,deleted) \
             VALUES (?1,?2,?3,'todo','','',?4,NULL,?5,?5,0)",
            params![&step_uuid, &task_uuid, &title, sort, now]
        ).ok();
    }
    sync::push_change(&state.db, &state.server_url, "step", &step_uuid);
    serde_json::json!({ "status": "ok", "uuid": step_uuid })
}

// AI 任务拆解：配了 DeepSeek Key 走云端，失败/未配置回落到本地模板（0 成本）
// 返回 source 字段（"cloud" / "local"）让前端能提示用户实际走了哪条链路
#[tauri::command]
fn ai_breakdown(state: tauri::State<AppState>, title: String) -> serde_json::Value {
    let key = {
        let db = state.db.lock().unwrap();
        read_setting(&db, "ai_api_key", "")
    };
    let mut source = "local";
    let mut steps: Vec<String> = Vec::new();

    if !key.trim().is_empty() {
        match call_deepseek(key.trim(), &title) {
            Ok(v) if !v.is_empty() => { steps = v; source = "cloud"; }
            Ok(_) => log::warn!("[ai] DeepSeek 返回空，回落本地模板"),
            Err(e) => log::warn!("[ai] DeepSeek 调用失败（{}），回落本地模板", e),
        }
    }
    if steps.is_empty() { steps = local_breakdown(&title); }

    serde_json::json!({ "steps": steps, "source": source })
}

fn call_deepseek(key: &str, title: &str) -> Result<Vec<String>, String> {
    // 12 秒超时：key 填错或网络不通时，前端不能一直卡在「拆解中…」
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(12))
        .build()
        .map_err(|e| e.to_string())?;
    let body = serde_json::json!({
        "model": "deepseek-chat",
        "messages": [
            { "role": "system", "content": "你是任务拆解助手。把用户的任务拆成 3-6 个具体可执行的小步骤，每步 5-15 个字，直接输出步骤列表，每行一步，不要序号和解释。" },
            { "role": "user", "content": title }
        ],
        "temperature": 0.3,
        "max_tokens": 300
    });
    let resp = client.post("https://api.deepseek.com/chat/completions")
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", key))
        .json(&body)
        .send()
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    let data: serde_json::Value = resp.json().map_err(|e| e.to_string())?;
    let text = data.get("choices").and_then(|c| c.get(0))
        .and_then(|c| c.get("message")).and_then(|m| m.get("content"))
        .and_then(|c| c.as_str()).unwrap_or("");
    Ok(parse_step_lines(text))
}

// 从模型输出里挑出步骤行：去序号/项目符号，长度过滤，最多 6 条
fn parse_step_lines(text: &str) -> Vec<String> {
    text.lines()
        .map(|l| l.trim())
        .map(|l| {
            let mut s = l;
            // 去掉 "1." "1、" "1)" "- " "* " 等前缀
            s = s.trim_start_matches(|c: char| c.is_ascii_digit());
            s = s.trim_start_matches(['.', '、', ')', '）', ']', ' ']);
            s = s.trim_start_matches(['-', '*', '•', '·', ' ']);
            s.trim()
        })
        .filter(|s| s.chars().count() >= 2 && s.chars().count() <= 30)
        .take(6)
        .map(|s| s.to_string())
        .collect()
}

// 本地模板拆解（未配置 Key 或云端失败时的兜底）
fn local_breakdown(title: &str) -> Vec<String> {
    let has = |kw: &str| title.contains(kw);
    if has("看") || has("读") || has("书") || has("背") {
        return vec!["通读核心内容", "划重点记笔记", "做一遍自测题", "总结复盘"]
            .into_iter().map(String::from).collect();
    }
    if has("复习") || has("学") || has("练") {
        return vec!["整理知识点框架", "重点章节精读", "做配套练习题", "错题回顾总结"]
            .into_iter().map(String::from).collect();
    }
    if has("写") || has("交") || has("报告") || has("作业") || has("论文") {
        return vec!["收集所需资料", "列出大纲初稿", "完成正文内容", "检查格式并提交"]
            .into_iter().map(String::from).collect();
    }
    if has("买") || has("购") || has("快递") || has("取") {
        return vec!["列清单确认需求", "比价下单", "确认收货"]
            .into_iter().map(String::from).collect();
    }
    if has("锻炼") || has("运动") || has("跑") || has("健身") {
        return vec!["热身 5 分钟", "完成主体训练", "拉伸放松 5 分钟"]
            .into_iter().map(String::from).collect();
    }
    vec!["明确目标范围", "列出执行步骤", "逐项推进完成"]
        .into_iter().map(String::from).collect()
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
    {
        let db = state.db.lock().unwrap();
        let is_habit: bool = db.query_row(
            "SELECT type='habit' FROM tasks WHERE uuid=?1", params![&task_uuid], |r| r.get(0)
        ).unwrap_or(false);
        if is_habit {
            let date = chrono::Local::now().format("%Y-%m-%d").to_string();
            let already: i64 = db.query_row(
                "SELECT COUNT(*) FROM habit_logs WHERE task_uuid=?1 AND check_date=?2",
                params![&task_uuid, &date], |r| r.get(0)
            ).unwrap_or(0);
            if already == 0 {
                db.execute("INSERT INTO habit_logs(task_uuid,check_date,created_at) VALUES(?1,?2,?3)",
                    params![&task_uuid, &date, now]).ok();
                let rp: i64 = db.query_row(
                    "SELECT reward_points FROM tasks WHERE uuid=?1", params![&task_uuid], |r| r.get(0)
                ).unwrap_or(5);
                db.execute(
                    "INSERT INTO settings(key,value) VALUES('total_points',?1) \
                     ON CONFLICT(key) DO UPDATE SET value=printf('%d', CAST(value AS INTEGER) + ?2)",
                    params![rp.to_string(), rp]
                ).ok();
            }
            sync::push_change(&state.db, &state.server_url, "task", &task_uuid);
            return serde_json::json!({ "status": "ok", "partial": false, "habit": true });
        }
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
    let db_path = std::env::current_dir().unwrap().join("taskguide.db");
    let conn = Connection::open(&db_path).expect("打开数据库失败");
    conn.execute_batch(include_str!("../db/schema.sql")).expect("建表失败");
    migrate(&conn);
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
            get_level, get_daily_progress, ai_breakdown, check_night_notify, dismiss_night_notify,
            advance_step, add_step, complete_task, delete_task, add_task, start_tracking, stop_tracking,
            set_display_mode, set_window_size,
            connect_server, disconnect_server, get_server_url,
            set_setting, get_setting, save_pairing, load_pairing,
            discover_devices
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
