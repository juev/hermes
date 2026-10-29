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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DraftSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = RecordingGateway()
    private val strings = AppStrings(context.resources)
    private lateinit var socket: GatewaySocket

    @Before
    fun setUp() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(gateway).build())
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun newChat(): ChatController {
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        socket = GatewaySocket(session.http, scope, strings)
        return ChatController(session, socket, scope, strings, null)
    }

    /** The params of the next call of [method]; other calls before it are skipped. */
    private suspend fun next(method: String): JsonObject = withTimeout(5_000) {
        var call = gateway.calls.receive()
        while (call.first != method) call = gateway.calls.receive()
        call.second
    }

    @Test
    fun `an empty new chat has its session before the first message, which goes into it`() = runBlocking<Unit> {
        val chat = newChat()
        socket.start()
        next("session.create")
        assertThat(chat.state.value.storedId).isNull()

        chat.send("hello", emptyList())
        val prompt = next("prompt.submit")
        val sent = withTimeout(5_000) { chat.state.first { it.storedId != null } }
        chat.close()

        assertThat(prompt.string("session_id")).isEqualTo("live1")
        assertThat(sent.storedId).isEqualTo("s1")
        assertThat(gateway.created.get()).isEqualTo(1)
    }

    @Test
    fun `leaving an empty new chat closes its session`() = runBlocking<Unit> {
        val chat = newChat()
        socket.start()
        next("session.create")

        chat.close()

        assertThat(next("session.close").string("session_id")).isEqualTo("live1")
    }

    @Test
    fun `a model picked in an empty chat gets a session of its own`() = runBlocking<Unit> {
        val chat = newChat()
        socket.start()
        next("session.create")

        chat.chooseModel("openrouter", "big-model")
        val closed = next("session.close")
        val created = next("session.create")
        chat.send("hello", emptyList())
        val prompt = next("prompt.submit")
        chat.close()

        assertThat(closed.string("session_id")).isEqualTo("live1")
        assertThat(created.string("provider")).isEqualTo("openrouter")
        assertThat(created.string("model")).isEqualTo("big-model")
        assertThat(prompt.string("session_id")).isEqualTo("live2")
    }

    @Test
    fun `a new chat does not open the socket itself`() = runBlocking<Unit> {
        val chat = newChat()
        delay(500)
        chat.close()

        assertThat(server.requestCount).isEqualTo(0)
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/** A gateway that is ready at once, numbers the sessions it creates and tells the test every call it gets. */
private class RecordingGateway : WebSocketListener() {
    val calls = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    val created = AtomicInteger()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        val method = frame["method"]?.jsonPrimitive?.content ?: return
        val result = if (method == "session.create") {
            val n = created.incrementAndGet()
            """{"session_id":"live$n","stored_session_id":"s$n"}"""
        } else {
            "{}"
        }
        webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
        calls.trySend(method to (frame["params"]?.jsonObject ?: JsonObject(emptyMap())))
    }
}
