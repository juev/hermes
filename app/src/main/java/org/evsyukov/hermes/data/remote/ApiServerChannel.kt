package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The stock Hermes API server (`gateway.platforms.api_server`) as the phone's channel.
 *
 * Every call here is a documented platform endpoint — `/api/sessions`, `/api/sessions/{id}/chat/stream`,
 * `/v1/runs/{id}` — so the channel adds no server-side API of its own. Sessions listed with
 * `source=api_server` are this channel's conversations: the API server stamps that source on every
 * session it creates, which keeps Telegram, Matrix, desktop and cron conversations out of the list.
 *
 * The turn itself is one SSE request. Its events are the documented ones: `run.started`,
 * `message.started`, `assistant.delta`, `assistant.completed`, `run.completed`, `approval.request`,
 * `tool.*`, `assistant.commentary` and `done`. Questions and approvals are answered through the
 * run control routes, whose bodies require the exact `execution_generation` the server reports.
 */
class ApiServerChannel(private val http: HermesHttp) {

    data class Chat(
        val id: String,
        val title: String,
        val updated: Double,
        val preview: String?,
        val messages: Int,
    )

    data class Row(
        val id: String?,
        val role: String,
        val text: String,
        val timestamp: Double,
        val displayKind: String?,
    )

    data class Page(val rows: List<Row>, val hasMore: Boolean)

    /** One pending question or approval: the exact body the answer routes expect. */
    data class Prompt(
        val kind: String,
        val promptId: String,
        val generation: Long,
        val question: String,
        val choices: List<String>,
        val multi: Boolean,
    )

    sealed interface Event {
        data class Started(val runId: String) : Event
        data class Delta(val text: String) : Event
        data class Commentary(val text: String) : Event
        /** A tool or reasoning step; the client shows one working indicator, never the payload. */
        data class Working(val tool: String?) : Event
        data class Approval(val text: String, val runId: String) : Event
        data class Completed(val text: String, val runId: String) : Event
        data class Failed(val message: String) : Event
    }

