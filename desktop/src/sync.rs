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
/// ═══ v5.17.0 传输加密：长期密钥（master，32 字节）═══
/// 配对获批时由手机端生成并下发；由它派生出 sign / enc 两把子密钥。
/// 此后**所有** HTTP 请求都签名 + 加密，WS 帧也加密（见 [crate::tbcrypto]）。
static AUTH_KEYS: Mutex<Option<crate::tbcrypto::Keys>> = Mutex::new(None);

/// 设置 master（hex）。格式非法返回 false。
pub fn set_master_hex(hex: &str) -> bool {
    match crate::tbcrypto::Keys::from_master_hex(hex) {
        Some(k) => {
            if let Ok(mut g) = AUTH_KEYS.lock() { *g = Some(k); }
            true
        }
        None => false,
    }
}

pub fn keys() -> Option<crate::tbcrypto::Keys> {
    AUTH_KEYS.lock().ok().and_then(|g| g.clone())
}

/// master 的 hex（持久化到 pairing.json）
pub fn master_hex() -> String {
    keys().map(|k| k.master_hex()).unwrap_or_default()
}

/// WS 握手用的 token（base64(master)，手机端 wsTokenOk 会解码比对）
pub fn auth_token() -> String {
    keys().map(|k| k.token()).unwrap_or_default()
}

pub fn has_auth_token() -> bool {
    keys().is_some()
}

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
    /// v5.27.0：负责人（桌面端只存不编；漏了会在"桌面→手机"方向把 owner 清空）
    owner: String,
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
            owner: t.owner.clone(),
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
    // v5.17.0：签名 + 解密 + 验签（白名单外的设备既看不懂也改不了）
    let plain = secure_get(&base, "/api/sync/full", 10)?;
    let resp: FullSyncPayload = serde_json::from_str(&plain).map_err(|e| e.to_string())?;

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
            "INSERT OR REPLACE INTO tasks (uuid,type,title,desc,category,priority,due_at,repeat_rule,deadline,track_status,done,done_at,delayed_count,count,done_count,reward_points,reminder_strength,owner,created_at,updated_at,deleted) \
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21)",
            params![t.uuid, t.task_type, t.title, t.desc, t.category, t.priority, t.due_at, t.repeat_rule, t.deadline, t.track_status, t.done, t.done_at, t.delayed_count, t.count, t.done_count, t.reward_points, t.reminder_strength, t.owner, t.created_at, t.updated_at, t.deleted]
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

// ══════════════════════════════════════════════════════════════════
// v5.17.0 传输加密 / 请求签名（"明文传输"整改，双向认证）
// ══════════════════════════════════════════════════════════════════

/// 给请求加上签名四件套。
/// 签名覆盖 `METHOD | PATH | TS | NONCE | sha256(body)`，
/// 所以改一个字节的 body 就会验签失败；nonce + 时间戳防重放。
fn signed_headers(
    rb: reqwest::blocking::RequestBuilder,
    method: &str,
    path: &str,
    body_wire: &str,
) -> reqwest::blocking::RequestBuilder {
    let ts = chrono::Local::now().timestamp_millis();
    let nonce = crate::tbcrypto::rand_nonce_hex();
    let (tok, sig) = match keys() {
        Some(k) => (
            k.token(),
            crate::tbcrypto::sign(&k, method, path, ts, &nonce, body_wire),
        ),
        None => (String::new(), String::new()),
    };
    rb.header("X-TB-Token", tok)
        .header("X-TB-Ts", ts.to_string())
        .header("X-TB-Nonce", nonce)
        .header("X-TB-Sig", sig)
}

/// 验签 + 解密响应（**双向认证的"电脑验手机"半边**）
fn open_response(
    k: &crate::tbcrypto::Keys,
    path: &str,
    headers: &reqwest::header::HeaderMap,
    wire: &str,
) -> Result<String, String> {
    let hs = |n: &str| {
        headers
            .get(n)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string()
    };
    let ts: i64 = hs("X-TB-Ts").parse().unwrap_or(0);
    let nonce = hs("X-TB-Nonce");
    let sig = hs("X-TB-Sig");
    let want = crate::tbcrypto::sign(k, "R", path, ts, &nonce, wire);
    if sig != want {
        return Err("响应验签失败（回话方可能不是本机配对的那台手机）".into());
    }
    crate::tbcrypto::open(k, wire).ok_or_else(|| "响应解密失败".to_string())
}

