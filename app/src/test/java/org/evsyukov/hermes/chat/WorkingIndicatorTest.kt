package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import org.evsyukov.hermes.testing.ChatTestResources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class WorkingIndicatorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val resources = ChatTestResources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = BusyGateway()
    private val strings = AppStrings(context.resources)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/ws" -> MockResponse.Builder().webSocketUpgrade(gateway).build()
                "/api/sessions/s1" -> json("""{"id":"s1","title":"Plans","is_active":true}""")
                "/api/sessions/s1/messages" -> json(
                    """{"messages":[
                        {"id":1,"role":"user","content":"найди"},
                        {"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{\"command\":\"ls\"}"}}]},
                        {"id":3,"role":"tool","tool_call_id":"c1","content":"ok"}
                    ]}""",
                )
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        resources.finish(owner, listOf("close server" to { server.close() }, "close database" to { db.close() }))
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun session(): ServerSession {
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        return ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
    }

    private fun chat(storedId: String?): ChatController {
        val session = session()
        return ChatController(session, resources.socket(GatewaySocket(session.http, scope, strings)), scope, strings, storedId)
    }

    @Test
    fun `a new chat keeps showing that it waits while the agent of its session is still being built`() = runBlocking<Unit> {
        val chat = chat(null)
        chat.send("hello", emptyList())
        // The server says the session is idle before the turn starts, then the agent thinks.
        val thinking = withTimeout(10_000) { chat.state.first { it.status == strings.get(org.evsyukov.hermes.R.string.chat_thinking) } }
        chat.close()

        assertThat(thinking.running).isTrue()
        assertThat(thinking.awaitingAnswer).isTrue()
    }

    /**
     * The socket is READY on the connection that carries the new chat before the gateway counts that
     * connection: it counts it only once `client.capabilities` is answered, up to 15 seconds later.
     * The count that arrives then is this chat's own connection, not one that replaced it.
     */
    @Test
    fun `a new chat keeps its waiting state when its connection is counted after the turn began`() = runBlocking<Unit> {
        val channel = GatedChannel()
        val controlled = CoroutineScope(SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Unconfined)
        val chat = ChatController(session(), channel, controlled, strings, null)
        try {
            channel.establish()
            chat.send("hello", emptyList())
            val thinking = withTimeout(2_000) {
                // Persistence can suspend even an Unconfined sender. The external agent can think
                // only after the actual prompt call, while the ready count still arrives later.
                channel.submitted.await()
                channel.announce()
                channel.idleThenThink()
                chat.state.first { it.status == strings.get(org.evsyukov.hermes.R.string.chat_thinking) }
            }

            assertThat(thinking.running).isTrue()
            assertThat(thinking.awaitingAnswer).isTrue()
        } finally {
            chat.close()
            controlled.cancel()
        }
    }

    @Test
    fun `a chat opened while its turn runs shows the turn going on with what streamed meanwhile`() = runBlocking<Unit> {
        // Leaving the chat and coming back opens it afresh, the way this controller does.
        val chat = chat("s1")
        val resumed = withTimeout(10_000) { chat.state.first { it.running && it.items.size == 2 && !it.loading } }
        chat.close()

        val answer = resumed.items.last() as ChatItem.Assistant
        assertThat(answer.streaming).isTrue()
        assertThat(answer.parts.map { it::class }).containsExactly(Part.Tool::class, Part.Text::class).inOrder()
        assertThat(answer.parts.last()).isEqualTo(Part.Text("Нашёл"))
        assertThat(resumed.awaitingAnswer).isFalse()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/**
 * A gateway with a turn under way in `s1`. A new session reports itself idle before its first turn
 * starts, as Hermes does when the agent of the session is built after the prompt was sent.
 */
private class BusyGateway : WebSocketListener() {
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(event("gateway.ready", null, "{}"))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        when (frame["method"]?.jsonPrimitive?.content) {
            "session.create" -> reply(webSocket, id, """{"session_id":"live1","stored_session_id":"s2"}""")
            "session.resume" -> reply(
                webSocket, id,
                """{"session_id":"live1","stored_session_id":"s1","running":true,"inflight":{"user":"найди","assistant":"Нашёл","streaming":true}}""",
            )
            "prompt.submit" -> {
                webSocket.send(event("session.info", "live1", """{"running":false,"model":"m"}"""))
                reply(webSocket, id, """{"status":"streaming"}""")
                webSocket.send(event("thinking.delta", "live1", """{"text":"…"}"""))
            }
            else -> reply(webSocket, id, "{}")
        }
    }

    private fun reply(webSocket: WebSocket, id: String, result: String) {
        webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
    }

    private fun event(type: String, sessionId: String?, payload: String): String {
        val session = sessionId?.let { ""","session_id":"$it"""" }.orEmpty()
        return """{"jsonrpc":"2.0","method":"event","params":{"type":"$type"$session,"payload":$payload}}"""
    }
}

/**
 * The gateway contract without a socket: READY on a connection before the gateway counts it, as
 * [GatewaySocket] does, and the count arrives when the test says so. A new session is created on the
 * connection under way and its first turn answers like Hermes: idle, then thinking.
 */
private class GatedChannel : GatewayChannel {
    val submitted = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    override val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<GatewayEvent>()
    override val events: SharedFlow<GatewayEvent> = _events

    private val _requests = MutableSharedFlow<ServerRequest>()
    override val requests: SharedFlow<ServerRequest> = _requests

    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)
    override val ready: SharedFlow<String?> = _ready

    private val _connections = MutableStateFlow(0)
    override val connections: StateFlow<Int> = _connections

    private val _transport = MutableStateFlow(0L)
    override val transport: StateFlow<Long> = _transport

    /** The connection is READY and carries calls; the gateway is not counted yet. */
    fun establish() {
        _transport.value = 1L
        _state.value = SocketState.READY
    }

    /** `client.capabilities` was answered: the gateway counts the connection ready. */
    fun announce() {
        _connections.value = 1
    }

    /** The server reports the new session idle, then the agent thinks while it is still being built. */
    suspend fun idleThenThink() {
        _events.emit(GatewayEvent("session.info", "live1", null, buildJsonObject { put("running", false); put("model", "m") }, transport = _transport.value))
        _events.emit(GatewayEvent("thinking.delta", "live1", null, buildJsonObject { put("text", "…") }, transport = _transport.value))
    }

    override suspend fun awaitReady() {
        if (_state.value != SocketState.READY) throw IOException("no connection")
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement = when (method) {
        "session.create" -> HermesJson.parseToJsonElement("""{"session_id":"live1","stored_session_id":"s2"}""")
        "session.active_list" -> HermesJson.parseToJsonElement("""{"sessions":[]}""")
        "prompt.submit" -> { submitted.complete(Unit); JsonObject(emptyMap()) }
        else -> JsonObject(emptyMap())
    }

    override fun respond(id: String, result: JsonElement?, error: String?) = Unit
}
