package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.chat.ChatController
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.LiveGateway
import org.evsyukov.hermes.chat.LiveServer
import org.evsyukov.hermes.data.settings.Settings
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The chat screen takes state only while it is up in an app in front, and the chat behind it works on
 * its transcript only then: below STARTED, or under another screen, new values wait, and coming back
 * shows the latest at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class ChatScreenLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LiveServer.database(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = LiveGateway()
    private val server = LiveServer(gateway)

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()

    /** What the screen told about the chat, in order. */
    private val shown = mutableListOf<Boolean>()

    @After
    fun tearDown() {
        val owner = scope.coroutineContext.job
        server.finish(owner, listOf("close server" to { server.close() }, "close database" to { db.close() }))
    }

    private fun move(state: Lifecycle.State) {
        compose.runOnIdle { owner.registry.currentState = state }
        compose.waitForIdle()
    }

    private fun answer(chat: ChatController) = (chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.text

    /**
     * A chat whose turn streamed "Hello there" while it was on the screen, starting hidden as the app
     * makes it. With [hides] off the screen still reports itself but the chat stays published, so what
     * is checked is the screen's own collection.
     */
    private fun open(hides: Boolean = true, onScreen: () -> Boolean = { true }): ChatController = runBlocking {
        gateway.connect()
        gateway.start("hi")
        gateway.delta("Hello")
        val chat = ChatController(server.session(context, scope, db), gateway, scope, AppStrings(context.resources), "s1", visible = false)
        val draft = ChatDraft()
        move(Lifecycle.State.RESUMED)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HermesTheme {
                    if (onScreen()) {
                        ChatScreen(
                            controller = chat, settings = Settings(), serverName = "Home", connected = true,
                            onMenu = {}, onNewChat = {}, openFile = {}, loadFile = { null }, loadModels = { error("not opened") },
                            openLocal = {}, onShown = { c, visible -> shown += visible; if (hides || visible) c.setVisible(visible) }, draft = draft,
                        )
                    } else {
                        Text("Settings")
                    }
                }
            }
        }
        withTimeout(5_000) { chat.state.first { (it.items.lastOrNull() as? ChatItem.Assistant)?.text == "Hello" && !it.loading } }
        gateway.delta(" there")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Hello there", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        chat
    }

    /** Sends a new title and waits until the chat has taken it, published or not. */
    private suspend fun rename(chat: ChatController, title: String) {
        gateway.event("session.title", buildJsonObject { put("session_id", "s1"); put("title", title) })
        gateway.deliver()
        withTimeout(5_000) { while (chat.working.title != title) delay(10) }
    }

    @Test
    fun `in the background the screen takes no new state, and back in front it shows the latest`() {
        // The chat stays published here: what the screen shows depends on its own collection alone.
        val chat = open(hides = false)

        move(Lifecycle.State.CREATED)
        runBlocking {
            rename(chat, "Renamed")
            withTimeout(5_000) { chat.state.first { it.title == "Renamed" } }
        }
        compose.waitForIdle()
        // The chat published a new title, and the screen in the background has not taken it.
        compose.onNodeWithText("Renamed").assertDoesNotExist()

        move(Lifecycle.State.STARTED)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Renamed")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a chat in the background publishes nothing, and back in front shows the latest without asking the server`() {
        val chat = open()

        move(Lifecycle.State.CREATED)
        val hidden = chat.state.value
        val calls = gateway.calls.size
        val requests = server.http.requestCount
        runBlocking {
            gateway.delta(" and more")
            rename(chat, "Renamed")
        }
        compose.waitForIdle()

        // The chat has both, the screen's copy has neither.
        assertThat(chat.state.value).isSameInstanceAs(hidden)
        compose.onNodeWithText("Renamed").assertDoesNotExist()

        move(Lifecycle.State.STARTED)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Hello there and more", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Renamed").assertExists()
        assertThat(shown).containsExactly(true, false, true).inOrder()
        assertThat(gateway.calls.size).isEqualTo(calls)
        assertThat(server.http.requestCount).isEqualTo(requests)
    }

    @Test
    fun `a screen over the chat stops it, and the chat shows the latest when it is back`() {
        var onScreen by mutableStateOf(true)
        val chat = open { onScreen }

        compose.runOnIdle { onScreen = false }
        compose.waitForIdle()
        val covered = chat.state.value
        runBlocking {
            gateway.delta(" under settings")
            rename(chat, "Covered")
        }
        assertThat(chat.state.value).isSameInstanceAs(covered)

        compose.runOnIdle { onScreen = true }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Hello there under settings", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(answer(chat)).isEqualTo("Hello there under settings")
        assertThat(shown).containsExactly(true, false, true).inOrder()
    }
}
