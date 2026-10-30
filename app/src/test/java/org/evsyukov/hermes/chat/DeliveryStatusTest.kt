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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.GatewayChannel
import org.evsyukov.hermes.data.remote.GatewayEvent
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.RpcException
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.SocketState
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the app says after a submission: the server took it, refused it, or the answer never came.
 * The protocol has no client message id and no idempotency key on `prompt.submit`, so an unknown
 * outcome is never retried on its own; the message stays in the chat and says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DeliveryStatusTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val gateway = DeliveryGateway()
    private val strings = AppStrings(context.resources)

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/sessions/s1" -> json("""{"id":"s1","title":"Mail","is_active":true,"last_active":1}""")
                "/api/sessions/s1/messages" -> json("""{"messages":[{"id":1,"role":"user","content":"earlier"}]}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private suspend fun openChat(): ChatController {
        startServer()
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), ServerStore(context, PlainCipher, scope), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, gateway, scope, strings, "s1")
        withTimeout(10_000) { chat.state.first { it.model != null && !it.loading } }
        return chat
    }

    private fun ChatController.lastUser(): ChatItem.User? = state.value.items.lastOrNull { it is ChatItem.User } as? ChatItem.User

    @Test
    fun `a submission the server took is marked sent`() = runBlocking<Unit> {
        val chat = openChat()

        chat.send("hello", emptyList())
        val sent = withTimeout(5_000) { chat.state.first { s -> (s.items.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.delivery == Delivery.SENT } }
        chat.close()

        assertThat(sent.error).isNull()
        assertThat(gateway.prompts.get()).isEqualTo(1)
    }

    @Test
    fun `a lost answer says the delivery is unknown, keeps the message and does not retry`() = runBlocking<Unit> {
        val chat = openChat()
        gateway.failure = IOException("the answer was lost")

        chat.send("hello", emptyList())
        val unknown = withTimeout(5_000) {
            chat.state.first { s -> (s.items.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.delivery == Delivery.UNKNOWN }
        }
        assertThat(unknown.items.filterIsInstance<ChatItem.User>().last().text).isEqualTo("hello")
        assertThat(unknown.error).isEqualTo(strings.get(R.string.error_send_unknown))
        assertThat(gateway.prompts.get()).isEqualTo(1)

        // A reconnect loads the chat again; the app still does not send the prompt a second time.
        gateway.drop()
        gateway.connect("again")
        delay(500)
        chat.close()
        assertThat(gateway.prompts.get()).isEqualTo(1)
    }

    @Test
    fun `a submission the server refused is marked not sent`() = runBlocking<Unit> {
        val chat = openChat()
        gateway.failure = RpcException(4002, "no such session", null)

        chat.send("hello", emptyList())
        val failed = withTimeout(5_000) {
            chat.state.first { s -> (s.items.lastOrNull { it is ChatItem.User } as? ChatItem.User)?.delivery == Delivery.FAILED }
        }
        chat.close()

        assertThat(failed.error).isEqualTo("no such session")
        assertThat(failed.items.filterIsInstance<ChatItem.User>().last().text).isEqualTo("hello")
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/** A connected gateway whose `prompt.submit` either works or fails the way [failure] says. */
private class DeliveryGateway : GatewayChannel {
    private val _state = MutableStateFlow(SocketState.READY)
    override val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<GatewayEvent> = _events

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 4)
    override val requests: SharedFlow<ServerRequest> = _requests

    private val _connections = MutableStateFlow(1)
    override val connections: StateFlow<Int> = _connections

    private val _ready = MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 4)
    override val ready: SharedFlow<String?> = _ready

    val prompts = AtomicInteger()

    /** The exception `prompt.submit` throws; null when the server takes it. */
    @Volatile
    var failure: Exception? = null

    fun drop() {
        _state.value = SocketState.DISCONNECTED
    }

    fun connect(epoch: String) {
        _state.value = SocketState.READY
        _connections.update { it + 1 }
        _ready.tryEmit(epoch)
    }

    override suspend fun awaitReady() {
        if (_state.value != SocketState.READY) throw IOException("no connection")
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        if (_state.value != SocketState.READY) throw IOException("no connection")
        return when (method) {
            "session.resume" -> HermesJson.parseToJsonElement(
                """{"session_id":"live1","stored_session_id":"s1","running":false,"info":{"model":"m"}}""",
            ).jsonObject
            "prompt.submit" -> {
                prompts.incrementAndGet()
                failure?.let { throw it }
                HermesJson.parseToJsonElement("""{"status":"streaming"}""").jsonObject
            }
            else -> buildJsonObject { }
        }
    }

    override fun respond(id: String, result: JsonElement?, error: String?) = Unit
}
