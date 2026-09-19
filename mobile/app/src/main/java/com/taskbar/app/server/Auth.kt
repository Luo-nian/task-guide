package com.taskbar.app.server

import com.taskbar.app.TaskBarApp
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * ══════════════════════════════════════════════════════════════════
 * v5.17.0 安全加固（P0 复修）
 * ══════════════════════════════════════════════════════════════════
 *
 * 相对 v5.16.0 的三处改动（按 boss 指示）：
 *
 *  ① **取消 6 位配对码** —— 改为「扫描设备 → 一端发申请 → 另一端弹窗确认」。
 *     [PairingState] 负责这次改版：配对**必须由人在手机上点「允许」**。
 *
 *  ② **把"确认之前"的锁做扎实** —— v5.16.0 的风险主要在"确认之前"这一段，
 *     现在用四道门把它围起来（详见 [PairingState.request]）：
 *       门1 配对模式门控：只有用户**正看着设置页**时，手机才接受配对申请
 *       门2 限流：同一 IP 每分钟最多 3 次
 *       门3 单 pending：同一时刻只允许一个待确认申请
 *       门4 2 分钟 TTL + 仅发起方 IP 可轮询 + 密钥一次性交付
 *
 *  ③ **双向认证** —— v5.16.0 只做了"手机验电脑"。
 *     现在响应也由手机端签名（[respondSecure]），电脑端验签 →
 *     证明回话的确实是那台配过对的手机，而不是伪冒的局域网设备。
 */

/** 允许的时钟偏差（毫秒）。超出即拒，配合 nonce 去重防重放。 */
private const val ALLOWED_SKEW_MS = 180_000L

// ═══════════════════════════ 长期密钥 ═══════════════════════════

/**
 * 长期密钥（master 32 字节，hex 存在 settings 表 `auth_secret`）。
 *
 * 注意：**它不参与同步**（`applySettingFromSync` 只处理白名单 key），
 * 否则会出现"要同步得先有密钥、要密钥得先同步"的自锁。
 */
object AuthState {
    private const val KEY = "auth_secret"

    @Volatile
    private var cached: TbCrypto.Keys? = null

    /** 已配对的密钥；未配对返回 null */
    suspend fun keys(): TbCrypto.Keys? {
        cached?.let { return it }
        val hex = TaskBarApp.instance.repo.getSetting(KEY, "")
        if (hex.isEmpty()) return null
        val k = TbCrypto.keysOfHex(hex) ?: return null
        cached = k
        return k
    }

    suspend fun isPaired(): Boolean = keys() != null

    /** 配对获批时调用：把手机端生成的 master 存下来 */
    suspend fun issue(masterHex: String) {
        TaskBarApp.instance.repo.setSetting(KEY, masterHex)
        cached = TbCrypto.keysOfHex(masterHex)
    }

    /** 作废（解除配对 / 换设备）—— 旧 token 立即失效 */
    suspend fun clear() {
        TaskBarApp.instance.repo.setSetting(KEY, "")
        cached = null
    }

    /** 密钥指纹（前 4 位），设置页显示用 */
    suspend fun fingerprint(): String {
        val k = keys() ?: return ""
        return k.masterHex().take(4).uppercase()
    }
}

// ═══════════════════════════ 重放保护 ═══════════════════════════

/**
 * 请求 nonce 去重（LRU，上限 2048 条）。
 * 没有它的话，攻击者抓到一次合法请求就能无限重放（例如反复提交"删除任务"）。
 */
object NonceCache {
    private const val CAP = 2048
    private const val TTL_MS = 300_000L
    private val seen = LinkedHashMap<String, Long>(512, 0.75f, true)

    /** @return true = 首次见到（放行）；false = 重放（拒绝） */
    @Synchronized
    fun checkAndPut(nonce: String): Boolean {
        val now = System.currentTimeMillis()
        if (seen.containsKey(nonce)) return false
        seen[nonce] = now
        if (seen.size > CAP) {
            val it = seen.entries.iterator()
            var removed = 0
            while (it.hasNext() && seen.size - removed > CAP / 2) {
                it.next()
                it.remove()
                removed++
            }
        }
        return true
    }
}

// ═══════════════════════════ 配对状态机 ═══════════════════════════

