// 同步客户端：连接手机端服务器
use crate::{Task, Step};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
// v5.14h.13：ws 连接实时状态（解决 boss 反馈"明明一个网络但显示未配对"——
//   之前 settings.pairing 只是缓存，与 ws_loop 实际连接脱钩）
pub static WS_CONNECTED: AtomicBool = AtomicBool::new(false);
// v5.15.7：主窗口 AppHandle（收到手机端变更后 emit 事件，前端立即重渲染而不是等 15s 轮询）
pub static APP_HANDLE: std::sync::OnceLock<tauri::AppHandle> = std::sync::OnceLock::new();
use std::time::Duration;

fn now_ms() -> i64 { chrono::Local::now().timestamp_millis() }

/// 通知前端"数据库被远端变更改写了" → app3.js 监听 sync-applied 立刻 render()
pub fn notify_changed() {
    if let Some(h) = APP_HANDLE.get() {
        use tauri::Emitter;
        let _ = h.emit("sync-applied", ());
    }
}

/// settings 表里的同步时间戳 key（用于"最新为主"的 last-write-wins）
pub fn setting_ts_key(key: &str) -> String { format!("sync_ts_{}", key) }

/// 读某个 setting 的同步时间戳（毫秒）；没有则 0
pub fn read_setting_ts(conn: &Connection, key: &str) -> i64 {
    conn.query_row(
        "SELECT value FROM settings WHERE key=?1", params![setting_ts_key(key)],
        |r| r.get::<_, String>(0)
    ).ok().and_then(|s| s.parse::<i64>().ok()).unwrap_or(0)
}

/// 写 setting + 打时间戳（本地修改方调用；随后应由调用方 push_change("setting", key)）
pub fn write_setting_stamped(conn: &Connection, key: &str, value: &str, ts: i64) {
    let _ = conn.execute(
        "INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=?2",
        params![key, value]
    );
    let _ = conn.execute(
        "INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=?2",
        params![setting_ts_key(key), ts.to_string()]
    );
}

/// 收到远端 setting 变更：远端时间戳更新（或相等）才覆盖 —— "最新为主"
pub fn apply_setting_remote(conn: &Connection, key: &str, value: &str, ts: i64) -> bool {
    if ts < read_setting_ts(conn, key) { return false; }
    write_setting_stamped(conn, key, value, ts);
    true
}

// ===== v5.15.7 P0：桌面 → 手机 推送时字段名必须是 camelCase =====
// 桌面 Task/Step 的 Serialize 统一是 snake_case（前端 app3.js 依赖），直接推给手机，
// 手机 kotlinx 解码时字段全对不上 → 抛异常 → "电脑端完成任务/推进步骤，手机端毫无反应"。
// 这里加一层只用于推送的 camelCase DTO。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TaskSyncDto {
    uuid: String,
    #[serde(rename = "type")]
    task_type: String,
    title: String,
    desc: String,
    category: String,
    priority: String,
    due_at: Option<i64>,
    repeat_rule: Option<String>,
    deadline: Option<i64>,
    track_status: String,
    done: i64,
    done_at: Option<i64>,
    delayed_count: i64,
    reward_points: i64,
    reminder_strength: Option<String>,
    created_at: i64,
    updated_at: i64,
    deleted: i64,
    /// 手机端里程碑进度字段（= 桌面次数任务的 done_count / count）
    progress: i64,
    target: i64,
}

