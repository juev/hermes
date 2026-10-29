package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.Invocation
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServiceMessageViewsTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = MessageActions({}, {}, {}, {}, {}, {}, { null })

    @Test
    fun `a cron report shows the name of its job and its text without the prefix`() {
        compose.setContent { HermesTheme { CronReportMessage(ChatItem.CronReport("c1", "hermes-experiment", "Новых гипотез **нет**.")) } }
        waitFor("Новых гипотез")

        compose.onNodeWithText("hermes-experiment").assertExists()
        assertThat(compose.onAllNodes(hasText("Cron delivery", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("**", substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `the chip of a cron run opens the text the model got`() {
        val full = "[IMPORTANT: You are running as a scheduled cron job.]\n\nОпубликуй статью."
        val item = ChatItem.User("u1", "", emptyList(), invocation = Invocation(cron = true, skills = listOf("research-daily"), full = full))
        compose.setContent { HermesTheme { UserBubble(item, last = true, actions = actions) } }
        waitFor("research-daily")

        assertThat(compose.onAllNodes(hasText("Опубликуй статью", substring = true)).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("research-daily", substring = true).performClick()
        waitFor("Опубликуй статью")
    }

    @Test
    fun `a reply shows the message it answers as a quote above the text`() {
        val item = ChatItem.User("u1", "Видно", emptyList(), quote = "Skill 'hermes-agent' patched")
        compose.setContent { HermesTheme { UserBubble(item, last = true, actions = actions) } }
        waitFor("Видно")

        compose.onNodeWithText("Skill 'hermes-agent' patched", substring = true).assertExists()
        assertThat(compose.onAllNodes(hasText("Replying to", substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    /** The renderer parses off the main thread, so idle is not enough: wait for [text] to show. */
    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
}
