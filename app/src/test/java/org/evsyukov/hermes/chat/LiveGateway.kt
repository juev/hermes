package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.GatewayChannel
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A gateway with one stored chat `s1` whose live session is `live1`, modelled the way the dashboard
 * keeps it: every event of the session gets the next `seq`; `session.resume` answers with the turn
 * under way and the questions still open; every call the chat makes is recorded, and a method the
 * fake does not know answers an empty object; the answer is written to the stored history after each round of tools and
 * at the end, each row with the server's time, which [LiveServer] serves over HTTP. No socket and no
 * real clock: the test drives the turn.
 */
internal class LiveGateway : GatewayChannel {
    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    override val state: StateFlow<SocketState> = _state

    // No buffer: emitting suspends until the chat takes the event, so nothing is dropped and
    // [deliver] knows the chat is done with everything sent before it.
    private val _events = MutableSharedFlow<GatewayEvent>()
    override val events: SharedFlow<GatewayEvent> = _events

    private val _requests = MutableSharedFlow<ServerRequest>()
    override val requests: SharedFlow<ServerRequest> = _requests

    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)
    override val ready: SharedFlow<String?> = _ready

    private val _connections = LaggingInt(0)
    override val connections: StateFlow<Int> = _connections

    private val _transport = LaggingFlow(0L)
    override val transport: StateFlow<Long> = _transport
    private var transports = 0L

    /** The connection each `session.create` went over, as [transport] numbered it. */
    val createdOn = ConcurrentLinkedQueue<Long>()

    /** The ids each `session.resume` listed in `open_requests`, in order. */
    val listedOn = ConcurrentLinkedQueue<List<String>>()

    /** The connection each `session.resume` went over, as [connections] counted it then. */
    val resumedOn = ConcurrentLinkedQueue<Int>()

    /** Every call the chat made, in order. */
    val calls = ConcurrentLinkedQueue<Pair<String, JsonObject>>()

    fun count(method: String) = calls.count { it.first == method }

    private var createGate: CompletableDeferred<Unit>? = null

    /** Completed when the next `session.create` arrives and waits for [releaseCreate]. */
    val createArrived = CompletableDeferred<Unit>()

    /** Holds the next `session.create` until [releaseCreate]. */
    fun holdCreate() {
        createGate = CompletableDeferred()
    }

    fun releaseCreate() {
        createGate?.complete(Unit)
    }

    /** Waits at the `session.create` gate, if a test is holding it. */
    suspend fun awaitCreate() {
        createArrived.complete(Unit)
        createGate?.await()
    }

    @Volatile
    var epoch = "epoch1"

    // The server's side of the session.
    private val lock = Any()
    private var seq = 0L

    @Volatile
    var running = false
        private set
    private val inflight = StringBuilder()

    /** How much of [inflight] is stored already, and the tools of the round not stored yet. */
    private var persisted = 0
    private val roundTools = mutableListOf<String>()

    /** The server's clock, in seconds; every stored row and turn start moves it. */
    private var clock = 1_000.0
    private var turnStartedAt: Double? = null
    private val stored = mutableListOf<String>()
    private val open = LinkedHashMap<String, ServerRequest>()
    private val rows = AtomicInteger()

    /** Bumped with every stored row, as `last_active`. */
    @Volatile
    var lastActive = 100.0
        private set

    /** Synthetic server watermark supplied by ownership/coalescing scenarios. */
    fun activity(at: Double) { lastActive = at }

    /** The gateway hosts the session of `s1`; off, its live list leaves the chat out. */
    @Volatile
    var hosted = true

    /** Runs inside `session.resume` after its answer is taken and before it is sent. */
    @Volatile
    var afterResume: (suspend () -> Unit)? = null

    /**
     * Held while `session.active_list` is answered: a test keeps the initial load before its join,
     * so a message can be sent before the join is asked. Null answers at once.
     */
    @Volatile
    var activeListGate: CountDownLatch? = null

    /** Returns once the chat has taken every event sent before: an event of another session follows them. */
    suspend fun deliver() {
        _events.emit(GatewayEvent("session.info", "elsewhere", null, JsonObject(emptyMap())))
    }

    /** Returns once the chat has taken every question sent before: a question of another session follows them. */
    suspend fun deliverRequests() {
        _requests.emit(ServerRequest("barrier", "clarify", buildJsonObject { put("session_id", "elsewhere") }))
    }

    /** A connection comes up and the gateway is ready on it, counted at once. */
    suspend fun connect() {
        establish()
        announce()
    }

    /**
     * The socket is READY on a new connection and calls go over it, but the gateway is not counted
     * yet: the real socket counts it only once `client.capabilities` is answered, up to 15 seconds later.
     */
    fun establish() {
        _transport.value = synchronized(lock) { ++transports }
        _state.value = SocketState.READY
    }

    /** The connection [establish] brought is counted and announced as ready. */
    suspend fun announce() {
        _connections.bump()
        _ready.emit(epoch)
    }

    /**
     * The next read of [connections] finds a new READY connection, already counted, while no collector
     * has heard of either: what a controller observes when the socket changes and is counted between
     * two reads of one decision.
     */
    fun replaceOnNextCount() {
        _connections.onNextRead {
            _transport.quietly(synchronized(lock) { ++transports })
            _connections.quietly(_connections.value + 1)
        }
    }

    /** The collectors hear the connection [replaceOnNextCount] brought. */
    fun publishReplacement() {
        _transport.publish()
        _connections.publish()
    }

    fun drop() {
        _state.value = SocketState.DISCONNECTED
        _transport.value = 0
    }

    /**
     * The connection is lost and another is READY at once, and no collector of [transport] has run
     * since: its value is the new connection, while what listens to it still knows only the old one.
     * [heard] lets the collectors run.
     */
    fun replaceUnheard() {
        _transport.quietly(synchronized(lock) { ++transports })
    }

    /** The collectors of [transport] hear what [replaceUnheard] did. */
    fun heard() = _transport.publish()

    val latestSeq: Long get() = synchronized(lock) { seq }

    /** Stamps an event of `live1` and sends it. */
    suspend fun event(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        val event = synchronized(lock) { stamp(type, payload) }
        _events.emit(event)
    }

    /** Sends an event of another live session of the dashboard, which no chat of `live1` may take as its own. */
    suspend fun foreign(type: String, payload: JsonObject) {
        _events.emit(GatewayEvent(type, "live9", null, payload, transport = _transport.value))
    }

    /** Stamps an event without sending it: a frame the connection lost. */
    fun lost(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        synchronized(lock) { stamp(type, payload) }
    }

    private fun stamp(type: String, payload: JsonObject): GatewayEvent {
        seq += 1
        return GatewayEvent(type, "live1", seq, payload, transport = _transport.value)
    }

    /** The user's message was taken and a turn starts on the server. */
    suspend fun start(text: String) {
        synchronized(lock) {
            stored += """{"id":${rows.incrementAndGet()},"role":"user","content":"$text","timestamp":${tick()}}"""
            lastActive += 1
            running = true
            inflight.clear()
            persisted = 0
            roundTools.clear()
            turnStartedAt = tick()
        }
        event("message.start")
    }

    private fun tick(): Double {
        clock += 1
        return clock
    }

    suspend fun delta(text: String) {
        synchronized(lock) { inflight.append(text) }
        event("message.delta", buildJsonObject { put("text", text) })
    }

    fun lostDelta(text: String) {
        synchronized(lock) { inflight.append(text) }
        lost("message.delta", buildJsonObject { put("text", text) })
    }

    suspend fun reasoning(text: String) = event("reasoning.delta", buildJsonObject { put("text", text) })

    suspend fun tool(id: String, name: String) {
        synchronized(lock) { roundTools += id }
        event("tool.start", buildJsonObject { put("tool_id", id); put("name", name); put("context", "ls") })
        event("tool.complete", buildJsonObject { put("tool_id", id); put("summary", "listed") })
    }

    /** A round of tools is over: what streamed since the last round and its tools are stored. */
    fun persistRound() {
        synchronized(lock) { storeRound() }
    }

    private fun storeRound() {
        val text = inflight.substring(persisted)
        if (text.isEmpty() && roundTools.isEmpty()) return
        val calls = roundTools.joinToString(",") { """{"id":"$it","function":{"name":"terminal","arguments":"{}"}}""" }
        val time = tick()
        stored += """{"id":${rows.incrementAndGet()},"role":"assistant","content":"$text","tool_calls":[$calls],"timestamp":$time}"""
        roundTools.forEach { stored += """{"id":${rows.incrementAndGet()},"role":"tool","tool_call_id":"$it","content":"listed","timestamp":$time}""" }
        roundTools.clear()
        persisted = inflight.length
        lastActive += 1
    }

    /** The turn ends with what streamed; the rest of the answer is stored. */
    suspend fun complete() {
        val text = synchronized(lock) {
            storeRound()
            val text = inflight.toString()
            running = false
            inflight.clear()
            persisted = 0
            turnStartedAt = null
            text
        }
        event("message.complete", buildJsonObject { put("text", text); put("status", "complete") })
    }

    /**
     * The turn ends on the server while no chat is connected: its outcome is not sent to anyone.
     * The server's state is idle as a fresh join reports it.
     */
    fun finishWithoutEvent() {
        synchronized(lock) {
            running = false
            inflight.clear()
            persisted = 0
            turnStartedAt = null
        }
    }

    /** The turn fails: the agent reports the error and the turn is over. */
    suspend fun fail(message: String) {
        synchronized(lock) {
            running = false
            inflight.clear()
            persisted = 0
            turnStartedAt = null
        }
        event("error", buildJsonObject { put("message", message) })
    }

    /** The agent asks the user; the question waits until it is answered. */
    suspend fun ask(id: String, method: String, params: JsonObject) {
        val request = ServerRequest(id, method, JsonObject(params + ("session_id" to JsonPrimitive("live1"))), transport = _transport.value)
        synchronized(lock) { open[id] = request }
        _requests.emit(request)
    }

    /**
     * A request of `live1` that came over connection [transport] and reaches the chat only now: the
     * server never listed it as open on the connection after. [transport] 0 is one with no connection known.
     */
    suspend fun askLate(transport: Long, id: String, params: JsonObject) {
        _requests.emit(ServerRequest(id, "clarify", JsonObject(params + ("session_id" to JsonPrimitive("live1"))), transport = transport))
    }

    /** An event of `live1` that came over connection [transport] and reaches the chat only now. */
    suspend fun eventLate(transport: Long, type: String, payload: JsonObject) {
        _events.emit(GatewayEvent(type, "live1", null, payload, transport = transport))
    }

    /** The question was answered elsewhere, e.g. from the notification: it no longer waits. */
    fun answeredElsewhere(id: String) {
        synchronized(lock) { open.remove(id) }
    }

    /** The server stops waiting for the question, as the dashboard does: one `request.cancel` with the reason. */
    suspend fun withdraw(id: String, reason: String) {
        val request = synchronized(lock) { open.remove(id) } ?: return
        event("request.cancel", buildJsonObject { put("id", id); put("method", request.method); put("reason", reason) })
        deliver()
    }

    /** An answer of the chat, as it went to the socket. */
    data class Response(val id: String, val result: JsonElement?, val error: String?)

    /** Every answer the chat gave to a question, in order. */
    val responses = ConcurrentLinkedQueue<Response>()

    /** Answers leave the phone but never reach the server, as on a connection that is dying. */
    @Volatile
    var losesResponses = false

    fun storedHistory(): String = synchronized(lock) { """{"messages":[${stored.joinToString(",")}]}""" }

    override suspend fun awaitReady() {
        if (_state.value != SocketState.READY) throw IOException("no connection")
    }

    @Volatile var beforePrompt: (suspend (JsonObject) -> Unit)? = null

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        if (_state.value != SocketState.READY) throw IOException("no connection")
        calls += method to params
        if (method == "prompt.submit") beforePrompt?.invoke(params)
        return when (method) {
            "session.resume" -> {
                resumedOn += _connections.value
                val answer = synchronized(lock) {
                    listedOn += open.keys.toList()
                    buildJsonObject {
                        put("session_id", "live1")
                        put("stored_session_id", "s1")
                        // The gateway reports the flag as 0 or 1, not as a boolean (docs/fixtures/socket/resume.json).
                        put("running", if (running) 1 else 0)
                        put("info", buildJsonObject { put("model", "live-model") })
                        if (running) put("inflight", buildJsonObject { put("assistant", inflight.toString()) })
                        turnStartedAt?.let { put("turn_started_at", it) }
                        if (open.isNotEmpty()) put("open_requests", openRequests())
                    }
                }
                afterResume?.invoke()
                answer
            }
            // A new chat's session; its questions are asked on `live1` like those of `s1`.
            "session.create" -> {
                awaitCreate()
                createdOn += _transport.value
                buildJsonObject { put("session_id", "live1") }
            }
            "session.active_list" -> {
                activeListGate?.let { runCatching { it.await(30, TimeUnit.SECONDS) } }
                HermesJson.parseToJsonElement(if (hosted) """{"sessions":[{"id":"live1","session_key":"s1"}]}""" else """{"sessions":[]}""")
            }
            else -> JsonObject(emptyMap())
        }
    }

    private fun openRequests() = buildJsonArray {
        open.values.forEach { r -> add(buildJsonObject { put("id", r.id); put("method", r.method); put("params", r.params) }) }
    }

    override fun respond(id: String, result: JsonElement?, error: String?) {
        responses += Response(id, result, error)
        if (!losesResponses) synchronized(lock) { open.remove(id) }
        calls += "respond" to JsonObject(mapOf("id" to JsonPrimitive(id)))
    }

    companion object {
        /** How many events the dashboard's replay ring keeps; a hidden stream longer than it must lose nothing. */
        const val RING = 512
    }
}