/**
 * 配对流程（**双向确认**）：
 *
 * ```
 *  电脑：扫描 mDNS → 找到手机 → 点「配对」
 *        └─ POST /api/pair/request  {deviceName, deviceId, kat}
 *                ↓
 *  手机：① 检查"配对模式"（用户正看着设置页）
 *        ② 限流（同 IP / 分钟）
 *        ③ 检查是否已有待确认申请
 *        ④ 校验 kat（两端加密实现是否一致）
 *        └─ 返回 {sessionId, ttl}
 *                ↓
 *  手机：弹出确认框 →【 boss 在手机上点「允许」】← 这一步才是真正的安全边界
 *                ↓
 *  电脑：GET /api/pair/poll?sessionId=…（仅发起方 IP 可查，2 分钟内有效）
 *        └─ 拿到 master → 写入 pairing.json → 开始同步
 * ```
 *
 * ⭐ 安全边界只有一条：**人在手机上点「允许」**。
 *   （v5.17.1 去掉了原先那个"两端核对 6 位数字"的设计 —— boss 指出那本质上还是配对码。）
 *   其余防线：配对模式门控 / 限流 / 单 pending / TTL / 仅发起方 IP 可轮询 / 密钥一次性交付。
 */
object PairingState {
    private const val TTL_MS = 120_000L
    private const val MAX_PER_IP_PER_MIN = 3

    data class Req(
        val id: String,
        val name: String,
        val deviceId: String,
        val ip: String,
        val createdAt: Long,
        @Volatile var status: String = "pending",
        @Volatile var masterHex: String? = null,
        @Volatile var delivered: Boolean = false,
    )

    /** 配对模式：只有为 true 时才接受配对申请（由设置页进入/离开控制） */
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed

    /** 待确认的申请（UI 观察它来弹确认框） */
    private val _pending = MutableStateFlow<Req?>(null)
    val pending: StateFlow<Req?> = _pending

    @Volatile
    private var current: Req? = null

    private val rate = HashMap<String, MutableList<Long>>()
    private val rejected = AtomicLong(0)

    fun setArmed(on: Boolean) {
        _armed.value = on
        if (!on) {
            val r = current
            if (r != null && r.status == "pending") {
                r.status = "denied"
                r.masterHex = null
            }
            current = if (r != null && !r.delivered) null else current
            _pending.value = null
        }
    }

    /**
     * 收到配对申请。
     * @return Req 表示已受理（UI 会弹窗）；null 表示被四道门之一拒掉
     */
    @Synchronized
    fun request(name: String, deviceId: String, ip: String): Req? {
        // 门1：配对模式
        if (!_armed.value) {
            rejected.incrementAndGet()
            android.util.Log.w("PairingState", "拒绝配对申请：当前不在配对模式 (ip=$ip)")
            return null
        }
        val now = System.currentTimeMillis()

        // 门2：限流
        val l = rate.getOrPut(ip) { mutableListOf() }
        l.removeAll { now - it > 60_000 }
        if (l.size >= MAX_PER_IP_PER_MIN) {
            rejected.incrementAndGet()
            android.util.Log.w("PairingState", "拒绝配对申请：IP 触发限流 (ip=$ip)")
            return null
        }
        l.add(now)

        // 门3：同一时刻只允许一个待确认申请
        val cur = current
        if (cur != null && cur.status == "pending" && now - cur.createdAt < TTL_MS) {
            android.util.Log.w("PairingState", "拒绝配对申请：已有待确认申请 (ip=$ip)")
            return null
        }

        val id = TbCrypto.toHex(TbCrypto.randBytes(16))
        val r = Req(
            id = id,
            name = name.ifBlank { "电脑" },
            deviceId = deviceId,
            ip = ip,
            createdAt = now,
        )
        current = r
        _pending.value = r
        android.util.Log.i("PairingState", "收到配对申请：${r.name} @ $ip")
        return r
    }

    /** boss 在手机上点了「允许」→ 生成并下发长期密钥 */
    suspend fun approve(id: String): Boolean {
        val r = current ?: return false
        if (r.id != id || r.status != "pending") return false
        val master = TbCrypto.newMasterHex()
        AuthState.issue(master)
        TaskBarApp.instance.repo.setSetting("paired_device", r.name)
        r.masterHex = master
        r.status = "approved"
        _pending.value = null
        android.util.Log.i("PairingState", "已允许配对：${r.name} @ ${r.ip}")
        return true
    }

    /** boss 点了「拒绝」 */
    fun deny(id: String) {
        val r = current ?: return
        if (r.id != id) return
        r.status = "denied"
        r.masterHex = null
        _pending.value = null
        android.util.Log.i("PairingState", "已拒绝配对：${r.name} @ ${r.ip}")
    }

    /**
     * 电脑端轮询配对结果。
     * ⚠️ **只允许发起配对的那个 IP 查询** —— 否则同网段其他人拿到 sessionId 就能把密钥领走。
     */
    fun poll(id: String, ip: String): Req? {
        val r = current ?: return null
        if (r.id != id) return null
        if (r.ip != ip) {
            android.util.Log.w("PairingState", "轮询 IP 不匹配，忽略 (期望 ${r.ip}，实际 $ip)")
            return null
        }
        if (r.status == "pending" && System.currentTimeMillis() - r.createdAt > TTL_MS) {
            r.status = "expired"
        }
        return r
    }

