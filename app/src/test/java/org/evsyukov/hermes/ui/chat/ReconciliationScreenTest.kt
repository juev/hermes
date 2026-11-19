package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.*
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReconciliationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val overlapNotice = hasText("Recent on-device replies may overlap stored history", substring = true)
    private fun show(current: ChatController, openLocal: (LocalAttachment) -> Unit = {}) {
        compose.setContent { HermesTheme { ChatScreen(current, Settings(), "Synthetic", true, {}, {}, {}, { null }, { error("not requested") }, openLocal, { c, visible -> c.setVisible(visible) }, remember { ChatDraft() }) } }
    }
    @Test fun `ordinary receiptless reply stays in the transcript without copy warnings`() {
        val fixture = FirstHistoryReconciliationTest()
        fixture.withScreenController({ it.holdFirst = true; it.freezeFirst = true; it.toolRich = true }) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            val load = checkNotNull(current.loadCompletion)
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            current.send("early prompt", emptyList())
            withTimeout(10_000) { terminal.await() }
            backend.release.countDown(); withTimeout(10_000) { load.await() }
            show(current)
            compose.onAllNodes(overlapNotice).assertCountEquals(0)
            compose.onAllNodes(hasText(app.getString(R.string.chat_local_copy))).assertCountEquals(0)
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("done", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("done", substring = true))
            compose.onNodeWithText("done", substring = true).assertIsDisplayed()
            compose.onAllNodes(hasText("full tool result", substring = true)).assertCountEquals(0)
            assertThat(current.state.value.observations.single().frames).isNotEmpty()
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }
    @Test fun `two unmatched copies remain accessible without a mismatch notice`() {
        val fixture = FirstHistoryReconciliationTest()
        fixture.withScreenController({}) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await() }
            val attachment = LocalAttachment("retained.txt", "text/plain", byteArrayOf(1, 2, 3))
            for (prompt in listOf("first local prompt", "second local prompt")) {
                val terminal = CompletableDeferred<Unit>()
                current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
                current.send(prompt, if (prompt.startsWith("first")) listOf(attachment) else emptyList())
                withTimeout(10_000) { terminal.await(); checkNotNull(current.submitCompletion).join() }
            }
            val copies = current.state.value.observations
            assertThat(copies).hasSize(2)
            assertThat(copies.none { it.reconciled }).isTrue()
            var opened: LocalAttachment? = null
            show(current) { opened = it }
            val transcript = compose.onNodeWithTag("transcript")
            // Markdown parsing is asynchronous; all these short rows fit in this viewport.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("first local prompt")).fetchSemanticsNodes().isNotEmpty() }
            transcript.performScrollToNode(hasText("first local prompt"))
            compose.onNodeWithText("first local prompt").assertIsDisplayed()
            compose.onAllNodes(overlapNotice).assertCountEquals(0)
            for (copy in copies) compose.onNodeWithTag("observation:${copy.id}").assertDoesNotExist()

            transcript.performScrollToNode(hasTestTag("local-copies"))
            compose.onNodeWithTag("local-copies").performTouchInput { click(center) }
            for (copy in copies) {
                val tag = "observation:${copy.id}"
                transcript.performScrollToNode(hasTestTag(tag))
                compose.onAllNodes(overlapNotice and hasAnyAncestor(hasTestTag(tag))).assertCountEquals(0)
                val prompt = copy.items.filterIsInstance<ChatItem.User>().single().text
                compose.onNode(hasText(prompt, substring = true) and hasAnyAncestor(hasTestTag(tag))).assertExists()
            }
            val fileLabel = app.getString(R.string.chat_local_file, attachment.name)
            transcript.performScrollToNode(hasText(fileLabel))
            compose.onNodeWithText(fileLabel).performClick()
            compose.runOnIdle {
                assertThat(opened?.name).isEqualTo(attachment.name)
                assertThat(opened?.mime).isEqualTo(attachment.mime)
                assertThat(opened?.bytes).isEqualTo(attachment.bytes)
            }
            assertThat(backend.prompts.get()).isEqualTo(2)
        }
    }
    @Test fun `a storage failure remains visible without opening recovery`() {
        FirstHistoryReconciliationTest().withScreenController({}, failJournalWrites = true) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await() }
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            current.send("retain this reply", emptyList())
            withTimeout(10_000) {
                terminal.await()
                current.state.first { it.observationStorageError }
            }
            show(current)
            compose.onNodeWithText(app.getString(R.string.chat_local_unsaved)).assertIsDisplayed()
            compose.onAllNodes(hasText(app.getString(R.string.chat_local_copy))).assertCountEquals(0)
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }
    @Test fun `history refresh retains an unmatched reply on success and on visible failure`() {
        val fixture = FirstHistoryReconciliationTest()
        fixture.withScreenController({ it.holdFirst = true; it.freezeFirst = true; it.toolRich = true }) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            val load = checkNotNull(current.loadCompletion)
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            current.send("early prompt", emptyList())
            withTimeout(10_000) { terminal.await() }
            backend.release.countDown(); withTimeout(10_000) { load.await(); current.continuationProducers.toList().joinAll() }
            val retained = current.state.value.observations.single().items
            show(current)
            compose.onNodeWithTag("transcript").performScrollToNode(hasTestTag("local-copies"))
            compose.onNodeWithTag("local-copies").performTouchInput { click(center) }
            backend.holdSecond = true
            compose.onNodeWithTag("transcript").performScrollToNode(hasText(app.getString(R.string.chat_local_refresh)))
            compose.onNodeWithText(app.getString(R.string.chat_local_refresh)).performTouchInput { click(center) }
            check(backend.secondEntered.await(10, TimeUnit.SECONDS)) { "UI refresh did not enter HTTP" }
            val refresh = checkNotNull(current.loadCompletion)
            backend.secondRelease.countDown(); withTimeout(10_000) { refresh.await() }
            compose.waitForIdle()
            assertThat(current.state.value.currentObservationId).isNull()
            compose.onAllNodes(overlapNotice).assertCountEquals(0)
            compose.onAllNodes(hasText("done")).assertCountEquals(0)
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("hi"))
            compose.onNodeWithText("hi").assertIsDisplayed()
            assertThat(current.state.value.observations.single().items).isEqualTo(retained)
            assertThat(current.state.value.observations.single().reconciled).isFalse()
            compose.onNodeWithTag("transcript").performScrollToNode(hasTestTag("local-copies"))
            compose.onNodeWithTag("local-copies").performTouchInput { click(center) }
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("done"))
            compose.onNodeWithText("done").assertIsDisplayed()

            val histories = backend.histories.get()
            backend.frozen = "not valid JSON"
            compose.onNodeWithTag("transcript").performScrollToNode(hasText(app.getString(R.string.chat_local_refresh)))
            compose.onNodeWithText(app.getString(R.string.chat_local_refresh)).performTouchInput { click(center) }
            val failure = withTimeout(10_000) { checkNotNull(current.state.first { it.error != null }.error) }
            compose.onNodeWithText(failure).assertIsDisplayed()
            assertThat(backend.histories.get()).isGreaterThan(histories)
            assertThat(current.state.value.observations.single().items).isEqualTo(retained)
            assertThat(current.state.value.observations.single().reconciled).isFalse()
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("done"))
            compose.onNodeWithText("done").assertIsDisplayed()
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }
    @Test fun `stored tail history and authorized current resumed delta both render in actual screen`() {
        val fixture = FirstHistoryReconciliationTest()
        fixture.withScreenController({ it.holdFirst = true; it.running = true; it.inflight = "hi plus " }) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { current.state.first { it.running } }
            val consumed = CompletableDeferred<Unit>(); current.afterCurrentEvent = { if(it == "message.delta") consumed.complete(Unit) }
            backend.delta("new")
            withTimeout(10_000) { consumed.await() }
            val load = checkNotNull(current.loadCompletion); backend.release.countDown(); withTimeout(10_000) { load.await() }
            show(current)
            // Renderer parsing is off-main; wait for parser readiness, then assert real visibility.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("hi") or hasText("hi plus new")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hi plus new", substring = true).assertExists()
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("hi plus new", substring = true))
            compose.onNodeWithText("hi plus new", substring = true).assertIsDisplayed()
            assertThat(current.state.value.storedHistory?.any { it.key == "a2" }).isTrue()
            assertThat(backend.prompts.get()).isEqualTo(0)
        }
    }    @Test fun `actual expired config resume updates stored keyed assistant and screen overlay`() {
        val fixture = FirstHistoryReconciliationTest()
        fixture.withScreenController({}) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await() }
            assertThat(current.working.items.last().key).isEqualTo("a2")
            backend.running = true; backend.inflight = "hi plus "; backend.expireConfig = true
            current.chooseModel("synthetic", "new-model")
            check(backend.configRecovered.await(10, TimeUnit.SECONDS)) { "Recovered config RPC did not arrive" }
            val consumed = CompletableDeferred<Unit>(); current.afterCurrentEvent = { if(it == "message.delta") consumed.complete(Unit) }
            backend.delta("new"); withTimeout(10_000) { consumed.await() }
            assertThat((current.working.items.last() as ChatItem.Assistant).key).isEqualTo("a2")
            assertThat((current.working.items.last() as ChatItem.Assistant).text).isEqualTo("hi plus new")
            show(current)
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("hi") or hasText("hi plus new")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hi plus new", substring = true).assertExists()
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("hi plus new", substring = true))
            compose.onNodeWithText("hi plus new", substring = true).assertIsDisplayed()
            assertThat(backend.prompts.get()).isEqualTo(0)
            val terminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
            backend.input("message.complete", kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive("hi plus new")); put("status", kotlinx.serialization.json.JsonPrimitive("complete"))
            })
            withTimeout(10_000) { terminal.await() }
            val archive = current.state.value.observations.single { it.id == current.state.value.currentObservationId }
            assertThat(archive.completed).isTrue()
            backend.inflight = "hi plus new resumed "; backend.running = true
            backend.configs.set(0); backend.configRecovered = java.util.concurrent.CountDownLatch(1)
            current.chooseModel("synthetic", "another-model")
            check(backend.configRecovered.await(10, TimeUnit.SECONDS)) { "Second recovered config RPC did not arrive" }
            val later = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.delta") later.complete(Unit) }
            backend.delta("later"); withTimeout(10_000) { later.await() }
            assertThat((current.working.items.last() as ChatItem.Assistant).text).isEqualTo("hi plus new resumed later")
            compose.waitForIdle()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("hi plus new") or hasText("hi plus new resumed later")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("hi plus new resumed later", substring = true))
            compose.onNodeWithText("hi plus new resumed later", substring = true).assertIsDisplayed()
            val live = current.state.value.observations.single { it.id == current.state.value.currentObservationId }
            assertThat(live.id).isNotEqualTo(archive.id)
            assertThat(current.state.value.observations.single { it.id == archive.id }).isEqualTo(archive)
            assertThat(current.state.value.storedHistory?.filterIsInstance<ChatItem.Assistant>()?.single()?.text).isEqualTo("hi")
            assertThat(current.state.value.questions).isEmpty()
            val finalTerminal = CompletableDeferred<Unit>()
            current.afterCurrentEvent = { if (it == "message.complete") finalTerminal.complete(Unit) }
            backend.input("message.complete", kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive("hi plus new resumed later")); put("status", kotlinx.serialization.json.JsonPrimitive("complete"))
            })
            withTimeout(10_000) { finalTerminal.await() }
            // Completed semantic content is retained without rebuilding a copy for every token.
            assertThat(current.state.value.observations.single { it.id == live.id }.items.filterIsInstance<ChatItem.Assistant>().single().text).isEqualTo("hi plus new resumed later")
            assertThat(current.state.value.observations.single { it.id == archive.id }).isEqualTo(archive)
            compose.waitForIdle()
            compose.onNodeWithTag("transcript").performScrollToNode(hasContentDescription(app.getString(R.string.chat_regenerate)))
            compose.onAllNodesWithContentDescription(app.getString(R.string.chat_regenerate)).assertCountEquals(1)
            assertThat(backend.prompts.get()).isEqualTo(0)
        }
    }

}
