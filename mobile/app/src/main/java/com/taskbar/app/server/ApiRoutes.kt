package com.taskbar.app.server

import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.ChangeOp
import com.taskbar.app.data.model.ChangesRequest
import com.taskbar.app.data.model.IncrementalPayload
import com.taskbar.app.data.model.WsMessage
import com.taskbar.app.data.repo.ChangeBus
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websockets.WebSockets
import io.ktor.server.websockets.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Ktor 插件 + 路由配置 */
fun Application.configureServer() {
    install(WebSockets)
    install(ContentNegotiation) { json(appJson) }

    routing {
        get("/api/ping") {
            call.respondText { """{"status":"ok","device":"taskguide-mobile","version":"1.0.0"}""" }
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
            req.changes.forEach { app.repo.applyChange(it) }
            call.respondText { """{"status":"ok","accepted":${req.changes.size}}""" }
        }

        // WebSocket 实时推送
        webSocket("/ws") {
            val app = TaskBarApp.instance
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
            }
        }
    }
}
