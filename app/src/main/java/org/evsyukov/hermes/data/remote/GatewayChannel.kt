package org.evsyukov.hermes.data.remote

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What a chat asks of the gateway: the state of the connection, the events and questions it brings,
 * a ready announced after every (re)connection, and the calls the chat makes.
 *
 * [GatewaySocket] is the only implementation in the app. A chat test speaks to a fake instead, so
 * what it checks — the state of a card, the session a chat joins — does not depend on the timing of a
 * websocket that drops and comes back.
 */
interface GatewayChannel {
    val state: StateFlow<SocketState>

    /** Events of the sessions; nothing is kept for a collector that comes late. */
    val events: SharedFlow<GatewayEvent>

    /** Questions the server asks the client (approvals, clarify), answered with [respond]. */
    val requests: SharedFlow<ServerRequest>

    /** Emits after every (re)connection once the gateway said it is ready; carries `replay_epoch`. */
    val ready: SharedFlow<String?>

    /** Calls [method] and waits for its result; errors come back as [RpcException]. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 60_000): JsonElement

    /** Waits until the socket is ready, opening it if needed; a stopped socket fails at once. */
    suspend fun awaitReady()

    /** Answers a [ServerRequest]; [error] rejects it instead. */
    fun respond(id: String, result: JsonElement?, error: String? = null)
}
