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
import kotlinx.coroutines.delay
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
import org.evsyukov.hermes.data.remote.cacheKey
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A chat started in the app is opened again from what the phone keeps: the turn that made it read
 * its stored history into the cache, so the second open does not wait for the server with an empty
 * screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class NewChatCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })
    private val strings = AppStrings(context.resources)

    private val messagesKey = cacheKey("api/sessions/s1/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false))

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { server.close() }
        db.close()
    }

    private fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/ws" -> MockResponse.Builder().webSocketUpgrade(NewChatGateway).build()
                "/api/sessions/s1" -> json("""{"id":"s1","title":"New","last_active":10,"is_active":true}""")
                "/api/sessions/s1/messages" -> json("""{"messages":[{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"hi"}]}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun controller(storedId: String?, url: String = server.url("/").toString()): ChatController {
        val profile = ServerProfile("home", "Home", url)
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, cache)
        return ChatController(session, GatewaySocket(session.http, scope, strings), scope, strings, storedId)
    }

    @Test
    fun `the first turn of a new chat puts its history on the phone`() = runBlocking<Unit> {
        startServer()
        val chat = controller(null)

        chat.send("hello", emptyList())
        withTimeout(10_000) { while (cache.read("home", messagesKey) == null) delay(20) }
        val kept = cache.read("home", messagesKey)
        assertThat(kept).contains("hello")
        assertThat(kept).contains("hi")
        chat.close()
    }

    @Test
    fun `a chat opened again with the server gone shows the history kept on the phone`() = runBlocking<Unit> {
        startServer()
        val url = server.url("/").toString()
        val chat = controller(null)
        chat.send("hello", emptyList())
        withTimeout(10_000) { while (cache.read("home", messagesKey) == null) delay(20) }
        chat.close()
        server.close()

        // The server is gone: only the phone has the chat now.
        val again = controller("s1", url)
        val failed = withTimeout(10_000) { again.state.first { it.error != null } }
        again.close()

        assertThat(failed.items.filterIsInstance<ChatItem.User>().map { it.text }).containsExactly("hello")
        assertThat(failed.items.filterIsInstance<ChatItem.Assistant>().single().text).isEqualTo("hi")
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/** A gateway that builds the session of a new chat and finishes its first turn at once. */
private object NewChatGateway : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        webSocket.send(event("gateway.ready", null, "{}"))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        when (frame["method"]?.jsonPrimitive?.content) {
            "session.create", "session.resume" ->
                reply(webSocket, id, """{"session_id":"live1","stored_session_id":"s1","running":false,"info":{"model":"m"}}""")
            "prompt.submit" -> {
                reply(webSocket, id, """{"status":"streaming"}""")
                webSocket.send(event("message.start", "live1", "{}"))
                webSocket.send(event("message.complete", "live1", """{"text":"hi","status":"complete"}"""))
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
