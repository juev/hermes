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
    private val preparationRoots = CopyOnWriteArrayList<Job>()
    private val protocolControls = CopyOnWriteArrayList<ReadyProtocolControl>()
    private val gates = CopyOnWriteArrayList<Gate>()
    private lateinit var ownedState: OwnedReadyState
    private val createdContainers = CopyOnWriteArrayList<AppContainer>()
    private var cleanupDeadlineNanos = 0L
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
            ownedState = OwnedReadyState()
            ownedState.open()
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
    internal val fixtureIndependentDependenciesClosed: Boolean get() = !fixtureDatabaseOpen && !alphaStarted && !betaStarted && !mainInstalled && main.isShutdown
    internal val fixtureDependenciesClosed: Boolean get() = fixtureIndependentDependenciesClosed && (!::ownedState.isInitialized || ownedState.closed)
    internal var fixtureDeleteOwnedDirectory: ((java.io.File) -> Boolean)?
        get() = ownedState.deleteDirectory
        set(value) { ownedState.deleteDirectory = value }
    internal val fixtureOwnedStoreRoots: List<Job> get() = ownedState.actualRoots
    internal val fixtureOwnedDirectory: java.io.File? get() = ownedState.actualDirectory
    internal val fixtureOwnedFiles: List<java.io.File> get() = ownedState.actualFiles
    internal val fixtureUserRoots: List<Job> get() = buildList {
        if (::vm.isInitialized) add(vm.viewModelScope.coroutineContext.job)
        if (::container.isInitialized) add(container.scope.coroutineContext.job)
        createdContainers.filter { !::container.isInitialized || it !== container }.forEach { add(it.scope.coroutineContext.job) }
        addAll(preparationRoots)
        add(cacheScope.coroutineContext.job)
        add(browserScope.coroutineContext.job)
    }
    internal fun fixtureProducerScope(): CoroutineScope = cacheScope
    internal val fixtureApplication: Application get() = context
    internal fun fixtureCreateContainer(): AppContainer = makeContainer()
    internal fun fixtureAwaitMainTermination(): Boolean = main.awaitTermination(10, TimeUnit.SECONDS)

    @After
    fun tearDown() {
        val owners = buildList<Job> {
            if (::vm.isInitialized) add(vm.viewModelScope.coroutineContext.job)
            if (::container.isInitialized) add(container.scope.coroutineContext.job)
            createdContainers.filter { !::container.isInitialized || it !== container }.forEach { add(it.scope.coroutineContext.job) }
            addAll(preparationRoots)
        add(cacheScope.coroutineContext.job)
            add(browserScope.coroutineContext.job)
        }
        cleanupDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fixtureCleanup.joinMillis)
        fixtureCleanup.finish(
            owners,
            beforeJoin = listOf(
                "release protocol controls" to { protocolControls.forEach { it.releaseBoth() } },
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
                        container.lifetime.beforeProfileMint = null
                        container.afterActiveProfileObservation = null
                        if (::vm.isInitialized) {
                            vm.beforeInitialConnection = null
                            vm.beforeInitialOwnerAdmission = null
                            vm.beforeStoredController = null
                        }
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
                    if (::container.isInitialized && !ownedState.retired) {
                        testCipher?.failing?.set(false)
                        resetting = true
                        try { runBlocking { org.evsyukov.hermes.testing.resetFixtureProfiles(container.servers, ownProfiles) { resetResults += it } } } finally { resetting = false }
                    }
                },
            ),
            afterJoin = listOf<Pair<String, () -> Unit>>(
                "close owned stores" to { if (::ownedState.isInitialized) ownedState.close(cleanupDeadlineNanos) },
            ) + listOf<Pair<String, () -> Unit>>(
                "close Room" to { if (::db.isInitialized) db.close() },
                "close alpha" to { if (alphaStarted) { alphaServer.server.close(); alphaStarted = false } },
                "close beta" to { if (betaStarted) { betaServer.server.close(); betaStarted = false } },
                "reset Main" to { if (mainInstalled) { Dispatchers.resetMain(); mainInstalled = false } },
                "close dispatcher" to { mainDispatcher.close() },
                "report close failure" to { closeFailure?.let { throw it } },
            ),
        )
        // Supplemental receipt after the existing root joins and dependent closure succeed.
        println("READY fixture cleanup: roots=${owners.map { it.toString() }} dependenciesClosed=$fixtureDependenciesClosed")
    }

    /** Concrete stores belong to this fixture; recreating its container keeps the same files. */
    private class OwnedReadyState {
        private val roots = mutableListOf<Job>()
        private val files = mutableListOf<java.io.File>()
        private var directory: java.io.File? = null
        var deleteDirectory: ((java.io.File) -> Boolean)? = null
        val actualRoots: List<Job> get() = roots.toList()
        val retired: Boolean get() = roots.isNotEmpty() && roots.all { !it.isActive }
        val actualDirectory: java.io.File? get() = directory
        val actualFiles: List<java.io.File> get() = files.toList()
        var closed = false
            private set
        lateinit var settings: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            private set
        lateinit var servers: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            private set
        lateinit var pins: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            private set
        lateinit var reads: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            private set
        lateinit var observations: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            private set
        lateinit var push: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
            private set
        private val unexpectedPushActions = CopyOnWriteArrayList<String>()
        val distributor = object : org.evsyukov.hermes.push.PushDistributor {
            override fun installed(): List<org.evsyukov.hermes.push.Distributor> = emptyList()
            override fun saved(): String? = null
            override fun save(packageName: String) {
                unexpectedPushActions += "save"
                error("No distributor is installed in this fixture")
            }
            override fun register(instance: String, name: String, vapid: String): Boolean {
                unexpectedPushActions += "register"
                return false
            }
            override fun unregister(instance: String) { unexpectedPushActions += "unregister" }
        }

        fun open() {
            val temporary = System.getenv("TMPDIR")?.takeIf { java.io.File(it).isDirectory }
                ?: System.getProperty("java.io.tmpdir")
            directory = java.nio.file.Files.createTempDirectory(java.io.File(temporary).toPath(), "hermes-ready-").toFile()
            fun store(name: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                roots += scope.coroutineContext.job
                val file = java.io.File(checkNotNull(directory), "$name.preferences_pb")
                files += file
                return androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            }
            settings = store("settings")
            servers = store("servers")
            pins = store("pins")
            reads = store("reads")
            observations = store("observations")
            push = store("push")
            println("READY owned stores opened: owner=${System.identityHashCode(this)} " +
                "backings=${listOf(settings, servers, pins, reads, observations, push).map { System.identityHashCode(it) }} " +
                "files=${files.size} roots=${roots.map { it.toString() }}")
        }

        fun close(deadlineNanos: Long) {
            if (closed) return
            roots.forEach { it.cancel() }
            if (roots.any { !it.isCompleted }) {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                check(remainingMillis > 0) { "Owned DataStore cleanup budget exhausted; dependencies remain open" }
                runBlocking { withTimeout(remainingMillis) { roots.forEach { it.join() } } }
            }
            check(roots.all { it.isCompleted }) { "Owned DataStore producers remain live; dependencies remain open" }
            directory?.let { check((deleteDirectory?.invoke(it) ?: it.deleteRecursively()) && !it.exists()) { "Owned DataStore files were not removed" } }
            closed = true
            println("READY owned stores closed: owner=${System.identityHashCode(this)} " +
                "rootsCompleted=${roots.all { it.isCompleted }} ownedFilesAbsent=${files.all { !it.exists() }} " +
                "directoryAbsent=${directory?.exists() != true} unexpectedPushActions=${unexpectedPushActions.toList()}")
        }
    }

    internal val fixtureProtocolCurrent: org.evsyukov.hermes.Connection? get() = container.connection.value

    internal data class ReadyProtocolEntry(val serverSocket: WebSocket, val requestId: String, val thread: Long)
    internal data class ReadyProtocolPrefix(
        val origin: org.evsyukov.hermes.Connection,
        val owner: org.evsyukov.hermes.data.server.WorkOwner,
        val transport: Long, val counted: Int, val access: Job, val list: Job, val writers: List<Job>,
        val startupChild: Job, val vmRoot: Job, val thread: Long,
    )
    internal data class ReadyProtocolSentinel(val returned: ReadyProtocolPrefix?, val thread: Long)
    internal class ReadyProtocolControl {
        val protocolEntered = kotlinx.coroutines.CompletableDeferred<ReadyProtocolEntry>()
        val protocolRelease = CountDownLatch(1)
        val callbackAfterReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val protocolFailure = AtomicReference<Throwable?>()
        val prefix = kotlinx.coroutines.CompletableDeferred<ReadyProtocolPrefix>()
        val returnRecord = AtomicReference<ReadyProtocolPrefix?>()
        val returnThread = AtomicReference<Long?>()
        val returned = kotlinx.coroutines.CompletableDeferred<ReadyProtocolPrefix>()
        val postReturn = kotlinx.coroutines.CompletableDeferred<Unit>()
        val driverEntered = AtomicReference<Job?>()
        val driverExited = kotlinx.coroutines.CompletableDeferred<Long>()
        val remainderCompleted = AtomicBoolean()
        fun releaseBoth() { protocolRelease.countDown(); postReturn.complete(Unit) }
    }

    internal fun fixtureProtocolControl(): ReadyProtocolControl = ReadyProtocolControl().also {
        protocolControls += it
        alphaServer.nextCapabilities.set(it)
    }

    internal suspend fun fixtureProtocolSentinel(control: ReadyProtocolControl): ReadyProtocolSentinel =
        withContext(mainDispatcher) { ReadyProtocolSentinel(control.returnRecord.get(), Thread.currentThread().id) }

    internal suspend fun fixtureRunProtocolReady(control: ReadyProtocolControl) = withContext(mainDispatcher) {
        control.driverEntered.set(kotlinx.coroutines.currentCoroutineContext().job)
        try {
            readyReplacement("route", protocolControl = control) { receipt ->
                val fresh = receipt.candidates.single { it.origin === receipt.fresh }
                fresh.child.joinNormally()
                receipt.access.joinNormally()
                receipt.list.joinNormally()
                val controller = checkNotNull(vm.chat.value)
                assertThat(controller.initialWorkOwner).isEqualTo(fresh.consumed)
                assertThat(vm.draftOf(controller, receipt.fresh)).isNotNull()
                assertThat(vm.draftOf(controller, receipt.old)).isNull()
                ReadyChild(controller.observationRecovery).joinNormally()
                val writers = cache.producers.toList()
                withTimeout(10_000) { writers.forEach { it.join() } }
                assertThat(dao.parts(fresh.consumed.profileId, vmListKey).joinToString("")) .isEqualTo(EMPTY_SESSIONS)
                assertThat(cache.read(fresh.consumed.profileId, vmListKey)).isEqualTo(EMPTY_SESSIONS)
                assertThat(container.connection.value).isSameInstanceAs(receipt.fresh)
            }
            control.remainderCompleted.set(true)
        } finally { control.driverExited.complete(Thread.currentThread().id) }
    }

    private data class PermanentWire(val socket: WebSocket, val closed: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred())

    internal suspend fun fixtureRunPermanentLane() = kotlinx.coroutines.coroutineScope {
        val alpha = profile("permanent-alpha", "Alpha", alphaServer.server)
        val beta = profile("permanent-beta", "Beta", betaServer.server)
        val cipher = TestCipher()
        val preparation = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preparationRoot = preparation.coroutineContext.job
        preparationRoots += preparationRoot
        try {
            val settings = org.evsyukov.hermes.data.settings.AppSettings(context, preparation, ownedState.settings)
            val servers = org.evsyukov.hermes.data.server.ServerStore(
                context, cipher, preparation, ownedState.observations, ownedState.servers,
            )
            withTimeout(10_000) {
                settings.keepConnected.filterNotNull().first()
                servers.authority.bootstrap(servers)
                ownProfiles += alpha.id
                ownProfiles += beta.id
                servers.save(alpha, makeActive = true)
                servers.save(beta, makeActive = false)
                servers.setTokens(alpha.id, Tokens("alpha-token"))
                servers.setTokens(beta.id, Tokens("beta-token"))
                settings.update { it.copy(keepConnected = true) }
            }
        } finally {
            preparationRoot.cancel()
            withTimeout(10_000) { preparationRoot.join() }
        }
        assertThat(preparationRoot.isCompleted).isTrue()
        makeContainer(cipher)
        val containerRoot = container.scope.coroutineContext.job
        val firstLoaded = withTimeout(10_000) { container.settings.keepConnected.filterNotNull().first() }
        assertThat(firstLoaded).isTrue()
        assertThat(::vm.isInitialized).isFalse()
        val initial = withTimeout(10_000) { container.connection.filterNotNull().first { it.session.profile.id == alpha.id } }
        withTimeout(10_000) { initial.socket.connections.first { it > 0 } }
        val initialOwner = checkNotNull(initial.session.owner())
        assertThat(initial.socket.ownerOn(initial.socket.transport.value)).isEqualTo(initialOwner)
        val initialWire = alphaServer.wires.single()
        val initialActors = containerRoot.children.toList()
        fun counter(server: FakeServer, offset: Int): Map<String, Int> =
            server.requests.drop(offset).groupingBy { "${it.method} ${it.url.encodedPath}" }.eachCount()
        val socketOnly = mapOf("POST /api/auth/ws-ticket" to 1, "GET /api/ws" to 1)
        assertThat(counter(alphaServer, 0)).isEqualTo(socketOnly)
        assertThat(alphaServer.wireCalls.filter { it.first === initialWire.socket }.map { it.second })
            .contains("client.capabilities")
        println("READY permanent initial: firstLoaded=$firstLoaded vmAbsent=${!::vm.isInitialized} " +
            "initial=${System.identityHashCode(initial)} owner=${initialOwner.hashCode()} " +
            "socket=${System.identityHashCode(initial.socket)} transport=${initial.socket.transport.value} " +
            "counted=${initial.socket.connections.value} producerRoot=$containerRoot actorSnapshot=$initialActors " +
            "wireCounter=${counter(alphaServer, 0)} preparationRootCompleted=${preparationRoot.isCompleted} " +
            "authorityBootstrap=real-selected-store-precondition-not-cold-process")
        startViewModel(alpha, readyReplacementInitial = true)
        val initialAccess = checkNotNull(vm.accessProducer)
        val initialList = checkNotNull(vm.listProducer)
        val initialController = checkNotNull(vm.chat.value)
        ReadyChild(initialAccess).joinNormally()
        ReadyChild(initialList).joinNormally()
        ReadyChild(initialController.observationRecovery).joinNormally()
        val betaBefore = betaServer.listed.get()
        container.servers.activate(beta.id)
        settle(beta.id, betaServer, betaBefore)
        val betaConnection = checkNotNull(container.connection.value)
        withTimeout(10_000) { betaConnection.socket.connections.first { it > 0 }; initialWire.closed.await() }
        val retiredAlphaActors = initialActors.filter { !it.isActive }
        assertThat(retiredAlphaActors).isNotEmpty()
        withTimeout(10_000) { retiredAlphaActors.forEach { it.join() } }
        assertThat(retiredAlphaActors.all { it.isCompleted }).isTrue()
        assertThat(initial.socket.transport.value).isEqualTo(0L)
        val betaActors = containerRoot.children.toList()
        val betaWire = betaServer.wires.single()
        val alphaOffset = alphaServer.requests.size
        val betaOffset = betaServer.requests.size
        val pending = kotlinx.coroutines.CompletableDeferred<ReadyRouteHold>()
        val hold = Gate().also { gates += it }
        val observedVm = vm
        val vmRoot = vm.viewModelScope.coroutineContext.job
        val decisions = CopyOnWriteArrayList<ReadyDecision>()
        val entries = CopyOnWriteArrayList<ReadyRouteEntry>()
        val candidates = CopyOnWriteArrayList<ReadyCandidate>()
        val writes = CopyOnWriteArrayList<ReadyWrite>()
        val once = AtomicBoolean()
        val heldReference = AtomicReference<ReadyRouteHold?>()
        var primary: Throwable? = null
        observedVm.beforeInitialOwnerAdmission = { origin, consumed ->
            val child = kotlinx.coroutines.currentCoroutineContext().job
            candidates += ReadyCandidate(origin, consumed, ReadyChild(child))
            if (origin.session.profile.id == alpha.id && once.compareAndSet(false, true)) {
                val receipt = ReadyRouteHold(observedVm, vmRoot, origin, consumed, child,
                    observedVm.cachedListProducer, observedVm.accessProducer, observedVm.listProducer)
                heldReference.set(receipt)
                pending.complete(receipt)
                hold.entered.countDown()
                check(hold.release.await(10, TimeUnit.SECONDS)) { "Permanent lane admission hold expired" }
            }
        }
        observedVm.afterInitialOwnerDecision = { origin, consumed, actual, child ->
            decisions += ReadyDecision(observedVm, vmRoot, origin, consumed, actual, child, container.connection.value)
        }
        observedVm.beforeInitialConnection = { origin ->
            heldReference.get()?.let { held ->
                entries += ReadyRouteEntry(observedVm, vmRoot, origin, held.child, held.child.isCompleted,
                    held.child.isCancelled, observedVm.cachedListProducer, observedVm.accessProducer, observedVm.listProducer)
            }
        }
        cache.beforeOwnedWriteObservation = { owner, key, child -> writes += ReadyWrite(owner, key, ReadyChild(child)) }
        try {
            container.servers.activate(alpha.id)
            val held = withTimeout(10_000) { pending.await() }
            val old = held.origin
            assertThat(container.connection.value).isSameInstanceAs(old)
            assertThat(held.child).isNotSameInstanceAs(vmRoot)
            assertThat(hold.release.count).isEqualTo(1L)
            assertThat(held.child.isCompleted).isFalse()
            withTimeout(10_000) { old.socket.connections.first { it > 0 }; betaWire.closed.await() }
            val retiredBetaActors = betaActors.filter { !it.isActive }
            assertThat(retiredBetaActors).isNotEmpty()
            withTimeout(10_000) { retiredBetaActors.forEach { it.join() } }
            assertThat(retiredBetaActors.all { it.isCompleted }).isTrue()
            val transport = old.socket.transport.value
            assertThat(old.socket.ownerOn(transport)).isEqualTo(held.consumed)
            assertThat(old.session.owner()).isEqualTo(held.consumed)
            assertThat(container.settings.keepConnected.value).isTrue()
            assertThat(held.child.isCompleted).isFalse()
            assertThat(decisions.filter { it.origin === old }).isEmpty()
            assertThat(counter(alphaServer, alphaOffset)).isEqualTo(socketOnly)
            val heldWire = alphaServer.wires.last()
            assertThat(heldWire.socket).isNotSameInstanceAs(initialWire.socket)
            assertThat(alphaServer.wireCalls.filter { it.first === heldWire.socket }.map { it.second })
                .contains("client.capabilities")
            val heldActors = containerRoot.children.toList()
            println("READY permanent held: old=${System.identityHashCode(old)} owner=${held.consumed.hashCode()} " +
                "socket=${System.identityHashCode(old.socket)} transport=$transport counted=${old.socket.connections.value} " +
                "collector=${held.child} collectorHeld=${!held.child.isCompleted} vmRoot=$vmRoot " +
                "permanentProducerRoot=$containerRoot ownedActorSnapshot=$heldActors " +
                "previousAlphaWireClosed=${initialWire.closed.isCompleted} retiredAlphaActors=$retiredAlphaActors " +
                "retiredAlphaActorsCompleted=${retiredAlphaActors.all { it.isCompleted }} " +
                "wireCounter=${counter(alphaServer, alphaOffset)} keep=${container.settings.keepConnected.value}")
            val freshSince = betaServer.listed.get()
            val fresh = withTimeout(10_000) {
                try {
                    container.servers.save(alpha.copy(baseUrl = betaServer.server.url("/").toString()))
                    container.connection.filterNotNull().first { it !== old && it.session.profile.baseUrl == betaServer.server.url("/").toString() }
                        .also {
                            assertThat(it.session.owner()).isEqualTo(held.consumed)
                            assertThat(held.child.isCompleted).isFalse()
                            assertThat(hold.release.count).isEqualTo(1L)
                        }
                } finally { hold.release.countDown() }
            }
            settle(alpha.id, betaServer, freshSince)
            withTimeout(10_000) { held.child.join(); fresh.socket.connections.first { it > 0 }; heldWire.closed.await() }
            val stale = decisions.single { it.origin === old && it.child === held.child && it.consumed == held.consumed }
            assertThat(stale.actual).isNull()
            assertThat(stale.currentAtReturn).isSameInstanceAs(fresh)
            assertThat(held.child.isCompleted).isTrue()
            val entry = entries.single { it.origin === fresh }
            assertThat(entry.oldCompleted).isTrue()
            assertThat(entry.cached).isSameInstanceAs(held.cached)
            assertThat(entry.access).isSameInstanceAs(held.access)
            assertThat(entry.list).isSameInstanceAs(held.list)
            val consumedFresh = candidates.single { it.origin === fresh }
            consumedFresh.child.joinNormally()
            val admitted = decisions.single { it.origin === fresh && it.child === consumedFresh.child.job }
            assertThat(admitted.actual).isEqualTo(consumedFresh.consumed)
            assertThat(fresh.socket.ownerOn(fresh.socket.transport.value)).isEqualTo(consumedFresh.consumed)
            ReadyChild(checkNotNull(vm.accessProducer)).joinNormally()
            ReadyChild(checkNotNull(vm.listProducer)).joinNormally()
            withContext(mainDispatcher) { vm.checkAccess(held.consumed, old) }
            assertThat(vm.access.value).isEqualTo(Access.OK)
            assertThat(container.connection.value).isSameInstanceAs(fresh)
            val controller = withContext(mainDispatcher) { checkNotNull(vm.chat.value) }
            assertThat(controller.initialWorkOwner).isEqualTo(consumedFresh.consumed)
            assertThat(withContext(mainDispatcher) { vm.draftOf(controller, fresh) }).isNotNull()
            assertThat(withContext(mainDispatcher) { vm.draftOf(controller, old) }).isNull()
            ReadyChild(controller.observationRecovery).joinNormally()
            val writers = cache.producers.toList()
            withTimeout(10_000) { writers.forEach { it.join() } }
            val selected = writes.toList().filter { it.owner == consumedFresh.consumed && it.key == vmListKey }
            assertThat(selected).isNotEmpty()
            selected.forEach { assertThat(writers).contains(it.child.job); it.child.joinNormally() }
            assertThat(dao.parts(consumedFresh.consumed.profileId, vmListKey).joinToString("")).isEqualTo(EMPTY_SESSIONS)
            assertThat(cache.read(consumedFresh.consumed.profileId, vmListKey)).isEqualTo(EMPTY_SESSIONS)
            assertThat(container.reads.of(consumedFresh.consumed.profileId)).isEmpty()
            assertThat(counter(alphaServer, alphaOffset)).isEqualTo(socketOnly)
            assertThat(counter(betaServer, betaOffset)).isEqualTo(socketOnly + mapOf("GET /api/status" to 1, "GET /api/sessions" to 1))
            assertThat(old.socket.transport.value).isEqualTo(0L)
            assertThat(container.settings.keepConnected.value).isTrue()
            println("READY permanent positive: actualStaleDecisionNull=${stale.actual == null} " +
                "actualOldChild=${held.child} consumedOwner=${held.consumed.hashCode()} fresh=${System.identityHashCode(fresh)} " +
                "freshCollector=${consumedFresh.child.job} freshAdmission=true noStartHandles=true freshBinding=true " +
                "realDaoCacheReads=true selectedWriters=${selected.size} keep=${container.settings.keepConnected.value} " +
                "alphaCounter=${counter(alphaServer, alphaOffset)} freshCounter=${counter(betaServer, betaOffset)}")
        } catch (error: Throwable) { primary = error; throw error } finally {
            hold.release.countDown()
            gates.forEach { it.release.countDown() }
            observedVm.beforeInitialOwnerAdmission = null
            observedVm.afterInitialOwnerDecision = null
            observedVm.beforeInitialConnection = null
            cache.beforeOwnedWriteObservation = null
            var cleanupFailure: Throwable? = null
            suspend fun finish(action: suspend () -> Unit) {
                try { action() } catch (later: Throwable) {
                    val first = primary ?: cleanupFailure
                    if (first == null) cleanupFailure = later else if (later !== first) first.addSuppressed(later)
                }
            }
            finish { dao.releaseGates() }
            val children = candidates.map { it.child.job } + listOfNotNull(observedVm.cachedListProducer, observedVm.accessProducer, observedVm.listProducer)
            finish { vmRoot.cancel() }
            finish { withTimeout(10_000) { children.forEach { it.join() }; vmRoot.join() } }
            println("READY permanent lane cleanup: admissionReleased=${hold.release.count == 0L} " +
                "actualChildrenCompleted=${children.all { it.isCompleted }} vmRootCompleted=${vmRoot.isCompleted} " +
                "preparationRootCompleted=${preparationRoot.isCompleted}")
            cleanupFailure?.let { throw it }
        }
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
                wires += PermanentWire(webSocket)
                activeSocket.set(webSocket)
                webSocket.send(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":false}}}""",
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = HermesJson.parseToJsonElement(text).jsonObject
                rpcCalls += frame
                wireCalls += webSocket to frame["method"]?.jsonPrimitive?.content.orEmpty()
                val id = frame["id"]?.jsonPrimitive?.content ?: return
                if (frame["method"]?.jsonPrimitive?.content == "session.resume") {
                    nextResume.getAndSet(null)?.let { gate ->
                        gate.entered.countDown()
                        check(gate.release.await(10, TimeUnit.SECONDS)) { "Resume gate was not released" }
                    }
                }
                val capabilities = if (frame["method"]?.jsonPrimitive?.content == "client.capabilities") nextCapabilities.getAndSet(null) else null
                capabilities?.let { control ->
                    control.protocolEntered.complete(ReadyProtocolEntry(webSocket, id, Thread.currentThread().id))
                    try {
                        check(control.protocolRelease.await(10, TimeUnit.SECONDS)) { "Initial capabilities hold safety expired" }
                    } catch (error: Throwable) {
                        control.protocolFailure.set(error)
                        throw error
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
                capabilities?.callbackAfterReply?.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wires.single { it.socket === webSocket }.closed.complete(Unit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }
        val wires = CopyOnWriteArrayList<PermanentWire>()
        val wireCalls = CopyOnWriteArrayList<Pair<WebSocket, String>>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val nextCapabilities = AtomicReference<ReadyProtocolControl?>()
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
        AppContainer(
            context, cipher, cache,
            settingsData = ownedState.settings,
            serverData = ownedState.servers,
            pinData = ownedState.pins,
            readData = ownedState.reads,
            observationData = ownedState.observations,
            pushData = ownedState.push,
            pushDistributor = ownedState.distributor,
        ).also {
            createdContainers += it
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

    private suspend fun startViewModel(
        alpha: ServerProfile, readyReplacementInitial: Boolean = false, protocolControl: ReadyProtocolControl? = null,
    ) {
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
            if (readyReplacementInitial) {
                val initial = checkNotNull(container.connection.value)
                protocolControl?.let { control ->
                    control.prefix.complete(ReadyProtocolPrefix(
                    initial, checkNotNull(initial.session.owner()), initial.socket.transport.value,
                    initial.socket.connections.value, checkNotNull(vm.accessProducer), checkNotNull(vm.listProducer),
                    cache.producers.toList(), kotlinx.coroutines.currentCoroutineContext().job,
                    vm.viewModelScope.coroutineContext.job, Thread.currentThread().id,
                    ))
                }
                initial.socket.connections.first { it > 0 }
            }
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
        // settle() observes the HTTP list, which can finish before the legitimate WebSocket
        // upgrade. Finish that startup too before counting requests caused by the stale result.
        val replacement = requireNotNull(container.connection.value)
        assertThat(replacement.session.profile.id).isEqualTo(beta.id)
        withTimeout(10_000) { replacement.socket.ready.first() }
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
            // HTTP access/list completion does not mean the independent socket handshake
            // finished. Establish the live transport before releasing the retired status.
            val transport = withTimeout(10_000) { current.socket.transport.first { it > 0L } }
            hold.release.countDown()
            withTimeout(10_000) { old.join() }
            assertThat(old.isCancelled).isTrue()
            assertThat(vm.access.value).isEqualTo(Access.OK)
            assertThat(vm.accessProducer).isSameInstanceAs(currentCheck)
            assertThat(current.socket.transport.value).isGreaterThan(0L)
            assertThat(current.socket.transport.value).isEqualTo(transport)
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

    @Test fun `READY persisted recreation waits for real owner before initial work`() = runBlocking<Unit> { readyRecreation(false) }
    @Test fun `READY real mint before readiness observation retains the full recreation remainder`() = runBlocking<Unit> { readyRecreation(true) }

    private suspend fun readyRecreation(mintFirst: Boolean) = kotlinx.coroutines.coroutineScope {
        val alpha = profile("ready-recreated-alpha", "Alpha", alphaServer.server)
        val beta = profile("ready-recreated-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        unionOpenDraft("before removal")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, removed ->
            if (owner.profileId == alpha.id && removed) { entered.complete(Unit); release.await() }
        }
        val betaApplied = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.beforeInitialConnection = { if (it.session.profile.id == beta.id) betaApplied.complete(Unit) }
        val removal = async(Dispatchers.IO) { vm.removeServer(alpha) }
        try {
            withTimeout(10_000) { entered.await() }
            assertThat(container.servers.load().servers.map { it.id }).doesNotContain(alpha.id)
            withTimeout(10_000) { betaApplied.await() }
            // Join beta's real producers, even if its request arrived before this sample.
            withContext(mainDispatcher) { }
            withTimeout(10_000) { vm.access.first { it == Access.OK } }
            withTimeout(10_000) { withContext(mainDispatcher) { vm.accessProducer }?.join() }
            withTimeout(10_000) { withContext(mainDispatcher) { vm.listProducer }?.join() }
            assertThat(container.connection.value?.session?.profile?.id).isEqualTo(beta.id)
            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val mint = kotlinx.coroutines.CompletableDeferred<Unit>()
            val applied = kotlinx.coroutines.CompletableDeferred<org.evsyukov.hermes.Connection>()
            container.lifetime.beforeProfileMint = { state ->
                if (state.active?.id == alpha.id && !container.lifetime.present(alpha.id)) {
                    persisted.complete(Unit)
                    withTimeout(10_000) { mint.await() }
                }
            }
            val observeOwner = kotlinx.coroutines.CompletableDeferred<Unit>()
            vm.beforeInitialConnection = { origin ->
                if (origin.session.profile.id == alpha.id) {
                    applied.complete(origin)
                    if (mintFirst) withTimeout(10_000) { observeOwner.await() }
                }
            }
            val before = alphaServer.listed.get()
            val save = async(Dispatchers.IO) { container.servers.save(alpha) }
            try {
                withTimeout(10_000) { persisted.await() }
                val exact = withTimeout(10_000) { applied.await() }
                if (!mintFirst) {
                    // The old decision completed and returned; fixed code subscribed to readiness.
                    // This Main barrier never relies on a rejection that GREEN intentionally avoids.
                    withContext(mainDispatcher) { }
                }
                assertThat(container.connection.value).isSameInstanceAs(exact)
                assertThat(exact.session.owner().generation).isEqualTo(0L)
            } finally {
                mint.complete(Unit)
                try { withTimeout(10_000) { save.await() } } finally {
                    observeOwner.complete(Unit)
                    container.lifetime.beforeProfileMint = null
                    vm.beforeInitialConnection = null
                }
            }
            settle(alpha.id, alphaServer, before)
            assertThat(vm.chat.value?.initialWorkOwner).isEqualTo(container.connection.value!!.session.owner())
            assertThat(alphaServer.requests.any { it.url.encodedPath == "/api/status" }).isTrue()
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

    @Test fun `READY conflated identical recreation replaces disabled root and preserves fresh data`() = runBlocking<Unit> {
        val alpha = profile("ready-conflated-alpha", "Alpha", alphaServer.server)
        val beta = profile("ready-conflated-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        val old = container.connection.value!!
        val oldOwner = old.session.owner()
        val oldApi = old.session.chatFor(oldOwner)
        assertThat(old.session.signOut(alpha.id)).isInstanceOf(org.evsyukov.hermes.data.server.NativeResult.Applied::class.java)
        assertThat(old.session.localDataEnabled).isFalse()
        val paused = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observe = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resumed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val once = AtomicBoolean()
        container.afterActiveProfileObservation = {
            if (once.compareAndSet(false, true)) {
                paused.complete(Unit)
                try { withTimeout(10_000) { observe.await() } } finally { resumed.complete(Unit) }
            }
        }
        val removed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
        container.lifetime.beforeCleanupAdmission = { owner, absent ->
            if (owner.profileId == alpha.id && absent) { removed.complete(Unit); cleanup.await() }
        }
        var removal: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            container.servers.setTokens(alpha.id, Tokens("conflation-before-removal"))
            withTimeout(10_000) { paused.await() }
            val before = alphaServer.listed.get()
            removal = async(Dispatchers.IO) { vm.removeServer(alpha) }
            withTimeout(10_000) { removed.await() }
            withTimeout(10_000) { container.servers.state.first { it.active?.id == beta.id } }
            assertThat(container.connection.value).isSameInstanceAs(old)
            container.servers.save(alpha)
            withTimeout(10_000) { container.servers.state.first { it.active == alpha } }
            assertThat(container.connection.value?.session?.profile).isEqualTo(alpha)
            observe.complete(Unit)
            withTimeout(10_000) { resumed.await() }
            // The real StateFlow observer skipped beta/absence; equal profile metadata alone
            // must still discover the new incarnation and perform actual access and list work.
            settle(alpha.id, alphaServer, before)
            val freshRoot = container.connection.value!!
            assertThat(freshRoot).isNotSameInstanceAs(old)
            assertThat(freshRoot.session.localDataEnabled).isTrue()
            assertThat(old.session.localDataEnabled).isFalse()
            assertThat(old.socket.transport.value).isEqualTo(0L)
            val requests = alphaServer.requests.size
            assertThat(runCatching { oldApi.get("retired") }.exceptionOrNull()).isInstanceOf(org.evsyukov.hermes.data.remote.SignInRequiredException::class.java)
            assertThat(alphaServer.requests.size).isEqualTo(requests)
            withTimeout(10_000) { vm.signIn(browser()) }
            val draft = unionOpenDraft("conflated fresh")
            val memory = container.drafts.existing(alpha.id)
            alphaServer.chats = mapOf("fresh-root" to summary("fresh-root"))
            freshRoot.session.chat.get("fresh-root")
            awaitKept(alpha.id, "api/sessions/fresh-root")
            cache.write(alpha.id, "conflated-fresh", "current cache")
            container.reads.markRead(alpha.id, "shared", 97.0)
            cleanup.complete(Unit)
            assertThat(withTimeout(10_000) { removal.await() }).isTrue()
            assertThat(container.drafts.existing(alpha.id)).isSameInstanceAs(memory)
            assertThat(memory?.obtain(org.evsyukov.hermes.ui.chat.DraftKey.Chat("shared"))).isSameInstanceAs(draft)
            assertThat(draft.text).isEqualTo("conflated fresh")
            assertThat(draft.attachments.single().name).isEqualTo("conflated fresh.txt")
            assertThat(cache.read(alpha.id, "conflated-fresh")).isEqualTo("current cache")
            assertThat(container.reads.of(alpha.id)["shared"]).isEqualTo(97.0)
            assertThat(container.servers.load().tokens[alpha.id]?.accessToken).isEqualTo("native-access")
            assertThat(old.session.localDataEnabled).isFalse()
            assertThat(vm.notice.value).isNull()
        } finally {
            observe.complete(Unit); cleanup.complete(Unit)
            container.afterActiveProfileObservation = null
            container.lifetime.beforeCleanupAdmission = null
            removal?.cancel(); withTimeout(10_000) { removal?.join() }
        }
    }

    @Test fun `READY initial application preserves access already admitted for its exact connection`() = runBlocking<Unit> {
        val alpha = profile("ready-early-alpha", "Alpha", alphaServer.server)
        val beta = profile("ready-early-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        val entered = kotlinx.coroutines.CompletableDeferred<org.evsyukov.hermes.Connection>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.beforeInitialConnection = { origin ->
            if (origin.session.profile.id == beta.id) {
                entered.complete(origin)
                withTimeout(10_000) { release.await() }
            }
        }
        try {
            container.servers.activate(beta.id)
            val origin = withTimeout(10_000) { entered.await() }
            val before = betaServer.listed.get()
            withContext(mainDispatcher) { vm.checkAccess(origin.session.owner(), origin) }
            val current = withContext(mainDispatcher) { vm.accessProducer!! }
            settle(beta.id, betaServer, before)
            withTimeout(10_000) { current.join() }
            withTimeout(10_000) { withContext(mainDispatcher) { vm.listProducer }?.join() }
            val sidebar = vm.sidebar.value
            val listed = betaServer.listed.get()
            release.complete(Unit)
            withTimeout(10_000) { vm.chat.filterNotNull().first { it.initialWorkOwner.profileId == beta.id } }
            withContext(mainDispatcher) { }
            assertThat(vm.accessProducer).isSameInstanceAs(current)
            assertThat(current.isCancelled).isFalse()
            assertThat(vm.access.value).isEqualTo(Access.OK)
            assertThat(vm.sidebar.value).isEqualTo(sidebar)
            assertThat(betaServer.listed.get()).isEqualTo(listed)
        } finally { release.complete(Unit); vm.beforeInitialConnection = null }
    }

    @Test fun `READY superseded candidate retries current owner without replacing its admitted access`() = runBlocking<Unit> {
        val alpha = profile("ready-candidate-alpha", "Alpha", alphaServer.server)
        val beta = profile("ready-candidate-beta", "Beta", betaServer.server)
        alphaServer.authRequired = true
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        val candidate = kotlinx.coroutines.CompletableDeferred<org.evsyukov.hermes.data.server.WorkOwner>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val once = AtomicBoolean()
        vm.beforeInitialOwnerAdmission = { _, owner ->
            if (owner.profileId == alpha.id && once.compareAndSet(false, true)) {
                candidate.complete(owner)
                withTimeout(10_000) { release.await() }
            }
        }
        try {
            container.servers.save(alpha.copy(name = "New metadata"))
            val stale = withTimeout(10_000) { candidate.await() }
            val origin = container.connection.value!!
            assertThat(vm.chat.value).isNull()
            assertThat(vm.access.value).isEqualTo(Access.UNKNOWN)
            container.servers.setTokens(alpha.id, Tokens("ready-replacement-token"))
            val owner = origin.session.owner()
            assertThat(owner).isNotEqualTo(stale)
            val before = alphaServer.listed.get()
            withContext(mainDispatcher) { vm.checkAccess(owner, origin) }
            val current = withContext(mainDispatcher) { vm.accessProducer!! }
            settle(alpha.id, alphaServer, before)
            withTimeout(10_000) { current.join() }
            val listed = alphaServer.listed.get()
            release.complete(Unit)
            val chat = withTimeout(10_000) { vm.chat.filterNotNull().first() }
            withContext(mainDispatcher) { }
            assertThat(chat.initialWorkOwner).isEqualTo(owner)
            assertThat(vm.accessProducer).isSameInstanceAs(current)
            assertThat(current.isCancelled).isFalse()
            assertThat(alphaServer.listed.get()).isEqualTo(listed)
            val requests = alphaServer.requests.size
            withContext(mainDispatcher) { vm.checkAccess(stale, origin) }
            assertThat(vm.accessProducer).isSameInstanceAs(current)
            assertThat(alphaServer.requests.size).isEqualTo(requests)
            assertThat(alphaServer.requests.filter { it.url.encodedPath == "/api/system/stats" }.last().headers["Authorization"]).isEqualTo("Bearer ready-replacement-token")
        } finally { release.complete(Unit); vm.beforeInitialOwnerAdmission = null }
    }

    @Test fun `READY auth generation changes preserve the valid root controller and draft`() = runBlocking<Unit> {
        val alpha = profile("ready-auth-alpha", "Alpha", alphaServer.server)
        val beta = profile("ready-auth-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha)
        val origin = container.connection.value!!
        val draft = unionOpenDraft("keep across auth")
        val chat = vm.chat.value
        val incarnation = container.lifetime.incarnations.value[alpha.id]
        container.servers.setTokens(alpha.id, Tokens("renewed-current-token"))
        withContext(mainDispatcher) { }
        assertThat(container.lifetime.incarnations.value[alpha.id]).isEqualTo(incarnation)
        assertThat(container.connection.value).isSameInstanceAs(origin)
        assertThat(vm.chat.value).isSameInstanceAs(chat)
        assertThat(vm.draftOf(chat!!)).isSameInstanceAs(draft)
        assertThat(draft.text).isEqualTo("keep across auth")
        val before = alphaServer.listed.get()
        withContext(mainDispatcher) { vm.checkAccess(origin.session.owner(), origin) }
        settle(alpha.id, alphaServer, before)
        assertThat(vm.chat.value).isSameInstanceAs(chat)
    }

    @Test fun `READY child join waits for its completion observer`() {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
        val callerRoot = SupervisorJob()
        val callerScope = CoroutineScope(callerRoot + testDispatcher)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "ready-child-ack-worker") }
        val dispatcher = executor.asCoroutineDispatcher()
        val workerRoot = SupervisorJob()
        val workerScope = CoroutineScope(workerRoot + dispatcher)
        val bodyEntered = AtomicBoolean()
        val bodyJob = AtomicReference<Job?>()
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val handlerExited = CountDownLatch(1)
        val sentinelExited = CountDownLatch(1)
        val handlerHeld = AtomicBoolean()
        val handlerTimedOut = AtomicBoolean()
        val handlerCause = AtomicReference<Throwable?>()
        val handlerInterrupted = AtomicReference<InterruptedException?>()
        val callerEntered = AtomicBoolean()
        val callerJob = AtomicReference<Job?>()
        val callerNormal = AtomicBoolean()
        val helperFailure = AtomicReference<AssertionError?>()
        val unexpectedCallerFailure = AtomicReference<Throwable?>()
        val exceptionRecorder = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            unexpectedCallerFailure.set(error)
        }
        val child = workerScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            bodyJob.set(kotlinx.coroutines.currentCoroutineContext().job)
            bodyEntered.set(true)
        }
        // Registered first, before ReadyChild and before the real lazy Job starts.
        child.invokeOnCompletion { cause ->
            handlerCause.set(cause)
            handlerHeld.set(true)
            handlerEntered.countDown()
            try {
                if (!releaseHandler.await(10, TimeUnit.SECONDS)) handlerTimedOut.set(true)
            } catch (interrupted: InterruptedException) {
                handlerInterrupted.set(interrupted)
                Thread.currentThread().interrupt()
            } finally {
                handlerHeld.set(false)
                handlerExited.countDown()
            }
        }
        val observed = ReadyChild(child)
        var caller: Job? = null
        var sentinelQueued = false
        var primary: Throwable? = null
        try {
            assertThat(observed.job).isSameInstanceAs(child)
            assertThat(child.start()).isTrue()
            check(handlerEntered.await(10, TimeUnit.SECONDS)) { "Earlier completion handler did not enter" }
            assertThat(bodyEntered.get()).isTrue()
            assertThat(bodyJob.get()).isSameInstanceAs(child)
            assertThat(child.isCompleted).isTrue()
            assertThat(child.isCancelled).isFalse()
            assertThat(handlerCause.get()).isNull()
            assertThat(handlerHeld.get()).isTrue()
            assertThat(handlerExited.count).isEqualTo(1L)
            assertThat(handlerTimedOut.get()).isFalse()
            assertThat(observed.completed.get()).isFalse()
            // This sentinel runs only after the worker's real task and all its handlers return.
            executor.execute { sentinelExited.countDown() }
            sentinelQueued = true
            caller = callerScope.launch(exceptionRecorder) {
                callerJob.set(kotlinx.coroutines.currentCoroutineContext().job)
                callerEntered.set(true)
                try {
                    observed.joinNormally()
                    callerNormal.set(true)
                } catch (error: AssertionError) {
                    val fromActualHelper = error.javaClass.name == "com.google.common.truth.AssertionErrorWithFacts" &&
                        error.stackTrace.any { it.className.endsWith("\$ReadyChild") && it.methodName == "joinNormally" }
                    if (!fromActualHelper) throw error
                    helperFailure.set(error)
                }
            }
            // Process current runnable work only: no sleep, polling or virtual-time auto-advance.
            scheduler.runCurrent()
            unexpectedCallerFailure.get()?.let { throw it }
            assertThat(callerEntered.get()).isTrue()
            assertThat(callerJob.get()).isSameInstanceAs(caller)
            assertThat(caller.isCancelled).isFalse()
            assertThat(child.isCompleted).isTrue()
            assertThat(child.isCancelled).isFalse()
            assertThat(handlerHeld.get()).isTrue()
            assertThat(handlerTimedOut.get()).isFalse()
            assertThat(observed.completed.get()).isFalse()
            val originalFailure = helperFailure.get()
            println("READY child ack opportunity: actualJob=$child bodyJob=${bodyJob.get()} " +
                "caller=$caller entered=${callerEntered.get()} terminal=${child.isCompleted} " +
                "cancelled=${child.isCancelled} held=${handlerHeld.get()} observed=${observed.completed.get()} " +
                "callerCompleted=${caller.isCompleted} callerNormal=${callerNormal.get()} " +
                "helperFailure=${originalFailure?.javaClass?.name} schedulerTime=${scheduler.currentTime}")
            originalFailure?.let { println("READY original helper failure:\n${it.stackTraceToString()}") }
            com.google.common.truth.Truth.assertWithMessage("READY_CHILD_JOIN_FINISHED_BEFORE_COMPLETION_OBSERVER")
                .that(caller.isCompleted).isFalse()
            // Frozen positive continuation: the SAME helper call must resume after real observer exit.
            releaseHandler.countDown()
            check(handlerExited.await(10, TimeUnit.SECONDS)) { "Earlier completion handler did not exit" }
            check(sentinelExited.await(10, TimeUnit.SECONDS)) { "Worker task/handlers did not reach sentinel" }
            assertThat(handlerTimedOut.get()).isFalse()
            assertThat(handlerHeld.get()).isFalse()
            assertThat(observed.completed.get()).isTrue()
            assertThat(observed.failure.get()).isNull()
            scheduler.runCurrent()
            unexpectedCallerFailure.get()?.let { throw it }
            assertThat(caller.isCompleted).isTrue()
            assertThat(caller.isCancelled).isFalse()
            assertThat(callerNormal.get()).isTrue()
            assertThat(helperFailure.get()).isNull()
            assertThat(observed.job).isSameInstanceAs(child)
            assertThat(bodyJob.get()).isSameInstanceAs(child)
            assertThat(child.isCancelled).isFalse()
        } catch (error: Throwable) {
            primary = error
        } finally {
            fun safety(action: () -> Unit) {
                try { action() } catch (later: Throwable) {
                    val first = primary
                    if (first == null) primary = later else if (later !== first) first.addSuppressed(later)
                }
            }
            // Each action is attempted even if an earlier join fails; dependent fixture teardown is later.
            releaseHandler.countDown()
            safety { callerRoot.cancel(); scheduler.runCurrent() }
            safety { runBlocking { withTimeout(10_000) { caller?.join(); callerRoot.join() } } }
            safety { workerRoot.cancel() }
            safety { runBlocking { withTimeout(10_000) { child.join(); workerRoot.join() } } }
            safety { dispatcher.close() }
            safety { check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Owned worker did not terminate" } }
            safety {
                if (handlerEntered.count == 0L) check(handlerExited.count == 0L) { "Completion handler has not exited" }
                if (sentinelQueued) check(sentinelExited.count == 0L) { "Worker sentinel did not execute" }
                handlerInterrupted.get()?.let { throw it }
                check(!handlerTimedOut.get()) { "Independent completion-handler safety bound expired" }
                check(callerRoot.isCompleted && workerRoot.isCompleted) { "Owned roots did not complete" }
            }
            println("READY child ack safety: handlerExited=${handlerExited.count == 0L} " +
                "sentinelExited=${sentinelExited.count == 0L} executorTerminated=${executor.isTerminated} " +
                "callerRootCompleted=${callerRoot.isCompleted} workerRootCompleted=${workerRoot.isCompleted} " +
                "handlerTimedOut=${handlerTimedOut.get()} observed=${observed.completed.get()}")
        }
        primary?.let { throw it }
    }

    // Supplemental observations only: each route invocation owns its records and captured VM/root.
    private class ReadyChild(val job: Job) {
        val completed = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        private val completionObserved = kotlinx.coroutines.CompletableDeferred<Unit>()
        init {
            job.invokeOnCompletion { cause ->
                failure.set(cause)
                completed.set(true)
                completionObserved.complete(Unit)
            }
        }
        suspend fun joinNormally() {
            withTimeout(10_000) {
                job.join()
                completionObserved.await()
            }
            assertThat(completed.get()).isTrue()
            assertThat(failure.get()).isNull()
            assertThat(job.isCancelled).isFalse()
        }
    }
    private data class ReadyCandidate(
        val origin: org.evsyukov.hermes.Connection,
        val consumed: org.evsyukov.hermes.data.server.WorkOwner,
        val child: ReadyChild,
    )
    private data class ReadyDecision(
        val vm: MainViewModel, val root: Job,
        val origin: org.evsyukov.hermes.Connection,
        val consumed: org.evsyukov.hermes.data.server.WorkOwner,
        val actual: org.evsyukov.hermes.data.server.WorkOwner?, val child: Job,
        val currentAtReturn: org.evsyukov.hermes.Connection?,
    )
    // Supplemental stale-route scheduling receipts; observations carry no product authority.
    private data class ReadyRouteHold(
        val vm: MainViewModel, val root: Job,
        val origin: org.evsyukov.hermes.Connection,
        val consumed: org.evsyukov.hermes.data.server.WorkOwner, val child: Job,
        val cached: Job?, val access: Job?, val list: Job?,
    )
    private data class ReadyRouteEntry(
        val vm: MainViewModel, val root: Job, val origin: org.evsyukov.hermes.Connection,
        val oldChild: Job, val oldCompleted: Boolean, val oldCancelled: Boolean,
        val cached: Job?, val access: Job?, val list: Job?,
    )
    private data class ReadyRoutePublication(
        val origin: org.evsyukov.hermes.Connection,
        val owner: org.evsyukov.hermes.data.server.WorkOwner, val candidateHeld: Boolean,
    )
    private data class ReadyWrite(
        val owner: org.evsyukov.hermes.data.server.WorkOwner, val key: String, val child: ReadyChild,
    )
    private data class ReadyReceipt(
        val vm: MainViewModel, val root: Job,
        val initial: org.evsyukov.hermes.Connection,
        val old: org.evsyukov.hermes.Connection, val fresh: org.evsyukov.hermes.Connection,
        val candidates: List<ReadyCandidate>, val decisions: List<ReadyDecision>,
        val snapshotWrites: () -> List<ReadyWrite>, val access: ReadyChild, val list: ReadyChild,
        val routeHold: ReadyRouteHold?, val routePublication: ReadyRoutePublication?,
        val snapshotDecisions: () -> List<ReadyDecision>,
        val snapshotRouteEntries: () -> List<ReadyRouteEntry>, val routeFailure: Throwable?,
    )

    private suspend fun readyReplacement(
        kind: String, staleRouteDecision: Boolean = false, protocolControl: ReadyProtocolControl? = null,
        checkpoint: (suspend (ReadyReceipt) -> Unit)? = null,
    ) = kotlinx.coroutines.coroutineScope {
        val alpha = profile("ready-$kind-alpha", "Alpha", alphaServer.server)
        val beta = profile("ready-$kind-beta", "Beta", betaServer.server)
        makeContainer(); seed(alpha, beta); startViewModel(alpha, readyReplacementInitial = true, protocolControl = protocolControl)
        protocolControl?.let { control ->
            val prefix = control.prefix.await()
            control.returnThread.set(Thread.currentThread().id)
            control.returnRecord.set(prefix)
            control.returned.complete(prefix)
            withTimeout(10_000) { control.postReturn.await() }
        }
        // Supplemental retention of independently admitted initial alpha transport.
        val initialConnection = container.connection.value!!
        val betaBefore = betaServer.listed.get()
        container.servers.activate(beta.id); settle(beta.id, betaServer, betaBefore)
        val pending = kotlinx.coroutines.CompletableDeferred<org.evsyukov.hermes.Connection>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val once = AtomicBoolean()
        // Supplemental recording is shared by original R1 and the tracer, even with no checkpoint.
        val observedVm = vm
        val observedRoot = observedVm.viewModelScope.coroutineContext.job
        val candidates = CopyOnWriteArrayList<ReadyCandidate>()
        val decisions = CopyOnWriteArrayList<ReadyDecision>()
        val writes = CopyOnWriteArrayList<ReadyWrite>()
        // Explicit opt-in stimulus only; default R1/tracer retain their cancellable schedule.
        val routeGate = if (staleRouteDecision) Gate().also { gates += it } else null
        val routeHold = AtomicReference<ReadyRouteHold?>()
        val routePublication = AtomicReference<ReadyRoutePublication?>()
        val routeEntries = CopyOnWriteArrayList<ReadyRouteEntry>()
        val routeFailure = AtomicReference<Throwable?>()
        var routePrimary: Throwable? = null
        if (staleRouteDecision) {
            require(kind == "route")
            observedVm.beforeInitialConnection = { origin ->
                routeHold.get()?.let { held ->
                    routeEntries += ReadyRouteEntry(
                        observedVm, observedRoot, origin, held.child, held.child.isCompleted, held.child.isCancelled,
                        observedVm.cachedListProducer, observedVm.accessProducer, observedVm.listProducer,
                    )
                }
            }
        }
        if (kind == "route") {
            observedVm.afterInitialOwnerDecision = { origin, consumed, actual, child ->
                decisions += ReadyDecision(
                    observedVm, observedRoot, origin, consumed, actual, child, container.connection.value,
                )
            }
            cache.beforeOwnedWriteObservation = { owner, key, child ->
                writes += ReadyWrite(owner, key, ReadyChild(child))
            }
        }
        vm.beforeInitialOwnerAdmission = { origin, consumed ->
            if (kind == "route") candidates += ReadyCandidate(
                origin, consumed, ReadyChild(kotlinx.coroutines.currentCoroutineContext().job),
            )
            if (origin.session.profile.id == alpha.id && once.compareAndSet(false, true)) {
                if (staleRouteDecision) {
                    routeHold.set(ReadyRouteHold(
                        observedVm, observedRoot, origin, consumed, kotlinx.coroutines.currentCoroutineContext().job,
                        observedVm.cachedListProducer, observedVm.accessProducer, observedVm.listProducer,
                    ))
                    routeGate!!.entered.countDown()
                }
                pending.complete(origin)
                if (staleRouteDecision) {
                    try {
                        check(routeGate!!.release.await(10, TimeUnit.SECONDS)) { "Stale route Main hold safety expired" }
                    } catch (error: Throwable) {
                        routeFailure.set(error)
                        throw error
                    } finally { cancelled.complete(Unit) }
                } else {
                    try { withTimeout(10_000) { release.await() } } finally { cancelled.complete(Unit) }
                }
            }
        }
        try {
            container.servers.activate(alpha.id)
            val old = withTimeout(10_000) { pending.await() }
            val captured = old.session.owner()
            val alphaRequests = alphaServer.requests.size
            val alphaStatuses = alphaServer.requests.count { it.url.encodedPath == "/api/status" }
            val alphaLists = alphaServer.listed.get()
            val betaSince = betaServer.listed.get()
            when (kind) {
                "route" -> {
                    container.servers.save(alpha.copy(baseUrl = betaServer.server.url("/").toString()))
                    if (staleRouteDecision) withTimeout(10_000) {
                        try {
                            val replacement = container.connection.filterNotNull().first {
                                it !== old && it.session.profile.id == alpha.id &&
                                    it.session.profile.baseUrl == betaServer.server.url("/").toString()
                            }
                            val held = checkNotNull(routeHold.get())
                            val owner = replacement.session.owner()
                            val stillHeld = container.lifetime.holds(held.consumed.profileId, held.consumed.generation)
                            routePublication.set(ReadyRoutePublication(replacement, owner, stillHeld))
                            assertThat(routeGate!!.entered.count).isEqualTo(0L)
                            assertThat(routeGate!!.release.count).isEqualTo(1L)
                            assertThat(held.child.isCompleted).isFalse()
                            assertThat(owner).isEqualTo(held.consumed)
                            assertThat(stillHeld).isTrue()
                            println("READY stale route publication: old=${System.identityHashCode(old)} " +
                                "replacement=${System.identityHashCode(replacement)} candidate=${held.consumed} " +
                                "currentOwner=$owner candidateHeld=$stillHeld oldChild=${held.child}")
                        } finally { routeGate!!.release.countDown() }
                        settle(alpha.id, betaServer, betaSince)
                    } else {
                        settle(alpha.id, betaServer, betaSince)
                    }
                }
                "same-metadata" -> {
                    container.servers.activate(beta.id); settle(beta.id, betaServer, betaSince)
                    val since = alphaServer.listed.get()
                    container.servers.activate(alpha.id); settle(alpha.id, alphaServer, since)
                    assertThat(container.connection.value?.session?.profile).isEqualTo(old.session.profile)
                }
                "null" -> {
                    container.servers.remove(alpha.id)
                    container.servers.remove(beta.id)
                    withTimeout(10_000) { container.connection.first { it == null } }
                    withContext(mainDispatcher) { }
                    assertThat(vm.chat.value).isNull()
                    assertThat(vm.access.value).isEqualTo(Access.UNKNOWN)
                    container.servers.save(beta)
                    settle(beta.id, betaServer, betaSince)
                }
                "disposal" -> {
                    vm.viewModelScope.cancel()
                    withTimeout(10_000) { vm.viewModelScope.coroutineContext.job.join() }
                    container.servers.activate(beta.id)
                    withTimeout(10_000) { container.connection.filterNotNull().first { it.session.profile.id == beta.id } }
                    // A genuinely new VM must work; disposing the old pending action cannot cancel it.
                    vm = MainViewModel(container)
                    settle(beta.id, betaServer, betaSince)
                }
            }
            withTimeout(10_000) { cancelled.await() }
            val fresh = container.connection.value!!
            assertThat(fresh).isNotSameInstanceAs(old)
            val producer = withContext(mainDispatcher) { vm.accessProducer!! }
            release.complete(Unit)
            withTimeout(10_000) { producer.join() }
            withContext(mainDispatcher) { vm.checkAccess(captured, old) }
            // Supplemental assertion checkpoint: no product action or alternative route policy.
            if (kind == "route") checkpoint?.invoke(ReadyReceipt(
                observedVm, observedRoot, initialConnection, old, fresh,
                candidates.toList(), decisions.toList(), { writes.toList() },
                ReadyChild(producer), ReadyChild(withContext(mainDispatcher) { vm.listProducer!! }),
                routeHold.get(), routePublication.get(), { decisions.toList() }, { routeEntries.toList() }, routeFailure.get(),
            ))
            assertThat(vm.accessProducer).isSameInstanceAs(producer)
            assertThat(producer.isCancelled).isFalse()
            assertThat(vm.access.value).isEqualTo(Access.OK)
            assertThat(old.socket.transport.value).isEqualTo(0L)
            if (kind != "same-metadata") assertThat(alphaServer.requests.size).isEqualTo(alphaRequests) else {
                assertThat(alphaServer.requests.count { it.url.encodedPath == "/api/status" }).isEqualTo(alphaStatuses + 1)
                assertThat(alphaServer.listed.get()).isEqualTo(alphaLists + 1)
            }
            assertThat(vm.chat.value?.initialWorkOwner).isEqualTo(fresh.session.owner())
        } catch (error: Throwable) {
            routePrimary = error
            throw error
        } finally {
            // Test-thread release FIRST, before any Main barrier, cancellation or producer join.
            routeGate?.release?.countDown()
            release.complete(Unit); vm.beforeInitialOwnerAdmission = null
            // Supplemental recorders own no gates or authority.
            if (kind == "route") {
                observedVm.afterInitialOwnerDecision = null
                cache.beforeOwnedWriteObservation = null
            }
            if (staleRouteDecision) {
                observedVm.beforeInitialConnection = null
                var cleanupFailure: Throwable? = null
                suspend fun finish(action: suspend () -> Unit) {
                    try { action() } catch (later: Throwable) {
                        val first = routePrimary ?: cleanupFailure
                        if (first == null) cleanupFailure = later else if (later !== first) first.addSuppressed(later)
                    }
                }
                finish { dao.releaseGates() }
                finish { gates.forEach { it.release.countDown() } }
                val actualChildren = candidates.map { it.child.job } + listOfNotNull(
                    observedVm.cachedListProducer, observedVm.accessProducer, observedVm.listProducer,
                )
                finish { observedRoot.cancel() }
                finish { withTimeout(10_000) { actualChildren.forEach { it.join() }; observedRoot.join() } }
                println("READY stale route cleanup: latchReleased=${routeGate!!.release.count == 0L} " +
                    "actualChildrenCompleted=${actualChildren.all { it.isCompleted }} vmRootCompleted=${observedRoot.isCompleted}")
                cleanupFailure?.let { throw it }
                // Existing fixture teardown still joins container/cache/browser roots before dependent close.
            }
        }
    }

    @Test fun `READY route recovery reports the consumed initial owner decision before effects`() = runBlocking<Unit> {
        readyReplacement("route") { receipt ->
            assertThat(vm).isSameInstanceAs(receipt.vm)
            assertThat(receipt.vm.viewModelScope.coroutineContext.job).isSameInstanceAs(receipt.root)
            assertThat(container.connection.value).isSameInstanceAs(receipt.fresh)
            val fresh = receipt.candidates.single { it.origin === receipt.fresh }
            val pending = receipt.candidates.single { it.origin === receipt.old }
            assertThat(fresh.child.job).isNotSameInstanceAs(receipt.root)
            fresh.child.joinNormally()
            receipt.access.joinNormally()
            receipt.list.joinNormally()
            val controller = withContext(mainDispatcher) { receipt.vm.chat.value!! }
            assertThat(withContext(mainDispatcher) { receipt.vm.draftOf(controller, receipt.fresh) }).isNotNull()
            assertThat(controller.initialWorkOwner).isEqualTo(fresh.consumed)
            // Empty READY controllers have no stored-history loadCompletion.
            ReadyChild(controller.observationRecovery).joinNormally()
            withTimeout(10_000) { pending.child.job.join() }
            assertThat(pending.child.completed.get()).isTrue()
            assertThat(pending.child.job.isCancelled).isTrue()
            assertThat(pending.child.failure.get()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
            println("READY opportunity: vm=${System.identityHashCode(receipt.vm)} root=${receipt.root} " +
                "initial=${System.identityHashCode(receipt.initial)} fresh=${System.identityHashCode(receipt.fresh)} " +
                "candidate=${fresh.consumed} collector=${fresh.child.job} access=${receipt.access.job} " +
                "list=${receipt.list.job} binding=true pending=cancelled-before-decision records=${receipt.decisions.size}")
            val matching = receipt.decisions.filter {
                it.vm === receipt.vm && it.root === receipt.root && it.origin === receipt.fresh &&
                    it.consumed == fresh.consumed && it.child === fresh.child.job
            }
            com.google.common.truth.Truth.assertWithMessage("READY_INITIAL_OWNER_DECISION_OBSERVATION_ABSENT")
                .that(matching).isNotEmpty()
            // Full positive continuation is frozen before RED, including real full-key persistence.
            assertThat(matching).hasSize(1)
            assertThat(matching.single().actual).isEqualTo(fresh.consumed)
            assertThat(receipt.decisions.filter { it.origin === receipt.old && it.child === pending.child.job }).isEmpty()
            // Finite accounting for already launched writers; not a no-future-launch barrier.
            val writerJobs = container.responses.producers.toList()
            withTimeout(10_000) { writerJobs.forEach { it.join() } }
            val selected = receipt.snapshotWrites().filter { it.owner == fresh.consumed && it.key == vmListKey }
            assertThat(selected).isNotEmpty()
            selected.forEach { write ->
                assertThat(writerJobs).contains(write.child.job)
                write.child.joinNormally()
            }
            assertThat(dao.parts(fresh.consumed.profileId, vmListKey).joinToString("")).isEqualTo(EMPTY_SESSIONS)
            assertThat(cache.read(fresh.consumed.profileId, vmListKey)).isEqualTo(EMPTY_SESSIONS)
            assertThat(container.connection.value).isSameInstanceAs(receipt.fresh)
            assertThat(withContext(mainDispatcher) { receipt.vm.draftOf(controller, receipt.fresh) }).isNotNull()
        }
    }

    @Test fun `READY stale route candidate returns rejection before fresh recovery`() = runBlocking<Unit> {
        readyReplacement("route", staleRouteDecision = true) { receipt ->
            assertThat(vm).isSameInstanceAs(receipt.vm)
            assertThat(receipt.vm.viewModelScope.coroutineContext.job).isSameInstanceAs(receipt.root)
            receipt.routeFailure?.let { throw it }
            val held = checkNotNull(receipt.routeHold)
            val publication = checkNotNull(receipt.routePublication)
            assertThat(held.vm).isSameInstanceAs(receipt.vm)
            assertThat(held.root).isSameInstanceAs(receipt.root)
            assertThat(held.origin).isSameInstanceAs(receipt.old)
            assertThat(held.child).isNotSameInstanceAs(receipt.root)
            val oldCandidate = receipt.candidates.single { it.origin === receipt.old }
            assertThat(oldCandidate.child.job).isSameInstanceAs(held.child)
            assertThat(oldCandidate.consumed).isEqualTo(held.consumed)
            assertThat(publication.origin).isSameInstanceAs(receipt.fresh)
            assertThat(publication.owner).isEqualTo(held.consumed)
            assertThat(publication.candidateHeld).isTrue()
            assertThat(container.connection.value).isSameInstanceAs(receipt.fresh)
            assertThat(receipt.fresh.session.owner()).isEqualTo(held.consumed)
            assertThat(container.lifetime.holds(held.consumed.profileId, held.consumed.generation)).isTrue()
            withTimeout(10_000) { held.child.join() }
            assertThat(held.child.isCompleted).isTrue()
            val fresh = receipt.candidates.single { it.origin === receipt.fresh }
            assertThat(fresh.child.job).isNotSameInstanceAs(receipt.root)
            fresh.child.joinNormally()
            receipt.access.joinNormally()
            receipt.list.joinNormally()
            val decisions = receipt.snapshotDecisions()
            val oldDecisions = decisions.filter {
                it.vm === receipt.vm && it.root === receipt.root && it.origin === receipt.old &&
                    it.consumed == held.consumed && it.child === held.child
            }
            com.google.common.truth.Truth.assertWithMessage("READY_STALE_ROUTE_DECISION_ABSENT")
                .that(oldDecisions).hasSize(1)
            val decision = oldDecisions.single()
            println("READY stale route decision: vm=${System.identityHashCode(decision.vm)} root=${decision.root} " +
                "origin=${System.identityHashCode(decision.origin)} candidate=${decision.consumed} child=${decision.child} " +
                "actual=${decision.actual} currentAtReturn=${System.identityHashCode(decision.currentAtReturn)}")
            com.google.common.truth.Truth.assertWithMessage("READY_STALE_ROUTE_DECISION_ACCEPTED")
                .that(decision.actual).isNull()
            assertThat(decision.currentAtReturn).isSameInstanceAs(publication.origin)
            assertThat(held.child.isCancelled).isTrue()
            val entries = receipt.snapshotRouteEntries().filter { it.origin === publication.origin }
            assertThat(entries).hasSize(1)
            val entry = entries.single()
            assertThat(entry.vm).isSameInstanceAs(receipt.vm)
            assertThat(entry.root).isSameInstanceAs(receipt.root)
            assertThat(entry.oldChild).isSameInstanceAs(held.child)
            assertThat(entry.oldCompleted).isTrue()
            assertThat(entry.oldCancelled).isTrue()
            println("READY stale route no-start: oldCompleted=${entry.oldCompleted} oldCancelled=${entry.oldCancelled} " +
                "cachedUnchanged=${entry.cached === held.cached} accessUnchanged=${entry.access === held.access} " +
                "listUnchanged=${entry.list === held.list}")
            com.google.common.truth.Truth.assertWithMessage("READY_STALE_ROUTE_CACHED_INITIALIZER_STARTED")
                .that(entry.cached).isSameInstanceAs(held.cached)
            assertThat(entry.access).isSameInstanceAs(held.access)
            assertThat(entry.list).isSameInstanceAs(held.list)
            val freshDecisions = decisions.filter {
                it.vm === receipt.vm && it.root === receipt.root && it.origin === receipt.fresh &&
                    it.consumed == fresh.consumed && it.child === fresh.child.job
            }
            assertThat(freshDecisions).hasSize(1)
            assertThat(freshDecisions.single().actual).isEqualTo(fresh.consumed)
            assertThat(freshDecisions.single().currentAtReturn).isSameInstanceAs(receipt.fresh)
            val controller = withContext(mainDispatcher) { receipt.vm.chat.value!! }
            assertThat(controller.initialWorkOwner).isEqualTo(fresh.consumed)
            assertThat(withContext(mainDispatcher) { receipt.vm.draftOf(controller, receipt.fresh) }).isNotNull()
            assertThat(withContext(mainDispatcher) { receipt.vm.draftOf(controller, receipt.old) }).isNull()
            ReadyChild(controller.observationRecovery).joinNormally()
            val writers = container.responses.producers.toList()
            withTimeout(10_000) { writers.forEach { it.join() } }
            val selected = receipt.snapshotWrites().filter { it.owner == fresh.consumed && it.key == vmListKey }
            assertThat(selected).isNotEmpty()
            selected.forEach {
                assertThat(writers).contains(it.child.job)
                it.child.joinNormally()
            }
            assertThat(dao.parts(fresh.consumed.profileId, vmListKey).joinToString("")).isEqualTo(EMPTY_SESSIONS)
            assertThat(cache.read(fresh.consumed.profileId, vmListKey)).isEqualTo(EMPTY_SESSIONS)
            assertThat(container.connection.value).isSameInstanceAs(receipt.fresh)
            assertThat(withContext(mainDispatcher) { receipt.vm.draftOf(controller, receipt.fresh) }).isNotNull()
            println("READY stale route recovery: freshCollector=${fresh.child.job} access=${receipt.access.job} " +
                "list=${receipt.list.job} selectedWriters=${selected.size} binding=true realDaoCacheBody=true")
        }
    }

    @Test fun `READY pending candidate is cancelled by route replacement`() = runBlocking<Unit> { readyReplacement("route") }
    @Test fun `READY pending candidate is cancelled by equal metadata A to B to A replacement`() = runBlocking<Unit> { readyReplacement("same-metadata") }
    @Test fun `READY pending candidate is cancelled by null before a fresh profile works`() = runBlocking<Unit> { readyReplacement("null") }
    @Test fun `READY pending candidate is cancelled by disposal before a fresh VM works`() = runBlocking<Unit> { readyReplacement("disposal") }

    /** Joins accepted load cycles, including the real load/read callback, even on the faulty decision. */
    private suspend fun finishInitialStoredLoad(chat: org.evsyukov.hermes.chat.ChatController) {
        withTimeout(10_000) {
            chat.observationRecovery.join()
            val accepted = chat.acceptedReloadRequests.get()
            assertThat(accepted).isAtMost(1L)
            if (accepted != 0L) {
                while (chat.completedLoadCycles.get() == 0L) delay(5)
                checkNotNull(chat.loadCompletion).await()
            }
        }
        drainCacheWrites()
    }

    private suspend fun storedInitialOrigin(vmCaller: Boolean) {
        val caller = if (vmCaller) "vm" else "controller"
        val alpha = profile("stored-origin-$caller-alpha", "Alpha", alphaServer.server)
        val beta = profile("stored-origin-$caller-beta", "Beta", betaServer.server)
        val id = "initial-origin"
        val headKey = "api/sessions/$id"
        val historyKey = cacheKey("$headKey/messages", mapOf("limit" to 500, "order" to "oldest", "inline_images" to false))
        val head = summary(id)
        val history = """{"messages":[{"id":701,"role":"assistant","content":"authorized B history","timestamp":49.0}]}"""
        alphaServer.authRequired = true
        alphaServer.chats = mapOf(id to head)
        alphaServer.messageBodies = mapOf(id to history)
        makeContainer()
        val constructionConnection = AtomicReference<org.evsyukov.hermes.Connection>()
        val allowInitial = kotlinx.coroutines.CompletableDeferred<Unit>()
        val construction = Gate().also { gates += it }
        val admitted = AtomicReference<org.evsyukov.hermes.data.server.WorkOwner>()
        val readCallbacks = CopyOnWriteArrayList<org.evsyukov.hermes.data.server.WorkOwner>()
        val readJobs = CopyOnWriteArrayList<Job>()
        if (vmCaller) {
            // A notification arrives before there is a Connection: exercise the actual pending path.
            withContext(mainDispatcher) {
                vm = MainViewModel(container)
                vm.readProducers
                vm.beforeInitialConnection = {
                    withTimeout(10_000) { allowInitial.await() }
                }
                vm.beforeStoredController = { connection, owner ->
                    constructionConnection.set(connection)
                    admitted.set(owner)
                    construction.entered.countDown()
                    check(construction.release.await(10, TimeUnit.SECONDS)) { "Stored construction gate was not released" }
                }
                assertThat(container.connection.value).isNull()
                vm.openChat(id)
            }
        }
        seed(alpha, beta)
        if (!vmCaller) startViewModel(alpha)
        var chat: org.evsyukov.hermes.chat.ChatController? = null
        try {
            if (vmCaller) {
                allowInitial.complete(Unit)
                assertThat(construction.entered.await(10, TimeUnit.SECONDS)).isTrue()
            }
            val exact = if (vmCaller) checkNotNull(constructionConnection.get()) else container.connection.value!!
            val a = if (vmCaller) checkNotNull(admitted.get()) else checkNotNull(container.lifetime.publish(exact.session.owner()) { exact.session.owner() })
            assertThat(container.connection.value).isSameInstanceAs(exact)
            assertThat(exact.session.holds(a)).isTrue()
            // Real durable auth replacement, while stored construction still carries admitted A.
            val previousConnections = exact.socket.connections.value
            container.servers.setTokens(alpha.id, Tokens("stored-origin-B"))
            val b = exact.session.owner()
            assertThat(b).isNotEqualTo(a)
            assertThat(container.connection.value).isSameInstanceAs(exact)
            // Finish the genuine B socket event BEFORE constructing this stored controller. Its
            // replay must not be mistaken for a fresh event after the constructor's implicit load.
            withTimeout(10_000) {
                exact.socket.awaitReady(b)
                exact.socket.connections.first { it > previousConnections }
            }
            val transport = exact.socket.transport.value
            val socketConnections = exact.socket.connections.value
            assertThat(exact.socket.ownerOn(transport)).isEqualTo(b)
            if (vmCaller) {
                construction.release.countDown()
                withContext(mainDispatcher) { }
                chat = checkNotNull(vm.chat.value)
                vm.beforeStoredController = null
                vm.beforeInitialConnection = null
            } else {
                chat = withContext(mainDispatcher) {
                    org.evsyukov.hermes.chat.ChatController(
                        exact.session, exact.socket, vm.viewModelScope, container.strings, id,
                        initialOwner = a,
                        wanted = { container.connection.value === exact },
                        onRead = { stored, at, owner ->
                            readCallbacks += owner
                            readJobs += cacheScope.launch {
                                container.lifetime.withOwner(owner) { container.reads.markRead(alpha.id, stored, at) }
                            }
                        },
                    )
                }
            }
            val retained = checkNotNull(chat)
            assertThat(retained.initialWorkOwner).isEqualTo(a)
            finishInitialStoredLoad(retained)
            if (vmCaller) withContext(mainDispatcher) { vm.chatShown(retained, true) }
            withTimeout(10_000) {
                readJobs.toList().forEach { it.join() }
                vm.readProducers.toList().forEach { it.join() }
            }
            val implicit = alphaServer.requests.filter { it.url.encodedPath.startsWith("/api/sessions/$id") }
            val implicitHead = cache.read(alpha.id, headKey)
            val implicitHistory = cache.read(alpha.id, historyKey)
            val implicitRead = container.reads.of(alpha.id)[id]
            println("STORED_ORIGIN implicit vm=$vmCaller A=$a B=$b accepted=${retained.acceptedReloadRequests.get()} completed=${retained.completedLoadCycles.get()} requests=${implicit.map { it.method + " " + it.url.encodedPath + " " + it.headers["Authorization"] }} head=$implicitHead history=$implicitHistory read=$implicitRead title=${retained.working.title}")
            // Keep all effect checks after real completion; RED must identify borrowed B traffic.
            assertThat(implicit.map { it.method + " " + it.url.encodedPath + " " + it.headers["Authorization"] }).isEmpty()
            assertThat(implicitHead).isNull()
            assertThat(implicitHistory).isNull()
            assertThat(implicitRead).isNull()
            assertThat(readCallbacks).isEmpty()
            assertThat(retained.working.title).isEmpty()
            assertThat(retained.working.items).isEmpty()
            assertThat(retained.working.storedHistory).isNull()
            assertThat(retained.working.loading).isFalse()
            assertThat(exact.socket.connections.value).isEqualTo(socketConnections)
            assertThat(exact.socket.transport.value).isEqualTo(transport)

            // A new public action on the SAME retained controller/exact current root captures B.
            val access = withContext(mainDispatcher) { vm.checkAccess(b, exact); vm.accessProducer!! }
            withTimeout(10_000) {
                access.join()
                withContext(mainDispatcher) { vm.listProducer }?.join()
            }
            val completedBefore = retained.completedLoadCycles.get()
            withContext(mainDispatcher) { retained.reload() }
            withTimeout(10_000) {
                while (retained.completedLoadCycles.get() == completedBefore) delay(5)
                checkNotNull(retained.loadCompletion).await()
                readJobs.toList().forEach { it.join() }
                vm.readProducers.toList().forEach { it.join() }
            }
            drainCacheWrites()
            val fresh = alphaServer.requests.filter { it.url.encodedPath.startsWith("/api/sessions/$id") }
            println("STORED_ORIGIN fresh vm=$vmCaller requests=${fresh.map { it.method + " " + it.url.encodedPath + " " + it.headers["Authorization"] }} head=${cache.read(alpha.id, headKey)} history=${cache.read(alpha.id, historyKey)} read=${container.reads.of(alpha.id)[id]} title=${retained.working.title}")
            val expectedRequests = mutableListOf("GET /api/sessions/$id", "GET /api/sessions/$id/messages")
            if (vmCaller) expectedRequests += listOf("GET /api/sessions/$id/mutation-snapshot", "PATCH /api/sessions/$id")
            assertThat(fresh.map { it.method + " " + it.url.encodedPath }).containsExactlyElementsIn(expectedRequests)
            assertThat(fresh.map { it.headers["Authorization"] }.toSet()).containsExactly("Bearer stored-origin-B")
            assertThat(cache.read(alpha.id, headKey)).isEqualTo(head)
            assertThat(cache.read(alpha.id, historyKey)).isEqualTo(history)
            assertThat(container.reads.of(alpha.id)[id]).isEqualTo(50.0)
            if (!vmCaller) assertThat(readCallbacks).containsExactly(b)
            assertThat(retained.working.title).isEqualTo(id)
            assertThat(retained.working.items).isNotEmpty()
            assertThat(retained.working.storedHistory).isNotEmpty()
            assertThat(retained.working.loading).isFalse()
            assertThat(retained.working.error).isNull()
            assertThat(container.connection.value).isSameInstanceAs(exact)
            assertThat(exact.session.localDataEnabled).isTrue()
            assertThat(exact.socket.transport.value).isEqualTo(transport)
            assertThat(vm.accessProducer).isSameInstanceAs(access)
            assertThat(access.isCancelled).isFalse()
            assertThat(vm.access.value).isEqualTo(Access.OK)
            if (vmCaller) assertThat(vm.chat.value).isSameInstanceAs(retained)
        } finally {
            allowInitial.complete(Unit)
            construction.release.countDown()
            vm.beforeStoredController = null
            vm.beforeInitialConnection = null
            if (!vmCaller) withContext(mainDispatcher) { chat?.close() }
        }
    }

    @Test fun `OR stored constructor cannot borrow B but fresh public reload completes on B`() = runBlocking<Unit> { storedInitialOrigin(false) }
    @Test fun `OR pending VM stored initialization retains admitted A across auth B`() = runBlocking<Unit> { storedInitialOrigin(true) }

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
