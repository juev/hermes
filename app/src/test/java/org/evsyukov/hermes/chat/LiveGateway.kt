package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
import java.util.concurrent.atomic.AtomicInteger

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

    private val _connections = MutableStateFlow(0)
    override val connections: StateFlow<Int> = _connections

    /** Every call the chat made, in order. */
    val calls = ConcurrentLinkedQueue<Pair<String, JsonObject>>()

    fun count(method: String) = calls.count { it.first == method }

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

    /** Runs inside `session.resume` after its answer is taken and before it is sent. */
    @Volatile
    var afterResume: (suspend () -> Unit)? = null

    /** Returns once the chat has taken every event sent before: an event of another session follows them. */
    suspend fun deliver() {
        _events.emit(GatewayEvent("session.info", "elsewhere", null, JsonObject(emptyMap())))
    }

    /** Returns once the chat has taken every question sent before: a question of another session follows them. */
    suspend fun deliverRequests() {
        _requests.emit(ServerRequest("barrier", "clarify", buildJsonObject { put("session_id", "elsewhere") }))
    }

    suspend fun connect() {
        _state.value = SocketState.READY
        _connections.update { it + 1 }
        _ready.emit(epoch)
    }

    fun drop() {
        _state.value = SocketState.DISCONNECTED
    }

    val latestSeq: Long get() = synchronized(lock) { seq }

    /** Stamps an event of `live1` and sends it. */
    suspend fun event(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        val event = synchronized(lock) { stamp(type, payload) }
        _events.emit(event)
    }

    /** Stamps an event without sending it: a frame the connection lost. */
    fun lost(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        synchronized(lock) { stamp(type, payload) }
    }

    private fun stamp(type: String, payload: JsonObject): GatewayEvent {
        seq += 1
        return GatewayEvent(type, "live1", seq, payload)
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
        val request = ServerRequest(id, method, JsonObject(params + ("session_id" to JsonPrimitive("live1"))))
        synchronized(lock) { open[id] = request }
        _requests.emit(request)
    }

    /** The question was answered elsewhere, e.g. from the notification: it no longer waits. */
    fun answeredElsewhere(id: String) {
        synchronized(lock) { open.remove(id) }
    }

    fun storedHistory(): String = synchronized(lock) { """{"messages":[${stored.joinToString(",")}]}""" }

    override suspend fun awaitReady() {
        if (_state.value != SocketState.READY) throw IOException("no connection")
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        if (_state.value != SocketState.READY) throw IOException("no connection")
        calls += method to params
        return when (method) {
            "session.resume" -> {
                val answer = synchronized(lock) {
                    buildJsonObject {
                        put("session_id", "live1")
                        put("stored_session_id", "s1")
                        put("running", running)
                        put("info", buildJsonObject { put("model", "live-model") })
                        if (running) put("inflight", buildJsonObject { put("assistant", inflight.toString()) })
                        turnStartedAt?.let { put("turn_started_at", it) }
                        if (open.isNotEmpty()) put("open_requests", openRequests())
                    }
                }
                afterResume?.invoke()
                answer
            }
            "session.active_list" -> HermesJson.parseToJsonElement("""{"sessions":[{"id":"live1","session_key":"s1"}]}""")
            else -> JsonObject(emptyMap())
        }
    }

    private fun openRequests() = buildJsonArray {
        open.values.forEach { r -> add(buildJsonObject { put("id", r.id); put("method", r.method); put("params", r.params) }) }
    }

    override fun respond(id: String, result: JsonElement?, error: String?) {
        synchronized(lock) { open.remove(id) }
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

    /** How long the stored history takes to come, to let events race it. */
    @Volatile
    var historyDelayMillis = 0L

    init {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/sessions/s1" -> json(
                    """{"id":"s1","title":"Mail","model":"stored-model","last_active":${gateway.lastActive},"is_active":${gateway.running}}""",
                )
                "/api/sessions/s1/messages" -> {
                    if (historyDelayMillis > 0) Thread.sleep(historyDelayMillis)
                    json(gateway.storedHistory())
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        http.start()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    fun session(context: Application, scope: CoroutineScope, db: CacheDatabase): ServerSession {
        val profile = ServerProfile("home", "Home", http.url("/").toString())
        return ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), AppStrings(context.resources), ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
    }

    fun close() = http.close()

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
