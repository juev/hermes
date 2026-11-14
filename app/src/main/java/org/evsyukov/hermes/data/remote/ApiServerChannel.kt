package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The stock Hermes API server (`gateway.platforms.api_server`) as the phone's channel.
 *
 * Every call here is a documented platform endpoint — `/api/sessions`, `/v1/runs`,
 * `/v1/runs/{id}/events` — so the channel adds no server-side API of its own. Sessions listed with
 * `source=api_server` are this channel's conversations: the API server stamps that source on every
 * session it creates, which keeps Telegram, Matrix, desktop and cron conversations out of the list.
 *
 * A turn is a detached run: `POST /v1/runs` admits it and returns at once, and its events stream
 * from `GET /v1/runs/{id}/events`, which replays after a disconnect (`Last-Event-ID`). The
 * `chat/stream` wrapper is deliberately not used: it interrupts the run when the client goes away.
 * Questions and approvals are answered through the run control routes, whose bodies require the
 * exact `execution_generation` the server reports.
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
        data class Delta(val text: String) : Event
        data class Commentary(val text: String) : Event
        /** A tool or reasoning step; the client shows one working indicator, never the payload. */
        data class Working(val tool: String?) : Event
        /** A question or approval the run raised, with the generation its answer must carry. */
        data class Control(val prompt: Prompt, val runId: String) : Event
        /** A control the server settled itself; [requestId] names it when the server does. */
        data class Settled(val kind: String, val requestId: String?, val runId: String) : Event
        /** The readable answer of the turn; [text] is what the phone shows. */
        data class Completed(val text: String, val runId: String) : Event
        data class Failed(val message: String) : Event
        /** The replay lost its tail: history must be read again from the server. */
        data class Truncated(val oldestSeq: Long) : Event
        /** The stream is over; nothing new to show. */
        data object Finished : Event
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

    /** The stock PATCH carries the durable sidebar flags; archive is one of them. */
    suspend fun archive(id: String, archived: Boolean) {
        http.execute { url(http.endpoint("api/sessions/$id").build()); patch(buildJsonObject { put("archived", archived) }.toBody()) }.consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
        }
    }

    suspend fun delete(id: String) {
        http.delete("api/sessions/$id")
    }

    /** Readable transcript; [order] pages oldest-first or latest-first, hidden rows are filtered here, not stored away. */
    suspend fun history(id: String, limit: Int = 200, offset: Int = 0, order: String = "oldest"): Page {
        val root = objectOf(http.getJson("api/sessions/$id/messages", mapOf("limit" to limit, "offset" to offset, "order" to order)))
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
        // The stock envelope reports {limit, offset, order, returned}; a full page may have more.
        val returned = pagination?.long("returned")
        val pageLimit = pagination?.long("limit") ?: limit.toLong()
        val hasMore = returned != null && returned >= pageLimit
        return Page(rows, hasMore)
    }

    /** The newest stored row id, or null for an empty transcript: the boundary of the next turn. */
    suspend fun newestRowId(id: String): Long? {
        val root = objectOf(http.getJson("api/sessions/$id/messages", mapOf("order" to "latest", "limit" to 1)))
        return (root["data"] as? JsonArray).orEmpty().firstNotNullOfOrNull {
            (it as? JsonObject)?.string("id")?.toLongOrNull()
        }
    }

    /**
     * Admits one turn and returns its run id at once; the run outlives this request. The same
     * [idempotencyKey] with the same payload returns the same run, so a lost acceptance is never
     * a second turn.
     */
    suspend fun startRun(sessionId: String, message: String, idempotencyKey: String): String {
        val body = buildJsonObject { put("session_id", sessionId); put("input", message) }.toBody()
        return http.execute {
            url(http.endpoint("v1/runs").build())
            header("Idempotency-Key", idempotencyKey)
            post(body)
        }.consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
            objectOf(HermesJson.parseToJsonElement(response.body.string())).string("run_id")
                ?: error("The API server did not return a run id")
        }
    }

    /** The pollable state of a run: status, output and the controls it still holds. */
    suspend fun runStatus(runId: String): JsonObject = http.getJson("v1/runs/$runId").jsonObject

    /** A run the server no longer knows is null, not a failure: its turn is over or was forgotten. */
    suspend fun runStatusOrNull(runId: String): JsonObject? = try {
        runStatus(runId)
    } catch (error: HermesException) {
        if (error.status == 404) null else throw error
    }

    /**
     * Streams one run's events, replaying after [lastSeq] when it is given. Each event carries the
     * seq of its frame so a later reattach asks for exactly what it missed. Cancelling the coroutine
     * closes the request; the run itself goes on.
     */
    suspend fun runEvents(runId: String, lastSeq: Long? = null, onEvent: suspend (Event, Long) -> Unit) {
        val request = Request.Builder()
            .url(http.endpoint("v1/runs/$runId/events").build())
            .header("Accept", "text/event-stream")
            .apply {
                http.accessToken()?.let { header("Authorization", "Bearer $it") }
                if (lastSeq != null) header("Last-Event-ID", lastSeq.toString())
            }
            .get()
            .build()
        streamClient.newCall(request).await().consume { response ->
            if (!response.isSuccessful) throw errorOf(response)
            var name: String? = null
            var seq = -1L
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith(":") -> Unit
                    line.startsWith("id:") -> seq = line.removePrefix("id:").trim().toLongOrNull() ?: seq
                    line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val event = parse(name, line.removePrefix("data:").trim())
                        name = null
                        if (event != null) onEvent(event, seq)
                    }
                    line.isBlank() -> name = null
                }
            }
            onEvent(Event.Finished, seq)
        }
    }

    /** The run's pending questions and approvals, with the generation the answer must carry. */
    suspend fun pending(runId: String): List<Prompt> {
        val root = objectOf(http.getJson("v1/runs/$runId"))
        return (root["pending_controls"] as? JsonArray).orEmpty().mapNotNull { promptOf(it as? JsonObject) }
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

    private fun promptOf(obj: JsonObject?): Prompt? {
        if (obj == null) return null
        val kind = obj.string("kind") ?: return null
        if (kind != "clarify" && kind != "approval") return null
        return Prompt(
            kind = kind,
            promptId = obj.string("prompt_id") ?: return null,
            generation = obj.long("execution_generation") ?: return null,
            question = obj.string("question") ?: obj.string("description") ?: obj.string("command").orEmpty(),
            choices = (obj["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            multi = obj.bool("multi_select") ?: false,
        )
    }

    private fun parse(name: String?, data: String): Event? {
        val root = try {
            HermesJson.parseToJsonElement(data) as? JsonObject ?: return null
        } catch (error: Exception) {
            return null
        }
        val runId = root.string("run_id").orEmpty()
        return when (name) {
            "message.delta" -> Event.Delta(root.string("delta").orEmpty()).takeIf { it.text.isNotEmpty() }
            "message.interim" -> Event.Commentary(root.string("text").orEmpty()).takeIf { it.text.isNotBlank() }
            "tool.started", "tool.completed" -> Event.Working(root.string("tool"))
            // Reasoning crosses the wire as a step only: its text never becomes an event payload.
            "reasoning.available" -> Event.Working(null)
            "approval.request", "clarify.request" -> promptOf(root)?.let { Event.Control(it, runId) }
            "approval.settled", "clarify.settled" ->
                Event.Settled(name.removeSuffix(".settled"), root.string("request_id"), runId)
            "run.completed" -> Event.Completed(root.string("output").orEmpty(), runId)
            "run.cancelled" -> Event.Failed(root.string("error") ?: "cancelled")
            "run.failed", "run.interrupted" -> Event.Failed(root.string("error") ?: name.orEmpty())
            "replay.truncated" -> Event.Truncated(root.long("oldest_retained_seq") ?: -1L)
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