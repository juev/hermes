package org.evsyukov.hermes.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingCallsTest {
    @Test
    fun `a call of a connection that is over is refused, not left waiting`() {
        val calls = PendingCalls()
        val first = Any()
        val second = Any()
        calls.open(first)

        // The first connection dropped.
        calls.close()
        assertThat(calls.add(first, "a1", CompletableDeferred())).isFalse()

        calls.open(second)
        assertThat(calls.add(first, "a2", CompletableDeferred())).isFalse()
        assertThat(calls.add(second, "a3", CompletableDeferred())).isTrue()
    }

    @Test
    fun `every call is refused or failed by the drop, also when the two race`() {
        val threads = Executors.newFixedThreadPool(4)
        try {
            repeat(200) { round ->
                val calls = PendingCalls()
                val connection = Any()
                calls.open(connection)
                val accepted = ConcurrentLinkedQueue<CompletableDeferred<JsonElement>>()
                val barrier = CyclicBarrier(4)
                val callers = (1..3).map { caller ->
                    threads.submit {
                        barrier.await()
                        repeat(50) { n ->
                            val deferred = CompletableDeferred<JsonElement>()
                            if (calls.add(connection, "r$round-c$caller-$n", deferred)) accepted += deferred
                        }
                    }
                }
                val drop = threads.submit {
                    barrier.await()
                    calls.close().forEach { it.completeExceptionally(IOException("lost")) }
                }
                (callers + drop).forEach { it.get(5, TimeUnit.SECONDS) }

                val waiting = accepted.count { !it.isCompleted }
                assertThat(waiting).isEqualTo(0)
            }
        } finally {
            threads.shutdownNow()
        }
    }
}
