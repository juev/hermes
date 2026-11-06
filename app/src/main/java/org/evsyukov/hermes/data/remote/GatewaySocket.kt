package org.evsyukov.hermes.data.remote

import kotlinx.coroutines.ensureActive

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.server.WorkOwner
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** An event of the gateway: `params` of a JSON-RPC notification with method `event`. */
/**
 * An event of a session. [transport] is not on the wire: it is the connection, by
 * [GatewayChannel.transport], the frame came over, 0 for one the app made itself.
 */
data class GatewayEvent(val type: String, val sessionId: String?, val seq: Long?, val payload: JsonObject, val transport: Long = 0L, val owner: WorkOwner? = null)

/**
 * A question the server asks the client (approval, clarify); answered with [GatewaySocket.respond].
 * [transport] is not on the wire: it is the connection the frame came over, or the one of the join
 * whose answer listed the request; 0 when neither is known.
 */
data class ServerRequest(val id: String, val method: String, val params: JsonObject, val transport: Long = 0L)

class RpcException(val code: Int, message: String, val data: JsonElement?) : IOException(message)

enum class SocketState { DISCONNECTED, CONNECTING, READY }

/**
 * The chat channel of the dashboard: JSON-RPC 2.0 over `/api/ws`, one JSON object per text frame.
 * It connects on demand, pings while open and reconnects with a growing pause after a drop; the
 * owner resumes its sessions on [ready].
 */