impl From<&Task> for TaskSyncDto {
    fn from(t: &Task) -> Self {
        TaskSyncDto {
            uuid: t.uuid.clone(),
            task_type: t.task_type.clone(),
            title: t.title.clone(),
            desc: t.desc.clone(),
            category: t.category.clone(),
            priority: t.priority.clone(),
            due_at: t.due_at,
            repeat_rule: t.repeat_rule.clone(),
            deadline: t.deadline,
            track_status: t.track_status.clone(),
            done: t.done,
            done_at: t.done_at,
            delayed_count: t.delayed_count,
            reward_points: t.reward_points,
            reminder_strength: t.reminder_strength.clone(),
            created_at: t.created_at,
            updated_at: t.updated_at,
            deleted: t.deleted,
            progress: t.done_count,
            target: if t.count > 1 { t.count } else { 1 },
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StepSyncDto {
    uuid: String,
    task_uuid: String,
    title: String,
    status: String,
    attr_label: String,
    attr_value: String,
    sort_order: i64,
    done_at: Option<i64>,
    created_at: i64,
    updated_at: i64,
    deleted: i64,
}

impl From<&Step> for StepSyncDto {
    fn from(s: &Step) -> Self {
        StepSyncDto {
            uuid: s.uuid.clone(),
            task_uuid: s.task_uuid.clone(),
            title: s.title.clone(),
            status: s.status.clone(),
            attr_label: s.attr_label.clone(),
            attr_value: s.attr_value.clone(),
            sort_order: s.sort_order,
            done_at: s.done_at,
            created_at: s.created_at,
            updated_at: s.updated_at,
            deleted: s.deleted,
        }
    }
}

#[derive(Serialize, Deserialize, Debug)]
pub struct ChangeOp {
    pub op: String,
    pub entity: String,
    pub uuid: String,
    pub data: Option<String>,
}

#[derive(Deserialize, Debug)]
struct FullSyncPayload {
    tasks: Vec<Task>,
    steps: Vec<Step>,
    #[serde(rename = "habit_logs")]
    habit_logs: Vec<HabitLog>,
    /// v5.15.7：手机端全量 settings（积分/等级、头像等）。老版本手机不传也不报错。
    #[serde(default)]
    settings: Vec<SettingKV>,
    #[serde(rename = "server_time")]
    server_time: i64,
}

#[derive(Deserialize, Debug)]
struct SettingKV {
    key: String,
    value: String,
    #[serde(default)]
    updated_at: i64,
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]  // 手机端 HabitLog 序列化为 camelCase（taskUuid/checkDate/createdAt）
struct HabitLog {
    task_uuid: String,
    check_date: String,
    created_at: i64,
}

/// v5.15.7：手机端推送的打卡变更（同 HabitLog，但 id 等额外字段忽略）
#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct HabitLogChange {
    task_uuid: String,
    check_date: String,
    created_at: i64,
}

pub fn server_base(url: &str) -> String {
    url.trim_end_matches('/').to_string()
}

/// v5.15.7：手机端 Task 没有 count/doneCount 字段（里程碑用 progress/target），
/// 从手机拉回同一个任务时会把本地"次数任务"进度抹成 count=1/done_count=0。
/// 落库前做一次保护：远端看起来是默认值时，保留本地已有的次数进度。
fn preserve_count_progress(conn: &Connection, t: &mut Task) {
    if t.count > 1 { return; }
    if let Ok((lc, ld)) = conn.query_row(
        "SELECT COALESCE(count,1), COALESCE(done_count,0) FROM tasks WHERE uuid=?1",
        params![&t.uuid], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?))
    ) {
        if lc > 1 {
            t.count = lc;
            t.done_count = ld.max(t.done_count);
        }
    }
}

