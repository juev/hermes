package org.evsyukov.hermes.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.evsyukov.hermes.ui.chat.DraftKey
import org.evsyukov.hermes.chat.LocalAttachment
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.SearchHit
import org.evsyukov.hermes.data.server.ServerProfile
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A result opens the conversation the person chose, the same one already open or another, and leaves
 * Search. The search itself stays under the journey, so the return has the same query and answer to
 * show; the tests drive the real [MainViewModel] and container, with only the lookup answered by the
 * test. Back and the screen wiring are covered by [org.evsyukov.hermes.ui.search.SearchReturnJourneyTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SearchReturnTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val main = StandardTestDispatcher()
    private val lifecycle = SearchFixtureLifecycle(main.scheduler)
    @get:Rule val lifetime = TestRule { base, _ -> object : Statement() {
        override fun evaluate() = lifecycle.run(setup = {}, body = { base.evaluate() })
    } }
    internal val cleanupMain get() = main
    internal val cleanupContainer get() = container
    internal fun cleanupViewModel(): MainViewModel = viewModel()

    private val lock = Any()
    private val calls = mutableListOf<SearchCall>()

    private lateinit var container: AppContainer
    private lateinit var server: MockWebServer
    private val connectionInputs = java.util.concurrent.CopyOnWriteArrayList<Triple<Connection, ServerProfile, Long?>>()

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    private class SearchCall(val query: String, val continuation: Continuation<List<SearchHit>>) { var answered = false }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        lifecycle.dependency { Dispatchers.resetMain() }
        lifecycle.release {
            synchronized(lock) { calls.filterNot { it.answered }.toList() }.forEach { it.succeed(emptyList()) }
        }
        server = MockWebServer().also { lifecycle.dependency { it.close() } }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/api/status" -> json("""{"auth_required":true}""")
                    request.url.encodedPath.endsWith("/messages") -> json("""{"messages":[]}""")
                    else -> json("{}")
                }
            }
            start()
        }
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        container = AppContainer(context, PlainCipher, connectionConstructionObserver = { connection, profile, incarnation ->
            connectionInputs += Triple(connection, profile, incarnation)
        })
        lifecycle.root(requireNotNull(container.scope.coroutineContext[Job]))
        lifecycle.stop {
            container.connection.value?.socket?.stop()
        }
        lifecycle.dependency {
            runBlocking(Dispatchers.Default) { container.servers.clearSyntheticServers() }
        }
        runBlocking(Dispatchers.Default) {
            container.servers.clearSyntheticServers()
            container.servers.save(profile())
            val intended = container.lifetime.incarnations.value[PROFILE_ID]
            awaitConnection { candidate ->
                candidate?.session?.profile == profile() && connectionInputs.any {
                    it.first === candidate && it.second == profile() && it.third == intended
                }
            }
        }
    }

    @Test
    fun `a result of the current chat leaves Search and shows that chat`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.openChat("s1")
        val current = vm.chat.value
        vm.push(Screen.Search)
        results(vm, hit("s1"), hit("s2"))
        val identity = vm.search.value.request

        vm.openSearchHit(hit("s1"), identity)

        assertThat(vm.stack).isEmpty()
        assertThat(vm.chat.value).isSameInstanceAs(current)
        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("s1")
        assertThat(vm.searchJourney.value?.chatId).isEqualTo("s1")
    }

    @Test
    fun `a result of another chat leaves Search and shows the chosen chat`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.openChat("s2")
        vm.push(Screen.Search)
        results(vm, hit("s1"), hit("s2"))
        val identity = vm.search.value.request

        vm.openSearchHit(hit("s1"), identity)

        assertThat(vm.stack).isEmpty()
        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("s1")
        assertThat(vm.searchJourney.value?.chatId).isEqualTo("s1")
    }

    @Test
    fun `opening a result does not touch the search the journey returns to`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.openChat("s2")
        vm.push(Screen.Search)
        results(vm, hit("s1"), hit("s2"))
        val before = vm.search.value
        val identity = before.request

        vm.openSearchHit(hit("s1"), identity)

        assertThat(vm.search.value).isSameInstanceAs(before)
        assertThat(vm.searchJourney.value?.request).isEqualTo(identity)
        assertThat(queries()).containsExactly("alpha")
    }

    @Test
    fun `a second tap on the same result opens the same chat once`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.openChat("s2")
        vm.push(Screen.Search)
        results(vm, hit("s1"))
        val identity = vm.search.value.request

        vm.openSearchHit(hit("s1"), identity)
        val opened = vm.chat.value
        vm.openSearchHit(hit("s1"), identity)

        assertThat(vm.chat.value).isSameInstanceAs(opened)
        assertThat(vm.stack).isEmpty()
        assertThat(vm.searchJourney.value?.chatId).isEqualTo("s1")
        assertThat(queries()).containsExactly("alpha")
    }

    @Test
    fun `an ordinary new chat ends the journey`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.openChat("s2")
        vm.push(Screen.Search)
        results(vm, hit("s1"))
        val identity = vm.search.value.request
        vm.openSearchHit(hit("s1"), identity)

        vm.newChat()

        assertThat(vm.chat.value?.state?.value?.storedId).isNull()
        assertThat(vm.stack).isEmpty()
        assertThat(vm.searchJourney.value).isNull()
    }

    @Test
    fun `opening another chat ends the journey`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.openChat("s2")
        vm.push(Screen.Search)
        results(vm, hit("s1"))
        val identity = vm.search.value.request
        vm.openSearchHit(hit("s1"), identity)

        vm.openChat("s3")

        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("s3")
        assertThat(vm.stack).isEmpty()
        assertThat(vm.searchJourney.value).isNull()
    }

    @Test
    fun `capacity refusal preserves Search viewport controller and draft then same result succeeds`() = runTest(main.scheduler) {
        val vm = viewModel()
        val initial = requireNotNull(vm.chat.value)
        requireNotNull(vm.draftOf(initial)).text = "new chat words"
        val memory = container.drafts.of(PROFILE_ID)
        val retained = (1..14).map { index ->
            requireNotNull(memory.obtain(DraftKey.Chat("filled-$index"))).also { it.text = "keep $index" }
        }
        vm.openChat("origin")
        val current = requireNotNull(vm.chat.value)
        val draft = requireNotNull(vm.draftOf(current))
        draft.text = "origin words"
        draft.attachments = listOf(LocalAttachment("note.txt", "text/plain", byteArrayOf(1, 2)))
        draft.scrollIndex = 9
        draft.scrollOffset = 23
        vm.showSearch()
        results(vm, hit("target"))
        vm.searchScrolled(12, 37)
        val before = vm.search.value
        val request = before.request

        vm.openSearchHit(hit("target"), request)

        assertThat(vm.stack).containsExactly(Screen.Search)
        assertThat(vm.chat.value).isSameInstanceAs(current)
        assertThat(vm.draftOf(current)).isSameInstanceAs(draft)
        assertThat(draft.text).isEqualTo("origin words")
        assertThat(draft.attachments.single().bytes).isEqualTo(byteArrayOf(1, 2))
        assertThat(draft.scrollIndex).isEqualTo(9)
        assertThat(draft.scrollOffset).isEqualTo(23)
        assertThat(vm.search.value).isSameInstanceAs(before)
        assertThat(vm.searchJourney.value).isNull()
        assertThat(vm.notice.value).isNotNull()
        retained.first().text = ""
        vm.openSearchHit(hit("target"), request)
        assertThat(vm.stack).isEmpty()
        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("target")
        assertThat(vm.searchJourney.value?.index).isEqualTo(12)
        assertThat(vm.searchJourney.value?.offset).isEqualTo(37)
        assertThat(vm.returnToSearch()).isTrue()
        assertThat(vm.searchEntry.value).isEqualTo(SearchEntry(12, 37, false))
        assertThat(queries()).containsExactly("alpha")
        vm.openChat("origin")
        val recreated = requireNotNull(vm.chat.value)
        assertThat(recreated).isNotSameInstanceAs(current)
        assertThat(vm.draftOf(recreated)).isSameInstanceAs(draft)
        assertThat(draft.text).isEqualTo("origin words")
        assertThat(draft.attachments.single().bytes).isEqualTo(byteArrayOf(1, 2))
        assertThat(draft.scrollIndex).isEqualTo(9)
        assertThat(draft.scrollOffset).isEqualTo(23)
    }

    @Test
    fun `refused ordinary navigation preserves an existing journey`() = runTest(main.scheduler) {
        val vm = viewModel()
        requireNotNull(vm.draftOf(requireNotNull(vm.chat.value))).text = "new words"
        val memory = container.drafts.of(PROFILE_ID)
        repeat(14) { requireNotNull(memory.obtain(DraftKey.Chat("filled-$it"))).text = "keep $it" }
        vm.openChat("origin")
        requireNotNull(vm.draftOf(requireNotNull(vm.chat.value))).text = "origin words"
        vm.showSearch()
        results(vm, hit("origin"))
        vm.openSearchHit(hit("origin"), vm.search.value.request)
        val journey = vm.searchJourney.value
        assertThat(journey).isNotNull()
        vm.openChat("no-slot")
        assertThat(vm.searchJourney.value).isSameInstanceAs(journey)
        assertThat(vm.newChat()).isTrue() // retained new-chat slot is already admitted
        assertThat(vm.searchJourney.value).isNull()
    }

    @Test
    fun `new input retires the journey and stale taps while a current result still works`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.showSearch()
        results(vm, hit("old"))
        val old = vm.search.value.request
        vm.openSearchHit(hit("old"), old)
        vm.setQuery("beta")
        assertThat(vm.searchJourney.value).isNull()
        assertThat(vm.returnToSearch()).isFalse()
        assertThat(vm.search.value.hits).isEmpty()
        assertThat(queries()).containsExactly("alpha")
        vm.openSearchHit(hit("old"), old)
        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("old")
        assertThat(vm.searchJourney.value).isNull()
        advance(299)
        assertThat(queries()).containsExactly("alpha")
        advance(1)
        assertThat(queries()).containsExactly("alpha", "beta").inOrder()
        calls.last().succeed(listOf(hit("fresh")))
        vm.showSearch()
        vm.openSearchHit(hit("old"), old)
        assertThat(vm.stack).containsExactly(Screen.Search)
        vm.openSearchHit(hit("fresh"), vm.search.value.request)
        assertThat(vm.stack).isEmpty()
        assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("fresh")
        assertThat(vm.returnToSearch()).isTrue()
        assertThat(vm.search.value.query).isEqualTo("beta")
        assertThat(queries()).containsExactly("alpha", "beta").inOrder()
    }

    @Test
    fun `refused New Chat preserves the journey until a mutable slot is freed`() = runTest(main.scheduler) {
        val admission = kotlinx.coroutines.CompletableDeferred<Unit>()
        lifecycle.release { admission.complete(Unit) }
        val vm = MainViewModel(container, ::search)
        lifecycle.root(requireNotNull(vm.viewModelScope.coroutineContext[Job]))
        lifecycle.stop { vm.chat.value?.close() }
        vm.beforeInitialOwnerAdmission = { _, _ -> admission.await() }
        advance(0)
        vm.openChat("origin")
        val beforeNavigation = requireNotNull(vm.chat.value)
        val draft = requireNotNull(vm.draftOf(beforeNavigation))
        draft.text = "origin words"
        val memory = container.drafts.of(PROFILE_ID)
        val retained = (1..15).map { index ->
            requireNotNull(memory.obtain(DraftKey.Chat("filled-$index"))).also { it.text = "keep $index" }
        }
        vm.showSearch()
        // Let initial admission encounter a full memory before selecting the search result.
        // Its fallback may replace the controller, but must keep the origin's retained draft.
        admission.complete(Unit)
        results(vm, hit("origin"))
        vm.openSearchHit(hit("origin"), vm.search.value.request)
        val current = requireNotNull(vm.chat.value)
        assertThat(current).isNotSameInstanceAs(beforeNavigation)
        assertThat(vm.draftOf(current)).isSameInstanceAs(draft)
        val journey = vm.searchJourney.value
        assertThat(journey).isNotNull()
        // Refusal must preserve the controller at this action, not one from before navigation.
        assertThat(vm.newChat()).isFalse()
        assertThat(vm.searchJourney.value).isSameInstanceAs(journey)
        assertThat(vm.chat.value).isSameInstanceAs(current)
        assertThat(vm.draftOf(current)).isSameInstanceAs(draft)
        assertThat(draft.text).isEqualTo("origin words")
        retained.first().text = ""
        assertThat(vm.newChat()).isTrue()
        assertThat(vm.searchJourney.value).isNull()
        assertThat(vm.chat.value).isNotSameInstanceAs(current)
        assertThat(vm.chat.value?.state?.value?.storedId).isNull()
    }

    /** Shows a fresh Search and answers its one query with [hits]. */
    private fun results(vm: MainViewModel, vararg hits: SearchHit) {
        vm.setQuery("alpha")
        advance(300)
        calls.last().succeed(hits.toList())
    }

    private fun viewModel(): MainViewModel {
        val vm = MainViewModel(container, ::search)
        lifecycle.root(requireNotNull(vm.viewModelScope.coroutineContext[Job]))
        lifecycle.stop { vm.chat.value?.close() }
        // The start of the connection opens the first empty chat before the test picks one.
        advance(0)
        runBlocking {
            withTimeout(10_000) {
                while (vm.chat.value?.let { vm.draftOf(it) != null } != true) {
                    main.scheduler.runCurrent()
                    kotlinx.coroutines.yield()
                }
            }
        }
        return vm
    }

    private suspend fun search(connection: Connection, query: String): List<SearchHit> =
        suspendCoroutine { continuation -> synchronized(lock) { calls += SearchCall(query, continuation) } }

    private fun SearchCall.succeed(hits: List<SearchHit>) {
        answered = true
        continuation.resume(hits)
        main.scheduler.runCurrent()
    }

    private fun queries(): List<String> = synchronized(lock) { calls.map { it.query } }

    private fun advance(millis: Long) {
        main.scheduler.advanceTimeBy(millis)
        main.scheduler.runCurrent()
    }

    private suspend fun awaitConnection(predicate: (Connection?) -> Boolean): Connection? =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { container.connection.filterNotNull().first(predicate) }
        }

    private fun profile(): ServerProfile = ServerProfile(PROFILE_ID, "Home", server.url("/").toString())

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

    private companion object {
        const val PROFILE_ID = "search-return-vm"
    }
}
