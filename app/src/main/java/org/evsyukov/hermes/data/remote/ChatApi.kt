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
) {
    /** The title the sidebar shows: the server's, else the start of the first message. */
    val label: String get() = title.ifBlank { preview.ifBlank { "Новый чат" } }
}

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
class ChatApi(private val http: HermesHttp) {
    suspend fun list(offset: Int = 0, limit: Int = 50, archived: Boolean = false): ChatPage {
        val root = http.getJson(
            "api/sessions",
            mapOf(
                "limit" to limit,
                "offset" to offset,
                "order" to "recent",
                "archived" to if (archived) "only" else "exclude",
                "min_messages" to 1,
            ),
        ).jsonObject
        val chats = (root["sessions"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::summary) }.orEmpty()
        return ChatPage(chats, root.long("total")?.toInt() ?: chats.size)
    }

    suspend fun get(id: String): ChatSummary? = (http.getJson("api/sessions/$id") as? JsonObject)?.let(::summary)

    suspend fun search(query: String, limit: Int = 50): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val root = http.getJson("api/sessions/search", mapOf("q" to query, "limit" to limit)).jsonObject
        return (root["results"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val chat = summary(obj, idKey = "session_id") ?: return@mapNotNull null
            SearchHit(chat, obj.string("snippet").orEmpty(), obj.string("role"))
        }.orEmpty()
    }

    suspend fun messages(id: String, limit: Int = 500): List<StoredMessage> {
        val root = http.getJson("api/sessions/$id/messages", mapOf("limit" to limit, "order" to "oldest", "inline_images" to false)).jsonObject
        return (root["messages"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::message) }.orEmpty()
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

    /** A URL that downloads a file the agent wrote or got; the token rides in the query string. */
    suspend fun downloadUrl(path: String): String {
        val token = http.accessToken()
        val url = http.endpoint("api/files/download").addQueryParameter("path", path)
        if (token != null) url.addQueryParameter("token", token)
        return url.build().toString()
    }

    private data class Fence(val requestId: String, val revision: Long?, val generation: Long?)

    private suspend fun fence(id: String): Fence {
        val snapshot = http.getJson("api/sessions/$id/mutation-snapshot").jsonObject
        return Fence(UUID.randomUUID().toString(), snapshot.long("runtime_revision"), snapshot.long("runtime_generation"))
    }

    companion object {
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
            )
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
            return StoredMessage(
                rowId = obj.long("id") ?: 0,
                role = obj.string("role") ?: "assistant",
                text = obj.string("display_content") ?: text,
                reasoning = obj.string("reasoning") ?: obj.string("reasoning_content"),
                toolCalls = calls,
                toolName = obj.string("tool_name"),
                toolCallId = obj.string("tool_call_id"),
                timestamp = obj.double("timestamp") ?: 0.0,
                hidden = obj.string("display_kind") == "hidden" || obj.bool("_compressed_summary") == true,
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
