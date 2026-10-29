package org.evsyukov.hermes.ui.common

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
// Legacy graphics measure every glyph as a pixel wide, so no text would ever wrap.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTableTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `cells and headers wrap their text instead of cutting it`() {
        val header = "a header long enough to need several lines on a phone screen"
        val cell = "a cell long enough to need several lines on a phone screen"
        show("| Name | $header |\n|---|---|\n| x | $cell |", until = cell)
        for (text in listOf(header, cell)) {
            val layout = layoutOf(text)
            assertThat(layout.lineCount).isGreaterThan(1)
            assertThat(layout.hasVisualOverflow).isFalse()
        }
    }

    @Test
    fun `four columns fit the screen without scrolling sideways`() {
        show("| a | b | c | d |\n|---|---|---|---|\n| one | two | three | four |", until = "four")
        assertThat(sidewaysScrolls()).isEqualTo(0)
    }

    @Test
    fun `a table too wide for readable columns scrolls sideways`() {
        val header = (1..8).joinToString(" | ", "| ", " |") { "col$it" }
        val rule = (1..8).joinToString("|", "|", "|") { "---" }
        show("$header\n$rule\n" + (1..8).joinToString(" | ", "| ", " |") { "v$it" }, until = "v8")
        assertThat(sidewaysScrolls()).isEqualTo(1)
    }

    /** The renderer parses off the main thread, so idle is not enough: wait for [until] to show. */
    private fun show(markdown: String, until: String) {
        compose.setContent { HermesTheme { MarkdownText(markdown) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(until, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, substring = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun sidewaysScrolls() = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        .fetchSemanticsNodes().size
}
