package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.evsyukov.hermes.testing.FixtureCleanup
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A chat that is not on the screen keeps every event of its turn on the phone: the words and the
 * reasoning go to a buffer per piece of the answer instead of rebuilding the transcript for each delta,
 * nothing is published for the screen, and shown again the chat publishes its state once without
 * asking the server anything. The gateway is a fake, so no test waits for a socket.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class HiddenChatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: ResponseCache
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val strings = AppStrings(context.resources)

    /** What the chat told the read mark, in order. */
    private val reads = ConcurrentLinkedQueue<Pair<String, Double>>()
    private val readOwners = ConcurrentLinkedQueue<org.evsyukov.hermes.data.server.WorkOwner>()

    /** The persisted profile the chats are built on; a session has no authority without one. */
    private lateinit var home: ServerProfile
    private lateinit var store: ServerStore

    @Before
    fun setUp() {
        try {
            db.openHelper.writableDatabase
            cache = ResponseCache(db.responses(), scope, { Long.MAX_VALUE })
            cache.producers
            home = ServerProfile("hidden-chat-home", "Home", server.http.url("/").toString())
            store = ServerStore(context, PlainCipher, scope)
            runBlocking { store.save(home, makeActive = true) }
            setupFailure?.let { throw it }
        } catch (primary: Throwable) {
            try { tearDown() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
            throw primary
        }
    }

    private var serverClosed = false
    internal val fixtureDependenciesClosed: Boolean get() = !fixtureDatabaseOpen && serverClosed
    internal val fixtureCleanup = FixtureCleanup()
    internal var setupFailure: RuntimeException? = null
    internal var resetFailure: RuntimeException? = null
    @Volatile internal var resetWriteFailure: Exception? = null
    @Volatile internal var resetReadFailure: Exception? = null
    @Volatile private var resetting = false
    internal var resetResults = mutableListOf<org.evsyukov.hermes.data.server.NativeResult>()
    internal var closeFailure: RuntimeException? = null
    internal val fixtureDatabaseOpen: Boolean get() = db.isOpen
    internal fun fixtureSeedReset() = Unit
    internal fun fixtureRemoveResetTarget() = runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(store, listOf(home.id)) }
    internal fun fixtureProducerScope(): CoroutineScope = scope

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        fixtureCleanup.finish(
            listOf(owner),
            beforeJoin = listOf("release gates" to { server.releaseGates() }, "reset profile" to {
                resetFailure?.let { throw it }
                if (::store.isInitialized) { resetting = true; try { runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(store, listOf(home.id)) { resetResults += it } } } finally { resetting = false } }
            }),
            afterJoin = listOf(
                "close server" to { server.close(); serverClosed = true },
                "close Room" to { db.close() },
                "report close failure" to { closeFailure?.let { throw it } },
            ),
        )
    }

    private lateinit var lastSession: ServerSession

    private fun chat(visible: Boolean = true): ChatController {
        lastSession = ServerSession(home, OkHttpClient(), store, strings, cache)
        return ChatController(
            lastSession, gateway, scope, strings, "s1",
            onRead = { id, at, owner -> reads += id to at; readOwners += owner }, visible = visible,
        ).also { it.continuationProducers }
    }

    private val PlainCipher = object : TokenCipher {
        override fun encrypt(plain: String): String { if (resetting) resetWriteFailure?.let { throw it }; return plain }
        override fun decrypt(sealed: String): String { if (resetting) resetReadFailure?.let { throw it }; return sealed }
    }

    private fun answer(state: ChatState) = state.items.lastOrNull() as? ChatItem.Assistant

    private suspend fun ChatController.until(timeoutMillis: Long = 5_000, condition: (ChatState) -> Boolean): ChatState =
        withTimeout(timeoutMillis) { state.first(condition) }

    /** A chat opened on a turn under way: "Hello" streamed before it opened, " there" while it is on the screen. */
    private suspend fun openMidTurn(): ChatController {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hello")
        val chat = chat()
        chat.until { answer(it)?.text == "Hello" && !it.loading }
        gateway.delta(" there")
        chat.until { answer(it)?.text == "Hello there" }
        // Opening the chat read it; what follows is about the answers that come after.
        withTimeout(5_000) { while (reads.isEmpty()) delay(10) }
        reads.clear()
        return chat
    }

    /** Every state the chat publishes from now on, as it publishes it. */
    private fun ChatController.publications(): MutableList<ChatState> {
        val seen = Collections.synchronizedList(mutableListOf<ChatState>())
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { state.drop(1).collect { seen += it } }
        return seen
    }

    /** Items without keys and times, to compare what two chats show. */
    private fun shape(items: List<ChatItem>) = items.map { item ->
        when (item) {
            is ChatItem.User -> "user:${item.text}"
            is ChatItem.CronReport -> "cron:${item.text}"
            is ChatItem.Assistant -> "assistant:${item.streaming}:${item.error}:" + item.parts.joinToString("|") { part ->
                when (part) {
                    is Part.Text -> "text=${part.text}"
                    is Part.Reasoning -> "reasoning=${part.text}"
                    is Part.Tool -> "tool=${part.run.id}:${part.run.done}"
                }
            }
        }
    }


    private suspend fun awaitLoad(chat: ChatController, previous: kotlinx.coroutines.CompletableDeferred<Unit>? = null) {
        withTimeout(10_000) {
            while (chat.loadCompletion == null || chat.loadCompletion === previous) delay(5)
            chat.loadCompletion!!.await()
        }
    }


    @Test fun `D rejoin queued during held old history cannot start a retired second load`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("old accepted turn")
        val hold = server.holdHistory()
        val chat = chat(visible = false)
        try {
            assertThat(hold.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            withTimeout(10_000) { while (chat.joinedTransport != gateway.transport.value) delay(5) }
            gateway.drop()
            gateway.connect()
            withTimeout(10_000) { while (!chat.pendingRejoin) delay(5) }
            val old = lastSession.owner()
            lastSession.signOut(home.id)
            assertThat(store.authority.holds(home.id, old.generation)).isFalse()
            val histories = server.requests.count { it.url.encodedPath.endsWith("/messages") }
            hold.release.countDown()
            withTimeout(10_000) { while (chat.completedLoadCycles.get() == 0L) delay(5) }
            assertThat(server.requests.count { it.url.encodedPath.endsWith("/messages") }).isEqualTo(histories)
            chat.setVisible(true)
            assertThat(readOwners.toList().filter { it != old }).isEmpty()
        } finally {
            hold.release.countDown()
        }
    }


    @Test fun `D stale rejoin arrival after fresh queued reload does not displace fresh work`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("old accepted turn")
        val history = server.holdHistory()
        val chat = chat(visible = false)
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handled = kotlinx.coroutines.CompletableDeferred<Unit>()
        chat.beforeReconnectEnqueue = { reached.complete(Unit); release.await() }
        chat.afterReconnectEnqueue = { handled.complete(Unit) }
        try {
            assertThat(history.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            withTimeout(10_000) { while (chat.joinedTransport != gateway.transport.value) delay(5) }
            gateway.drop()
            gateway.connect()
            withTimeout(10_000) { reached.await() }
            store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("fresh-queued-owner"))
            val fresh = store.authority.owner(home.id)
            gateway.activity(17.0)
            chat.reload()
            release.complete(Unit)
            withTimeout(10_000) { handled.await() }
            history.release.countDown()
            withTimeout(10_000) { while (chat.completedLoadCycles.get() == 0L) delay(5) }
            chat.setVisible(true)
            assertThat(reads.toList()).containsExactly("s1" to 17.0)
            assertThat(readOwners.toList()).containsExactly(fresh)
            assertThat(server.requests.count { it.url.encodedPath.endsWith("/messages") }).isEqualTo(2)
        } finally {
            release.complete(Unit)
            history.release.countDown()
            chat.beforeReconnectEnqueue = null
            chat.afterReconnectEnqueue = null
        }
    }

    private suspend fun submitContinuation(stale: Boolean) {
        gateway.connect()
        val chat = chat()
        awaitLoad(chat)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        gateway.beforePrompt = {
            if (first.compareAndSet(true, false)) {
                entered.complete(Unit)
                release.await()
                throw java.io.IOException("synthetic earlier submit failure")
            }
        }
        chat.send("earlier submit", emptyList())
        val earlier = chat.continuationProducers.last()
        try {
            withTimeout(10_000) { entered.await() }
            if (stale) {
                store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("fresh-submit-owner"))
                val prior = chat.loadCompletion
                chat.reload()
                awaitLoad(chat, prior)
            } else {
                release.complete(Unit)
                withTimeout(10_000) { earlier.join() }
                assertThat(chat.working.error).isEqualTo(strings.get(org.evsyukov.hermes.R.string.error_send_unknown))
                assertThat(chat.working.items.filterIsInstance<ChatItem.User>().last().delivery).isEqualTo(Delivery.UNKNOWN)
            }
            chat.send("fresh submit", emptyList())
            val current = chat.continuationProducers.last()
            withTimeout(10_000) { current.join() }
            assertThat(gateway.calls.count { it.first == "prompt.submit" }).isEqualTo(2)
            assertThat(chat.working.items.filterIsInstance<ChatItem.User>().last().delivery).isEqualTo(Delivery.SENT)
            assertThat(chat.working.running).isTrue()
            release.complete(Unit)
            withTimeout(10_000) { earlier.join() }
            gateway.event("session.info", buildJsonObject { put("running", 0) })
            gateway.deliver()
            assertThat(chat.working.error).isNull()
            assertThat(chat.working.running).isTrue()
        } finally {
            release.complete(Unit)
            withTimeout(10_000) { earlier.join() }
            gateway.beforePrompt = null
        }
    }

    @Test fun `F retired submit error cannot clear a fresh turn start latch`() = runBlocking<Unit> { submitContinuation(true) }
    @Test fun `F current submitted turn ignores idle before its start`() = runBlocking<Unit> { submitContinuation(false) }

    @Test fun `F old accepted turn completion never starts fresh-owner usage or warm reads`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("old accepted turn")
        val chat = chat(visible = false)
        awaitLoad(chat)
        store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("new-turn-owner"))
        val before = server.requests.size
        gateway.delta("old turn answer")
        gateway.complete()
        gateway.deliver()
        withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() } }
        assertThat(server.requests.size).isEqualTo(before)
    }

    @Test fun `F close warm queued under old owner cannot read or keep newer bytes`() = runBlocking<Unit> {
        gateway.connect()
        val chat = chat(visible = false)
        awaitLoad(chat)
        withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        chat.beforeCloseWarm = { entered.complete(Unit); release.await() }
        chat.close()
        try {
            withTimeout(10_000) { entered.await() }
            store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("new-close-owner"))
            cache.forget(home.id)
            val before = server.requests.size
            release.complete(Unit)
            withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() } }
            withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
            assertThat(server.requests.size).isEqualTo(before)
            assertThat(cache.size()).isEqualTo(0L)
        } finally {
            release.complete(Unit)
            chat.beforeCloseWarm = null
        }
    }

    @Test fun `F valid close warm keeps exact history body for its origin`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("current close question")
        gateway.delta("distinct current close answer")
        gateway.complete()
        val chat = chat(visible = false)
        awaitLoad(chat)
        withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
        cache.forget(home.id)
        chat.close()
        withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() }; cache.producers.toList().forEach { it.join() } }
        val key = org.evsyukov.hermes.data.remote.cacheKey("api/sessions/s1/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false))
        assertThat(cache.read(home.id, key)).isEqualTo(gateway.storedHistory())
    }

    @Test fun `F summary and history admitted before renewal cannot publish old load state`() = runBlocking<Unit> {
        gateway.connect()
        server.inputTokens = 999
        val hold = server.holdSummary()
        val chat = chat(visible = false)
        try {
            assertThat(hold.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("new-load-owner"))
            cache.forget(home.id)
            hold.release.countDown()
            awaitLoad(chat)
            withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
            assertThat(chat.working.title).isEmpty()
            assertThat(chat.working.usage).isNull()
            assertThat(cache.size()).isEqualTo(0L)
        } finally {
            hold.release.countDown()
        }
    }


    @Test fun `F delayed old history cannot apply transcript or read after renewal`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("old history question")
        gateway.delta("distinct retired history answer")
        gateway.complete()
        val hold = server.holdHistory()
        val chat = chat(visible = false)
        try {
            assertThat(hold.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("fresh-history-owner"))
            withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
            cache.forget(home.id)
            hold.release.countDown()
            awaitLoad(chat)
            withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
            assertThat(chat.working.items).isEmpty()
            chat.setVisible(true)
            assertThat(reads).isEmpty()
            assertThat(cache.size()).isEqualTo(0L)
        } finally {
            hold.release.countDown()
        }
    }

    private suspend fun pendingReadOwners(old: Double, fresh: Double, lateStale: Boolean = false) {
        gateway.connect()
        gateway.activity(old)
        if (lateStale) gateway.start("old accepted turn")
        val chat = chat(visible = false)
        awaitLoad(chat)
        val first = chat.loadCompletion
        assertThat(reads).isEmpty()
        val stale = if (lateStale) server.holdSummary() else null
        try {
            if (stale != null) {
                gateway.activity(old)
                gateway.complete()
                assertThat(stale.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            }
            store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("fresh-hidden-token"))
            val freshOwner = store.authority.owner(home.id)
            gateway.activity(fresh)
            chat.reload()
            awaitLoad(chat, first)
            stale?.release?.countDown()
            withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() } }
            chat.setVisible(true)
            assertThat(reads.toList()).containsExactly("s1" to fresh)
            assertThat(readOwners.toList()).containsExactly(freshOwner)
        } finally {
            stale?.release?.countDown()
        }
    }

    @Test fun `E fresh lower hidden read replaces stale higher owner`() = runBlocking<Unit> {
        pendingReadOwners(100.0, 20.0)
    }
    @Test fun `E equal hidden read replaces stale owner`() = runBlocking<Unit> {
        pendingReadOwners(20.0, 20.0)
    }
    @Test fun `E late stale usage cannot displace fresh hidden pair`() = runBlocking<Unit> {
        pendingReadOwners(100.0, 20.0, lateStale = true)
    }
    @Test fun `E same owner hidden reads preserve their maximum`() = runBlocking<Unit> {
        gateway.connect()
        gateway.activity(100.0)
        val chat = chat(visible = false)
        awaitLoad(chat)
        val first = chat.loadCompletion
        gateway.activity(20.0)
        chat.reload()
        awaitLoad(chat, first)
        chat.setVisible(true)
        assertThat(reads.toList()).containsExactly("s1" to 100.0)
        assertThat(readOwners.toList()).containsExactly(store.authority.owner(home.id))
    }

    @Test fun `F delayed usage cannot replace a freshly loaded owner summary`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("accepted old turn")
        server.inputTokens = 100
        val chat = chat(visible = false)
        awaitLoad(chat)
        val first = chat.loadCompletion
        server.inputTokens = 999
        val stale = server.holdSummary()
        try {
            gateway.complete()
            assertThat(stale.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            store.setTokens(home.id, org.evsyukov.hermes.data.auth.Tokens("fresh-usage-token"))
            server.inputTokens = 7
            chat.reload()
            awaitLoad(chat, first)
            assertThat(chat.working.usage!!.input).isEqualTo(7)
            stale.release.countDown()
            withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() } }
            assertThat(chat.working.usage!!.input).isEqualTo(7)
        } finally {
            stale.release.countDown()
        }
    }

    @Test fun `F current completed turn applies exact usage and retained history`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("accepted current turn")
        val chat = chat(visible = false)
        awaitLoad(chat)
        server.inputTokens = 333
        gateway.delta("fresh current answer")
        gateway.complete()
        gateway.deliver()
        withTimeout(10_000) {
            while (chat.continuationProducers.isEmpty()) delay(5)
            chat.continuationProducers.toList().forEach { it.join() }
        }
        assertThat(chat.working.usage!!.input).isEqualTo(333)
        chat.setVisible(true)
        assertThat(answer(chat.state.value)!!.text).contains("fresh current answer")
        assertThat(reads.last()).isEqualTo("s1" to gateway.lastActive)
    }

    @Test
    fun `2000 hidden deltas rebuild nothing and publish nothing, and the return publishes all of them once`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value
        val working = chat.working.items
        val calls = gateway.calls.size
        val requests = server.http.requestCount

        repeat(2_000) { gateway.delta(" w$it") }
        gateway.deliver()

        // Neither the screen's copy nor the chat's own transcript was rebuilt for a delta.
        assertThat(chat.state.value).isSameInstanceAs(published)
        assertThat(chat.working.items).isSameInstanceAs(working)

        val seen = chat.publications()
        chat.setVisible(true)

        assertThat(seen).hasSize(1)
        assertThat(answer(seen.single())!!.text).isEqualTo("Hello there" + (0 until 2_000).joinToString("") { " w$it" })
        assertThat(answer(seen.single())!!.streaming).isTrue()
        // The return asked the server nothing; the turn goes on streaming once.
        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
        gateway.delta("!")
        assertThat(answer(chat.until { answer(it)?.text?.endsWith("!") == true })!!.text).endsWith(" w1999!")
    }

    @Test
    fun `turns that run while hidden end up as the chat on the screen shows them`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hello")
        val shown = chat()
        val hidden = chat()
        shown.until { answer(it)?.text == "Hello" && !it.loading }
        hidden.until { answer(it)?.text == "Hello" && !it.loading }
        hidden.setVisible(false)

        // The open turn: reasoning, words and a tool between them, then its end.
        repeat(50) { gateway.reasoning("r$it ") }
        repeat(50) { gateway.delta(" a$it") }
        gateway.tool("t1", "terminal")
        repeat(50) { gateway.reasoning("s$it ") }
        repeat(50) { gateway.delta(" b$it") }
        gateway.complete()
        // A turn longer than the gateway's replay ring, with tools inside it.
        gateway.start("long")
        repeat(LiveGateway.RING) { gateway.delta(" c$it") }
        gateway.tool("t2", "terminal")
        gateway.persistRound()
        repeat(LiveGateway.RING) { gateway.delta(" d$it") }
        gateway.tool("t3", "terminal")
        gateway.complete()
        // A third turn, still running.
        gateway.start("more")
        repeat(100) { gateway.delta(" e$it") }
        gateway.deliver()
        // The usage of the finished turns is read as each ends, as on the screen; then everything is quiet.
        delay(300)
        val calls = gateway.calls.size
        val requests = server.http.requestCount

        hidden.setVisible(true)

        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
        assertThat(shape(hidden.state.value.items)).isEqualTo(shape(shown.state.value.items))
        val answers = hidden.state.value.items.filterIsInstance<ChatItem.Assistant>()
        assertThat(answers).hasSize(3)
        val long = answers[1].parts
        assertThat(long.filterIsInstance<Part.Tool>().map { it.run.id }).containsExactly("t2", "t3").inOrder()
        assertThat(long.filterIsInstance<Part.Text>().joinToString("") { it.text })
            .isEqualTo((0 until LiveGateway.RING).joinToString("") { " c$it" } + (0 until LiveGateway.RING).joinToString("") { " d$it" })
        assertThat(answers[0].parts.filterIsInstance<Part.Reasoning>().map { it.text })
            .containsExactly((0 until 50).joinToString("") { "r$it " }, (0 until 50).joinToString("") { "s$it " }).inOrder()
        assertThat(answers[2].streaming).isTrue()
        assertThat(hidden.state.value.running).isTrue()
    }

    @Test
    fun `a turn that ended while hidden is whole on return, and only then is read`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value

        gateway.delta(" done")
        gateway.complete()
        gateway.deliver()
        // The chat knows the turn is over; the screen's copy does not, and nothing was read.
        withTimeout(5_000) { while (chat.working.running) delay(10) }
        delay(300)
        assertThat(chat.state.value).isSameInstanceAs(published)
        assertThat(reads).isEmpty()

        chat.setVisible(true)
        val back = chat.state.value
        withTimeout(5_000) { while (reads.isEmpty()) delay(10) }

        assertThat(back.running).isFalse()
        assertThat(answer(back)!!.text).isEqualTo("Hello there done")
        assertThat(answer(back)!!.streaming).isFalse()
        assertThat(back.items.count { it is ChatItem.Assistant }).isEqualTo(1)
        assertThat(reads.last()).isEqualTo("s1" to gateway.lastActive)
    }

    @Test
    fun `an error of a hidden turn is there on return, whole`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val message = "quota exceeded: " + "x".repeat(2_000)

        gateway.delta(" and")
        gateway.fail(message)
        gateway.deliver()
        chat.setVisible(true)
        val back = chat.state.value

        assertThat(back.error).isEqualTo(message)
        assertThat(back.running).isFalse()
        assertThat(answer(back)!!.text).isEqualTo("Hello there and")
    }

    @Test
    fun `questions asked and cancelled while hidden stand on return as the socket left them`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value

        gateway.ask("r1", "clarify", questions("Which file?"))
        gateway.deliverRequests()
        gateway.event("request.cancel", buildJsonObject { put("id", "r1") })
        gateway.deliver()
        gateway.ask("r2", "clarify", questions("Which branch?"))
        gateway.deliverRequests()
        gateway.delta(" waiting")
        gateway.deliver()

        // The chat holds r2 open and r1 closed while hidden; the screen's copy is unchanged.
        assertThat(chat.working.questions.map { it.id to it.state }).containsExactly("r1" to QuestionState.CLOSED, "r2" to QuestionState.PENDING).inOrder()
        assertThat(chat.state.value).isSameInstanceAs(published)
        chat.setVisible(true)
        val back = chat.state.value

        assertThat(back.questions.single { it.state == QuestionState.PENDING }.questions.single().text).isEqualTo("Which branch?")
        assertThat(answer(back)!!.text).isEqualTo("Hello there waiting")
    }

    @Test
    fun `a card opened while hidden is there on return`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)

        gateway.event("connection.request", HermesJson.parseToJsonElement(CARD).jsonObject)
        gateway.deliver()
        chat.setVisible(true)

        assertThat(chat.state.value.connections.single().opId).isEqualTo("op9")
    }

    @Test
    fun `stopping a hidden turn keeps every word it streamed`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        repeat(100) { gateway.delta(" s$it") }
        gateway.deliver()

        chat.stop()
        withTimeout(5_000) { while (chat.working.running) delay(10) }
        chat.setVisible(true)
        val stopped = answer(chat.state.value)!!

        assertThat(stopped.interrupted).isTrue()
        assertThat(stopped.text).isEqualTo("Hello there" + (0 until 100).joinToString("") { " s$it" })
    }

    @Test
    fun `a reconnect while hidden joins again and converges, and the return asks nothing more`() = runBlocking<Unit> {
        val chat = openMidTurn()
        chat.setVisible(false)
        val published = chat.state.value

        gateway.drop()
        gateway.lostDelta(" lost")
        gateway.connect()
        // The session is joined at once, so its events keep coming to the app while it is hidden; the
        // join is applied once its answer's turn is in the transcript.
        withTimeout(5_000) { while (gateway.count("session.resume") < 2 || answer(chat.working)?.text != "Hello there lost") delay(10) }
        gateway.delta(" after")
        gateway.ask("r1", "clarify", questions("Go on?"))
        gateway.deliverRequests()
        gateway.deliver()
        delay(300)
        assertThat(reads).isEmpty()
        assertThat(chat.state.value).isSameInstanceAs(published)
        val calls = gateway.calls.size

        chat.setVisible(true)
        val back = chat.state.value
        withTimeout(5_000) { while (reads.isEmpty()) delay(10) }

        assertThat(answer(back)!!.text).isEqualTo("Hello there lost after")
        assertThat(back.items.count { it is ChatItem.Assistant }).isEqualTo(1)
        assertThat(back.questions.single().id).isEqualTo("r1")
        assertThat(gateway.calls.size).isEqualTo(calls)
    }

    @Test
    fun `a chat shown in the middle of its load gets the rest of it on the screen`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hel")
        server.historyDelayMillis = 500
        // Opened behind a screen, as the app does after a cold start from a notification.
        val chat = chat(visible = false)
        withTimeout(5_000) { while (gateway.count("session.resume") < 1) delay(10) }
        assertThat(chat.state.value.items).isEmpty()

        chat.setVisible(true)
        chat.until { answer(it)?.text == "Hel" && !it.loading }
        gateway.delta("lo")
        val back = chat.until { answer(it)?.text == "Hello" }

        assertThat(back.items.count { it is ChatItem.Assistant }).isEqualTo(1)
    }

    @Test
    fun `a chat hidden in the middle of its load keeps the screen as it was and shows the end on return`() = runBlocking<Unit> {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hel")
        server.historyDelayMillis = 500
        val chat = chat()
        chat.until { it.loading }

        chat.setVisible(false)
        val published = chat.state.value
        withTimeout(5_000) { while (answer(chat.working)?.text != "Hel") delay(10) }
        gateway.delta("lo")
        gateway.deliver()
        assertThat(chat.state.value).isSameInstanceAs(published)
        val calls = gateway.calls.size

        chat.setVisible(true)
        val back = chat.state.value

        assertThat(back.loading).isFalse()
        assertThat(answer(back)!!.text).isEqualTo("Hello")
        assertThat(gateway.calls.size).isEqualTo(calls)
    }

    @Test
    fun `a chat hidden and shown with nothing new does no work`() = runBlocking<Unit> {
        val chat = openMidTurn()
        val before = chat.state.value
        val calls = gateway.calls.size
        val requests = server.http.requestCount
        val seen = chat.publications()

        chat.setVisible(false)
        chat.setVisible(true)
        delay(300)

        assertThat(chat.state.value).isSameInstanceAs(before)
        assertThat(seen).isEmpty()
        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
    }
}

private const val CARD = """{"op_id":"op9","seq":1,"deadline_at":1800000000,"timeout_seconds":600,"tool_call_id":"call_9","targets":[
    {"name":"gmail","kind":"connector","action":"connect","state":"initiated","connect_url":"https://connect.example/gmail"}]}"""
