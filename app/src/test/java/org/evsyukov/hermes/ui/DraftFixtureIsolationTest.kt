package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.evsyukov.hermes.chat.joinOwnedJobs
import kotlinx.coroutines.runBlocking
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The shared synthetic DataStore outlives one case: a reset must leave no fallback profile. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DraftFixtureIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the isolation reset leaves no profile for the next case`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ServerStore(context, PlainCipher, scope)
        try {
            store.save(ServerProfile("fixture-foreign", "Foreign", "http://127.0.0.1:1/"))
            // The same reset the draft cases run before they seed their own server.
            store.clearSyntheticServers()
            val next = store.load()
            assertThat(next.servers).isEmpty()

            store.save(ServerProfile("fixture-next", "Next", "http://127.0.0.1:2/"))
            val after = store.load()
            assertThat(after.active?.id).isEqualTo("fixture-next")
            assertThat(after.servers.map { it.id }).containsExactly("fixture-next")
        } finally {
            runCleanupSteps(
                listOf(
                    { store.clearSyntheticServers() },
                    { check(scope.joinOwnedJobs()) { "isolation fixture jobs did not finish" } },
                ),
            )
        }
        Unit
    }

    @Test
    fun `repeated actual fixture boundaries leave no foreign profile or unfinished root`() {
        repeat(2) { index ->
            val case = DraftNavigationTest()
            case.runFixture {
                val profile = ServerProfile("real-fixture-$index", "Fixture", case.server.http.url("/").toString())
                case.start(profile)
                val saved = runBlocking { case.container.servers.load() }
                assertThat(saved.servers).containsExactly(profile)
                assertThat(case.container.connection.value?.session?.profile).isEqualTo(profile)
            }
            assertThat(case.container.scope.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted).isTrue()
            assertThat(runBlocking { case.container.servers.load() }.servers).isEmpty()
            assertThat(case.server.closed).isTrue()
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