/** The HTTP side of the chat `s1`: its head and its stored history, as [gateway] has them now. */
internal class LiveServer(private val gateway: LiveGateway) {
    val http = MockWebServer()
    val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    @Volatile var inputTokens = 0L
    class Gate {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
    }
    private val summaryGate = java.util.concurrent.atomic.AtomicReference<Gate?>()
    private val ownedHistoryGate = java.util.concurrent.atomic.AtomicReference<Gate?>()
    private val gates = java.util.concurrent.CopyOnWriteArrayList<Gate>()
    fun holdSummary() = Gate().also { gates += it; summaryGate.set(it) }
    fun holdHistory() = Gate().also { gates += it; ownedHistoryGate.set(it) }
    fun releaseGates() = gates.forEach { it.release.countDown() }
    private fun waitAt(gate: Gate?) {
        if (gate == null) return
        gate.entered.countDown()
        check(gate.release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Live-server gate was not released" }
    }

    /** Set once [close] ran; a teardown regression checks that a failed reset did not skip it. */
    @Volatile
    var closed = false
        private set

    /** How long the stored history takes to come, to let events race it. */
    @Volatile
    var historyDelayMillis = 0L

    /**
     * Held while a history answer is produced: a test opens the chat with it in place, so the initial
     * load's adoption is applied only when the test releases it. Null answers at once.
     */
    @Volatile
    var historyGate: CountDownLatch? = null
    /** The summary of `s1`; null serves the one [gateway] implies. */
    @Volatile
    var summary: String? = null

    /** Holds a DELETE of `s1` after the request arrives; [releaseDelete] lets it answer. */
    @Volatile
    var deleteGate: java.util.concurrent.CountDownLatch? = null

    /** Counted down when a DELETE of `s1` has arrived and is held by [deleteGate]. */
    val deleteArrived = java.util.concurrent.CountDownLatch(1)

    /** How many list requests the sidebar made over HTTP. */
    private val lists = AtomicInteger()

    val listCount: Int get() = lists.get()
    private val searches = AtomicInteger()
    val searchCount: Int get() = searches.get()

    fun releaseDelete() {
        deleteGate?.countDown()
    }

    /** Holds the next `session.create` of [gateway] until [releaseCreate]. */
    fun holdCreate() = gateway.holdCreate()

    fun releaseCreate() = gateway.releaseCreate()

    val createArrived get() = gateway.createArrived

    /**
     * The gateway protocol over a real socket, enough for the view model's own connection: the first
     * `session.create` waits on [LiveGateway.releaseCreate], so a test can hold the identity of a
     * new chat while the screen moves on.
     */
    private val openSockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()

    private val socketGateway = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openSockets += webSocket
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { HermesJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            val id = (frame["id"] as? JsonPrimitive)?.content ?: return
            val method = (frame["method"] as? JsonPrimitive)?.content
            val result = when (method) {
                "session.create" -> {
                    runBlocking { kotlinx.coroutines.withTimeout(5_000) { gateway.awaitCreate() } }
                    """{"session_id":"live1","stored_session_id":"s1"}"""
                }
                "session.resume" -> """{"session_id":"live1","stored_session_id":"s1","running":0,"info":{"model":"live-model"}}"""
                "session.active_list" -> if (gateway.hosted) """{"sessions":[{"id":"live1","session_key":"s1"}]}""" else """{"sessions":[]}"""
                else -> "{}"
            }
            webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            openSockets -= webSocket
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            openSockets -= webSocket
        }
    }

