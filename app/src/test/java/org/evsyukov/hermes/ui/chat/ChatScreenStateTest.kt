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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.chat.LocalAttachment
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
        scope.cancel()
        server.close()
        db.close()
    }

    private fun chat(id: String?) = ChatController(server.session(app, scope, db), gateway, scope, strings, id)

    @androidx.compose.runtime.Composable
    private fun Screen(controller: ChatController, draft: ChatDraft) = ChatScreen(
        controller = controller, settings = Settings(), serverName = "Home", connected = true,
        onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null }, loadModels = { error("not opened") },
        openLocal = {}, onShown = { c, visible -> c.setVisible(visible) }, draft = draft,
    )

    private fun field(text: String) = compose.onNode(hasSetTextAction() and hasText(text))

    /** A live chat with a turn already submitted, as the gateway has it after the first message. */
    private suspend fun runningChat(): ChatController {
        gateway.connect()
        val chat = chat("s1")
        withTimeout(5_000) { chat.state.first { !it.loading } }
        chat.send("first", emptyList())
        withTimeout(5_000) { chat.state.first { it.running } }
        withTimeout(5_000) { while (gateway.count("prompt.submit") < 1) delay(10) }
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
        compose.onNodeWithText(app.getString(R.string.composer_hint)).assertExists()

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

        compose.onNodeWithContentDescription(app.getString(R.string.composer_stop)).performClick()
        compose.waitForIdle()
        withTimeout(5_000) { chat.state.first { !it.running } }

        assertThat(gateway.count("prompt.submit")).isEqualTo(submitted)
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

        compose.onNodeWithContentDescription(app.getString(R.string.composer_queue)).performClick()
        withTimeout(5_000) { while (gateway.count("prompt.submit") < submitted + 1) delay(10) }
        compose.waitForIdle()

        assertThat(gateway.count("prompt.submit")).isEqualTo(submitted + 1)
        assertThat(draft.text).isEmpty()
        chat.close()
    }
}
