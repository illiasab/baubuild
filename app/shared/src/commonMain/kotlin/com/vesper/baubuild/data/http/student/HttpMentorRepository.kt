package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.network.AppJson
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.repository.MentorCitation
import com.vesper.baubuild.data.repository.MentorChatMessage
import com.vesper.baubuild.data.repository.MentorEvent
import com.vesper.baubuild.data.repository.MentorRepository
import com.vesper.baubuild.data.repository.MentorRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * [MentorRepository] on the BAU BUILD backend (server/go `extra.go` / `kb.go` `mentorStream`).
 *
 * - `GET /v1/projects/{id}/mentor/messages` → `{items:[{id, role, text, task_id, citations:[{title, rule_id,
 *   document_id}], created_at}], next_cursor}`. Role `user`/`student` → [MentorRole.User], anything else assistant.
 * - `POST /v1/projects/{id}/mentor/messages` `{text, task_id?}` → `text/event-stream`:
 *   `status {label}` → `citation {title, rule_id?, document_id?}`* → `delta {text}`* → `done {message_id, id, text}`;
 *   an `error {code, message}` event is mapped to [MentorEvent.Error]. A plain JSON answer (`{text}` / `{reply}`)
 *   becomes one delta + done, like the web client's `sse()`.
 *
 * Streaming goes through the shared authenticated client (Bearer + refresh). Parsing is lenient: unknown events and
 * malformed data lines are skipped.
 */
class HttpMentorRepository(private val client: HttpClient) : MentorRepository {

    override suspend fun history(projectId: String): List<MentorChatMessage> {
        val root: JsonElement = apiCall { client.get(messagesPath(projectId)).body() }
        val items = (root as? JsonObject)?.get("items") as? JsonArray ?: (root as? JsonArray) ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.toMessage() }
    }

    override fun send(projectId: String, text: String, taskId: String?): Flow<MentorEvent> = flow {
        val body = buildJsonObject {
            put("text", text)
            if (!taskId.isNullOrBlank()) put("task_id", taskId)
        }
        apiCall {
            client.preparePost(messagesPath(projectId)) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(body)
                // The server builds the whole reply (LLM call, up to ~25 s) before it starts streaming.
                timeout {
                    requestTimeoutMillis = STREAM_TIMEOUT_MS
                    socketTimeoutMillis = STREAM_TIMEOUT_MS
                }
            }.execute { response ->
                val type = response.contentType()
                val channel = response.bodyAsChannel()
                if (type != null && !type.match(ContentType.Text.EventStream)) {
                    val raw = StringBuilder()
                    while (true) raw.append(channel.readUTF8Line() ?: break).append('\n')
                    emitPlainReply(raw.toString())
                    return@execute
                }
                val parser = SseParser()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    parser.feed(line)?.let { (event, data) -> toMentorEvent(event, data)?.let { emit(it) } }
                }
                parser.finish()?.let { (event, data) -> toMentorEvent(event, data)?.let { emit(it) } }
            }
        }
    }

    private suspend fun FlowCollector<MentorEvent>.emitPlainReply(raw: String) {
        val json = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull()
        val text = (json as? JsonObject)?.let { it.string("text") ?: it.string("reply") } ?: raw.trim().takeIf { json == null }
        if (!text.isNullOrEmpty()) emit(MentorEvent.Delta(text))
        emit(MentorEvent.Done(messageId = (json as? JsonObject)?.let { it.string("message_id") ?: it.string("id") }, text = text))
    }

    private fun messagesPath(projectId: String) = "v1/projects/${projectId.encodeURLPathPart()}/mentor/messages"

    companion object {
        const val STREAM_TIMEOUT_MS = 90_000L
    }
}

/** Minimal `text/event-stream` line parser (event + multi-line data, `:` comments, blank-line dispatch). */
internal class SseParser {
    private var event = "message"
    private val data = mutableListOf<String>()

    /** Feeds one line; returns `(event, data)` when it completes an event. */
    fun feed(rawLine: String): Pair<String, String>? {
        val line = rawLine.removeSuffix("\r")
        when {
            line.isEmpty() -> return dispatch()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> data += line.substring(5).removePrefix(" ")
            line == "data" -> data += ""
        }
        return null
    }

    /** End of stream: dispatches a last event that had no trailing blank line. */
    fun finish(): Pair<String, String>? = dispatch()

    private fun dispatch(): Pair<String, String>? {
        val out = if (data.isEmpty()) null else event.ifEmpty { "message" } to data.joinToString("\n")
        event = "message"
        data.clear()
        return out
    }
}

/** Maps one SSE event to a [MentorEvent]; null for unknown / empty events. Data may be JSON or plain text. */
internal fun toMentorEvent(event: String, data: String): MentorEvent? {
    val json = runCatching { AppJson.parseToJsonElement(data) }.getOrNull()
    val obj = json as? JsonObject
    val plain: String? = when {
        json == null -> data
        json is JsonPrimitive && json.isString -> json.content
        else -> null
    }
    return when (event) {
        "status" -> (obj?.let { it.string("label") ?: it.string("text") ?: it.string("message") } ?: plain)
            ?.takeIf { it.isNotBlank() }?.let { MentorEvent.Status(it) }
        "citation" -> obj?.toCitation()?.let { MentorEvent.Citation(it) }
        "delta" -> (obj?.let { it.string("text") ?: it.string("delta") ?: it.string("content") } ?: plain)
            ?.takeIf { it.isNotEmpty() }?.let { MentorEvent.Delta(it) }
        "done" -> MentorEvent.Done(
            messageId = obj?.let { it.string("message_id") ?: it.string("id") },
            text = obj?.string("text"),
        )
        "error" -> {
            val err = (obj?.get("error") as? JsonObject) ?: obj
            MentorEvent.Error(
                code = err?.string("code") ?: "stream_error",
                message = err?.string("message") ?: plain ?: "The mentor stopped answering.",
                retryAfterSeconds = (err?.get("retry_after_s") as? JsonPrimitive)?.longOrNull,
            )
        }
        "message" -> obj?.let { it.string("text") ?: it.string("reply") }?.let { MentorEvent.Delta(it) }
        else -> null
    }
}

private fun JsonObject.string(key: String): String? = when (val v = get(key)) {
    null, JsonNull -> null
    is JsonPrimitive -> v.contentOrNull
    else -> null
}

private fun JsonObject.toCitation(): MentorCitation? {
    val title = string("title").orEmpty()
    val rule = string("rule_id")?.takeIf { it.isNotBlank() }
    val doc = string("document_id")?.takeIf { it.isNotBlank() }
    if (title.isBlank() && rule == null) return null
    return MentorCitation(title = title.ifBlank { rule.orEmpty() }, ruleId = rule, documentId = doc)
}

private fun JsonObject.toMessage(): MentorChatMessage? {
    val text = string("text") ?: string("body") ?: return null
    val role = when (string("role")) {
        "user", "student" -> MentorRole.User
        else -> MentorRole.Assistant
    }
    return MentorChatMessage(
        id = string("id").orEmpty(),
        role = role,
        text = text,
        taskId = string("task_id")?.takeIf { it.isNotBlank() },
        citations = (get("citations") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toCitation() },
        createdAt = string("created_at"),
    )
}
