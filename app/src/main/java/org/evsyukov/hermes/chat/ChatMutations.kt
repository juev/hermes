package org.evsyukov.hermes.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.string

/**
 * Renames, archives and deletes chats. The REST routes hand the change to the gateway process,
 * which answers `not_found` for a session that lives in the dashboard process, as the chats of
 * this app do on a plain `hermes dashboard`. Such a change goes through the chat socket instead,
 * where the session lives; [liveOf] gives the live id of a chat the app has open.
 */
class ChatMutations(
    private val chat: ChatApi,
    private val socket: GatewaySocket,
    private val liveOf: (String) -> String?,
) {
    suspend fun delete(id: String) = viaSocketWhenMissing({ chat.delete(id) }) {
        liveOf(id)?.let { live -> socket.call("session.close", buildJsonObject { put("session_id", live) }) }
        socket.call("session.delete", buildJsonObject { put("session_id", id) })
    }

    suspend fun archive(id: String, archived: Boolean) = viaSocketWhenMissing({ chat.mutate(id, archived = archived) }) {
        socket.call("session.archive", buildJsonObject { put("session_key", id); put("archived", archived) })
    }

    suspend fun rename(id: String, title: String) = viaSocketWhenMissing({ chat.mutate(id, title = title) }) {
        val open = liveOf(id)
        val live = open ?: (
            socket.call(
                "session.resume",
                buildJsonObject {
                    put("session_id", id)
                    put("lazy", true)
                    put("omit_messages", true)
                },
            ) as JsonObject
            ).string("session_id") ?: error("No session_id in the answer")
        try {
            socket.call("session.title", buildJsonObject { put("session_id", live); put("title", title) })
        } finally {
            // A session joined only for the rename is let go again.
            if (open == null) socket.call("session.close", buildJsonObject { put("session_id", live) })
        }
    }

    suspend fun pin(id: String, pinned: Boolean) = chat.mutate(id, pinned = pinned)

    private suspend fun viaSocketWhenMissing(rest: suspend () -> Unit, rpc: suspend () -> Unit) {
        try {
            rest()
        } catch (e: HermesException) {
            if (e.status != 404) throw e
            rpc()
        }
    }
}
