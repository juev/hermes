package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.*
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.*
import org.evsyukov.hermes.data.server.*
import org.evsyukov.hermes.testing.ChatTestResources
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReviewOriginTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = LiveServer.database(context)
    private val resources = ChatTestResources()
    private val server = MockWebServer()
    private val strings = AppStrings(context.resources)
    private val frames = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val enqueues = CopyOnWriteArrayList<String>()
    private val created = AtomicInteger()
    private val resumed = AtomicInteger()
    private val terminalEntered = CountDownLatch(1)
    private val terminalRelease = CountDownLatch(1)
    private val renewalRelease = CompletableDeferred<Unit>()
    private lateinit var store: ServerStore
    private lateinit var session: ServerSession
    private lateinit var socket: GatewaySocket
    private lateinit var home: ServerProfile
    @Volatile private var titleError: Int? = null
    @Volatile private var closeError: Int? = null
    @Volatile private var promptError: Int? = null
    private val peer = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { peerSocket = webSocket; webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""") }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = HermesJson.parseToJsonElement(text).jsonObject
            val id = frame.string("id") ?: return
            val method = frame.string("method") ?: return
            val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
            frames += method to params
            val error = when (method) { "session.title" -> titleError; "session.close" -> closeError; "prompt.submit" -> promptError; else -> null }
            val result = when (method) {
                "session.create" -> """{"session_id":"draft${created.incrementAndGet()}","stored_session_id":"s1"}"""
                "session.resume" -> """{"session_id":"joined${resumed.incrementAndGet()}","stored_session_id":"s1","running":1,"info":{"model":"test"}}"""
                "session.active_list" -> """{"sessions":[{"id":"live","session_key":"s1"}]}"""
                else -> "{}"
            }
            webSocket.send(if (error != null) """{"jsonrpc":"2.0","id":"$id","error":{"code":$error,"message":"failure-$method"}}""" else """{"jsonrpc":"2.0","id":"$id","result":$result}""")
        }
    }
    @Before fun setUp() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/api/ws" -> MockResponse.Builder().webSocketUpgrade(peer).build()
                request.method == "PATCH" -> MockResponse.Builder().code(404).body("{}").build()
                request.url.encodedPath.endsWith("/messages") -> MockResponse.Builder().body("""{"messages":[]}""").build()
                else -> MockResponse.Builder().body("""{"id":"s1","title":"Original","last_active":10}""").build()
            }
        }
        server.start()
        home = ServerProfile("review-origin", "Review", server.url("/").toString())
        store = resources.store(context, object : TokenCipher { override fun encrypt(plain: String) = plain; override fun decrypt(sealed: String) = sealed }, scope, home)
        store.setTokens(home.id, Tokens("initial"))
        session = ServerSession(home, OkHttpClient(), store, strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        socket = resources.socket(GatewaySocket(session.http, scope, strings))
        socket.onRequestEnqueued = { enqueues += it }
        socket.start()
        withTimeout(10_000) { socket.state.first { it == SocketState.READY } }
    }
    @After fun tearDown() {
        val owner = scope.coroutineContext.job
        terminalRelease.countDown(); renewalRelease.complete(Unit)
        if (::socket.isInitialized) { socket.beforeTerminalCompletion = null; socket.beforeRequestAwait = null }
        resources.finish(owner, listOf("close server" to { server.close() }, "close Room" to { db.close() }))
    }
    private suspend fun loaded(): ChatController {
        val chat = ChatController(session, socket, scope, strings, "s1")
        withTimeout(10_000) { while (chat.loadCompletion == null) delay(5); chat.loadCompletion!!.await() }
        assertThat(chat.boundLiveId).isNotNull()
        chat.continuationProducers
        return chat
    }
    private suspend fun replaceAuthorization() {
        val prior = socket.transport.value
        store.setTokens(home.id, Tokens("replacement"))
        socket.start()
        withTimeout(10_000) { socket.state.first { it == SocketState.READY && socket.transport.value != prior } }
    }
    private suspend fun draftRace(owner: Boolean) {
        val chat = ChatController(session, socket, scope, strings, null)
        withTimeout(10_000) { while (chat.retainedDraftId == null) delay(5) }
        val oldId = chat.retainedDraftId!!
        val renewalEntered = CompletableDeferred<Unit>()
        chat.beforeDraftRenewal = { renewalEntered.complete(Unit); renewalRelease.await() }
        val prior = socket.transport.value
        if (owner) { store.setTokens(home.id, Tokens("fresh-draft")); socket.start() }
        else {
            // Actual wire loss drives the real reconnect loop, retaining this controller/socket.
            peerSocket!!.close(1012, "restart")
        }
        withTimeout(10_000) { renewalEntered.await(); socket.state.first { it == SocketState.READY && socket.transport.value != prior } }
        chat.continuationProducers
        chat.send("fresh empty-chat send", emptyList())
        val send = checkNotNull(chat.submitCompletion)
        withTimeout(10_000) { send.join() }
        assertThat(frames.filter { it.first == "session.close" }.map { it.second.string("session_id") }).doesNotContain(oldId)
        assertThat(frames.last { it.first == "prompt.submit" }.second.string("session_id")).isNotEqualTo(oldId)
        assertThat(chat.working.items.filterIsInstance<ChatItem.User>().last().delivery).isEqualTo(Delivery.SENT)
        renewalRelease.complete(Unit)
    }
    @Volatile private var peerSocket: WebSocket? = null
    @Test fun `S1 owner replacement never closes retained draft through fresh authority`() = runBlocking<Unit> { draftRace(true) }
    @Test fun `S1 transport replacement never closes retained draft on new transport`() = runBlocking<Unit> { draftRace(false) }
    @Test fun `S1 current empty draft is reused by its first send`() = runBlocking<Unit> {
        val chat = ChatController(session, socket, scope, strings, null)
        withTimeout(10_000) { while (chat.retainedDraftId == null) delay(5) }
        val id = chat.retainedDraftId
        chat.continuationProducers; chat.send("current send", emptyList())
        withTimeout(10_000) { checkNotNull(chat.submitCompletion).join() }
        assertThat(created.get()).isEqualTo(1)
        assertThat(frames.last { it.first == "prompt.submit" }.second.string("session_id")).isEqualTo(id)
    }
    @Test fun `L1 exceptional old 4001 cannot erase a replacement live binding`() = runBlocking<Unit> {
        val chat = loaded()
        promptError = 4001
        socket.beforeTerminalCompletion = { reply ->
            if ((reply["error"] as? JsonObject)?.long("code") == 4001L) {
                terminalEntered.countDown()
                check(terminalRelease.await(10, TimeUnit.SECONDS)) { "terminal handoff not released" }
            }
        }
        chat.send("old prompt", emptyList())
        val old = checkNotNull(chat.submitCompletion)
        assertThat(terminalEntered.await(10, TimeUnit.SECONDS)).isTrue()
        promptError = null
        replaceAuthorization()
        val first = chat.loadCompletion
        chat.reload()
        withTimeout(10_000) { while (chat.loadCompletion === first) delay(5); chat.loadCompletion!!.await() }
        val fresh = chat.boundLiveId
        val on = chat.joinedTransport
        assertThat(fresh).isNotNull()
        terminalRelease.countDown()
        withTimeout(10_000) { old.join() }
        assertThat(chat.boundLiveId).isEqualTo(fresh)
        assertThat(chat.joinedTransport).isEqualTo(on)
        val count = resumed.get()
        chat.send("fresh next prompt", emptyList())
        withTimeout(10_000) { checkNotNull(chat.submitCompletion).join() }
        assertThat(resumed.get()).isEqualTo(count)
        assertThat(frames.last { it.first == "prompt.submit" }.second.string("session_id")).isEqualTo(fresh)
    }
    @Test fun `L1 current 4001 rejoins before retrying its prompt`() = runBlocking<Unit> {
        val chat = loaded()
        val old = chat.boundLiveId
        promptError = 4001
        socket.beforeTerminalCompletion = { reply -> if (reply["error"] != null) promptError = null }
        chat.send("current recover", emptyList())
        withTimeout(10_000) { checkNotNull(chat.submitCompletion).join() }
        assertThat(resumed.get()).isEqualTo(2)
        assertThat(chat.boundLiveId).isNotEqualTo(old)
        assertThat(chat.working.items.filterIsInstance<ChatItem.User>().last().delivery).isEqualTo(Delivery.SENT)
    }
    @Test fun `L1 admitted old 4001 recovery cannot erase a newer binding on the same transport`() = runBlocking<Unit> {
        val chat = loaded()
        val initial = chat.boundLiveId
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val errors = AtomicInteger()
        promptError = 4001
        socket.beforeTerminalCompletion = { reply ->
            if (reply["error"] != null && errors.incrementAndGet() == 2) promptError = null
        }
        chat.beforeLiveRecovery = { if (first.compareAndSet(true, false)) { entered.complete(Unit); release.await() } }
        chat.send("earlier admitted 4001", emptyList())
        val earlier = checkNotNull(chat.submitCompletion)
        try {
            withTimeout(10_000) { entered.await() }
            val on = socket.transport.value
            chat.send("new current 4001 recovery", emptyList())
            val current = checkNotNull(chat.submitCompletion)
            withTimeout(10_000) { current.join() }
            val fresh = chat.boundLiveId
            assertThat(fresh).isNotNull()
            assertThat(fresh).isNotEqualTo(initial)
            assertThat(chat.joinedTransport).isEqualTo(on)
            val count = resumed.get()
            release.complete(Unit)
            withTimeout(10_000) { earlier.join() }
            assertThat(chat.boundLiveId).isEqualTo(fresh)
            assertThat(chat.joinedTransport).isEqualTo(on)
            chat.send("next current action", emptyList())
            withTimeout(10_000) { checkNotNull(chat.submitCompletion).join() }
            assertThat(resumed.get()).isEqualTo(count)
            assertThat(frames.last { it.first == "prompt.submit" }.second.string("session_id")).isEqualTo(fresh)
        } finally {
            release.complete(Unit); withTimeout(10_000) { earlier.join() }; chat.beforeLiveRecovery = null
        }
    }

    private fun mutations() = ChatMutations(session.chatFor(session.owner()), socket, { null }, { _, _ -> }, session.owner(), { session.holds(session.owner()) }, socket.transport.value)
    @Test fun `L2 temporary title primary retains independent close failure as suppressed`() = runBlocking<Unit> {
        titleError = 4100; closeError = 4101
        val error = runCatching { mutations().rename("s1", "Title") }.exceptionOrNull()
        assertThat(error).isInstanceOf(RpcException::class.java)
        assertThat((error as RpcException).code).isEqualTo(4100)
        assertThat(error.suppressed.map { (it as RpcException).code }).containsExactly(4101)
    }
    @Test fun `L2 cancelled temporary rename enqueues no close after title cancellation`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        socket.beforeRequestAwait = { if (it == "session.title") { entered.complete(Unit); release.await() } }
        val action = scope.async { mutations().rename("s1", "Cancelled") }
        try {
            withTimeout(10_000) { entered.await() }
            action.cancel(CancellationException("cancel title"))
            withTimeout(10_000) { action.join() }
            assertThat(action.isCancelled).isTrue()
            assertThat(enqueues.count { it == "session.title" }).isEqualTo(1)
            assertThat(enqueues).doesNotContain("session.close")
        } finally { release.complete(Unit); action.cancelAndJoin() }
    }
    private suspend fun cancelledController(rename: Boolean) {
        val chat = loaded()
        socket.beforeRequestAwait = { method -> if (method == if (rename) "session.title" else "session.interrupt") throw CancellationException("synthetic current action cancellation") }
        if (rename) chat.rename("Cancelled title") else chat.stop()
        val action = chat.continuationProducers.last()
        withTimeout(10_000) { action.join() }
        assertThat(action.isCancelled).isTrue()
        assertThat(chat.working.error).isNull()
        if (rename) assertThat(chat.working.title).isEqualTo("Original") else assertThat(chat.working.running).isTrue()
    }
    @Test fun `L2 current controller stop cancellation propagates without ending its turn`() = runBlocking<Unit> { cancelledController(false) }
    @Test fun `L2 current controller rename cancellation propagates without error publication`() = runBlocking<Unit> { cancelledController(true) }
    @Test fun `L2 successful interrupt rename and temporary fallback remain usable`() = runBlocking<Unit> {
        val chat = loaded(); chat.stop(); withTimeout(10_000) { chat.continuationProducers.last().join() }
        assertThat(chat.working.running).isFalse()
        chat.rename("Current title"); withTimeout(10_000) { chat.continuationProducers.last().join() }
        assertThat(chat.working.title).isEqualTo("Current title")
        mutations().rename("s1", "Temporary title")
        assertThat(frames.last { it.first == "session.close" }.second.string("session_id")).isEqualTo(frames.last { it.first == "session.resume" }.second.let { "joined${resumed.get()}" })
    }
}