    init {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.url.encodedPath) {
                "/api/sessions/search" -> {
                    searches.incrementAndGet()
                    json("""{"results":[{"session_id":"s16","title":"Capacity","snippet":"found","source":"android"}]}""")
                }
                "/api/sessions" -> {
                    lists.incrementAndGet()
                    json("""{"sessions":[],"total":0}""")
                }
                "/api/sessions/s1" -> {
                    if (request.method == "DELETE") {
                        deleteArrived.countDown()
                        deleteGate?.let { check(it.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "DELETE gate was not released" } }
                    }
                    val body = summary ?: """{"id":"s1","title":"Mail","model":"stored-model","last_active":${gateway.lastActive},"input_tokens":$inputTokens}"""
                    waitAt(summaryGate.getAndSet(null))
                    json(body)
                }
                "/api/sessions/s1/messages" -> {
                    if (historyDelayMillis > 0) Thread.sleep(historyDelayMillis)
                    val body = gateway.storedHistory()
                    historyGate?.let { check(it.await(30, TimeUnit.SECONDS)) { "history gate not released" } }
                    waitAt(ownedHistoryGate.getAndSet(null))
                    json(body)
                }
                "/api/sessions/s1/mutation-snapshot" -> json("{}")
                "/api/system/stats" -> json("{}")
                "/api/status" -> json("""{"auth_required":false,"version":"test"}""")
                "/api/ws" -> MockResponse.Builder().webSocketUpgrade(socketGateway).build()
                else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        http.start()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val resources = org.evsyukov.hermes.testing.ChatTestResources()

    fun finish(job: kotlinx.coroutines.Job, closures: List<Pair<String, () -> Unit>>) = resources.finish(job, closures)

    fun session(context: Application, scope: CoroutineScope, db: CacheDatabase): ServerSession {
        val profile = ServerProfile("home", "Home", http.url("/").toString())
        return ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile), AppStrings(context.resources), ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
    }

