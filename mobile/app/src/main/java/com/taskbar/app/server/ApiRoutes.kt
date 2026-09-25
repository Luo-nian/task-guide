package com.taskbar.app.server

import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.ChangeOp
import com.taskbar.app.data.model.ChangesRequest
import com.taskbar.app.data.model.FullSyncPayload
import com.taskbar.app.data.model.IncrementalPayload
import com.taskbar.app.data.model.WsMessage
import com.taskbar.app.notify.ReminderScheduler
import com.taskbar.app.data.repo.ChangeBus
import com.taskbar.app.data.repo.LinkState
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** v5.15.19：当前连接本机的电脑端 WS 数量（引用计数，归零才算"未连接"） */
private val WS_CLIENT_COUNT = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Ktor 插件 + 路由配置
 *
 * ⚠️ 安全边界（v5.17.0 定稿）：
 *
 *  **公开接口只有三个**（都在"确认之前"，所以必须自己能扛住）：
 *    - `GET  /api/ping`          探活：只回 `{"status":"ok"}`，不泄露任何信息
 *    - `POST /api/pair/request`  配对申请：四道门（配对模式 / 限流 / 单 pending / kat 互校）
 *    - `GET  /api/pair/poll`     取配对结果：**仅发起方 IP** + 2 分钟 TTL + 密钥一次性交付
 *
 *  **其余全部要求签名 + 加密**（见 [guard]）：
 *    `/api/pair/status`、`/api/pair/clear`、`/api/sync/…`、`/api/settings/upsert`、`/ws`
 */
