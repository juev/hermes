package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the empty composer is one row of attach, text, dictation and send`() {
        show("")

        val attach = bounds(R.string.composer_attach)
        val field = field()
        val mic = bounds(R.string.composer_dictate)
        val send = bounds(R.string.common_send)
        assertThat(attach.right.value).isAtMost(field.left.value)
        assertThat(field.right.value).isAtMost(mic.left.value)
        assertThat(mic.right.value).isAtMost(send.left.value)
        listOf(field, mic, send).forEach { assertThat(it.centerY).isWithin(1f).of(attach.centerY) }
    }

    @Test
    fun `the empty composer is no taller than a line of text and a button`() {
        show("")

        // The frame around the row: 8 dp outer and 6 dp inner padding on each side, plus a 1 dp border.
        val frame = 2 * (8 + 6 + 1)
        assertThat(compose.onNodeWithTag("composer").getBoundsInRoot().height.value).isAtMost(frame + 22f + 48f)
    }

    @Test
    fun `the placeholder sits in the row between the attach and dictation buttons`() {
        show("")

        val hint = compose.onNodeWithText(app.getString(R.string.composer_hint)).getBoundsInRoot()
        assertThat(hint.left.value).isAtLeast(bounds(R.string.composer_attach).right.value)
        assertThat(hint.right.value).isAtMost(bounds(R.string.composer_dictate).left.value)
    }

    @Test
    fun `a long text grows the row and keeps the buttons next to its last line`() {
        show((1..5).joinToString("\n") { "строка $it" })

        val field = field()
        val send = bounds(R.string.common_send)
        assertThat(field.height.value).isGreaterThan(4 * 22f)
        assertThat(send.top.value).isGreaterThan(field.top.value + 2 * 22f)
        // The middle of the last line, 22 dp high.
        assertThat(send.centerY).isWithin(2f).of(field.bottom.value - 11f)
        assertThat(bounds(R.string.composer_attach).centerY).isWithin(1f).of(send.centerY)
    }

    @Test
    fun `pending attachments stay in a strip above the row`() {
        show("", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))))

        val chip = compose.onNodeWithText("report.pdf").getBoundsInRoot()
        assertThat(chip.bottom.value).isAtMost(field().top.value)
        assertThat(chip.bottom.value).isAtMost(bounds(R.string.composer_attach).top.value)
    }

    private val DpRect.centerY get() = (top.value + bottom.value) / 2

    private fun field() = compose.onNode(hasSetTextAction()).getBoundsInRoot()

    private fun bounds(label: Int) = compose.onNodeWithContentDescription(app.getString(label)).getBoundsInRoot()

    private fun show(text: String, attachments: List<LocalAttachment> = emptyList()) {
        compose.setContent {
            HermesTheme {
                Box(Modifier.testTag("composer")) {
                    Composer(
                        text = text, onText = {}, attachments = attachments, onAttachments = {},
                        running = false, enterSends = false, onSend = {}, onStop = {},
                        transcribe = { _, _ -> "" }, onError = {}, onOpen = {},
                    )
                }
            }
        }
    }
}
