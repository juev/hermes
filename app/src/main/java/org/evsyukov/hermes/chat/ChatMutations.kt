package org.evsyukov.hermes.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.data.remote.ChatApi
import org.evsyukov.hermes.data.remote.GatewayChannel
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.data.remote.string

/**
 * Renames, pins, archives and deletes chats. The REST routes hand the change to the gateway process,
 * which answers `not_found` for a session that lives in the dashboard process, as the chats of
 * this app do on a plain `hermes dashboard`. Such a change goes through the chat socket instead,
 * where the session lives; [liveOf] gives the live id of a chat the app has open.
 */
class ChatMutations(
    private val chat: ChatApi,
    private val socket: GatewayChannel,
    private val liveOf: (String) -> String?,
    private val pinLocally: suspend (String, Boolean) -> Unit,
    private val owner: org.evsyukov.hermes.data.server.WorkOwner? = null,
    private val wanted: () -> Boolean = { true },
    private var transport: Long = socket.transport.value,
) {
    private fun admitted() = wanted() && (transport == 0L || socket.transport.value == transport)
    private suspend fun rpc(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement {
        if (!admitted()) throw java.io.IOException("Retired mutation origin")
        if (transport == 0L) {
            if (socket is org.evsyukov.hermes.data.remote.GatewaySocket) socket.awaitReady(owner) else socket.awaitReady()
            if (!wanted()) throw java.io.IOException("Retired mutation origin")
            transport = socket.transport.value
        }
        val result = if (owner == null) socket.call(method, params) else socket.callOn(owner, transport, method, params)
        if (!admitted()) throw java.io.IOException("Retired mutation origin")
        return result
    }

    suspend fun delete(id: String) {
        viaSocketWhenMissing({ chat.delete(id) }) {
            liveOf(id)?.let { live -> rpc("session.close", buildJsonObject { put("session_id", live) }) }
            rpc("session.delete", buildJsonObject { put("session_id", id) })
        }
        if (wanted()) pinLocally(id, false)
    }

    suspend fun archive(id: String, archived: Boolean) = viaSocketWhenMissing({ chat.mutate(id, archived = archived) }) {
        rpc("session.archive", buildJsonObject { put("session_key", id); put("archived", archived) })
    }

    suspend fun rename(id: String, title: String) = viaSocketWhenMissing({ chat.mutate(id, title = title) }) {
        val open = liveOf(id)
        val live = open ?: (
            rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", id)
                    put("lazy", true)
                    put("omit_messages", true)
                },
            ) as JsonObject
            ).string("session_id") ?: error("No session_id in the answer")
        var primary: Throwable? = null
        try {
            rpc("session.title", buildJsonObject { put("session_id", live); put("title", title) })
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            // Cleanup retains the title failure; cancellation grants no new send admission.
            if (open == null && admitted() && primary !is CancellationException) {
                try {
                    currentCoroutineContext().ensureActive()
                    rpc("session.close", buildJsonObject { put("session_id", live) })
                } catch (cleanup: Exception) {
                    val failure = primary
                    if (cleanup is CancellationException) {
                        if (failure != null && failure !== cleanup) cleanup.addSuppressed(failure)
                        throw cleanup
                    }
                    if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
    }

    /**
     * The server has no socket call for a pin, so a chat it answers `not_found` for is pinned on the
     * phone through [pinLocally]. An unpin clears a pin on the phone as well.
     */
    suspend fun pin(id: String, pinned: Boolean) {
        try {
            chat.mutate(id, pinned = pinned)
        } catch (e: HermesException) {
            if (e.status != 404) throw e
            if (wanted()) pinLocally(id, pinned)
            return
        }
        if (!pinned && wanted()) pinLocally(id, false)
    }

    private suspend fun viaSocketWhenMissing(rest: suspend () -> Unit, rpc: suspend () -> Unit) {
        try {
            rest()
        } catch (e: HermesException) {
            if (e.status != 404) throw e
            if (!admitted()) throw java.io.IOException("Retired mutation origin")
            rpc()
        }
    }
}