/// 带签名 + 加密的 GET，返回解密后的明文
pub fn secure_get(base: &str, path: &str, timeout_secs: u64) -> Result<String, String> {
    let k = keys().ok_or_else(|| "未配对（无密钥），请先完成配对".to_string())?;
    // v5.29.0：签名只覆盖 path（手机端 guard 用 request.path() 验签，**不含 query**）。
    //   此前 task_changes 把 ?task_uuid=... 整串当 path 签名 → 手机端 bad_sig 401。
    let (sign_path, query) = match path.find('?') {
        Some(i) => (&path[..i], &path[i..]),
        None => (path, ""),
    };
    let rb = signed_headers(
        lan_client().get(format!("{}{}{}", base, sign_path, query)),
        "GET",
        sign_path,
        "",
    )
    .timeout(Duration::from_secs(timeout_secs));
    let resp = rb.send().map_err(|e| e.to_string())?;
    let status = resp.status();
    let headers = resp.headers().clone();
    let wire = resp.text().map_err(|e| e.to_string())?;
    if !status.is_success() {
        return Err(format!(
            "HTTP {} {}",
            status,
            wire.chars().take(160).collect::<String>()
        ));
    }
    // 响应验签同样用不含 query 的 path（手机端 respondSecure 用 request.path()）
    open_response(&k, sign_path, &headers, &wire)
}

/// 带签名 + 加密请求体的 POST。返回 (是否成功, 明文响应)
pub fn secure_post(
    base: &str,
    path: &str,
    json: &str,
    timeout_secs: u64,
) -> Result<(bool, String), String> {
    let k = keys().ok_or_else(|| "未配对（无密钥），请先完成配对".to_string())?;
    let wire = crate::tbcrypto::seal(&k, json);
    let rb = signed_headers(
        lan_client().post(format!("{}{}", base, path)),
        "POST",
        path,
        &wire,
    )
    .header("X-TB-Enc", "1")
    .timeout(Duration::from_secs(timeout_secs))
    .body(wire);
    let resp = rb.send().map_err(|e| e.to_string())?;
    // 注意：reqwest 的 Response::text(self) 会取得所有权 —— status 必须先取出
    let code = resp.status();
    let ok = code.is_success();
    let headers = resp.headers().clone();
    let body = resp.text().unwrap_or_default();
    if !ok {
        return Ok((false, format!("HTTP {}", code)));
    }
    match open_response(&k, path, &headers, &body) {
        Ok(plain) => Ok((true, plain)),
        Err(e) => {
            sync_debug_log(&format!("响应校验失败 ({}): {}", path, e));
            Ok((true, String::new()))
        }
    }
}

/// v5.17.2：URL query 参数百分号编码（只保留 RFC 3986 unreserved 字符）。
///
/// 起因：WS 握手的 token 是标准 base64，含 `+` `/` `=`。
/// `+` 直接拼进 query 会被对端按 x-www-form-urlencoded 解成**空格**，
/// 于是手机端 token 校验失败 → 101 升级成功后立刻发关闭帧(1008) →
/// 桌面端 read() 立刻报错 → 睡 5 秒重连 → **表现为"每 5 秒断联重连一次"**，
/// 而每次重连都会全量拉取一次数据（写库 + 重渲染）→ 肉眼可见的卡顿。
fn url_encode(s: &str) -> String {
    let mut o = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => o.push(b as char),
            _ => o.push_str(&format!("%{:02X}", b)),
        }
    }
    o
}

