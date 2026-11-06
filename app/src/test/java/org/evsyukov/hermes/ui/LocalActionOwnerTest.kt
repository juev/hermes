package org.evsyukov.hermes.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.cache.CacheDatabase
import org.evsyukov.hermes.data.cache.CachedResponse
import org.evsyukov.hermes.data.cache.ResponseCache
import org.evsyukov.hermes.data.cache.ResponseDao
import org.evsyukov.hermes.data.cache.ResponsePart
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The owner of the two destructive local actions: the profile form can go away while the phone is
 * still deleting, and a sign-out that finishes late must not navigate or probe the server that
 * replaced the one it was opened for. Room and DataStore are real; caller-scope cancellation models
 * disposal here. The separate form tests exercise composition and confirmation callbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LocalActionOwnerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val main = Executors.newSingleThreadExecutor { Thread(it, "owner-test-main") }
    private val mainDispatcher = main.asCoroutineDispatcher()
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: CacheDatabase
    private lateinit var dao: GatedDao
    private lateinit var cache: ResponseCache
    private lateinit var container: AppContainer
    private lateinit var vm: MainViewModel
    /** Profiles this method seeded, removed in teardown so the shared store starts clean. */
    private val ownProfiles = mutableSetOf<String>()
    private var alphaStarted = false
    private var betaStarted = false
    private var mainInstalled = false
    private var testCipher: TestCipher? = null
    private val alphaServer = MockWebServer()
    private val betaServer = MockWebServer()
    private val alphaListed = AtomicInteger()
    private val betaListed = AtomicInteger()

    @Before
    fun setUp() {
        try {
            // The container schedules the activity check while it is built.
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
            startHealthy(alphaServer, alphaListed)
            alphaStarted = true
            setupFailure?.let { throw it }
            startHealthy(betaServer, betaListed)
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
        val alpha = profile("review-reset-alpha", "Alpha", alphaServer)
        val beta = profile("review-reset-beta", "Beta", betaServer)
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
        }
        fixtureCleanup.finish(
            owners,
            beforeJoin = listOf(
                "release cipher gates" to { testCipher?.releaseGates() },
                "release DAO gates" to { if (::dao.isInitialized) dao.releaseGates() },
                "stop socket" to {
                    if (::container.isInitialized) container.connection.value?.socket?.stop()
                },
                "reset profiles" to {
                    if (::container.isInitialized) container.servers.afterCredentialWrite = null
                    resetFailure?.let { throw it }
                    if (::container.isInitialized) {
                        testCipher?.failEncrypt = false
                        testCipher?.failDecrypt = false
                        resetting = true
                        try { runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(container.servers, ownProfiles) { resetResults += it } } } finally { resetting = false }
                    }
                },
            ),
            afterJoin = listOf(
                "close Room" to { if (::db.isInitialized) db.close() },
                "close alpha" to { if (alphaStarted) { alphaServer.close(); alphaStarted = false } },
                "close beta" to { if (betaStarted) { betaServer.close(); betaStarted = false } },
                "reset Main" to { if (mainInstalled) { Dispatchers.resetMain(); mainInstalled = false } },
                "close dispatcher" to { mainDispatcher.close() },
                "report close failure" to { closeFailure?.let { throw it } },
            ),
        )
    }

    private fun startHealthy(server: MockWebServer, listed: AtomicInteger) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when (path) {
                    "/api/status" -> json("""{"auth_required":false}""")
                    "/api/sessions" -> {
                        listed.incrementAndGet()
                        json("""{"sessions":[],"total":0}""")
                    }
                    else -> MockResponse.Builder().code(404).body("{}").build()
                }
            }
        }
        server.start()
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
        // The first connection builds the chat of the server on the view-model dispatcher.
        withTimeout(10_000) { vm.chat.filterNotNull().first() }
        // That collector clears the stack after it publishes the chat; the visit is opened only once
        // the task that clears it is done, or the clear would take the new visit away.
        withContext(mainDispatcher) { vm.pushSettings() }
        withTimeout(10_000) { vm.access.first { it == Access.OK } }
    }

    private suspend fun seedLocalData(alpha: ServerProfile, beta: ServerProfile) {
        val key = cacheKey("api/sessions/s1", emptyMap())
        cache.write(alpha.id, key, """{"alpha":true}""")
        cache.write(beta.id, key, """{"beta":true}""")
        container.reads.markRead(alpha.id, "s1", 10.0)
        container.reads.markRead(beta.id, "s1", 20.0)
    }

    @Test
    fun `a removal finishes after the form goes away and leaves the other profile alone`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-removal", "Alpha", alphaServer)
        val beta = profile("owner-beta-removal", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())
        val attachment = container.attachments.fileFor(alpha.id, "/api/fs/download?path=report.pdf").apply { writeText("pdf") }

        val gate = cipher.holdNextEncrypt()
        val caller = launch(Dispatchers.IO) { vm.removeServer(alpha) }
        val chatBefore = vm.chat.value
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue()
        // The form is disposed with the connection while the profile is still being removed.
        caller.cancel()
        gate.release.countDown()
        caller.join()

        withTimeout(10_000) { container.servers.state.first { state -> state.servers.none { it.id == alpha.id } } }
        withTimeout(10_000) { container.connection.filterNotNull().first { it.session.profile.id == beta.id } }
        withTimeout(10_000) { vm.chat.filterNotNull().first { it !== chatBefore } }
        // The caller was cancelled with the form, but the cleanup of the confirmed server goes on.
        withTimeout(10_000) { while (cache.read(alpha.id, key) != null) delay(10) }
        withTimeout(10_000) { while (container.reads.of(alpha.id).isNotEmpty()) delay(10) }

        val stored = container.servers.load()
        assertThat(stored.servers.map { it.id }).containsExactly(beta.id)
        assertThat(stored.tokens[alpha.id]).isNull()
        assertThat(stored.tokens[beta.id]?.accessToken).isEqualTo("beta-token")
        assertThat(cache.read(alpha.id, key)).isNull()
        assertThat(cache.read(beta.id, key)).isNotNull()
        assertThat(container.reads.of(alpha.id)).isEmpty()
        assertThat(container.reads.of(beta.id)).containsKey("s1")
        assertThat(attachment.isFile).isTrue()
        assertThat(attachment.readText()).isEqualTo("pdf")
        assertThat(vm.stack).isEmpty()
    }

    @Test
    fun `a failed removal leaves the profile and reports the failure for it`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-failed-removal", "Alpha", alphaServer)
        val beta = profile("owner-beta-failed-removal", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())
        cipher.failEncrypt = true

        val removed = withTimeout(10_000) { vm.removeServer(alpha) }

        assertThat(removed).isFalse()
        assertThat(container.servers.load().servers.map { it.id }).containsExactly(alpha.id, beta.id)
        assertThat(cache.read(alpha.id, key)).isNotNull()
        assertThat(container.reads.of(alpha.id)).containsKey("s1")
        assertThat(vm.notice.value).isEqualTo(container.strings.get(R.string.setup_remove_failed, alpha.name))
        assertThat(vm.stack.lastOrNull()).isInstanceOf(Screen.Settings::class.java)
    }

    @Test
    fun `a failed sign-out reports it and leaves the server signed in`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-failed-sign-out", "Alpha", alphaServer)
        val beta = profile("owner-beta-failed-sign-out", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())
        cipher.failEncrypt = true

        val signedOut = withTimeout(10_000) { vm.signOut(alpha) }

        assertThat(signedOut).isFalse()
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("alpha-token")
        assertThat(cache.read(alpha.id, key)).isNotNull()
        assertThat(container.reads.of(alpha.id)).containsKey("s1")
        assertThat(vm.notice.value).isEqualTo(container.strings.get(R.string.settings_sign_out_failed, alpha.name))
        assertThat(vm.stack.lastOrNull()).isInstanceOf(Screen.Settings::class.java)
    }

    @Test
    fun `an unreadable storage after a failed removal is not taken for absence`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-unreadable-removal", "Alpha", alphaServer)
        val beta = profile("owner-beta-unreadable-removal", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())
        cipher.failEncrypt = true
        cipher.failDecrypt = true

        val removed = withTimeout(10_000) { vm.removeServer(alpha) }

        assertThat(removed).isFalse()
        assertThat(vm.notice.value).isEqualTo(container.strings.get(R.string.setup_remove_failed, alpha.name))
        // The failed write left the old sealed bytes alone; they are readable again now.
        cipher.failDecrypt = false
        val stored = container.servers.load()
        assertThat(stored.servers.map { it.id }).containsExactly(alpha.id, beta.id)
        assertThat(stored.tokens[alpha.id]?.accessToken).isEqualTo("alpha-token")
        assertThat(cache.read(alpha.id, key)).isNotNull()
        assertThat(container.reads.of(alpha.id)).containsKey("s1")
    }

    @Test
    fun `an unreadable storage after a failed sign-out keeps the server signed in`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-unreadable-sign-out", "Alpha", alphaServer)
        val beta = profile("owner-beta-unreadable-sign-out", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())
        cipher.failEncrypt = true
        cipher.failDecrypt = true

        val signedOut = withTimeout(10_000) { vm.signOut(alpha) }

        assertThat(signedOut).isFalse()
        assertThat(vm.notice.value).isEqualTo(container.strings.get(R.string.settings_sign_out_failed, alpha.name))
        cipher.failDecrypt = false
        assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("alpha-token")
        assertThat(cache.read(alpha.id, key)).isNotNull()
        assertThat(container.reads.of(alpha.id)).containsKey("s1")
    }

    @Test
    fun `a late sign-out does not pop or probe the server that replaced it`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-late-sign-out", "Alpha", alphaServer)
        val beta = profile("owner-beta-late-sign-out", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())

        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.afterSignoutCleanup = { entered.complete(Unit); release.await() }
        val signOut = launch(Dispatchers.IO) { vm.signOut(alpha) }
        try {
            withTimeout(10_000) { entered.await() }

            // The token change is done; the user switched to the other server, which became current,
            // after the old cleanup and before its navigation continuation.
            container.servers.activate(beta.id)
            withTimeout(10_000) {
                while (true) {
                    val current = container.connection.value
                    if (current?.session?.profile?.id == beta.id &&
                        vm.access.value == Access.OK &&
                        !vm.sidebar.value.loading &&
                        betaListed.get() > 0
                    ) break
                    delay(10)
                }
            }
            withContext(mainDispatcher) { vm.pushSettings() }
            val opened = vm.stack.lastOrNull()
            assertThat(opened).isInstanceOf(Screen.Settings::class.java)
            val requestsBefore = betaServer.requestCount

            release.complete(Unit)
            signOut.join()
            // Give the main dispatcher every chance to act on the completed cleanup before the count.
            withContext(mainDispatcher) { }
            withTimeout(10_000) { container.servers.state.first { it.tokens[alpha.id] == null } }

            assertThat(vm.stack.lastOrNull()).isSameInstanceAs(opened)
            assertThat(betaServer.requestCount).isEqualTo(requestsBefore)
            assertThat(cache.read(alpha.id, key)).isNull()
            assertThat(cache.read(beta.id, key)).isNotNull()
            assertThat(container.reads.of(alpha.id)).isEmpty()
            assertThat(container.reads.of(beta.id)).containsKey("s1")
            assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")
        } finally { release.complete(Unit); withTimeout(10_000) { signOut.join() }; vm.afterSignoutCleanup = null }
    }

    @Test
    fun `a late sign-out does not pop a settings visit opened after it`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-late-route", "Alpha", alphaServer)
        val beta = profile("owner-beta-late-route", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())

        val gate = dao.holdNextDeleteStarting()
        val signOut = launch(Dispatchers.IO) { vm.signOut(alpha) }
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue()

        // The same server stays current, but the user left this settings visit and opened another.
        vm.pop()
        vm.pushSettings()
        val replacement = vm.stack.lastOrNull()
        assertThat(replacement).isInstanceOf(Screen.Settings::class.java)

        gate.release.countDown()
        signOut.join()
        withTimeout(10_000) { container.servers.state.first { it.tokens[alpha.id] == null } }

        assertThat(vm.stack.lastOrNull()).isSameInstanceAs(replacement)
        assertThat(cache.read(alpha.id, key)).isNull()
        assertThat(container.reads.of(alpha.id)).isEmpty()
        assertThat(cache.read(beta.id, key)).isNotNull()
        assertThat(container.reads.of(beta.id)).containsKey("s1")
    }

    @Test
    fun `signing out the current server still closes settings and checks access`() = runBlocking<Unit> {
        val alpha = profile("owner-alpha-current-sign-out", "Alpha", alphaServer)
        val beta = profile("owner-beta-current-sign-out", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        startViewModel(alpha)
        seedLocalData(alpha, beta)
        val key = cacheKey("api/sessions/s1", emptyMap())
        val requestsBefore = alphaServer.requestCount

        val signedOut = withTimeout(10_000) { vm.signOut(alpha) }

        assertThat(signedOut).isTrue()
        withTimeout(10_000) { vm.access.filter { it == Access.OK }.first() }
        withTimeout(10_000) { while (alphaServer.requestCount <= requestsBefore) delay(10) }
        assertThat(vm.stack).isEmpty()
        assertThat(alphaServer.requestCount).isGreaterThan(requestsBefore)
        assertThat(container.servers.load().tokens[alpha.id]).isNull()
        assertThat(cache.read(alpha.id, key)).isNull()
        assertThat(container.reads.of(alpha.id)).isEmpty()
        assertThat(cache.read(beta.id, key)).isNotNull()
        assertThat(container.reads.of(beta.id)).containsKey("s1")
    }


    @Test
    fun `I R2 unreadable postwrite clear remains indeterminate and preserves original error`() = runBlocking<Unit> {
        val alpha = profile("completion-r2-postwrite-alpha", "Alpha", alphaServer)
        val beta = profile("completion-r2-postwrite-beta", "Beta", betaServer)
        val cipher = TestCipher()
        makeContainer(cipher)
        seed(alpha, beta)
        seedLocalData(alpha, beta)
        val owner = container.lifetime.owner(alpha.id)
        val original = IOException("synthetic after-write failure")
        container.servers.afterCredentialWrite = { cipher.failDecrypt = true; throw original }
        try {
            val result = container.lifetime.retireAndClear(container.servers, alpha.id)
            assertThat(result).isInstanceOf(org.evsyukov.hermes.data.server.NativeResult.Failed::class.java)
            assertThat((result as org.evsyukov.hermes.data.server.NativeResult.Failed).error).isSameInstanceAs(original)
            assertThat(container.lifetime.owner(alpha.id)).isEqualTo(owner)
            assertThat(container.lifetime.credentials(alpha.id, owner.generation)?.accessToken).isEqualTo("alpha-token")
            assertThat(container.reads.of(alpha.id)).containsKey("s1")
            assertThat(cache.read(alpha.id, cacheKey("api/sessions/s1", emptyMap()))).isNotNull()
        } finally {
            container.servers.afterCredentialWrite = null
            cipher.failDecrypt = false
        }
        // Readable durable bytes show the clear happened; this is not a claim that unreadable meant absence.
        assertThat(container.servers.load().tokens[alpha.id]).isNull()
        assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")
    }

    @Test
    fun `I R2 readable postwrite absence can establish applied retirement`() = runBlocking<Unit> {
        val alpha = profile("completion-r2-absent-alpha", "Alpha", alphaServer)
        val beta = profile("completion-r2-absent-beta", "Beta", betaServer)
        makeContainer()
        seed(alpha, beta)
        val original = IOException("synthetic readable after-write failure")
        container.servers.afterCredentialWrite = { throw original }
        try {
            val result = container.lifetime.retireAndClear(container.servers, alpha.id)
            assertThat(result).isInstanceOf(org.evsyukov.hermes.data.server.NativeResult.Applied::class.java)
            assertThat(container.servers.load().tokens[alpha.id]).isNull()
            assertThat(container.servers.load().tokens[beta.id]?.accessToken).isEqualTo("beta-token")
        } finally {
            container.servers.afterCredentialWrite = null
        }
    }

    /**
     * The real Room DAO with its next prefix deletion held, so a test can look at the state after
     * the tokens changed but before the rows go. Every other operation reaches Room untouched.
     */
    private class GatedDao(private val delegate: ResponseDao) : ResponseDao() {
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

    /** A cipher whose next encryption can be held, or that can fail, while the rest works. */
    private class TestCipher : TokenCipher {
        @Volatile
        var failEncrypt = false
        @Volatile
        var failDecrypt = false
        private val allGates = java.util.concurrent.CopyOnWriteArrayList<Gate>()
        fun releaseGates() = allGates.forEach { it.release.countDown() }
        private var next: Gate? = null
        private val armed = AtomicBoolean(false)

        class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

        fun holdNextEncrypt(): Gate {
            val gate = Gate().also { allGates += it }
            synchronized(this) {
                next = gate
                armed.set(true)
            }
            return gate
        }

        override fun encrypt(plain: String): String {
            val gate = synchronized(this) {
                if (armed.compareAndSet(true, false)) next.also { next = null } else null
            }
            gate?.entered?.countDown()
            if (gate != null) check(gate.release.await(10, TimeUnit.SECONDS)) { "Cipher gate was not released" }
            if (failEncrypt) throw IOException("storage is unavailable")
            return plain
        }

        override fun decrypt(sealed: String): String {
            if (failDecrypt) throw IOException("storage is unreadable")
            return sealed
        }
    }
}
