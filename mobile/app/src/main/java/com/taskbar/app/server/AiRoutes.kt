package com.taskbar.app.server

import com.taskbar.app.TaskBarApp
import com.taskbar.app.data.model.Priority
import com.taskbar.app.data.model.StepStatus
import com.taskbar.app.data.model.TaskType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * ==================== AI 控制接口（v5.30.0） ====================
 *
 * boss 诉求：「给 next任务 加个接口，AI 一接触就能很容易地操控这个 App —— 帮我加任务、
 * 完成任务、追踪任务…… 而且 AI 加的任务也要走双端同步」。
 *
 * 设计原则（为什么长这样）：
 *  1. **自描述**：`GET /ai/help` 回完整的接口清单 + 字段说明 + 示例 —— AI 读一次就会用，
 *     不需要人教，也不依赖任何本地文档。
 *  2. **明文 JSON**：不走设备间的 ChaCha20 握手（那是配对协议），AI 用普通 HTTP 即可。
 *  3. **一次性令牌**：`X-AI-Token` 头，或 `?token=` 查询参数（AI 工具爱用 URL）。
 *     令牌存设置项 `ai_token`，首次访问自动生成，设置页「AI 控制」可见/可复制。
 *  4. ⭐ **所有写操作都走 TaskRepository**：repo 每条写路径都带 `push_change` →
 *     AI 的改动自动经 WS 推给电脑端，与手机手动操作**完全同一条链路**。
 *     这是本接口与"直接改数据库"的根本区别 —— 直接改库不会触发同步。
 */
