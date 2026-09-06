// 同步客户端：连接手机端服务器
use crate::{Task, Step};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::Duration;

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
    #[serde(rename = "server_time")]
    server_time: i64,
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]  // 手机端 HabitLog 序列化为 camelCase（taskUuid/checkDate/createdAt）
struct HabitLog {
    task_uuid: String,
    check_date: String,
    created_at: i64,
}

pub fn server_base(url: &str) -> String {
    url.trim_end_matches('/').to_string()
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
    for t in resp.tasks {
        let _ = conn.execute(
            "INSERT OR REPLACE INTO tasks (uuid,type,title,desc,category,priority,due_at,repeat_rule,deadline,track_status,done,done_at,delayed_count,reward_points,reminder_strength,created_at,updated_at,deleted) \
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)",
            params![t.uuid, t.task_type, t.title, t.desc, t.category, t.priority, t.due_at, t.repeat_rule, t.deadline, t.track_status, t.done, t.done_at, t.delayed_count, t.reward_points, t.reminder_strength, t.created_at, t.updated_at, t.deleted]
        );
    }
    for s in resp.steps {
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
    Ok(())
}

/// 推送本地变更到手机
pub fn push_change(db: &Arc<Mutex<Connection>>, url: &Arc<Mutex<String>>, entity: &str, uuid: &str) {
    let base = {
        let guard = url.lock().unwrap();
        server_base(&guard)
    };
    if base.is_empty() { return; }
    // 读本地最新数据
    let data: Option<String> = {
        let conn = db.lock().unwrap();
        match entity {
            "task" => conn.query_row(
                "SELECT * FROM tasks WHERE uuid=?1", params![uuid],
                |r| crate::row_to_task(r).map(|t| serde_json::to_string(&t).unwrap_or_default())
            ).ok(),
            "step" => conn.query_row(
                "SELECT * FROM steps WHERE uuid=?1", params![uuid],
                |r| crate::row_to_step(r).map(|s| serde_json::to_string(&s).unwrap_or_default())
            ).ok(),
            _ => None,
        }
    };
    let op = if data.is_none() { "delete" } else { "upsert" };
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
        match tungstenite::connect(&ws_url) {
            Ok((mut socket, _)) => {
                log::info!("WS 已连接: {}", ws_url);
                use tungstenite::Message;
                loop {
                    match socket.read() {
                        Ok(Message::Text(txt)) => {
                            if let Ok(op) = serde_json::from_str::<ChangeOp>(&txt) {
                                apply_change(&db, &op);
                            }
                        }
                        Ok(Message::Ping(_)) | Ok(Message::Pong(_)) => {}
                        Ok(_) => {}
                        Err(_) => break,
                    }
                }
            }
            Err(_) => {}
        }
        std::thread::sleep(Duration::from_secs(5));
    }
}

fn apply_change(db: &Arc<Mutex<Connection>>, op: &ChangeOp) {
    let conn = match db.lock() { Ok(c) => c, Err(_) => return };
    match (op.op.as_str(), op.entity.as_str()) {
        ("upsert", "task") => {
            if let Some(d) = &op.data {
                if let Ok(t) = serde_json::from_str::<Task>(d) {
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
        _ => {}
    }
}
