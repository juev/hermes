package org.evsyukov.hermes.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** Encloses acquisition as well as the body. Dependencies remain open if any producer cannot join. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SearchFixtureLifecycle(
    private val scheduler: TestCoroutineScheduler,
    private val joinDeadlineMillis: Long = 10_000,
) {
    private val roots = mutableListOf<Job>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()
    private val stops = mutableListOf<() -> Unit>()
    private val releases = mutableListOf<() -> Unit>()
    private val dependencies = mutableListOf<() -> Unit>()
    private val independent = mutableListOf<() -> Unit>()
    var joined = false
        private set

    fun executor(worker: java.util.concurrent.ExecutorService) { executors += worker }

    fun root(job: Job) { roots += job }
    fun stop(action: () -> Unit) { stops += action }
    fun release(action: () -> Unit) { releases += action }
    fun dependency(action: () -> Unit) { dependencies += action }
    fun independent(action: () -> Unit) { independent += action }

    fun run(setup: () -> Unit, body: () -> Unit) {
        joined = false
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (t: Throwable) {
                val primary = failure
                if (primary == null) failure = t else if (primary !== t) primary.addSuppressed(t)
            }
        }
        try {
            setup()
            body()
        } catch (t: Throwable) {
            failure = t
        } finally {
            stops.asReversed().forEach { attempt(it) }
            executors.asReversed().forEach { worker -> attempt { worker.shutdownNow() } }
            roots.forEach { root -> attempt { root.cancel() } }
            releases.asReversed().forEach { attempt(it) }
            var rootsJoined = false
            attempt {
                // Roots were captured during acquisition, outside every cleanup coroutine builder.
                runBlocking {
                    val completion = async(Dispatchers.Default) {
                        withTimeout(joinDeadlineMillis) { roots.forEach { it.join() } }
                    }
                    // Keep the virtual scheduler on its owner thread. Never block that thread in join.
                    while (!completion.isCompleted) {
                        scheduler.runCurrent()
                        yield()
                    }
                    completion.await()
                }
                rootsJoined = true
            }
            var executorsJoined = true
            executors.asReversed().forEach { worker ->
                var terminated = false
                attempt {
                    check(worker.awaitTermination(joinDeadlineMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        "owned executor did not terminate before dependency closure"
                    }
                    terminated = true
                }
                executorsJoined = executorsJoined && terminated
            }
            joined = rootsJoined && executorsJoined
            if (joined) dependencies.asReversed().forEach { attempt(it) }
            independent.asReversed().forEach { attempt(it) }
        }
        failure?.let { throw it }
    }
}