fun Route.aiRoutes() {
    get("/ai/help") {
        call.respondText(HELP, ContentType.Application.Json)
    }

    get("/ai/tasks") {
        if (!call.aiOk()) return@get
        val filter = call.request.queryParameters["filter"] ?: "today"
        val r = TaskBarApp.instance.repo
        if (filter == "deleted") {
            val uuids = r.deletedTaskUuids()
            call.respondJson(ok(buildJsonObject {
                put("filter", JsonPrimitive("deleted"))
                put("count", JsonPrimitive(uuids.size))
                put("deleted_uuids", buildJsonArray { uuids.forEach { add(JsonPrimitive(it)) } })
            }))
            return@get
        }
        val tasks = when (filter) {
            "all" -> r.observeAllForWarehouse().first()
            "tracking" -> r.observeTracking().first()
            else -> r.observeMainListToday().first()
        }
        val steps = r.observeAllSteps().first()
        call.respondJson(ok(buildJsonObject {
            put("filter", JsonPrimitive(filter))
            put("count", JsonPrimitive(tasks.size))
            put("tasks", buildJsonArray {
                tasks.forEach { t -> add(taskJson(t, steps.filter { it.taskUuid == t.uuid })) }
            })
        }))
    }

    post("/ai/tasks") {
        if (!call.aiOk()) return@post
        val b = call.bodyJson()
        val title = b.str("title")?.takeIf { it.isNotBlank() }
            ?: return@post call.respondJson(err("title 不能为空"), HttpStatusCode.BadRequest)
        val type = (b.str("type") ?: TaskType.ONCE).trim()
        val r = TaskBarApp.instance.repo
        val task = r.createTask(
            type = type,
            title = title,
            desc = b.str("desc") ?: "",
            category = b.str("category") ?: defaultCategory(type),
            priority = b.str("priority") ?: Priority.MEDIUM,
            dueAt = parseWhen(b.str("due_at")),
            repeatRule = b.str("repeat_rule"),
            deadline = parseWhen(b.str("deadline")),
            target = b.int("count") ?: b.int("target") ?: 1,
            remindAheadMin = b.int("remind_ahead_min") ?: 0,
            owner = b.str("owner") ?: ""
        )
        // 可选：随任务一起建步骤
        val stepTitles = mutableListOf<String>()
        b.arr("steps")?.forEach { el ->
            val s = runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                ?: runCatching { el.jsonObjectElement().str("title") }.getOrNull()
            if (!s.isNullOrBlank()) {
                r.addStep(task.uuid, s)
                stepTitles.add(s)
            }
        }
        call.respondJson(ok(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("uuid", JsonPrimitive(task.uuid))
            put("title", JsonPrimitive(task.title))
            put("steps_added", JsonPrimitive(stepTitles.size))
        }))
    }

    patch("/ai/tasks/{uuid}") {
        if (!call.aiOk()) return@patch
        val uuid = call.parameters["uuid"] ?: return@patch call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        val b = call.bodyJson()
        val r = TaskBarApp.instance.repo
        val cur = findTask(uuid)
            ?: return@patch call.respondJson(err("找不到任务 $uuid"), HttpStatusCode.NotFound)
        val updated = cur.copy(
            title = b.str("title") ?: cur.title,
            desc = b.str("desc") ?: cur.desc,
            category = b.str("category") ?: cur.category,
            priority = b.str("priority") ?: cur.priority,
            dueAt = if (b.containsKey("due_at")) parseWhen(b.str("due_at")) else cur.dueAt,
            repeatRule = if (b.containsKey("repeat_rule")) b.str("repeat_rule") else cur.repeatRule,
            target = b.int("count") ?: b.int("target") ?: cur.target,
            owner = b.str("owner") ?: cur.owner,
            type = b.str("type") ?: cur.type,
        )
        r.updateTask(updated)
        call.respondJson(ok(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("uuid", JsonPrimitive(uuid))
            put("title", JsonPrimitive(updated.title))
        }))
    }

    post("/ai/tasks/{uuid}/complete") {
        if (!call.aiOk()) return@post
        val uuid = call.parameters["uuid"] ?: return@post call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        if (findTask(uuid) == null) return@post call.respondJson(err("找不到任务 $uuid"), HttpStatusCode.NotFound)
        TaskBarApp.instance.repo.completeTask(uuid)
        call.respondJson(ok(buildJsonObject {
            put("ok", JsonPrimitive(true)); put("uuid", JsonPrimitive(uuid)); put("done", JsonPrimitive(true))
        }))
    }

    post("/ai/tasks/{uuid}/undo") {
        if (!call.aiOk()) return@post
        val uuid = call.parameters["uuid"] ?: return@post call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        TaskBarApp.instance.repo.restoreTask(uuid)
        call.respondJson(ok(buildJsonObject { put("ok", JsonPrimitive(true)); put("uuid", JsonPrimitive(uuid)) }))
    }

    post("/ai/tasks/{uuid}/track") {
        if (!call.aiOk()) return@post
        val uuid = call.parameters["uuid"] ?: return@post call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        val on = call.bodyJson().bool("on") ?: true
        val r = TaskBarApp.instance.repo
        if (on) {
            // TrackStartResult 细分（v5.28.1）—— 名额满/已完成等原因原样告诉 AI，别让它猜
            val res = r.startTracking(uuid)
            val reason = when (res) {
                com.taskbar.app.data.model.TrackStartResult.OK -> ""
                com.taskbar.app.data.model.TrackStartResult.LIMIT_REACHED -> "追踪名额已满（免费版 1 个 / 完整版上限见设置）"
                com.taskbar.app.data.model.TrackStartResult.ALREADY_DONE -> "任务已完成，不能追踪（要追踪先撤销完成）"
                com.taskbar.app.data.model.TrackStartResult.NOT_FOUND -> "找不到任务"
            }
            call.respondJson(ok(buildJsonObject {
                put("ok", JsonPrimitive(res == com.taskbar.app.data.model.TrackStartResult.OK))
                put("uuid", JsonPrimitive(uuid))
                put("tracking", JsonPrimitive(res == com.taskbar.app.data.model.TrackStartResult.OK))
                put("result", JsonPrimitive(res.name))
                put("reason", JsonPrimitive(reason))
            }))
        } else {
            r.stopTracking(uuid)
            call.respondJson(ok(buildJsonObject {
                put("ok", JsonPrimitive(true)); put("uuid", JsonPrimitive(uuid)); put("tracking", JsonPrimitive(false))
            }))
        }
    }

    delete("/ai/tasks/{uuid}") {
        if (!call.aiOk()) return@delete
        val uuid = call.parameters["uuid"] ?: return@delete call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        TaskBarApp.instance.repo.deleteTask(uuid)
        call.respondJson(ok(buildJsonObject { put("ok", JsonPrimitive(true)); put("uuid", JsonPrimitive(uuid)); put("deleted", JsonPrimitive(true)) }))
    }

    post("/ai/tasks/{uuid}/steps") {
        if (!call.aiOk()) return@post
        val uuid = call.parameters["uuid"] ?: return@post call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        val b = call.bodyJson()
        val title = b.str("title")?.takeIf { it.isNotBlank() }
            ?: return@post call.respondJson(err("title 不能为空"), HttpStatusCode.BadRequest)
        val step = TaskBarApp.instance.repo.addStep(
            taskUuid = uuid, title = title,
            attrLabel = b.str("attr_label") ?: "", attrValue = b.str("attr_value") ?: ""
        )
        call.respondJson(ok(buildJsonObject {
            put("ok", JsonPrimitive(true)); put("step_uuid", JsonPrimitive(step.uuid)); put("title", JsonPrimitive(title))
        }))
    }

    post("/ai/steps/{uuid}/done") {
        if (!call.aiOk()) return@post
        val uuid = call.parameters["uuid"] ?: return@post call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        val r = TaskBarApp.instance.repo
        val step = r.observeAllSteps().first().find { it.uuid == uuid }
            ?: return@post call.respondJson(err("找不到步骤 $uuid"), HttpStatusCode.NotFound)
        r.updateStep(step.copy(status = StepStatus.DONE, doneAt = System.currentTimeMillis()))
        call.respondJson(ok(buildJsonObject { put("ok", JsonPrimitive(true)); put("step_uuid", JsonPrimitive(uuid)); put("done", JsonPrimitive(true)) }))
    }

    delete("/ai/steps/{uuid}") {
        if (!call.aiOk()) return@delete
        val uuid = call.parameters["uuid"] ?: return@delete call.respondJson(err("缺少 uuid"), HttpStatusCode.BadRequest)
        TaskBarApp.instance.repo.deleteStep(uuid)
        call.respondJson(ok(buildJsonObject { put("ok", JsonPrimitive(true)); put("step_uuid", JsonPrimitive(uuid)) }))
    }

    /** 让双端立刻对齐：给所有已连接的电脑端广播 syncnow（桌面端收到就拉+推一轮） */
    post("/ai/sync") {
        if (!call.aiOk()) return@post
        val n = PULL_BUS.tryEmit(SYNCNOW)
        call.respondJson(ok(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("broadcast", JsonPrimitive(n))
            put("clients", JsonPrimitive(WS_CLIENT_COUNT.get()))
        }))
    }
}

