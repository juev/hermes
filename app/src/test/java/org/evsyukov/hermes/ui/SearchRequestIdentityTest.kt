package org.evsyukov.hermes.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.IOException

/** All transitions run in the production SearchOwner; no container, disk, socket or service exists here. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchRequestIdentityTest {
    private val state = SearchStateFixture()
    private val main get() = state.main
    private val calls get() = state.answers.calls
    @get:Rule val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() = state.run { base.evaluate() }
    } }
    private fun connection(endpoint: String) = SearchInput("home", endpoint)
    private fun viewModel(connections: MutableStateFlow<SearchInput?>) = state.owner(connections)
    private fun hit(id: String) = searchHit(id)
    private fun queries() = calls.map { it.query }
    private fun callCount() = calls.size
    private fun advance(millis: Long) { main.scheduler.advanceTimeBy(millis); main.scheduler.runCurrent() }
    private companion object { const val LOCAL_OLD = "https://old.example.test" }

    // --- the eleven logical cases ---

    @Test
    fun `a new query clears the old hits at once and shows its own loading`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        assertThat(queries()).containsExactly("alpha")
        calls[0].succeed(listOf(hit("s1")))

        viewModel.setQuery("beta")

        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isTrue()
        assertThat(viewModel.search.value.error).isNull()
        advance(299)
        assertThat(queries()).containsExactly("alpha")
        advance(1)
        assertThat(queries()).containsExactly("alpha", "beta")
        calls[1].succeed(listOf(hit("s2")))

        assertThat(viewModel.search.value.hits).containsExactly(hit("s2"))
        assertThat(viewModel.search.value.searching).isFalse()
    }

    @Test
    fun `a query reaches the server once, then appears as its own results`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        assertThat(callCount()).isEqualTo(1)
        assertThat(queries()).containsExactly("alpha")
        assertThat(calls[0].connection).isSameInstanceAs(viewModel.search.value.request?.connection)
        calls[0].succeed(emptyList())

        assertThat(viewModel.search.value.query).isEqualTo("alpha")
        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isFalse()
        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.request).isNotNull()
    }

    @Test
    fun `a failure belongs to the query shown, and a later success clears it`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        calls[0].fail(IOException("boom"))

        assertThat(viewModel.search.value.error).isEqualTo("boom")
        assertThat(viewModel.search.value.searching).isFalse()

        viewModel.setQuery("beta")
        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.searching).isTrue()
        advance(300)
        calls[1].succeed(listOf(hit("s2")))

        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.hits).containsExactly(hit("s2"))
    }

    @Test
    fun `an old failure cannot replace the loading or the results of the new query`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        viewModel.setQuery("beta")
        advance(300)
        assertThat(queries()).containsExactly("alpha", "beta")

        calls[0].fail(IOException("alpha failed"))
        runCurrent()

        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.searching).isTrue()
        calls[1].succeed(listOf(hit("s2")))

        assertThat(viewModel.search.value.hits).containsExactly(hit("s2"))
        assertThat(viewModel.search.value.error).isNull()
    }

    @Test
    fun `an old cancellation cannot hide the loading or mark the new query failed`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        viewModel.setQuery("beta")
        advance(300)

        calls[0].cancel()
        runCurrent()

        assertThat(viewModel.search.value.searching).isTrue()
        assertThat(viewModel.search.value.error).isNull()
        calls[1].succeed(listOf(hit("s2")))

        assertThat(viewModel.search.value.hits).containsExactly(hit("s2"))
    }

    @Test
    fun `alpha to beta to alpha rejects the first alpha even when the text is back`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        viewModel.setQuery("beta")
        runCurrent()
        viewModel.setQuery("alpha")
        advance(300)
        assertThat(queries()).containsExactly("alpha", "alpha")

        calls[0].succeed(listOf(hit("first-alpha")))
        runCurrent()

        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isTrue()
        calls[1].succeed(listOf(hit("second-alpha")))

        assertThat(viewModel.search.value.hits).containsExactly(hit("second-alpha"))
    }

    @Test
    fun `clearing the query drops the hits at once without a request`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("s1")))
        val before = callCount()

        viewModel.setQuery("")

        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isFalse()
        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.request).isNull()
        advance(300)
        assertThat(callCount()).isEqualTo(before)
    }

    @Test
    fun `a whitespace query clears like an empty one`() = runTest(main.scheduler) {
        val viewModel = viewModel(MutableStateFlow(connection(LOCAL_OLD)))
        viewModel.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("s1")))
        val before = callCount()

        viewModel.setQuery("   ")

        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isFalse()
        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.request).isNull()
        advance(300)
        assertThat(callCount()).isEqualTo(before)
    }

    /**
     * A completed answer of the old connection is replaced, not merged: the same query is asked again
     * on the actual new connection after the same debounce, and the old row is never actionable.
     */
    @Test
    fun `the same query runs again after the connection is replaced`() = runTest(main.scheduler) {
        val old = connection("http://127.0.0.1:9")
        val new = connection("http://127.0.0.1:9")
        assertThat(new).isNotSameInstanceAs(old)
        assertThat(new.profile).isEqualTo(old.profile)
        assertThat(new.endpoint).isEqualTo(old.endpoint)
        val connections = MutableStateFlow<SearchInput?>(old)
        val viewModel = viewModel(connections)

        viewModel.setQuery("alpha")
        advance(300)
        assertThat(callCount()).isEqualTo(1)
        assertThat(calls[0].connection).isSameInstanceAs(old)
        assertThat(calls[0].query).isEqualTo("alpha")
        calls[0].succeed(listOf(hit("old-server")))

        val oldIdentity = viewModel.search.value.request
        assertThat(oldIdentity).isNotNull()
        assertThat(oldIdentity!!.connection).isSameInstanceAs(old)
        assertThat(viewModel.search.value.hits).containsExactly(hit("old-server"))

        // The replacement is emitted now; the owner's collector task is queued, not applied.
        connections.value = new
        assertThat(viewModel.search.value.request).isSameInstanceAs(oldIdentity)

        assertThat(viewModel.admits(hit("old-server"), oldIdentity)).isFalse()

        val before = main.scheduler.currentTime
        main.scheduler.runCurrent() // drain the controlled owner event boundary

        assertThat(main.scheduler.currentTime).isEqualTo(before)
        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isTrue()
        assertThat(viewModel.search.value.query).isEqualTo("alpha")
        val replacement = viewModel.search.value.request
        assertThat(replacement).isNotNull()
        assertThat(replacement!!.connection).isSameInstanceAs(new)
        assertThat(replacement.generation).isNotEqualTo(oldIdentity.generation)
        assertThat(callCount()).isEqualTo(1)

        advance(299)
        assertThat(callCount()).isEqualTo(1)
        advance(1)
        assertThat(callCount()).isEqualTo(2)
        assertThat(calls[1].connection).isSameInstanceAs(new)
        assertThat(calls[1].query).isEqualTo("alpha")

        calls[1].succeed(listOf(hit("new-server")))
        assertThat(viewModel.search.value.hits).containsExactly(hit("new-server"))
        assertThat(viewModel.search.value.request).isSameInstanceAs(replacement)
        assertThat(viewModel.search.value.searching).isFalse()
    }

    /**
     * An answer that comes back after the old request was replaced does not rewrite the state of the
     * request now shown, even though the query text is the same.
     */
    @Test
    fun `a late answer of the previous connection does not land on the replacement`() = runTest(main.scheduler) {
        val old = connection("http://127.0.0.1:9")
        val new = connection("http://127.0.0.1:10")
        val connections = MutableStateFlow<SearchInput?>(old)
        val viewModel = viewModel(connections)

        viewModel.setQuery("alpha")
        advance(300)
        assertThat(callCount()).isEqualTo(1)

        connections.value = new
        main.scheduler.runCurrent()

        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isTrue()
        assertThat(viewModel.search.value.request?.connection).isSameInstanceAs(new)

        // The old answer lands after the owner moved on: it must be dropped.
        calls[0].succeed(listOf(hit("old-server")))
        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.request?.connection).isSameInstanceAs(new)

        advance(300)
        assertThat(callCount()).isEqualTo(2)
        assertThat(calls[1].connection).isSameInstanceAs(new)
        assertThat(calls[1].query).isEqualTo("alpha")
        calls[1].succeed(listOf(hit("new-server")))
        assertThat(viewModel.search.value.hits).containsExactly(hit("new-server"))
    }

    @Test
    fun `another server cannot keep the hits of the previous one even with the same query and chat id`() =
        runTest(main.scheduler) {
            val old = SearchInput("server-a", "https://a.example.test")
            val new = SearchInput("server-b", "https://b.example.test")
            assertThat(new.profile).isNotEqualTo(old.profile)
            assertThat(new.endpoint).isNotEqualTo(old.endpoint)
            val connections = MutableStateFlow<SearchInput?>(old)
            val viewModel = viewModel(connections)
            viewModel.setQuery("alpha")
            advance(300)
            calls[0].succeed(listOf(hit("s1")))
            val oldIdentity = viewModel.search.value.request
            assertThat(oldIdentity).isNotNull()
            assertThat(oldIdentity!!.connection).isSameInstanceAs(old)

            // The collector of the ViewModel has not seen the new connection yet: the action boundary
            // must still know the result was rendered for the old server.
            connections.value = new
            assertThat(viewModel.admits(hit("s1"), oldIdentity)).isFalse()

            main.scheduler.runCurrent()

            assertThat(viewModel.search.value.request?.connection).isSameInstanceAs(new)
            assertThat(viewModel.search.value.hits).isEmpty()
            assertThat(viewModel.search.value.searching).isTrue()
            assertThat(callCount()).isEqualTo(1)
            advance(300)
            assertThat(callCount()).isEqualTo(2)
            assertThat(calls[1].connection).isSameInstanceAs(new)
            calls[1].succeed(listOf(hit("s1")))

            assertThat(viewModel.admits(hit("s1"), viewModel.search.value.request)).isTrue()
        }

    @Test
    fun `a connection lost and another one found reruns the query and clears the old hits`() = runTest(main.scheduler) {
        val old = connection("http://127.0.0.1:9")
        val new = connection("http://127.0.0.1:10")
        val connections = MutableStateFlow<SearchInput?>(old)
        val viewModel = viewModel(connections)
        viewModel.setQuery("alpha")
        advance(300)
        calls[0].succeed(listOf(hit("s1")))
        val before = main.scheduler.currentTime

        connections.value = null
        main.scheduler.runCurrent()

        assertThat(viewModel.search.value.hits).isEmpty()
        assertThat(viewModel.search.value.searching).isFalse()
        assertThat(viewModel.search.value.error).isNull()
        assertThat(viewModel.search.value.request).isNull()
        assertThat(callCount()).isEqualTo(1)
        assertThat(main.scheduler.currentTime).isEqualTo(before)

        connections.value = new
        main.scheduler.runCurrent()

        assertThat(main.scheduler.currentTime).isEqualTo(before)
        assertThat(callCount()).isEqualTo(1)
        assertThat(viewModel.search.value.searching).isTrue()
        assertThat(viewModel.search.value.request?.connection).isSameInstanceAs(new)
        assertThat(viewModel.search.value.query).isEqualTo("alpha")
        assertThat(viewModel.search.value.hits).isEmpty()
        advance(299)
        assertThat(callCount()).isEqualTo(1)
        advance(1)
        assertThat(callCount()).isEqualTo(2)

        assertThat(calls[1].connection).isSameInstanceAs(new)
        assertThat(calls[1].query).isEqualTo("alpha")
    }

}