class GatewaySocket(
    private val http: HermesHttp,
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    private val firstPause: Long = 1_000L,
    private val probeMillis: Long = 5_000L,
) : GatewayChannel {
    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    override val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    override val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)
    override val requests: SharedFlow<ServerRequest> = _requests.asSharedFlow()

    /** Emits after every (re)connection once the gateway said it is ready; carries `replay_epoch`. */
    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)
    override val ready: SharedFlow<String?> = _ready.asSharedFlow()

    private val _connections = MutableStateFlow(0)
    override val connections: StateFlow<Int> = _connections.asStateFlow()

    /** The number of the attempt the socket is READY on, 0 while it is on none; changed under the lock with [state]. */
    private val _transport = MutableStateFlow(0L)
    override val transport: StateFlow<Long> = _transport.asStateFlow()

    /** The websocket of [transport], for an answer that must go over that connection alone. */
    @Volatile
    private var readyOn: WebSocket? = null

    @Volatile
    var sessionAuthority: Boolean = false
        private set

    private val pending = PendingCalls()
    private val ids = AtomicLong()
    @Volatile
    private var socket: WebSocket? = null
    private var connector: Job? = null
    private var heartbeat: Job? = null
    private var probing: Job? = null

    @Volatile
    private var wanted = false

    /** Set by [stop] for good: the connection that replaces this one brings its own socket. */
    @Volatile
    private var stopped = false

    /**
     * The number of the attempt under way; the end of an attempt and [stop] move it on too. A callback
     * of an earlier attempt, e.g. a ready handled after the stop, finds another number and changes
     * nothing.
     */
    private var attempt = 0L

    /**
     * Completes when the coming connection is ready, or fails with the reason it did not open. The
     * next gate replaces it before the state leaves [SocketState.READY], so a caller never waits on
     * the gate of a connection that is already over.
     */
    @Volatile
    private var readyGate = CompletableDeferred<Unit>()

    /** Cuts the pause between attempts short. */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastFrameAt = 0L

    /** Frames received so far; a probe counts what came after its ping. */
    private val frames = AtomicLong()

    /** For the tests: the heartbeat of a connection runs. */
    internal val heartbeatRunning: Boolean get() = heartbeat?.isActive == true

    /** The gateway announced its heartbeat, so it answers `gateway.ping`. */
    @Volatile
    private var pings = false

    @Volatile private var connectorOwner: WorkOwner? = null

    fun ownerOn(transport: Long): WorkOwner? = synchronized(this) {
        connectorOwner.takeIf { transport != 0L && _transport.value == transport }
    }

    /**
     * Keeps the socket open until [stop]; safe to call many times, and does nothing after [stop]. A
     * socket whose sign-in is over stops trying until it is started again.
     */
    fun start() = start(http.owner())

    fun start(owner: WorkOwner?) {
        http.publishWork(owner) {
            synchronized(this) {
                if (stopped) return@synchronized
                if (connector?.isActive == true && connectorOwner == owner) { wanted = true; return@synchronized }
                if (connectorOwner != owner) {
                    attempt++
                    connector?.cancel()
                    heartbeat?.cancel()
                    probing?.cancel()
                    socket?.cancel()
                    socket = null
                    lose()
                    _state.value = SocketState.DISCONNECTED
                    val error = IOException("Socket authorization replaced")
                    pending.close().forEach { it.completeExceptionally(error) }
                    readyGate.completeExceptionally(error)
                }
                connectorOwner = owner
                wanted = true
                readyGate = CompletableDeferred()
                connector = scope.launch { connectLoop(owner) }
            }
        }
    }

    /**
     * Closes the socket for good. The connection is rebuilt with a new socket, and an owner that
     * still holds this one, e.g. a collector that read the connection just before, must not open it
     * again.
     */
    fun stop() {
        val closed = IOException(strings.get(R.string.error_connection_closed))
        val lost = synchronized(this) {
            stopped = true
            wanted = false
            attempt++
            connector?.cancel()
            heartbeat?.cancel()
            probing?.cancel()
            socket?.close(1000, "bye")
            socket = null
            // The callers waiting for the attempt under way fail now, also when no close ever reaches its
            // listener; a new, failed gate serves the callers to come, since the gate of a connection that
            // was ready has completed already and a caller would loop on it without ever suspending.
            readyGate.completeExceptionally(closed)
            readyGate = CompletableDeferred<Unit>().apply { completeExceptionally(closed) }
            lose()
            _state.value = SocketState.DISCONNECTED
            pending.close()
        }
        lost.forEach { it.completeExceptionally(closed) }
    }

    /**
     * Makes sure a started socket works, e.g. when the app comes to the front or the network
     * changed: one waiting out its pause tries at once, an open one is pinged and replaced only when
     * nothing comes back. An open socket that answers keeps its calls in flight. A gateway without
     * the heartbeat is not pinged; OkHttp's own pings find its dead socket.
     */
    fun checkAlive() {
        if (!wanted) return
        when (_state.value) {
            SocketState.DISCONNECTED -> wakeup.trySend(Unit)
            SocketState.CONNECTING -> Unit
            SocketState.READY -> if (pings) probe()
        }
    }

    @Synchronized
    private fun probe() {
        if (probing?.isActive == true) return
        val probed = socket ?: return
        probing = scope.launch {
            val sentAt = System.currentTimeMillis()
            val seen = frames.get()
            val alive = try {
                request(probed, "gateway.ping", timeoutMillis = probeMillis)
                true
            } catch (e: RpcException) {
                // An error is an answer too.
                true
            } catch (e: TimeoutCancellationException) {
                frames.get() > seen
            } catch (e: IOException) {
                // The socket dropped meanwhile.
                false
            }
            if (!alive) {
                Log.i(TAG, "Gateway socket dead after ${System.currentTimeMillis() - sentAt} ms of checking, reconnecting")
                if (socket === probed) probed.cancel()
                // The loop tries again at once instead of after its pause.
                wakeup.trySend(Unit)
            }
        }
    }

    private suspend fun connectLoop(owner: WorkOwner?) {
        var pause = firstPause
        while (true) {
            if (!http.holds(owner)) return
            val (number, gate) = begin() ?: return
            val closed = CompletableDeferred<Unit>()
            val attemptAt = System.currentTimeMillis()
            try {
                val request = Request.Builder().url(socketUrl(owner)).build()
                val ticketMillis = System.currentTimeMillis() - attemptAt
                if (http.publishWork(owner) { open(number, request, Listener(number, closed, gate)) } != true) return
                withTimeout(20_000) { gate.await() }
                Log.i(TAG, "Gateway ready in ${System.currentTimeMillis() - attemptAt} ms, $ticketMillis ms of them for the ticket")
                pause = firstPause
                closed.await()
            } catch (e: Exception) {
                // A stop cancels the loop; the timeout of an attempt is a failure like any other.
                if (e is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) throw e
                Log.w(TAG, "Gateway socket failed after ${System.currentTimeMillis() - attemptAt} ms: ${e.message}")
                // A ticket that could not be fetched fails the waiting callers now, not after their timeout.
                gate.completeExceptionally(e as? IOException ?: IOException(strings.get(R.string.error_connection_failed), e))
                // Without a sign-in no attempt can succeed; the next start tries again.
                if (e is SignInRequiredException || (e as? HermesException)?.status == 401) giveUp(number)
            }
            if (!finish(number, gate)) return
            withTimeoutOrNull(pause) { wakeup.receive() }
            pause = (pause * 2).coerceAtMost(30_000L)
        }
    }

    /** Numbers the next attempt and hands out its gate; null once the socket is no longer wanted. */
    @Synchronized
    private fun begin(): Pair<Long, CompletableDeferred<Unit>>? {
        if (!wanted) return null
        attempt++
        lose()
        _state.value = SocketState.CONNECTING
        return attempt to readyGate
    }

    /** Makes attempt [number] the last one, unless a start came in between. */
    @Synchronized
    private fun giveUp(number: Long) {
        if (attempt == number) wanted = false
    }

    /** Opens the socket of attempt [number], unless a stop came in between. */
    @Synchronized
    private fun open(number: Long, request: Request, listener: Listener): Boolean {
        if (attempt != number) return false
        socket = http.client.newWebSocket(request, listener)
        return true
    }

    /**
     * Clears up after attempt [number] and moves the number on, so the coroutines the attempt left
     * behind, e.g. one still waiting for `client.capabilities`, announce no ready and start no
     * heartbeat. False when a stop has cleared up already or the socket gave up.
     */
    private fun finish(number: Long, gate: CompletableDeferred<Unit>): Boolean {
        val (more, lost) = synchronized(this) {
            if (attempt != number) return false
            attempt++
            socket?.cancel()
            heartbeat?.cancel()
            // A socket that gave up keeps the failed gate of its last attempt until the next start, so a
            // caller that looks only now, e.g. one whose start ran the whole attempt, fails with the
            // reason at once instead of waiting for a connection nobody opens.
            readyGate = if (wanted) CompletableDeferred() else gate
            lose()
            _state.value = SocketState.DISCONNECTED
            // The loop ends here, so the next start must begin another one.
            if (!wanted) connector = null
            wanted to pending.close()
        }
        // Outside the lock: a caller resumed by its failure may call the socket again.
        val error = IOException(strings.get(R.string.error_connection_lost))
        lost.forEach { it.completeExceptionally(error) }
        return more
    }

    /** True while attempt [number] is the one under way. */
    @Synchronized
    private fun current(number: Long): Boolean = attempt == number

    /** A ticket lives 30 seconds and opens one socket; a server without sign-in takes no ticket. */
    private suspend fun socketUrl(owner: WorkOwner?): String {
        val url = http.endpoint("api/ws")
        val token = if (owner == null) http.accessToken() else http.accessToken(owner)
        if (token != null) {
            val ticket = (if (owner == null) http.postJson("api/auth/ws-ticket") else http.postJson(owner, "api/auth/ws-ticket")).jsonObject.string("ticket")
            url.addQueryParameter("ticket", ticket)
        }
        val built = url.build().toString()
        return when {
            built.startsWith("https://") -> "wss://" + built.removePrefix("https://")
            built.startsWith("http://") -> "ws://" + built.removePrefix("http://")
            else -> built
        }
    }

    /** Calls [method] and waits for its result; errors come back as [RpcException]. */
    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        val origin = http.owner()
        awaitReady(origin)
        val open = readyOn ?: throw IOException(strings.get(R.string.error_no_connection))
        return request(open, method, params, timeoutMillis, origin, _transport.value)
    }

    override suspend fun callOn(owner: WorkOwner, transport: Long, method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        val via = synchronized(this) { readyOn.takeIf { transport != 0L && _transport.value == transport && connectorOwner == owner } }
            ?: throw IOException("Retired socket transport")
        return request(via, method, params, timeoutMillis, owner, transport)
    }

    /**
     * Sends [method] over [via] alone. The socket's own calls go this way: they belong to one
     * connection and must neither wait for nor open another.
     */
    @Volatile internal var beforeTerminalCompletion: ((JsonObject) -> Unit)? = null
    @Volatile internal var beforeRequestAwait: (suspend (String) -> Unit)? = null
    @Volatile internal var onRequestEnqueued: ((String) -> Unit)? = null

    private suspend fun request(via: WebSocket, method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long, owner: WorkOwner? = connectorOwner, transport: Long? = null): JsonElement {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val id = "a${ids.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement>()
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val sent = http.publishWork(owner) {
            synchronized(this) {
                if (readyOn !== via || (transport != null && _transport.value != transport) || !pending.add(via, id, deferred)) false
                else via.send(HermesJson.encodeToString(JsonElement.serializer(), frame)).also { if (it) onRequestEnqueued?.invoke(method) }
            }
        } == true
        if (!sent) {
            pending.take(id)
            throw IOException(strings.get(R.string.error_no_connection))
        }
        return try {
            beforeRequestAwait?.invoke(method)
            val result = withTimeout(timeoutMillis) { deferred.await() }
            if (!http.holds(owner) || (transport != null && _transport.value != transport)) throw IOException("Retired socket origin")
            result
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!http.holds(owner) || (transport != null && _transport.value != transport)) throw IOException("Retired socket origin", error)
            throw error
        } finally {
            pending.take(id)
        }
    }

    /** Answers a [ServerRequest]; [error] rejects it instead. */
    override fun respond(id: String, result: JsonElement?, error: String?) {
        socket?.send(answerFrame(id, result, error))
    }

    /** Sends the answer over the websocket of [transport] itself, so it never goes over a connection that replaced it. */
    override fun respondOn(transport: Long, id: String, result: JsonElement?): Boolean {
        val via = synchronized(this) { readyOn.takeIf { transport != 0L && _transport.value == transport } } ?: return false
        return via.send(answerFrame(id, result, null))
    }

    override fun respondOwned(owner: WorkOwner, transport: Long, id: String, result: JsonElement?, error: String?): Boolean =
        http.publishWork(owner) {
            synchronized(this) {
                readyOn?.takeIf { connectorOwner == owner && transport != 0L && _transport.value == transport }
                    ?.send(answerFrame(id, result, error)) ?: false
            }
        } == true

    private fun answerFrame(id: String, result: JsonElement?, error: String?): String {
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (error != null) {
                put("error", buildJsonObject { put("code", -32000); put("message", error) })
            } else {
                put("result", result ?: JsonObject(emptyMap()))
            }
        }
        return HermesJson.encodeToString(JsonElement.serializer(), frame)
    }

    /** Waits until the socket is ready, opening it if needed; a stopped socket fails at once. */
    override suspend fun awaitReady() = awaitReady(http.owner())

    suspend fun awaitReady(owner: WorkOwner?) {
        if (!http.holds(owner)) throw IOException("Retired socket origin")
        start(owner)
        withTimeout(20_000) {
            while (true) {
                if (!http.holds(owner)) throw IOException("Retired socket origin")
                val gate = readyGate
                if (_state.value == SocketState.READY && connectorOwner == owner) return@withTimeout
                gate.await()
            }
        }
    }

    /** For the tests: [text] as the listener of attempt [number] hands it over, on the socket open now. */
    @VisibleForTesting
    internal fun receive(text: String, number: Long) {
        val via = socket ?: return
        handle(text, via, number, readyGate)
    }

    private fun handle(text: String, via: WebSocket, number: Long, gate: CompletableDeferred<Unit>) {
        if (!current(number)) return
        // A terminal reply must finish its original waiter even after retirement. It publishes no
        // event/ready state; request() rechecks authority before returning the result to its caller.
        val reply = runCatching { HermesJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val id = reply?.get("id")?.str()
        if (reply != null && reply.string("method") == null && id != null) {
            val deferred = pending.take(id) ?: return
            beforeTerminalCompletion?.invoke(reply)
            val error = reply["error"] as? JsonObject
            if (error != null) deferred.completeExceptionally(RpcException(error.long("code")?.toInt() ?: -1, error.string("message") ?: strings.get(R.string.error_server), error["data"]))
            else deferred.complete(reply["result"] ?: JsonNull)
            return
        }
        val origin = connectorOwner
        http.publishWork(origin) { handleAdmitted(text, via, number, gate, origin) }
    }

    private fun handleAdmitted(text: String, via: WebSocket, number: Long, gate: CompletableDeferred<Unit>, owner: WorkOwner?) {
        // A frame of an attempt that is over, handed over late by its listener, belongs to no connection
        // the socket has: it neither tells the app anything nor counts as a sign of life.
        if (!current(number)) return
        lastFrameAt = System.currentTimeMillis()
        frames.incrementAndGet()
        val frame = try {
            HermesJson.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return
        val id = frame["id"]?.str()
        val method = frame.string("method")
        when {
            method == "event" -> {
                val params = frame["params"] as? JsonObject ?: return
                val event = GatewayEvent(
                    type = params.string("type").orEmpty(),
                    sessionId = params.string("session_id"),
                    seq = params.long("seq"),
                    payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                    transport = number,
                    owner = owner,
                )
                if (event.type == "gateway.ready") {
                    onReady(event.payload, via, number, gate)
                } else {
                    _events.tryEmit(event)
                }
            }
            method != null && id != null -> _requests.tryEmit(ServerRequest(id, method, frame["params"] as? JsonObject ?: JsonObject(emptyMap()), transport = number))
            id != null -> {
                val deferred = pending.take(id) ?: return
                val error = frame["error"] as? JsonObject
                if (error != null) {
                    deferred.completeExceptionally(
                        RpcException(error.long("code")?.toInt() ?: -1, error.string("message") ?: strings.get(R.string.error_server), error["data"]),
                    )
                } else {
                    deferred.complete(frame["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun onReady(payload: JsonObject, via: WebSocket, number: Long, gate: CompletableDeferred<Unit>) {
        val epoch = payload.string("replay_epoch")
        val origin = connectorOwner
        scope.launch {
            if (http.publishWork(origin) { markReady(number, via, gate, payload) } != true) return@launch
            // Without this the server fails approvals of this client at once instead of asking.
            try {
                request(via, "client.capabilities", buildJsonObject { put("server_requests", true) }, timeoutMillis = 15_000)
            } catch (e: Exception) {
                Log.w(TAG, "client.capabilities failed: ${e.message}")
            }
            if (pings) startHeartbeat(number, via)
            val published = http.publishWork(origin) {
                if (current(number)) { _connections.update { it + 1 }; true } else false
            } == true
            if (published) _ready.emit(epoch)
        }
    }

    /**
     * Turns the state READY for attempt [number]. An attempt that is over, given up after its timeout
     * or stopped, stays over; the check and the change happen under the lock [stop] takes.
     */
    @Synchronized
    private fun markReady(number: Long, via: WebSocket, gate: CompletableDeferred<Unit>, payload: JsonObject): Boolean {
        if (attempt != number || gate.isCompleted) return false
        sessionAuthority = payload.bool("session_authority") ?: false
        pings = payload.bool("heartbeat") == true
        pending.open(via)
        // The connection is the socket's from READY on, before the gateway is counted in [connections].
        readyOn = via
        _transport.value = number
        _state.value = SocketState.READY
        gate.complete(Unit)
        return true
    }

    /** The connection the socket was READY on is no longer its own; called under the lock. */
    private fun lose() {
        readyOn = null
        _transport.value = 0
    }

    @Synchronized
    private fun startHeartbeat(number: Long, via: WebSocket) {
        if (attempt != number) return
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(15_000)
                try {
                    request(via, "gateway.ping", timeoutMillis = 10_000)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) throw e
                    if (System.currentTimeMillis() - lastFrameAt > 45_000) {
                        Log.w(TAG, "Gateway heartbeat lost")
                        via.cancel()
                        return@launch
                    }
                }
            }
        }
    }

    private inner class Listener(
        private val number: Long,
        private val closed: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text, webSocket, number, gate)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == 4401) Log.w(TAG, "Gateway refused the sign-in")
            closed.complete(Unit)
            gate.completeExceptionally(IOException(strings.get(R.string.error_server_closed, code, reason)))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(Unit)
            gate.completeExceptionally(IOException(t.message ?: strings.get(R.string.error_connection_failed), t))
        }
    }

    private companion object {
        const val TAG = "HermesSocket"
    }
}

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.mapNotNull { (key, value) ->
        when (value) {
            null -> null
            is JsonElement -> key to value
            is String -> key to JsonPrimitive(value)
            is Number -> key to JsonPrimitive(value)
            is Boolean -> key to JsonPrimitive(value)
            else -> key to JsonPrimitive(value.toString())
        }
    }.toMap(),
)

/**
 * The calls in flight, each bound to the connection that sent it. Adding a call and closing the
 * connection take one lock, so a call either goes out on the live connection, to be failed when it
 * closes, or is refused at once; none is left waiting for an answer that cannot come.
 */
internal class PendingCalls {
    private val calls = HashMap<String, CompletableDeferred<JsonElement>>()
    private var live: Any? = null

    /** Lets [connection] send calls; a connection opened before may send no more. */
    @Synchronized
    fun open(connection: Any) {
        live = connection
    }

    /** Keeps a call going out over [connection]; false, and nothing kept, unless it is the live one. */
    @Synchronized
    fun add(connection: Any, id: String, deferred: CompletableDeferred<JsonElement>): Boolean {
        if (connection !== live) return false
        calls[id] = deferred
        return true
    }

    @Synchronized
    fun take(id: String): CompletableDeferred<JsonElement>? = calls.remove(id)

    /** Ends the live connection and hands out its calls, for the caller to fail outside the lock. */
    @Synchronized
    fun close(): List<CompletableDeferred<JsonElement>> {
        live = null
        return calls.values.toList().also { calls.clear() }
    }
}
