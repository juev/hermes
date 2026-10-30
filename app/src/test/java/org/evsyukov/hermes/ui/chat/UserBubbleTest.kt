package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ChatItem
import org.evsyukov.hermes.chat.Delivery
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserBubbleTest {
    @get:Rule
    val compose = createComposeRule()

    // The shape of a cron report the server files as a user message.
    private val report = "[Cron delivery: research-daily]\n**Тема:** NixOS и Ansible.\n\n**PR:** [#10](https://github.com/juev/research/pull/10), ветка `hermes/research`."

    @Test
    fun `a user message renders its Markdown`() {
        show(report)

        assertThat(compose.onAllNodes(hasText("**", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("](https://", substring = true)).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Тема:", substring = true).assertExists()
        compose.onNodeWithText("#10", substring = true).assertExists()
    }

    @Test
    fun `a long press still copies the text as written`() {
        var copied: String? = null
        show(report) { copied = it }

        compose.onNodeWithText("NixOS", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(R.string.common_copy)).performClick()

        assertThat(copied).isEqualTo(report)
    }

    @Test
    fun `single line breaks of a user message stay`() {
        show("первая строка\nвторая строка")

        compose.onNodeWithText("первая строка\nвторая строка", substring = true).assertExists()
    }

    @Test
    fun `a message whose delivery is not confirmed says so`() {
        deliver(Delivery.UNKNOWN)

        compose.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(R.string.chat_delivery_unknown)).assertExists()
    }

    @Test
    fun `a message the server took carries no delivery note`() {
        deliver(Delivery.SENT)

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertThat(compose.onAllNodes(hasText(app.getString(R.string.chat_delivery_unknown), substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    private fun deliver(delivery: Delivery) {
        val actions = MessageActions({}, {}, {}, {}, {}, {}, { null })
        compose.setContent {
            HermesTheme { UserBubble(ChatItem.User("u1", "hello", emptyList(), delivery = delivery), last = false, actions = actions) }
        }
    }

    /** The renderer parses off the main thread, so idle is not enough: wait for the text to show. */
    private fun show(text: String, copy: (String) -> Unit = {}) {
        val actions = MessageActions(copy, {}, {}, {}, {}, {}, { null })
        compose.setContent { HermesTheme { UserBubble(ChatItem.User("u1", text, emptyList()), last = true, actions = actions) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("NixOS", substring = true) or hasText("строка", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
