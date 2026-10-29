package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesJson
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ConnectionChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = ConnectingGateway()
    private val strings = AppStrings(context.resources)

    @Before
    fun setUp() {
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
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    /** A chat with its turn waiting on the card of `op1`, opened afresh. */
    private suspend fun openChat(): ChatController {
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, GatewaySocket(session.http, scope, strings), scope, strings, "s1")
        withTimeout(10_000) { chat.state.first { it.connections.isNotEmpty() } }
        return chat
    }

    /** The params of the next call of [method]; other calls before it are skipped. */
    private suspend fun next(method: String): JsonObject = withTimeout(5_000) {
        var call = gateway.calls.receive()
        while (call.first != method) call = gateway.calls.receive()
        call.second
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
        delay(500)
        chat.close()

        assertThat(gateway.methods()).containsNoneOf("connection.respond", "connectors.connect")
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

/** A gateway whose session `s1` waits on the card of `op1`; it tells the test every call it gets. */
private class ConnectingGateway : WebSocketListener() {
    val calls = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    private val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Volatile
    private var socket: WebSocket? = null

    fun methods(): List<String> = seen.toList()

    fun send(type: String, sessionId: String, payload: String) {
        socket!!.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$sessionId","payload":$payload}}""")
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
            "session.resume" -> """{"session_id":"live1","stored_session_id":"s1","running":true,"pending_connection":$REQUEST}"""
            "connection.respond" -> """{"status":"ok","settled":false}"""
            "connectors.operation.wake" -> """{"status":"ok"}"""
            "connectors.operation.status" ->
                """{"op_id":"op1","seq":3,"deadline_at":1800000000,"settled":false,"targets":[{"name":"gmail","kind":"connector","action":"connect","state":"connected","alias":"work"}]}"""
            else -> "{}"
        }
        webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
        seen += method
        calls.trySend(method to (frame["params"]?.jsonObject ?: JsonObject(emptyMap())))
    }
}
