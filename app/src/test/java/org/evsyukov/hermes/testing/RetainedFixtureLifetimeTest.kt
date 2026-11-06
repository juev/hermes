package org.evsyukov.hermes.testing

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.evsyukov.hermes.data.server.SessionRetiredWriterTest
import org.evsyukov.hermes.data.server.SignOutTargetTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class RetainedFixtureLifetimeTest {
    private class Subject(val setUp: () -> Unit, val tearDown: () -> Unit, val cleanup: FixtureCleanup,
        val scope: () -> CoroutineScope, val open: () -> Boolean, val reset: (RuntimeException?) -> Unit, val close: (RuntimeException?) -> Unit)
    private fun writer() = SessionRetiredWriterTest().let { f -> Subject(f::setUp, f::tearDown, f.fixtureCleanup, f::fixtureProducerScope, { f.fixtureDatabaseOpen }, { f.resetFailure = it }, { f.closeFailure = it }) }
    private fun target() = SignOutTargetTest().let { f -> Subject(f::setUp, f::tearDown, f.fixtureCleanup, f::fixtureProducerScope, { f.fixtureDatabaseOpen }, { f.resetFailure = it }, { f.closeFailure = it }) }
    private fun held(f: Subject) = runBlocking<Unit> {
        val owner = f.scope().coroutineContext.job
        val release = CompletableDeferred<Unit>()
        var child: Job? = null
        try {
            f.setUp()
            assertThat(f.open()).isTrue()
            child = f.scope().launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { release.await() } }
            f.cleanup.joinMillis = 50
            val error = runCatching { f.tearDown() }.exceptionOrNull()
            assertThat(error).isInstanceOf(AssertionError::class.java)
            assertThat(f.open()).isTrue()
            assertThat(owner.isCompleted).isFalse()
            release.complete(Unit)
            withTimeout(10_000) { child.join() }
            f.cleanup.joinMillis = 10_000; f.tearDown()
            assertThat(owner.isCompleted).isTrue()
            assertThat(f.open()).isFalse()
        } finally {
            release.complete(Unit)
            child?.let { withTimeout(10_000) { it.join() } }
            withTimeout(10_000) { owner.cancelAndJoin() }
            f.cleanup.joinMillis = 10_000; f.tearDown()
        }
    }
    private fun failures(f: Subject) = runBlocking<Unit> {
        val owner = f.scope().coroutineContext.job
        val reset = IllegalStateException("retained reset failure")
        val close = IllegalStateException("retained independent close failure")
        try {
            f.setUp(); f.reset(reset); f.close(close)
            val actual = runCatching { f.tearDown() }.exceptionOrNull()
            assertThat(actual).isSameInstanceAs(reset)
            assertThat(actual!!.suppressed.toList()).containsExactly(close)
            assertThat(owner.isCompleted).isTrue()
            assertThat(f.open()).isFalse()
        } finally {
            f.reset(null); f.close(null)
            withTimeout(10_000) { owner.cancelAndJoin() }
            f.tearDown()
        }
    }
    @Test fun `T2 retained writer refuses closure while actual child is held`() = held(writer())
    @Test fun `T2 signout target refuses closure while actual child is held`() = held(target())
    @Test fun `T2 retained writer joins and closes despite reset failure`() = failures(writer())
    @Test fun `T2 signout target joins and closes despite reset failure`() = failures(target())
}
