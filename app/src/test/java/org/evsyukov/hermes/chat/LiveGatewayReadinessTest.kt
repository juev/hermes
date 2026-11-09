package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveGatewayReadinessTest {
    @Test fun `a question sender waits for the request collector instead of dropping its request`() = runTest {
        val gateway = LiveGateway()
        gateway.connect()
        val sender = backgroundScope.launch {
            withTimeout(5_000) {
                gateway.awaitRequestCollector()
                gateway.ask("r1", "clarify", JsonObject(emptyMap()))
            }
        }
        runCurrent()
        assertWithMessage("REQUEST_MUST_NOT_BE_EMITTED_WITHOUT_A_COLLECTOR")
            .that(sender.isCompleted).isFalse()
        val incoming = async { gateway.requests.first { it.id == "r1" } }
        runCurrent()
        val request = withTimeout(5_000) { incoming.await() }
        sender.join()
        assertThat(request.id).isEqualTo("r1")
        assertThat(request.method).isEqualTo("clarify")
        assertThat(sender.isCompleted && !sender.isCancelled).isTrue()
    }
}
