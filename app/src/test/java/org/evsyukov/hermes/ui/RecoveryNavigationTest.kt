package org.evsyukov.hermes.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.Tokens
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.ProxySettings
import org.evsyukov.hermes.data.server.ProxyType
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerState
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The recovery screens are the only way out of a server that is down or signed out, so they must
 * reach another saved server and the local settings without probing the server first. The state of a
 * sign-in belongs to one server: switching servers may not carry the route, browser page or error of
 * the old one, and an answer about a server that is no longer current may not overwrite the current one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", application = Application::class)
class RecoveryNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resources = mutableListOf<AutoCloseable>()
    private val dependencies = mutableListOf<AutoCloseable>()
    private val releases = mutableListOf<() -> Unit>()
    private val stores = mutableListOf<ViewModelStore>()
    private val modelJobs = mutableListOf<Job>()
    private var container: AppContainer? = null
    private var viewModel: MainViewModel? = null

    /** Whether the test put its screens into the composition; teardown takes them out again. */
    private val contentPresent = mutableStateOf(false)
    private var contentInstalled = false
    private var installContent: (@Composable () -> Unit) -> Unit = { compose.setContent(it) }
    private var joinTimeoutMillis = JOIN_TIMEOUT_MS
    private var modelScheduler: TestCoroutineScheduler? = null

    /** How teardown takes the installed screens out; a test may replace it to exercise a failed disposal. */
    private var disposeComposition: () -> Unit = {
        contentPresent.value = false
        compose.waitForIdle()
    }

    /** Runs at the restore boundary, so a test can observe which owners have already stopped. */
    private var onRestore: () -> Unit = {}
    private var onRestoreJoined: (Job) -> Unit = {}
    private var beforeOwnerJoin: (Job) -> Unit = {}

    /** Runs once the container exists and before the model subscribes, for a controlled publication gap. */
    private var beforeModel: (AppContainer) -> Unit = {}

    /** The servers this class found on disk; they are put back, since the file outlives the class. */
    private var initialServers: ServerState? = null

    /** Teardown runs once: a test may call it itself to inspect the boundary, and @After must not repeat it. */
    private var closed = false

    @Before
    fun setUp() {
        // The background activity check schedules itself as soon as the container reads the settings;
        // a bare test Application does not run the startup initializer that would provide WorkManager.
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        initialServers = runBlocking { ServerStore(context, PlainCipher, scope).load() }
    }

    @After
    fun tearDown() = closeFixture()

    /**
     * Stops everything this class started, then puts the servers file back. The file is shared by every
     * test class in the JVM: a profile left here would answer for the next class, which removes its own
     * server and expects no server left.
     */
    private fun closeFixture() {
        if (closed) return
        closed = true
        var primary: Throwable? = null
        var ownersJoined = true
        var compositionDisposed = true
        fun record(failure: Throwable) {
            val first = primary
            if (first == null) {
                primary = failure
            } else if (first !== failure) {
                runCatching { first.addSuppressed(failure) }
            }
        }

        try {
            // A held call would make the joins below wait on a gate that nobody opens.
            for (release in releases) {
                try {
                    release()
                } catch (failure: Throwable) {
                    record(failure)
                }
            }

            // The screens go first: their effects and collectors belong to the composition, and a
            // disposal that did not complete means the boundary was not reached.
            var quiesced = true
            if (contentInstalled) {
                try {
                    disposeComposition()
                } catch (failure: Throwable) {
                    quiesced = false
                    compositionDisposed = false
                    record(failure)
                } finally {
                    contentInstalled = false
                }
            }

            // Nothing may write to the servers file after it is put back: every producer this class
            // started is cancelled and observed to finish before the restore below.
            runBlocking {
                for (store in stores) {
                    try {
                        store.clear()
                    } catch (failure: Throwable) {
                        quiesced = false
                        ownersJoined = false
                        record(failure)
                    }
                }
                suspend fun stop(job: Job?, label: String) {
                    try {
                        val owned = requireNotNull(job) { "$label has no owned Job" }
                        // Cancellation is requested even if pumping or join instrumentation fails.
                        owned.cancel()
                        beforeOwnerJoin(owned)
                        check(withTimeoutOrNull(joinTimeoutMillis) {
                            while (!owned.isCompleted) { modelScheduler?.runCurrent(); yield() }
                        } != null) { "$label did not finish" }
                    } catch (failure: Throwable) {
                        quiesced = false
                        ownersJoined = false
                        record(failure)
                    }
                }
                for (job in modelJobs) stop(job, "a ViewModel scope")
                container?.let { stop(it.scope.coroutineContext[Job], "the container scope") }
                stop(scope.coroutineContext[Job], "the fixture scope")
            }

            if (quiesced) {
                initialServers?.let { state ->
                    val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    try {
                        onRestore()
                        runBlocking { ServerStore(context, PlainCipher, cleanup).update { state } }
                    } catch (failure: Throwable) {
                        record(failure)
                    } finally {
                        try {
                            val owner = requireNotNull(cleanup.coroutineContext[Job])
                            runBlocking { withTimeout(JOIN_TIMEOUT_MS) { owner.cancelAndJoin() } }
                            onRestoreJoined(owner)
                        } catch (failure: Throwable) { record(failure) }
                    }
                }
            } else {
                // Restoring now would race the work that did not stop, and would claim a safe boundary
                // that was not reached. Only independent cleanup may proceed.
                record(IllegalStateException("owned work did not stop; the saved servers were not restored"))
            }
        } finally {
            if (ownersJoined && compositionDisposed) {
                for (dependency in dependencies) {
                    try { dependency.close() } catch (failure: Throwable) { record(failure) }
                }
            }
            // Independent cleanup still runs when dependent resources must remain open.
            for (resource in resources) {
                try {
                    resource.close()
                } catch (failure: Throwable) {
                    record(failure)
                }
            }
            if (ownersJoined && compositionDisposed) Dispatchers.resetMain()
        }

        primary?.let { throw it }
    }

    /** A gate a test holds open; teardown releases it before it waits for the work behind it. */
    private fun hold(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        releases += { gate.complete(Unit) }
        return gate
    }

    /** Builds the model through the ordinary lifecycle API, so clearing its store ends its scope. */
    private fun model(app: AppContainer): MainViewModel {
        val store = ViewModelStore()
        stores += store
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
        }
        val vm = ViewModelProvider(store, factory)[MainViewModel::class.java]
        modelJobs += vm.viewModelScope.coroutineContext[Job]!!
        return vm
    }

    @Test
    fun `an offline server reaches settings and another saved server`() {
        val dispatcherA = RecoveryDispatcher(failing = true)
        val dispatcherB = RecoveryDispatcher()
        val profileA = profile("a", "Alpha", server(dispatcherA))
        val profileB = profile("b", "Beta", server(dispatcherB))
        val app = start(listOf(profileA, profileB), active = profileA.id)

        show()

        waitForText(context.getString(R.string.setup_offline_title))
        compose.onNodeWithText(context.getString(R.string.setup_retry)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.common_settings)).assertIsDisplayed()

        val probesBefore = dispatcherA.paths.count { it.endsWith("/api/status") }
        compose.onNodeWithContentDescription(context.getString(R.string.common_settings)).performClick()
        waitForText(context.getString(R.string.settings_appearance))
        compose.onNodeWithText(context.getString(R.string.settings_haptics)).performClick()
        compose.waitUntil(10_000) { !app.settings.current.value.haptics }

        // Returning lands on the same recovery screen, and the settings never asked the server.
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        waitForText(context.getString(R.string.setup_offline_title))
        assertThat(dispatcherA.paths.count { it.endsWith("/api/status") }).isEqualTo(probesBefore)

        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithText("Beta").performClick()
        compose.waitUntil(15_000) { viewModel?.connection?.value?.session?.profile?.id == profileB.id }
        compose.waitUntil(15_000) { viewModel?.access?.value == Access.OK }
        assertThat(app.connection.value?.session?.profile?.id).isEqualTo(profileB.id)
    }

    @Test
    fun `a signed-out server reaches settings and its saved server switcher`() {
        val profileA = profile("a", "Alpha", server(RecoveryDispatcher(signIn = true)))
        val profileB = profile("b", "Beta", server(RecoveryDispatcher(signIn = true)))
        val app = start(listOf(profileA, profileB), active = profileA.id)

        show()

        waitForText(context.getString(R.string.setup_signin_title, profileA.name))
        compose.onNodeWithContentDescription(context.getString(R.string.common_settings)).assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.common_settings)).performClick()
        waitForText(context.getString(R.string.settings_appearance))
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        waitForText(context.getString(R.string.setup_signin_title, profileA.name))

        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithText("Beta").performClick()
        waitForText(context.getString(R.string.setup_signin_title, profileB.name))
    }

    @Test
    fun `switching signed-out servers drops the route and the error of the old one`() {
        val dispatcherA = RecoveryDispatcher(signIn = true)
        val proxy = server(dispatcherA)
        // The first server signs in inside the app, through its proxy; the second is reached directly.
        val profileA = ServerProfile(
            "a", "Alpha", "http://alpha.invalid",
            ProxySettings(ProxyType.HTTP, proxy.hostName, proxy.port),
        )
        val profileB = profile("b", "Beta", server(RecoveryDispatcher(signIn = true)))
        val app = start(listOf(profileA, profileB), active = profileA.id)

        show()

        waitForText(context.getString(R.string.setup_signin_title, profileA.name))
        compose.onNodeWithText(context.getString(R.string.setup_signin_inapp_hint)).assertIsDisplayed()

        // A failed sign-in leaves its error on the screen of the first server.
        dispatcherA.failing = true
        compose.onNodeWithText(context.getString(R.string.setup_signin)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("500", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        dispatcherA.failing = false

        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithText("Beta").performClick()
        waitForText(context.getString(R.string.setup_signin_title, profileB.name))

        compose.onNodeWithText(context.getString(R.string.setup_signin_browser_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.setup_signin_inapp_hint)).assertDoesNotExist()
        assertThat(compose.onAllNodes(hasText("500", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(app.connection.value?.session?.profile?.id).isEqualTo(profileB.id)
    }

    @Test
    fun `a late answer about the old server cannot land after its connection is gone`() = runBlocking<Unit> {
        val dispatcherA = RecoveryDispatcher(failing = true, holdStatus = true)
        val profileA = profile("a", "Alpha", server(dispatcherA))
        val broken = ServerProfile("broken", "Broken", "bad host")
        val app = start(listOf(profileA, broken), active = profileA.id)

        withTimeout(10_000) { await { dispatcherA.arrived.count == 0L } }
        runBlocking { app.servers.activate(broken.id) }
        withTimeout(10_000) { await { app.connection.value == null } }
        assertThat(viewModel?.access?.value).isEqualTo(Access.UNKNOWN)

        dispatcherA.releaseStatus.countDown()
        withTimeout(10_000) { await { dispatcherA.served.count == 0L } }
        delay(300)

        // The answer belongs to a connection that is no longer there.
        assertThat(viewModel?.access?.value).isEqualTo(Access.UNKNOWN)
        assertThat(viewModel?.accessError?.value).isNull()
    }

    @Test
    fun `a late answer about the old server cannot overwrite the new one`() = runBlocking<Unit> {
        val dispatcherA = RecoveryDispatcher(failing = true, holdStatus = true)
        val profileA = profile("a", "Alpha", server(dispatcherA))
        val profileB = profile("b", "Beta", server(RecoveryDispatcher()))
        val app = start(listOf(profileA, profileB), active = profileA.id)

        withTimeout(10_000) { await { dispatcherA.arrived.count == 0L } }
        runBlocking { app.servers.activate(profileB.id) }
        withTimeout(15_000) { await { viewModel?.access?.value == Access.OK } }

        dispatcherA.releaseStatus.countDown()
        withTimeout(10_000) { await { dispatcherA.served.count == 0L } }
        delay(300)

        assertThat(viewModel?.access?.value).isEqualTo(Access.OK)
        assertThat(viewModel?.accessError?.value).isNull()
        assertThat(viewModel?.connection?.value?.session?.profile?.id).isEqualTo(profileB.id)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the Russian recovery screen keeps settings and retry reachable at a large font`() = runBlocking<Unit> {
        val dispatcherA = RecoveryDispatcher(failing = true, detail = "Сервер недоступен. ".repeat(40))
        val profileA = profile("a", LONG_NAME, server(dispatcherA))
        val profileB = profile("b", "Beta", server(RecoveryDispatcher()))
        val app = start(listOf(profileA, profileB), active = profileA.id)
        showLargeFont()

        waitForText(context.getString(R.string.setup_offline_title))

        // Settings stays on the screen, outside the scroll of a long error.
        compose.onNodeWithContentDescription(context.getString(R.string.common_settings)).performClick()
        waitForText(context.getString(R.string.settings_appearance))
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        waitForText(context.getString(R.string.setup_offline_title))

        // Retry is below the fold with this error: a scroll and a tap still reach it.
        val probes = dispatcherA.paths.count { it.endsWith("/api/status") }
        compose.onNodeWithText(context.getString(R.string.setup_retry)).performScrollTo().performClick()
        compose.waitUntil(15_000) { dispatcherA.paths.count { it.endsWith("/api/status") } > probes }
        waitForText(context.getString(R.string.setup_offline_title))

        // The saved servers and the way to add one open from the pinned footer by a tap.
        compose.onNodeWithText(LONG_NAME).performClick()
        waitForText("Beta")
        compose.onNodeWithText(context.getString(R.string.sidebar_configure, LONG_NAME)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sidebar_add_server)).assertIsDisplayed()
        compose.onNodeWithText("Beta").performScrollTo().performClick()
        awaitProfile(app, profileB.id)
        awaitAccess(requireNotNull(viewModel), Access.OK)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the Russian sign-in screen keeps settings and the saved servers at a large font`() {
        val profileA = profile("a", LONG_NAME, server(RecoveryDispatcher(signIn = true)))
        val profileB = profile("b", "Beta", server(RecoveryDispatcher(signIn = true)))
        start(listOf(profileA, profileB), active = profileA.id)
        showLargeFont()

        waitForText(context.getString(R.string.setup_signin_title, LONG_NAME))
        compose.onNodeWithText(context.getString(R.string.setup_signin)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.common_settings)).performClick()
        waitForText(context.getString(R.string.settings_appearance))
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        waitForText(context.getString(R.string.setup_signin_title, LONG_NAME))

        compose.onNodeWithText(LONG_NAME).performClick()
        waitForText("Beta")
        compose.onNodeWithText(context.getString(R.string.sidebar_configure, LONG_NAME)).assertIsDisplayed()
        compose.onNodeWithText("Beta").performScrollTo().performClick()
        waitForText(context.getString(R.string.setup_signin_title, "Beta"))
    }

    /*
     * The sign-in of one server can outlive its place on the screen: the status it reads may come
     * back after another server is current. These cases drive the real MainViewModel entry, the real
     * ServerSession PKCE flow and the loopback callback, with the browser replaced by a synthetic
     * one that answers the loopback address at once.
     */

    @Test
    fun `the current access check still refreshes expired tokens without opening sign-in`() = runBlocking<Unit> {
        val dispatcher = RecoveryDispatcher(signIn = true)
        val profileA = profile("a", "Alpha", server(dispatcher))
        val app = start(listOf(profileA), active = profileA.id, tokens = mapOf(profileA.id to Tokens("expired", refreshToken = "rt", expiresAt = 1)))
        val vm = requireNotNull(viewModel)
        awaitAccess(vm, Access.OK)
        assertThat(app.servers.load().tokens[profileA.id]?.accessToken).isEqualTo("fresh")
        assertThat(dispatcher.paths.count { it.endsWith("/auth/native/refresh") }).isEqualTo(1)
        assertThat(dispatcher.tokensIssued).isEqualTo(0)
    }

    @Test
    fun `a current sign-in opens its browser page and signs the server in`() = runBlocking<Unit> {
        val dispatcher = RecoveryDispatcher(signIn = true)
        val profileA = profile("a", "Alpha", server(dispatcher))
        val app = start(listOf(profileA), active = profileA.id)
        val vm = requireNotNull(viewModel)
        awaitAccess(vm, Access.SIGN_IN)

        val opened = CopyOnWriteArrayList<String>()
        signIn(vm, current(app), browser(opened)).await()

        assertThat(opened).hasSize(1)
        assertThat(opened.single()).contains("/auth/native/authorize")
        assertThat(dispatcher.tokensIssued).isEqualTo(1)
        assertThat(app.servers.load().tokens[profileA.id]?.accessToken).isEqualTo("at")
        awaitAccess(vm, Access.OK)
    }

    @Test
    fun `a sign-in whose server was replaced never opens its browser page`() = runBlocking<Unit> {
        val dispatcherA = RecoveryDispatcher(signIn = true)
        val dispatcherB = RecoveryDispatcher(signIn = true)
        val profileA = profile("a", "Alpha", server(dispatcherA))
        val profileB = profile("b", "Beta", server(dispatcherB))
        val app = start(listOf(profileA, profileB), active = profileA.id)
        val vm = requireNotNull(viewModel)
        awaitAccess(vm, Access.SIGN_IN)

        // The status of the sign-in is answered only after the other server is already current.
        dispatcherA.holdStatus = true
        val opened = CopyOnWriteArrayList<String>()
        val job = signIn(vm, current(app), recording(opened))
        withTimeout(10_000) { await { dispatcherA.statuses.get() >= 2 } }
        runBlocking { app.servers.activate(profileB.id) }
        awaitProfile(app, profileB.id)
        awaitAccess(vm, Access.SIGN_IN)

        dispatcherA.releaseStatus.countDown()
        // The stale flow must end here; a page opened on the retired server would then wait on the loopback.
        withTimeout(5_000) { job.join() }
        assertThat(job.isCancelled).isTrue()

        assertThat(opened).isEmpty()
        assertThat(dispatcherA.tokensIssued).isEqualTo(0)
        assertThat(vm.access.value).isEqualTo(Access.SIGN_IN)

        // The current server still signs in; the refusal was for the retired one alone.
        val openedB = CopyOnWriteArrayList<String>()
        signIn(vm, current(app), browser(openedB)).await()
        assertThat(openedB).hasSize(1)
        assertThat(app.servers.load().tokens[profileB.id]?.accessToken).isEqualTo("at")
        awaitAccess(vm, Access.OK)
    }

    @Test
    fun `a sign-in whose server is gone never opens its browser page`() = runBlocking<Unit> {
        val dispatcherA = RecoveryDispatcher(signIn = true)
        val profileA = profile("a", "Alpha", server(dispatcherA))
        val broken = ServerProfile("broken", "Broken", "bad host")
        val app = start(listOf(profileA, broken), active = profileA.id)
        val vm = requireNotNull(viewModel)
        awaitAccess(vm, Access.SIGN_IN)

        dispatcherA.holdStatus = true
        val opened = CopyOnWriteArrayList<String>()
        val job = signIn(vm, current(app), recording(opened))
        withTimeout(10_000) { await { dispatcherA.statuses.get() >= 2 } }
        runBlocking { app.servers.activate(broken.id) }
        awaitGoneServer(app, vm)
        assertThat(vm.access.value).isEqualTo(Access.UNKNOWN)

        dispatcherA.releaseStatus.countDown()
        withTimeout(5_000) { job.join() }
        assertThat(job.isCancelled).isTrue()

        assertThat(opened).isEmpty()
        assertThat(dispatcherA.tokensIssued).isEqualTo(0)
        assertThat(vm.access.value).isEqualTo(Access.UNKNOWN)
        assertThat(vm.accessError.value).isNull()

        // A saved server is still reachable and signs in as usual.
        dispatcherA.holdStatus = false
        runBlocking { app.servers.activate(profileA.id) }
        awaitProfile(app, profileA.id)
        awaitAccess(vm, Access.SIGN_IN)
        val openedAgain = CopyOnWriteArrayList<String>()
        signIn(vm, current(app), browser(openedAgain)).await()
        assertThat(openedAgain).hasSize(1)
        awaitAccess(vm, Access.OK)
    }

    @Test
    fun `a gone server is ready only after the model applies the removal`() = runBlocking<Unit> {
        val dispatcher = RecoveryDispatcher(signIn = true)
        val profileA = profile("a", "Alpha", server(dispatcher))
        val broken = ServerProfile("broken", "Broken", "bad host")
        val main = StandardTestDispatcher()
        beforeModel = { modelScheduler = main.scheduler; Dispatchers.setMain(main) }
        val app = start(listOf(profileA, broken), active = profileA.id)
        val vm = requireNotNull(viewModel)
        withTimeout(10_000) { await { main.scheduler.runCurrent(); vm.access.value == Access.SIGN_IN } }
        app.servers.activate(broken.id)
        withTimeout(10_000) { await { app.connection.value == null } }
        // The real container has applied removal; the actual model's Main work remains held.
        assertThat(vm.access.value).isEqualTo(Access.SIGN_IN)
        try {
            assertThat(withTimeoutOrNull(1_000) { awaitGoneServer(app, vm) }).isNull()
        } finally {
            main.scheduler.runCurrent()
        }
        awaitGoneServer(app, vm)
        assertThat(vm.access.value).isEqualTo(Access.UNKNOWN)
        assertThat(vm.accessError.value).isNull()
    }

    @Test
    fun `a sign-in that finishes after the switch keeps the new server state`() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        val dispatcherA = RecoveryDispatcher(signIn = true, tokenHold = gate)
        val dispatcherB = RecoveryDispatcher(signIn = true)
        val profileA = profile("a", "Alpha", server(dispatcherA))
        val profileB = profile("b", "Beta", server(dispatcherB))
        val app = start(listOf(profileA, profileB), active = profileA.id)
        val vm = requireNotNull(viewModel)
        awaitAccess(vm, Access.SIGN_IN)
        val connectionA = current(app)

        val opened = CopyOnWriteArrayList<String>()
        val job = signIn(vm, connectionA, browser(opened))
        withTimeout(10_000) { await { dispatcherA.tokenRequested.count == 0L } }

        // The browser page of A was opened while A was current; the exchange finishes after B is.
        runBlocking { app.servers.activate(profileB.id) }
        awaitProfile(app, profileB.id)
        awaitAccess(vm, Access.SIGN_IN)
        gate.countDown()
        job.await()
        delay(300)

        assertThat(opened).hasSize(1)
        assertThat(app.servers.load().tokens[profileA.id]?.accessToken).isEqualTo("at")
        assertThat(vm.access.value).isEqualTo(Access.SIGN_IN)
        assertThat(vm.accessError.value).isNull()

        // A retired server is refused at the entry, before its status is even asked.
        val statuses = dispatcherA.statuses.get()
        val staleBrowser = CopyOnWriteArrayList<String>()
        val refused = withTimeoutOrNull(5_000) { runCatching { vm.signIn(connectionA, recording(staleBrowser)) } }
        assertThat(refused).isNotNull()
        assertThat(staleBrowser).isEmpty()
        assertThat(dispatcherA.statuses.get()).isEqualTo(statuses)
        assertThat(vm.access.value).isEqualTo(Access.SIGN_IN)

        // The current server signs in and recovers.
        val openedB = CopyOnWriteArrayList<String>()
        signIn(vm, current(app), browser(openedB)).await()
        assertThat(openedB).hasSize(1)
        assertThat(app.servers.load().tokens[profileB.id]?.accessToken).isEqualTo("at")
        awaitAccess(vm, Access.OK)
    }

    /*
     * The fixture boundary itself: the teardown that runs after every test must stop the work it owns
     * before it puts the saved servers back, or a late write of that work lands in the next class.
     */

    @Test
    fun `partial composition setup is disposed by actual teardown`() {
        val snapshot = requireNotNull(initialServers)
        val install = installContent
        val dispose = disposeComposition
        val setupFailure = IllegalStateException("after actual installation")
        installContent = { content -> install(content); throw setupFailure }
        try {
            assertThat(runCatching { show { Text("partial-marker") } }.exceptionOrNull()).isSameInstanceAs(setupFailure)
            closeFixture()
            assertThat(compose.onAllNodes(hasText("partial-marker")).fetchSemanticsNodes()).isEmpty()
        } finally {
            dispose()
            restoreServers(snapshot)
        }
    }

    @Test
    fun `actual teardown refuses dependent closure while an owned producer remains held`() {
        val snapshot = requireNotNull(initialServers)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = requireNotNull(scope.coroutineContext[Job])
        val child = scope.launch {
            withContext(NonCancellable) { started.complete(Unit); release.await() }
        }
        runBlocking { withTimeout(10_000) { started.await() } }
        var dependentClosed = false
        dependencies += AutoCloseable { dependentClosed = true }
        joinTimeoutMillis = 25
        try {
            val thrown = runCatching { closeFixture() }.exceptionOrNull()
            assertThat(thrown).isNotNull()
            assertThat(child.isCompleted).isFalse()
            assertThat(dependentClosed).isFalse()
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(10_000) { owner.join() } }
            dependencies.forEach { it.close() }
            Dispatchers.resetMain()
            restoreServers(snapshot)
        }
    }

    @Test
    fun `an unexpected join failure preserves dependencies and still stops other owners`() {
        val snapshot = requireNotNull(initialServers)
        val app = start(listOf(profile("a", "Alpha", server(RecoveryDispatcher()))), active = "a")
        val expected = IllegalStateException("join stage failed")
        var calls = 0
        beforeOwnerJoin = { if (++calls == 1) throw expected }
        var independentClosed = false
        resources += AutoCloseable { independentClosed = true }
        var dependentClosed = false
        dependencies += AutoCloseable { dependentClosed = true }
        try {
            val thrown = runCatching { closeFixture() }.exceptionOrNull()
            assertThat(thrown).isSameInstanceAs(expected)
            assertThat(calls).isEqualTo(3)
            assertThat(app.scope.coroutineContext[Job]?.isCompleted).isTrue()
            assertThat(scope.coroutineContext[Job]?.isCompleted).isTrue()
            assertThat(independentClosed).isTrue()
            assertThat(dependentClosed).isFalse()
        } finally {
            runBlocking { withTimeout(JOIN_TIMEOUT_MS) { modelJobs.forEach { it.cancelAndJoin() } } }
            dependencies.forEach { it.close() }
            Dispatchers.resetMain()
            restoreServers(snapshot)
        }
    }

    @Test
    fun `restore cleanup preserves the primary failure and suppresses a later failure`() {
        val first = IllegalStateException("restore failed")
        val later = IllegalArgumentException("after restore join failed")
        onRestore = { throw first }
        onRestoreJoined = { owner -> assertThat(owner.isCompleted).isTrue(); throw later }
        var independentClosed = false
        resources += AutoCloseable { independentClosed = true }
        val thrown = runCatching { closeFixture() }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(first)
        assertThat(thrown?.suppressed?.toList()).containsExactly(later)
        assertThat(independentClosed).isTrue()
    }

    @Test
    fun `teardown joins an owned producer before restoring the saved servers`() {
        val snapshot = requireNotNull(initialServers)
        val late = ServerProfile("late", "Late", "http://late.invalid")
        val reached = CountDownLatch(1)
        val written = CountDownLatch(1)
        val gate = hold()
        scope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                reached.countDown()
                gate.await()
                val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    ServerStore(context, PlainCipher, own).update {
                        ServerState(servers = listOf(late), activeId = late.id)
                    }
                } finally {
                    runBlocking { withTimeout(JOIN_TIMEOUT_MS) { requireNotNull(own.coroutineContext[Job]).cancelAndJoin() } }
                }
                written.countDown()
            }
        }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

        try {
            closeFixture()
        } finally {
            gate.complete(Unit)
        }

        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue()
        val read = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val persisted = try {
            runBlocking { ServerStore(context, PlainCipher, read).load() }
        } finally {
            runBlocking { withTimeout(JOIN_TIMEOUT_MS) { requireNotNull(read.coroutineContext[Job]).cancelAndJoin() } }
        }
        assertThat(persisted).isEqualTo(snapshot)
    }

    @Test
    fun `teardown attempts every owned close and keeps the first failure`() {
        val closedInOrder = CopyOnWriteArrayList<String>()
        val first = IllegalStateException("first close failed")
        val third = IllegalArgumentException("third close failed")
        resources += AutoCloseable {
            closedInOrder += "first"
            throw first
        }
        resources += AutoCloseable { closedInOrder += "second" }
        resources += AutoCloseable {
            closedInOrder += "third"
            throw third
        }

        val thrown = runCatching { closeFixture() }.exceptionOrNull()

        assertThat(closedInOrder).containsExactly("first", "second", "third").inOrder()
        assertThat(thrown).isSameInstanceAs(first)
        assertThat(thrown?.suppressed?.toList()).containsExactly(third)
    }

    /*
     * The composition is an owner too: a teardown that could not take the screens out has not reached
     * a safe boundary, even when every scope behind them did stop.
     */

    @Test
    fun `teardown removes the screens and completes the model before restoring the saved servers`() {
        val snapshot = requireNotNull(initialServers)
        val app = start(listOf(profile("a", "Alpha", server(RecoveryDispatcher()))), active = "a")
        show {
            Box {
                HermesApp(requireNotNull(viewModel))
                Text("fixture-marker")
            }
        }
        waitForText("fixture-marker")

        val observed = CopyOnWriteArrayList<String>()
        onRestore = {
            observed += "content=${contentInstalled}"
            observed += "model=${modelJobs.single().isCompleted}"
            observed += "container=${app.scope.coroutineContext[Job]?.isCompleted}"
            observed += "fixture=${scope.coroutineContext[Job]?.isCompleted}"
        }

        closeFixture()

        assertThat(observed).containsExactly("content=false", "model=true", "container=true", "fixture=true").inOrder()
        assertThat(compose.onAllNodes(hasText("fixture-marker")).fetchSemanticsNodes()).isEmpty()
        assertThat(readServers()).isEqualTo(snapshot)
    }

    @Test
    fun `a failed composition disposal skips the restore but not the other cleanup`() {
        val snapshot = requireNotNull(initialServers)
        // The real disposer, kept before the failure is injected: cleanup must still take the screens out.
        val normalDisposal = disposeComposition
        var app: AppContainer? = null
        var failure: Throwable? = null
        try {
            val late = ServerProfile("late", "Late", "http://late.invalid")
            val started = start(listOf(profile("a", "Alpha", server(RecoveryDispatcher()))), active = "a")
            app = started
            show { Text("fixture-marker") }
            waitForText("fixture-marker")
            val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                runBlocking {
                    ServerStore(context, PlainCipher, writer).update {
                        ServerState(servers = listOf(late), activeId = late.id)
                    }
                }
            } finally {
                runBlocking { withTimeout(JOIN_TIMEOUT_MS) { requireNotNull(writer.coroutineContext[Job]).cancelAndJoin() } }
            }

            val closed = CopyOnWriteArrayList<String>()
            resources += AutoCloseable { closed += "after-server" }
            val disposal = IllegalStateException("composition disposal failed")
            disposeComposition = { throw disposal }
            val later = IllegalArgumentException("later close failed")
            resources += AutoCloseable { throw later }

            val thrown = runCatching { closeFixture() }.exceptionOrNull()

            assertThat(thrown).isSameInstanceAs(disposal)
            assertThat(thrown?.suppressed?.toList()).contains(later)
            assertThat(closed).containsExactly("after-server")
            assertThat(modelJobs.single().isCompleted).isTrue()
            assertThat(started.scope.coroutineContext[Job]?.isCompleted).isTrue()
            assertThat(scope.coroutineContext[Job]?.isCompleted).isTrue()
            val persisted = readServers()
            assertThat(persisted).isNotEqualTo(snapshot)
            assertThat(persisted.servers.map { it.id }).containsExactly(late.id)
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            try {
                // The injected disposer never removed the screens; the real one does it here, and the
                // shared file is put back only after that removal and the owned jobs have finished.
                normalDisposal()
                val stopped = modelJobs.all { it.isCompleted } &&
                    app?.scope?.coroutineContext?.get(Job)?.isCompleted != false &&
                    scope.coroutineContext[Job]?.isCompleted != false
                if (!stopped) error("owned work did not finish; the saved servers were not restored")
                dependencies.forEach { it.close() }
                Dispatchers.resetMain()
                restoreServers(snapshot)
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun readServers(): ServerState {
        val read = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            runBlocking { ServerStore(context, PlainCipher, read).load() }
        } finally {
            runBlocking { withTimeout(JOIN_TIMEOUT_MS) { requireNotNull(read.coroutineContext[Job]).cancelAndJoin() } }
        }
    }

    private fun restoreServers(state: ServerState) {
        val restore = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking { ServerStore(context, PlainCipher, restore).update { state } }
        } finally {
            runBlocking { withTimeout(JOIN_TIMEOUT_MS) { requireNotNull(restore.coroutineContext[Job]).cancelAndJoin() } }
        }
    }

    private fun profile(id: String, name: String, server: MockWebServer) =
        ServerProfile(id, name, server.url("/").toString())

    private fun server(dispatcher: RecoveryDispatcher): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        dependencies += server
        releases += { dispatcher.releaseStatus.countDown(); dispatcher.releaseToken() }
        return server
    }

    private fun current(app: AppContainer): Connection = requireNotNull(app.connection.value)

    private suspend fun awaitProfile(app: AppContainer, id: String) {
        withTimeout(15_000) { await { app.connection.value?.session?.profile?.id == id } }
    }

    private suspend fun awaitAccess(vm: MainViewModel, access: Access) {
        withTimeout(15_000) { await { vm.access.value == access } }
    }

    /** The gone-server readiness: the container has no connection and the model has applied that. */
    private suspend fun awaitGoneServer(app: AppContainer, vm: MainViewModel) {
        withTimeout(10_000) { await { app.connection.value == null && vm.access.value == Access.UNKNOWN } }
    }

    private fun signIn(vm: MainViewModel, connection: Connection, openBrowser: (String) -> Unit): Deferred<Unit> =
        scope.async(Dispatchers.IO) { vm.signIn(connection, openBrowser) }

    private fun recording(opened: MutableList<String>): (String) -> Unit = { opened += it }

    /** The browser part: the dashboard would answer the authorize page; here the loopback gets the code at once. */
    private fun browser(opened: MutableList<String>): (String) -> Unit = { url ->
        opened += url
        val authorize = url.toHttpUrl()
        val redirect = requireNotNull(authorize.queryParameter("redirect_uri"))
        val state = requireNotNull(authorize.queryParameter("state"))
        scope.launch(Dispatchers.IO) {
            runCatching { URL("$redirect?code=test-code&state=${URLEncoder.encode(state, "UTF-8")}").readText() }
        }
    }

    private fun start(profiles: List<ServerProfile>, active: String, tokens: Map<String, Tokens> = emptyMap()): AppContainer {
        runBlocking {
            ServerStore(context, PlainCipher, scope).update { ServerState(servers = profiles, activeId = active, tokens = tokens) }
        }
        val app = AppContainer(context, PlainCipher)
        container = app
        runBlocking {
            withTimeout(10_000) {
                app.servers.state.first { it.active?.id == active && it.servers.map { profile -> profile.id }.toSet() == profiles.map { it.id }.toSet() }
            }
            withTimeout(10_000) { app.connection.first { it != null } }
        }
        beforeModel(app)
        viewModel = model(app)
        return app
    }

    private fun show(content: @Composable () -> Unit = { HermesApp(requireNotNull(viewModel)) }) {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        contentPresent.value = true
        contentInstalled = true
        installContent {
            if (contentPresent.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { content() }
            }
        }
    }

    /** The same screens at twice the system font, where a long title or error takes the room. */
    private fun showLargeFont() = show {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 2f)) {
            HermesApp(requireNotNull(viewModel))
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private suspend fun await(condition: () -> Boolean) {
        while (!condition()) delay(10)
    }

    private class RecoveryDispatcher(
        @Volatile var failing: Boolean = false,
        @Volatile var signIn: Boolean = false,
        @Volatile var detail: String = "boom",
        @Volatile var holdStatus: Boolean = false,
        private val tokenHold: CountDownLatch? = null,
    ) : Dispatcher() {
        val paths = CopyOnWriteArrayList<String>()
        val arrived = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val served = CountDownLatch(1)
        val tokenRequested = CountDownLatch(1)
        val statuses = AtomicInteger()

        @Volatile
        var tokensIssued = 0

        fun releaseToken() { tokenHold?.countDown() }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            paths += path
            if (path.endsWith("/api/status")) {
                statuses.incrementAndGet()
                arrived.countDown()
                if (holdStatus) check(releaseStatus.await(10, TimeUnit.SECONDS))
                served.countDown()
                if (failing) return json(500, """{"detail": "$detail"}""")
                return json(200, """{"auth_required": $signIn}""")
            }
            if (path.endsWith("/auth/native/refresh")) {
                return json(200, """{"access_token":"fresh","refresh_token":"rt","expires_at":9999999999}""")
            }
            if (path.endsWith("/auth/native/token")) {
                tokensIssued++
                tokenRequested.countDown()
                tokenHold?.let { check(it.await(10, TimeUnit.SECONDS)) }
                return json(200, """{"access_token": "at", "refresh_token": "rt", "expires_at": 9999999999, "provider": "self-hosted", "user_id": "u"}""")
            }
            if (path.endsWith("/api/system/stats")) return json(200, "{}")
            if (path.endsWith("/api/sessions")) return json(200, """{"sessions": [], "total": 0}""")
            return MockResponse.Builder().code(404).body("""{"detail": "not found"}""").build()
        }

        private fun json(code: Int, body: String) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    private companion object {
        const val LONG_NAME = "Очень длинное имя сохранённого сервера"

        /** How long teardown waits for work it cancelled; past this it reports a failed boundary. */
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