// ==================== 内部工具 ====================

internal const val SYNCNOW = """{"op":"syncnow"}"""

/** 令牌校验；未通过时已写好 401 响应 */
private suspend fun ApplicationCall.aiOk(): Boolean {
    val expected = ensureAiToken()
    val got = request.headers["X-AI-Token"] ?: request.queryParameters["token"]
    if (got != null && got == expected) return true
    respondJson(err("token 不对或缺失 —— 手机端「设置 → AI 控制」可查看令牌；也支持 ?token=xxx"), HttpStatusCode.Unauthorized)
    return false
}

private suspend fun ensureAiToken(): String {
    val r = TaskBarApp.instance.repo
    val cur = r.getSetting("ai_token")
    if (cur.isNotEmpty()) return cur
    val fresh = UUID.randomUUID().toString().replace("-", "")
    r.setSetting("ai_token", fresh)
    return fresh
}

private suspend fun findTask(uuid: String): com.taskbar.app.data.model.Task? =
    TaskBarApp.instance.repo.observeMainListToday().first().find { it.uuid == uuid }

private fun defaultCategory(type: String): String = when (type) {
    TaskType.HABIT -> "daily"
    TaskType.GOAL, TaskType.MILESTONE -> "goal"
    TaskType.REPEAT -> "time-limited"
    else -> "once"
}

/** 时间：支持毫秒数 / "yyyy-MM-dd HH:mm" / "yyyy-MM-dd"（纯日期按当天 09:00） */
private fun parseWhen(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    val v = s.trim()
    v.toLongOrNull()?.let { return it }
    for (f in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "MM-dd HH:mm", "yyyy/MM/dd HH:mm")) {
        runCatching { SimpleDateFormat(f, Locale.CHINA).parse(v)?.time }.getOrNull()?.let { return it }
    }
    runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(v)?.time }.getOrNull()?.let { return it + 9L * 3600_000L }
    return null
}

private fun taskJson(
    t: com.taskbar.app.data.model.Task,
    steps: List<com.taskbar.app.data.model.Step>
): JsonObject = buildJsonObject {
    put("uuid", JsonPrimitive(t.uuid))
    put("title", JsonPrimitive(t.title))
    put("desc", JsonPrimitive(t.desc))
    put("type", JsonPrimitive(t.type))
    put("category", JsonPrimitive(t.category))
    put("priority", JsonPrimitive(t.priority))
    put("track_status", JsonPrimitive(t.trackStatus))
    put("done", JsonPrimitive(t.done))
    put("due_at", JsonPrimitive(t.dueAt))
    put("repeat_rule", JsonPrimitive(t.repeatRule))
    put("count", JsonPrimitive(t.target))
    put("done_count", JsonPrimitive(t.progress))
    put("owner", JsonPrimitive(t.owner))
    put("steps", buildJsonArray {
        steps.forEach { s ->
            add(buildJsonObject {
                put("uuid", JsonPrimitive(s.uuid))
                put("title", JsonPrimitive(s.title))
                put("status", JsonPrimitive(s.status))
                put("attr_label", JsonPrimitive(s.attrLabel))
                put("attr_value", JsonPrimitive(s.attrValue))
            })
        }
    })
}

