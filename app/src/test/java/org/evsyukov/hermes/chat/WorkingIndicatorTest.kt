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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class WorkingIndicatorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
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
        scope.cancel()
        server.close()
        db.close()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun chat(storedId: String?): ChatController {
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        return ChatController(session, GatewaySocket(session.http, scope, strings), scope, strings, storedId)
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
