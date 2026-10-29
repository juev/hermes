package org.evsyukov.hermes.ui.admin

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
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
@Config(sdk = [36], qualifiers = "ru-w360dp-h800dp", application = Application::class)
// Legacy graphics measure every glyph as a pixel wide, so no label would ever wrap.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GatewayButtonsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `long labels stay on one line and every button fits the screen`() {
        compose.setContent { HermesTheme { GatewayButtons(onRestart = {}, onStart = {}, onStop = {}) } }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (label in listOf("Перезапустить", "Запустить", "Остановить")) {
            val node = compose.onNodeWithText(label).fetchSemanticsNode()
            assertThat(layoutOf(label).lineCount).isEqualTo(1)
            assertThat(node.boundsInRoot.right).isAtMost(screen.right)
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }
}