/// 全量同步：首次连接拉取所有数据写入本地
pub fn full_sync(db: &Arc<Mutex<Connection>>, url: &Arc<Mutex<String>>) -> Result<(), String> {
    let base = {
        let g = url.lock().map_err(|e| e.to_string())?;
        server_base(&g)
    };
    if base.is_empty() { return Err("未设置服务器地址".into()); }
    let resp: FullSyncPayload = reqwest::blocking::Client::new()
        .get(format!("{}/api/sync/full", base))
        .timeout(Duration::from_secs(10))
        .send().map_err(|e| e.to_string())?
        .json().map_err(|e| e.to_string())?;

    let conn = db.lock().map_err(|e| e.to_string())?;
    for mut t in resp.tasks {
        // v5.15 P0 last-write-wins：本地的 task 更新（updated_at 更大）则不覆盖
        let local_newer = conn.query_row(
            "SELECT updated_at FROM tasks WHERE uuid=?1", params![&t.uuid],
            |r| r.get::<_, i64>(0)
        ).ok().map(|u| u > t.updated_at).unwrap_or(false);
        if local_newer { continue; }
        preserve_count_progress(&conn, &mut t);
        let _ = conn.execute(
            "INSERT OR REPLACE INTO tasks (uuid,type,title,desc,category,priority,due_at,repeat_rule,deadline,track_status,done,done_at,delayed_count,count,done_count,reward_points,reminder_strength,created_at,updated_at,deleted) \
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20)",
            params![t.uuid, t.task_type, t.title, t.desc, t.category, t.priority, t.due_at, t.repeat_rule, t.deadline, t.track_status, t.done, t.done_at, t.delayed_count, t.count, t.done_count, t.reward_points, t.reminder_strength, t.created_at, t.updated_at, t.deleted]
        );
    }
    for s in resp.steps {
        let local_newer = conn.query_row(
            "SELECT updated_at FROM steps WHERE uuid=?1", params![&s.uuid],
            |r| r.get::<_, i64>(0)
        ).ok().map(|u| u > s.updated_at).unwrap_or(false);
        if local_newer { continue; }
        let _ = conn.execute(
            "INSERT OR REPLACE INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,done_at,created_at,updated_at,deleted) \
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![s.uuid, s.task_uuid, s.title, s.status, s.attr_label, s.attr_value, s.sort_order, s.done_at, s.created_at, s.updated_at, s.deleted]
        );
    }
    for h in resp.habit_logs {
        let _ = conn.execute(
            "INSERT OR IGNORE INTO habit_logs (task_uuid,check_date,created_at) VALUES (?1,?2,?3)",
            params![h.task_uuid, h.check_date, h.created_at]
        );
    }
    // v5.15.7：settings 也做 last-write-wins（积分/等级、头像等跨端一致）
    for s in resp.settings {
        if s.key.starts_with("sync_ts_") { continue; }
        apply_setting_remote(&conn, &s.key, &s.value, s.updated_at);
    }
    Ok(())
}

