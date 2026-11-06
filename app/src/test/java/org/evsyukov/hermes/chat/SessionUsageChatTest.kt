package org.evsyukov.hermes.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import org.evsyukov.hermes.testing.ChatTestResources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SessionUsageChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
    private val resources = ChatTestResources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val strings = AppStrings(context.resources)

    /** Turns the server has finished; the stored totals grow with each. */
    private val turns = AtomicInteger()

    private val observations = UsageObservations()
    private lateinit var gateway: OneTurnGateway

    @Before
    fun setUp() {
        gateway = OneTurnGateway(turns, observations = observations)
        server.dispatcher = dispatcher(gateway)
        server.start()
    }

    private fun dispatcher(gateway: OneTurnGateway) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
            "/api/ws" -> MockResponse.Builder().webSocketUpgrade(gateway).build()
            "/api/sessions/s1" -> json(
                if (turns.get() == 0) {
                    observations.detailBefore.incrementAndGet()
                    """{"id":"s1","title":"Plans","input_tokens":100,"output_tokens":10,"estimated_cost_usd":0.01,"cost_status":"estimated"}"""
                } else {
                    observations.detailAfter.incrementAndGet()
                    """{"id":"s1","title":"Plans","input_tokens":300,"cache_read_tokens":2000,"output_tokens":40,"estimated_cost_usd":0.05,"cost_status":"estimated"}"""
                },
            )
            "/api/sessions/s1/messages" -> json(observations.history.incrementAndGet().let { """{"messages":[{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"hi"}]}""" })
            else -> MockResponse.Builder().code(404).build()
        }
    }

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        resources.finish(owner, listOf("close server" to { server.close() }, "close database" to { db.close() }))
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `a chat shows what it spent and the totals of the server after each turn`() = runBlocking<Unit> {
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val socket = resources.socket(GatewaySocket(session.http, scope, strings))
        val chat = ChatController(session, socket, scope, strings, "s1")

        val opened = awaitStoredHistory(chat)
        assertThat(opened.usage).isEqualTo(SessionUsage(input = 100, output = 10, costUsd = 0.01, included = false))

        chat.send("more", emptyList())
        val after = try {
            awaitFinishedTurn(chat)
        } catch (timeout: TimeoutCancellationException) {
            // Observe only this original deadline, before cleanup. Independent reads are not atomic;
            // successful WebSocket.send means enqueued, not consumed by the controller.
            try {
                val state = chat.state.value
                val tail = state.items.takeLast(4).map { item ->
                    when (item) {
                        is ChatItem.Assistant -> "assistant(streaming=${item.streaming},length=${item.text.length},done=${item.text == "done"})"
                        else -> item.javaClass.simpleName
                    }
                }
                System.err.println("USAGE_DEADLINE output40=${state.usage?.output == 40L} idle=${!state.running} " +
                    "finished=${(state.items.lastOrNull() as? ChatItem.Assistant)?.streaming == false} " +
                    "usage=${state.usage} loading=${state.loading} running=${state.running} connecting=${state.connecting} " +
                    "hasError=${state.error != null} storedS1=${state.storedId == "s1"} tail=$tail " +
                    "socket=${socket.state.value} transport=${socket.transport.value} connections=${socket.connections.value} " +
                    "joined=${chat.joinedTransport} turns=${turns.get()} gatewayRunning=${gateway.running} " +
                    "rootActive=${scope.coroutineContext[Job]?.isActive} rootCompleted=${scope.coroutineContext[Job]?.isCompleted} " +
                    observations.snapshot())
            } catch (observation: Throwable) {
                timeout.addSuppressed(observation)
            }
            throw timeout
        }
        chat.close()

        assertThat(after.usage).isEqualTo(SessionUsage(input = 2300, output = 40, costUsd = 0.05, included = false))
        assertThat(after.running).isFalse()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `an automatic initial reread cannot consume the hold reserved for an explicit refresh`() = runBlocking<Unit> {
        val main = StandardTestDispatcher()
        val root = SupervisorJob(requireNotNull(scope.coroutineContext[Job]))
        val owner = CoroutineScope(root + main)
        val activeListAsked = CompletableDeferred<Unit>()
        val activeListRelease = CompletableDeferred<Unit>()
        val controlledGateway = OneTurnGateway(turns, scope = scope, activeListAsked = activeListAsked, activeListRelease = activeListRelease)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val hold = HistoryReadHold(dispatcher(controlledGateway), entered, release)
        server.dispatcher = hold
        val session = ServerSession(ServerProfile("home", "Home", server.url("/").toString()), OkHttpClient(), resources.store(context, PlainCipher, scope, ServerProfile("home", "Home", server.url("/").toString())), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val socket = resources.socket(GatewaySocket(session.http, scope, strings))
        val chat = ChatController(session, socket, owner, strings, "s1")
        val opening = owner.async { awaitStoredHistory(chat) }
        suspend fun pumpUntil(predicate: () -> Boolean) {
            withTimeout(10_000) { while (!predicate()) { main.scheduler.runCurrent(); yield() } }
        }
        preservingCleanup(body = {
            // The real load waits on its active-list response while the real socket counts generation1.
            pumpUntil { activeListAsked.isCompleted && socket.connections.value == 1 }
            main.scheduler.runCurrent() // Apply that connection to the still-loading controller.
            assertThat(chat.state.value.loading).isTrue()
            activeListRelease.complete(Unit)
            pumpUntil { hold.requests.get() >= 2 }
            main.scheduler.runCurrent()
            System.err.println("INITIAL_REREAD requests=${hold.requests.get()} held=${entered.isCompleted} " +
                "loading=${chat.state.value.loading} usage=${chat.state.value.usage} " +
                "connections=${socket.connections.value} joined=${chat.joinedTransport} openingDone=${opening.isCompleted}")
            // With the old ordinal hold, the first false-loading publication was conflated with
            // reread loading=true. Exercise that SAME initial helper's original 10-second deadline.
            if (!entered.isCompleted) pumpUntil { opening.isCompleted }
            main.scheduler.advanceTimeBy(10_000)
            main.scheduler.runCurrent()
            val initial = opening.await()
            assertThat(initial.error).isNull()
            assertThat(initial.usage).isEqualTo(SessionUsage(input = 100, output = 10, costUsd = 0.01, included = false))
            assertThat(entered.isCompleted).isFalse()
            val initialRequests = hold.requests.get()
            hold.arm()
            chat.reload()
            pumpUntil { entered.isCompleted }
            assertThat(hold.requests.get()).isEqualTo(initialRequests + 1)
            assertThat(chat.state.value.loading).isTrue()
            release.countDown()
            val refreshed = owner.async { awaitStoredHistory(chat) }
            pumpUntil { refreshed.isCompleted }
            assertThat(refreshed.await().error).isNull()
        }, cleanup = {
            activeListRelease.complete(Unit)
            release.countDown()
            preservingCleanup(body = {
                // Positive release uses the very same initial helper, not a copied predicate.
                val released = owner.async { awaitStoredHistory(chat) }
                pumpUntil { released.isCompleted }
                val settled = released.await()
                assertThat(settled.error).isNull()
                assertThat(settled.usage).isEqualTo(SessionUsage(input = 100, output = 10, costUsd = 0.01, included = false))
                System.err.println("INITIAL_REREAD_RELEASED loading=${settled.loading} error=${settled.error != null}")
            }, cleanup = {
                preservingCleanup(body = { chat.close() }, cleanup = {
                    withContext(NonCancellable) {
                        root.cancel()
                        withTimeout(10_000) { while (!root.isCompleted) { main.scheduler.runCurrent(); yield() } }
                    }
                })
            })
        })
    }

    @Test
    fun `a history read begun before a turn cannot erase its completed answer`() = runBlocking<Unit> {
        val historyStarted = CompletableDeferred<Unit>()
        val releaseHistory = java.util.concurrent.CountDownLatch(1)
        val historyRead = CompletableDeferred<Unit>()
        val base = dispatcher(gateway)
        val historyHold = HistoryReadHold(base, historyStarted, releaseHistory)
        server.dispatcher = historyHold
        val session = ServerSession(ServerProfile("home", "Home", server.url("/").toString()), OkHttpClient(), resources.store(context, PlainCipher, scope, ServerProfile("home", "Home", server.url("/").toString())), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        lateinit var chat: ChatController
        var ownedChat: ChatController? = null
        preservingCleanup(body = {
            chat = openHistoryChat(session) {
                if (historyStarted.isCompleted && !chat.state.value.loading) historyRead.complete(Unit)
            }
            ownedChat = chat
            val initialReads = historyHold.requests.get()
            historyHold.arm()
            chat.reload()
            withTimeout(10_000) { historyStarted.await() }
            assertThat(historyHold.requests.get()).isEqualTo(initialReads + 1)
            assertThat(chat.state.value.loading).isTrue()
            System.err.println("ARMED_HISTORY initialReady=true initialReads=$initialReads heldRead=${historyHold.requests.get()} turns=${turns.get()}")
            chat.send("more", emptyList())
            val completed = awaitFinishedTurn(chat)
            assertThat((completed.items.last() as ChatItem.Assistant).text).isEqualTo("done")
            releaseHistory.countDown()
            withTimeout(10_000) { historyRead.await() }
            assertThat((chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.text).isEqualTo("done")
            System.err.println("ARMED_HISTORY_COMPLETED retainedDone=true")
        }, cleanup = {
            releaseHistory.countDown()
            ownedChat?.close()
        })
    }

    @Test
    fun `a history refresh without concurrent transcript changes still replaces history`() = runBlocking<Unit> {
        val base = dispatcher(gateway)
        val changed = java.util.concurrent.atomic.AtomicBoolean()
        val refreshedRequest = java.util.concurrent.atomic.AtomicBoolean()
        val read = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (changed.get() && request.url.encodedPath.endsWith("/messages")) {
                    refreshedRequest.set(true)
                    json("""{"messages":[{"id":9,"role":"assistant","content":"refreshed"}]}""")
                } else base.dispatch(request)
        }
        val session = ServerSession(ServerProfile("home", "Home", server.url("/").toString()), OkHttpClient(), resources.store(context, PlainCipher, scope, ServerProfile("home", "Home", server.url("/").toString())), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, resources.socket(GatewaySocket(session.http, scope, strings)), scope, strings, "s1", onRead = { _, _, _ -> if (refreshedRequest.get()) read.complete(Unit) })
        try {
            awaitStoredHistory(chat)
            changed.set(true)
            chat.reload()
            withTimeout(10_000) { read.await() }
            assertThat(chat.state.value.items).hasSize(1)
            assertThat((chat.state.value.items.single() as ChatItem.Assistant).text).isEqualTo("refreshed")
        } finally { chat.close() }
    }

    @Test
    fun `a history refresh cannot erase a streaming answer changed during its read`() = runBlocking<Unit> {
        val completion = CompletableDeferred<Unit>()
        val producer = CoroutineScope(SupervisorJob(requireNotNull(scope.coroutineContext[Job])) + Dispatchers.IO)
        val heldGateway = OneTurnGateway(turns, completion, producer)
        val base = dispatcher(heldGateway)
        val historyStarted = CompletableDeferred<Unit>()
        val releaseHistory = java.util.concurrent.CountDownLatch(1)
        val historyRead = CompletableDeferred<Unit>()
        val historyHold = HistoryReadHold(base, historyStarted, releaseHistory)
        server.dispatcher = historyHold
        val session = ServerSession(ServerProfile("home", "Home", server.url("/").toString()), OkHttpClient(), resources.store(context, PlainCipher, scope, ServerProfile("home", "Home", server.url("/").toString())), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        lateinit var chat: ChatController
        var ownedChat: ChatController? = null
        preservingCleanup(body = {
            chat = openHistoryChat(session) {
                if (historyStarted.isCompleted && !chat.state.value.loading) historyRead.complete(Unit)
            }
            ownedChat = chat
            val initialReads = historyHold.requests.get()
            historyHold.arm()
            chat.reload()
            withTimeout(10_000) { historyStarted.await() }
            assertThat(historyHold.requests.get()).isEqualTo(initialReads + 1)
            assertThat(chat.state.value.loading).isTrue()
            System.err.println("ARMED_HISTORY initialReady=true initialReads=$initialReads heldRead=${historyHold.requests.get()} turns=${turns.get()}")
            chat.send("more", emptyList())
            withTimeout(10_000) { chat.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true } }
            releaseHistory.countDown()
            withTimeout(10_000) { historyRead.await() }
            assertThat((chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.streaming).isTrue()
            System.err.println("ARMED_HISTORY_STREAMING retainedStreaming=true")
            completion.complete(Unit)
            val after = awaitFinishedTurn(chat)
            assertThat((after.items.last() as ChatItem.Assistant).text).isEqualTo("done")
        }, cleanup = {
            releaseHistory.countDown()
            completion.complete(Unit)
            preservingCleanup(body = {
                withContext(NonCancellable) { withTimeout(10_000) { requireNotNull(producer.coroutineContext[Job]).cancelAndJoin() } }
            }, cleanup = { ownedChat?.close() })
        })
    }

    @Test
    fun `a held turn is not a finished one even when its totals are stored`() = runBlocking<Unit> {
        val completion = CompletableDeferred<Unit>()
        val gatewayScope = CoroutineScope(SupervisorJob(requireNotNull(scope.coroutineContext[Job])) + Dispatchers.IO)
        val gateway = OneTurnGateway(turns, completion, gatewayScope)
        server.dispatcher = dispatcher(gateway)
        val profile = ServerProfile("home", "Home", server.url("/").toString())
        val session = ServerSession(profile, OkHttpClient(), resources.store(context, PlainCipher, scope, profile), strings, ResponseCache(db.responses(), scope, { Long.MAX_VALUE }))
        val chat = ChatController(session, resources.socket(GatewaySocket(session.http, scope, strings)), scope, strings, "s1")
        try {
            val opened = awaitStoredHistory(chat)
            assertThat(opened.usage).isEqualTo(SessionUsage(input = 100, output = 10, costUsd = 0.01, included = false))

            chat.send("more", emptyList())
            // The controller has consumed message.start: the answer is on the screen and still streaming.
            withTimeout(10_000) {
                chat.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true }
            }
            assertThat(turns.get()).isEqualTo(1)

            // A real usage publisher reads the stored totals while the completion is still withheld.
            chat.reload()
            val running = withTimeout(10_000) {
                chat.state.first { it.usage?.output == 40L && it.running }
            }
            assertThat(running.usage).isEqualTo(SessionUsage(input = 2300, output = 40, costUsd = 0.05, included = false))

            // The stored totals alone are not the end of the turn: while the completion is
            // withheld the finished-turn barrier must not complete.
            assertThat(withTimeoutOrNull(1_000) { awaitFinishedTurn(chat) }).isNull()

            completion.complete(Unit)
            val after = awaitFinishedTurn(chat)
            chat.close()
            assertThat(after.usage).isEqualTo(SessionUsage(input = 2300, output = 40, costUsd = 0.05, included = false))
            assertThat(after.running).isFalse()
            val answer = after.items.last() as ChatItem.Assistant
            assertThat(answer.streaming).isFalse()
            assertThat(answer.text).isEqualTo("done")
        } finally {
            completion.complete(Unit)
            try {
                withContext(NonCancellable) {
                    withTimeout(10_000) { requireNotNull(gatewayScope.coroutineContext[Job]).cancelAndJoin() }
                }
            } finally {
                chat.close()
            }
        }
    }

    private suspend fun openHistoryChat(session: ServerSession, onRead: () -> Unit): ChatController {
        var created: ChatController? = null
        try {
            return withTimeout(10_000) {
                val socket = resources.socket(GatewaySocket(session.http, scope, strings))
                socket.awaitReady()
                socket.connections.first { it > 0 }
                // Construct after the initial generation is counted. ChatController treats that
                // generation as its starting connection, so it cannot schedule an initial rejoin
                // underneath the history-read hold. This fixture reports no active session yet.
                val chat = ChatController(session, socket, scope, strings, "s1", onRead = { _, _, _ -> onRead() })
                created = chat
                val opened = awaitStoredHistory(chat)
                assertThat(opened.error).isNull()
                assertThat(opened.usage).isEqualTo(SessionUsage(input = 100, output = 10, costUsd = 0.01, included = false))
                chat
            }
        } catch (failure: Throwable) {
            try { created?.close() } catch (cleanup: Throwable) {
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private suspend fun awaitStoredHistory(chat: ChatController): ChatState = withTimeout(10_000) {
        chat.state.first { !it.loading && it.usage != null }
    }

    /** Preserve a body failure if releasing/joining its real fixture also fails. */
    private suspend fun <T> preservingCleanup(body: suspend () -> T, cleanup: suspend () -> Unit): T {
        var primary: Throwable? = null
        try { return body() } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { cleanup() } catch (failure: Throwable) {
                val first = primary
                if (first == null) throw failure
                if (first !== failure) first.addSuppressed(failure)
            }
        }
    }

    /**
     * The end of a turn: the stored totals, the running flag and the answer the controller finished
     * from `message.complete`. The totals alone can be published by a reload before the completion
     * is consumed, so they do not tell a finished turn from a held one.
     */
    private suspend fun awaitFinishedTurn(chat: ChatController): ChatState = withTimeout(10_000) {
        chat.state.first { state ->
            state.usage?.output == 40L &&
                !state.running &&
                (state.items.lastOrNull() as? ChatItem.Assistant)?.streaming == false
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}

/**
 * A gateway that runs every prompt as a turn. Without [completion] it finishes the turn at once;
 * with a gate it records the turn and sends `message.complete` only after the gate opens, so the
 * socket reader keeps serving other calls while the turn is held.
 */
private class OneTurnGateway(
    private val turns: AtomicInteger,
    private val completion: CompletableDeferred<Unit>? = null,
    private val scope: CoroutineScope? = null,
    private val observations: UsageObservations = UsageObservations(),
    private val activeListAsked: CompletableDeferred<Unit>? = null,
    private val activeListRelease: CompletableDeferred<Unit>? = null,
) : WebSocketListener() {
    @Volatile
    var running = false
        private set
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        webSocket.send(event("gateway.ready", null, "{}"))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = HermesJson.parseToJsonElement(text).jsonObject
        val id = frame["id"]?.jsonPrimitive?.content ?: return
        when (frame["method"]?.jsonPrimitive?.content) {
            "session.active_list" -> {
                activeListAsked?.complete(Unit)
                val release = activeListRelease
                if (release == null) reply(webSocket, id, "{}")
                else requireNotNull(scope).launch { release.await(); reply(webSocket, id, "{}") }
            }
            "session.resume" -> {
                observations.resume.incrementAndGet()
                if (reply(webSocket, id, """{"session_id":"live1","stored_session_id":"s1","running":$running}""")) observations.resumeSent.incrementAndGet()
            }
            "prompt.submit" -> {
                observations.submit.incrementAndGet()
                if (reply(webSocket, id, """{"status":"streaming"}""")) observations.submitSent.incrementAndGet()
                observations.start.incrementAndGet()
                if (webSocket.send(event("message.start", "live1", "{}"))) observations.startSent.incrementAndGet()
                // The server writes the totals of the turn before it reports the turn complete.
                running = true
                turns.incrementAndGet()
                val held = completion
                if (held == null) {
                    complete(webSocket)
                } else {
                    requireNotNull(scope).launch {
                        held.await()
                        complete(webSocket)
                    }
                }
            }
            else -> reply(webSocket, id, "{}")
        }
    }

    private fun complete(webSocket: WebSocket) {
        running = false
        observations.complete.incrementAndGet()
        if (webSocket.send(event("message.complete", "live1", """{"text":"done","status":"complete","usage":{"input":200,"output":30}}"""))) observations.completeSent.incrementAndGet()
    }

    private fun reply(webSocket: WebSocket, id: String, result: String): Boolean =
        webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")

    private fun event(type: String, sessionId: String?, payload: String): String {
        val session = sessionId?.let { ""","session_id":"$it"""" }.orEmpty()
        return """{"jsonrpc":"2.0","method":"event","params":{"type":"$type"$session,"payload":$payload}}"""
    }
}

/** Passive synthetic fixture metadata; no additional observer, readiness or scheduling gate. */
private class UsageObservations {
    val resume = AtomicInteger()
    val resumeSent = AtomicInteger()
    val submit = AtomicInteger()
    val submitSent = AtomicInteger()
    val start = AtomicInteger()
    val startSent = AtomicInteger()
    val complete = AtomicInteger()
    val completeSent = AtomicInteger()
    val detailBefore = AtomicInteger()
    val detailAfter = AtomicInteger()
    val history = AtomicInteger()
    fun snapshot(): String = "resume=${resume.get()}/${resumeSent.get()} submit=${submit.get()}/${submitSent.get()} " +
        "start=${start.get()}/${startSent.get()} complete=${complete.get()}/${completeSent.get()} " +
        "detailBefore=${detailBefore.get()} detailAfter=${detailAfter.get()} history=${history.get()}"
}

/** Captures and holds one response only after the test has established initial history and armed it. */
private class HistoryReadHold(
    private val base: Dispatcher,
    private val entered: CompletableDeferred<Unit>,
    private val release: java.util.concurrent.CountDownLatch,
) : Dispatcher() {
    val requests = AtomicInteger()
    private val armed = AtomicBoolean()
    fun arm() {
        check(!entered.isCompleted && armed.compareAndSet(false, true))
    }
    override fun dispatch(request: RecordedRequest): MockResponse {
        val response = base.dispatch(request)
        if (request.url.encodedPath.endsWith("/messages")) {
            requests.incrementAndGet()
            if (!armed.compareAndSet(true, false)) return response
            entered.complete(Unit)
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        return response
    }
}
