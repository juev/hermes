package org.evsyukov.hermes.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchFixtureLifecycleTest {
    private fun held(f: SearchStateFixture) {
        f.owner(MutableStateFlow(SearchInput("home", "https://fixture.example.test"))).setQuery("alpha")
        f.main.scheduler.advanceTimeBy(300)
        f.main.scheduler.runCurrent()
        assertThat(f.answers.calls).hasSize(1)
    }

    @Test fun `actual fixture releases held child and joins before closing search IO`() {
        val f = SearchStateFixture()
        f.run {
            held(f)
            f.root.cancel()
            f.main.scheduler.runCurrent()
            assertThat(f.root.isCompleted).isFalse()
            assertThat(f.answers.closed).isFalse()
        }
        assertThat(f.lifecycle.joined).isTrue()
        assertThat(f.root.isCompleted).isTrue()
        assertThat(f.answers.calls.single().answered).isTrue()
        assertThat(f.answers.closed).isTrue()
        assertThat(f.independentClosed).isTrue()
    }

    @Test fun `actual failing setup cleans acquisitions and preserves primary and suppressed failures`() {
        val f = SearchStateFixture()
        val setupFailure = IllegalStateException("setup failed after acquisition")
        val releaseFailure = IllegalArgumentException("release failed")
        val independentFailure = IllegalStateException("independent cleanup failed")
        f.answers.releaseFailure = releaseFailure
        val thrown = capture {
            f.run(afterAcquire = {
                f.lifecycle.independent { throw independentFailure }
                throw setupFailure
            }) { error("body must not run") }
        }
        assertThat(thrown).isSameInstanceAs(setupFailure)
        assertThat(thrown.suppressed.toList()).containsExactly(releaseFailure, independentFailure).inOrder()
        assertThat(f.lifecycle.joined).isTrue()
        assertThat(f.root.isCompleted).isTrue()
        assertThat(f.answers.closed).isTrue()
        assertThat(f.independentClosed).isTrue()
    }

    @Test fun `actual release failure propagates while independent cleanup still runs`() {
        val f = SearchStateFixture()
        val failure = IllegalStateException("release failed after releasing held answer")
        f.answers.releaseFailure = failure
        val thrown = capture { f.run { held(f) } }
        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(f.root.isCompleted).isTrue()
        assertThat(f.lifecycle.joined).isTrue()
        assertThat(f.answers.closed).isTrue()
        assertThat(f.independentClosed).isTrue()
    }

    @Test fun `actual fixture refuses dependency closure when a producer cannot join`() {
        // Short deadline deliberately tests the failure path, rather than extending a production wait.
        val f = SearchStateFixture(joinDeadlineMillis = 50)
        f.answers.holdOnRelease = true
        try {
            val thrown = capture { f.run { held(f) } }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            assertThat(f.root.isCompleted).isFalse()
            assertThat(f.lifecycle.joined).isFalse()
            assertThat(f.answers.closed).isFalse()
            assertThat(f.independentClosed).isTrue()
        } finally {
            // The deliberately refused fixture is repaired only for this proof's final resource release.
            f.answers.holdOnRelease = false
            SearchFixtureLifecycle(f.main.scheduler).apply {
                root(f.root)
                release(f.answers::releaseAll)
                dependency(f.answers::close)
            }.run(setup = {}, body = {})
        }
        assertThat(f.root.isCompleted).isTrue()
        assertThat(f.answers.closed).isTrue()
    }

    private fun capture(block: () -> Unit): Throwable {
        try { block() } catch (t: Throwable) { return t }
        error("fixture failure did not propagate")
    }
}
