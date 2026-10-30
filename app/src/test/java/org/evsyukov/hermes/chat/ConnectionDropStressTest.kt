package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.data.remote.HermesJson
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The drop and rejoin of `ConnectionChatTest > a card the server closed while the socket was down…`
 * (#56) over and over in one JVM, a fresh chat every round: a rare race needs more attempts than a
 * suite run offers.
 *
 * `STRESS_ROUNDS` sets how many times the chat is dropped and joined again: ten by default, so a
 * normal run pays about a minute, and a hundred in the stress job, which is where a rare race is
 * looked for. A round that times out says which step it was in, the calls the gateway got and the
 * socket log.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ConnectionDropStressTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val strings = AppStrings(context.resources)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a chat joins its session again after a drop, many times`() = runBlocking<Unit> {
        val rounds = System.getenv("STRESS_ROUNDS")?.toIntOrNull() ?: 10
        // One client, as in the app: a client per round would leave threads behind.
        val client = OkHttpClient()
        for (round in 1..rounds) {
            val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
            val server = MockWebServer()
            val gateway = StressGateway()
            var step = "the card of the first connection"
            var chat: ChatController? = null
            val changes = java.util.concurrent.CopyOnWriteArrayList<String>()
            try {
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                        "/api/ws" -> MockResponse.Builder().webSocketUpgrade(gateway).build()
                        "/api/sessions/s1" -> json("""{"id":"s1","title":"Mail","is_active":true}""")
                        "/api/sessions/s1/messages" -> json(
                            """{"messages":[
                                {"id":1,"role":"user","content":"send the report"},
                                {"id":2,"role":"assistant","content":"","tool_calls":[{"id":"call_1","function":{"name":"manage_connections","arguments":"{}"}}]}
                            ]}""",
                        )
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
                server.start()
                val profile = ServerProfile("home", "Home", server.url("/").toString())
                val session = ServerSession(profile, client, ServerStore(context, StressCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
                val socket = GatewaySocket(session.http, scope, strings)
                val opened = ChatController(session, socket, scope, strings, "s1")
                chat = opened
                val watcher = scope.launch {
                    opened.state.collect { state ->
                        val now = snapshot(state)
                        if (changes.lastOrNull() != now) changes += now
                    }
                }
                try {
                    withTimeout(10_000) { opened.state.first { it.connections.isNotEmpty() } }
                    next(gateway, "session.resume")
                    gateway.pending = false
                    gateway.drop()
                    step = "the chat joins its session again after the drop"
                    next(gateway, "session.resume")
                    step = "the card settles once the session has it no longer"
                    withTimeout(5_000) { opened.state.first { it.connections.single().settled } }
                } finally {
                    watcher.cancel()
                    opened.close()
                    socket.stop()
                    // The upgrade of the dropped connection is still closing; a server closed over it
                    // gives up on the queue and would fail a round for nothing.
                    kotlinx.coroutines.delay(100)
                }
            } catch (e: TimeoutCancellationException) {
                val log = (ShadowLog.getLogsForTag("HermesSocket") + ShadowLog.getLogsForTag("HermesChat")).joinToString("\n") { "${it.tag}: ${it.msg}" }
                val state = chat?.state?.value?.let(::snapshot) ?: "no chat"
                val trail = changes.takeLast(12).joinToString("\n  ")
                throw AssertionError(
                    "Round $round of $rounds timed out: $step\nChat state: $state\nChat went:\n  $trail\nCalls the gateway got: ${gateway.methods()}\n$log",
                    e,
                )
            } finally {
                runCatching { server.close() }
                db.close()
            }
            if (round % 10 == 0) println("stress: round $round of $rounds passed")
        }
    }

    private fun snapshot(state: ChatState): String =
        "connections=[" + state.connections.joinToString { c -> c.opId + " settled=" + c.settled + " targets=" + c.targets.map { it.name + ":" + it.state } } + "]" +
            " running=" + state.running + " loading=" + state.loading + " error=" + state.error + " items=" + state.items.size

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    /** The params of the next call of [method]; other calls before it are skipped. */
    private suspend fun next(gateway: StressGateway, method: String) = withTimeout(5_000) {
        var call = gateway.calls.receive()
        while (call.first != method) call = gateway.calls.receive()
        call.second
    }

    private object StressCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

private const val STRESS_REQUEST = """{"op_id":"op1","seq":1,"deadline_at":1800000000,"timeout_seconds":600,"tool_call_id":"call_1","targets":[
    {"name":"gmail","kind":"connector","action":"connect","state":"initiated","connect_url":"https://connect.example/gmail"},
    {"name":"github","kind":"mcp","action":"install","state":"pending","required_env":[{"name":"GITHUB_TOKEN","required":true,"secret":true,"default":""}]}]}"""

/** A gateway whose session `s1` waits on the card of `op1`; it tells the test every call it gets. */
internal class StressGateway : WebSocketListener() {
    val calls = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    private val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Volatile
    private var socket: WebSocket? = null

    /** Whether a resumed session still has the card open. */
    @Volatile
    var pending = true

    fun methods(): List<String> = seen.toList()

    fun drop() {
        socket!!.close(1012, "restart")
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        val method = frame["method"]?.jsonPrimitive?.content ?: return
        val result = when (method) {
            "session.resume" ->
                if (pending) """{"session_id":"live1","stored_session_id":"s1","running":true,"pending_connection":$STRESS_REQUEST}"""
                else """{"session_id":"live1","stored_session_id":"s1","running":true}"""
            "connection.respond" -> """{"status":"ok","settled":false}"""
            else -> "{}"
        }
        webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
        seen += method
        calls.trySend(method to (frame["params"]?.jsonObject ?: JsonObject(emptyMap())))
    }
}
