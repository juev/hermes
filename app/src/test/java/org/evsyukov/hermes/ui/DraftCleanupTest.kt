package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.evsyukov.hermes.AppContainer
import org.evsyukov.hermes.data.auth.TokenCipher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A teardown that stops at its first failure leaves the servers and scopes of the case behind; the
 * failing step itself must stay the reported error.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DraftCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a failing shared reset still closes the servers`() {
        val case = DraftNavigationTest()
        case.server
        case.otherServer
        case.acquireContainer()
        val failure = IllegalStateException("the shared reset failed")
        case.resetSharedServers = { throw failure }

        val thrown = runCatching { case.tearDown() }.exceptionOrNull()

        try {
            assertThat(case.server.closed).isTrue()
            assertThat(case.otherServer.closed).isTrue()
            assertThat(case.container.scope.isActive).isFalse()
            assertThat(thrown).isSameInstanceAs(failure)
        } finally {
            if (!case.server.closed) case.server.close()
            if (!case.otherServer.closed) case.otherServer.close()
        }
    }

    @Test
    fun `a later cleanup failure is suppressed under the first`() = runBlocking {
        val first = IllegalStateException("the shared reset failed")
        val second = IllegalArgumentException("the close failed")
        val thrown = runCatching {
            runCleanupSteps(
                listOf(
                    { throw first },
                    { throw second },
                ),
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(first)
        assertThat(thrown!!.suppressed.toList()).containsExactly(second)
        Unit
    }

    @Test
    fun `a failing fixture reset still cancels its scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = IllegalStateException("the fixture reset failed")
        val thrown = runCatching {
            runBlocking {
                runCleanupSteps(
                    listOf(
                        { throw failure },
                        { scope.cancel() },
                    ),
                )
            }
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(scope.isActive).isFalse()
    }

    @Test
    fun `actual teardown refuses dependent closure until its producer completes`() {
        val case = DraftNavigationTest()
        case.server
        case.otherServer
        case.acquireContainer()
        case.joinDeadlineMillis = 100
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producer = case.container.scope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                arrived.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "producer was not released" }
            }
        }
        var reset = false
        case.resetSharedServers = { reset = true }
        try {
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
            val failure = runCatching { case.tearDown() }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(producer.isCompleted).isFalse()
            assertThat(case.server.closed).isFalse()
            assertThat(case.otherServer.closed).isFalse()
            assertThat(reset).isFalse()
            release.countDown()
            runBlocking { producer.join() }
            case.tearDown()
            assertThat(case.container.scope.coroutineContext[Job]!!.isCompleted).isTrue()
            assertThat(reset).isTrue()
            assertThat(case.server.closed).isTrue()
            assertThat(case.otherServer.closed).isTrue()
        } finally {
            release.countDown()
            runBlocking { producer.join() }
            case.tearDown()
        }
    }

    @Test
    fun `partial actual fixture setup preserves its failure and completes cleanup`() {
        val case = DraftNavigationTest()
        val primary = IllegalStateException("failed after app acquisition")
        val reset = IllegalArgumentException("reset failed too")
        val thrown = runCatching {
            case.runFixture {
                case.server
                case.otherServer
                case.acquireContainer()
                runBlocking(Dispatchers.IO) { case.container.cacheDatabase.query("SELECT 1", null).close() }
                assertThat(case.container.cacheDatabase.isOpen).isTrue()
                case.resetSharedServers = { throw reset }
                throw primary
            }
        }.exceptionOrNull()
        try {
            assertThat(thrown).isSameInstanceAs(primary)
            assertThat(primary.suppressed.toList()).containsExactly(reset)
            assertThat(case.container.cacheDatabase.isOpen).isFalse()
            assertThat(case.container.scope.coroutineContext[Job]!!.isCompleted).isTrue()
            assertThat(case.server.closed).isTrue()
            assertThat(case.otherServer.closed).isTrue()
        } finally {
            case.resetSharedServers = {}
            case.tearDown()
        }
    }

    @Test
    fun `the actual Main driver completes cancellation of its children before returning`() {
        val case = DraftNavigationTest()
        var child: Job? = null
        var completed = false
        try {
            case.onMain {
                child = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                    try { kotlinx.coroutines.awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            kotlinx.coroutines.yield()
                            completed = true
                        }
                    }
                }
                kotlinx.coroutines.yield()
            }
            assertThat(checkNotNull(child).isCompleted).isTrue()
            assertThat(completed).isTrue()
        } finally {
            case.tearDown()
        }
    }

    @Test
    fun `actual Main driver preserves body failure when its child join also refuses`() {
        val case = DraftNavigationTest()
        case.joinDeadlineMillis = 100
        val sentinel = IllegalArgumentException("body sentinel")
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var child: Job? = null
        case.runFixture {
            var failure: Throwable? = null
            try {
                val thrown = runCatching {
                    case.onMain {
                        child = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            withContext(NonCancellable) { release.await() }
                        }
                        throw sentinel
                    }
                }.exceptionOrNull()
                assertThat(checkNotNull(child).isCompleted).isFalse()
                assertThat(thrown).isSameInstanceAs(sentinel)
                assertThat(sentinel.suppressed.toList()).hasSize(1)
                assertThat(sentinel.suppressed.single()).hasMessageThat().contains("main driver did not complete cancellation")
            } catch (error: Throwable) {
                failure = error
            } finally {
                runBlocking {
                    runCleanupSteps(listOf(
                        { failure?.let { throw it } },
                        { release.complete(Unit) },
                        { child?.let { held -> case.onMain { held.join() }; assertThat(held.isCompleted).isTrue() } },
                    ))
                }
            }
        }
    }

    @Test
    fun `actual Main driver keeps a body only failure primary`() {
        val case = DraftNavigationTest()
        val sentinel = IllegalArgumentException("body only sentinel")
        case.runFixture {
            val thrown = runCatching { case.onMain { throw sentinel } }.exceptionOrNull()
            assertThat(thrown).isSameInstanceAs(sentinel)
            assertThat(sentinel.suppressed).isEmpty()
        }
    }

    @Test
    fun `actual Main driver still reports cleanup only join refusal`() {
        val case = DraftNavigationTest()
        case.joinDeadlineMillis = 100
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var child: Job? = null
        case.runFixture {
            var failure: Throwable? = null
            try {
                val thrown = runCatching {
                    case.onMain {
                        child = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            withContext(NonCancellable) { release.await() }
                        }
                    }
                }.exceptionOrNull()
                assertThat(checkNotNull(child).isCompleted).isFalse()
                assertThat(thrown).hasMessageThat().contains("main driver did not complete cancellation")
            } catch (error: Throwable) {
                failure = error
            } finally {
                runBlocking {
                    runCleanupSteps(listOf(
                        { failure?.let { throw it } },
                        { release.complete(Unit) },
                        { child?.let { held -> case.onMain { held.join() }; assertThat(held.isCompleted).isTrue() } },
                    ))
                }
            }
        }
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