/// v5.15.22：同步调试日志（log crate 没接 logger，log::info! 全是空操作 ——
///   排查"推送到底跑没跑/为什么失败"必须落文件）。写在 exe 同目录 sync-debug.log。
pub fn sync_debug_log(msg: &str) {
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
        let body = serde_json::json!({ "changes": chunk, "client_time": now_ms() }).to_string();
        let t0 = std::time::Instant::now();
        let resp = secure_post(&base, "/api/sync/changes", &body, 25);   // v5.17.0
        match resp {
            Ok((true, _)) => {
                sent += chunk.len();
                sync_debug_log(&format!(
                    "push_full batch#{} {}条 ok ({}ms)", bi, chunk.len(), t0.elapsed().as_millis()
                ));
            }
            Ok((false, msg)) => {
                sync_debug_log(&format!("push_full batch#{} 失败: {}", bi, msg));
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
    // 网络推送放后台线程：手机不在线时最多拖 5s 超时，若阻塞在主线程，点「添加步骤/完成任务」
    // 会卡住整个 UI 直到超时（boss 实测感知为「按了没反应」）。这里立即返回、推送失败不影响主流程。
    let base_for_push = base.clone();
    let body_s = body.to_string();
    // v5.18.1：带出日志用的标识（闭包要 move，先克隆）
    let entity_log = entity.to_string();
    let uuid_log = uuid.to_string();
    std::thread::spawn(move || {
        // v5.17.0：签名 + 加密
        // v5.18.1：失败必须留痕 —— 这里原来 `let _ =` 把结果全吞了，
        //   "某条变更到底推没推过去"完全不可观测（排查步骤同步时吃了这个亏）
        match secure_post(&base_for_push, "/api/sync/changes", &body_s, 5) {
            Ok((true, _)) => {}
            Ok((false, msg)) => log::warn!("push_change 被拒: {}/{} → {}", entity_log, uuid_log, msg),
            Err(e) => log::warn!("push_change 失败: {}/{} → {}", entity_log, uuid_log, e),
        }
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
        // v5.16.0：WS 握手要带 token（手机端未授权会直接关闭连接）
        // ⚠️ v5.17.2 修复：token 必须**百分号编码**再拼进 query ——
        //   标准 base64 里的 `+` 会被对端解成空格 → 手机端判 unauthorized(1008) 立刻断开
        //   → 桌面端每 5 秒重连一次（连带每轮全量拉取），这就是"一直断联重连很卡"的根因。
        let ws_url = format!("{}?token={}",
            base.replace("http://", "ws://") + "/ws", url_encode(&auth_token()));
        WS_CONNECTED.store(false, Ordering::Relaxed);
        match tungstenite::connect(&ws_url) {
            Ok((mut socket, _)) => {
                log::info!("WS 已连接: {}", ws_url);
                sync_debug_log(&format!("WS 已连接: {}", ws_url));
                WS_CONNECTED.store(true, Ordering::Relaxed);
                // v5.17.0：配对改为「弹窗确认」（见 lib.rs pair_request/pair_poll）。
                //   paired_device 在人在手机上点「允许」时由手机端自己写入，重连不再需要上报。
                // v5.15.7：刚连上时补一次全量拉取 —— ws 断开期间手机端的改动不会补发，
                //   靠这次 pull 收敛（LWW 保证本地更新的不会被覆盖）
                // v5.17.2：重连拉取加 20s 节流。
                //   原先"每次连上都全量拉一次"在网络抖动时会把桌面拖卡（连接不稳定 → 反复
                //   全量拉取 → 写库 + 通知前端重渲染）。抖动期间只需要合并成一次。
                {
                    use std::sync::atomic::AtomicI64 as A64;
                    static LAST_PULL_MS: A64 = A64::new(0);
                    let nowp = now_ms();
                    let lastp = LAST_PULL_MS.load(Ordering::Relaxed);
                    if lastp > 0 && nowp - lastp < 20_000 {
                        sync_debug_log("ws 连上：距上次拉取 <20s，跳过 full_sync（防抖动风暴）");
                    } else {
                    LAST_PULL_MS.store(nowp, Ordering::Relaxed);
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
                    }   // v5.17.2：节流分支结束
                }
                use tungstenite::Message;
                loop {
                    match socket.read() {
                        Ok(Message::Text(txt)) => {
                            // v5.17.0：手机端推送的帧已加密（"E1:" 前缀）；无前缀按明文兼容
                            let plain = match keys() {
                                Some(k) => crate::tbcrypto::open(&k, &txt).unwrap_or_default(),
                                None => txt.clone(),
                            };
                            if let Ok(op) = serde_json::from_str::<ChangeOp>(&plain) {
                                apply_change(&db, &op);
                                // v5.15.7：立刻通知前端刷新（否则要等 setInterval 15s 轮询）
                                notify_changed();
                            }
                            // v5.30.0：手机端下拉刷新 / AI 调 /ai/sync 发来的控制指令 ——
                            //   立刻拉一轮 + 推一轮，让两端当场对齐。
                            //   （此前手机端**没有任何办法**主动催电脑同步：它只是被动服务器）
                            if plain.contains("\"syncnow\"") {
                                sync_debug_log("收到手机端 syncnow：立即 full_sync + push_full");
                                let db2 = db.clone();
                                let url2 = url.clone();
                                std::thread::spawn(move || {
                                    match full_sync(&db2, &url2) {
                                        Ok(_) => { notify_changed(); sync_debug_log("syncnow: 拉取 ok"); }
                                        Err(e) => sync_debug_log(&format!("syncnow: 拉取失败 {}", e)),
                                    }
                                    for _attempt in 1..=2 {
                                        match push_full_to_mobile(&db2, &url2) {
                                            Ok(n) if n == 0 => break,
                                            Ok(n) => { sync_debug_log(&format!("syncnow: 推送 {} 条", n)); break; }
                                            Err(e) => {
                                                sync_debug_log(&format!("syncnow: 推送失败 {}", e));
                                                std::thread::sleep(Duration::from_secs(3));
                                            }
                                        }
                                    }
                                });
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
                    // v5.27.0：INSERT 列表补 count/done_count（原来漏了 —— preserve 塞回结构体
                    //   也写不进库，REPLACE 后次数任务被重置回默认值）+ owner（负责人路由数据）
                    let _ = conn.execute(
                        "INSERT OR REPLACE INTO tasks (uuid,type,title,desc,category,priority,due_at,repeat_rule,deadline,track_status,done,done_at,delayed_count,count,done_count,reward_points,reminder_strength,owner,created_at,updated_at,deleted) \
                         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21)",
                        params![t.uuid, t.task_type, t.title, t.desc, t.category, t.priority, t.due_at, t.repeat_rule, t.deadline, t.track_status, t.done, t.done_at, t.delayed_count, t.count, t.done_count, t.reward_points, t.reminder_strength, t.owner, t.created_at, t.updated_at, t.deleted]
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
        ("delete", "task") => {
            let _ = conn.execute("UPDATE tasks SET deleted=1 WHERE uuid=?1", params![&op.uuid]);
            // v5.18.1：级联软删该任务的步骤 —— 兜底"只推了 task 的 delete"的对端（老版本）
            let _ = conn.execute("UPDATE steps SET deleted=1 WHERE task_uuid=?1", params![&op.uuid]);
        }
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
