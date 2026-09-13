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
// v5.15.19：已配对的手机 deviceId（进程级）—— ws_loop 连上后要反 POST /api/pair，
//   但 sync.rs 拿不到 AppState，故用 OnceLock 由 lib.rs 启动时写入。
pub static PAIRED_DEVICE_ID: std::sync::OnceLock<Mutex<String>> = std::sync::OnceLock::new();

/// 写入已配对手机的 deviceId（lib.rs 在加载/保存配对时调用）
pub fn set_paired_device_id(id: &str) {
    let cell = PAIRED_DEVICE_ID.get_or_init(|| Mutex::new(String::new()));
    if let Ok(mut g) = cell.lock() {
        *g = id.to_string();
    }
}

/// 读取已配对手机的 deviceId（可能为空）
fn read_device_id() -> Option<String> {
    PAIRED_DEVICE_ID
        .get()
        .and_then(|cell| cell.lock().ok().map(|g| g.clone()))
}
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

/// v5.15.22 M11：habit 也参与全量推送 → 补 Serialize（此前只用于接收，只有 Deserialize）
#[derive(Serialize, Deserialize, Debug)]
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
    let resp: FullSyncPayload = lan_client()
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

/// v5.15.22 M11 关键修复：所有发往手机的 HTTP 一律**直连**（no_proxy）。
/// 根因：boss 电脑开着系统代理（127.0.0.1:7892），reqwest 默认读取系统代理，
/// 把发往手机内网 IP 的请求也塞进代理 → 代理回 502 Bad Gateway；
/// WS 用的是 tungstenite 不走代理，所以"WS 通、HTTP 全挂"——
/// 这就是双端同步单方向失效（桌面→手机全丢、全量拉取解码失败）的真正元凶。
pub fn lan_client() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .no_proxy()
        .build()
        .expect("reqwest client 初始化失败")
}

/// v5.15.22：同步调试日志（log crate 没接 logger，log::info! 全是空操作 ——
///   排查"推送到底跑没跑/为什么失败"必须落文件）。写在 exe 同目录 sync-debug.log。
fn sync_debug_log(msg: &str) {
    use std::io::Write;
    let path = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join("sync-debug.log")));
    let Some(path) = path else { return };
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let ts = chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f");
        let _ = writeln!(f, "[{}] {}", ts, msg);
    }
}

