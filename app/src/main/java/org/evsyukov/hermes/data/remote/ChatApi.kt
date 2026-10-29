package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID

/** A chat as the list in the sidebar shows it; [id] is the stored id the REST routes take. */
data class ChatSummary(
    val id: String,
    val title: String,
    val preview: String,
    val source: String,
    val model: String?,
    val startedAt: Double,
    val lastActive: Double,
    val messageCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val active: Boolean,
    /** What the chat has spent so far; null while it has spent nothing. */
    val usage: SessionUsage? = null,
) {
    /** The title the sidebar shows: the server's, else the start of the first message; blank for an empty chat. */
    val label: String get() = title.ifBlank { preview }
}

/**
 * Tokens and money a chat has spent, as the server totals them after each turn. [input] counts every
 * token the model read, cached ones too; [costUsd] is null when the server knows no price for the
 * model, and [included] says the provider bills by subscription, not per token.
 */
data class SessionUsage(val input: Long, val output: Long, val costUsd: Double?, val included: Boolean)

/** A hit of the full-text search with the matching piece of text. */
data class SearchHit(val chat: ChatSummary, val snippet: String, val role: String?)

/** A message of the history as `GET /api/sessions/{id}/messages` stores it. */
data class StoredMessage(
    val rowId: Long,
    val role: String,
    val text: String,
    val reasoning: String?,
    val toolCalls: List<ToolCallInfo>,
    val toolName: String?,
    val toolCallId: String?,
    val timestamp: Double,
    val hidden: Boolean,
    val images: List<String>,
)

data class ToolCallInfo(val id: String, val name: String, val arguments: String)

class ChatPage(val chats: List<ChatSummary>, val total: Int)

/** The chat routes of the dashboard that work over plain HTTP; the live chat goes through [GatewaySocket]. */
class ChatApi(private val http: HermesHttp, private val reads: Reads = NetworkReads(http, null, ""), cachedReads: Reads? = null) {
    /** The same reads answered from the phone's cache alone; null without a cache. Not for changes. */
    val cached: ChatApi? = cachedReads?.let { ChatApi(http, it) }

    /** A page of chats, newest activity first; [sources] keeps only those sources, [excludeSources] drops them. */
    suspend fun list(offset: Int = 0, limit: Int = 50, archived: Boolean = false, sources: String? = null, excludeSources: String? = null): ChatPage {
        val root = reads.kept(
            "api/sessions",
            mapOf(
                "limit" to limit,
                "offset" to offset,
                "order" to "recent",
                "archived" to if (archived) "only" else "exclude",
                "min_messages" to 1,
                "sources" to sources,
                "exclude_sources" to excludeSources,
            ),
        ).jsonObject
        val chats = (root["sessions"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::summary) }.orEmpty()
        return ChatPage(chats, root.long("total")?.toInt() ?: chats.size)
    }

    suspend fun get(id: String): ChatSummary? = (reads.kept("api/sessions/$id") as? JsonObject)?.let(::summary)

    suspend fun search(query: String, limit: Int = 50): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val root = reads.fresh("api/sessions/search", mapOf("q" to query, "limit" to limit)).jsonObject
        return (root["results"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val chat = summary(obj, idKey = "session_id") ?: return@mapNotNull null
            SearchHit(chat, obj.string("snippet").orEmpty(), obj.string("role"))
        }.orEmpty()
    }

    suspend fun messages(id: String, limit: Int = 500): List<StoredMessage> {
        val root = reads.kept("api/sessions/$id/messages", mapOf("limit" to limit, "order" to "oldest", "inline_images" to false)).jsonObject
        return (root["messages"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::message) }.orEmpty()
    }

    /** The text of the newest answer of the chat, for a notification. */
    suspend fun lastReply(id: String): String? {
        val root = reads.fresh("api/sessions/$id/messages", mapOf("limit" to 6, "order" to "latest", "inline_images" to false)).jsonObject
        return (root["messages"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::message) }
            ?.filter { it.role == "assistant" && it.text.isNotBlank() && !it.hidden }
            ?.maxByOrNull { it.timestamp }
            ?.text
    }

    /** Renames, pins or archives; the server takes such a change only with the revision it was made on. */
    suspend fun mutate(id: String, title: String? = null, pinned: Boolean? = null, archived: Boolean? = null) {
        val fence = fence(id)
        http.patchJson(
            "api/sessions/$id",
            buildJsonObject {
                put("request_id", fence.requestId)
                fence.revision?.let { put("expected_revision", it) }
                fence.generation?.let { put("expected_generation", it) }
                title?.let { put("title", it) }
                pinned?.let { put("pinned", it) }
                archived?.let { put("archived", it) }
            },
        )
    }

    suspend fun delete(id: String) {
        val fence = fence(id)
        val query = buildString {
            append("api/sessions/$id?request_id=${fence.requestId}")
            fence.revision?.let { append("&expected_revision=$it") }
            fence.generation?.let { append("&expected_generation=$it") }
        }
        http.delete(query)
    }

