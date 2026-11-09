package org.evsyukov.hermes.chat

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class HistoryRefreshTest {
    @Test fun `failed explicit history read keeps the reply and reports the failure`() {
        FirstHistoryReconciliationTest().withScreenController({}) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) {
                checkNotNull(current.loadCompletion).await()
                while (current.completedLoadCycles.get() == 0L) kotlinx.coroutines.yield()
            }
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            current.send("local prompt", emptyList())
            withTimeout(10_000) {
                terminal.await()
                checkNotNull(current.submitCompletion).join()
                current.continuationProducers.toList().joinAll()
            }
            val retained = current.working.items
            val observation = current.working.currentObservationId
            backend.frozen = "not valid JSON"
            backend.holdSecond = true
            val previousLoad = current.loadCompletion
            val completedBeforeRefresh = current.completedLoadCycles.get()
            current.refreshHistory()
            check(backend.secondEntered.await(10, TimeUnit.SECONDS))
            val load = checkNotNull(current.loadCompletion)
            assertThat(load).isNotSameInstanceAs(previousLoad)
            backend.secondRelease.countDown()
            withTimeout(10_000) {
                load.await()
                while (current.completedLoadCycles.get() <= completedBeforeRefresh) kotlinx.coroutines.yield()
            }
            com.google.common.truth.Truth.assertWithMessage(
                "Failed read outcome: histories=${backend.histories.get()}, cycles=${current.completedLoadCycles.get()}, " +
                    "accepted=${current.acceptedReloadRequests.get()}, loadIsLatest=${current.loadCompletion === load}, " +
                    "latestComplete=${current.loadCompletion?.isCompleted}, loading=${current.working.loading}, " +
                    "refreshes=${current.working.historyRefresh}",
            ).that(current.working.error).isNotNull()
            assertThat(current.working.historyRefresh).isEqualTo(0)
            assertThat(current.working.items).isEqualTo(retained)
            assertThat(current.working.currentObservationId).isEqualTo(observation)
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }

    @Test fun `a queued refresh cannot hide a turn sent after the refresh action`() {
        FirstHistoryReconciliationTest().withScreenController({}) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await() }
            backend.holdSecond = true
            backend.frozen = """{"session_id":"s1","profile":"synthetic","messages":[]}"""
            current.reload()
            check(backend.secondEntered.await(10, TimeUnit.SECONDS))
            current.refreshHistory()
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            current.send("sent after queued refresh", emptyList())
            withTimeout(10_000) { terminal.await() }
            backend.secondRelease.countDown()
            withTimeout(10_000) {
                current.state.first { it.historyRefresh > 0 }
                checkNotNull(current.loadCompletion).await()
            }
            assertThat(current.working.items.filterIsInstance<ChatItem.User>().map { it.text }).contains("sent after queued refresh")
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }

    @Test fun `a turn completed during explicit history refresh is not replaced by the old page`() {
        FirstHistoryReconciliationTest().withScreenController({}) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await() }
            backend.holdSecond = true
            backend.frozen = """{"session_id":"s1","profile":"synthetic","messages":[]}"""
            current.refreshHistory()
            check(backend.secondEntered.await(10, TimeUnit.SECONDS))
            val load = checkNotNull(current.loadCompletion)
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            current.send("newer local prompt", emptyList())
            withTimeout(10_000) { terminal.await() }
            val observation = current.working.currentObservationId
            backend.secondRelease.countDown()
            withTimeout(10_000) { load.await() }
            assertThat(current.working.items.filterIsInstance<ChatItem.User>().map { it.text }).contains("newer local prompt")
            assertThat(current.working.items.filterIsInstance<ChatItem.Assistant>().last().text).isEqualTo("done")
            assertThat(current.working.currentObservationId).isEqualTo(observation)
            assertThat(current.working.historyRefresh).isEqualTo(1)
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }
}
