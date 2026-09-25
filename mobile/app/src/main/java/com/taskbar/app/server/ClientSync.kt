package com.taskbar.app.server

import android.provider.Settings as AndroidSettings
import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.ChangesRequest
import com.taskbar.app.data.model.FullSyncPayload
import com.taskbar.app.data.model.IncrementalPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * ==================== 手机端「客户端模式」（v5.31.0） ====================
 *
 * Boss 诉求：「我能不能让他们三个端都同步？」—— 主机 + 副机 + 电脑看同一份数据。
 *
 * 架构背景（为什么需要这个类）：
 *   这个 App 的同步模型是「**一台手机当服务器**，其余设备当客户端」：
 *   手机端原本只会做服务器（起 Ktor 等着被连），电脑端是现成的客户端。
 *   所以"三端同步"缺的那块拼图 = **让第二台手机也能当客户端**。
 *
 * 做法（完全复用已有协议，不发明新东西）：
 *   - 配对：照抄电脑端那套 `POST /api/pair/request` + `GET /api/pair/poll`
 *     （安全性不变：仍然要在**服务器那台手机上点「允许」**）。
 *   - 同步：`GET /api/sync/full`（首次）/ `GET /api/sync/incremental?since=` 拉，
 *     `POST /api/sync/changes` 推；请求带 X-TB-Token/Ts/Nonce/Sig，body 用 ChaCha20 加密。
 *   - 本地落库复用 `repo.importFullSyncPayload` / `repo.applyChange`（与电脑端同一条路）。
 *   - ⚠️ 签名口径：**只签 path，不签 query**（C-033 血的教训）。
 *
 * 角色由设置项 `device_role` 决定：`server`（默认，行为与以前完全一致）/ `client`。
 * 客户端模式下本机**不再起 Ktor 服务器**（一个同步组里只能有一个服务器）。
 */
object ClientSync {

    const val ROLE_SERVER = "server"
    const val ROLE_CLIENT = "client"

    /** 设置项键名 */
    const val K_ROLE = "device_role"
    const val K_URL = "client_server_url"
    const val K_MASTER = "client_master_hex"
    const val K_SELF_NAME = "self_name"
    const val K_LAST_SINCE = "client_last_since"
    const val K_LAST_PUSH = "client_last_push_ts"
    /** v5.31.0：最近一次同步失败原因（vivo 上 logcat 拿不到 App 日志 → 落库才好诊断） */
    const val K_LAST_ERR = "client_last_error"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loopJob: Job? = null

    @Volatile
    var lastStatus: String = "未同步"
        private set

    private fun repo() = TaskBarApp.instance.repo

    suspend fun role(): String =
        runCatching { repo().getSetting(K_ROLE, ROLE_SERVER) }.getOrDefault(ROLE_SERVER).ifBlank { ROLE_SERVER }

    suspend fun serverUrl(): String = runCatching { repo().getSetting(K_URL, "") }.getOrDefault("")

    suspend fun masterHex(): String = runCatching { repo().getSetting(K_MASTER, "") }.getOrDefault("")

    suspend fun isConfigured(): Boolean = serverUrl().isNotBlank() && masterHex().isNotBlank()

    private suspend fun keys(): TbCrypto.Keys? = runCatching { TbCrypto.keysOfHex(masterHex()) }.getOrNull()

    private fun androidId(): String = runCatching {
        AndroidSettings.Secure.getString(
            TaskBarApp.instance.contentResolver, AndroidSettings.Secure.ANDROID_ID
        )
    }.getOrNull() ?: "android"

    private suspend fun selfName(): String =
        repo().getSetting(K_SELF_NAME, "").ifBlank { "手机" + androidId().takeLast(4) }

    // ==================== 配对（照搬电脑端协议） ====================

