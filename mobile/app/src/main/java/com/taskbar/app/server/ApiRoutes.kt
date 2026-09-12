package com.taskbar.app.server

import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.ChangeOp
import com.taskbar.app.data.model.ChangesRequest
import com.taskbar.app.data.model.IncrementalPayload
import com.taskbar.app.data.model.WsMessage
import com.taskbar.app.data.repo.ChangeBus
import com.taskbar.app.data.repo.LinkState
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** v5.15.19：当前连接本机的电脑端 WS 数量（引用计数，归零才算"未连接"） */
private val WS_CLIENT_COUNT = java.util.concurrent.atomic.AtomicInteger(0)

/** Ktor 插件 + 路由配置 */
fun Application.configureServer() {
    install(WebSockets)
    install(ContentNegotiation) { json(appJson) }

    routing {
        get("/api/ping") {
            call.respondText { """{"status":"ok","device":"taskguide-mobile","version":"1.0.0"}""" }
        }

        // 配对状态：手机端记录已配对的电脑
        // v5.15.19：额外返回 connected（WS 实时连接状态）—— 电脑在线 ≠ 曾经配对过
        get("/api/pair/status") {
            val app = TaskBarApp.instance
            val device = app.repo.getSetting("paired_device", "")
            val connected = LinkState.isConnected
            call.respondText {
                """{"paired":${if (device.isEmpty()) "false" else "true"},"connected":$connected,"device":"$device"}"""
            }
        }

        // 电脑端发起配对：记录配对设备名（桌面端名字）
        post("/api/pair") {
            val body = call.receiveText()
            val name = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(body).jsonObject["deviceName"]?.jsonPrimitive?.contentOrNull ?: "电脑"
            val app = TaskBarApp.instance
            app.repo.setSetting("paired_device", name)
            call.respondText { """{"status":"ok","paired":true,"device":"$name"}""" }
        }

        // 解除配对（手机端主动断开）
        post("/api/pair/clear") {
            val app = TaskBarApp.instance
            app.repo.setSetting("paired_device", "")
            call.respondText { """{"status":"ok","paired":false}""" }
        }

        // 全量同步（首次连接）
        get("/api/sync/full") {
            val app = TaskBarApp.instance
            call.respond(app.repo.buildFullSyncPayload())
        }

        // 增量同步
        get("/api/sync/incremental") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val app = TaskBarApp.instance
            val changes = app.repo.buildIncrementalChanges(since)
            call.respond(IncrementalPayload(changes, System.currentTimeMillis()))
        }

        // 电脑端推送自己的变更
        post("/api/sync/changes") {
            val req = call.receive<ChangesRequest>()
            val app = TaskBarApp.instance
            // v5.15.7：逐条 runCatching —— 单条数据异常不该让整批变更（含积分/头像）一起失败
            var accepted = 0
            req.changes.forEach {
                runCatching { app.repo.applyChange(it) }.onSuccess { accepted++ }
                    .onFailure { e -> android.util.Log.e("sync", "applyChange 失败: ${it.entity}/${it.op}", e) }
            }
            call.respondText { """{"status":"ok","accepted":$accepted}""" }
        }

        // v5.15.6：电脑端推送设置变更（头像 emoji 同步）—— 用 JsonElement 接 body 避免新增 Request 类
        // v5.15.7：改为"最新为主"落库 + 不回推（避免与桌面端形成同步回环）
        post("/api/settings/upsert") {
            val body = call.receiveText()
            val elem = appJson.parseToJsonElement(body)
            val map = if (elem is kotlinx.serialization.json.JsonObject) elem else kotlinx.serialization.json.JsonObject(emptyMap())
            val key = map["key"]?.toString()?.trim('"') ?: ""
            val value = map["value"]?.toString()?.trim('"') ?: ""
            if (key.isNotEmpty()) {
                val ts = map["updated_at"]?.toString()?.trim('"')?.toLongOrNull() ?: System.currentTimeMillis()
                TaskBarApp.instance.repo.applySettingFromSync(key, value, ts)
            }
            call.respondText { """{"status":"ok","key":"$key"}""" }
        }

        // WebSocket 实时推送
        webSocket("/ws") {
            val app = TaskBarApp.instance
            // v5.15.19：电脑端连上 → 标记"已连接"。
            //   用引用计数是因为可能同时有多个 WS（主窗 + 挂件重连瞬间的旧连接未完全释放），
            //   直接置 true/false 会被后断开的那条错误覆盖成"未连接"。
            WS_CLIENT_COUNT.incrementAndGet()
            LinkState.set(true)
            // 子协程：接收电脑端上报的变更
            val incomingJob = launch {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        if (text.contains("\"ping\"")) {
                            send(Frame.Text("""{"op":"pong"}"""))
                            continue
                        }
                        runCatching {
                            val msg = appJson.decodeFromString(WsMessage.serializer(), text)
                            if (msg.op == "upsert" || msg.op == "delete") {
                                app.repo.applyChange(
                                    ChangeOp(msg.op, msg.entity, msg.uuid, msg.data.ifEmpty { null })
                                )
                            }
                        }
                    }
                }
            }
            // 主流程：订阅本地变更总线，推送给电脑端
            try {
                ChangeBus.events.collect { op ->
                    send(Frame.Text(appJson.encodeToString(ChangeOp.serializer(), op)))
                }
            } finally {
                incomingJob.cancel()
                // v5.15.19：连接结束 → 计数减一，归零才标记"未连接"
                if (WS_CLIENT_COUNT.decrementAndGet() <= 0) {
                    WS_CLIENT_COUNT.set(0)
                    LinkState.set(false)
                }
            }
        }
    }
}
