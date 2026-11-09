package org.evsyukov.hermes.ui.search

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.evsyukov.hermes.ui.clearSyntheticServers
import org.evsyukov.hermes.ui.joinDraftJobs
import org.evsyukov.hermes.ui.runCleanupSteps
import org.evsyukov.hermes.chat.closeWithOwnedJobs
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import android.app.Application
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.SearchHit
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.ui.HermesApp
import org.evsyukov.hermes.ui.MainViewModel
import org.evsyukov.hermes.ui.Screen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The whole journey through production wiring: the drawer opens Search, a result leaves Search for
 * the conversation, and Back returns to the same query, results and list place without asking the
 * server again or taking the keyboard. The app is the real [HermesApp] on a real [MainViewModel] and
 * container; the server is a local fixture and only the search answer is supplied by the test.
 *
 * Result rows are tapped at the centre of their drawn bounds. The query is entered and the list is
 * scrolled through test/semantic actions, and Back is dispatched through the NavigationEvent and
 * OnBackPressed owners composed by this test. It therefore does not prove real OS or device Back,
 * IME or pointer delivery; it checks the app's screens and state through its production composition.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", fontScale = 1.3f, application = Application::class)
class SearchReturnJourneyTest {
    @get:Rule(order = 0)
    val lifetime = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            try { base.evaluate() } catch (t: Throwable) { failure = t } finally {
                try { tearDown() } catch (t: Throwable) {
                    val first = failure
                    if (first == null) failure = t else if (first !== t) first.addSuppressed(t)
                }
            }
            failure?.let { throw it }
        }
    } }
    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val strings = AppStrings(context.resources)
    private val searches = AtomicInteger()

    private val owner = BackOwner()
    private val backInput = TestBackInput()
    private lateinit var container: AppContainer
    private lateinit var server: MockWebServer
    private lateinit var vm: MainViewModel
    private var beforeReplacementWait: (suspend (Connection) -> Unit)? = null
    private val viewerFixtures = mutableListOf<File>()

    /** The answer of the test's lookup, by chat id in the order the rows show it. */
    private var answer: List<SearchHit> = listOf(hit("s1"), hit("s2"))

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    private fun tearDown() {
        val app = if (::container.isInitialized) container else null
        val model = if (::vm.isInitialized) vm else null
        val roots = listOfNotNull(model?.viewModelScope?.coroutineContext?.get(Job), app?.scope?.coroutineContext?.get(Job))
        var joined = false
        runBlocking {
            runCleanupSteps(listOf(
                { model?.chat?.value?.close() },
                { app?.connection?.value?.socket?.stop() },
                { roots.forEach { it.cancel() } },
                { joined = joinDraftJobs(roots, 10_000) { shadowOf(Looper.getMainLooper()).idle() }; check(joined) },
                {
                    if (joined) {
                        val close = {
                            runBlocking(Dispatchers.Default) {
                                runCleanupSteps(listOf(
                                    { app?.servers?.clearSyntheticServers() },
                                    { if (::server.isInitialized) server.close() },
                                    {
                                        viewerFixtures.forEach { file ->
                                            check(!file.exists() || file.delete()) { "Could not delete owned viewer fixture ${file.name}" }
                                        }
                                    },
                                ))
                            }
                        }
                        if (app == null) close() else app.scope.closeWithOwnedJobs(app.cacheDatabase, closeOther = close)
                    }
                },
            ))
        }
        owner.navigationEventDispatcher.removeInput(backInput)
    }

    @Test
    fun `a result of another chat opens it, and Back returns to the same results without asking again`() {
        start()
        openChat("s0")
        openSearch()
        search("alpha")
        assertThat(searches.get()).isEqualTo(1)
        assertHint()
        captureFrame("search-en-360-large-font")
        val before = vm.search.value

        clickRow("Chat s1")

        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("s1")
        assertThat(vm.stack).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isNotEmpty()

        back()

        assertThat(vm.stack.lastOrNull()).isEqualTo(Screen.Search)
        assertThat(compose.onAllNodes(hasText("alpha")).fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isEmpty()
        assertThat(vm.search.value).isSameInstanceAs(before)
        assertThat(searches.get()).isEqualTo(1)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()

        // The return journey is bounded: Back from Search goes to the chat, not to the result again.
        back()
        assertThat(vm.stack).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `a result of the current chat also leaves Search`() {
        start()
        openChat("s1")
        val current = vm.chat.value
        openSearch()
        search("alpha")

        clickRow("Chat s1")

        assertThat(vm.chat.value).isSameInstanceAs(current)
        assertThat(vm.stack).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isNotEmpty()

        back()

        assertThat(compose.onAllNodes(hasText("alpha")).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `Back returns to the same row of the results, not to their start`() {
        start()
        openChat("s0")
        answer = (0 until 30).map { hit("s$it") }
        openSearch()
        search("alpha")
        compose.onNode(hasScrollAction()).performScrollToIndex(16)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 37f) }
        compose.waitForIdle()
        compose.onNodeWithText("Chat s16").assertIsDisplayed()
        val position = compose.runOnIdle { vm.searchListPosition }
        assertThat(position.first).isEqualTo(16)
        assertThat(position.second).isEqualTo(37)
        val before = vm.search.value
        assertThat(searches.get()).isEqualTo(1)

        clickRow("Chat s16")
        assertThat(vm.searchJourney.value?.index).isEqualTo(position.first)
        assertThat(vm.searchJourney.value?.offset).isEqualTo(position.second)
        back()

        compose.onNodeWithText("Chat s16").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasText("Chat s0")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.runOnIdle { vm.searchListPosition }).isEqualTo(position)
        assertThat(vm.search.value).isSameInstanceAs(before)
        assertThat(searches.get()).isEqualTo(1)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `a file viewer over the result returns to the chat, then to Search`() {
        start()
        openChat("s0")
        openSearch()
        search("alpha")
        clickRow("Chat s1")

        val note = File.createTempFile("search-return-note-", ".txt", context.cacheDir).also {
            viewerFixtures += it
            it.writeText("note")
        }
        assertThat(note.parentFile).isEqualTo(context.cacheDir)
        compose.runOnUiThread { vm.openLocalFile(note, note.name, "text/plain") }
        compose.waitForIdle()
        assertThat(vm.stack.lastOrNull()).isInstanceOf(Screen.Viewer::class.java)
        val journey = vm.searchJourney.value
        assertThat(journey).isNotNull()
        back()
        assertThat(vm.searchJourney.value).isSameInstanceAs(journey)
        assertThat(vm.stack).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isNotEmpty()

        back()

        assertThat(compose.onAllNodes(hasText("alpha")).fetchSemanticsNodes()).isNotEmpty()
        assertThat(vm.search.value.hits).containsExactly(hit("s1"), hit("s2"))
    }

    @Test
    fun `a replaced connection retires the journey, and Back does not show stale results`() {
        start()
        openChat("s0")
        openSearch()
        search("alpha")
        clickRow("Chat s1")
        replaceActiveProfile(profile(trailingSlash = false))

        back()

        assertThat(vm.stack).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodes(hasText("alpha")).fetchSemanticsNodes()).isEmpty()
    }

    @Test fun `replacement readiness follows current identity when an intermediate is superseded`() {
        start()
        openChat("s0")
        openSearch()
        search("alpha")
        clickRow("Chat s1")
        val desired = profile(trailingSlash = false)
        var superseded: Connection? = null
        beforeReplacementWait = { candidate ->
            superseded = candidate
            val intermediate = desired.copy(name = "Intermediate")
            container.servers.save(intermediate)
            container.connection.first { it?.session?.profile == intermediate && it !== candidate }
            container.servers.save(desired)
            container.connection.first { it?.session?.profile == desired && it !== candidate }
            assertThat(container.connection.value).isNotSameInstanceAs(candidate)
        }
        val replaced = replaceActiveProfile(desired)
        assertThat(replaced).isNotSameInstanceAs(superseded)
        assertThat(replaced).isSameInstanceAs(container.connection.value)
        assertThat(vm.search.value.request?.connection).isSameInstanceAs(replaced)
        assertThat(vm.searchJourney.value).isNull()
        back()
        assertThat(vm.stack).isEmpty()
        assertThat(compose.onAllNodes(hasContentDescription(strings.get(R.string.chat_chats))).fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodes(hasText("alpha")).fetchSemanticsNodes()).isEmpty()
    }

    private fun replaceActiveProfile(desired: ServerProfile): Connection {
        val previous = container.connection.value
        runBlocking {
            container.servers.save(desired)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    container.connection.filterNotNull().first { it !== previous }
                        .also { beforeReplacementWait?.invoke(it) }
                }
            }
        }
        var replaced: Connection? = null
        compose.waitUntil(10_000) {
            // IO can publish a newer connection after any single Main drain. Apply the
            // current identity, not a first emission that StateFlow may already have retired.
            shadowOf(Looper.getMainLooper()).idle()
            val current = container.connection.value
            val applied = current != null && current !== previous && current.session.profile == desired &&
                vm.search.value.request?.connection === current
            if (applied) replaced = current
            applied
        }
        compose.waitForIdle()
        return checkNotNull(replaced)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp")
    fun `the honest fallback is Russian on a Russian phone`() {
        start()
        openChat("s0")
        openSearch()
        search("alpha")

        assertThat(context.getString(R.string.search_result_hint)).isEqualTo("Открывает чат, а не точное сообщение.")
        compose.onNodeWithText(context.getString(R.string.search_result_hint)).assertIsDisplayed()
        captureFrame("search-ru-360-large-font")
    }

    private fun start() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/api/status" -> json("""{"auth_required":false}""")
                    request.url.encodedPath.endsWith("/messages") -> json("""{"messages":[]}""")
                    else -> json("{}")
                }
            }
            start()
        }
        container = AppContainer(context, PlainCipher)
        runBlocking(Dispatchers.Default) {
            container.servers.clearSyntheticServers()
            container.servers.save(profile())
            withTimeout(10_000) { container.connection.filterNotNull().first { it.session.profile == profile() } }
        }
        vm = MainViewModel(container) { _, _ ->
            searches.incrementAndGet()
            answer
        }
        owner.navigationEventDispatcher.addInput(backInput)
        compose.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalOnBackPressedDispatcherOwner provides owner,
                LocalNavigationEventDispatcherOwner provides owner,
            ) {
                HermesApp(vm)
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { vm.chat.value != null }
    }

    private fun openChat(id: String) {
        compose.runOnUiThread { vm.openChat(id) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { vm.chat.value?.state?.value?.storedId == id }
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription(strings.get(R.string.chat_chats)).performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText(strings.get(R.string.sidebar_search)).performTouchInput { click() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { vm.stack.lastOrNull() is Screen.Search }
    }

    private fun search(query: String) {
        compose.onNode(hasSetTextAction()).performTextInput(query)
        // The lookup waits for typing to settle; the main looper of the test has to reach that delay.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        compose.waitUntil(10_000) { vm.search.value.hits.isNotEmpty() }
        compose.waitForIdle()
    }

    private fun clickRow(title: String) {
        compose.onNodeWithText(title).performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { backInput.press() }
        compose.waitForIdle()
    }

    private fun captureFrame(name: String) {
        val directory = System.getenv("HERMES_116_VISUAL_DIR") ?: return
        val file = File(directory, "$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun assertHint() {
        compose.onNodeWithText(strings.get(R.string.search_result_hint)).assertIsDisplayed()
    }

    private fun hasText(text: String) = androidx.compose.ui.test.hasText(text)

    private fun profile(trailingSlash: Boolean = true): ServerProfile {
        val url = server.url("/").toString()
        return ServerProfile(PROFILE_ID, "Home", if (trailingSlash) url else url.trimEnd('/'))
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun hit(id: String) = SearchHit(
        ChatSummary(
            id = id, title = "Chat $id", preview = "", source = "telegram", model = null,
            startedAt = 1.0, lastActive = 2.0, messageCount = 1,
            pinned = false, archived = false, active = false,
        ),
        "snippet of $id",
        null,
    )

    private class BackOwner : LifecycleOwner, OnBackPressedDispatcherOwner, NavigationEventDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        override val navigationEventDispatcher = NavigationEventDispatcher()

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    /** A system back gesture: the dispatcher starts the event and completes it, as the platform does. */
    private class TestBackInput : NavigationEventInput() {
        fun press() {
            dispatchOnBackStarted(NavigationEvent())
            dispatchOnBackCompleted()
        }
    }

    private companion object {
        const val PROFILE_ID = "search-return-compose"
    }
}