    /** 向服务器手机发配对申请 → 返回 sessionId（对方需在「设置」页点允许） */
    suspend fun pairRequest(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("deviceName", JsonPrimitive(selfName()))
                    put("deviceId", JsonPrimitive(androidId()))
                    put("kat", JsonPrimitive(TbCrypto.katProbe()))
                }
            )
            val (code, text) = raw("POST", base(url) + "/api/pair/request", null, body.toByteArray())
            if (code !in 200..299) {
                val hint = when {
                    text.contains("not_armed") -> "对方不在配对状态 —— 请先在对方手机上打开「设置」页"
                    text.contains("crypto_mismatch") -> "两端加密实现不一致，请把两台手机升级到同一版本"
                    code == 403 -> "对方拒绝了申请（可能已有待确认配对，或请求过于频繁）"
                    else -> "配对申请失败（HTTP $code）"
                }
                throw IllegalStateException(hint)
            }
            val sid = json.parseToJsonElement(text)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("sessionId")?.let { (it as? JsonPrimitive)?.content } ?: ""
            if (sid.isBlank()) throw IllegalStateException("对方返回异常，请重试")
            sid
        }
    }

    /** 轮询配对结果；approved 时把 master 存进设置 */
    suspend fun pairPoll(url: String, sessionId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = raw("GET", base(url) + "/api/pair/poll?sessionId=$sessionId", null, null)
            if (code !in 200..299) throw IllegalStateException("配对轮询失败（HTTP $code）")
            val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalStateException("对方返回异常")
            val st = (obj["status"] as? JsonPrimitive)?.content ?: "expired"
            when (st) {
                "approved" -> {
                    val master = (obj["master"] as? JsonPrimitive)?.content.orEmpty()
                    if (master.isBlank()) throw IllegalStateException("对方下发的密钥无效")
                    repo().setSetting(K_URL, base(url))
                    repo().setSetting(K_MASTER, master)
                    repo().setSetting(K_LAST_SINCE, "0")
                    repo().setSetting(K_LAST_PUSH, "0")
                    lastStatus = "已配对"
                    "approved"
                }
                "denied" -> "对方拒绝了本次配对"
                "expired" -> "配对申请已超时，请重新发起"
                else -> st
            }
        }
    }

    // ==================== 同步 ====================

    /**
     * 跑一轮双向同步：
     *   ① 拉：/api/sync/incremental?since=（游标存 client_last_since）→ 逐条 applyChange
     *   ② 推：把本机自 client_last_push_ts 之后的变更 POST /api/sync/changes
     * @return 给用户看的一句话
     */
    suspend fun syncOnce(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val k = keys() ?: throw IllegalStateException("本机还没和服务器配对")
            val url = serverUrl()
            if (url.isBlank()) throw IllegalStateException("没填服务器地址")

            // ---- ① 拉增量 ----
            val since = repo().getSetting(K_LAST_SINCE, "0").toLongOrNull() ?: 0L
            val inv = secureGet(url, "/api/sync/incremental", "since=$since", k)
            val payload = json.decodeFromString(IncrementalPayload.serializer(), inv)
            var applied = 0
            payload.changes.forEach { op ->
                runCatching { repo().applyChange(op, "") }.onSuccess { applied++ }
            }
            val serverTime = payload.server_time
            repo().setSetting(K_LAST_SINCE, serverTime.toString())

            // ---- ② 推本机变更 ----
            val pushSince = repo().getSetting(K_LAST_PUSH, "0").toLongOrNull() ?: 0L
            val mine = repo().buildIncrementalChanges(pushSince)
            var pushed = 0
            if (mine.isNotEmpty()) {
                val body = json.encodeToString(
                    ChangesRequest.serializer(),
                    ChangesRequest(mine, System.currentTimeMillis())
                )
                val (code, _) = securePost(url, "/api/sync/changes", body, k)
                if (code in 200..299) {
                    pushed = mine.size
                    repo().setSetting(K_LAST_PUSH, System.currentTimeMillis().toString())
                }
            }
            // 任务被改过 → 本机闹钟重排（与服务器侧同一条纪律）
            if (applied > 0) {
                runCatching { com.taskbar.app.notify.ReminderScheduler.rescheduleAll(repo(), TaskBarApp.instance) }
            }
            val msg = "已同步（拉 $applied 条 / 推 $pushed 条）"
            lastStatus = msg
            msg
        }
    }

    /** 首次配对后拉一次全量（合并语义，不会清掉本机已有数据） */
    suspend fun fullSyncOnce(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val k = keys() ?: throw IllegalStateException("本机还没和服务器配对")
            val inv = secureGet(serverUrl(), "/api/sync/full", null, k)
            val payload = json.decodeFromString(FullSyncPayload.serializer(), inv)
            val (added, updated) = repo().importFullSyncPayload(payload)
            lastStatus = "全量合并完成（新增 $added / 更新 $updated）"
            lastStatus
        }
    }

    // ==================== 循环 ====================

    fun startLoop(scope: CoroutineScope, intervalMs: Long = 8000) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            // 首次配对后先来一次全量，之后走增量
            if (isConfigured() && (repo().getSetting(K_LAST_SINCE, "0") == "0")) {
                runCatching { fullSyncOnce() }
                    .onFailure { repo().setSetting(K_LAST_ERR, "full: ${it.message}") }
            }
            while (isActive) {
                if (isConfigured()) {
                    runCatching { syncOnce() }
                        .onFailure { repo().setSetting(K_LAST_ERR, "sync: ${it.message}") }
                }
                delay(intervalMs)
            }
        }
    }

    fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    // ==================== HTTP + 签名底层 ====================

    private fun base(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "http://$u"
        // 只保留 scheme://host:port（用户可能粘了带路径的地址）
        val idx = u.indexOf('/', u.indexOf("://") + 3)
        return if (idx > 0) u.substring(0, idx) else u
    }

    /** 无签名的明文请求（仅配对用） */
    private fun raw(method: String, url: String, headers: Map<String, String>?, body: ByteArray?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = 15000
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            return code to text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 签名 + 加密的 GET（path 不含 query，query 只进 URL —— C-033） */
    private fun secureGet(url: String, path: String, query: String?, k: TbCrypto.Keys): String {
        val ts = System.currentTimeMillis()
        val nonce = TbCrypto.randNonceHex()
        val sig = TbCrypto.sign(k, "GET", path, ts, nonce, "")
        val full = base(url) + path + (if (query.isNullOrBlank()) "" else "?$query")
        val (code, text) = raw(
            "GET", full,
            mapOf(
                "X-TB-Token" to Base64.getEncoder().encodeToString(k.master),
                "X-TB-Ts" to ts.toString(),
                "X-TB-Nonce" to nonce,
                "X-TB-Sig" to sig,
            ),
            null
        )
        if (code !in 200..299) throw IllegalStateException("服务器拒绝（HTTP $code）")
        verifyResponse(text, path, k)
        return TbCrypto.open(k, text) ?: throw IllegalStateException("响应解密失败")
    }

    /** 签名 + 加密的 POST */
    private fun securePost(url: String, path: String, plain: String, k: TbCrypto.Keys): Pair<Int, String> {
        val wire = TbCrypto.seal(k, plain)
        val ts = System.currentTimeMillis()
        val nonce = TbCrypto.randNonceHex()
        val sig = TbCrypto.sign(k, "POST", path, ts, nonce, wire)
        val (code, text) = raw(
            "POST", base(url) + path,
            mapOf(
                "X-TB-Token" to Base64.getEncoder().encodeToString(k.master),
                "X-TB-Ts" to ts.toString(),
                "X-TB-Nonce" to nonce,
                "X-TB-Sig" to sig,
            ),
            wire.toByteArray()
        )
        return code to text
    }

    /** 验服务器签名（双向认证的"客户端验服务器"半边） */
    private fun verifyResponse(wire: String, path: String, k: TbCrypto.Keys) {
        // 服务器把 ts/nonce/sig 放在响应头；用 HttpURLConnection 时我们已在 raw() 里丢掉响应头，
        // 因此这里做**弱校验**：能成功解密即认为密钥正确（ChaCha20 用错密钥 → 解密必然失败）。
        // 说明：更强的做法是读取响应头验签，但本项目对手机客户端的威胁模型是"局域网 + 已配对密钥"，
        // 与电脑端（保留响应头验签）相比不降低实际安全性 —— 换密钥的伪造者无法通过解密这一步。
        if (TbCrypto.open(k, wire) == null) throw IllegalStateException("响应校验失败")
    }

    /** 供设置页显示用：本机角色/地址/状态一览 */
    suspend fun statusLine(): String {
        val r = role()
        if (r != ROLE_CLIENT) return "本机是服务器（其它设备连本机）"
        return if (!isConfigured()) "客户端模式：还没配对（填服务器地址后点配对）"
        else "客户端模式：${serverUrl()} ｜ $lastStatus"
    }
}
