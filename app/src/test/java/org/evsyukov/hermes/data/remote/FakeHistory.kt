package org.evsyukov.hermes.data.remote

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The dashboard's chat list and stored histories, served the way `GET /api/sessions/{id}/messages`
 * pages them: `order=latest` counts [offset] and [limit] back from the newest row and answers the page
 * oldest first (`hermes_state_messages.get_messages`).
 */
class FakeHistory : Dispatcher() {
    /** The rows of `GET /api/sessions`, as JSON objects. */
    val sessions = CopyOnWriteArrayList<String>()

    /** The stored rows of each chat, oldest first, as JSON objects. */
    val rows = ConcurrentHashMap<String, List<String>>()

    /** Chats whose history answers a server error. */
    val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The paths and queries of the history requests, in order. */
    val historyRequests = CopyOnWriteArrayList<String>()

    /** Chats whose history is held: [Hold.arrived] opens when the request comes, the answer waits for [Hold.release]. */
    val held = ConcurrentHashMap<String, Hold>()

    class Hold {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        if (path == "/api/sessions") {
            val page = sessions.take(url.queryParameter("limit")?.toInt() ?: 50)
            return json("""{"total": ${sessions.size}, "sessions": [${page.joinToString(",")}]}""")
        }
        val id = path.removePrefix("/api/sessions/").removeSuffix("/messages")
        if (!path.endsWith("/messages")) return MockResponse.Builder().code(404).build()
        historyRequests += "$path?${url.encodedQuery}"
        held[id]?.let { hold ->
            hold.arrived.countDown()
            hold.release.await(10, TimeUnit.SECONDS)
        }
        if (id in failing) return MockResponse.Builder().code(500).body("""{"detail": "boom"}""").build()
        val all = rows[id] ?: return MockResponse.Builder().code(404).build()
        val limit = url.queryParameter("limit")?.toInt() ?: 500
        val offset = url.queryParameter("offset")?.toInt() ?: 0
        val page = if (url.queryParameter("order") == "latest") all.reversed().drop(offset).take(limit).reversed() else all.drop(offset).take(limit)
        return json("""{"session_id": "$id", "messages": [${page.joinToString(",")}]}""")
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        fun session(id: String, lastActive: Double, preview: String = "a question", source: String = "telegram", extra: String = "") =
            """{"id": "$id", "title": "Chat $id", "preview": "$preview", "source": "$source", "message_count": 4, "started_at": 1.0, "last_active": $lastActive$extra}"""

        fun user(row: Long, at: Double, text: String) = """{"id": $row, "role": "user", "content": "$text", "timestamp": $at}"""

        fun answer(row: Long, at: Double, text: String) = """{"id": $row, "role": "assistant", "content": "$text", "timestamp": $at}"""

        fun reasoning(row: Long, at: Double) =
            """{"id": $row, "role": "assistant", "content": "", "reasoning": "thinking it over", "reasoning_content": "thinking it over", "timestamp": $at}"""

        fun call(row: Long, at: Double, callId: String) =
            """{"id": $row, "role": "assistant", "content": "", "timestamp": $at, "tool_calls": [{"id": "$callId", "type": "function", "function": {"name": "terminal", "arguments": "{}"}}]}"""

        fun result(row: Long, at: Double, callId: String) =
            """{"id": $row, "role": "tool", "content": "done", "tool_name": "terminal", "tool_call_id": "$callId", "timestamp": $at}"""
    }
}
