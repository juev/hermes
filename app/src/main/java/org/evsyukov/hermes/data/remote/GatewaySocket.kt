package org.evsyukov.hermes.data.remote

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An event of the gateway: `params` of a JSON-RPC notification with method `event`. */
data class GatewayEvent(val type: String, val sessionId: String?, val seq: Long?, val payload: JsonObject)

/** A question the server asks the client (approval, clarify); answered with [GatewaySocket.respond]. */
data class ServerRequest(val id: String, val method: String, val params: JsonObject)

class RpcException(val code: Int, message: String, val data: JsonElement?) : IOException(message)

enum class SocketState { DISCONNECTED, CONNECTING, READY }

/**
 * The chat channel of the dashboard: JSON-RPC 2.0 over `/api/ws`, one JSON object per text frame.
 * It connects on demand, pings while open and reconnects with a growing pause after a drop; the
 * owner resumes its sessions on [ready].
 */
class GatewaySocket(private val http: HermesHttp, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)
    val requests: SharedFlow<ServerRequest> = _requests.asSharedFlow()

    /** Emits after every (re)connection once the gateway said it is ready; carries `replay_epoch`. */
    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)
    val ready: SharedFlow<String?> = _ready.asSharedFlow()

    @Volatile
    var sessionAuthority: Boolean = false
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val ids = AtomicLong()
    private var socket: WebSocket? = null
    private var connector: Job? = null
    private var heartbeat: Job? = null
    private var wanted = false
    private var readyGate = CompletableDeferred<Unit>()

    @Volatile
    private var lastFrameAt = 0L

    /** Keeps the socket open until [stop]; safe to call many times. */
    @Synchronized
    fun start() {
        wanted = true
        if (connector?.isActive == true) return
        connector = scope.launch { connectLoop() }
    }

    @Synchronized
    fun stop() {
        wanted = false
        connector?.cancel()
        heartbeat?.cancel()
        socket?.close(1000, "bye")
        socket = null
        failPending(IOException("Соединение закрыто"))
        _state.value = SocketState.DISCONNECTED
    }

    /** Reconnects at once, e.g. when the network came back. */
    fun kick() {
        socket?.cancel()
    }

    private suspend fun connectLoop() {
        var pause = 1_000L
        while (wanted) {
            val closed = CompletableDeferred<Unit>()
            try {
                _state.value = SocketState.CONNECTING
                readyGate = CompletableDeferred()
                val request = Request.Builder().url(socketUrl()).build()
                socket = http.client.newWebSocket(request, Listener(closed))
                withTimeout(20_000) { readyGate.await() }
                pause = 1_000L
                closed.await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && !wanted) throw e
                Log.w(TAG, "Gateway socket failed: ${e.message}")
                socket?.cancel()
            }
            heartbeat?.cancel()
            _state.value = SocketState.DISCONNECTED
            failPending(IOException("Соединение с сервером прервалось"))
            if (!wanted) break
            delay(pause)
            pause = (pause * 2).coerceAtMost(30_000L)
        }
    }

    /** A ticket lives 30 seconds and opens one socket; a server without sign-in takes no ticket. */
    private suspend fun socketUrl(): String {
        val url = http.endpoint("api/ws")
        if (http.accessToken() != null) {
            val ticket = http.postJson("api/auth/ws-ticket").jsonObject.string("ticket")
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
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 60_000): JsonElement {
        awaitReady()
        val id = "a${ids.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val sent = socket?.send(HermesJson.encodeToString(JsonElement.serializer(), frame)) ?: false
        if (!sent) {
            pending.remove(id)
            throw IOException("Нет соединения с сервером")
        }
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Answers a [ServerRequest]; [error] rejects it instead. */
    fun respond(id: String, result: JsonElement?, error: String? = null) {
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (error != null) {
                put("error", buildJsonObject { put("code", -32000); put("message", error) })
            } else {
                put("result", result ?: JsonObject(emptyMap()))
            }
        }
        socket?.send(HermesJson.encodeToString(JsonElement.serializer(), frame))
    }

    suspend fun awaitReady() {
        start()
        withTimeout(30_000) { while (true) { if (_state.value == SocketState.READY) return@withTimeout; readyGate.await() } }
    }

    private fun failPending(error: Exception) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun handle(text: String) {
        lastFrameAt = System.currentTimeMillis()
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
                )
                if (event.type == "gateway.ready") {
                    onReady(event.payload)
                } else {
                    _events.tryEmit(event)
                }
            }
            method != null && id != null -> _requests.tryEmit(ServerRequest(id, method, frame["params"] as? JsonObject ?: JsonObject(emptyMap())))
            id != null -> {
                val deferred = pending.remove(id) ?: return
                val error = frame["error"] as? JsonObject
                if (error != null) {
                    deferred.completeExceptionally(
                        RpcException(error.long("code")?.toInt() ?: -1, error.string("message") ?: "Ошибка сервера", error["data"]),
                    )
                } else {
                    deferred.complete(frame["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun onReady(payload: JsonObject) {
        sessionAuthority = payload.bool("session_authority") ?: false
        val epoch = payload.string("replay_epoch")
        scope.launch {
            // Without this the server fails approvals of this client at once instead of asking.
            _state.value = SocketState.READY
            readyGate.complete(Unit)
            try {
                call("client.capabilities", buildJsonObject { put("server_requests", true) }, timeoutMillis = 15_000)
            } catch (e: Exception) {
                Log.w(TAG, "client.capabilities failed: ${e.message}")
            }
            if (payload.bool("heartbeat") == true) startHeartbeat()
            _ready.emit(epoch)
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(15_000)
                try {
                    call("gateway.ping", timeoutMillis = 10_000)
                } catch (e: Exception) {
                    if (System.currentTimeMillis() - lastFrameAt > 45_000) {
                        Log.w(TAG, "Gateway heartbeat lost")
                        socket?.cancel()
                        return@launch
                    }
                }
            }
        }
    }

    private inner class Listener(private val closed: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == 4401) Log.w(TAG, "Gateway refused the sign-in")
            closed.complete(Unit)
            if (!readyGate.isCompleted) readyGate.completeExceptionally(IOException("Сервер закрыл соединение: $code $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(Unit)
            if (!readyGate.isCompleted) readyGate.completeExceptionally(IOException(t.message ?: "Сбой соединения", t))
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