fun Application.configureServer() {
    install(WebSockets)
    install(ContentNegotiation) { json(appJson) }

    // v5.17.0：加密实现自检（若两端算法不一致，日志里会立刻暴露，而不是等同步失败）
    android.util.Log.i(
        "crypto",
        "ChaCha20/RFC8439 自检 = " + (if (TbCrypto.selfTest()) "OK" else "FAIL") +
            " | probe=" + TbCrypto.katProbe().take(16)
    )

    routing {
        // ═══════════ 公开接口（仅三个）═══════════

        get("/api/ping") {
            call.respondText("""{"status":"ok"}""")
        }

        /**
         * 配对申请 —— "扫描设备 → 一端发申请 → 另一端弹窗确认" 里的第 2 步。
         *
         * 这一段的锁（boss 要求"把确认之前的锁写好点"）：
         *   ① 配对模式门控：[PairingState.armed] 为 false 直接 403（人没看着手机时不受理）
         *   ② 限流：同 IP 每分钟 ≤ 3 次
         *   ③ 单 pending：同一时刻只允许一个待确认申请
         *   ④ kat 互校：两端加密实现指纹必须一致，否则拒绝（防止版本错配导致静默失败）
         * 只有全部通过，手机才会弹窗 —— **真正的授权发生在人点「允许」那一刻**。
         */
        post("/api/pair/request") {
            val raw = call.receiveText()
            val obj = runCatching { appJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (obj == null) {
                call.respondText("""{"status":"bad_request"}""", status = HttpStatusCode.BadRequest)
                return@post
            }
            val name = obj["deviceName"]?.jsonPrimitive?.contentOrNull ?: "电脑"
            val deviceId = obj["deviceId"]?.jsonPrimitive?.contentOrNull ?: ""
            val kat = obj["kat"]?.jsonPrimitive?.contentOrNull ?: ""

            if (kat != TbCrypto.katProbe()) {
                android.util.Log.w("pair", "kat 不一致 → 两端加密实现不匹配，拒绝配对")
                call.respondText(
                    """{"status":"crypto_mismatch"}""",
                    status = HttpStatusCode.BadRequest,
                )
                return@post
            }
            val ip = call.request.origin.remoteHost
            val r = PairingState.request(name, deviceId, ip)
            if (r == null) {
                call.respondText(
                    """{"status":"rejected","armed":${PairingState.armed.value}}""",
                    status = HttpStatusCode.Forbidden,
                )
                return@post
            }
            call.respondText("""{"status":"pending","sessionId":"${r.id}","ttl":120}""")
        }

        /** 取配对结果：仅发起方 IP 可查；approved 时**一次性**交付 master */
        get("/api/pair/poll") {
            val sid = call.request.queryParameters["sessionId"] ?: ""
            val ip = call.request.origin.remoteHost
            val r = PairingState.poll(sid, ip)
            if (r == null) {
                call.respondText("""{"status":"expired"}""")
                return@get
            }
            when (r.status) {
                "approved" -> {
                    val m = r.masterHex
                    PairingState.consume(sid)
                    if (m == null) {
                        call.respondText("""{"status":"expired"}""")
                    } else {
                        call.respondText(
                            """{"status":"approved","master":"$m",""" +
                                """"deviceName":${appJson.encodeToString(String.serializer(), r.name)}}"""
                        )
                    }
                }
                "denied" -> call.respondText("""{"status":"denied"}""")
                "expired" -> call.respondText("""{"status":"expired"}""")
                else -> call.respondText("""{"status":"pending"}""")
            }
        }

        // ═══════════ 以下全部要求签名 + 加密 ═══════════

        // 配对状态：手机端记录已配对的电脑（额外返回 connected = WS 实时状态）
        get("/api/pair/status") {
            if (call.guard("GET", "") == null) return@get
            val app = TaskBarApp.instance
            // v5.27.0：多设备 —— paired = 表非空；device/fp 取第一台（响应字段与旧版一致，桌面端兼容）
            val first = AuthState.allDevices().firstOrNull()
            val connected = LinkState.isConnected
            val fp = first?.let { AuthState.fingerprintOf(it) } ?: ""
            val deviceName = first?.name ?: ""
            call.respondSecure(
                """{"paired":${first != null},"connected":$connected,""" +
                    """"device":${appJson.encodeToString(String.serializer(), deviceName)},"fp":"$fp"}"""
            )
        }

        // 解除配对（同时作废密钥，旧 token 立即失效）
        post("/api/pair/clear") {
            val raw = call.receiveText()
            if (call.guard("POST", raw) == null) return@post
            val app = TaskBarApp.instance
            app.repo.setSetting("paired_device", "")
            AuthState.clear()
            call.respondText("""{"status":"ok","paired":false}""")
        }

        // 全量同步（首次连接）
        get("/api/sync/full") {
            if (call.guard("GET", "") == null) return@get
            val payload = TaskBarApp.instance.repo.buildFullSyncPayload()
            call.respondSecure(appJson.encodeToString(FullSyncPayload.serializer(), payload))
        }

        // 增量同步
        get("/api/sync/incremental") {
            if (call.guard("GET", "") == null) return@get
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val app = TaskBarApp.instance
            val changes = app.repo.buildIncrementalChanges(since)
            val payload = IncrementalPayload(changes, System.currentTimeMillis())
            call.respondSecure(appJson.encodeToString(IncrementalPayload.serializer(), payload))
        }

        // v5.29.0 D-01：任务动态时间线（桌面详情页「动态」区数据源 —— 桌面端此前一直没有时间线）
        get("/api/task_changes") {
            if (call.guard("GET", "") == null) return@get
            val uuid = call.request.queryParameters["task_uuid"].orEmpty()
            if (uuid.isBlank()) {
                call.respondSecure("""{"error":"task_uuid required"}""")
            } else {
                val logs = TaskBarApp.instance.repo.changesFor(uuid)
                call.respondSecure(
                    appJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.taskbar.app.data.model.ChangeLogDto.serializer()),
                        logs.map { com.taskbar.app.data.model.ChangeLogDto(it.who, it.action, it.detail, it.createdAt) }
                    )
                )
            }
        }

        // 电脑端推送自己的变更
        post("/api/sync/changes") {
            val raw = call.receiveText()
            val plain = call.guard("POST", raw) ?: return@post
            val req = runCatching {
                appJson.decodeFromString(ChangesRequest.serializer(), plain)
            }.getOrNull()
            if (req == null) {
                call.respondSecure("""{"status":"bad_body","accepted":0}""")
                return@post
            }
            val app = TaskBarApp.instance
            // v5.15.7：逐条 runCatching —— 单条数据异常不该让整批变更一起失败
            // v5.27.0：who = 按 token 反查的设备名（变更记录记「谁」）
            val who = call.currentDevice()?.name ?: ""
            var accepted = 0
            var taskChanged = false
            req.changes.forEach {
                runCatching { app.repo.applyChange(it, who) }.onSuccess { accepted++ }
                    .onFailure { e -> android.util.Log.e("sync", "applyChange 失败: ${it.entity}/${it.op}", e) }
                if (it.entity == "task") taskChanged = true
            }
            // v5.28.2：桌面改了任务（尤其每日提醒时间）→ 必须重排本机闹钟。
            //   实测：桌面把每日任务 09:00 改 01:29，手机库同步到位但闹钟还是旧的 09:00
            //   （v5.26.0 修的是"UI 写路径"重排，WS 接收路径一直漏着）。
            //   rescheduleAll 幂等（唯一名 REPLACE），顺便把被改时间的旧闹钟撤掉。
            if (taskChanged) {
                runCatching { ReminderScheduler.rescheduleAll(app.repo, app) }
            }
            call.respondSecure("""{"status":"ok","accepted":$accepted}""")
        }

        // 电脑端推送设置变更（头像 emoji 同步）—— 用 JsonElement 接 body 避免新增 Request 类
        post("/api/settings/upsert") {
            val raw = call.receiveText()
            val plain = call.guard("POST", raw) ?: return@post
            val elem = runCatching { appJson.parseToJsonElement(plain) }.getOrNull()
            val map = if (elem is JsonObject) elem else JsonObject(emptyMap())
            val key = map["key"]?.jsonPrimitive?.contentOrNull ?: ""
            val value = map["value"]?.jsonPrimitive?.contentOrNull ?: ""
            if (key.isNotEmpty()) {
                val ts = map["updated_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: System.currentTimeMillis()
                TaskBarApp.instance.repo.applySettingFromSync(key, value, ts)
            }
            val keyJson = appJson.encodeToString(String.serializer(), key)
            call.respondSecure("""{"status":"ok","key":$keyJson}""")
        }

        // WebSocket 实时推送（握手校验 token；**帧内容同样加密**）
        webSocket("/ws") {
            // v5.27.0：按 token 反查**这台连接对应的设备** —— 该连接的加解密与变更记录「谁」都用它
            val device = wsMatchDevice(call.request.queryParameters["token"])
            if (device == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            val keys = AuthState.keysOf(device)
            if (keys == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not_paired"))
                return@webSocket
            }
            val app = TaskBarApp.instance
            WS_CLIENT_COUNT.incrementAndGet()
            LinkState.set(true)

            // 子协程：接收电脑端上报的变更（帧可能是 E1: 密文，也可能是不带前缀的明文：兼容旧端）
            val incomingJob = launch {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val rawText = frame.readText()
                        val text = runCatching { TbCrypto.open(keys, rawText) }.getOrNull() ?: continue
                        if (text.contains("\"ping\"")) {
                            send(Frame.Text(TbCrypto.seal(keys, """{"op":"pong"}""")))
                            continue
                        }
                        runCatching {
                            val msg = appJson.decodeFromString(WsMessage.serializer(), text)
                            if (msg.op == "upsert" || msg.op == "delete") {
                                app.repo.applyChange(
                                    ChangeOp(msg.op, msg.entity, msg.uuid, msg.data.ifEmpty { null }),
                                    device.name
                                )
                                // v5.28.2：WS 实时路径同样要重排（同 /api/changes 的教训）
                                if (msg.entity == "task") {
                                    runCatching { ReminderScheduler.rescheduleAll(app.repo, app) }
                                }
                            }
                        }
                    }
                }
            }

            try {
                ChangeBus.events.collect { op ->
                    val json = appJson.encodeToString(ChangeOp.serializer(), op)
                    send(Frame.Text(TbCrypto.seal(keys, json)))
                }
            } finally {
                incomingJob.cancel()
                if (WS_CLIENT_COUNT.decrementAndGet() <= 0) {
                    WS_CLIENT_COUNT.set(0)
                    LinkState.set(false)
                }
            }
        }
    }
}