    /**
     * Streams a file to [target], an absolute path on the server (`/api/files/upload-stream`);
     * returns the path the server wrote, for the prompt to point the agent at.
     */
    suspend fun upload(file: File, name: String, mime: String?, target: String): UploadedFile {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("path", target)
            .addFormDataPart("overwrite", "true")
            .addFormDataPart("file", name, file.asRequestBody(mime?.toMediaTypeOrNull()))
            .build()
        val answer = http.json { url(http.endpoint("api/files/upload-stream").build()).post(body) }.jsonObject
        return UploadedFile(path = answer.string("path") ?: target, name = name, mime = mime)
    }

    /** Speech to text by the provider configured on the server; silence gives an empty string. */
    suspend fun transcribe(audio: ByteArray, mime: String): String {
        val dataUrl = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(audio)
        val answer = http.postJson("api/audio/transcribe", buildJsonObject { put("data_url", dataUrl); put("mime_type", mime) }).jsonObject
        return answer.string("transcript").orEmpty()
    }

    /** A URL that downloads a file the agent wrote or got; the token rides in the query string. */
    suspend fun downloadUrl(path: String): String {
        val token = http.accessToken()
        val url = http.endpoint("api/files/download").addQueryParameter("path", path)
        if (token != null) url.addQueryParameter("token", token)
        return url.build().toString()
    }

    private data class Fence(val requestId: String, val revision: Long?, val generation: Long?)

    private suspend fun fence(id: String): Fence {
        val snapshot = reads.fresh("api/sessions/$id/mutation-snapshot").jsonObject
        return Fence(UUID.randomUUID().toString(), snapshot.long("runtime_revision"), snapshot.long("runtime_generation"))
    }

    companion object {
        /** A message the user sent while the agent worked; `display_content` holds their words. */
        private const val STEER = "steer"

        fun summary(obj: JsonObject, idKey: String = "id"): ChatSummary? {
            val id = obj.string(idKey) ?: obj.string("id") ?: return null
            val started = obj.double("started_at") ?: obj.double("session_started") ?: 0.0
            return ChatSummary(
                id = id,
                title = obj.string("title").orEmpty(),
                preview = obj.string("preview").orEmpty(),
                source = obj.string("source").orEmpty(),
                model = obj.string("model"),
                startedAt = started,
                lastActive = obj.double("last_active") ?: obj.double("last_activity_at") ?: started,
                messageCount = obj.long("message_count")?.toInt() ?: 0,
                pinned = obj.flag("pinned"),
                archived = obj.flag("archived"),
                active = obj.flag("is_active"),
                usage = usage(obj),
            )
        }

        fun usage(obj: JsonObject): SessionUsage? {
            val input = (obj.long("input_tokens") ?: 0) + (obj.long("cache_read_tokens") ?: 0) + (obj.long("cache_write_tokens") ?: 0)
            val output = obj.long("output_tokens") ?: 0
            if (input == 0L && output == 0L) return null
            // The billed amount when the provider reported one, else the server's estimate by its price list.
            val cost = obj.double("actual_cost_usd")?.takeIf { it > 0 } ?: obj.double("estimated_cost_usd")?.takeIf { it > 0 }
            return SessionUsage(input, output, cost, included = cost == null && obj.string("cost_status") == "included")
        }

        fun message(obj: JsonObject): StoredMessage {
            val content = obj["content"]
            val images = mutableListOf<String>()
            val text = when (content) {
                is JsonPrimitive -> content.content
                is JsonArray -> content.mapNotNull { part ->
                    val p = part as? JsonObject ?: return@mapNotNull null
                    when (p.string("type")) {
                        "text", "input_text" -> p.string("text")
                        "image_url" -> {
                            (p["image_url"] as? JsonObject)?.string("url")?.let(images::add)
                            null
                        }
                        else -> null
                    }
                }.joinToString("\n")
                else -> ""
            }
            val calls = (obj["tool_calls"] as? JsonArray)?.mapNotNull { call ->
                val c = call as? JsonObject ?: return@mapNotNull null
                val function = c["function"] as? JsonObject
                ToolCallInfo(
                    id = c.string("id").orEmpty(),
                    name = function?.string("name") ?: c.string("name").orEmpty(),
                    arguments = function?.get("arguments")?.let { if (it is JsonPrimitive) it.content else it.toString() }.orEmpty(),
                )
            }.orEmpty()
            val role = obj.string("role") ?: "assistant"
            val kind = obj.string("display_kind")
            // A user row of another kind is what the server told the model on its own: a finished
            // background process, results of subagents, a notice. The user did not write it.
            val toModel = role == "user" && kind != null && kind != STEER
            return StoredMessage(
                rowId = obj.long("id") ?: 0,
                role = role,
                text = obj.string("display_content") ?: text,
                reasoning = obj.string("reasoning") ?: obj.string("reasoning_content"),
                toolCalls = calls,
                toolName = obj.string("tool_name"),
                toolCallId = obj.string("tool_call_id"),
                timestamp = obj.double("timestamp") ?: 0.0,
                hidden = kind == "hidden" || toModel || obj.bool("_compressed_summary") == true,
                images = images,
            )
        }

        /** SQLite keeps flags as 0 and 1; the router turns some of them into booleans. */
        private fun JsonObject.flag(key: String): Boolean = when (val v = this[key]) {
            is JsonPrimitive -> v.content == "true" || v.content == "1"
            else -> false
        }
    }
}

data class UploadedFile(val path: String, val name: String, val mime: String?)

internal fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject
