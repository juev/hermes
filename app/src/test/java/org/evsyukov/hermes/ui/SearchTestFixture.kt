package org.evsyukov.hermes.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.SearchHit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Controls only search I/O: even cancelled requests may answer late, as the retained cases require. */
internal class SearchAnswers<C : Any>(private val apply: () -> Unit) {
    inner class Call(val connection: C, val query: String, private val answer: Continuation<List<SearchHit>>) {
        var answered = false
            private set
        fun succeed(hits: List<SearchHit>) = finish { answer.resume(hits) }
        fun fail(error: Exception) = finish { answer.resumeWithException(error) }
        fun cancel() = fail(CancellationException("late cancellation"))
        private fun finish(deliver: () -> Unit) {
            check(!answered)
            answered = true
            deliver()
            apply()
        }
    }
    val calls = mutableListOf<Call>()
    var closed = false
        private set
    // Fault controls exercise real release/close paths through the enclosing fixture, not replaced cleanup steps.
    var releaseFailure: Throwable? = null
    var holdOnRelease = false
    suspend fun search(connection: C, query: String): List<SearchHit> {
        check(!closed)
        return suspendCoroutine { calls += Call(connection, query, it) }
    }
    fun releaseAll() {
        if (!holdOnRelease) calls.filterNot { it.answered }.forEach { it.cancel() }
        releaseFailure?.let { throw it }
    }
    fun close() {
        check(calls.all { it.answered }) { "unfinished search I/O" }
        closed = true
    }
}

/** A connection input with identity semantics, including exact same-profile/endpoint recreation. */
internal class SearchInput(val profile: String, val endpoint: String)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SearchStateFixture(joinDeadlineMillis: Long = 10_000) {
    val main = StandardTestDispatcher()
    val lifecycle = SearchFixtureLifecycle(main.scheduler, joinDeadlineMillis)
    val answers = SearchAnswers<SearchInput> { main.scheduler.runCurrent() }
    lateinit var scope: CoroutineScope
        private set
    val root: Job get() = requireNotNull(scope.coroutineContext[Job])
    var independentClosed = false
        private set

    fun run(afterAcquire: () -> Unit = {}, body: () -> Unit) = lifecycle.run(setup = {
        scope = CoroutineScope(SupervisorJob() + main)
        lifecycle.root(root)
        lifecycle.release(answers::releaseAll)
        lifecycle.dependency {
            check(lifecycle.joined && root.isCompleted)
            answers.close()
        }
        lifecycle.independent { independentClosed = true }
        afterAcquire()
    }, body = body)

    fun owner(connections: MutableStateFlow<SearchInput?>) = SearchOwner(connections, scope, answers::search)
}

internal fun searchHit(id: String) = SearchHit(
    ChatSummary(
        id = id, title = "Chat $id", preview = "", source = "telegram", model = null,
        startedAt = 1.0, lastActive = 2.0, messageCount = 1,
        pinned = false, archived = false, active = false,
    ),
    "snippet of $id", null,
)