/// 推送本地变更到手机
pub fn push_change(db: &Arc<Mutex<Connection>>, url: &Arc<Mutex<String>>, entity: &str, uuid: &str) {
    let base = {
        let guard = url.lock().unwrap();
        server_base(&guard)
    };
    if base.is_empty() { return; }
    // 读本地最新数据。v5.15.7 加固：只有"查不到这一行"才算删除；
    //   解析/序列化失败一律不推（曾经误判成 delete，把手机端任务直接删掉 —— 数据丢失级事故）
    let (data, is_delete) = {
        let conn = db.lock().unwrap();
        match entity {
            "task" => {
                match conn.query_row("SELECT * FROM tasks WHERE uuid=?1", params![uuid],
                    |r| crate::row_to_task(r)).ok() {
                    None => (None, true),
                    Some(t) => match serde_json::to_string(&TaskSyncDto::from(&t)) {
                        Ok(s) => (Some(s), false),
                        Err(e) => { log::warn!("task 序列化失败，跳过推送: {} {}", uuid, e); (None, false) }
                    },
                }
            }
            "step" => {
                match conn.query_row("SELECT * FROM steps WHERE uuid=?1", params![uuid],
                    |r| crate::row_to_step(r)).ok() {
                    None => (None, true),
                    Some(s) => match serde_json::to_string(&StepSyncDto::from(&s)) {
                        Ok(s2) => (Some(s2), false),
                        Err(e) => { log::warn!("step 序列化失败，跳过推送: {} {}", uuid, e); (None, false) }
                    },
                }
            }
            // v5.15.7：settings 类实体（积分/等级、头像等）——
            //   data = {"key":...,"value":...,"updated_at":...}，uuid 就是 setting key
            "setting" => {
                match conn.query_row("SELECT value FROM settings WHERE key=?1", params![uuid],
                    |r| r.get::<_, String>(0)).ok() {
                    None => (None, false),
                    Some(v) => {
                        let ts = read_setting_ts(&conn, uuid);
                        let body = serde_json::json!({
                            "key": uuid,
                            "value": v,
                            "updated_at": if ts > 0 { ts } else { now_ms() }
                        }).to_string();
                        (Some(body), false)
                    }
                }
            }
            _ => (None, false),
        }
    };
    if !is_delete && data.is_none() { return; }   // 解析失败：什么都不做
    let op = if is_delete { "delete" } else { "upsert" };
    let change = ChangeOp { op: op.into(), entity: entity.into(), uuid: uuid.into(), data };
    let body = serde_json::json!({ "changes": [change], "client_time": chrono::Local::now().timestamp_millis() });
    let url = format!("{}/api/sync/changes", base);
    // 网络推送放后台线程：手机不在线时最多拖 5s 超时，若阻塞在主线程，点「添加步骤/完成任务」
    // 会卡住整个 UI 直到超时（boss 实测感知为「按了没反应」）。这里立即返回、推送失败不影响主流程。
    std::thread::spawn(move || {
        let _ = reqwest::blocking::Client::new()
            .post(url)
            .timeout(Duration::from_secs(5))
            .json(&body)
            .send();
    });

    // v5.15.18 P2（boss：「在客户端里点追踪任务，挂件的同步有点慢；挂件上操作同步到客户端也有点慢」）：
    //   本地任何一次变更（16 个 push_change 调用点全覆盖）都广播 widget-refresh，
    //   让挂件窗口立刻刷新，而不是干等它自己的 5 秒轮询。
    //   刻意**不**发 sync-applied —— 那个事件主窗会走 800ms 去抖 + 头像/昵称比对，
    //   本地操作本来就会自己 render()，再发一次只会多一遍重绘（反而制造闪）。
    if let Some(h) = APP_HANDLE.get() {
        use tauri::Emitter;
        let _ = h.emit("widget-refresh", ());
    }
}

/// WS 监听循环：连接手机 ws，收变更写入本地；断连 5s 重试
pub fn ws_loop(db: Arc<Mutex<Connection>>, url: Arc<Mutex<String>>) {
    loop {
        let base = {
            let g = url.lock().unwrap();
            server_base(&g)
        };
        if base.is_empty() {
            std::thread::sleep(Duration::from_secs(5));
            continue;
        }
        let ws_url = base.replace("http://", "ws://") + "/ws";
        WS_CONNECTED.store(false, Ordering::Relaxed);
        match tungstenite::connect(&ws_url) {
            Ok((mut socket, _)) => {
                log::info!("WS 已连接: {}", ws_url);
                WS_CONNECTED.store(true, Ordering::Relaxed);
                // v5.15.7：刚连上时补一次全量拉取 —— ws 断开期间手机端的改动不会补发，
                //   靠这次 pull 收敛（LWW 保证本地更新的不会被覆盖）
                {
                    let db2 = db.clone();
                    let url2 = url.clone();
                    std::thread::spawn(move || {
                        if full_sync(&db2, &url2).is_ok() { notify_changed(); }
                    });
                }
                use tungstenite::Message;
                loop {
                    match socket.read() {
                        Ok(Message::Text(txt)) => {
                            if let Ok(op) = serde_json::from_str::<ChangeOp>(&txt) {
                                apply_change(&db, &op);
                                // v5.15.7：立刻通知前端刷新（否则要等 setInterval 15s 轮询）
                                notify_changed();
                            }
                        }
                        Ok(Message::Ping(_)) | Ok(Message::Pong(_)) => {}
                        Ok(_) => {}
                        Err(_) => break,
                    }
                }
                WS_CONNECTED.store(false, Ordering::Relaxed);
            }
            Err(_) => { WS_CONNECTED.store(false, Ordering::Relaxed); }
        }
        std::thread::sleep(Duration::from_secs(5));
    }
}

