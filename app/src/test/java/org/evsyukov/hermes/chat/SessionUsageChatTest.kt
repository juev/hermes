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
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.SessionUsage
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
class SessionUsageChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val strings = AppStrings(context.resources)

    /** Turns the server has finished; the stored totals grow with each. */
    private val turns = AtomicInteger()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/ws" -> MockResponse.Builder().webSocketUpgrade(OneTurnGateway(turns)).build()
                "/api/sessions/s1" -> json(
                    if (turns.get() == 0) {
                        """{"id":"s1","title":"Plans","input_tokens":100,"output_tokens":10,"estimated_cost_usd":0.01,"cost_status":"estimated"}"""
                    } else {
                        """{"id":"s1","title":"Plans","input_tokens":300,"cache_read_tokens":2000,"output_tokens":40,"estimated_cost_usd":0.05,"cost_status":"estimated"}"""
                    },
                )
                "/api/sessions/s1/messages" -> json("""{"messages":[{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"hi"}]}""")
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

    @Test
    fun `a chat shows what it spent and the totals of the server after each turn`() = runBlocking<Unit> {
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, GatewaySocket(session.http, scope, strings), scope, strings, "s1")

        val opened = withTimeout(10_000) { chat.state.first { !it.loading && it.usage != null } }
        assertThat(opened.usage).isEqualTo(SessionUsage(input = 100, output = 10, costUsd = 0.01, included = false))

        chat.send("more", emptyList())
        val after = withTimeout(10_000) { chat.state.first { it.usage?.output == 40L } }
        chat.close()

        assertThat(after.usage).isEqualTo(SessionUsage(input = 2300, output = 40, costUsd = 0.05, included = false))
        assertThat(after.running).isFalse()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/** A gateway that runs every prompt as a turn and finishes it at once. */
private class OneTurnGateway(private val turns: AtomicInteger) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        webSocket.send(event("gateway.ready", null, "{}"))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        when (frame["method"]?.jsonPrimitive?.content) {
            "session.resume" -> reply(webSocket, id, """{"session_id":"live1","stored_session_id":"s1","running":false}""")
            "prompt.submit" -> {
                reply(webSocket, id, """{"status":"streaming"}""")
                webSocket.send(event("message.start", "live1", "{}"))
                // The server writes the totals of the turn before it reports the turn complete.
                turns.incrementAndGet()
                webSocket.send(event("message.complete", "live1", """{"text":"done","status":"complete","usage":{"input":200,"output":30}}"""))
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
