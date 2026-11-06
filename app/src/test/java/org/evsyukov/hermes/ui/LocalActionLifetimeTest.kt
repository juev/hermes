package org.evsyukov.hermes.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.CachedResponse
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.cache.ResponseDao
import org.evsyukov.hermes.data.cache.ResponsePart
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.cacheKey
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerState
import org.evsyukov.hermes.testing.FixtureCleanup
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What the phone may still write after the user signs out of a server or removes it. A response or a
 * refresh that started under the old session must not put anything back after the cleanup, while the
 * server that replaced it and a session built afterwards keep working. Room and DataStore are real;
 * caller-scope cancellation models disposal here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LocalActionLifetimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val main = Executors.newSingleThreadExecutor { Thread(it, "lifetime-test-main") }
    private val mainDispatcher = main.asCoroutineDispatcher()
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Answers the loopback address for a native sign-in, as the browser would. */
    private val browserScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gates = CopyOnWriteArrayList<Gate>()
    private lateinit var db: CacheDatabase
    private lateinit var dao: GatedDao
    private lateinit var cache: ResponseCache
    private lateinit var container: AppContainer
    private lateinit var vm: MainViewModel
    private val alphaServer = FakeServer()
    private val betaServer = FakeServer()
    /** Profiles this method seeded, removed in teardown so the shared store starts clean. */
    private val ownProfiles = mutableSetOf<String>()
    private var alphaStarted = false
    private var betaStarted = false
    private var mainInstalled = false
    private var testCipher: TestCipher? = null

    @Before
    fun setUp() {
        try {
            try {
                WorkManager.initialize(context, Configuration.Builder().build())
            } catch (error: IllegalStateException) {
                if (error.message?.contains("already initialized") != true) throw error
            }
            Dispatchers.setMain(mainDispatcher)
            mainInstalled = true
            db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).allowMainThreadQueries().build()
            db.openHelper.writableDatabase
            dao = GatedDao(db.responses())
            cache = ResponseCache(dao, cacheScope, { Long.MAX_VALUE })
            cache.producers
            alphaServer.start()
            alphaStarted = true
            setupFailure?.let { throw it }
            betaServer.start()
            betaStarted = true
        } catch (primary: Throwable) {
            try { tearDown() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
            throw primary
        }
    }

    internal fun fixtureSeedReset() = runBlocking<Unit> {
        val resetCipher = object : TokenCipher {
            override fun encrypt(plain: String): String { if (resetting) resetWriteFailure?.let { throw it }; return plain }
            override fun decrypt(sealed: String): String { if (resetting) resetReadFailure?.let { throw it }; return sealed }
        }
        makeContainer(resetCipher)
        val alpha = profile("review-reset-alpha", "Alpha", alphaServer.server)
        val beta = profile("review-reset-beta", "Beta", betaServer.server)
        seed(alpha, beta)
    }

    internal fun fixtureRemoveResetTarget() = runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(container.servers, ownProfiles) }

    internal val fixtureCleanup = FixtureCleanup()
    internal var setupFailure: RuntimeException? = null
    internal var resetFailure: RuntimeException? = null
    @Volatile internal var resetWriteFailure: Exception? = null
    @Volatile internal var resetReadFailure: Exception? = null
    @Volatile private var resetting = false
    internal var resetResults = mutableListOf<org.evsyukov.hermes.data.server.NativeResult>()
    internal var closeFailure: RuntimeException? = null
    internal val fixtureDatabaseOpen: Boolean get() = ::db.isInitialized && db.isOpen
    internal val fixtureDependenciesClosed: Boolean get() = !fixtureDatabaseOpen && !alphaStarted && !betaStarted && !mainInstalled && main.isShutdown
    internal fun fixtureProducerScope(): CoroutineScope = cacheScope

    @After
    fun tearDown() {
        val owners = buildList<Job> {
            if (::vm.isInitialized) add(vm.viewModelScope.coroutineContext.job)
            if (::container.isInitialized) add(container.scope.coroutineContext.job)
            add(cacheScope.coroutineContext.job)
            add(browserScope.coroutineContext.job)
        }
        fixtureCleanup.finish(
            owners,
            beforeJoin = listOf(
                "release DAO gates" to { if (::dao.isInitialized) dao.releaseGates() },
                "release gates" to { gates.forEach { it.release.countDown() } },
                "stop socket" to {
                    if (::container.isInitialized) container.connection.value?.socket?.stop()
                },
                "clear operation gates" to {
                    if (::container.isInitialized) {
                        container.lifetime.beforeCleanupAdmission = null
                        container.lifetime.beforeNativeAdmission = null
                        container.lifetime.beforeProfileAdmission = null
                        container.reads.beforeClear = null
                        if (::vm.isInitialized) vm.beforeChatDeleteAdmission = null
                        container.pins.beforeSet = null
                        container.servers.afterCredentialWrite = null
                        container.connection.value?.session?.beforeNativeBootstrap = null
                        container.connection.value?.session?.afterNativeCommit = null
                    }
                },
                "reset profiles" to {
                    resetFailure?.let { throw it }
                    if (::container.isInitialized) {
                        testCipher?.failing?.set(false)
                        resetting = true
                        try { runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(container.servers, ownProfiles) { resetResults += it } } } finally { resetting = false }
                    }
                },
            ),
            afterJoin = listOf(
                "close Room" to { if (::db.isInitialized) db.close() },
                "close alpha" to { if (alphaStarted) { alphaServer.server.close(); alphaStarted = false } },
                "close beta" to { if (betaStarted) { betaServer.server.close(); betaStarted = false } },
                "reset Main" to { if (mainInstalled) { Dispatchers.resetMain(); mainInstalled = false } },
                "close dispatcher" to { mainDispatcher.close() },
                "report close failure" to { closeFailure?.let { throw it } },
            ),
        )
    }

    /** A server whose next list or token refresh can be held, and whose status can ask for a sign-in. */
    private inner class FakeServer {
        val server = MockWebServer()
        val listed = AtomicInteger()

        @Volatile
        var authRequired = false

        @Volatile
        var sessions = EMPTY_SESSIONS

        @Volatile
        var refresh = FRESH_TOKENS

        @Volatile
        var refreshStatus = 200

        /** The stored answer of a single chat, by id. */
        @Volatile
        var chats: Map<String, String> = emptyMap()

        @Volatile var messageBodies: Map<String, String> = emptyMap()

        /** How many `session.active_list` calls the open socket has answered. */
        val activeLists = AtomicInteger()
        val rpcCalls = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()

        /** What the app asks the live socket: the ready frame and an empty answer to every call. */
        private val activeSocket = AtomicReference<WebSocket?>()
        fun dropSocket() { activeSocket.getAndSet(null)?.close(1012, "synthetic transport replacement") }
        private val gateway = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSocket.set(webSocket)
                webSocket.send(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false}}}""",
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = HermesJson.parseToJsonElement(text).jsonObject
                rpcCalls += frame
                val id = frame["id"]?.jsonPrimitive?.content ?: return
                if (frame["method"]?.jsonPrimitive?.content == "session.resume") {
                    nextResume.getAndSet(null)?.let { gate ->
                        gate.entered.countDown()
                        check(gate.release.await(10, TimeUnit.SECONDS)) { "Resume gate was not released" }
                    }
                }
                val result = if (frame["method"]?.jsonPrimitive?.content == "session.active_list") {
                    activeLists.incrementAndGet()
                    """{"sessions":[]}"""
                } else if (frame["method"]?.jsonPrimitive?.content == "session.resume") {
                    """{"session_id":"temporary-shared","running":0,"messages":[]}"""
                } else {
                    "{}"
                }
                webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":$result}""")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val nextResume = AtomicReference<Gate?>()
        private val nextPatch = AtomicReference<Gate?>()
        private val nextSnapshot = AtomicReference<Gate?>()
        private val nextDelete = AtomicReference<Gate?>()
        private val nextStatus = AtomicReference<Gate?>()
        private val nextProbe = AtomicReference<Gate?>()
        private val nextSessions = AtomicReference<Gate?>()
        private val nextRefresh = AtomicReference<Gate?>()
        private val nextGet = AtomicReference<Gate?>()
        private val nextMessages = AtomicReference<Gate?>()
        private val nextToken = AtomicReference<Gate?>()

        fun holdNextResume(): Gate = Gate().also { gates += it; nextResume.set(it) }

        fun holdNextPatch(): Gate = Gate().also { gates += it; nextPatch.set(it) }

        fun holdNextSnapshot(): Gate = Gate().also { gates += it; nextSnapshot.set(it) }

        fun holdNextDelete(): Gate = Gate().also { gates += it; nextDelete.set(it) }

        fun holdNextStatus(): Gate = Gate().also { gates += it; nextStatus.set(it) }

        fun holdNextProbe(): Gate = Gate().also { gates += it; nextProbe.set(it) }

        fun holdNextSessions(): Gate = Gate().also { gates += it; nextSessions.set(it) }

        fun holdNextRefresh(): Gate = Gate().also { gates += it; nextRefresh.set(it) }

        fun holdNextToken(): Gate = Gate().also { gates += it; nextToken.set(it) }

        fun holdNextGet(): Gate = Gate().also { gates += it; nextGet.set(it) }

        fun holdNextMessages(): Gate = Gate().also { gates += it; nextMessages.set(it) }

        fun start() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.url.encodedPath
                    // The app asks for a ticket before it opens the live socket of a signed-in server.
                    if (path == "/api/ws") return MockResponse.Builder().webSocketUpgrade(gateway).build()
                    if (path == "/api/auth/ws-ticket") return json("""{"ticket":"test-ticket"}""")
                    val gate = when {
                        request.method == "PATCH" -> nextPatch.getAndSet(null)
                        request.method == "DELETE" -> nextDelete.getAndSet(null)
                        path.endsWith("/mutation-snapshot") && nextSnapshot.get() != null -> nextSnapshot.getAndSet(null)
                        path == "/api/status" -> nextStatus.getAndSet(null)
                        path == "/api/system/stats" -> nextProbe.getAndSet(null)
                        path == "/api/sessions" -> {
                            listed.incrementAndGet()
                            nextSessions.getAndSet(null)
                        }
                        path == "/auth/native/token" -> nextToken.getAndSet(null)
                        path == "/auth/native/refresh" -> nextRefresh.getAndSet(null)
                        path.endsWith("/messages") -> nextMessages.getAndSet(null)
                        path.startsWith("/api/sessions/") -> nextGet.getAndSet(null)
                        else -> null
                    }
                    gate?.entered?.countDown()
                    if (gate != null) check(gate.release.await(10, TimeUnit.SECONDS)) { "Synthetic response gate was not released" }
                    gate?.response?.let { return it }
                    return when {
                        request.method == "DELETE" || request.method == "PATCH" -> json("{}")
                        path == "/api/system/stats" -> json("{}")
                        path == "/api/status" -> json("""{"auth_required":$authRequired,"auth_flows":["native_pkce"]}""")
                        path == "/api/sessions" -> json(sessions)
                        path == "/auth/native/token" -> json("""{"access_token":"native-access","refresh_token":"native-refresh","expires_at":4102444800,"provider":"native"}""")
                        path == "/auth/native/refresh" ->
                            if (refreshStatus == 200) json(refresh)
                            else MockResponse.Builder().addHeader("Content-Type", "application/json")
                                .code(refreshStatus).body("""{"detail":"expired"}""").build()
                        path.endsWith("/messages") -> json(messageBodies[path.removePrefix("/api/sessions/").removeSuffix("/messages")] ?: """{"messages":[]}""")
                        path.startsWith("/api/sessions/") ->
                            json(chats[path.removePrefix("/api/sessions/")] ?: """{"id":"unknown"}""")
                        else -> MockResponse.Builder().code(404).body("{}").build()
                    }
                }
            }
            server.start()
        }
    }

    private class Gate {
        @Volatile var response: MockResponse? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun profile(id: String, name: String, server: MockWebServer) = ServerProfile(
        id = id,
        name = name,
        baseUrl = server.url("/").toString(),
        proxy = ProxySettings(ProxyType.NONE),
    )

    private fun makeContainer(cipher: TokenCipher = TestCipher()): AppContainer =
        AppContainer(context, cipher, cache).also {
            container = it
            testCipher = cipher as? TestCipher
        }

    private suspend fun seed(alpha: ServerProfile, beta: ServerProfile) {
        ownProfiles += alpha.id
        ownProfiles += beta.id
        container.servers.save(alpha, makeActive = true)
        container.servers.save(beta, makeActive = false)
        container.servers.setTokens(alpha.id, Tokens("alpha-token"))
        container.servers.setTokens(beta.id, Tokens("beta-token"))
        withTimeout(10_000) { container.servers.state.first { it.active?.id == alpha.id } }
    }

    private suspend fun startViewModel(alpha: ServerProfile) {
        withTimeout(10_000) { container.connection.filterNotNull().first { it.session.profile.id == alpha.id } }
        vm = MainViewModel(container)
        vm.readProducers
        withTimeout(10_000) { vm.chat.filterNotNull().first() }
        // The connection collector clears the stack after it publishes the chat; the visit is opened
        // only once the task that clears it is done, or the clear would take the new visit away.
        withContext(mainDispatcher) { vm.pushSettings() }
        withTimeout(10_000) { vm.access.first { it == Access.OK } }
        withTimeout(10_000) {
            withContext(mainDispatcher) { vm.accessProducer }?.join()
            withContext(mainDispatcher) { vm.listProducer }?.join()
            drainCacheWrites()
        }
    }

    /** Waits until [id] is the current server and its own list has been answered. */
    private suspend fun settle(id: String, fake: FakeServer, since: Int) {
        withTimeout(10_000) {
            while (true) {
                val current = container.connection.value
                if (current?.session?.profile?.id == id &&
                    vm.access.value == Access.OK &&
                    !vm.sidebar.value.loading &&
                    fake.listed.get() > since
                ) break
                delay(10)
            }
        }
    }

    private fun expired(refresh: String) =
        Tokens("stale-access", refresh, System.currentTimeMillis() / 1000 - 10, "native")

    private suspend fun seedExpired(profile: ServerProfile, refresh: String) {
        container.servers.setTokens(profile.id, expired(refresh))
        withTimeout(10_000) {
            container.servers.state.first { it.tokens[profile.id]?.refreshToken == refresh }
        }
    }

    /** Joins the real writes, so "nothing was kept" is not just "the write has not run yet". */
    private suspend fun drainCacheWrites() {
        withTimeout(10_000) {
            cacheScope.coroutineContext.job.children.toList().forEach { it.join() }
        }
    }

    private suspend fun awaitKept(server: String, request: String) {
        withTimeout(10_000) { while (cache.read(server, request) == null) delay(10) }
    }

    private val listKey = cacheKey(
        "api/sessions",
        mapOf("limit" to 50, "offset" to 0, "order" to "recent", "archived" to "exclude", "min_messages" to 1),
    )

    /** The first page as the sidebar asks for it, with the sources of a chat list left out. */
    private val vmListKey = cacheKey(
        "api/sessions",
        mapOf("limit" to 50, "offset" to 0, "order" to "recent", "archived" to "exclude", "min_messages" to 1, "exclude_sources" to ListMode.CHATS.excludeSources),
    )

    @Test
    fun `an answer of a removed inactive server is not kept`() = runBlocking<Unit> {
        val alpha = profile("life-inactive-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-inactive-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val old = container.connection.value!!.session
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        cache.write(beta.id, listKey, EMPTY_SESSIONS)
        drainCacheWrites()

        val hold = alphaServer.holdNextSessions()
        val late = async(Dispatchers.IO) { runCatching { old.chat.list() } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.removeServer(alpha) }).isTrue()
        // The cleanup rightly drops the removed server's own rows, so the size to keep is the one
        // left after it: the late answer must not add a row of the removed server back.
        val keptAfterCleanup = cache.size()
        hold.release.countDown()
        late.await()
        drainCacheWrites()

        assertThat(cache.read(alpha.id, listKey)).isNull()
        assertThat(cache.size()).isEqualTo(keptAfterCleanup)
        assertThat(cache.read(beta.id, listKey)).isNotNull()
    }

    @Test
    fun `an answer of a previous session of the removed server is not kept`() = runBlocking<Unit> {
        val alpha = profile("life-previous-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-previous-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val first = container.connection.value!!.session
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        val alphaSince = alphaServer.listed.get()
        container.servers.activate(alpha.id)
        container.connection.filterNotNull().first { it.session.profile.id == alpha.id && it.session !== first }
        settle(alpha.id, alphaServer, alphaSince)

        val hold = alphaServer.holdNextSessions()
        val late = async(Dispatchers.IO) { runCatching { first.chat.list() } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.removeServer(alpha) }).isTrue()
        hold.release.countDown()
        late.await()
        drainCacheWrites()

        assertThat(cache.read(alpha.id, listKey)).isNull()
        assertThat(container.servers.load().servers.map { it.id }).containsExactly(beta.id)
    }

    @Test
    fun `a write queued behind the removal cleanup is dropped`() = runBlocking<Unit> {
        val alpha = profile("life-queued-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-queued-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val old = container.connection.value!!.session
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        cache.write(beta.id, listKey, EMPTY_SESSIONS)

        val hold = alphaServer.holdNextSessions()
        val late = async(Dispatchers.IO) { runCatching { old.chat.list() } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        val deleting = dao.holdNextDeleteStarting()
        val removed = async(Dispatchers.IO) { vm.removeServer(alpha) }
        assertThat(deleting.entered.await(10, TimeUnit.SECONDS)).isTrue()
        hold.release.countDown()
        late.await()
        // The answer is now queued for the cache lock the cleanup holds.
        deleting.release.countDown()
        assertThat(removed.await()).isTrue()
        drainCacheWrites()

        assertThat(cache.read(alpha.id, listKey)).isNull()
        assertThat(cache.read(beta.id, listKey)).isNotNull()
    }

    @Test
    fun `a late refresh does not restore the tokens of a signed-out server`() = runBlocking<Unit> {
        val alpha = profile("life-refresh-signout-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-refresh-signout-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        seedExpired(alpha, "stale-refresh")
        val old = container.connection.value!!.session

        val hold = alphaServer.holdNextRefresh()
        val late = async(Dispatchers.IO) { runCatching { old.chat.list() } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.signOut(alpha) }).isTrue()
        hold.release.countDown()
        late.await()

        assertThat(container.servers.load().tokens[alpha.id]).isNull()
        assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")

        // A session built after the sign-out is a new generation: a real refresh still lands.
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        val alphaSince = alphaServer.listed.get()
        container.servers.activate(alpha.id)
        val fresh = container.connection.filterNotNull()
            .first { it.session.profile.id == alpha.id && it.session !== old }.session
        settle(alpha.id, alphaServer, alphaSince)
        seedExpired(alpha, "later-refresh")
        alphaServer.refresh = LATER_TOKENS
        fresh.chat.list()
        withTimeout(10_000) {
            while (container.servers.load().tokens[alpha.id]?.accessToken != "later-access") delay(10)
        }
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("later-access")
    }

    @Test
    fun `a late refresh does not restore the tokens of a removed server`() = runBlocking<Unit> {
        val alpha = profile("life-refresh-removal-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-refresh-removal-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        seedExpired(alpha, "stale-refresh")
        val old = container.connection.value!!.session

        val hold = alphaServer.holdNextRefresh()
        val late = async(Dispatchers.IO) { runCatching { old.chat.list() } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.removeServer(alpha) }).isTrue()
        hold.release.countDown()
        late.await()

        val stored = container.servers.load()
        assertThat(stored.servers.map { it.id }).containsExactly(beta.id)
        assertThat(stored.tokens[alpha.id]).isNull()
        assertThat(stored.tokens[beta.id]?.accessToken).isEqualTo("beta-token")
    }

    @Test
    fun `a late chat list after signing out does not mark its chats read`() = runBlocking<Unit> {
        val alpha = profile("life-reads-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-reads-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val listedBefore = alphaServer.listed.get()
        alphaServer.authRequired = true
        alphaServer.sessions = LATE_CHATS

        val hold = alphaServer.holdNextSessions()
        withContext(mainDispatcher) { vm.refreshChats() }
        val producer = checkNotNull(vm.listProducer)
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.signOut(alpha) }).isTrue()
        hold.release.countDown()
        withTimeout(10_000) { producer.join() }

        assertThat(alphaServer.listed.get()).isGreaterThan(listedBefore)
        assertThat(container.reads.of(alpha.id)).isEmpty()
        assertThat(container.reads.answers(alpha.id)).isEmpty()

        // The server that is current now writes its read state as usual.
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        betaServer.sessions = BETA_CHATS
        val before = betaServer.listed.get()
        withContext(mainDispatcher) { vm.refreshChats() }
        withTimeout(10_000) {
            while (vm.sidebar.value.loading || betaServer.listed.get() == before) delay(10)
        }
        withTimeout(10_000) { while (container.reads.of(beta.id)["beta-late"] == null) delay(10) }
        assertThat(container.reads.of(beta.id)["beta-late"]).isEqualTo(20.0)
    }

    @Test
    fun `a session built after signing out keeps its new work`() = runBlocking<Unit> {
        val alpha = profile("life-after-signout-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-after-signout-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        assertThat(withTimeout(10_000) { vm.signOut(alpha) }).isTrue()
        assertThat(container.servers.load().tokens[alpha.id]).isNull()

        // The user signs in again: the session is built after the sign-out, so it is a new generation.
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        val alphaSince = alphaServer.listed.get()
        container.servers.activate(alpha.id)
        container.connection.filterNotNull().first { it.session.profile.id == alpha.id }
        settle(alpha.id, alphaServer, alphaSince)

        // Authorized work of that session publishes tokens, a cache copy and read state as usual.
        seedExpired(alpha, "new-refresh")
        alphaServer.refresh = LATER_TOKENS
        alphaServer.sessions = LATE_CHATS
        val listedBefore = alphaServer.listed.get()
        withContext(mainDispatcher) { vm.refreshChats() }
        withTimeout(10_000) {
            while (container.servers.load().tokens[alpha.id]?.accessToken != "later-access" ||
                vm.sidebar.value.loading ||
                alphaServer.listed.get() == listedBefore
            ) {
                delay(10)
            }
        }
        withTimeout(10_000) { while (container.reads.of(alpha.id)["late"] == null) delay(10) }

        assertThat(container.reads.of(alpha.id)["late"]).isEqualTo(50.0)
        // The new session's own answer, not the empty page the earlier settling left in that key.
        withTimeout(10_000) {
            while (cache.read(alpha.id, vmListKey)?.contains("\"id\":\"late\"") != true) delay(10)
        }
        assertThat(cache.read(alpha.id, vmListKey)).contains("\"id\":\"late\"")
        assertThat(cache.read(alpha.id, vmListKey)).contains("\"total\":1")
    }

    @Test
    fun `a failed sign-out leaves the current server writing`() = runBlocking<Unit> {
        val alpha = profile("life-failed-signout-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-failed-signout-beta", "Beta", betaServer.server)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedExpired(alpha, "stale-refresh")
        val session = container.connection.value!!.session
        cipher.failing.set(true)

        assertThat(withTimeout(10_000) { vm.signOut(alpha) }).isFalse()
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("stale-access")

        cipher.failing.set(false)
        alphaServer.refresh = FRESH_TOKENS
        session.chat.list()
        withTimeout(10_000) {
            while (container.servers.load().tokens[alpha.id]?.accessToken != "fresh-access") delay(10)
        }
        awaitKept(alpha.id, listKey)
        assertThat(cache.read(alpha.id, listKey)).isNotNull()
    }

    @Test
    fun `an old rejection does not delete the tokens of a newer authorization`() = runBlocking<Unit> {
        val alpha = profile("life-stale-reject-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-stale-reject-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        seedExpired(alpha, "stale-refresh")
        val old = container.connection.value!!.session

        val hold = alphaServer.holdNextRefresh()
        alphaServer.refreshStatus = 401
        val late = async(Dispatchers.IO) { runCatching { old.chat.list() } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.signOut(alpha) }).isTrue()
        assertThat(container.servers.load().tokens[alpha.id]).isNull()

        // The user authorizes the server again while the old rejection is still on its way.
        container.servers.setTokens(alpha.id, Tokens("new-access", "new-refresh", 4102444800, "native"))
        hold.release.countDown()
        late.await()

        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("new-access")
        assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")

        // A rejection of the work that belongs to the current session still clears its tokens.
        container.servers.setTokens(alpha.id, Tokens("current-access", "current-refresh", 4102444800, "native"))
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        val alphaSince = alphaServer.listed.get()
        container.servers.activate(alpha.id)
        val fresh = container.connection.filterNotNull()
            .first { it.session.profile.id == alpha.id && it.session !== old }.session
        settle(alpha.id, alphaServer, alphaSince)
        seedExpired(alpha, "current-refresh")
        alphaServer.refreshStatus = 401
        runCatching { fresh.chat.list() }
        withTimeout(10_000) { while (container.servers.load().tokens[alpha.id] != null) delay(10) }
        assertThat(container.servers.load().tokens[alpha.id]).isNull()
    }

    @Test
    fun `a retained session of a removed server cannot cache its next request`() = runBlocking<Unit> {
        val alpha = profile("life-retained-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-retained-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val old = container.connection.value!!.session
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        cache.write(beta.id, listKey, EMPTY_SESSIONS)
        drainCacheWrites()
        alphaServer.chats = mapOf("c1" to summary("c1"), "c2" to summary("c2"), "c3" to summary("c3"))

        // The first request of the old session is still in flight when the server is removed.
        val hold = alphaServer.holdNextGet()
        val first = async(Dispatchers.IO) { runCatching { old.chat.get("c1") } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(withTimeout(10_000) { vm.removeServer(alpha) }).isTrue()
        hold.release.countDown()
        first.await()

        // The same producer goes on with another request: it is not a new authorization.
        val requestsBefore = alphaServer.server.requestCount
        val rejected = runCatching { old.chat.get("c2") }.exceptionOrNull()
        assertThat(rejected).isInstanceOf(org.evsyukov.hermes.data.remote.SignInRequiredException::class.java)
        assertThat(alphaServer.server.requestCount).isEqualTo(requestsBefore)
        drainCacheWrites()

        assertThat(cache.read(alpha.id, "api/sessions/c1")).isNull()
        assertThat(cache.read(alpha.id, "api/sessions/c2")).isNull()
        assertThat(cache.read(beta.id, listKey)).isNotNull()

        // Saved again, the server is genuinely new work and its new session caches as usual.
        val alphaSince = alphaServer.listed.get()
        container.servers.save(alpha)
        container.servers.setTokens(alpha.id, Tokens("again-access"))
        withContext(mainDispatcher) { vm.checkAccess() }
        settle(alpha.id, alphaServer, alphaSince)
        val again = container.connection.filterNotNull().first { it.session.profile.id == alpha.id }.session
        again.chat.get("c3")
        awaitKept(alpha.id, "api/sessions/c3")
        assertThat(cache.read(alpha.id, "api/sessions/c3")).isNotNull()
    }

    @Test
    fun `a chat opened while signed in marks itself read when its history arrives`() = runBlocking<Unit> {
        val alpha = profile("life-live-mark-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-live-mark-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.chats = mapOf("late" to summary("late"))

        val activeBefore = alphaServer.activeLists.get()
        val hold = alphaServer.holdNextMessages()
        withContext(mainDispatcher) { vm.openChat("late") }
        val chat = vm.chat.value!!
        withContext(mainDispatcher) { vm.chatShown(chat, true) }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        withTimeout(10_000) { chat.state.first { it.loading } }
        // The stored head is in: the live check of this load has run, and only the history waits.
        withTimeout(10_000) { while (alphaServer.activeLists.get() == activeBefore) delay(10) }
        hold.release.countDown()
        withTimeout(10_000) { chat.state.first { !it.loading } }

        assertThat(chat.state.value.error).isNull()
        withTimeout(10_000) { while (container.reads.of(alpha.id)["late"] == null) delay(10) }
        assertThat(container.reads.of(alpha.id)["late"]).isEqualTo(50.0)
    }

    @Test
    fun `a history that outlived the sign-out does not mark its chat read again`() = runBlocking<Unit> {
        val alpha = profile("life-late-mark-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-late-mark-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.chats = mapOf("late" to summary("late"))
        val origin = container.connection.value!!

        val activeBefore = alphaServer.activeLists.get()
        val hold = alphaServer.holdNextMessages()
        withContext(mainDispatcher) { vm.openChat("late") }
        val chat = vm.chat.value!!
        withContext(mainDispatcher) { vm.chatShown(chat, true) }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        // The load is under way: the next "not loading" is its end, not the state before it started.
        withTimeout(10_000) { chat.state.first { it.loading } }
        // The stored head is in: this load has already read the summary and asked the live socket.
        withTimeout(10_000) { while (alphaServer.activeLists.get() == activeBefore) delay(10) }
        assertThat(container.reads.of(alpha.id)["late"]).isNull()

        withContext(mainDispatcher) { chat.reload() }
        assertThat(withTimeout(10_000) { vm.signOut(alpha) }).isTrue()
        assertThat(container.servers.load().tokens[alpha.id]).isNull()
        assertThat(container.reads.of(alpha.id)).isEmpty()
        assertThat(container.connection.value).isSameInstanceAs(origin)
        assertThat(vm.chat.value).isSameInstanceAs(chat)
        hold.release.countDown()

        // The load finishes and delivers its read after the cleanup.
        withTimeout(10_000) { while (chat.completedLoadCycles.get() == 0L) delay(5) }
        assertThat(chat.state.value.error).isNull()
        withTimeout(10_000) {
            chat.loadCompletion!!.await()
            vm.readProducers.toList().forEach { it.join() }
        }

        assertThat(container.reads.of(alpha.id)).doesNotContainKey("late")

        // The server the app works with now still writes its own marks.
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        betaServer.sessions = BETA_CHATS
        val listedBefore = betaServer.listed.get()
        withContext(mainDispatcher) { vm.refreshChats() }
        withTimeout(10_000) {
            while (vm.sidebar.value.loading || betaServer.listed.get() == listedBefore) delay(10)
        }
        withTimeout(10_000) { while (container.reads.of(beta.id)["beta-late"] == null) delay(10) }
        assertThat(container.reads.of(beta.id)["beta-late"]).isEqualTo(20.0)
    }

    /** The browser part of a native sign-in: answers the loopback address once signIn waits on it. */
    private fun browser(): (String) -> Unit = { url ->
        val parts = url.toHttpUrl()
        val state = parts.queryParameter("state").orEmpty()
        val callback = parts.queryParameter("redirect_uri").orEmpty() + "?code=native-code&state=" + state
        browserScope.launch {
            runCatching { OkHttpClient().newCall(Request.Builder().url(callback).build()).execute().close() }
        }
    }

    @Test
    fun `a removal cleanup does not erase a profile recreated while it runs`() = runBlocking<Unit> {
        val alpha = profile("life-recreate-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-recreate-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        container.reads.markRead(alpha.id, "old", 1.0)

        val deleting = dao.holdNextDeleteStarting()
        val removed = async(Dispatchers.IO) { vm.removeServer(alpha) }
        assertThat(deleting.entered.await(10, TimeUnit.SECONDS)).isTrue()

        // The same server is added back while the old cleanup is between its check and its delete.
        val arrived = kotlinx.coroutines.CompletableDeferred<Unit>()
        container.lifetime.beforeProfileAdmission = { arrived.complete(Unit) }
        val recreated = async(Dispatchers.IO) {
            container.servers.save(alpha, makeActive = false)
            container.servers.setTokens(alpha.id, Tokens("new-access"))
            container.reads.markRead(alpha.id, "fresh", 99.0)
        }
        try {
            withTimeout(10_000) { arrived.await() }
            assertThat(recreated.isCompleted).isFalse()
        } finally {
            deleting.release.countDown()
            container.lifetime.beforeProfileAdmission = null
        }
        assertThat(withTimeout(10_000) { removed.await() }).isTrue()
        withTimeout(10_000) { recreated.await() }

        assertThat(container.servers.load().servers.map { it.id }).contains(alpha.id)
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("new-access")
        assertThat(container.reads.of(alpha.id)).containsKey("fresh")
    }

    @Test
    fun `a rejected native sign-in does not check the server that replaced it`() = runBlocking<Unit> {
        val alpha = profile("life-reject-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-reject-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)

        val hold = alphaServer.holdNextToken()
        val attempt = async(Dispatchers.IO) { runCatching { withTimeout(10_000) { vm.signIn(browser()) } } }
        assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
        // A newer authorization wins while the exchange of the first attempt is still in flight.
        container.servers.setTokens(alpha.id, Tokens("newer-access"))
        val betaSince = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaSince)
        val requestsBefore = betaServer.server.requestCount

        hold.release.countDown()
        val result = attempt.await()
        assertThat(result.isSuccess).isTrue()
        // Give a stale check every chance to reach the replacement server.
        withTimeoutOrNull(3_000) { while (betaServer.server.requestCount == requestsBefore) delay(10) }

        assertThat(betaServer.server.requestCount).isEqualTo(requestsBefore)
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("newer-access")
    }

    @Test
    fun `a current native sign-in still checks access after it applies`() = runBlocking<Unit> {
        val alpha = profile("life-native-ok-alpha", "Alpha", alphaServer.server)
        val beta = profile("life-native-ok-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val listedBefore = alphaServer.listed.get()

        withTimeout(10_000) { vm.signIn(browser()) }

        withTimeout(10_000) { while (alphaServer.listed.get() == listedBefore) delay(10) }
        // The list may still have been loading from the startup check; the access the sign-in
        // itself produced is what this control observes.
        withTimeout(10_000) { vm.access.first { it == Access.OK } }
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
    }


    @Test
    fun `B native root held at status cannot borrow a newer authorization`() = runBlocking<Unit> {
        val alpha = profile("completion-native-status-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-native-status-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextStatus()
        val opened = AtomicInteger()
        val attempt = async(Dispatchers.IO) { vm.signIn { opened.incrementAndGet(); browser()(it) } }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            container.servers.setTokens(alpha.id, Tokens("replacement-access"))
            val replacement = container.connection.value!!.session.owner()
            hold.release.countDown()
            withTimeout(10_000) { attempt.await() }
            assertThat(opened.get()).isEqualTo(0)
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("replacement-access")
            assertThat(container.connection.value!!.session.owner()).isEqualTo(replacement)
        } finally {
            hold.release.countDown()
            attempt.cancel()
            withTimeout(10_000) { attempt.join() }
        }
    }

    @Test
    fun `B old status error is suppressed after current native success`() = runBlocking<Unit> {
        val alpha = profile("completion-native-error-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-native-error-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextStatus().also {
            it.response = MockResponse.Builder().code(500).body("old status failure").build()
        }
        val attempt = async(Dispatchers.IO) { runCatching { withTimeout(10_000) { vm.signIn(browser()) } } }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            withTimeout(10_000) { vm.signIn(browser()) }
            val current = container.connection.value!!.session.owner()
            hold.release.countDown()
            assertThat(withTimeout(10_000) { attempt.await() }.isSuccess).isTrue()
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
            assertThat(container.connection.value!!.session.owner()).isEqualTo(current)
        } finally {
            hold.release.countDown()
            attempt.cancel()
            withTimeout(10_000) { attempt.join() }
        }
    }

    @Test
    fun `C status continuation does not probe with replacement credentials`() = runBlocking<Unit> {
        val alpha = profile("completion-access-status-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-access-status-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.authRequired = true
        val origin = container.connection.value!!
        val owner = origin.session.owner()
        val hold = alphaServer.holdNextStatus()
        val probesBefore = alphaServer.requests.count { it.url.encodedPath == "/api/system/stats" }
        val check = withContext(mainDispatcher) { vm.checkAccess(owner, origin); vm.accessProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            container.servers.setTokens(alpha.id, Tokens("replacement-access"))
            hold.release.countDown()
            withTimeout(10_000) { check.join() }
            assertThat(alphaServer.requests.count { it.url.encodedPath == "/api/system/stats" }).isEqualTo(probesBefore)
        } finally {
            hold.release.countDown()
            check.cancel()
            withTimeout(10_000) { check.join() }
        }
    }


    @Test
    fun `C fresh authenticated access probes exact current credentials`() = runBlocking<Unit> {
        val alpha = profile("completion-access-fresh-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-access-fresh-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.authRequired = true
        container.servers.setTokens(alpha.id, Tokens("replacement-access"))
        val fresh = withContext(mainDispatcher) { vm.checkAccess(); vm.accessProducer!! }
        withTimeout(10_000) { fresh.join() }
        assertThat(vm.access.value).isEqualTo(Access.OK)
        val probes = alphaServer.requests.filter { it.url.encodedPath == "/api/system/stats" }
        assertThat(probes).hasSize(1)
        assertThat(probes.single().headers["Authorization"]).isEqualTo("Bearer replacement-access")
    }

    @Test
    fun `G old mutation snapshot cannot send PATCH with newer credentials`() = runBlocking<Unit> {
        val alpha = profile("completion-mutation-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-mutation-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextGet()
        val mutation = withContext(mainDispatcher) { vm.renameChat("shared", "old title"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(alphaServer.requests.last().url.encodedPath).isEqualTo("/api/sessions/shared/mutation-snapshot")
            container.servers.setTokens(alpha.id, Tokens("replacement-access"))
            hold.release.countDown()
            withTimeout(10_000) { mutation.join() }
            assertThat(alphaServer.requests.filter { it.method == "PATCH" }).isEmpty()
        } finally {
            hold.release.countDown()
            mutation.cancel()
            withTimeout(10_000) { mutation.join() }
        }
    }

    @Test
    fun `G current rename sends exact title with current credentials`() = runBlocking<Unit> {
        val alpha = profile("completion-mutation-current-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-mutation-current-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val mutation = withContext(mainDispatcher) { vm.renameChat("shared", "current title"); vm.mutationProducer!! }
        withTimeout(10_000) { mutation.join() }
        val patch = alphaServer.requests.single { it.method == "PATCH" }
        assertThat(patch.url.encodedPath).isEqualTo("/api/sessions/shared")
        assertThat(patch.headers["Authorization"]).isEqualTo("Bearer alpha-token")
        assertThat(HermesJson.parseToJsonElement(patch.body!!.utf8()).jsonObject["title"]!!.jsonPrimitive.content).isEqualTo("current title")
    }

    @Test
    fun `G delayed pinned detail 404 cannot remove a fresh owner pin`() = runBlocking<Unit> {
        pinned404(freshOwner = true)
    }

    @Test
    fun `G current pinned detail 404 removes only its current pin`() = runBlocking<Unit> {
        pinned404(freshOwner = false)
    }

    private suspend fun pinned404(freshOwner: Boolean) {
        val alpha = profile("completion-pin-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-pin-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        container.pins.set(alpha.id, "shared", true)
        container.pins.set(beta.id, "shared", true)
        val hold = alphaServer.holdNextGet().also { it.response = MockResponse.Builder().code(404).body("{}").build() }
        val load = withContext(mainDispatcher) { vm.refreshChats(); vm.listProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            if (freshOwner) {
                container.servers.setTokens(alpha.id, Tokens("replacement-access"))
                container.pins.set(alpha.id, "shared", true)
            }
            hold.release.countDown()
            withTimeout(10_000) { load.join() }
            assertThat(container.pins.of(alpha.id).contains("shared")).isEqualTo(freshOwner)
            assertThat(container.pins.of(beta.id)).containsExactly("shared")
        } finally {
            hold.release.countDown()
            load.cancel()
            withTimeout(10_000) { load.join() }
            container.pins.set(alpha.id, "shared", false)
            container.pins.set(beta.id, "shared", false)
        }
    }

    @Test
    fun `H completed A delete does not erase B with the same chat ID`() = runBlocking<Unit> {
        val alpha = profile("completion-delete-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-delete-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.chats = mapOf("shared" to summary("shared"))
        betaServer.chats = mapOf("shared" to summary("shared"))
        withContext(mainDispatcher) { vm.openChat("shared") }
        val a = vm.chat.value!!
        withTimeout(10_000) { while (a.loadCompletion == null) delay(5); a.loadCompletion!!.await() }
        val hold = alphaServer.holdNextDelete()
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val betaSince = betaServer.listed.get()
            container.servers.activate(beta.id)
            settle(beta.id, betaServer, betaSince)
            withContext(mainDispatcher) { vm.openChat("shared") }
            val bChat = vm.chat.value!!
            withTimeout(10_000) { while (bChat.loadCompletion == null) delay(5); bChat.loadCompletion!!.await() }
            drainCacheWrites()
            container.reads.markRead(beta.id, "shared", 77.0)
            cache.write(beta.id, "api/sessions/shared/messages", "distinct B bytes")
            val b = container.connection.value!!
            b.questions.answers("shared", "request-B")["draft"] = "B retained answer"
            val controller = vm.chat.value
            hold.release.countDown()
            withTimeout(10_000) { deletion.join() }
            assertThat(container.reads.of(beta.id)["shared"]).isEqualTo(77.0)
            assertThat(cache.read(beta.id, "api/sessions/shared/messages")).isEqualTo("distinct B bytes")
            assertThat(b.questions.answers("shared", "request-B")["draft"]).isEqualTo("B retained answer")
            assertThat(vm.chat.value).isSameInstanceAs(controller)
            assertThat(betaServer.requests.filter { it.method == "DELETE" }).isEmpty()
        } finally {
            hold.release.countDown()
            deletion.cancel()
            withTimeout(10_000) { deletion.join() }
        }
    }

    @Test
    fun `H old same-profile deletion cannot erase newly authorized local data`() = runBlocking<Unit> {
        val alpha = profile("completion-delete-renew-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-delete-renew-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextDelete()
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            container.servers.setTokens(alpha.id, Tokens("replacement-access"))
            container.reads.markRead(alpha.id, "shared", 88.0)
            cache.write(alpha.id, "api/sessions/shared/messages", "fresh A bytes")
            hold.release.countDown()
            withTimeout(10_000) { deletion.join() }
            assertThat(container.reads.of(alpha.id)["shared"]).isEqualTo(88.0)
            assertThat(cache.read(alpha.id, "api/sessions/shared/messages")).isEqualTo("fresh A bytes")
        } finally {
            hold.release.countDown()
            deletion.cancel()
            withTimeout(10_000) { deletion.join() }
        }
    }

    @Test
    fun `H current deletion clears captured target and preserves other profile bytes`() = runBlocking<Unit> {
        val alpha = profile("completion-delete-current-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-delete-current-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        for (p in listOf(alpha, beta)) {
            container.reads.markRead(p.id, "shared", 88.0)
            cache.write(p.id, "api/sessions/shared/messages", "${p.name} exact bytes")
        }
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        withTimeout(10_000) { deletion.join() }
        assertThat(container.reads.of(alpha.id)).doesNotContainKey("shared")
        assertThat(cache.read(alpha.id, "api/sessions/shared/messages")).isNull()
        assertThat(container.reads.of(beta.id)["shared"]).isEqualTo(88.0)
        assertThat(cache.read(beta.id, "api/sessions/shared/messages")).isEqualTo("Beta exact bytes")
        assertThat(alphaServer.requests.filter { it.method == "DELETE" }).hasSize(1)
    }


    @Test
    fun `A retirement wins over a native enable paused after committed tokens`() = runBlocking<Unit> {
        val alpha = profile("completion-enable-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-enable-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val session = container.connection.value!!.session
        val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        session.afterNativeCommit = { committed.complete(Unit); release.await() }
        val attempt = async(Dispatchers.IO) { withTimeout(10_000) { vm.signIn(browser()) } }
        try {
            withTimeout(10_000) { committed.await() }
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
            assertThat(vm.signOut(alpha)).isTrue()
            assertThat(session.localDataEnabled).isFalse()
            release.complete(Unit)
            withTimeout(10_000) { attempt.await() }
            assertThat(session.localDataEnabled).isFalse()
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
        } finally {
            release.complete(Unit)
            session.afterNativeCommit = null
            attempt.cancel()
            withTimeout(10_000) { attempt.join() }
        }
    }

    @Test
    fun `A fresh native admission before old retirement cleanup preserves exact fresh state`() = runBlocking<Unit> {
        val alpha = profile("completion-cleanup-fresh-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-cleanup-fresh-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        container.lifetime.beforeCleanupAdmission = { owner, removed ->
            if (owner.profileId == alpha.id && !removed && first.compareAndSet(true, false)) {
                reached.complete(Unit)
                release.await()
            }
        }
        val signout = async(Dispatchers.IO) { vm.signOut(alpha) }
        try {
            withTimeout(10_000) { reached.await() }
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            withTimeout(10_000) { vm.signIn(browser()) }
            cache.write(alpha.id, "fresh-authorized", "native exact fresh body")
            container.reads.markRead(alpha.id, "fresh", 19.0)
            val before = dao.deletes.get()
            release.complete(Unit)
            assertThat(withTimeout(10_000) { signout.await() }).isTrue()
            assertThat(dao.deletes.get()).isEqualTo(before)
            assertThat(cache.read(alpha.id, "fresh-authorized")).isEqualTo("native exact fresh body")
            assertThat(container.reads.of(alpha.id)["fresh"]).isEqualTo(19.0)
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
            assertThat(container.connection.value!!.session.localDataEnabled).isTrue()
        } finally {
            release.complete(Unit)
            container.lifetime.beforeCleanupAdmission = null
            signout.cancel()
            withTimeout(10_000) { signout.join() }
        }
    }

    @Test
    fun `A recreation admitted before old removal cleanup has zero deletion effects`() = runBlocking<Unit> {
        val alpha = profile("completion-remove-fresh-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-remove-fresh-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, removed ->
            if (owner.profileId == alpha.id && removed) { reached.complete(Unit); release.await() }
        }
        val removal = async(Dispatchers.IO) { vm.removeServer(alpha) }
        try {
            withTimeout(10_000) { reached.await() }
            assertThat(container.servers.load().servers.map { it.id }).doesNotContain(alpha.id)
            container.servers.save(alpha, makeActive = false)
            container.servers.setTokens(alpha.id, Tokens("recreated-access"))
            cache.write(alpha.id, "recreated", "distinct recreated bytes")
            container.reads.markRead(alpha.id, "fresh", 21.0)
            val before = dao.deletes.get()
            release.complete(Unit)
            assertThat(withTimeout(10_000) { removal.await() }).isTrue()
            assertThat(dao.deletes.get()).isEqualTo(before)
            assertThat(cache.read(alpha.id, "recreated")).isEqualTo("distinct recreated bytes")
            assertThat(container.reads.of(alpha.id)["fresh"]).isEqualTo(21.0)
        } finally {
            release.complete(Unit)
            container.lifetime.beforeCleanupAdmission = null
            removal.cancel()
            withTimeout(10_000) { removal.join() }
        }
    }

    @Test
    fun `B native invocation held before bootstrap cannot adopt renewed owner`() = runBlocking<Unit> {
        val alpha = profile("completion-native-bootstrap-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-native-bootstrap-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = container.connection.value!!.session
        session.beforeNativeBootstrap = { reached.complete(Unit); release.await() }
        val opened = AtomicInteger()
        val attempt = async(Dispatchers.IO) { vm.signIn { opened.incrementAndGet(); browser()(it) } }
        try {
            withTimeout(10_000) { reached.await() }
            container.servers.setTokens(alpha.id, Tokens("bootstrap-replacement"))
            release.complete(Unit)
            withTimeout(10_000) { attempt.await() }
            assertThat(opened.get()).isEqualTo(0)
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("bootstrap-replacement")
        } finally {
            release.complete(Unit)
            session.beforeNativeBootstrap = null
            attempt.cancel()
            withTimeout(10_000) { attempt.join() }
        }
    }

    private suspend fun partialSignout(cacheFailure: Boolean, cancellation: Boolean = false) {
        val alpha = profile("completion-partial-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-partial-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        cache.write(alpha.id, "retained", "prior cache bytes")
        container.reads.markRead(alpha.id, "retained", 11.0)
        val clearCalls = AtomicInteger()
        val original = IllegalStateException("synthetic local cleanup failure")
        if (cacheFailure) dao.deleteFailure = original
        container.reads.beforeClear = {
            clearCalls.incrementAndGet()
            if (cancellation) throw kotlinx.coroutines.CancellationException("synthetic cleanup cancellation")
            if (!cacheFailure) throw original
        }
        try {
            val result = runCatching { vm.signOut(alpha) }
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            assertThat(clearCalls.get()).isEqualTo(1)
            assertThat(dao.deletes.get()).isGreaterThan(0)
            if (cancellation) {
                assertThat(result.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
            } else {
                assertThat(result.getOrThrow()).isFalse()
                assertThat(vm.notice.value).contains(alpha.name)
                assertThat(vm.stack.lastOrNull()).isInstanceOf(Screen.Settings::class.java)
                if (cacheFailure) assertThat(container.reads.of(alpha.id)).isEmpty()
                else assertThat(cache.read(alpha.id, "retained")).isNull()
            }
        } finally {
            dao.deleteFailure = null
            container.reads.beforeClear = null
        }
    }

    @Test fun `I cache clear failure still attempts reads and reports partial signout`() = runBlocking<Unit> {
        partialSignout(cacheFailure = true)
    }
    @Test fun `I read clear failure preserves committed retirement and reports partial signout`() = runBlocking<Unit> {
        partialSignout(cacheFailure = false)
    }
    @Test fun `I read cleanup cancellation escapes after committed retirement`() = runBlocking<Unit> {
        partialSignout(cacheFailure = false, cancellation = true)
    }


    @Test
    fun `D surviving controller fresh native reload keeps distinct fresh history and read`() = runBlocking<Unit> {
        val alpha = profile("completion-reload-fresh-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-reload-fresh-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.chats = mapOf("late" to summary("late"))
        alphaServer.messageBodies = mapOf("late" to """{"messages":[{"id":1,"role":"assistant","content":"old native history","timestamp":50.0}]}""")
        withContext(mainDispatcher) { vm.openChat("late") }
        val chat = vm.chat.value!!
        withTimeout(10_000) { while (chat.loadCompletion == null) delay(5); chat.loadCompletion!!.await() }
        assertThat(vm.signOut(alpha)).isTrue()
        val oldCompletion = chat.loadCompletion
        alphaServer.chats = mapOf("late" to """{"id":"late","title":"DISTINCT fresh native body","source":"android","last_active":73.0,"message_count":2}""")
        alphaServer.messageBodies = mapOf("late" to """{"messages":[{"id":2,"role":"assistant","content":"DISTINCT fresh native history","timestamp":73.0}]}""")
        withTimeout(10_000) { vm.signIn(browser()) }
        assertThat(vm.chat.value).isSameInstanceAs(chat)
        withContext(mainDispatcher) { vm.chatShown(chat, true); chat.reload() }
        withTimeout(10_000) {
            while (chat.loadCompletion == null || chat.loadCompletion === oldCompletion) delay(5)
            chat.loadCompletion!!.await()
            while (container.reads.of(alpha.id)["late"] != 73.0) delay(5)
        }
        assertThat(chat.state.value.title).isEqualTo("DISTINCT fresh native body")
        val detailKey = cacheKey("api/sessions/late", emptyMap())
        withTimeout(10_000) { while (cache.read(alpha.id, detailKey)?.contains("DISTINCT fresh native body") != true) delay(5) }
        assertThat(cache.read(alpha.id, detailKey)).contains("DISTINCT fresh native body")
        assertThat(alphaServer.requests.last { it.url.encodedPath == "/api/sessions/late" }.headers["Authorization"]).isEqualTo("Bearer native-access")
        val historyKey = cacheKey("api/sessions/late/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false))
        withTimeout(10_000) { cache.producers.toList().forEach { it.join() } }
        assertThat(cache.read(alpha.id, historyKey)).contains("DISTINCT fresh native history")
        assertThat(cache.read(alpha.id, historyKey)).doesNotContain("old native history")
        assertThat(container.reads.of(alpha.id)["late"]).isEqualTo(73.0)
    }

    @Test
    fun `G retired REST 404 cannot send deletion fallback on surviving transport`() = runBlocking<Unit> {
        val alpha = profile("completion-fallback-stale-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-fallback-stale-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextDelete().also { it.response = MockResponse.Builder().code(404).body("{}").build() }
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val transport = container.connection.value!!.socket.transport.value
            assertThat(transport).isGreaterThan(0L)
            container.servers.setTokens(alpha.id, Tokens("replacement-access"))
            hold.release.countDown()
            withTimeout(10_000) { deletion.join() }
            assertThat(alphaServer.rpcCalls.filter { it["method"]?.jsonPrimitive?.content == "session.delete" }).isEmpty()
            assertThat(container.connection.value!!.socket.transport.value).isEqualTo(transport)
        } finally {
            hold.release.countDown()
            deletion.cancel()
            withTimeout(10_000) { deletion.join() }
        }
    }

    @Test
    fun `G current REST 404 deletion fallback uses captured stored target once`() = runBlocking<Unit> {
        val alpha = profile("completion-fallback-current-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-fallback-current-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextDelete().also { it.response = MockResponse.Builder().code(404).body("{}").build() }
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            hold.release.countDown()
            withTimeout(10_000) { deletion.join() }
            val rpc = alphaServer.rpcCalls.single { it["method"]?.jsonPrimitive?.content == "session.delete" }
            assertThat(rpc["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content).isEqualTo("shared")
        } finally {
            hold.release.countDown()
            deletion.cancel()
            withTimeout(10_000) { deletion.join() }
        }
    }


    @Test
    fun `A retirement cleanup admitted first completes before fresh native credentials`() = runBlocking<Unit> {
        val alpha = profile("completion-cleanup-first-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-cleanup-first-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        cache.write(alpha.id, "old", "old exact bytes")
        container.reads.markRead(alpha.id, "old", 1.0)
        val deleting = dao.holdNextDeleteStarting()
        val signout = async(Dispatchers.IO) { vm.signOut(alpha) }
        val arrived = kotlinx.coroutines.CompletableDeferred<Unit>()
        var native: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertThat(deleting.entered.await(10, TimeUnit.SECONDS)).isTrue()
            container.connection.value!!.session.beforeNativeBootstrap = { arrived.complete(Unit) }
            val attempt = async(Dispatchers.IO) { withTimeout(10_000) { vm.signIn(browser()) } }
            native = attempt
            withTimeout(10_000) { arrived.await() }
            assertThat(attempt.isCompleted).isFalse()
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            deleting.release.countDown()
            assertThat(withTimeout(10_000) { signout.await() }).isTrue()
            withTimeout(10_000) { attempt.await() }
            assertThat(cache.read(alpha.id, "old")).isNull()
            assertThat(container.reads.of(alpha.id)).doesNotContainKey("old")
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
            cache.write(alpha.id, "fresh", "fresh admitted bytes")
            container.reads.markRead(alpha.id, "fresh", 2.0)
            assertThat(cache.read(alpha.id, "fresh")).isEqualTo("fresh admitted bytes")
            assertThat(container.reads.of(alpha.id)["fresh"]).isEqualTo(2.0)
        } finally {
            deleting.release.countDown()
            container.connection.value?.session?.beforeNativeBootstrap = null
            native?.cancel()
            signout.cancel()
            withTimeout(10_000) { native?.join(); signout.join() }
        }
    }

    @Test
    fun `A newer same-Connection owner prevents old signout pop and check cancellation`() = runBlocking<Unit> {
        val alpha = profile("completion-pop-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-pop-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val route = vm.stack.last()
        val origin = container.connection.value!!
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.afterSignoutCleanup = { reached.complete(Unit); release.await() }
        val signout = async(Dispatchers.IO) { vm.signOut(alpha) }
        try {
            withTimeout(10_000) { reached.await() }
            withTimeout(10_000) { vm.signIn(browser()) }
            val currentCheck = withContext(mainDispatcher) { vm.accessProducer }
            release.complete(Unit)
            assertThat(withTimeout(10_000) { signout.await() }).isTrue()
            assertThat(container.connection.value).isSameInstanceAs(origin)
            assertThat(vm.stack.lastOrNull()).isSameInstanceAs(route)
            assertThat(vm.accessProducer).isSameInstanceAs(currentCheck)
        } finally {
            release.complete(Unit)
            vm.afterSignoutCleanup = null
            signout.cancel()
            withTimeout(10_000) { signout.join() }
        }
    }

    @Test
    fun `B status-held A to B to A native root opens no stale browser`() = runBlocking<Unit> {
        val alpha = profile("completion-native-aba-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-native-aba-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val origin = container.connection.value!!
        val hold = alphaServer.holdNextStatus()
        val opened = AtomicInteger()
        val attempt = async(Dispatchers.IO) { vm.signIn { opened.incrementAndGet(); browser()(it) } }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val betaSince = betaServer.listed.get()
            container.servers.activate(beta.id)
            settle(beta.id, betaServer, betaSince)
            val alphaSince = alphaServer.listed.get()
            container.servers.activate(alpha.id)
            settle(alpha.id, alphaServer, alphaSince)
            assertThat(container.connection.value).isNotSameInstanceAs(origin)
            hold.release.countDown()
            withTimeout(10_000) { attempt.await() }
            assertThat(opened.get()).isEqualTo(0)
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("alpha-token")
        } finally {
            hold.release.countDown()
            attempt.cancel()
            withTimeout(10_000) { attempt.join() }
        }
    }

    @Test
    fun `B older failure cannot escape after newer same-generation native attempt`() = runBlocking<Unit> {
        val alpha = profile("completion-native-attempt-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-native-attempt-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val owner = container.connection.value!!.session.owner()
        val old = alphaServer.holdNextToken().also { it.response = MockResponse.Builder().code(500).body("old token error").build() }
        val attempt = async(Dispatchers.IO) { runCatching { withTimeout(10_000) { vm.signIn(browser()) } } }
        try {
            assertThat(old.entered.await(10, TimeUnit.SECONDS)).isTrue()
            alphaServer.holdNextStatus().also {
                it.response = MockResponse.Builder().code(503).body("new attempt failure").build()
                it.release.countDown()
            }
            val currentFailure = runCatching { withTimeout(10_000) { vm.signIn(browser()) } }.exceptionOrNull()
            assertThat(currentFailure).isNotNull()
            assertThat(container.connection.value!!.session.owner()).isEqualTo(owner)
            old.release.countDown()
            assertThat(withTimeout(10_000) { attempt.await() }.isSuccess).isTrue()
        } finally {
            old.release.countDown()
            attempt.cancel()
            withTimeout(10_000) { attempt.join() }
        }
    }

    @Test
    fun `C access waiting before store read cannot borrow replacement authorization`() = runBlocking<Unit> {
        val alpha = profile("completion-access-store-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-access-store-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.authRequired = true
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.beforeAccessStoreRead = { entered.complete(Unit); release.await() }
        val check = withContext(mainDispatcher) { vm.checkAccess(); vm.accessProducer!! }
        val before = alphaServer.requests.count { it.url.encodedPath == "/api/system/stats" }
        try {
            withTimeout(10_000) { entered.await() }
            container.servers.setTokens(alpha.id, Tokens("store-replacement"))
            release.complete(Unit)
            withTimeout(10_000) { check.join() }
            assertThat(alphaServer.requests.count { it.url.encodedPath == "/api/system/stats" }).isEqualTo(before)
        } finally {
            release.complete(Unit)
            vm.beforeAccessStoreRead = null
            check.cancel()
            withTimeout(10_000) { check.join() }
        }
    }

    private suspend fun replacedProbe(error: Boolean) {
        val alpha = profile("completion-probe-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-probe-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.authRequired = true
        val hold = alphaServer.holdNextProbe()
        if (error) hold.response = MockResponse.Builder().code(500).body("old probe error").build()
        val old = withContext(mainDispatcher) { vm.checkAccess(); vm.accessProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            container.servers.setTokens(alpha.id, Tokens("probe-replacement"))
            val fresh = withContext(mainDispatcher) { vm.checkAccess(); vm.accessProducer!! }
            withTimeout(10_000) { fresh.join() }
            assertThat(vm.access.value).isEqualTo(Access.OK)
            hold.release.countDown()
            withTimeout(10_000) { old.join() }
            assertThat(old.isCancelled).isTrue()
            assertThat(vm.accessProducer).isSameInstanceAs(fresh)
            assertThat(vm.access.value).isEqualTo(Access.OK)
            assertThat(vm.accessError.value).isNull()
            assertThat(alphaServer.requests.last { it.url.encodedPath == "/api/system/stats" }.headers["Authorization"]).isEqualTo("Bearer probe-replacement")
        } finally {
            hold.release.countDown()
            old.cancel()
            withTimeout(10_000) { old.join() }
        }
    }
    @Test fun `C cancelled old successful probe cannot replace current access`() = runBlocking<Unit> { replacedProbe(false) }
    @Test fun `C cancelled old failed probe cannot replace current access`() = runBlocking<Unit> { replacedProbe(true) }


    @Test
    fun `G queued local unpin cannot remove a newly authorized pin`() = runBlocking<Unit> {
        val alpha = profile("completion-pin-queue-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-pin-queue-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        container.pins.set(alpha.id, "shared", true)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val once = AtomicBoolean(true)
        container.pins.beforeSet = { if (once.compareAndSet(true, false)) { entered.complete(Unit); release.await() } }
        alphaServer.holdNextPatch().also {
            it.response = MockResponse.Builder().code(404).body("{}").build()
            it.release.countDown()
        }
        val mutation = withContext(mainDispatcher) { vm.pinChat("shared", false); vm.mutationProducer!! }
        try {
            withTimeout(10_000) { entered.await() }
            container.servers.setTokens(alpha.id, Tokens("fresh-pin-owner"))
            container.pins.set(alpha.id, "shared", true)
            release.complete(Unit)
            withTimeout(10_000) { mutation.join() }
            assertThat(container.pins.of(alpha.id)).contains("shared")
        } finally {
            release.complete(Unit)
            container.pins.beforeSet = null
            mutation.cancel()
            withTimeout(10_000) { mutation.join() }
            container.pins.set(alpha.id, "shared", false)
        }
    }

    @Test
    fun `G read callback snapshot cannot borrow newer credentials for unread PATCH`() = runBlocking<Unit> {
        val alpha = profile("completion-read-patch-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-read-patch-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.chats = mapOf("late" to summary("late"))
        val hold = alphaServer.holdNextSnapshot()
        withContext(mainDispatcher) { vm.openChat("late"); vm.chatShown(vm.chat.value!!, true) }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(container.reads.of(alpha.id)["late"]).isEqualTo(50.0)
            container.servers.setTokens(alpha.id, Tokens("fresh-read-owner"))
            hold.release.countDown()
            withTimeout(10_000) { vm.readProducers.toList().forEach { it.join() } }
            assertThat(alphaServer.requests.filter { it.method == "PATCH" }).isEmpty()
        } finally {
            hold.release.countDown()
            withTimeout(10_000) { vm.readProducers.toList().forEach { it.join() } }
        }
    }

    @Test
    fun `H same-profile renewed state survives an old local deletion waiting for admission`() = runBlocking<Unit> {
        val alpha = profile("completion-delete-local-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-delete-local-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.beforeChatDeleteAdmission = { entered.complete(Unit); release.await() }
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        try {
            withTimeout(10_000) { entered.await() }
            assertThat(alphaServer.requests.filter { it.method == "DELETE" }).hasSize(1)
            container.servers.setTokens(alpha.id, Tokens("fresh-local-delete-owner"))
            container.reads.markRead(alpha.id, "shared", 91.0)
            cache.write(alpha.id, "api/sessions/shared/messages", "fresh local-delete bytes")
            release.complete(Unit)
            withTimeout(10_000) { deletion.join() }
            assertThat(container.reads.of(alpha.id)["shared"]).isEqualTo(91.0)
            assertThat(cache.read(alpha.id, "api/sessions/shared/messages")).isEqualTo("fresh local-delete bytes")
        } finally {
            release.complete(Unit)
            vm.beforeChatDeleteAdmission = null
            deletion.cancel()
            withTimeout(10_000) { deletion.join() }
        }
    }


    private suspend fun temporaryRename(stale: Boolean) {
        val alpha = profile("completion-temp-rename-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-temp-rename-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.holdNextPatch().also {
            it.response = MockResponse.Builder().code(404).body("{}").build()
            it.release.countDown()
        }
        val hold = alphaServer.holdNextResume()
        val mutation = withContext(mainDispatcher) { vm.renameChat("shared", "temporary exact title"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            if (stale) container.servers.setTokens(alpha.id, Tokens("new-temp-owner"))
            hold.release.countDown()
            withTimeout(10_000) { mutation.join() }
            val effects = alphaServer.rpcCalls.filter {
                it["method"]?.jsonPrimitive?.content in setOf("session.title", "session.close")
            }
            if (stale) assertThat(effects).isEmpty()
            else {
                assertThat(effects.map { it["method"]!!.jsonPrimitive.content }).containsExactly("session.title", "session.close").inOrder()
                effects.forEach { assertThat(it["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content).isEqualTo("temporary-shared") }
                assertThat(effects.first()["params"]!!.jsonObject["title"]!!.jsonPrimitive.content).isEqualTo("temporary exact title")
            }
        } finally {
            hold.release.countDown()
            mutation.cancel()
            withTimeout(10_000) { mutation.join() }
        }
    }
    @Test fun `G temporary resume cannot title or close after owner renewal`() = runBlocking<Unit> { temporaryRename(true) }
    @Test fun `G current temporary rename titles and closes exact resumed transport target`() = runBlocking<Unit> { temporaryRename(false) }


    @Test
    fun `G replaced temporary resume transport receives no title or close`() = runBlocking<Unit> {
        val alpha = profile("completion-temp-transport-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-temp-transport-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.holdNextPatch().also {
            it.response = MockResponse.Builder().code(404).body("{}").build()
            it.release.countDown()
        }
        val hold = alphaServer.holdNextResume()
        val socket = container.connection.value!!.socket
        val oldTransport = socket.transport.value
        val mutation = withContext(mainDispatcher) { vm.renameChat("shared", "transport-bound title"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            alphaServer.dropSocket()
            withTimeout(10_000) { while (socket.transport.value == oldTransport) delay(5) }
            hold.release.countDown()
            withTimeout(10_000) { mutation.join(); socket.awaitReady() }
            assertThat(socket.transport.value).isNotEqualTo(oldTransport)
            assertThat(alphaServer.rpcCalls.filter { it["method"]?.jsonPrimitive?.content in setOf("session.title", "session.close") }).isEmpty()
        } finally {
            hold.release.countDown()
            mutation.cancel()
            withTimeout(10_000) { mutation.join() }
        }
    }

    @Test
    fun `G switched profile receives no stale REST fallback or error notice`() = runBlocking<Unit> {
        val alpha = profile("completion-fallback-switch-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-fallback-switch-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val hold = alphaServer.holdNextDelete().also { it.response = MockResponse.Builder().code(404).body("{}").build() }
        val deletion = withContext(mainDispatcher) { vm.deleteChat("shared"); vm.mutationProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val since = betaServer.listed.get()
            container.servers.activate(beta.id)
            settle(beta.id, betaServer, since)
            hold.release.countDown()
            withTimeout(10_000) { deletion.join() }
            assertThat(betaServer.rpcCalls.filter { it["method"]?.jsonPrimitive?.content == "session.delete" }).isEmpty()
            assertThat(alphaServer.rpcCalls.filter { it["method"]?.jsonPrimitive?.content == "session.delete" }).isEmpty()
            assertThat(vm.notice.value).isNull()
        } finally {
            hold.release.countDown()
            deletion.cancel()
            withTimeout(10_000) { deletion.join() }
        }
    }

    @Test
    fun `C switched Connection cancels old status and preserves current socket access`() = runBlocking<Unit> {
        val alpha = profile("completion-access-switch-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-access-switch-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val origin = container.connection.value!!
        val hold = alphaServer.holdNextStatus()
        val old = withContext(mainDispatcher) { vm.checkAccess(origin.session.owner(), origin); vm.accessProducer!! }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val since = betaServer.listed.get()
            container.servers.activate(beta.id)
            settle(beta.id, betaServer, since)
            val current = container.connection.value!!
            val currentCheck = vm.accessProducer
            hold.release.countDown()
            withTimeout(10_000) { old.join() }
            assertThat(old.isCancelled).isTrue()
            assertThat(vm.access.value).isEqualTo(Access.OK)
            assertThat(vm.accessProducer).isSameInstanceAs(currentCheck)
            assertThat(current.socket.transport.value).isGreaterThan(0L)
            assertThat(origin.socket.transport.value).isEqualTo(0L)
            assertThat(alphaServer.requests.filter { it.url.encodedPath == "/api/system/stats" }).isEmpty()
        } finally {
            hold.release.countDown()
            old.cancel()
            withTimeout(10_000) { old.join() }
        }
    }


    private suspend fun currentMutation(field: String, value: Boolean) {
        val alpha = profile("completion-current-$field-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-current-$field-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        if (field == "pinned" && !value) container.pins.set(alpha.id, "shared", true)
        try {
            val job = withContext(mainDispatcher) {
                if (field == "pinned") vm.pinChat("shared", value) else vm.archiveChat("shared", value)
                vm.mutationProducer!!
            }
            withTimeout(10_000) { job.join() }
            val patch = alphaServer.requests.single { it.method == "PATCH" }
            assertThat(patch.headers["Authorization"]).isEqualTo("Bearer alpha-token")
            assertThat(HermesJson.parseToJsonElement(patch.body!!.utf8()).jsonObject[field]!!.jsonPrimitive.content).isEqualTo(value.toString())
            if (field == "pinned" && !value) assertThat(container.pins.of(alpha.id)).doesNotContain("shared")
        } finally {
            container.pins.set(alpha.id, "shared", false)
        }
    }
    @Test fun `G current pin mutation sends requested value`() = runBlocking<Unit> { currentMutation("pinned", true) }
    @Test fun `G current unpin mutation clears its local pin`() = runBlocking<Unit> { currentMutation("pinned", false) }
    @Test fun `G current archive mutation sends requested value`() = runBlocking<Unit> { currentMutation("archived", true) }

    @Test
    fun `I cache cleanup cancellation escapes without reporting successful signout`() = runBlocking<Unit> {
        val alpha = profile("completion-cache-cancel-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-cache-cancel-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val cancel = kotlinx.coroutines.CancellationException("synthetic cache clear cancellation")
        dao.deleteFailure = cancel
        try {
            val result = runCatching { vm.signOut(alpha) }
            assertThat(result.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
            assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo(cancel.message)
            assertThat(generateSequence(result.exceptionOrNull()) { it.cause }.any { it === cancel }).isTrue()
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            assertThat(vm.stack.lastOrNull()).isInstanceOf(Screen.Settings::class.java)
        } finally {
            dao.deleteFailure = null
        }
    }


    @Test
    fun `C current anonymous server remains usable without credentials`() = runBlocking<Unit> {
        val alpha = profile("completion-anonymous-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-anonymous-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        assertThat(vm.signOut(alpha)).isTrue()
        assertThat(container.servers.load().tokens[alpha.id]).isNull()
        val check = withContext(mainDispatcher) { vm.checkAccess(); vm.accessProducer!! }
        withTimeout(10_000) { check.join() }
        assertThat(vm.access.value).isEqualTo(Access.OK)
        withTimeout(10_000) { container.connection.value!!.socket.awaitReady() }
        assertThat(container.connection.value!!.socket.transport.value).isGreaterThan(0L)
        assertThat(alphaServer.requests.filter { it.url.encodedPath == "/api/system/stats" }).isEmpty()
    }


    private suspend fun controllerRename(stale: Boolean) {
        val alpha = profile("completion-controller-rename-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-controller-rename-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        alphaServer.chats = mapOf("shared" to summary("shared"))
        withContext(mainDispatcher) { vm.openChat("shared") }
        val chat = vm.chat.value!!
        chat.continuationProducers
        withTimeout(10_000) { while (chat.loadCompletion == null) delay(5); chat.loadCompletion!!.await() }
        val hold = alphaServer.holdNextSnapshot()
        withContext(mainDispatcher) { chat.rename("controller exact title") }
        try {
            assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue()
            if (stale) container.servers.setTokens(alpha.id, Tokens("fresh-controller-owner"))
            hold.release.countDown()
            withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() } }
            val patches = alphaServer.requests.filter { it.method == "PATCH" }
            if (stale) {
                assertThat(patches).isEmpty()
                assertThat(chat.working.title).isEqualTo("shared")
            } else {
                assertThat(patches).hasSize(1)
                assertThat(patches.single().headers["Authorization"]).isEqualTo("Bearer alpha-token")
                assertThat(chat.working.title).isEqualTo("controller exact title")
            }
        } finally {
            hold.release.countDown()
            withTimeout(10_000) { chat.continuationProducers.toList().forEach { it.join() } }
        }
    }
    @Test fun `G controller rename cannot borrow a newer owner after snapshot`() = runBlocking<Unit> { controllerRename(true) }
    @Test fun `G current controller rename applies exact title and credentials`() = runBlocking<Unit> { controllerRename(false) }


    private suspend fun capturedLocalAction(signout: Boolean, stale: Boolean) {
        val alpha = profile("completion-action-root-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-action-root-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val remove = vm.prepareRemoval(alpha)
        val signOut = vm.prepareSignOut(alpha)
        container.servers.setTokens(alpha.id, Tokens("new-action-owner"))
        container.reads.markRead(alpha.id, "fresh", 123.0)
        cache.write(alpha.id, "fresh-action-body", "fresh action exact bytes")
        if (signout) {
            val result = withTimeout(10_000) { (if (stale) signOut else vm.prepareSignOut(alpha))() }
            assertThat(result).isEqualTo(if (stale) LocalSignOutResult.REJECTED else LocalSignOutResult.COMPLETE)
        } else {
            val result = withTimeout(10_000) { (if (stale) remove else vm.prepareRemoval(alpha))() }
            assertThat(result).isEqualTo(!stale)
        }
        if (stale) {
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("new-action-owner")
            assertThat(container.servers.load().servers.map { it.id }).contains(alpha.id)
            assertThat(container.reads.of(alpha.id)["fresh"]).isEqualTo(123.0)
            assertThat(cache.read(alpha.id, "fresh-action-body")).isEqualTo("fresh action exact bytes")
        } else {
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            assertThat(container.reads.of(alpha.id)).isEmpty()
            assertThat(cache.read(alpha.id, "fresh-action-body")).isNull()
        }
        assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")
    }
    @Test fun `A captured signout root cannot retire newer same-profile authorization`() = runBlocking<Unit> { capturedLocalAction(true, true) }
    @Test fun `A fresh captured signout root still clears its current target`() = runBlocking<Unit> { capturedLocalAction(true, false) }
    @Test fun `A captured removal root cannot delete newer same-profile authorization`() = runBlocking<Unit> { capturedLocalAction(false, true) }
    @Test fun `A fresh captured removal root still clears its current target`() = runBlocking<Unit> { capturedLocalAction(false, false) }


    private suspend fun cacheReadAdmission(stale: Boolean) {
        val alpha = profile("completion-cache-reader-alpha", "Alpha", alphaServer.server)
        val beta = profile("completion-cache-reader-beta", "Beta", betaServer.server)
        makeContainer()
        seed(alpha, beta)
        startViewModel(alpha)
        val session = container.connection.value!!.session
        val old = session.chatFor(session.owner()).cached!!
        container.servers.setTokens(alpha.id, Tokens("fresh-cache-reader"))
        val key = cacheKey("api/sessions/shared", emptyMap())
        val body = """{"id":"shared","title":"distinct fresh cached body","last_active":23.0}"""
        dao.put(CachedResponse(alpha.id, key, body.toByteArray().size.toLong(), 77L), listOf(body))
        val requestsBefore = alphaServer.requests.size
        val result = runCatching { (if (stale) old else session.chatFor(session.owner()).cached!!).get("shared") }
        val row = dao.response(alpha.id, key)!!
        if (stale) {
            assertThat(row.used).isEqualTo(77L)
            assertThat(result.exceptionOrNull()).isNotNull()
        } else {
            assertThat(row.used).isGreaterThan(77L)
            assertThat(result.getOrThrow()?.title).isEqualTo("distinct fresh cached body")
        }
        assertThat(dao.parts(alpha.id, key)).containsExactly(body)
        assertThat(alphaServer.requests.size).isEqualTo(requestsBefore)
    }
    @Test fun `F retired cached reader cannot touch newly authorized cache metadata`() = runBlocking<Unit> { cacheReadAdmission(true) }
    @Test fun `F current cached reader returns exact body and updates its own metadata`() = runBlocking<Unit> { cacheReadAdmission(false) }

    private suspend fun unionOpenDraft(text: String): org.evsyukov.hermes.ui.chat.ChatDraft {
        alphaServer.chats = mapOf("shared" to summary("shared"))
        betaServer.chats = mapOf("shared" to summary("shared"))
        val chat = withContext(mainDispatcher) { vm.openChat("shared"); vm.chatShown(vm.chat.value!!, true); vm.chat.value!! }
        withTimeout(10_000) { while (chat.loadCompletion == null) delay(5); chat.loadCompletion!!.await() }
        return withContext(mainDispatcher) {
            checkNotNull(vm.draftOf(chat, container.connection.value)).also {
                it.text = text
                it.attachments = listOf(org.evsyukov.hermes.chat.LocalAttachment("$text.txt", "text/plain", byteArrayOf(7)))
                it.scrollIndex = 3; it.scrollOffset = 29
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.unionSignoutRenewed(renew: Boolean) {
        val alpha = profile("union-native-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-native-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("old draft")
        val oldChat = vm.chat.value!!
        val memory = container.drafts.existing(alpha.id)!!
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        container.lifetime.beforeCleanupAdmission = { owner, removed ->
            if (owner.profileId == alpha.id && !removed && first.compareAndSet(true, false)) { entered.complete(Unit); release.await() }
        }
        val action = withContext(mainDispatcher) { vm.prepareSignOut(alpha) }
        val signout = async(Dispatchers.IO) { action() }
        try {
            withTimeout(10_000) { entered.await() }
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            val fresh = if (renew) {
                withTimeout(10_000) { vm.signIn(browser()) }
                cache.write(alpha.id, "union-fresh", "distinct native cache")
                container.reads.markRead(alpha.id, "union-fresh", 83.0)
                unionOpenDraft("fresh draft")
            } else null
            val route = vm.stack.lastOrNull()
            release.complete(Unit)
            val result = withTimeout(10_000) { signout.await() }
            if (renew) {
                assertThat(result).isEqualTo(LocalSignOutResult.SUPERSEDED)
                assertThat(container.drafts.existing(alpha.id)).isSameInstanceAs(memory)
                assertThat(memory.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))).isSameInstanceAs(fresh)
                assertThat(fresh!!.text).isEqualTo("fresh draft")
                assertThat(fresh.attachments.single().name).isEqualTo("fresh draft.txt")
                assertThat(fresh.scrollOffset).isEqualTo(29)
                assertThat(cache.read(alpha.id, "union-fresh")).isEqualTo("distinct native cache")
                assertThat(container.reads.of(alpha.id)["union-fresh"]).isEqualTo(83.0)
                assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
                assertThat(vm.stack.lastOrNull()).isSameInstanceAs(route)
                assertThat(vm.notice.value).isNull()
            } else {
                assertThat(result).isEqualTo(LocalSignOutResult.COMPLETE)
                assertThat(container.drafts.existing(alpha.id)).isNotSameInstanceAs(memory)
                withContext(mainDispatcher) {
                    assertThat(vm.draftOf(oldChat)).isNull()
                    assertThat(checkNotNull(vm.draftOf(vm.chat.value!!)).text).isEmpty()
                }
                assertThat(container.reads.of(alpha.id)).isEmpty()
                assertThat(container.servers.load().tokens[alpha.id]).isNull()
            }
        } finally { release.complete(Unit); container.lifetime.beforeCleanupAdmission = null; signout.cancel(); withTimeout(10_000) { signout.join() } }
    }
    @Test fun `UNION newer native authorization keeps captured-profile drafts from old signout cleanup`() = runBlocking<Unit> { unionSignoutRenewed(true) }
    @Test fun `UNION current complete signout clears captured drafts and issues a fresh empty binding`() = runBlocking<Unit> { unionSignoutRenewed(false) }

    @Test fun `UNION switched same-ID chat keeps B drafts reads cache tokens and settings visit`() = runBlocking<Unit> {
        val alpha = profile("union-switch-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-switch-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("Alpha words")
        cache.write(alpha.id, "union-A", "Alpha cached")
        container.reads.markRead(alpha.id, "shared", 61.0)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, removed -> if (owner.profileId == alpha.id && !removed) { entered.complete(Unit); release.await() } }
        val action = withContext(mainDispatcher) { vm.prepareSignOut(alpha) }
        val cleanup = async(Dispatchers.IO) { action() }
        try {
            withTimeout(10_000) { entered.await() }
            val before = betaServer.listed.get(); container.servers.activate(beta.id); settle(beta.id, betaServer, before)
            val draft = unionOpenDraft("Beta words"); val chat = vm.chat.value!!
            cache.write(beta.id, "union-B", "Beta cached"); container.reads.markRead(beta.id, "shared", 72.0)
            withContext(mainDispatcher) { vm.pushSettings() }
            val route = vm.stack.lastOrNull(); val visible = container.turns.visibleChat
            release.complete(Unit)
            assertThat(withTimeout(10_000) { cleanup.await() }).isEqualTo(LocalSignOutResult.SUPERSEDED)
            assertThat(container.drafts.existing(alpha.id)).isNull()
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            assertThat(container.reads.of(alpha.id)).isEmpty()
            assertThat(cache.read(alpha.id, "union-A")).isNull()
            assertThat(vm.chat.value).isSameInstanceAs(chat)
            assertThat(vm.stack.lastOrNull()).isSameInstanceAs(route)
            assertThat(container.turns.visibleChat).isEqualTo(visible)
            assertThat(vm.notice.value).isNull()
            assertThat(draft.text).isEqualTo("Beta words")
            assertThat(draft.attachments.single().name).isEqualTo("Beta words.txt")
            assertThat(draft.scrollIndex).isEqualTo(3)
            assertThat(container.reads.of(beta.id)["shared"]).isEqualTo(72.0)
            assertThat(cache.read(beta.id, "union-B")).isEqualTo("Beta cached")
            assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")
        } finally { release.complete(Unit); container.lifetime.beforeCleanupAdmission = null; cleanup.cancel(); withTimeout(10_000) { cleanup.join() } }
    }

    private suspend fun unionPartial(cacheFails: Boolean, cancel: Boolean = false) {
        val alpha = profile("union-partial-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-partial-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        val draft = unionOpenDraft("Alpha retained")
        val memory = container.drafts.existing(alpha.id)!!
        val betaMemory = container.drafts.of(beta.id)
        withContext(mainDispatcher) { betaMemory.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))!!.text = "Beta retained"; vm.pushSettings() }
        val route = vm.stack.lastOrNull()
        val failure = IllegalStateException("union local I/O failure")
        if (cacheFails) dao.deleteFailure = if (cancel) kotlinx.coroutines.CancellationException("union cache cancellation") else failure
        else container.reads.beforeClear = { throw failure }
        try {
            val action = withContext(mainDispatcher) { vm.prepareSignOut(alpha) }
            val result = runCatching { action() }
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            assertThat(vm.stack.lastOrNull()).isSameInstanceAs(route)
            assertThat(container.drafts.existing(beta.id)).isSameInstanceAs(betaMemory)
            assertThat(betaMemory.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))!!.text).isEqualTo("Beta retained")
            if (cancel) {
                assertThat(result.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
                assertThat(container.drafts.existing(alpha.id)).isSameInstanceAs(memory)
                assertThat(draft.text).isEqualTo("Alpha retained")
                assertThat(vm.notice.value).isNull()
            } else {
                assertThat(result.getOrThrow()).isEqualTo(LocalSignOutResult.INCOMPLETE)
                assertThat(container.drafts.existing(alpha.id)).isNull()
                assertThat(vm.notice.value).contains(alpha.name)
            }
        } finally { dao.deleteFailure = null; container.reads.beforeClear = null }
    }
    @Test fun `UNION cache partial failure still clears captured drafts without success navigation`() = runBlocking<Unit> { unionPartial(true) }
    @Test fun `UNION read partial failure still clears captured drafts without success navigation`() = runBlocking<Unit> { unionPartial(false) }
    @Test fun `UNION cancelled cache cleanup retains uncleared drafts and cannot publish success`() = runBlocking<Unit> { unionPartial(true, true) }

    @Test fun `UNION renewed same-profile authorization preserves drafts while old deletion waits for admission`() = runBlocking<Unit> {
        val alpha = profile("union-delete-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-delete-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("old delete draft")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.beforeChatDeleteAdmission = { entered.complete(Unit); release.await() }
        withContext(mainDispatcher) { vm.deleteChat("shared") }
        val deletion = checkNotNull(vm.mutationProducer)
        try {
            withTimeout(10_000) { entered.await() }
            withTimeout(10_000) { vm.signIn(browser()) }
            val fresh = unionOpenDraft("fresh delete draft")
            cache.write(alpha.id, "union-new-delete", "fresh cache")
            container.reads.markRead(alpha.id, "shared", 93.0)
            val chat = vm.chat.value
            release.complete(Unit); withTimeout(10_000) { deletion.join() }
            assertThat(container.drafts.existing(alpha.id)?.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))).isSameInstanceAs(fresh)
            assertThat(fresh.text).isEqualTo("fresh delete draft")
            assertThat(vm.chat.value).isSameInstanceAs(chat)
            assertThat(container.reads.of(alpha.id)["shared"]).isEqualTo(93.0)
            assertThat(cache.read(alpha.id, "union-new-delete")).isEqualTo("fresh cache")
            assertThat(vm.notice.value).isNull()
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
        } finally { release.complete(Unit); vm.beforeChatDeleteAdmission = null; withTimeout(10_000) { deletion.join() } }
    }

    @Test fun `UNION current deletion clears only captured draft read and cache after real producer completion`() = runBlocking<Unit> {
        val alpha = profile("union-current-delete-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-current-delete-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("delete current")
        val other = container.drafts.of(beta.id)
        withContext(mainDispatcher) { other.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))!!.text = "keep Beta" }
        container.reads.markRead(alpha.id, "shared", 55.0)
        cache.write(alpha.id, "api/sessions/shared", "delete cached")
        withContext(mainDispatcher) { vm.deleteChat("shared") }
        val deletion = checkNotNull(vm.mutationProducer)
        withTimeout(10_000) { deletion.join() }
        assertThat(container.drafts.existing(alpha.id)?.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))?.text).isEmpty()
        assertThat(container.reads.of(alpha.id)).doesNotContainKey("shared")
        assertThat(cache.read(alpha.id, "api/sessions/shared")).isNull()
        assertThat(other.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))!!.text).isEqualTo("keep Beta")
        assertThat(vm.chat.value?.state?.value?.storedId).isNull()
        assertThat(vm.notice.value).isNull()
    }

    @Test fun `UNION recreated profile native drafts survive old removal marker cleanup`() = runBlocking<Unit> {
        val alpha = profile("union-recreated-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-recreated-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("before removal")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, removed ->
            if (owner.profileId == alpha.id && removed) { entered.complete(Unit); release.await() }
        }
        val removal = async(Dispatchers.IO) { vm.removeServer(alpha) }
        try {
            withTimeout(10_000) { entered.await() }
            assertThat(container.servers.load().servers.map { it.id }).doesNotContain(alpha.id)
            val before = alphaServer.listed.get()
            container.servers.save(alpha)
            settle(alpha.id, alphaServer, before)
            withTimeout(10_000) { vm.signIn(browser()) }
            val fresh = unionOpenDraft("recreated fresh")
            val memory = container.drafts.existing(alpha.id)
            cache.write(alpha.id, "union-recreated", "recreated exact cache")
            container.reads.markRead(alpha.id, "shared", 96.0)
            release.complete(Unit)
            assertThat(withTimeout(10_000) { removal.await() }).isTrue()
            assertThat(container.drafts.existing(alpha.id)).isSameInstanceAs(memory)
            assertThat(memory?.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))).isSameInstanceAs(fresh)
            assertThat(fresh.text).isEqualTo("recreated fresh")
            assertThat(fresh.attachments.single().name).isEqualTo("recreated fresh.txt")
            assertThat(container.reads.of(alpha.id)["shared"]).isEqualTo(96.0)
            assertThat(cache.read(alpha.id, "union-recreated")).isEqualTo("recreated exact cache")
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
            assertThat(vm.notice.value).isNull()
        } finally { release.complete(Unit); container.lifetime.beforeCleanupAdmission = null; removal.cancel(); withTimeout(10_000) { removal.join() } }
    }

    @Test fun `UNION complete inactive profile removal clears only its captured memory`() = runBlocking<Unit> {
        val alpha = profile("union-remove-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-remove-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("remove Alpha")
        val before = betaServer.listed.get(); container.servers.activate(beta.id); settle(beta.id, betaServer, before)
        val betaDraft = unionOpenDraft("keep current Beta")
        val chat = vm.chat.value
        assertThat(vm.removeServer(alpha)).isTrue()
        assertThat(container.drafts.existing(alpha.id)).isNull()
        assertThat(container.servers.load().servers.map { it.id }).doesNotContain(alpha.id)
        assertThat(betaDraft.text).isEqualTo("keep current Beta")
        assertThat(vm.chat.value).isSameInstanceAs(chat)
        assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")
    }

    @Test fun `UNION delayed removal event retains its original marker across repeated same-ID removal`() = runBlocking<Unit> {
        val alpha = profile("union-repeat-alpha", "Alpha", alphaServer.server)
        val beta = profile("union-repeat-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("captured Alpha")
        val before = betaServer.listed.get(); container.servers.activate(beta.id); settle(beta.id, betaServer, before)
        val betaDraft = unionOpenDraft("keep Beta")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstAdmission = kotlinx.coroutines.CompletableDeferred<org.evsyukov.hermes.data.server.WorkOwner>()
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var latest: org.evsyukov.hermes.data.server.WorkOwner? = null
        container.beforeDraftRemoval = { id -> if (id == alpha.id && !entered.isCompleted) { entered.complete(Unit); release.await() } }
        container.lifetime.beforeCleanupAdmission = { owner, removed -> if (removed && owner.profileId == alpha.id) firstAdmission.complete(owner) }
        container.afterDraftRemoval = { owner -> if (owner == latest) completed.complete(Unit) }
        try {
            val first = container.servers.remove(alpha.id) as org.evsyukov.hermes.data.server.NativeResult.Applied
            withTimeout(10_000) { entered.await() }
            container.servers.save(alpha, makeActive = false)
            val second = container.servers.remove(alpha.id) as org.evsyukov.hermes.data.server.NativeResult.Applied
            latest = second.owner
            assertThat(second.owner).isNotEqualTo(first.owner)
            release.complete(Unit)
            assertThat(withTimeout(10_000) { firstAdmission.await() }).isEqualTo(first.owner)
            withTimeout(10_000) { completed.await() }
            assertThat(container.drafts.existing(alpha.id)).isNull()
            assertThat(betaDraft.text).isEqualTo("keep Beta")
        } finally {
            release.complete(Unit)
            container.beforeDraftRemoval = null; container.afterDraftRemoval = null
            container.lifetime.beforeCleanupAdmission = null
        }
    }

    private fun summary(id: String) =
        """{"id":"$id","title":"$id","source":"android","started_at":40.0,"last_active":50.0,"message_count":2}"""

    /** A cipher whose encryption can fail, while the rest works. */
    private class TestCipher : TokenCipher {
        val failing = AtomicBoolean(false)

        override fun encrypt(plain: String): String {
            if (failing.get()) throw IOException("storage is unavailable")
            return plain
        }

        override fun decrypt(sealed: String) = sealed
    }

    /**
     * The real Room DAO with its next prefix deletion held, so a test can put a write behind the
     * cleanup. Every other operation reaches Room untouched.
     */
    private class GatedDao(private val delegate: ResponseDao) : ResponseDao() {
        @Volatile var deleteFailure: RuntimeException? = null
        val deletes = AtomicInteger()
        class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

        private val allGates = java.util.concurrent.CopyOnWriteArrayList<Gate>()
        fun releaseGates() = allGates.forEach { it.release.countDown() }

        private var next: Gate? = null
        private val armed = AtomicBoolean(false)

        fun holdNextDeleteStarting(): Gate {
            val gate = Gate().also { allGates += it }
            synchronized(this) {
                next = gate
                armed.set(true)
            }
            return gate
        }

        override suspend fun deleteStarting(server: String, prefix: String) {
            deletes.incrementAndGet()
            deleteFailure?.let { throw it }
            val gate = synchronized(this) {
                if (armed.compareAndSet(true, false)) next.also { next = null } else null
            }
            gate?.entered?.countDown()
            if (gate != null) check(gate.release.await(10, TimeUnit.SECONDS)) { "DAO deletion gate was not released" }
            delegate.deleteStarting(server, prefix)
        }

        override suspend fun parts(server: String, request: String) = delegate.parts(server, request)
        override suspend fun response(server: String, request: String) = delegate.response(server, request)
        override suspend fun touch(server: String, request: String, used: Long) = delegate.touch(server, request, used)
        override suspend fun total() = delegate.total()
        override suspend fun byUse() = delegate.byUse()
        override suspend fun put(response: CachedResponse, parts: List<String>) = delegate.put(response, parts)
        override suspend fun delete(server: String, request: String) = delegate.delete(server, request)
        override suspend fun clear() = delegate.clear()

        protected override suspend fun upsert(response: CachedResponse) = unused()
        protected override suspend fun insertParts(parts: List<ResponsePart>) = unused()
        protected override suspend fun deleteParts(server: String, request: String) = unused()
        protected override suspend fun deleteResponse(server: String, request: String) = unused()
        protected override suspend fun deletePartsLike(server: String, prefix: String) = unused()
        protected override suspend fun deleteResponsesLike(server: String, prefix: String) = unused()
        protected override suspend fun deleteAllParts() = unused()
        protected override suspend fun deleteAllResponses() = unused()

        private fun unused(): Nothing = error("the gated DAO delegates whole operations")
    }

    private companion object {
        const val EMPTY_SESSIONS = """{"sessions":[],"total":0}"""
        const val FRESH_TOKENS = """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_at":4102444800,"provider":"native"}"""
        const val LATER_TOKENS = """{"access_token":"later-access","refresh_token":"later-refresh-2","expires_at":4102444800,"provider":"native"}"""
        const val LATE_CHATS = """{"sessions":[{"id":"late","title":"Late","source":"android","started_at":40.0,"last_active":50.0,"message_count":2}],"total":1}"""
        const val BETA_CHATS = """{"sessions":[{"id":"beta-late","title":"Beta late","source":"android","started_at":10.0,"last_active":20.0,"message_count":1}],"total":1}"""
    }
}