fn apply_change(db: &Arc<Mutex<Connection>>, op: &ChangeOp) {
    let conn = match db.lock() { Ok(c) => c, Err(_) => return };
    match (op.op.as_str(), op.entity.as_str()) {
        ("upsert", "task") => {
            if let Some(d) = &op.data {
                if let Ok(mut t) = serde_json::from_str::<Task>(d) {
                    // v5.15 P0 last-write-wins：本地的 task 更新则不覆盖
                    let local_newer = conn.query_row(
                        "SELECT updated_at FROM tasks WHERE uuid=?1", params![&t.uuid],
                        |r| r.get::<_, i64>(0)
                    ).ok().map(|u| u > t.updated_at).unwrap_or(false);
                    if local_newer { return; }
                    preserve_count_progress(&conn, &mut t);
                    let _ = conn.execute(
                        "INSERT OR REPLACE INTO tasks (uuid,type,title,desc,category,priority,due_at,repeat_rule,deadline,track_status,done,done_at,delayed_count,reward_points,reminder_strength,created_at,updated_at,deleted) \
                         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)",
                        params![t.uuid, t.task_type, t.title, t.desc, t.category, t.priority, t.due_at, t.repeat_rule, t.deadline, t.track_status, t.done, t.done_at, t.delayed_count, t.reward_points, t.reminder_strength, t.created_at, t.updated_at, t.deleted]
                    );
                }
            }
        }
        ("upsert", "step") => {
            if let Some(d) = &op.data {
                if let Ok(s) = serde_json::from_str::<Step>(d) {
                    let local_newer = conn.query_row(
                        "SELECT updated_at FROM steps WHERE uuid=?1", params![&s.uuid],
                        |r| r.get::<_, i64>(0)
                    ).ok().map(|u| u > s.updated_at).unwrap_or(false);
                    if local_newer { return; }
                    let _ = conn.execute(
                        "INSERT OR REPLACE INTO steps (uuid,task_uuid,title,status,attr_label,attr_value,sort_order,done_at,created_at,updated_at,deleted) \
                         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
                        params![s.uuid, s.task_uuid, s.title, s.status, s.attr_label, s.attr_value, s.sort_order, s.done_at, s.created_at, s.updated_at, s.deleted]
                    );
                }
            }
        }
        ("delete", "task") => { let _ = conn.execute("UPDATE tasks SET deleted=1 WHERE uuid=?1", params![&op.uuid]); }
        ("delete", "step") => { let _ = conn.execute("UPDATE steps SET deleted=1 WHERE uuid=?1", params![&op.uuid]); }
        // v5.15.7：手机端改的设置（积分/等级、头像、昵称…）→ 最新为主写本地
        ("upsert", "setting") => {
            if let Some(d) = &op.data {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(d) {
                    let key = v.get("key").and_then(|x| x.as_str())
                        .map(|s| s.to_string()).unwrap_or_else(|| op.uuid.clone());
                    let val = v.get("value").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    let ts = v.get("updated_at").and_then(|x| x.as_i64()).unwrap_or(0);
                    apply_setting_remote(&conn, &key, &val, ts);
                }
            }
        }
        // v5.15.7：手机端打卡 → 桌面写 habit_logs + 习惯任务置 done（当日）
        ("upsert", "habit") => {
            if let Some(d) = &op.data {
                if let Ok(h) = serde_json::from_str::<HabitLogChange>(d) {
                    let _ = conn.execute(
                        "INSERT OR IGNORE INTO habit_logs(task_uuid,check_date,created_at) VALUES(?1,?2,?3)",
                        params![&h.task_uuid, &h.check_date, h.created_at]
                    );
                    let today = chrono::Local::now().format("%Y-%m-%d").to_string();
                    if h.check_date == today {
                        let _ = conn.execute(
                            "UPDATE tasks SET track_status='done', done=1, done_at=?1, updated_at=?1 \
                             WHERE uuid=?2 AND type='habit' AND track_status!='done'",
                            params![now_ms(), &h.task_uuid]
                        );
                    }
                }
            }
        }
        _ => {}
    }
}