    fun close() {
        var primary: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                val first = primary
                if (first == null) primary = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        openSockets.forEach { socket -> attempt { socket.close(1000, "test over") } }
        openSockets.clear()
        attempt { http.close(); closed = true }
        primary?.let { throw it }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    companion object {
        fun database(context: Application): CacheDatabase = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    }
}

internal fun questions(vararg text: String): JsonObject = buildJsonObject {
    put("questions", JsonArray(text.mapIndexed { i, q -> buildJsonObject { put("qid", "q$i"); put("question", q) } }))
}

/**
 * A [StateFlow] whose collectors can lag behind its value, as a collector that has not been scheduled
 * yet does: [quietly] changes the value at once and tells no collector until [publish].
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class LaggingFlow(initial: Long) : StateFlow<Long> {
    private val told = MutableStateFlow(initial)

    @Volatile
    private var current = initial

    override var value: Long
        get() = current
        set(v) {
            current = v
            told.value = v
        }

    override val replayCache: List<Long> get() = listOf(current)

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Long>): Nothing = told.collect(collector)

    fun quietly(v: Long) {
        current = v
    }

    fun publish() {
        told.value = current
    }
}

/**
 * The count of connections with the same lag, and a one-shot [onNextRead] hook: a change that the
 * socket makes while a decision reads the count, before any collector has been told of it.
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class LaggingInt(initial: Int) : StateFlow<Int> {
    private val told = MutableStateFlow(initial)

    @Volatile
    private var current = initial

    private val hook = AtomicReference<(() -> Unit)?>(null)

    /** Runs [hook] once, before the next value is read: a change made exactly when a decision looks. */
    fun onNextRead(hook: () -> Unit) {
        this.hook.set(hook)
    }

    override var value: Int
        get() {
            hook.getAndSet(null)?.invoke()
            return current
        }
        set(v) {
            current = v
            told.value = v
        }

    override val replayCache: List<Int> get() = listOf(current)

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Int>): Nothing = told.collect(collector)

    /** A connection was counted and announced: every collector is told now. */
    fun bump() {
        current += 1
        told.value = current
    }

    fun quietly(v: Int) {
        current = v
    }

    fun publish() {
        told.value = current
    }
}
