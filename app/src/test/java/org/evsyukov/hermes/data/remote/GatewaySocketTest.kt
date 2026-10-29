package org.evsyukov.hermes.data.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import org.evsyukov.hermes.data.auth.Tokens
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class GatewaySocketTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeGateway()
    private var socket: GatewaySocket? = null

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        socket?.stop()
        scope.cancel()
        server.close()
    }

    private fun socket(
        firstPause: Long = 1_000,
        probeMillis: Long = 500,
        tokens: suspend () -> Tokens? = { null },
        saveTokens: suspend (Tokens?) -> Unit = {},
    ): GatewaySocket {
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = tokens, saveTokens = saveTokens, strings = strings)
        return GatewaySocket(http, scope, strings, firstPause, probeMillis).also { socket = it }
    }

    private fun upgrade() = MockResponse.Builder().webSocketUpgrade(gateway).build()

    @Test
    fun `a healthy socket survives the app coming to the front, with its call in flight`() = runBlocking {
        repeat(2) { server.enqueue(upgrade()) }
        val socket = socket()
        socket.awaitReady()
        val answer = async { socket.call("test.hold", timeoutMillis = 10_000) }
        val (ws, id) = withTimeout(5_000) { gateway.held.receive() }

        socket.checkAlive()
        // Longer than the probe: an answered probe leaves the socket as it is.
        delay(1_000)
        ws.send(result(id, """{"ok":true}"""))

        assertThat(answer.await().jsonObject["ok"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(gateway.sockets).hasSize(1)
        assertThat(socket.state.value).isEqualTo(SocketState.READY)
    }

    @Test
    fun `a socket that went silent is replaced at once when the app comes to the front`() = runBlocking {
        repeat(2) { server.enqueue(upgrade()) }
        // A pause longer than the test: only the probe may bring the second connection.
        val socket = socket(firstPause = 30_000, probeMillis = 500)
        settled(socket)
        // The first connection answers nothing any more, the way a socket over a lost network does.
        gateway.silenced += gateway.sockets.single()

        socket.checkAlive()

        withTimeout(5_000) { until { gateway.sockets.size == 2 && socket.state.value == SocketState.READY } }
    }

    @Test
    fun `a socket that drops while it is checked reconnects at once`() = runBlocking {
        repeat(2) { server.enqueue(upgrade()) }
        val socket = socket(firstPause = 30_000)
        settled(socket)
        // The server forgot the socket while the app was away; the ping finds the connection gone.
        gateway.dropOnPing += gateway.sockets.single()

        socket.checkAlive()

        withTimeout(5_000) { until { gateway.sockets.size == 2 && socket.state.value == SocketState.READY } }
    }

    @Test
    fun `a gateway without the heartbeat is not pinged, its socket stays`() = runBlocking {
        gateway.heartbeat = false
        repeat(2) { server.enqueue(upgrade()) }
        val socket = socket(probeMillis = 500)
        settled(socket)
        gateway.silenced += gateway.sockets.single()

        socket.checkAlive()
        delay(1_000)

        assertThat(gateway.methods).doesNotContain("gateway.ping")
        assertThat(gateway.sockets).hasSize(1)
        assertThat(socket.state.value).isEqualTo(SocketState.READY)
    }

    @Test
    fun `a socket waiting out its pause reconnects at once when asked`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(upgrade())
        val socket = socket(firstPause = 60_000)
        socket.start()
        withTimeout(5_000) { until { server.requestCount == 1 && socket.state.value == SocketState.DISCONNECTED } }

        socket.checkAlive()

        withTimeout(5_000) { until { socket.state.value == SocketState.READY } }
        assertThat(gateway.sockets).hasSize(1)
    }

    @Test
    fun `a caller that asks right after the start gets the first connection`() = runBlocking {
        server.enqueue(upgrade())
        // The connect loop runs on a busy thread, so the caller gets to wait before the loop begins.
        val loop = Executors.newSingleThreadExecutor()
        val busy = CountDownLatch(1)
        loop.execute { busy.await() }
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings)
        val socket = GatewaySocket(http, CoroutineScope(SupervisorJob() + loop.asCoroutineDispatcher()), strings).also { this@GatewaySocketTest.socket = it }
        try {
            val ready = async(Dispatchers.IO) { socket.awaitReady() }
            delay(200)
            busy.countDown()

            withTimeout(5_000) { ready.await() }
            assertThat(socket.state.value).isEqualTo(SocketState.READY)
        } finally {
            socket.stop()
            loop.shutdownNow()
        }
    }

    @Test
    fun `a socket nobody started stays closed`() = runBlocking {
        server.enqueue(upgrade())
        val socket = socket()

        socket.checkAlive()
        delay(500)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(socket.state.value).isEqualTo(SocketState.DISCONNECTED)
    }

    @Test
    fun `a stop fails the callers waiting for the connection at once`() = runBlocking {
        // Nothing enqueued: the server holds the upgrade, the way a lost network does, so no close
        // of the socket ever reaches its listener.
        val socket = socket()
        val ready = async(Dispatchers.IO) { runCatching { socket.awaitReady() } }
        withTimeout(5_000) { until { server.requestCount == 1 } }

        socket.stop()

        val outcome = withTimeout(2_000) { ready.await() }
        assertThat(outcome.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a ready that is handled after the stop does not bring the socket back`() = runBlocking {
        gateway.autoReady = false
        repeat(2) { server.enqueue(upgrade()) }
        // The socket's own coroutines run on one thread the test can hold.
        val loop = Executors.newSingleThreadExecutor()
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        val http = HermesHttp(server.url("/"), OkHttpClient(), tokens = { null }, saveTokens = {}, strings = strings)
        val socket = GatewaySocket(http, CoroutineScope(SupervisorJob() + loop.asCoroutineDispatcher()), strings).also { this@GatewaySocketTest.socket = it }
        try {
            socket.start()
            withTimeout(5_000) { until { gateway.sockets.size == 1 } }
            val held = CountDownLatch(1)
            loop.execute { held.await() }
            // The ready arrives, and its handling waits behind the held thread until the socket is stopped.
            gateway.sockets.single().send(READY)
            delay(300)
            socket.stop()
            held.countDown()
            delay(1_000)

            assertThat(socket.state.value).isEqualTo(SocketState.DISCONNECTED)
            assertThat(gateway.sockets).hasSize(1)
        } finally {
            loop.shutdownNow()
        }
    }

    @Test
    fun `a connection that drops before it settles announces no ready and starts no heartbeat`() = runBlocking {
        // The gateway says it is ready but leaves the capabilities unanswered, then the connection drops.
        gateway.holdCapabilities = true
        server.enqueue(upgrade())
        // A pause longer than the test: the next attempt does not come in between.
        val socket = socket(firstPause = 30_000)
        socket.start()
        withTimeout(5_000) { until { socket.state.value == SocketState.READY && "client.capabilities" in gateway.methods } }

        gateway.sockets.single().close(1001, "going away")
        withTimeout(5_000) { until { socket.state.value == SocketState.DISCONNECTED } }
        delay(500)

        assertThat(socket.ready.replayCache).isEmpty()
        assertThat(socket.heartbeatRunning).isFalse()
    }

    @Test
    fun `a stopped socket stays closed when a late owner starts it`() = runBlocking {
        repeat(2) { server.enqueue(upgrade()) }
        val socket = socket(firstPause = 50)
        settled(socket)
        // The connection was rebuilt; its collector had read the old one just before.
        socket.stop()

        socket.start()
        val call = runCatching { withTimeout(2_000) { socket.call("test.anything") } }
        delay(500)

        assertThat(call.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(gateway.sockets).hasSize(1)
        assertThat(socket.state.value).isEqualTo(SocketState.DISCONNECTED)
    }

    @Test
    fun `a stop racing a start leaves no connect loop behind`() = runBlocking {
        val attempts = AtomicInteger()
        // Every attempt fails at once and counts, so a loop left running keeps counting.
        val client = OkHttpClient.Builder().addInterceptor { attempts.incrementAndGet(); throw IOException("refused") }.build()
        val strings = AppStrings(ApplicationProvider.getApplicationContext<Application>().resources)
        val http = HermesHttp(server.url("/"), client, tokens = { null }, saveTokens = {}, strings = strings)
        val threads = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val socket = GatewaySocket(http, scope, strings, firstPause = 20)
                val barrier = CyclicBarrier(2)
                val started = threads.submit { barrier.await(); socket.start() }
                val stopped = threads.submit { barrier.await(); socket.stop() }
                started.get()
                stopped.get()
            }
            delay(300)
            val settled = attempts.get()
            delay(500)

            assertThat(attempts.get()).isEqualTo(settled)
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    fun `a socket whose sign-in is over stops trying until it is started again`() = runBlocking {
        server.enqueue(upgrade())
        // Run out, and nothing to refresh them with.
        var tokens: Tokens? = Tokens("expired", expiresAt = 1)
        val socket = socket(firstPause = 50, tokens = { tokens }, saveTokens = { tokens = it })

        val failed = runCatching { socket.awaitReady() }
        // Many pauses of the loop.
        delay(500)

        assertThat(failed.exceptionOrNull()).isInstanceOf(SignInRequiredException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(socket.state.value).isEqualTo(SocketState.DISCONNECTED)

        // The tokens are gone; this server lets the app in without them, and the next start connects.
        socket.awaitReady()
        assertThat(gateway.sockets).hasSize(1)
    }

    /** Ready, and done with the calls that follow the gateway's ready, so no late answer counts as a pong. */
    private suspend fun settled(socket: GatewaySocket) {
        socket.awaitReady()
        withTimeout(5_000) { socket.ready.first() }
    }

    private suspend fun until(condition: () -> Boolean) {
        while (!condition()) delay(20)
    }
}

private const val READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}"""

private fun result(id: String, json: String = "{}") = """{"jsonrpc":"2.0","id":"$id","result":$json}"""

/**
 * The dashboard's end of `/api/ws`: says it is ready, with the heartbeat unless told otherwise,
 * answers every call at once except `test.hold`, which waits for the test, answers nothing on a
 * silenced connection and drops a connection marked so when it is pinged.
 */
private class FakeGateway : WebSocketListener() {
    val sockets = CopyOnWriteArrayList<WebSocket>()
    val silenced: MutableSet<WebSocket> = Collections.newSetFromMap(ConcurrentHashMap())
    val dropOnPing: MutableSet<WebSocket> = Collections.newSetFromMap(ConcurrentHashMap())
    val held = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
    val methods = CopyOnWriteArrayList<String>()

    @Volatile
    var heartbeat = true

    /** On: `client.capabilities` goes unanswered. */
    @Volatile
    var holdCapabilities = false

    /** Off: the test sends the ready itself. */
    @Volatile
    var autoReady = true

    override fun onOpen(webSocket: WebSocket, response: Response) {
        sockets += webSocket
        if (autoReady) webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":$heartbeat}}}""")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket in silenced) return
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        val method = frame["method"]?.jsonPrimitive?.content ?: return
        methods += method
        if (method == "gateway.ping" && webSocket in dropOnPing) return webSocket.cancel()
        if (method == "client.capabilities" && holdCapabilities) return
        if (method == "test.hold") held.trySend(webSocket to id) else webSocket.send(result(id))
    }
}
