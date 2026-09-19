package com.taskbar.app.server

import com.taskbar.app.TaskBarApp
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import java.security.SecureRandom

/**
 * v5.16.0 安全加固（P0）
 *
 * 修复的漏洞：
 *   ① 手机端 Ktor 监听 0.0.0.0 且 **API 全裸无鉴权** —— 同一 WiFi 下任何人可
 *      `GET /api/sync/full` 拿走全部数据、`POST /api/sync/changes` 清空/篡改数据。
 *   ② 配对机制形同虚设 —— 原 `/api/pair` 直接接受任意 deviceName 即配对成功，
 *      配对码从未被使用（安全边界等于零）。
 *
 * 本文件的三个部件：
 *   [constantTimeEquals] 常量时间比较（防时序侧信道）
 *   [PairCode]           一次性配对码：6 位 / 5 分钟 / 最多试 5 次 / 用过即废
 *   [AuthState]          长期共享密钥：配对成功后下发，双方持久化
 *   [requireAuth]        路由级鉴权：`if (!call.requireAuth()) return@get`
 */

/** 常量时间字符串比较 —— 避免用 `==` 时因提前返回而泄露前缀信息 */
fun constantTimeEquals(a: String, b: String): Boolean {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    if (x.size != y.size) return false
    var diff = 0
    for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
    return diff == 0
}

/**
 * 一次性配对码。
 *
 * 流程：手机端显示 6 位码 → 用户输入到桌面端 → 桌面端 `POST /api/pair {code}` →
 * 校验通过才下发 [AuthState.secret]。
 *
 * 安全设计：
 *   - **5 分钟过期**（超时自动换新码）
 *   - **最多尝试 5 次**（防 6 位码被暴力枚举；1e6 组合 ÷ 5 次 ≈ 可忽略）
 *   - **用过即废**（成功一次后立刻失效，不能重复用同一码再配一台设备）
 */
object PairCode {
    private const val TTL_MS = 5 * 60 * 1000L
    private const val MAX_ATTEMPTS = 5
    private val rnd = SecureRandom()

    private var code: String = ""
    private var expireAt: Long = 0L
    private var attempts: Int = 0

    /** 取当前码（已过期则自动生成新码） */
    @Synchronized
    fun current(): String {
        if (code.isEmpty() || System.currentTimeMillis() > expireAt) newCodeLocked()
        return code
    }

    /** 主动刷新（设置页「换一个」按钮） */
    @Synchronized
    fun refresh(): String {
        newCodeLocked()
        return code
    }

    private fun newCodeLocked() {
        code = (rnd.nextInt(900_000) + 100_000).toString()
        expireAt = System.currentTimeMillis() + TTL_MS
        attempts = 0
    }

    /** 剩余有效秒数（UI 倒计时用） */
    @Synchronized
    fun remainSeconds(): Int {
        val r = (expireAt - System.currentTimeMillis()) / 1000
        return if (r < 0) 0 else r.toInt()
    }

    /** 校验并消耗 */
    @Synchronized
    fun verify(input: String): Boolean {
        if (code.isEmpty() || System.currentTimeMillis() > expireAt) return false
        if (attempts >= MAX_ATTEMPTS) return false
        attempts++
        val ok = constantTimeEquals(code, input.trim())
        if (ok) {
            code = ""
            expireAt = 0L
            attempts = 0
        }
        return ok
    }
}

/**
 * 长期共享密钥（配对成功后双方各存一份）。
 *
 * 存放位置：settings 表，key = `auth_secret`。
 * 注意：**它不参与同步**（`applySettingFromSync` 只处理白名单 key），
 * 否则会出现"要同步得先有密钥、要密钥得先同步"的自锁。
 */
object AuthState {
    private const val KEY = "auth_secret"

    @Volatile
    private var cached: String? = null

    suspend fun secret(): String {
        cached?.let { return it }
        val s = TaskBarApp.instance.repo.getSetting(KEY, "")
        cached = s
        return s
    }

    suspend fun isPaired(): Boolean = secret().isNotEmpty()

    /** 生成并下发新密钥（配对成功时调用） */
    suspend fun issue(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val s = bytes.joinToString("") { "%02x".format(it) }
        TaskBarApp.instance.repo.setSetting(KEY, s)
        cached = s
        return s
    }

    /** 作废（解除配对 / 换设备） */
    suspend fun clear() {
        TaskBarApp.instance.repo.setSetting(KEY, "")
        cached = ""
    }
}

/**
 * 路由级鉴权。
 *
 * - 放行：`X-TB-Token` 头 或 `?token=` 查询参数 与本地密钥**常量时间相等**
 * - 拒绝：回 401（body 里不放任何有效信息）
 *
 * 用法：`get("/api/xxx") { if (!call.requireAuth()) return@get; ... }`
 */
suspend fun ApplicationCall.requireAuth(): Boolean {
    val tok = request.header("X-TB-Token") ?: request.queryParameters["token"] ?: ""
    val secret = AuthState.secret()
    if (secret.isNotEmpty() && constantTimeEquals(secret, tok)) return true
    respondText("""{"status":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
    return false
}

/** WebSocket 握手鉴权（token 走查询参数，WS 不能自定义头） */
suspend fun wsTokenOk(token: String?): Boolean {
    val secret = AuthState.secret()
    return secret.isNotEmpty() && token != null && constantTimeEquals(secret, token)
}
