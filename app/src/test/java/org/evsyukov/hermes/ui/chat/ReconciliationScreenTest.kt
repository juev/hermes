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
    private fun show(current: ChatController) {
        compose.setContent { HermesTheme { ChatScreen(current, Settings(), "Synthetic", true, {}, {}, {}, { null }, { error("not requested") }, {}, { c, visible -> c.setVisible(visible) }, remember { ChatDraft() }) } }
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
            compose.onAllNodes(hasText(app.getString(R.string.chat_local_overlap))).assertCountEquals(0)
            compose.onAllNodes(hasText(app.getString(R.string.chat_local_copy))).assertCountEquals(0)
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("done", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("done", substring = true))
            compose.onNodeWithText("done", substring = true).assertIsDisplayed()
            compose.onAllNodes(hasText("full tool result", substring = true)).assertCountEquals(0)
            assertThat(current.state.value.observations.single().frames).isNotEmpty()
            assertThat(backend.prompts.get()).isEqualTo(1)
        }
    }
    @Test fun `starting another turn does not hide the previous local conversation`() {
        val fixture = FirstHistoryReconciliationTest()
        fixture.withScreenController({}) { current, backend ->
            check(backend.firstEntered.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { checkNotNull(current.loadCompletion).await() }
            for (prompt in listOf("first local prompt", "second local prompt")) {
                val terminal = CompletableDeferred<Unit>()
                current.afterCurrentEvent = { if (it == "message.complete") terminal.complete(Unit) }
                current.send(prompt, emptyList())
                withTimeout(10_000) { terminal.await() }
            }
            show(current)
            // Markdown parsing is asynchronous; all these short rows fit in this viewport.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("first local prompt")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("first local prompt"))
            compose.onNodeWithText("first local prompt").assertIsDisplayed()
            assertThat(current.state.value.observations).hasSize(2)
            assertThat(backend.prompts.get()).isEqualTo(2)
        }
    }
    @Test fun `refresh shows server history and keeps the unmatched reply only in recovery`() {
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
            compose.onAllNodes(hasText(app.getString(R.string.chat_local_overlap))).assertCountEquals(0)
            compose.onAllNodes(hasText("done")).assertCountEquals(0)
            compose.onNodeWithTag("transcript").performScrollToNode(hasText("hi"))
            compose.onNodeWithText("hi").assertIsDisplayed()
            assertThat(current.state.value.observations.single().items).isEqualTo(retained)
            assertThat(current.state.value.observations.single().reconciled).isFalse()
            compose.onNodeWithTag("transcript").performScrollToNode(hasTestTag("local-copies"))
            compose.onNodeWithTag("local-copies").performTouchInput { click(center) }
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
