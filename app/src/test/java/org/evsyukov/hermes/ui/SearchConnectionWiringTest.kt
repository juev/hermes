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
    @Test fun saveRejectsPublishedIntermediateBeforeIntendedPublication() {
        val bId = "readiness-b"
        val observations = java.util.concurrent.CopyOnWriteArrayList<Triple<Connection, ServerProfile, Long?>>()
        val mintedConstruction = java.util.concurrent.CompletableFuture<Triple<Connection, ServerProfile, Long?>>()
        val publishMinted = java.util.concurrent.CountDownLatch(1)
        val mintEntered = kotlinx.coroutines.CompletableDeferred<ServerState>()
        val releaseMint = kotlinx.coroutines.CompletableDeferred<Unit>()
        val decision = java.util.concurrent.CompletableFuture<Pair<Connection?, Boolean>>()
        val f = WiringFixture { connection, profile, incarnation ->
            val observation = Triple(connection, profile, incarnation)
            observations.add(observation)
            if (profile.id == bId && incarnation != null) {
                mintedConstruction.complete(observation)
                check(publishMinted.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "construction gate release missing" }
            }
        }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        f.ownExecutor(worker)
        var saving: java.util.concurrent.Future<Connection>? = null
        var failure: Throwable? = null
        try {
            f.run {
                readinessBodyAndCleanup(worker, { saving }, release = {
                    releaseMint.complete(Unit)
                    publishMinted.countDown()
                    f.container.lifetime.beforeProfileMint = null
                    f.beforeSaveCandidate = null
                    f.afterSaveDecision = null
                }) {
                    val b = ServerProfile(bId, "Readiness B", f.serverB.url("/").toString())
                    val onA = requireNotNull(f.container.connection.value)
                    f.vm.setQuery("readiness")
                    f.apply(onA)
                    f.container.lifetime.beforeProfileMint = { persisted ->
                        if (persisted.active?.id == bId) {
                            mintEntered.complete(persisted)
                            releaseMint.await()
                        }
                    }
                    f.beforeSaveCandidate = { candidate ->
                        if (candidate?.session?.profile == b) {
                            // Keep the real first() candidate while the intended construction is held.
                            mintedConstruction.get(10, java.util.concurrent.TimeUnit.SECONDS)
                            assertThat(f.container.connection.value).isSameInstanceAs(candidate)
                        }
                    }
                    f.afterSaveDecision = { candidate, eligible ->
                        if (candidate?.session?.profile == b) decision.complete(candidate to eligible)
                    }
                    saving = worker.submit<Connection> { f.save(b) }
                    val persisted = f.external { mintEntered.await() }
                    assertThat(persisted.active).isEqualTo(b)
                    val intermediate = f.external {
                        requireNotNull(f.container.connection.first { it?.session?.profile == b })
                    }
                    val consumedIntermediate = observations.single { it.first === intermediate }
                    assertThat(consumedIntermediate.third).isNull()
                    assertThat(intermediate).isNotSameInstanceAs(onA)
                    releaseMint.complete(Unit)
                    val intended = mintedConstruction.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    assertThat(intended.second).isEqualTo(b)
                    assertThat(intended.third).isNotNull()
                    assertThat(intended.first).isNotSameInstanceAs(intermediate)
                    val actualDecision = decision.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    assertThat(actualDecision.first).isSameInstanceAs(intermediate)
                    assertThat(f.container.connection.value).isSameInstanceAs(intermediate)
                    println("READINESS_DECISION persisted=${persisted.active} intermediateIdentity=${System.identityHashCode(intermediate)} consumed=${consumedIntermediate.third} intendedIdentity=${System.identityHashCode(intended.first)} intendedConsumed=${intended.third} publishedIdentity=${System.identityHashCode(f.container.connection.value)} eligible=${actualDecision.second} mainIdentity=${f.vm.search.value.request?.connection?.let { System.identityHashCode(it) }}")
                    com.google.common.truth.Truth.assertWithMessage(
                        "INCOMPLETE_INPUT_READINESS_ACCEPTED: real save decision must reject published intermediate while intended construction is unpublished",
                    ).that(actualDecision.second).isFalse()
                    // A repaired decision can progress without its completion releasing publication.
                    publishMinted.countDown()
                    val published = f.external { f.container.connection.first { it === intended.first } }
                    assertThat(saving!!.get(10, java.util.concurrent.TimeUnit.SECONDS)).isSameInstanceAs(published)
                    f.apply(published)
                    assertThat(f.vm.search.value.request?.connection).isSameInstanceAs(published)
                }
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            releaseMint.complete(Unit)
            publishMinted.countDown()
            worker.shutdownNow()
            try {
                f.assertClosed()
                println("READINESS_CLEANUP fixture roots joined; dependencies closed; Main reset")
            } catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    @Test fun readinessCleanupPreservesBodyFailureWhenSaveWorkerFails() {
        val original = IllegalStateException("CONTROL original readiness body failure")
        val workerFailure = IllegalArgumentException("CONTROL save worker failure")
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var assertionFailure: Throwable? = null
        try {
            val saving = worker.submit<Unit> {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw workerFailure
            }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val caught = try {
                readinessBodyAndCleanup(worker, { saving }, release = { release.countDown() }) {
                    throw original
                }
                null
            } catch (t: Throwable) {
                t
            }
            println("PRIMARY_CONTROL originalIdentity=${System.identityHashCode(original)} originalType=${original.javaClass.name} caughtIdentity=${caught?.let { System.identityHashCode(it) }} caughtType=${caught?.javaClass?.name} causeIsWorker=${caught?.cause === workerFailure} suppressed=${caught?.suppressed?.map { it.javaClass.name + ":" + System.identityHashCode(it) }} workerFailureIdentity=${System.identityHashCode(workerFailure)}")
            println("PRIMARY_CONTROL original throwable:")
            original.printStackTrace(System.out)
            println("PRIMARY_CONTROL caught throwable (including suppressed):")
            caught?.printStackTrace(System.out)
            com.google.common.truth.Truth.assertWithMessage(
                "READINESS_PRIMARY_FAILURE_REPLACED: retain exact body throwable and suppress later save-get failure",
            ).that(caught).isSameInstanceAs(original)
            val suppressed = original.suppressed
            com.google.common.truth.Truth.assertWithMessage(
                "READINESS_CLEANUP_FAILURE_NOT_SUPPRESSED: save-get failure follows original body failure",
            ).that(suppressed.map { it.javaClass }).containsExactly(java.util.concurrent.ExecutionException::class.java).inOrder()
            assertThat(suppressed.single().cause).isSameInstanceAs(workerFailure)
        } catch (t: Throwable) {
            assertionFailure = t
            throw t
        } finally {
            // Test-local safety only: the shared baseline has already returned/thrown.
            try {
                release.countDown()
                worker.shutdownNow()
                check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                println("PRIMARY_CONTROL_SAFETY executorTerminated=${worker.isTerminated}; no fixture dependencies owned")
            } catch (cleanup: Throwable) {
                val primary = assertionFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    @Test fun readinessCleanupShutsDownWorkerAfterSaveFailure() {
        // Cleanup-driver component control; no Room/server/fixture dependencies.
        val workerFailure = IllegalArgumentException("CONTROL shutdown save worker failure")
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var assertionFailure: Throwable? = null
        try {
            val saving = worker.submit<Unit> {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw workerFailure
            }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val caught = try {
                readinessBodyAndCleanup(worker, { saving }, release = { release.countDown() }) {
                    Unit
                }
                null
            } catch (t: Throwable) {
                t
            }
            println("SHUTDOWN_CONTROL_BEFORE_SAFETY caughtType=${caught?.javaClass?.name} caughtIdentity=${caught?.let { System.identityHashCode(it) }} causeIsWorker=${caught?.cause === workerFailure} workerFailureIdentity=${System.identityHashCode(workerFailure)} suppressed=${caught?.suppressed?.map { it.javaClass.name }} isShutdown=${worker.isShutdown} isTerminated=${worker.isTerminated}")
            caught?.printStackTrace(System.out)
            com.google.common.truth.Truth.assertWithMessage(
                "READINESS_CLEANUP_ONLY_FAILURE_LOST: real failed save wait must propagate",
            ).that(caught).isInstanceOf(java.util.concurrent.ExecutionException::class.java)
            assertThat(caught!!.cause).isSameInstanceAs(workerFailure)
            println("SHUTDOWN_CONTROL cleanup-only propagation assertions passed; before safety cleanup")
            com.google.common.truth.Truth.assertWithMessage(
                "READINESS_SHUTDOWN_SKIPPED_AFTER_SAVE_FAILURE: helper must shut down executor despite failed save wait",
            ).that(worker.isShutdown).isTrue()
            com.google.common.truth.Truth.assertWithMessage(
                "READINESS_TERMINATION_SKIPPED_AFTER_SAVE_FAILURE: helper must confirm executor termination despite failed save wait",
            ).that(worker.isTerminated).isTrue()
        } catch (t: Throwable) {
            assertionFailure = t
            throw t
        } finally {
            // Independent test-local safety after observing/asserting the actual helper result.
            try {
                release.countDown()
                worker.shutdownNow()
                check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                println("SHUTDOWN_CONTROL_AFTER_SAFETY isShutdown=${worker.isShutdown} isTerminated=${worker.isTerminated}; no fixture dependencies owned")
            } catch (cleanup: Throwable) {
                val primary = assertionFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    @Test fun heldForeignWorkerRefusesLifecycleDependencyClosure() {
        // Real lifecycle admission control; harmless synthetic resource, no Room/server allocation.
        val lifecycle = SearchFixtureLifecycle(StandardTestDispatcher().scheduler, joinDeadlineMillis = 100)
        val root = Job().apply { complete() }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val releaseTask = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
        val interruptions = java.util.concurrent.atomic.AtomicInteger(0)
        val closes = java.util.concurrent.atomic.AtomicInteger(0)
        val resource = java.io.Closeable { closes.incrementAndGet() }
        val original = IllegalStateException("CONTROL held-worker body failure")
        var assertionFailure: Throwable? = null
        try {
            lifecycle.executor(worker)
            val saving = worker.submit<Unit> {
                running.set(true)
                entered.countDown()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                try {
                    // Cancellation/interruption cannot release this gate; independent safety does.
                    while (true) {
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "held task safety deadline expired" }
                        try {
                            check(releaseTask.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                                "held task safety deadline expired"
                            }
                            break
                        } catch (_: InterruptedException) {
                            interruptions.incrementAndGet()
                        }
                    }
                } finally {
                    running.set(false)
                    exited.countDown()
                }
            }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val caught = try {
                lifecycle.run(setup = {
                    lifecycle.root(root)
                    lifecycle.dependency {
                        println("OWNERSHIP_DEPENDENCY_ADMITTED running=${running.get()} exited=${exited.count == 0L} terminated=${worker.isTerminated}")
                        resource.close()
                    }
                }, body = {
                    readinessBodyAndCleanup(worker, { saving }, release = {}, waitBudgetMillis = 100) {
                        check(saving.cancel(true))
                        println("OWNERSHIP_CANCEL futureDone=${saving.isDone} cancelled=${saving.isCancelled} running=${running.get()} exited=${exited.count == 0L}")
                        throw original
                    }
                })
                null
            } catch (t: Throwable) {
                t
            }
            println("OWNERSHIP_BEFORE_SAFETY originalIdentity=${System.identityHashCode(original)} caughtIdentity=${caught?.let { System.identityHashCode(it) }} suppressed=${caught?.suppressed?.map { it.javaClass.name }} callbacks=${closes.get()} joined=${lifecycle.joined} rootCompleted=${root.isCompleted} futureDone=${saving.isDone} cancelled=${saving.isCancelled} running=${running.get()} exited=${exited.count == 0L} shutdown=${worker.isShutdown} terminated=${worker.isTerminated} interruptions=${interruptions.get()}")
            caught?.printStackTrace(System.out)
            assertThat(caught).isSameInstanceAs(original)
            assertThat(saving.isDone).isTrue()
            assertThat(saving.isCancelled).isTrue()
            assertThat(running.get()).isTrue()
            assertThat(exited.count).isEqualTo(1L)
            com.google.common.truth.Truth.assertWithMessage(
                "READINESS_FOREIGN_WORKER_CLOSURE_ADMITTED: lifecycle must refuse joined/dependency admission while owned executor task remains active",
            ).that(lifecycle.joined).isFalse()
            assertThat(closes.get()).isEqualTo(0)
            // Controlled second run only after actual termination; no general fixture retry claim.
            releaseTask.countDown()
            check(exited.await(10, java.util.concurrent.TimeUnit.SECONDS))
            check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            assertThat(closes.get()).isEqualTo(0)
            lifecycle.run(setup = {}, body = {})
            assertThat(lifecycle.joined).isTrue()
            assertThat(closes.get()).isEqualTo(1)
            println("OWNERSHIP_RELEASE_POSITIVE callbacks=${closes.get()} joined=${lifecycle.joined} exited=${exited.count == 0L} terminated=${worker.isTerminated}")
        } catch (t: Throwable) {
            assertionFailure = t
            throw t
        } finally {
            // Safety is independent of lifecycle admission and of the unreached positive remainder.
            try {
                releaseTask.countDown()
                worker.shutdownNow()
                check(exited.await(10, java.util.concurrent.TimeUnit.SECONDS))
                check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                println("OWNERSHIP_AFTER_SAFETY callbacks=${closes.get()} running=${running.get()} exited=${exited.count == 0L} terminated=${worker.isTerminated}")
            } catch (cleanup: Throwable) {
                val primary = assertionFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    @Test fun readinessCleanupPreservesBodyOnlyFailure() {
        val original = IllegalStateException("CONTROL body-only failure")
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(1)
        val workerRuns = java.util.concurrent.atomic.AtomicInteger(0)
        var bodyRuns = 0
        val result = Any()
        var assertionFailure: Throwable? = null
        try {
            val saving = worker.submit<Any> {
                entered.countDown()
                try {
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    workerRuns.incrementAndGet()
                    result
                } finally {
                    exited.countDown()
                }
            }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val caught = try {
                readinessBodyAndCleanup(worker, { saving }, release = { release.countDown() }) {
                    bodyRuns++
                    throw original
                }
                null
            } catch (t: Throwable) {
                t
            }
            println("POSITIVE_BODY_ONLY_BEFORE_SAFETY caughtType=${caught?.javaClass?.name} caughtIdentity=${caught?.let { System.identityHashCode(it) }} originalIdentity=${System.identityHashCode(original)} suppressed=${caught?.suppressed?.map { it.javaClass.name }} bodyRuns=$bodyRuns workerRuns=${workerRuns.get()} futureDone=${saving.isDone} cancelled=${saving.isCancelled} exited=${exited.count == 0L} shutdown=${worker.isShutdown} terminated=${worker.isTerminated}")
            caught?.printStackTrace(System.out)
            assertThat(caught).isSameInstanceAs(original)
            assertThat(original.suppressed.toList()).isEmpty()
            assertThat(bodyRuns).isEqualTo(1)
            assertThat(workerRuns.get()).isEqualTo(1)
            assertThat(saving.isDone).isTrue()
            assertThat(saving.isCancelled).isFalse()
            assertThat(saving.get(10, java.util.concurrent.TimeUnit.SECONDS)).isSameInstanceAs(result)
            assertThat(exited.count).isEqualTo(0L)
            assertThat(worker.isShutdown).isTrue()
            assertThat(worker.isTerminated).isTrue()
        } catch (t: Throwable) {
            assertionFailure = t
            throw t
        } finally {
            try {
                release.countDown()
                worker.shutdownNow()
                check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                println("POSITIVE_BODY_ONLY_AFTER_SAFETY exited=${exited.count == 0L} terminated=${worker.isTerminated}; no fixture dependencies owned")
            } catch (cleanup: Throwable) {
                val primary = assertionFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    @Test fun readinessCleanupCompletesSuccessfulWorker() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(1)
        val workerRuns = java.util.concurrent.atomic.AtomicInteger(0)
        var bodyRuns = 0
        val result = Any()
        var assertionFailure: Throwable? = null
        try {
            val saving = worker.submit<Any> {
                entered.countDown()
                try {
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    workerRuns.incrementAndGet()
                    result
                } finally {
                    exited.countDown()
                }
            }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val caught = try {
                readinessBodyAndCleanup(worker, { saving }, release = { release.countDown() }) {
                    bodyRuns++
                    Unit
                }
                null
            } catch (t: Throwable) {
                t
            }
            println("POSITIVE_SUCCESS_BEFORE_SAFETY caughtType=${caught?.javaClass?.name} caughtIdentity=${caught?.let { System.identityHashCode(it) }} suppressed=${caught?.suppressed?.map { it.javaClass.name }} bodyRuns=$bodyRuns workerRuns=${workerRuns.get()} futureDone=${saving.isDone} cancelled=${saving.isCancelled} exited=${exited.count == 0L} shutdown=${worker.isShutdown} terminated=${worker.isTerminated}")
            caught?.printStackTrace(System.out)
            assertThat(caught).isNull()
            assertThat(bodyRuns).isEqualTo(1)
            assertThat(workerRuns.get()).isEqualTo(1)
            assertThat(saving.isDone).isTrue()
            assertThat(saving.isCancelled).isFalse()
            assertThat(saving.get(10, java.util.concurrent.TimeUnit.SECONDS)).isSameInstanceAs(result)
            assertThat(exited.count).isEqualTo(0L)
            assertThat(worker.isShutdown).isTrue()
            assertThat(worker.isTerminated).isTrue()
        } catch (t: Throwable) {
            assertionFailure = t
            throw t
        } finally {
            try {
                release.countDown()
                worker.shutdownNow()
                check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                println("POSITIVE_SUCCESS_AFTER_SAFETY exited=${exited.count == 0L} terminated=${worker.isTerminated}; no fixture dependencies owned")
            } catch (cleanup: Throwable) {
                val primary = assertionFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun readinessBodyAndCleanup(
        worker: java.util.concurrent.ExecutorService,
        saving: () -> java.util.concurrent.Future<*>?,
        release: () -> Unit,
        waitBudgetMillis: Long = 10_000,
        body: () -> Unit,
    ) {
        var failure: Throwable? = null
        fun attempt(cleanup: () -> Unit) {
            try {
                cleanup()
            } catch (t: Throwable) {
                val primary = failure
                if (primary == null) failure = t
                else if (primary !== t && primary.suppressed.none { it === t }) primary.addSuppressed(t)
            }
        }
        try {
            body()
        } catch (t: Throwable) {
            failure = t
        } finally {
            attempt { release() }
            attempt { saving()?.get(waitBudgetMillis, java.util.concurrent.TimeUnit.MILLISECONDS) }
            attempt { worker.shutdown() }
            attempt {
                check(worker.awaitTermination(waitBudgetMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
                println("READINESS_GATES_RELEASED save worker joined before fixture shutdown")
            }
        }
        failure?.let { throw it }
    }

    @Test fun freshPublishedConnectionHasPriorConstructionObservation() {
        val observations = java.util.concurrent.CopyOnWriteArrayList<Triple<Connection, ServerProfile, Long?>>()
        val f = WiringFixture { connection, profile, incarnation ->
            observations.add(Triple(connection, profile, incarnation))
        }
        var failure: Throwable? = null
        try {
            f.run {
                // Setup's save() awaited a fresh matching instance from the real StateFlow.
                // A required upstream construction callback must already have completed now.
                val published = f.external { requireNotNull(f.container.connection.first { it != null }) }
                assertThat(published.session.profile).isEqualTo(f.initial)
                val recorded = observations.toList()
                println("CONSTRUCTION_PUBLICATION identity=${System.identityHashCode(published)} profile=${published.session.profile} observations=$recorded count=${recorded.size}")
                val observation = recorded.singleOrNull { it.first === published }
                com.google.common.truth.Truth.assertWithMessage(
                    "BEFORE_PUBLICATION_OBSERVATION_ABSENT: real published Connection requires a prior exact-instance construction observation",
                ).that(observation).isNotNull()
                assertThat(observation!!.second).isEqualTo(published.session.profile)
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            try {
                f.assertClosed()
                println("CONSTRUCTION_CLEANUP roots joined; answers released and closed; Main reset")
            } catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    @Test fun ownedRoomDatabaseClosesAfterWiringLifecycleCompletes() {
        val f = WiringFixture()
        var database: org.evsyukov.hermes.data.cache.CacheDatabase? = null
        var vmRoot: Job? = null
        var containerRoot: Job? = null
        var failure: Throwable? = null
        try {
            f.run {
                database = f.container.cacheDatabase
                vmRoot = requireNotNull(f.vm.viewModelScope.coroutineContext[Job])
                containerRoot = requireNotNull(f.container.scope.coroutineContext[Job])
                f.external { f.container.responses.read(f.initial.id, "room-lifecycle-regression") }
                assertThat(database!!.isOpen).isTrue()
                println("ROOM_LIFECYCLE body completed: database=${System.identityHashCode(database)} open=${database!!.isOpen}")
            }
            f.assertClosed()
            assertThat(database).isSameInstanceAs(f.container.cacheDatabase)
            println("ROOM_LIFECYCLE shutdown completed: database=${System.identityHashCode(database)} vmCompleted=${vmRoot!!.isCompleted} containerCompleted=${containerRoot!!.isCompleted} open=${database!!.isOpen}")
            com.google.common.truth.Truth.assertWithMessage(
                "Owned Room database must be closed after real WiringFixture roots complete",
            ).that(database!!.isOpen).isFalse()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // Final leak prevention only after the lifecycle has joined its actual producers.
            if (vmRoot?.isCompleted == true && containerRoot?.isCompleted == true) {
                try { database?.close() } catch (cleanup: Throwable) {
                    val primary = failure
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
        }
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

    private class WiringFixture(
        private val connectionConstructionObserver: ((Connection, ServerProfile, Long?) -> Unit)? = null,
    ) {
        private val constructionInputs = java.util.concurrent.CopyOnWriteArrayList<Triple<Connection, ServerProfile, Long?>>()
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

        fun ownExecutor(worker: java.util.concurrent.ExecutorService) = lifecycle.executor(worker)

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
            container = AppContainer(context, PlainCipher, connectionConstructionObserver = { connection, profile, incarnation ->
                constructionInputs.add(Triple(connection, profile, incarnation))
                connectionConstructionObserver?.invoke(connection, profile, incarnation)
            })
            val database = container.cacheDatabase
            lifecycle.dependency { check(lifecycle.joined); database.close() }
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

        var beforeSaveCandidate: (suspend (Connection?) -> Unit)? = null
        var afterSaveDecision: ((Connection?, Boolean) -> Unit)? = null

        fun save(profile: ServerProfile): Connection {
            finishStartupProducers()
            return external {
            val previous = container.connection.value
            container.servers.save(profile)
            val intendedIncarnation = container.lifetime.incarnations.value[profile.id]
            requireNotNull(container.connection.first {
                beforeSaveCandidate?.invoke(it)
                val eligible = it !== previous && it?.session?.profile == profile &&
                    constructionInputs.any { input ->
                        input.first === it && input.second == profile && input.third == intendedIncarnation
                    }
                afterSaveDecision?.invoke(it, eligible)
                eligible
            })
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
                    // These newly owned local reads can hold auth admission while resuming on
                    // Main. Complete them before this fixture deliberately stops pumping Main.
                    val chat = vm.chat.value
                    finish(chat?.observationRecovery)
                    finish(chat?.loadCompletion)
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