private fun ok(j: JsonObject) = j.toString()

private fun err(msg: String, code: HttpStatusCode? = null) = buildJsonObject {
    put("ok", JsonPrimitive(false))
    put("error", JsonPrimitive(msg))
    if (code != null) put("code", JsonPrimitive(code.value))
}.toString()

private suspend fun ApplicationCall.bodyJson(): JsonObject = runCatching {
    appJson.parseToJsonElement(receiveText()).jsonObjectElement()
}.getOrNull() ?: JsonObject(emptyMap())

private fun kotlinx.serialization.json.JsonElement.jsonObjectElement(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

private suspend fun ApplicationCall.respondJson(text: String, code: HttpStatusCode = HttpStatusCode.OK) {
    respondText(text, ContentType.Application.Json, code)
}

private fun JsonObject.str(k: String): String? =
    this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.int(k: String): Int? =
    this[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

private fun JsonObject.bool(k: String): Boolean? =
    this[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

private val HELP = """
{
  "name": "next任务 AI 控制接口",
  "base": "http://<手机IP>:8899",
  "auth": "请求头 X-AI-Token: <令牌>（或 URL 追加 ?token=<令牌>）。令牌在手机端「设置 → AI 控制」里看。",
  "notes": [
    "所有写操作都会自动触发双端同步（与手机手动操作同一条链路），无需额外调用同步接口。",
    "任务 uuid 从 GET /ai/tasks 拿；它不会变，可长期保存。",
    "时间字段 due_at 支持毫秒时间戳，或 'yyyy-MM-dd HH:mm'、'yyyy-MM-dd'（纯日期按当天 09:00）。"
  ],
  "endpoints": [
    {"method":"GET","path":"/ai/help","desc":"本说明（免令牌）"},
    {"method":"GET","path":"/ai/tasks?filter=today|all|tracking|deleted","desc":"列任务（含步骤）。today = 主页今日口径"},
    {"method":"POST","path":"/ai/tasks","body":{"title":"必填","type":"once|habit|goal|note|repeat|milestone","category":"daily|goal|time-limited|once","priority":"high|medium|low","due_at":"2026-09-26 08:00","desc":"","count":8,"owner":"","steps":["第一步","第二步"]},"desc":"新建任务，steps 可一次带上"},
    {"method":"PATCH","path":"/ai/tasks/{uuid}","body":{"title":"","desc":"","priority":"","due_at":"","category":"","count":0,"owner":""},"desc":"改任务（只传要改的字段）"},
    {"method":"POST","path":"/ai/tasks/{uuid}/complete","desc":"完成任务"},
    {"method":"POST","path":"/ai/tasks/{uuid}/undo","desc":"撤销完成"},
    {"method":"POST","path":"/ai/tasks/{uuid}/track","body":{"on":true},"desc":"开始/停止追踪"},
    {"method":"DELETE","path":"/ai/tasks/{uuid}","desc":"删除任务（软删，可同步到电脑端）"},
    {"method":"POST","path":"/ai/tasks/{uuid}/steps","body":{"title":"步骤名","attr_label":"","attr_value":""},"desc":"给任务加一步"},
    {"method":"POST","path":"/ai/steps/{uuid}/done","desc":"完成某一步骤"},
    {"method":"DELETE","path":"/ai/steps/{uuid}","desc":"删除某一步骤"},
    {"method":"POST","path":"/ai/sync","desc":"立刻让电脑端拉+推一轮对齐"}
  ],
  "examples": [
    "curl -H 'X-AI-Token: TOKEN' 'http://192.168.43.66:8899/ai/tasks?filter=today'",
    "curl -X POST -H 'X-AI-Token: TOKEN' -H 'Content-Type: application/json' -d '{\"title\":\"周五交周报\",\"type\":\"once\",\"due_at\":\"2026-09-26 18:00\"}' 'http://192.168.43.66:8899/ai/tasks'",
    "curl -X POST -H 'X-AI-Token: TOKEN' 'http://192.168.43.66:8899/ai/tasks/<uuid>/complete'"
  ]
}
""".trimIndent()