/// v5.15.22 M11：桌面 → 手机 **全量推送**。
///
/// 根因（2026-09-13 实测取证）：此前桌面端对手机只有「单条增量推送」—— push_change
/// 是 fire-and-forget（后台线程 + 5s 超时 + 失败不重试也不补发），而 full_sync 只做
/// "拉手机 → 写桌面"。**任何在手机不在线/断连期间产生的桌面端改动都会永久丢失**。
/// 实测：桌面 74 行任务（59 活 + 15 软删），手机全量接口只回 28 行、连软删共 40 行，
/// 其中 34 行手机端从未见过 —— 全是桌面端在断连期创建/修改的。
///
/// 做法：WS 连上（先拉后推）后，把桌面全量 tasks/steps/habit_logs 打包成 change 批量
/// POST 给手机端。安全性由手机端自己的 LWW 保证（`upsertTaskFromSync` 里
/// `local.updatedAt > remote.updatedAt` 就跳过）—— 手机端较新的状态不会被旧数据冲掉。
/// 60s 节流：WS 抖动重连时不会连环全量推。
pub fn push_full_to_mobile(db: &Arc<Mutex<Connection>>, url: &Arc<Mutex<String>>) -> Result<usize, String> {
    use std::sync::atomic::AtomicI64;
    static LAST_PUSH_MS: AtomicI64 = AtomicI64::new(0);

    let base = {
        let g = url.lock().map_err(|e| e.to_string())?;
        server_base(&g)
    };
    if base.is_empty() { return Err("未设置服务器地址".into()); }

    // 60s 节流（连接抖动时避免反复全量推）
    let now = now_ms();
    let last = LAST_PUSH_MS.load(Ordering::Relaxed);
    if last > 0 && now - last < 60_000 {
        sync_debug_log("push_full 跳过：60s 节流窗口内");
        return Ok(0);
    }
    sync_debug_log(&format!("push_full 开始 (base={})", base));

    let payload: Vec<ChangeOp> = {
        let conn = db.lock().map_err(|e| e.to_string())?;
        let mut ops: Vec<ChangeOp> = Vec::new();
        // ---- tasks（含软删行：deleted=1 也要推，手机端据此保持删除态） ----
        {
            let mut stmt = conn.prepare("SELECT * FROM tasks").map_err(|e| e.to_string())?;
            let rows = stmt.query_map([], |r| crate::row_to_task(r)).map_err(|e| e.to_string())?;
            for r in rows {
                let t = match r { Ok(v) => v, Err(_) => continue };
                if let Ok(data) = serde_json::to_string(&TaskSyncDto::from(&t)) {
                    ops.push(ChangeOp { op: "upsert".into(), entity: "task".into(), uuid: t.uuid.clone(), data: Some(data) });
                }
            }
        }
        // ---- steps ----
        {
            let mut stmt = conn.prepare("SELECT * FROM steps").map_err(|e| e.to_string())?;
            let rows = stmt.query_map([], |r| crate::row_to_step(r)).map_err(|e| e.to_string())?;
            for r in rows {
                let s = match r { Ok(v) => v, Err(_) => continue };
                if let Ok(data) = serde_json::to_string(&StepSyncDto::from(&s)) {
                    ops.push(ChangeOp { op: "upsert".into(), entity: "step".into(), uuid: s.uuid.clone(), data: Some(data) });
                }
            }
        }
        // ---- habit_logs ----
        {
            let mut stmt = conn.prepare("SELECT task_uuid, check_date, created_at FROM habit_logs").map_err(|e| e.to_string())?;
            let rows = stmt.query_map([], |r| Ok(HabitLogChange {
                task_uuid: r.get(0)?, check_date: r.get(1)?, created_at: r.get(2)?,
            })).map_err(|e| e.to_string())?;
            for r in rows {
                let h = match r { Ok(v) => v, Err(_) => continue };
                if let Ok(data) = serde_json::to_string(&h) {
                    ops.push(ChangeOp { op: "upsert".into(), entity: "habit".into(), uuid: h.task_uuid.clone(), data: Some(data) });
                }
            }
        }
        ops
    };
    let total = payload.len();
    sync_debug_log(&format!("push_full 载荷构建完成: {} 条 (task+step+habit)", total));
    if total == 0 { return Ok(0); }
    LAST_PUSH_MS.store(now, Ordering::Relaxed);

    // 分批推送（单批 120 条，避免一次性 body 过大 / 手机端逐条 apply 阻塞太久）
    let mut sent = 0usize;
    for (bi, chunk) in payload.chunks(120).enumerate() {
        let body = serde_json::json!({ "changes": chunk, "client_time": now_ms() });
        let t0 = std::time::Instant::now();
        let resp = lan_client()
            .post(format!("{}/api/sync/changes", base))
            .timeout(Duration::from_secs(25))
            .json(&body)
            .send();
        match resp {
            Ok(r) if r.status().is_success() => {
                sent += chunk.len();
                sync_debug_log(&format!(
                    "push_full batch#{} {}条 ok ({}ms)", bi, chunk.len(), t0.elapsed().as_millis()
                ));
            }
            Ok(r) => {
                sync_debug_log(&format!("push_full batch#{} 失败: HTTP {}", bi, r.status()));
                break;
            }
            Err(e) => {
                sync_debug_log(&format!("push_full batch#{} 网络错误: {}", bi, e));
                break;
            }
        }
    }
    sync_debug_log(&format!("push_full_to_mobile: 完成 {}/{} 条 (base={})", sent, total, base));
    Ok(sent)
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
        let _ = lan_client()
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
                sync_debug_log(&format!("WS 已连接: {}", ws_url));
                WS_CONNECTED.store(true, Ordering::Relaxed);
                // v5.15.19：每次 WS 连上后都补一次 /api/pair —— 让手机端 settings.paired_device
                //   有值。旧实现只在「手动配对那一刻」发一次，导致自动重连后手机端
                //   paired_device 为空 → 手机端显示"未配对"（boss：手机端没显示已连接）。
                //   同时补 deviceName —— 手机端 ApiRoutes 读的是 deviceName。
                {
                    let base2 = base.clone();
                    std::thread::spawn(move || {
                        if base2.is_empty() { return; }
                        let pc_name = std::env::var("COMPUTERNAME")
                            .unwrap_or_else(|_| "BOOS PC".to_string());
                        let did = read_device_id().unwrap_or_default();
                        let _ = lan_client()
                            .post(format!("{}/api/pair", base2))
                            .timeout(std::time::Duration::from_secs(4))
                            .json(&serde_json::json!({
                                "name": pc_name,
                                "deviceName": pc_name,
                                "deviceId": did
                            }))
                            .send();
                    });
                }
                // v5.15.7：刚连上时补一次全量拉取 —— ws 断开期间手机端的改动不会补发，
                //   靠这次 pull 收敛（LWW 保证本地更新的不会被覆盖）
                {
                    let db2 = db.clone();
                    let url2 = url.clone();
                    std::thread::spawn(move || {
                        match full_sync(&db2, &url2) {
                            Ok(_) => {
                                notify_changed();
                                sync_debug_log("ws 连上：full_sync(拉取) ok");
                            }
                            Err(e) => sync_debug_log(&format!("ws 连上：full_sync 失败: {}", e)),
                        }
                        // v5.15.22 M11（boss：「双端的任务还是没能完全同步」）：
                        //   拉完再把桌面全量推给手机 —— 双向收敛。
                        //   根因：此前桌面 → 手机只有 fire-and-forget 的单条推送，
                        //   断连期间的改动永久丢失（实测手机端少了 34 条任务）。
                        //   推不完（网络抖动/手机端繁忙）自动重试，最多 3 轮。
                        for attempt in 1..=3 {
                            match push_full_to_mobile(&db2, &url2) {
                                Ok(n) if n == 0 => break,          // 节流跳过
                                Ok(n) => { sync_debug_log(&format!("push 第{}轮推送 {} 条", attempt, n)); break; }
                                Err(e) => {
                                    sync_debug_log(&format!("push 第{}轮失败: {}", attempt, e));
                                    std::thread::sleep(Duration::from_secs(5));
                                }
                            }
                        }
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
            Err(e) => {
                WS_CONNECTED.store(false, Ordering::Relaxed);
                sync_debug_log(&format!("WS 连接失败(5s后重试): {}", e));
            }
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
