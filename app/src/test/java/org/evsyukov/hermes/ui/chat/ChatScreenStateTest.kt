package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.center
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.chat.closeWithOwnedJobs
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A screen over the chat takes the chat screen out of the composition. What the user prepared and the
 * place they read at belong to the chat, so they are there when the screen comes back, and another
 * chat has its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class ChatScreenStateTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)
    private val strings = AppStrings(app.resources)

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        server.finish(owner, listOf("close server" to { server.close() }, "close database" to { db.close() }))
    }

    private fun chat(id: String?) = ChatController(server.session(app, scope, db), gateway, scope, strings, id)

    @androidx.compose.runtime.Composable
    private fun Screen(controller: ChatController, draft: ChatDraft) = ChatScreen(
        controller = controller, settings = Settings(), serverName = "Home", connected = true,
        onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null }, loadModels = { error("not opened") },
        openLocal = {}, onShown = { c, visible -> c.setVisible(visible) }, draft = draft,
    )

    private fun field(text: String) = compose.onNode(hasSetTextAction() and hasText(text))

    /** A live chat with a turn started on the fake server, as the gateway has it after the first message. */
    private suspend fun runningChat(): ChatController {
        gateway.connect()
        val adopted = CompletableDeferred<Unit>()
        val chat = ChatController(server.session(app, scope, db), gateway, scope, strings, "s1",
            onLive = { _, _, _ -> adopted.complete(Unit) })
        withTimeout(5_000) { adopted.await() }
        // The chat's own join has been applied: the history is on the screen and the live model is
        // known. `!loading` alone is true before the load even starts.
        withTimeout(5_000) { chat.state.first { it.model == "live-model" && !it.loading } }
        chat.send("first", emptyList())
        withTimeout(5_000) { while (gateway.count("prompt.submit") < 1) delay(10) }
        // A recorded submit is not a running server: the fake starts the turn and the chat takes its
        // start, which opens the streaming answer under the message.
        gateway.start("first")
        withTimeout(5_000) { chat.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.streaming == true } }
        assertThat(chat.state.value.running).isTrue()
        return chat
    }

    @Test
    fun `the words, the files and the place in a chat survive a screen over it`() {
        val chat = runBlocking {
            gateway.connect()
            repeat(30) {
                gateway.start("question $it")
                gateway.delta("answer $it")
                gateway.complete()
            }
            chat("s1").also { c -> withTimeout(5_000) { c.state.first { it.items.size == 60 && !it.loading } } }
        }
        val draft = ChatDraft().apply {
            attachments = listOf(LocalAttachment("first.pdf", "application/pdf", ByteArray(1)), LocalAttachment("second.pdf", "application/pdf", ByteArray(1)))
        }
        var onScreen by mutableStateOf(true)
        compose.setContent { HermesTheme { if (onScreen) Screen(chat, draft) else Text("Settings") } }

        compose.onNode(hasSetTextAction()).performTextInput("half a thought")
        compose.onAllNodesWithContentDescription(app.getString(R.string.composer_remove))[0].performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        compose.onNodeWithContentDescription(app.getString(R.string.chat_scroll_down)).assertExists()

        compose.runOnIdle { onScreen = false }
        compose.onNodeWithText("Settings").assertExists()
        compose.runOnIdle { onScreen = true }
        compose.waitForIdle()

        field("half a thought").assertExists()
        compose.onNodeWithText("second.pdf").assertExists()
        compose.onNodeWithText("first.pdf").assertDoesNotExist()
        // Still up in the history: the button to the end shows and the last answer is out of view.
        compose.onNodeWithContentDescription(app.getString(R.string.chat_scroll_down)).assertExists()
        compose.onNodeWithText("answer 29", substring = true).assertDoesNotExist()
    }

    @Test
    fun `another chat has its own field, and the first one gets its words back`() {
        // New chats on a gateway that is not connected: nothing to load, only the screen.
        val first = chat(null)
        val second = chat(null)
        val drafts = mapOf(first to ChatDraft(), second to ChatDraft())
        var current by mutableStateOf(first)
        compose.setContent { HermesTheme { Screen(current, drafts.getValue(current)) } }

        compose.onNode(hasSetTextAction()).performTextInput("for the first chat")
        compose.runOnIdle { current = second }
        compose.waitForIdle()
        field("for the first chat").assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.composer_hint)).assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("for the second")
        compose.runOnIdle { current = first }
        compose.waitForIdle()

        field("for the first chat").assertExists()
        field("for the second").assertDoesNotExist()
    }

    @Test
    fun `stop keeps the follow-up and sends nothing while a turn runs`() = runBlocking<Unit> {
        val chat = runningChat()
        val draft = ChatDraft().apply {
            text = "follow-up"
            attachments = listOf(LocalAttachment("note.pdf", "application/pdf", ByteArray(1)))
        }
        compose.setContent { HermesTheme { Screen(chat, draft) } }
        val submitted = gateway.count("prompt.submit")
        assertThat(submitted).isEqualTo(1)

        compose.onNodeWithContentDescription(app.getString(R.string.composer_stop)).performTouchInput { click(center) }
        compose.waitForIdle()
        withTimeout(5_000) { chat.state.first { !it.running } }

        assertThat(gateway.count("prompt.submit")).isEqualTo(submitted)
        val interrupts = gateway.calls.filter { it.first == "session.interrupt" }
        assertThat(interrupts).hasSize(1)
        assertThat(interrupts.single().second.string("session_id")).isEqualTo("live1")
        assertThat(draft.text).isEqualTo("follow-up")
        assertThat(draft.attachments.map { it.name }).containsExactly("note.pdf")
        field("follow-up").assertExists()
        compose.onNodeWithText("note.pdf").assertExists()
        chat.close()
    }

    @Test
    fun `queue submits the follow-up once and clears it while a turn runs`() = runBlocking<Unit> {
        val chat = runningChat()
        val draft = ChatDraft().apply { text = "follow-up" }
        compose.setContent { HermesTheme { Screen(chat, draft) } }
        val submitted = gateway.count("prompt.submit")
        assertThat(submitted).isEqualTo(1)

        compose.onNodeWithContentDescription(app.getString(R.string.composer_queue)).performClick()
        withTimeout(5_000) { while (gateway.count("prompt.submit") < submitted + 1) delay(10) }
        compose.waitForIdle()

        assertThat(gateway.count("prompt.submit")).isEqualTo(submitted + 1)
        assertThat(draft.text).isEmpty()
        chat.close()
    }

    @Test
    fun `sending clears only the draft of the chat it goes from`() {
        val memory = ChatDraftMemory()
        val first = chat(null)
        val second = chat(null)
        val drafts = mapOf(first to checkNotNull(memory.obtain(DraftKey.NewChat)), second to checkNotNull(memory.obtain(DraftKey.Chat("s2"))))
        var current by mutableStateOf(first)
        compose.setContent { HermesTheme { Screen(current, drafts.getValue(current)) } }

        compose.onNode(hasSetTextAction()).performTextInput("send me")
        compose.onNodeWithContentDescription(app.getString(R.string.common_send)).performClick()
        compose.runOnIdle { assertThat(drafts.getValue(first).text).isEmpty() }

        compose.runOnIdle { current = second }
        compose.waitForIdle()
        field("send me").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("keep me")
        compose.runOnIdle { current = first }
        compose.waitForIdle()

        field("send me").assertDoesNotExist()
        compose.runOnIdle { assertThat(drafts.getValue(second).text).isEqualTo("keep me") }
    }
}
