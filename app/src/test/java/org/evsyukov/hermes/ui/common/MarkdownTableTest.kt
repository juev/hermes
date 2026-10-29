package org.evsyukov.hermes.ui.common

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
    fun `a table whose words do not fit the screen scrolls sideways`() {
        val header = (1..8).joinToString(" | ", "| ", " |") { "column$it" }
        val rule = (1..8).joinToString("|", "|", "|") { "---" }
        show("$header\n$rule\n" + (1..8).joinToString(" | ", "| ", " |") { "производительность$it" }, until = "производительность8")
        assertThat(sidewaysScrolls()).isEqualTo(1)
    }

    @Test
    fun `eight short columns fit the screen`() {
        val header = (1..8).joinToString(" | ", "| ", " |") { "c$it" }
        val rule = (1..8).joinToString("|", "|", "|") { "---" }
        show("$header\n$rule\n" + (1..8).joinToString(" | ", "| ", " |") { "v$it" }, until = "v8")
        assertThat(sidewaysScrolls()).isEqualTo(0)
    }

    @Test
    fun `a column of short words leaves its room to the columns with more text`() {
        val long = "Производительное или чувствительное к безопасности ядро: обработка данных, криптография, общая логика"
        show("| Язык | Где полезен на Android |\n|---|---|\n| Rust | $long |", until = "криптография")
        // Equal columns would give the long text half of the screen.
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertThat(boundsOf(long).width).isGreaterThan(screen * 0.7f)
    }

    @Test
    fun `a word is not broken inside a narrow column`() {
        val long = "Производительное или чувствительное к безопасности ядро"
        show("| Язык | Где полезен | Почему реже для всего приложения |\n|---|---|---|\n| Rust | $long | Для доступа к Android API и UI обычно нужен мост через JNI |", until = "JNI")
        val layout = layoutOf(long)
        // Every line ends at a space or at the end of the text, not inside a word.
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertThat(long[end - 1].isWhitespace() || long.getOrNull(end)?.isWhitespace() == true).isTrue()
        }
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

    private fun boundsOf(text: String) = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot

    private fun sidewaysScrolls() = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        .fetchSemanticsNodes().size
}
