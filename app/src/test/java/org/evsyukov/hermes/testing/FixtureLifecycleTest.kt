package org.evsyukov.hermes.testing

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.chat.ConnectionChatTest
import org.evsyukov.hermes.chat.HiddenChatTest
import org.evsyukov.hermes.ui.LocalActionLifetimeTest
import org.evsyukov.hermes.ui.LocalActionOwnerTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Calls the four real fixtures' setUp/tearDown, including their real Room/server closures. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class FixtureLifecycleTest {
    private class Subject(
        val setUp: () -> Unit,
        val tearDown: () -> Unit,
        val cleanup: FixtureCleanup,
        val scope: () -> CoroutineScope,
        val open: () -> Boolean,
        val closed: () -> Boolean,
        val failSetup: (RuntimeException?) -> Unit,
        val failReset: (RuntimeException?) -> Unit,
        val failClose: (RuntimeException?) -> Unit,
        val seedReset: () -> Unit,
        val failReturned: (Exception?) -> Unit,
        val resetResults: () -> List<org.evsyukov.hermes.data.server.NativeResult>,
        val removeResetTarget: () -> Unit,
        val failRead: (Exception?) -> Unit,
    )

    private fun lifetime() = LocalActionLifetimeTest().let { f ->
        Subject(f::setUp, f::tearDown, f.fixtureCleanup, f::fixtureProducerScope,
            { f.fixtureDatabaseOpen }, { f.fixtureDependenciesClosed }, { f.setupFailure = it }, { f.resetFailure = it }, { f.closeFailure = it }, f::fixtureSeedReset, { f.resetWriteFailure = it }, { f.resetResults }, f::fixtureRemoveResetTarget, { f.resetReadFailure = it })
    }
    private fun owner() = LocalActionOwnerTest().let { f ->
        Subject(f::setUp, f::tearDown, f.fixtureCleanup, f::fixtureProducerScope,
            { f.fixtureDatabaseOpen }, { f.fixtureDependenciesClosed }, { f.setupFailure = it }, { f.resetFailure = it }, { f.closeFailure = it }, f::fixtureSeedReset, { f.resetWriteFailure = it }, { f.resetResults }, f::fixtureRemoveResetTarget, { f.resetReadFailure = it })
    }
    private fun connection() = ConnectionChatTest().let { f ->
        Subject(f::setUp, f::tearDown, f.fixtureCleanup, f::fixtureProducerScope,
            { f.fixtureDatabaseOpen }, { f.fixtureDependenciesClosed }, { f.setupFailure = it }, { f.resetFailure = it }, { f.closeFailure = it }, f::fixtureSeedReset, { f.resetWriteFailure = it }, { f.resetResults }, f::fixtureRemoveResetTarget, { f.resetReadFailure = it })
    }
    private fun hidden() = HiddenChatTest().let { f ->
        Subject(f::setUp, f::tearDown, f.fixtureCleanup, f::fixtureProducerScope,
            { f.fixtureDatabaseOpen }, { f.fixtureDependenciesClosed }, { f.setupFailure = it }, { f.resetFailure = it }, { f.closeFailure = it }, f::fixtureSeedReset, { f.resetWriteFailure = it }, { f.resetResults }, f::fixtureRemoveResetTarget, { f.resetReadFailure = it })
    }

    private fun heldChild(f: Subject) = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var child: kotlinx.coroutines.Job? = null
        try {
            f.setUp()
            val held = f.scope().launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { release.await() }
            }
            child = held
            assertThat(held.isCompleted).isFalse()
            f.cleanup.joinMillis = 50
            val refused = runCatching { f.tearDown() }.exceptionOrNull()
            assertThat(refused).isInstanceOf(AssertionError::class.java)
            assertThat(refused).hasMessageThat().contains("dependencies remain open")
            assertThat(held.isCompleted).isFalse()
            assertThat(f.open()).isTrue()
            assertThat(f.closed()).isFalse()
            release.complete(Unit)
            withTimeout(10_000) { held.join() }
            assertThat(held.isCompleted).isTrue()
            f.cleanup.joinMillis = 10_000
            f.tearDown()
            assertThat(f.open()).isFalse()
            assertThat(f.closed()).isTrue()
        } finally {
            release.complete(Unit)
            child?.let { withTimeout(10_000) { it.join() } }
            f.cleanup.joinMillis = 10_000
            f.tearDown()
        }
    }

    private fun independentFailures(f: Subject) {
        val reset = IllegalStateException("synthetic reset failure")
        val close = IllegalStateException("synthetic post-close failure")
        try {
            f.setUp()
            f.failReset(reset)
            f.failClose(close)
            val failure = runCatching { f.tearDown() }.exceptionOrNull()
            assertThat(failure).isSameInstanceAs(reset)
            assertThat(failure!!.suppressed.toList()).containsExactly(close)
            assertThat(f.open()).isFalse()
            assertThat(f.closed()).isTrue()
        } finally {
            f.failReset(null)
            f.failClose(null)
            f.tearDown()
        }
    }

    private fun partialSetup(f: Subject) {
        val failure = IllegalStateException("synthetic partial setup failure")
        val cleanup = IllegalStateException("synthetic setup cleanup failure")
        try {
            f.failSetup(failure)
            f.failClose(cleanup)
            val actual = runCatching { f.setUp() }.exceptionOrNull()
            assertThat(actual).isSameInstanceAs(failure)
            assertThat(actual!!.suppressed.toList()).containsExactly(cleanup)
            assertThat(f.open()).isFalse()
            assertThat(f.closed()).isTrue()
        } finally {
            f.failSetup(null)
            f.failClose(null)
            f.tearDown()
        }
    }

    private class ReturnedResetFailure(val marker: Any) : Exception("actual reset cipher write failure")

    private fun returnedFailure(f: Subject) {
        val reset = ReturnedResetFailure(Any())
        val close = IllegalStateException("independent close after returned failure")
        try {
            f.setUp(); f.seedReset(); f.failReturned(reset); f.failClose(close)
            val failure = runCatching { f.tearDown() }.exceptionOrNull()
            assertThat(f.resetResults().any { it is org.evsyukov.hermes.data.server.NativeResult.Failed && it.error === reset }).isTrue()
            assertThat(failure).isSameInstanceAs(reset)
            assertThat(failure!!.suppressed.toList()).contains(close)
            assertThat(f.scope().coroutineContext[kotlinx.coroutines.Job]!!.isCompleted).isTrue()
            assertThat(f.closed()).isTrue()
        } finally {
            f.failReturned(null); f.failClose(null); f.tearDown()
        }
    }
    private fun absentReset(f: Subject, unreadable: Boolean) {
        try {
            f.setUp(); f.seedReset(); f.removeResetTarget()
            if (unreadable) f.failRead(ReturnedResetFailure(Any()))
            val failure = runCatching { f.tearDown() }.exceptionOrNull()
            assertThat(f.resetResults()).isNotEmpty()
            assertThat(f.resetResults().all { it === org.evsyukov.hermes.data.server.NativeResult.Rejected }).isTrue()
            if (unreadable) assertThat(failure).hasMessageThat().contains("unreadable durable state") else assertThat(failure).isNull()
            assertThat(f.scope().coroutineContext[kotlinx.coroutines.Job]!!.isCompleted).isTrue()
            assertThat(f.closed()).isTrue()
        } finally { f.failRead(null); f.tearDown() }
    }
    @Test fun `T3 lifetime accepts only known absent idempotent reset`() = absentReset(lifetime(), false)
    @Test fun `T3 owner accepts only known absent idempotent reset`() = absentReset(owner(), false)
    @Test fun `T3 connection accepts only known absent idempotent reset`() = absentReset(connection(), false)
    @Test fun `T3 hidden accepts only known absent idempotent reset`() = absentReset(hidden(), false)
    @Test fun `T3 lifetime rejects unreadable idempotent reset`() = absentReset(lifetime(), true)
    @Test fun `T3 owner rejects unreadable idempotent reset`() = absentReset(owner(), true)
    @Test fun `T3 connection rejects unreadable idempotent reset`() = absentReset(connection(), true)
    @Test fun `T3 hidden rejects unreadable idempotent reset`() = absentReset(hidden(), true)

    @Test fun `T3 lifetime propagates actual remove returned failure`() = returnedFailure(lifetime())
    @Test fun `T3 owner propagates actual remove returned failure`() = returnedFailure(owner())
    @Test fun `T3 connection propagates actual remove returned failure`() = returnedFailure(connection())
    @Test fun `T3 hidden propagates actual remove returned failure`() = returnedFailure(hidden())

    @Test fun `J lifetime refuses closure until actual held child completes`() = heldChild(lifetime())
    @Test fun `J owner refuses closure until actual held child completes`() = heldChild(owner())
    @Test fun `J connection refuses closure until actual held child completes`() = heldChild(connection())
    @Test fun `J hidden refuses closure until actual held child completes`() = heldChild(hidden())
    @Test fun `J lifetime propagates independent cleanup failures`() = independentFailures(lifetime())
    @Test fun `J owner propagates independent cleanup failures`() = independentFailures(owner())
    @Test fun `J connection propagates independent cleanup failures`() = independentFailures(connection())
    @Test fun `J hidden propagates independent cleanup failures`() = independentFailures(hidden())
    @Test fun `J lifetime partial setup preserves primary and cleanup failures`() = partialSetup(lifetime())
    @Test fun `J owner partial setup preserves primary and cleanup failures`() = partialSetup(owner())
    @Test fun `J connection partial setup preserves primary and cleanup failures`() = partialSetup(connection())
    @Test fun `J hidden partial setup preserves primary and cleanup failures`() = partialSetup(hidden())
}
