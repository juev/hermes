package org.evsyukov.hermes.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Real store → container → MainViewModel search and navigation; only search responses are held. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SearchConnectionWiringTest {
    @Test fun `real store transitions retain exact search identity and reject stale navigation`() {
        val f = WiringFixture()
        f.run {
            val vm = f.vm
            val calls = f.answers.calls
            val a = f.initial
            val onA = requireNotNull(f.container.connection.value)
            assertThat(onA.session.profile).isEqualTo(a)
            vm.setQuery("alpha")
            f.advance(299)
            assertThat(calls).isEmpty()
            f.advance(1)
            assertThat(calls).hasSize(1)
            assertThat(calls[0].connection).isSameInstanceAs(onA)
            assertThat(calls[0].query).isEqualTo("alpha")
            calls[0].succeed(listOf(searchHit("s1")))
            val first = requireNotNull(vm.search.value.request)

            // Same profile and endpoint, changed raw URL spelling: a real store replacement.
            val replacement = a.copy(baseUrl = a.baseUrl.trimEnd('/'))
            val before = f.main.scheduler.currentTime
            val replaced = f.save(replacement)
            assertThat(replaced).isNotSameInstanceAs(onA)
            assertThat(replaced.session.profile).isEqualTo(replacement)
            assertThat(replaced.session.profile.id).isEqualTo(a.id)
            assertThat(replaced.session.profile.url).isEqualTo(a.url)
            vm.push(Screen.Search)
            vm.openSearchHit(searchHit("s1"), first)
            assertThat(vm.chat.value?.state?.value?.storedId).isNull()
            assertThat(vm.stack).contains(Screen.Search)
            f.apply(replaced)
            f.assertReplacement(replaced, before, 1)
            assertThat(vm.search.value.request?.generation).isNotEqualTo(first.generation)
            f.debounce(replaced, 1)
            calls[1].succeed(listOf(searchHit("s1")))
            val sameProfileIdentity = requireNotNull(vm.search.value.request)

            // A genuinely distinct profile and endpoint, retaining the exact same chat id.
            val b = ServerProfile("fixture-b", "Server B", f.serverB.url("/").toString())
            assertThat(b.id).isNotEqualTo(a.id)
            assertThat(b.url).isNotEqualTo(a.url)
            val beforeB = f.main.scheduler.currentTime
            val onB = f.save(b)
            vm.push(Screen.Search)
            vm.openSearchHit(searchHit("s1"), sameProfileIdentity)
            assertThat(vm.chat.value?.state?.value?.storedId).isNull()
            assertThat(vm.stack).contains(Screen.Search)
            f.apply(onB)
            f.assertReplacement(onB, beforeB, 2)
            f.debounce(onB, 2)
            calls[2].succeed(listOf(searchHit("s1")))
            vm.push(Screen.Search)
            vm.openSearchHit(searchHit("s1"), vm.search.value.request)
            assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("s1")
            assertThat(vm.stack).isEmpty()

            val onBIdentity = vm.search.value.request
            val beforeNull = f.main.scheduler.currentTime
            f.external {
                f.container.servers.remove(a.id)
                f.container.servers.remove(b.id)
                f.container.connection.first { it == null }
            }
            vm.push(Screen.Search)
            vm.openSearchHit(searchHit("s1"), onBIdentity)
            assertThat(vm.stack).contains(Screen.Search)
            f.apply(null)
            assertThat(f.container.servers.state.value.servers).isEmpty()
            assertThat(vm.search.value.hits).isEmpty()
            assertThat(vm.search.value.searching).isFalse()
            assertThat(vm.search.value.error).isNull()
            assertThat(vm.search.value.request).isNull()
            assertThat(f.main.scheduler.currentTime).isEqualTo(beforeNull)
            assertThat(calls).hasSize(3)

            val recovery = f.save(a)
            f.apply(recovery)
            f.assertReplacement(recovery, beforeNull, 3)
            f.debounce(recovery, 3)
            calls[3].succeed(listOf(searchHit("recovered")))
            vm.openSearchHit(searchHit("recovered"), vm.search.value.request)
            assertThat(vm.chat.value?.state?.value?.storedId).isEqualTo("recovered")
            assertThat(vm.stack).isEmpty()
        }
        f.assertClosed()
    }

    @Test fun `repeated actual setup and teardown isolate synthetic store profiles`() {
        repeat(2) {
            val f = WiringFixture()
            f.run {
                assertThat(f.container.servers.state.value.servers).containsExactly(f.initial)
                assertThat(f.container.connection.value?.session?.profile).isEqualTo(f.initial)
                // Leave a second synthetic profile for the real teardown to clear.
                f.save(ServerProfile("leftover-$it", "Leftover", f.serverB.url("/").toString()))
            }
            f.assertClosed()
        }
    }

    private class WiringFixture {
        val main = StandardTestDispatcher()
        private val lifecycle = SearchFixtureLifecycle(main.scheduler)
        val answers = SearchAnswers<Connection> { main.scheduler.runCurrent() }
        lateinit var container: AppContainer
        lateinit var serverA: MockWebServer
        lateinit var serverB: MockWebServer
        lateinit var vm: MainViewModel
        lateinit var initial: ServerProfile
        private lateinit var vmRoot: Job
        private lateinit var containerRoot: Job
        private var reset = false
        private val context = ApplicationProvider.getApplicationContext<Application>()

        fun run(body: () -> Unit) = lifecycle.run(setup = {
            Dispatchers.setMain(main)
            lifecycle.dependency {
                check(lifecycle.joined)
                Dispatchers.resetMain()
                reset = true
            }
            lifecycle.release(answers::releaseAll)
            lifecycle.dependency(answers::close)
            try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
            container = AppContainer(context, PlainCipher)
            containerRoot = requireNotNull(container.scope.coroutineContext[Job])
            lifecycle.root(containerRoot)
            // These are only the container-owned producers. Global DataStore/Room ownership is not inferred.
            lifecycle.stop {
                container.connection.value?.socket?.stop()
                clearProfiles()
            }
            clearProfiles()
            assertThat(external { container.servers.load() }.servers).isEmpty()
            serverA = server()
            serverB = server()
            initial = ServerProfile("fixture-a", "Server A", serverA.url("/").toString())
            val exact = save(initial)
            assertThat(exact.session.profile).isEqualTo(initial)
            vm = MainViewModel(container, answers::search)
            vmRoot = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            lifecycle.root(vmRoot)
            lifecycle.stop { vm.chat.value?.close() }
            main.scheduler.runCurrent()
        }, body = body)

        private fun clearProfiles() {
            finishStartupProducers()
            external {
            container.servers.clearSyntheticServers()
            container.servers.state.first { it == ServerState() }
            container.connection.first { it == null }
            Unit
            }
        }

        fun save(profile: ServerProfile): Connection {
            finishStartupProducers()
            return external {
            val previous = container.connection.value
            container.servers.save(profile)
            requireNotNull(container.connection.first { it !== previous && it?.session?.profile == profile })
            }
        }

        /** Finish actual local jobs before deliberately freezing Main during the next input write. */
        private fun finishStartupProducers() {
            if (!::vm.isInitialized) return
            runBlocking {
                withTimeout(10_000) {
                    suspend fun finish(job: Job?) {
                        while (job != null && !job.isCompleted) { main.scheduler.runCurrent(); yield() }
                        job?.join()
                    }
                    main.scheduler.runCurrent()
                    finish(vm.accessProducer)
                    finish(vm.cachedListProducer)
                    finish(vm.listProducer)
                    vm.readProducers.toList().forEach { finish(it) }
                }
            }
        }

        /** Waits only on store/input delivery, with no virtual clock advancement or Main execution. */
        fun <T> external(action: suspend () -> T): T = runBlocking(Dispatchers.Default) {
            withTimeout(10_000) { action() }
        }

        /** An identity application barrier, never a wait for the hits or counts being asserted. */
        fun apply(connection: Connection?) = runBlocking {
            val deadline = System.nanoTime() + 10_000_000_000L
            do {
                main.scheduler.runCurrent()
                if (vm.search.value.request?.connection === connection) break
                check(System.nanoTime() < deadline) { "exact search input was not applied" }
                yield()
            } while (true)
        }

        fun assertReplacement(connection: Connection, before: Long, count: Int) {
            assertThat(main.scheduler.currentTime).isEqualTo(before)
            assertThat(answers.calls).hasSize(count)
            assertThat(vm.search.value.request?.connection).isSameInstanceAs(connection)
            assertThat(vm.search.value.query).isEqualTo("alpha")
            assertThat(vm.search.value.hits).isEmpty()
            assertThat(vm.search.value.searching).isTrue()
            assertThat(vm.search.value.error).isNull()
        }

        fun debounce(connection: Connection, count: Int) {
            advance(299)
            assertThat(answers.calls).hasSize(count)
            advance(1)
            assertThat(answers.calls).hasSize(count + 1)
            assertThat(answers.calls[count].connection).isSameInstanceAs(connection)
            assertThat(answers.calls[count].query).isEqualTo("alpha")
        }

        fun advance(millis: Long) {
            main.scheduler.advanceTimeBy(millis)
            main.scheduler.runCurrent()
        }

        fun assertClosed() {
            assertThat(lifecycle.joined).isTrue()
            assertThat(vmRoot.isCompleted).isTrue()
            assertThat(containerRoot.isCompleted).isTrue()
            assertThat(answers.closed).isTrue()
            assertThat(reset).isTrue()
        }

        private fun server(): MockWebServer {
            val server = MockWebServer()
            // Register immediately; close is dependent on all owned producers actually joining.
            lifecycle.dependency { check(lifecycle.joined); server.close() }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/api/status" -> json("""{"auth_required":true}""")
                    request.url.encodedPath == "/api/system/stats" -> MockResponse.Builder().code(401).body("{}").build()
                    request.url.encodedPath.endsWith("/messages") -> json("""{"messages":[]}""")
                    else -> json("{}")
                }
            }
            server.start()
            return server
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }

    private companion object {
        fun json(body: String): MockResponse = MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json").body(body).build()
    }
}
