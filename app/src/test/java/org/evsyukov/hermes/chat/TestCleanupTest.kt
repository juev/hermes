package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The teardown used by the chat fixtures: it joins the jobs of their own scope and closes the
 * database only after they are over. The scope handed in is the one whose jobs are joined, not the
 * coroutine the helper runs on; a job that blocks past the timeout leaves the database open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TestCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `joining completes an owned job and closes the database after it`() {
        val db = LiveServer.database(context)
        // Room opens lazily: touch it so `isOpen` reports whether the cleanup closed it.
        db.query("SELECT 1", null).close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val job = scope.launch {
            try {
                entered.countDown()
                awaitCancellation()
            } finally {
                finished.countDown()
            }
        }
        var primary: Throwable? = null
        try {
            // A DEFAULT launch may be cancelled before its body (and its finally) ever runs.
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            scope.closeWithOwnedJobs(db) {}
            assertThat(job.isCompleted).isTrue()
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(db.isOpen).isFalse()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // Also cancel and join if readiness or an assertion fails; never close under a job.
            try {
                scope.closeWithOwnedJobs(db) {}
            } catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                failure.addSuppressed(cleanup)
            }
        }
    }

    @Test
    fun `a default child cancelled before dispatch completes without entering its body`() = runBlocking {
        val db = LiveServer.database(context)
        db.query("SELECT 1", null).close()
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val job = scope.launch {
            try {
                entered.countDown()
                awaitCancellation()
            } finally {
                finished.countDown()
            }
        }
        try {
            assertThat(entered.count).isEqualTo(1L)
            // Run the real blocking cleanup off this thread so we can release the queued dispatch
            // after cancellation. No child code is dispatched before that boundary.
            val cleanup = async(Dispatchers.IO) { scope.closeWithOwnedJobs(db) {} }
            try {
                withTimeout(5_000) {
                    while (!job.isCancelled) delay(1)
                }
                dispatcher.scheduler.runCurrent()
                cleanup.await()
                assertThat(job.isCompleted).isTrue()
                assertThat(db.isOpen).isFalse()
                assertThat(entered.count).isEqualTo(1L)
                assertThat(finished.await(1, TimeUnit.SECONDS)).isFalse()
            } finally {
                scope.cancel()
                dispatcher.scheduler.runCurrent()
            }
        } finally {
            scope.cancel()
            dispatcher.scheduler.runCurrent()
            job.join()
            if (db.isOpen) db.close()
        }
    }

    @Test
    fun `unfinished owned jobs leave the database open and fail the cleanup, until they finish`() {
        val db = LiveServer.database(context)
        // Room opens lazily: touch it so `isOpen` reports whether the cleanup closed it.
        db.query("SELECT 1", null).close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val held = CountDownLatch(1)
        val job = scope.launch(Dispatchers.IO) {
            started.countDown()
            // A blocking wait does not take part in cancellation, as a DAO call does not.
            held.await()
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            val failure = runCatching {
                scope.closeWithOwnedJobs(db, timeoutMillis = 100) {}
            }.exceptionOrNull()
            assertWithMessage("cleanup must report the unfinished jobs")
                .that(failure).hasMessageThat().contains("database is left open")
            assertThat(db.isOpen).isTrue()

            held.countDown()
            runBlocking { job.join() }
            // With the held job over, the same cleanup closes the database.
            scope.closeWithOwnedJobs(db) {}
            assertThat(db.isOpen).isFalse()
        } finally {
            held.countDown()
            runBlocking { job.join() }
            if (db.isOpen) db.close()
        }
    }

    @Test
    fun `a scope without a job fails closed and leaves the database open`() {
        val db = LiveServer.database(context)
        db.query("SELECT 1", null).close()
        // `CoroutineScope(Dispatchers.IO)` adds a Job of its own, so this scope is built from the
        // interface: it has none, its launches are standalone, and nobody can wait for them.
        val scope = object : CoroutineScope {
            override val coroutineContext: CoroutineContext = Dispatchers.IO
        }
        assertWithMessage("the test needs a real scope with no job")
            .that(scope.coroutineContext[Job]).isNull()
        val started = CountDownLatch(1)
        val held = CountDownLatch(1)
        val job = scope.launch(Dispatchers.IO) {
            started.countDown()
            held.await()
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(scope.joinOwnedJobs(timeoutMillis = 100)).isFalse()
            val failure = runCatching {
                scope.closeWithOwnedJobs(db, timeoutMillis = 100) {}
            }.exceptionOrNull()
            assertWithMessage("cleanup must refuse to certify a scope it cannot join")
                .that(failure).hasMessageThat().contains("database is left open")
            assertThat(db.isOpen).isTrue()
        } finally {
            held.countDown()
            runBlocking { job.join() }
            if (db.isOpen) db.close()
        }
    }

    @Test
    fun `a failed join still runs the independent closure and keeps its error first`() {
        val db = LiveServer.database(context)
        db.query("SELECT 1", null).close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val held = CountDownLatch(1)
        val job = scope.launch(Dispatchers.IO) {
            started.countDown()
            held.await()
        }
        val closer = IllegalStateException("server close failed")
        var closed = false
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            val failure = runCatching {
                scope.closeWithOwnedJobs(db, timeoutMillis = 100) {
                    closed = true
                    throw closer
                }
            }.exceptionOrNull()
            assertWithMessage("the join failure, not the closer, is the error the cleanup reports")
                .that(failure).hasMessageThat().contains("database is left open")
            assertThat(closed).isTrue()
            assertWithMessage("the closer failure is kept as a suppressed error")
                .that(failure!!.suppressed.toList()).contains(closer)
            assertThat(db.isOpen).isTrue()
        } finally {
            held.countDown()
            runBlocking { job.join() }
            if (db.isOpen) db.close()
        }
    }

    @Test
    fun `a throwing independent closure does not stop the database close after the join`() {
        val db = LiveServer.database(context)
        db.query("SELECT 1", null).close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val closer = IllegalStateException("server close failed")
        var closed = false
        try {
            val failure = runCatching {
                scope.closeWithOwnedJobs(db) {
                    closed = true
                    throw closer
                }
            }.exceptionOrNull()
            assertThat(failure).isSameInstanceAs(closer)
            assertThat(closed).isTrue()
            assertWithMessage("the database is still closed after the owned jobs finished")
                .that(db.isOpen).isFalse()
        } finally {
            if (db.isOpen) db.close()
        }
    }
}