    /** 密钥交付后作废该 session（一次性） */
    @Synchronized
    fun consume(id: String) {
        val r = current ?: return
        if (r.id == id) {
            r.delivered = true
            r.masterHex = null
            current = null
        }
    }

    /** 电脑端主动取消 */
    fun cancel(id: String) {
        val r = current ?: return
        if (r.id == id) {
            r.status = "denied"
            r.masterHex = null
            current = null
            _pending.value = null
        }
    }

    /** 最近被拒次数（配对失败时给用户一个解释） */
    fun rejectedCount(): Long = rejected.get()

}

// ═══════════════════════════ 请求级鉴权 ═══════════════════════════

/**
 * 受保护路由的统一入口（**双向认证的"手机验电脑"半边**）。
 *
 * 依次校验：密钥存在 → token → 时间戳 → nonce 未用过 → 签名 → 解密 body。
 *
 * @param method  HTTP 方法（大写）
 * @param rawBody **原始** body（未解密）—— 签名是对密文做的
 * @return 解密后的明文 body；返回 null 表示已回 401，调用方直接 `return@get`
 */
suspend fun ApplicationCall.guard(method: String, rawBody: String): String? {
    val keys = AuthState.keys()
    if (keys == null) {
        deny("not_paired")
        return null
    }

    // ① 身份
    val tok = request.header("X-TB-Token") ?: ""
    val tokBytes = runCatching { Base64.getDecoder().decode(tok) }.getOrNull()
    if (tokBytes == null || !TbCrypto.ctEq(tokBytes, keys.master)) {
        deny("bad_token")
        return null
    }

    // ② 新鲜度
    val ts = request.header("X-TB-Ts")?.toLongOrNull() ?: 0L
    if (abs(System.currentTimeMillis() - ts) > ALLOWED_SKEW_MS) {
        deny("stale")
        return null
    }

    // ③ 防重放
    val nonce = request.header("X-TB-Nonce") ?: ""
    if (nonce.length < 8 || !NonceCache.checkAndPut(nonce)) {
        deny("replay")
        return null
    }

    // ④ 签名（对密文签名 → 篡改密文即验签失败）
    val sig = request.header("X-TB-Sig") ?: ""
    val want = TbCrypto.sign(keys, method, request.path(), ts, nonce, rawBody)
    if (!TbCrypto.ctEq(sig.toByteArray(), want.toByteArray())) {
        deny("bad_sig")
        return null
    }

    // ⑤ 解密
    if (rawBody.isEmpty()) return ""
    return TbCrypto.open(keys, rawBody) ?: run {
        deny("bad_cipher")
        null
    }
}

private suspend fun ApplicationCall.deny(reason: String) {
    android.util.Log.w("auth", "拒绝请求 ${request.path()}：$reason")
    response.header("X-TB-Deny", reason)
    respondText(
        """{"status":"unauthorized","reason":"$reason"}""",
        status = HttpStatusCode.Unauthorized,
    )
}

/**
 * 加密 + 签名响应（**双向认证的"电脑验手机"半边**）。
 * 电脑端会验签，失败即认为回话方不是真手机。
 */
suspend fun ApplicationCall.respondSecure(json: String) {
    val keys = AuthState.keys()
    if (keys == null) {
        respondText("""{"status":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
        return
    }
    val ts = System.currentTimeMillis()
    val nonce = TbCrypto.randNonceHex()
    val wire = TbCrypto.seal(keys, json)
    response.header("X-TB-Ts", ts.toString())
    response.header("X-TB-Nonce", nonce)
    response.header("X-TB-Sig", TbCrypto.sign(keys, "R", request.path(), ts, nonce, wire))
    respondText(wire, status = HttpStatusCode.OK, contentType = ContentType.Application.Json)
}

/** WebSocket 握手鉴权（WS 不能自定义头，token 走查询参数） */
suspend fun wsTokenOk(token: String?): Boolean {
    val keys = AuthState.keys() ?: return false
    if (token.isNullOrEmpty()) return false
    // v5.17.2：同时接受标准 base64 与 URL-safe base64（有无 padding 都认）。
    //   起因：桌面端曾把含 `+` 的 token 原样拼进 query，被解成空格 → 校验失败 → 每 5s 重连。
    //   桌面端已改为百分号编码；这里做容错，避免以后再因编码细节互相踢线。
    val raw = token.replace(" ", "+")          // 万一又被解成空格（旧端）：还原成 +
    val b = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
        ?: runCatching { Base64.getUrlDecoder().decode(raw.trimEnd('=')) }.getOrNull()
        ?: return false
    return TbCrypto.ctEq(b, keys.master)
}