    /** The channel's conversations; [offset] pages the same recency order the server uses. */
    suspend fun chats(limit: Int = 50, offset: Int = 0): List<Chat> {
        val root = objectOf(http.getJson("api/sessions", mapOf("source" to SOURCE, "limit" to limit, "offset" to offset)))
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            Chat(
                id = obj.string("id") ?: return@mapNotNull null,
                title = obj.string("title").orEmpty(),
                updated = obj.double("last_active") ?: obj.double("started_at") ?: 0.0,
                preview = obj.string("preview"),
                messages = obj.long("message_count")?.toInt() ?: 0,
            )
        }
    }

    suspend fun create(title: String? = null): String {
        val body = buildJsonObject { if (!title.isNullOrBlank()) put("title", title) }
        val root = objectOf(http.postJson("api/sessions", body))
        return (root["session"] as? JsonObject)?.string("id")
            ?: root.string("id")
            ?: error("The API server did not return a session id")
    }

    suspend fun rename(id: String, title: String) {
        http.execute { url(http.endpoint("api/sessions/$id").build()); patch(buildJsonObject { put("title", title) }.toBody()) }.consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
        }
    }

    suspend fun delete(id: String) {
        http.delete("api/sessions/$id")
    }

    /** Readable transcript, newest page last; hidden rows are filtered here, not stored away. */
    suspend fun history(id: String, limit: Int = 200, offset: Int = 0): Page {
        val root = objectOf(http.getJson("api/sessions/$id/messages", mapOf("limit" to limit, "offset" to offset)))
        val rows = (root["data"] as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            Row(
                id = obj.string("id"),
                role = obj.string("role").orEmpty(),
                text = obj.string("content").orEmpty(),
                timestamp = obj.double("timestamp") ?: 0.0,
                displayKind = obj.string("display_kind"),
            )
        }
        val pagination = root["pagination"] as? JsonObject
        val hasMore = pagination?.bool("has_more") ?: pagination?.bool("has_more_pages") ?: false
        return Page(rows, hasMore)
    }

    /**
     * One turn: the message goes up, the answer streams back. Cancelling the coroutine closes the
     * request, which is the API server's own "client went away" signal.
     */
    suspend fun stream(id: String, message: String, onEvent: suspend (Event) -> Unit) {
        val body = buildJsonObject { put("message", message) }.toBody()
        val token = http.accessToken()
        val request = Request.Builder()
            .url(http.endpoint("api/sessions/$id/chat/stream").build())
            .header("Accept", "text/event-stream")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .post(body)
            .build()
        streamClient.newCall(request).await().consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
            var name: String? = null
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val event = parse(name, line.removePrefix("data:").trim())
                        name = null
                        if (event != null) onEvent(event)
                    }
                    line.isBlank() -> name = null
                }
            }
        }
    }

    /** The run's pending questions and approvals, with the generation the answer must carry. */
    suspend fun pending(runId: String): List<Prompt> {
        val root = objectOf(http.getJson("v1/runs/$runId"))
        return (root["pending_controls"] as? JsonArray).orEmpty().mapNotNull { control ->
            val obj = control as? JsonObject ?: return@mapNotNull null
            val kind = obj.string("kind") ?: return@mapNotNull null
            if (kind != "clarify" && kind != "approval") return@mapNotNull null
            Prompt(
                kind = kind,
                promptId = obj.string("prompt_id") ?: return@mapNotNull null,
                generation = obj.long("execution_generation") ?: return@mapNotNull null,
                question = obj.string("question") ?: obj.string("description") ?: obj.string("command").orEmpty(),
                choices = (obj["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                multi = obj.bool("multi_select") ?: false,
            )
        }
    }

    /** Answer the exact request the server reported; a stale generation is refused, never retried. */
    suspend fun answer(runId: String, prompt: Prompt, choice: String) {
        val path = if (prompt.kind == "clarify") "v1/runs/$runId/clarify" else "v1/runs/$runId/approval"
        val body = buildJsonObject {
            put("request_id", prompt.promptId)
            put("execution_generation", prompt.generation)
            if (prompt.kind == "clarify") put("answer", choice) else put("choice", choice)
        }
        http.postJson(path, body)
    }

    suspend fun stop(runId: String) {
        http.postJson("v1/runs/$runId/stop")
    }

    /** Session authority routes answer 404 for a run that is not this channel's; that is not a failure. */
    suspend fun pendingOrEmpty(runId: String): List<Prompt> = try {
        pending(runId)
    } catch (error: HermesException) {
        if (error.status == 404) emptyList() else throw error
    }

    private val streamClient by lazy {
        http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private fun parse(name: String?, data: String): Event? {
        val root = try {
            HermesJson.parseToJsonElement(data) as? JsonObject ?: return null
        } catch (error: Exception) {
            return null
        }
        val runId = root.string("run_id").orEmpty()
        return when (name) {
            "run.started" -> Event.Started(runId)
            "assistant.delta" -> Event.Delta(root.string("delta").orEmpty()).takeIf { it.text.isNotEmpty() }
            "assistant.commentary" -> Event.Commentary(root.string("text").orEmpty()).takeIf { it.text.isNotBlank() }
            "tool.progress", "tool.started", "tool.completed", "tool.failed" ->
                Event.Working(root.string("tool_name"))
            "approval.request" -> Event.Approval(root.string("description") ?: root.string("command").orEmpty(), runId)
            "assistant.completed" -> Event.Completed(root.string("content").orEmpty(), runId)
            "run.completed" -> Event.Completed(root.string("content").orEmpty(), runId)
            "run.cancelled", "run.failed" -> Event.Failed(root.string("error") ?: name.orEmpty())
            "error" -> Event.Failed(root.string("message").orEmpty())
            else -> null
        }
    }

    companion object {
        /** The source the API server stamps on every session it creates. */
        const val SOURCE = "api_server"
    }
}

/** The object shape of a JSON answer, or an empty object when the server sent something else. */
private fun objectOf(element: JsonElement): JsonObject = element as? JsonObject ?: JsonObject(emptyMap())
