package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.Part
import org.evsyukov.hermes.chat.ToolRun
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkLineTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = MessageActions({}, {}, {}, {}, {}, {}, { null })

    private fun call(id: String, name: String) = Part.Tool(ToolRun(id, name, "ls -la", "{}", done = true))

    private fun show(vararg parts: Part) = compose.setContent {
        HermesTheme { AssistantMessage(ChatItem.Assistant("a1", parts.toList(), streaming = false), last = false, showReasoning = false, status = null, actions = actions) }
    }

    @Test
    fun `a turn with a call and a reasoning shows one line and keeps the reasoning until it is opened`() {
        show(Part.Reasoning("Сначала посмотрю каталог"), call("c1", "terminal"), Part.Text("В каталоге три файла."))
        waitFor("В каталоге три файла")

        val lines = compose.onAllNodes(hasText("tool run", substring = true)).fetchSemanticsNodes()
        assertThat(lines).hasSize(1)
        compose.onNodeWithText("1 tool run · terminal · reasoning").assertExists()
        assertThat(compose.onAllNodes(hasText("Сначала посмотрю", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("ls -la", substring = true)).fetchSemanticsNodes()).isEmpty()

        compose.onNodeWithText("1 tool run", substring = true).performClick()
        compose.onNodeWithText("Terminal · ls -la").assertExists()
        compose.onNodeWithText("Reasoning").performClick()
        waitFor("Сначала посмотрю каталог")
    }

    @Test
    fun `a turn with two calls counts them on one line`() {
        show(call("c1", "terminal"), call("c2", "read_file"), Part.Text("Готово"))
        waitFor("Готово")

        compose.onNodeWithText("2 tool runs · terminal, read_file").assertExists()
    }

    @Test
    fun `a turn with four calls names three of them and counts the rest`() {
        show(call("c1", "read_file"), call("c2", "terminal"), call("c3", "terminal"), Part.Reasoning("r"), call("c4", "web_search"), Part.Text("Готово"))
        waitFor("Готово")

        compose.onNodeWithText("4 tool runs · read_file, terminal, terminal +1 · reasoning").assertExists()
    }

    /** The renderer parses off the main thread, so idle is not enough: wait for [text] to show. */
    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
}
