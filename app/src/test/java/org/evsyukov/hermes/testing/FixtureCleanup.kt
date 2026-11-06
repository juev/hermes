package org.evsyukov.hermes.testing

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Test-owned jobs only. This does not own the process-wide DataStore or WorkManager. */
internal class FixtureCleanup {
    var joinMillis = 10_000L
    private val finished = mutableSetOf<String>()

    fun finish(
        jobs: List<Job>,
        beforeJoin: List<Pair<String, () -> Unit>>,
        afterJoin: List<Pair<String, () -> Unit>>,
    ) {
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun attempt(name: String, action: () -> Unit) {
            if (name in finished) return
            try {
                action()
                finished += name
            } catch (error: Throwable) {
                record(error)
            }
        }
        beforeJoin.forEach { (name, action) -> attempt(name, action) }
        // The callers capture these actual owner Jobs before entering any coroutine builder.
        jobs.forEach { it.cancel() }
        val joined = runBlocking {
            withTimeoutOrNull(joinMillis) {
                jobs.forEach { it.join() }
                true
            } == true
        }
        if (joined) {
            afterJoin.forEach { (name, action) -> attempt(name, action) }
        } else {
            record(AssertionError("Fixture producers did not complete; dependencies remain open"))
        }
        failure?.let { throw it }
    }
}
