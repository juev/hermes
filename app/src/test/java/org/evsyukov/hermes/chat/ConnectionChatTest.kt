package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ConnectionChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = FakeGateway()
    private val strings = AppStrings(context.resources)

    /** What the failing step prints beside the calls: the state of the chat under test. */
    private var stateOf: (() -> String)? = null

    /** On: the stored history cannot be read, the way a slow or broken server leaves it. */
    @Volatile
    private var historyFails = false

    /** On: the stored chat ends with the message of the user, so no stored answer closes the turn. */
    @Volatile
    private var historyEndsWithUser = false

    @Before
    fun setUp() {
        // The history of a chat comes over plain HTTP. What the cards do is the chat's own business
        // and takes a fake gateway, so no test here waits for a socket that drops and comes back.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/sessions/s1" -> json("""{"id":"s1","title":"Mail","is_active":true}""")
                "/api/sessions/s1/messages" -> when {
                    historyFails -> MockResponse.Builder().code(500).build()
                    historyEndsWithUser -> json("""{"messages":[{"id":1,"role":"user","content":"send the report"}]}""")
                    else -> json(
                        """{"messages":[
                            {"id":1,"role":"user","content":"send the report"},
                            {"id":2,"role":"assistant","content":"","tool_calls":[{"id":"call_1","function":{"name":"manage_connections","arguments":"{}"}}]}
                        ]}""",
                    )
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    /** A chat with its turn waiting on the card of `op1`, opened afresh on a connected gateway. */
    private suspend fun openChat(): ChatController {
        gateway.connect()
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, gateway, scope, strings, "s1")
        withTimeout(10_000) { chat.state.first { it.connections.isNotEmpty() } }
        return chat
    }

    /** The params of the next call of [method]; other calls before it are skipped. */
    private suspend fun next(method: String, timeoutMillis: Long = 5_000): JsonObject = withTimeout(timeoutMillis) {
        var call = gateway.calls.receive()
        while (call.first != method) call = gateway.calls.receive()
        call.second
    }

    /** Runs a step that waits; its timeout fails with the step's name, the calls the gateway got and the chat's state. */
    private suspend fun <T> step(name: String, block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        return try {
            block()
        } catch (e: TimeoutCancellationException) {
            val state: String? = try { stateOf?.invoke() } catch (ignored: Exception) { null }
            throw AssertionError("Timed out after ${System.currentTimeMillis() - started} ms: $name\nChat state: $state\nCalls the gateway got: ${gateway.methods()}", e)
        }
    }

    private suspend fun ChatController.target(name: String, state: TargetState) =
        withTimeout(5_000) { this@target.state.first { s -> s.connections.single().targets.any { it.name == name && it.state == state } } }

    @Test
    fun `a card left open comes back when the chat opens, and the server moves its rows`() = runBlocking<Unit> {
        val chat = openChat()
        val card = chat.state.value.connections.single()
        assertThat(card.opId).isEqualTo("op1")
        assertThat(card.toolCallId).isEqualTo("call_1")
        assertThat(card.targets.map { it.name }).containsExactly("gmail", "github").inOrder()

        gateway.send("connection.update", "live1", """{"op_id":"op1","seq":2,"deadline_at":1800000000,"settled":false,"targets":[{"name":"gmail","kind":"connector","action":"connect","state":"failed"}]}""")
        val failed = chat.target("gmail", TargetState.FAILED)
        chat.close()

        assertThat(failed.connections.single().targets[0].connectUrl).isEqualTo("https://connect.example/gmail")
    }

    @Test
    fun `the card answers the server for its rows and can let the agent go on`() = runBlocking<Unit> {
        val chat = openChat()

        chat.answerConnection("op1", listOf(TargetAnswer("github", approved = true, env = mapOf("GITHUB_TOKEN" to "t0ken"))))
        val answer = next("connection.respond")
        chat.continueConnection("op1")
        val proceed = next("connection.respond")
        chat.close()

        assertThat(answer["owner"]!!.jsonObject.string("type")).isEqualTo("session")
        assertThat(answer["owner"]!!.jsonObject.string("session_id")).isEqualTo("live1")
        assertThat(answer.string("op_id")).isEqualTo("op1")
        val row = answer["result"]!!.jsonObject["targets"]!!.jsonArray.single().jsonObject
        assertThat(row.string("name")).isEqualTo("github")
        assertThat(row.string("status")).isEqualTo("approved")
        assertThat(row["env"]!!.jsonObject.string("GITHUB_TOKEN")).isEqualTo("t0ken")
        assertThat(proceed["result"]!!.jsonObject.string("settled_by")).isEqualTo("continue")
    }

    @Test
    fun `a sign-in that failed asks the server for a new link`() = runBlocking<Unit> {
        val chat = openChat()

        chat.retryConnection("op1", "gmail")
        val connect = next("connectors.connect")
        chat.close()

        assertThat(connect["owner"]!!.jsonObject.string("session_id")).isEqualTo("live1")
        assertThat(connect["connectors"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("gmail")
        assertThat(connect.bool("reconnect")).isTrue()
    }

    @Test
    fun `coming back from the browser wakes the operation and shows what the server found`() = runBlocking<Unit> {
        val chat = openChat()

        chat.browserReturned()
        val wake = next("connectors.operation.wake")
        val status = next("connectors.operation.status")
        val connected = chat.target("gmail", TargetState.CONNECTED)
        chat.close()

        assertThat(wake.string("op_id")).isEqualTo("op1")
        assertThat(status.string("op_id")).isEqualTo("op1")
        assertThat(connected.connections.single().awaitsBrowser).isFalse()
    }

    @Test
    fun `a settled card stays as its outcome and takes no answers`() = runBlocking<Unit> {
        val chat = openChat()

        gateway.send("connection.update", "live1", """{"op_id":"op1","seq":2,"deadline_at":1800000000,"settled":true,"settled_by":"deadline","targets":[]}""")
        withTimeout(5_000) { chat.state.first { it.connections.single().settled } }
        chat.continueConnection("op1")
        chat.retryConnection("op1", "gmail")
        // Long enough for a call that went out to reach the gateway.
        delay(300)
        chat.close()

        assertThat(gateway.methods()).containsNoneOf("connection.respond", "connectors.connect")
    }

    @Test
    fun `a card the server closed while the socket was down takes no answers after the chat joins again`() = runBlocking<Unit> {
        val chat = openChat()
        stateOf = {
            val state = chat.state.value
            "connections=" + state.connections.map { it.opId + ":" + it.settled } + " loading=" + state.loading + " error=" + state.error
        }

        next("session.resume")
        gateway.pending = false
        // The connection drops and comes back, and the chat joins its session again, as it does after
        // a real reconnect. The gateway is a fake: no socket, no pause, no budget racing the runner.
        gateway.drop()
        gateway.connect("epoch2")
        step("the chat joins its session again after the drop") { next("session.resume") }
        step("the card settles once the session has it no longer") { withTimeout(5_000) { chat.state.first { it.connections.single().settled } } }
        chat.continueConnection("op1")
        delay(300)
        chat.close()

        assertThat(gateway.methods()).doesNotContain("connection.respond")
    }

    @Test
    fun `a connection whose ready the chat never heard still makes it join again`() = runBlocking<Unit> {
        val chat = openChat()
        // What the chat asked on the connection it started on is behind us; what comes next is the
        // join of the connection it never heard.
        while (gateway.calls.tryReceive().isSuccess) {
            // Drain the calls of the connection behind us.
        }

        // The socket is up again, but its ready went out before the chat was listening: the one
        // replayed value is that missed connection, and dropping the first ready dropped it.
        gateway.connectedWithoutSaying()

        step("the chat joins its session again after the connection it did not hear") { next("session.resume") }
        chat.close()
    }

    @Test
    fun `a join whose answer is lost is tried again on the next connection`() = runBlocking<Unit> {
        val chat = openChat()
        stateOf = {
            val state = chat.state.value
            "connections=" + state.connections.map { it.opId + ":" + it.settled } + " loading=" + state.loading + " error=" + state.error
        }
        while (gateway.calls.tryReceive().isSuccess) {
            // Drain the calls of the connection behind us.
        }

        // The socket comes back and the chat joins again, but the answer never reaches it: the
        // connection dropped between the call and its result. The live id is gone with it.
        gateway.pending = false
        gateway.failResume = true
        gateway.drop()
        gateway.connect("epoch2")
        step("the lost join fails") { withTimeout(5_000) { chat.state.first { it.error != null } } }
        // The failed call is in the channel; the join that matters is the next one.
        while (gateway.calls.tryReceive().isSuccess) {
            // Drain the call whose answer was lost.
        }

        // Another connection comes up. The chat has no live id, but it must join again all the same.
        gateway.failResume = false
        gateway.drop()
        gateway.connect("epoch3")
        step("the chat joins at the next connection") { next("session.resume") }
        step("the card settles once the session has it no longer") { withTimeout(5_000) { chat.state.first { it.connections.single().settled } } }
        chat.close()
    }

    @Test
    fun `a chat joins its session even when its history cannot be read`() = runBlocking<Unit> {
        historyFails = true

        val chat = openChat()
        val card = chat.state.value.connections.single()
        chat.close()

        // The resume went out before the transcript came back, so the card is here although the
        // history failed: a chat that cannot read its past is still live.
        assertThat(card.opId).isEqualTo("op1")
    }

    @Test
    fun `an answer that streams when the chat joins stays under the history`() = runBlocking<Unit> {
        historyEndsWithUser = true
        gateway.inflight = "half"

        val chat = openChat()
        val items = withTimeout(5_000) { chat.state.first { it.items.size == 2 }.items }
        chat.close()

        assertThat(items.first()).isInstanceOf(ChatItem.User::class.java)
        val answer = items.last() as ChatItem.Assistant
        assertThat(answer.streaming).isTrue()
        assertThat(answer.text).isEqualTo("half")
    }

    @Test
    fun `a request of another session opens no card here`() = runBlocking<Unit> {
        val chat = openChat()

        gateway.send("connection.request", "live9", REQUEST.replace("op1", "op9"))
        gateway.send("connection.request", "live1", REQUEST.replace("op1", "op2").replace("call_1", "call_2"))
        val two = withTimeout(5_000) { chat.state.first { it.connections.size == 2 } }
        chat.close()

        assertThat(two.connections.map { it.opId }).containsExactly("op1", "op2").inOrder()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

private const val REQUEST = """{"op_id":"op1","seq":1,"deadline_at":1800000000,"timeout_seconds":600,"tool_call_id":"call_1","targets":[
    {"name":"gmail","kind":"connector","action":"connect","state":"initiated","connect_url":"https://connect.example/gmail"},
    {"name":"github","kind":"mcp","action":"install","state":"pending","required_env":[{"name":"GITHUB_TOKEN","required":true,"secret":true,"default":""}]}]}"""

private const val STATUS = """{"op_id":"op1","seq":3,"deadline_at":1800000000,"settled":false,"targets":[{"name":"gmail","kind":"connector","action":"connect","state":"connected","alias":"work"}]}"""

/**
 * The gateway as a chat sees it: no socket and no clock. It connects when the test says so, answers
 * the calls the chat makes the way a gateway whose session `s1` waits on the card of `op1` does, and
 * tells the test every call it got.
 */
private class FakeGateway : GatewayChannel {
    private val _state = MutableStateFlow(SocketState.DISCONNECTED)

    override val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)

    override val events: SharedFlow<GatewayEvent> = _events

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 16)

    override val requests: SharedFlow<ServerRequest> = _requests

    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)

    override val ready: SharedFlow<String?> = _ready

    private val _connections = MutableStateFlow(0)

    override val connections: StateFlow<Int> = _connections

    val calls = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    private val seen = ConcurrentLinkedQueue<String>()

    /** Whether a resumed session still has the card open. */
    @Volatile
    var pending = true

    /** Whether the session runs a turn the chat joins. */
    @Volatile
    var running = true

    /** What streamed of the answer under way while the chat was away; null when nothing did. */
    @Volatile
    var inflight: String? = null

    /** Whether the next join's answer is lost, as when the connection drops before the result arrives. */
    @Volatile
    var failResume = false

    fun methods(): List<String> = seen.toList()

    /** The connection comes up, for the first time or again after a drop. */
    suspend fun connect(epoch: String = "epoch1") {
        _state.value = SocketState.READY
        _connections.update { it + 1 }
        _ready.emit(epoch)
    }

    /**
     * The connection came up again and no ready reached a collector, as when the chat had not started
     * listening yet: only the count of connections tells that it happened.
     */
    fun connectedWithoutSaying() {
        _state.value = SocketState.READY
        _connections.update { it + 1 }
    }

    /** The connection is gone, as after the server closed it. */
    fun drop() {
        _state.value = SocketState.DISCONNECTED
    }

    fun send(type: String, sessionId: String, payload: String) {
        _events.tryEmit(GatewayEvent(type, sessionId, null, HermesJson.parseToJsonElement(payload).jsonObject))
    }

    override suspend fun awaitReady() {
        if (_state.value != SocketState.READY) throw IOException("no connection")
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        if (_state.value != SocketState.READY) throw IOException("no connection")
        seen += method
        calls.trySend(method to params)
        if (method == "session.resume" && failResume) throw IOException("the answer was lost")
        val result = when (method) {
            "session.resume" -> {
                val streamed = inflight?.let { ""","inflight":{"assistant":"$it"}""" }.orEmpty()
                val card = if (pending) ""","pending_connection":$REQUEST""" else ""
                """{"session_id":"live1","stored_session_id":"s1","running":$running$streamed$card}"""
            }
            "connection.respond" -> """{"status":"ok","settled":false}"""
            "connectors.operation.wake" -> """{"status":"ok"}"""
            "connectors.operation.status" -> STATUS
            else -> "{}"
        }
        return HermesJson.parseToJsonElement(result)
    }

    override fun respond(id: String, result: JsonElement?, error: String?) {
        seen += "respond:$id"
    }
}
