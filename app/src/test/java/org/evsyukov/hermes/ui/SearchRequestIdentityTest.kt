package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.yield
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A search result belongs to one server and one attempt. A changed query, a replaced connection or a
 * cleared field ends the request before its answer lands: no stale hit stays actionable, no late
 * answer or failure rewrites the state of the request now shown, and an old row cannot open its chat
 * on another server. The tests drive the real [MainViewModel] and the real container; only the
 * search call itself waits for the test to answer it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SearchRequestIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val main = StandardTestDispatcher()
    private val lock = Any()
    private val calls = mutableListOf<SearchCall>()

    private lateinit var container: AppContainer
    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    /** One search the ViewModel made; the test answers it, early or late, as a server might. */
    private class SearchCall(val connection: Connection, val query: String, val continuation: Continuation<List<SearchHit>>)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        serverA = server()
        serverB = server()
        // The container schedules the activity check through WorkManager at start.
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        container = AppContainer(context, PlainCipher)
        runBlocking {
            container.servers.save(profile(serverA))
            awaitConnection { it != null }
        }
    }

    @After
    fun tearDown() {
        container.scope.cancel()
        serverA.close()
        serverB.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new query clears the old hits at once and shows its own loading`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        assertThat(queries()).containsExactly("alpha")
        calls[0].succeed(listOf(hit("s1")))

        vm.setQuery("beta")

        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isTrue()
        assertThat(vm.search.value.error).isNull()
        advance(299)
        assertThat(queries()).containsExactly("alpha")
        advance(1)
        assertThat(queries()).containsExactly("alpha", "beta")
        calls[1].succeed(listOf(hit("s2")))

        assertThat(vm.search.value.hits).containsExactly(hit("s2"))
        assertThat(vm.search.value.searching).isFalse()
    }

    @Test
    fun `a query reaches the server once, then appears as its own results`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        calls[0].succeed(emptyList())

        assertThat(vm.search.value.query).isEqualTo("alpha")
        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isFalse()
        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.request).isNotNull()
    }

    @Test
    fun `a failure belongs to the query shown, and a later success clears it`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        calls[0].fail(IOException("boom"))

        assertThat(vm.search.value.error).isEqualTo("boom")
        assertThat(vm.search.value.searching).isFalse()

        vm.setQuery("beta")
        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.searching).isTrue()
        advance(300)
        calls[1].succeed(listOf(hit("s2")))

        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.hits).containsExactly(hit("s2"))
    }

    @Test
    fun `an old failure cannot replace the loading or the results of the new query`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        vm.setQuery("beta")
        advance(300)
        assertThat(queries()).containsExactly("alpha", "beta")

        calls[0].fail(IOException("alpha failed"))
        runCurrent()

        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.searching).isTrue()
        calls[1].succeed(listOf(hit("s2")))

        assertThat(vm.search.value.hits).containsExactly(hit("s2"))
        assertThat(vm.search.value.error).isNull()
    }

    @Test
    fun `an old cancellation cannot hide the loading or mark the new query failed`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        vm.setQuery("beta")
        advance(300)

        calls[0].cancel()
        runCurrent()

        assertThat(vm.search.value.searching).isTrue()
        assertThat(vm.search.value.error).isNull()
        calls[1].succeed(listOf(hit("s2")))

        assertThat(vm.search.value.hits).containsExactly(hit("s2"))
    }

    @Test
    fun `alpha to beta to alpha rejects the first alpha even when the text is back`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        vm.setQuery("beta")
        runCurrent()
        vm.setQuery("alpha")
        advance(300)
        assertThat(queries()).containsExactly("alpha", "alpha")

        calls[0].succeed(listOf(hit("first-alpha")))
        runCurrent()

        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isTrue()
        calls[1].succeed(listOf(hit("second-alpha")))

        assertThat(vm.search.value.hits).containsExactly(hit("second-alpha"))
    }

    @Test
    fun `clearing the query drops the hits at once without a request`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("s1")))
        val before = callCount()

        vm.setQuery("")

        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isFalse()
        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.request).isNull()
        advance(300)
        assertThat(callCount()).isEqualTo(before)
    }

    @Test
    fun `a whitespace query clears like an empty one`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("s1")))
        val before = callCount()

        vm.setQuery("   ")

        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isFalse()
        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.request).isNull()
        advance(300)
        assertThat(callCount()).isEqualTo(before)
    }

    @Test
    fun `the same query runs again after the connection is replaced`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("old-server")))

        val replaced = replaceConnectionSameProfile()

        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isTrue()
        advance(300)
        assertThat(queries()).containsExactly("alpha", "alpha")
        assertThat(calls[1].connection).isSameInstanceAs(replaced)
        calls[1].succeed(listOf(hit("new-server")))

        assertThat(vm.search.value.hits).containsExactly(hit("new-server"))
    }

    @Test
    fun `another server cannot keep the hits of the previous one even with the same query and chat id`() =
        runTest(main.scheduler) {
            val vm = viewModel()
            vm.setQuery("alpha")
            advance(300)
            calls[0].succeed(listOf(hit("s1")))
            val oldIdentity = vm.search.value.request
            assertThat(oldIdentity).isNotNull()

            val previous = container.connection.value
            container.servers.save(profile(serverB, trailingSlash = false))
            val onB = awaitConnection { it !== previous && it != null }!!

            // The collector of the ViewModel has not seen the new connection yet: the action boundary
            // must still know the result was rendered for the old server.
            vm.openSearchHit(hit("s1"), oldIdentity)

            assertThat(vm.chat.value?.state?.value?.storedId).isNull()

            main.scheduler.advanceUntilIdle()

            assertThat(vm.search.value.request?.connection).isSameInstanceAs(onB)
            assertThat(vm.search.value.hits).isEmpty()
            assertThat(vm.search.value.searching).isTrue()
            assertThat(calls[1].connection).isSameInstanceAs(onB)
            calls[1].succeed(listOf(hit("s1")))

            vm.openSearchHit(hit("s1"), vm.search.value.request)

            assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("s1")
        }

    @Test
    fun `a connection lost and another one found reruns the query and clears the old hits`() = runTest(main.scheduler) {
        val vm = viewModel()
        vm.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("s1")))

        val previous = container.connection.value
        container.servers.remove(PROFILE_ID)
        withContext(Dispatchers.Default) { withTimeout(10_000) { container.connection.first { it == null } } }
        runCurrent()

        assertThat(vm.search.value.hits).isEmpty()
        assertThat(vm.search.value.searching).isFalse()
        assertThat(vm.search.value.error).isNull()
        assertThat(vm.search.value.request).isNull()

        val before = main.scheduler.currentTime
        assertThat(callCount()).isEqualTo(1)
        val save = async(Dispatchers.Default) { container.servers.save(profile(serverB)) }
        val onB = awaitSearchRequest(vm, previous, save)
        save.await()
        runCurrent()

        assertThat(main.scheduler.currentTime).isEqualTo(before)
        assertThat(callCount()).isEqualTo(1)
        assertThat(vm.search.value.searching).isTrue()
        assertThat(vm.search.value.request?.connection).isSameInstanceAs(onB)
        assertThat(vm.search.value.query).isEqualTo("alpha")
        assertThat(vm.search.value.hits).isEmpty()
        advance(299)
        assertThat(callCount()).isEqualTo(1)
        advance(1)
        assertThat(callCount()).isEqualTo(2)

        assertThat(calls[1].connection).isSameInstanceAs(onB)
        assertThat(calls[1].query).isEqualTo("alpha")
    }

    private fun viewModel() = MainViewModel(container, ::search)

    private suspend fun search(connection: Connection, query: String): List<SearchHit> =
        suspendCoroutine { continuation ->
            synchronized(lock) { calls += SearchCall(connection, query, continuation) }
        }

    private fun SearchCall.succeed(hits: List<SearchHit>) {
        continuation.resume(hits)
        main.scheduler.runCurrent()
    }

    private fun SearchCall.fail(error: Exception) {
        continuation.resumeWithException(error)
        main.scheduler.runCurrent()
    }

    private fun SearchCall.cancel() {
        continuation.resumeWithException(CancellationException("late cancellation"))
        main.scheduler.runCurrent()
    }

    private fun queries(): List<String> = synchronized(lock) { calls.map { it.query } }

    private fun callCount(): Int = synchronized(lock) { calls.size }

    private fun advance(millis: Long) {
        main.scheduler.advanceTimeBy(millis)
        main.scheduler.runCurrent()
    }

    /** The same server and profile id, rebuilt as a new [Connection]: the address gains a slash. */
    private suspend fun replaceConnectionSameProfile(): Connection {
        val previous = container.connection.value
        container.servers.save(profile(serverA, trailingSlash = false))
        return awaitConnection { it !== previous && it != null }!!.also { main.scheduler.runCurrent() }
    }

    private suspend fun awaitConnection(predicate: (Connection?) -> Boolean): Connection? =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { container.connection.filterNotNull().first(predicate) }
        }

    /**
     * Waits until the real save has completed and the ViewModel has applied the replacement
     * connection, without advancing virtual time. The test body stays runnable at the current time
     * while the save runs on another dispatcher and the collector delivers the connection, so
     * runTest cannot jump ahead to the search debounce.
     */
    private suspend fun awaitSearchRequest(vm: MainViewModel, previous: Connection?, save: Deferred<Unit>): Connection {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            val connection = container.connection.value
            if (save.isCompleted && connection != null && connection !== previous &&
                vm.search.value.request?.connection === connection
            ) {
                return connection
            }
            check(System.nanoTime() < deadline) { "the replacement connection was not applied to the search" }
            yield()
        }
    }

    private fun profile(server: MockWebServer, trailingSlash: Boolean = true): ServerProfile {
        val url = server.url("/").toString()
        return ServerProfile(PROFILE_ID, "Home", if (trailingSlash) url else url.trimEnd('/'))
    }

    private fun server(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/api/status" -> json("""{"auth_required":true}""")
                request.url.encodedPath.endsWith("/messages") -> json("""{"messages":[]}""")
                else -> json("{}")
            }
        }
        start()
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

    private companion object {
        const val PROFILE_ID = "home"
    }
}
