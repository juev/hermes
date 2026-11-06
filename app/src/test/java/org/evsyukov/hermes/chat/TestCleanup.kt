package org.evsyukov.hermes.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.evsyukov.hermes.data.cache.CacheDatabase

/**
 * Cancels and joins the jobs of this scope; false when they did not finish in [timeoutMillis].
 * A scope with no job is false too: the jobs its launches started are standalone, so this helper
 * cannot tell whether they are over. A caller must leave its database open on false: a cancelled
 * job may still be in a blocking DAO call, and closing the database under it turns cleanup into an
 * exception in another test.
 */
internal fun CoroutineScope.joinOwnedJobs(timeoutMillis: Long = 5_000): Boolean {
    // The receiver's job, not the job of the coroutine `withTimeoutOrNull` runs: joining that one
    // would cancel the timeout itself and never touch the scope whose jobs this helper owns.
    val owned = coroutineContext[Job] ?: return false
    return runBlocking { withTimeoutOrNull(timeoutMillis) { owned.cancelAndJoin() } } != null
}

/**
 * The teardown of a test that owns [scope] and [db]: joins the scope's jobs, closes [closeOther],
 * and closes the database only when the jobs are over. A job that does not finish leaves the
 * database open and fails the cleanup; a closed database under a blocked DAO call would report the
 * failure in another test. Every step runs even if another failed; the first failure is thrown and
 * the later ones are suppressed on it.
 */
internal fun CoroutineScope.closeWithOwnedJobs(
    db: CacheDatabase,
    timeoutMillis: Long = 5_000,
    closeOther: () -> Unit,
) {
    // Every step is attempted, whatever the others did: a join failure still needs the other
    // closure, and a throwing closure still needs the database closed once the jobs are over.
    val failures = ArrayList<Throwable>(2)
    fun attempt(step: () -> Unit) {
        try {
            step()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    var joined = false
    attempt {
        joined = joinOwnedJobs(timeoutMillis)
        check(joined) {
            "owned jobs did not finish in ${timeoutMillis}ms, or this scope has no job to join; " +
                "the database is left open"
        }
    }
    attempt { closeOther() }
    if (joined) attempt { db.close() }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}
