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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class ConnectingChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = SlowGateway()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    @Test
    fun `a message sent before the socket is ready shows that the app is connecting`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(gateway).build())
        val strings = AppStrings(context.resources)
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, GatewaySocket(session.http, scope, strings), scope, strings, null)

        chat.send("hello", emptyList())
        withTimeout(5_000) { chat.state.first { it.connecting } }
        withTimeout(5_000) { gateway.opened.receive() }.send(READY)
        val sent = withTimeout(5_000) { chat.state.first { it.storedId == "s1" } }
        withTimeout(5_000) { gateway.submitted.receive() }
        chat.close()

        assertThat(sent.connecting).isFalse()
        assertThat(sent.running).isTrue()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

private const val READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""

/** A gateway that says it is ready only when the test tells it to, then creates the session and takes the prompt. */
private class SlowGateway : WebSocketListener() {
    val opened = Channel<WebSocket>(Channel.UNLIMITED)
    val submitted = Channel<Unit>(Channel.UNLIMITED)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.trySend(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        val method = frame["method"]?.jsonPrimitive?.content ?: return
        val result = if (method == "session.create") """{"session_id":"live1","stored_session_id":"s1"}""" else "{}"
        webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
        if (method == "prompt.submit") submitted.trySend(Unit)
    }
}
