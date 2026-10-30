package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.SessionUsage
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSubtitleTest {
    @get:Rule
    val compose = createComposeRule()

    // The name a Codex chat carries: too long to leave room for the totals beside it.
    private val codex = "codex/gpt-5.1-codex-max"
    private val priced = SessionUsage(input = 1_234_000, output = 987_000, costUsd = 12.34, included = false)

    /** The box the subtitle was given, for the tests that check nothing runs past it. */
    private var shown: Dp = 240.dp

    @Test
    fun `a short model and its totals share one line`() {
        show(300.dp, "gpt-5.2", SessionUsage(12_400, 8_200, 0.12, false))

        compose.onNodeWithText("gpt-5.2 · Chat: 12.4 K in · 8.2 K out · $0.12").assertExists()
    }

    @Test
    fun `a codex model moves the totals under itself instead of off the header`() {
        show(240.dp, codex, priced)

        compose.onNodeWithText(codex).assertExists()
        compose.onNodeWithText("Chat: 1.2 M in · 987.0 K out · $12.34").assertExists()
        assertThat(topOf("Chat: 1.2 M in")).isAtLeast(bottomOf(codex))
        assertThat(rightOf(codex)).isAtMost(width)
        assertThat(rightOf("Chat: 1.2 M in")).isAtMost(width)
    }

    @Test
    fun `a line included in the plan also says so under a long model`() {
        show(240.dp, codex, SessionUsage(2_000, 300, costUsd = null, included = true))

        compose.onNodeWithText("Chat: 2.0 K in · 300 out · included in the plan").assertExists()
        assertThat(topOf("2.0 K in")).isAtLeast(bottomOf(codex))
    }

    @Test
    fun `a name too long even for its own line gives way to the numbers`() {
        show(120.dp, "openrouter/anthropic/claude-sonnet-4.5-thinking", priced)

        compose.onNodeWithText("Chat: 1.2 M in · 987.0 K out · $12.34").assertExists()
        assertThat(topOf("Chat: 1.2 M in")).isAtLeast(bottomOf("openrouter/"))
        assertThat(rightOf("Chat: 1.2 M in")).isAtMost(width)
    }

    @Test
    fun `a model picked for the next turn says so and keeps the chat totals`() {
        show(600.dp, "gpt-5.2", SessionUsage(12_400, 8_200, 0.12, false), pending = true)

        compose.onNodeWithText("gpt-5.2 · next turn · Chat: 12.4 K in · 8.2 K out · $0.12").assertExists()
    }

    @Test
    fun `the spent line goes beside the model only while the room lasts`() {
        // A model 3 px wide, a spent line 4 and 5, a separator of 3.
        assertThat(spentBesideModel(modelWidth = 3, spentWidth = 4, separatorWidth = 3, available = 10)).isTrue()
        assertThat(spentBesideModel(modelWidth = 3, spentWidth = 5, separatorWidth = 3, available = 10)).isFalse()
    }

    /** The subtitle in a box [width] wide, the room a header of a narrow phone gives it. */
    private fun show(width: Dp, model: String?, usage: SessionUsage?, pending: Boolean = false) {
        shown = width
        compose.setContent {
            HermesTheme {
                Box(Modifier.width(width)) { ChatSubtitle(model, usage, pending) }
            }
        }
    }

    private fun topOf(text: String): Float = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top

    private fun bottomOf(text: String): Float = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.bottom

    private fun rightOf(text: String): Float = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.right

    private val width: Float get() = compose.density.run { shown.toPx() }
}
