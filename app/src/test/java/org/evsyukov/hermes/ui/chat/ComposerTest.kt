package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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

    private var sent = 0
    private var stopped = 0

    @Test
    fun `the empty composer is one row of attach, text and send`() {
        show("")

        val attach = bounds(R.string.composer_attach)
        val field = field()
        val send = bounds(R.string.common_send)
        assertThat(attach.right.value).isAtMost(field.left.value)
        assertThat(field.right.value).isAtMost(send.left.value)
        listOf(field, send).forEach { assertThat(it.centerY).isWithin(1f).of(attach.centerY) }
    }

    @Test
    fun `the composer has no dictation button and no room left for one`() {
        show("")

        // The buttons of the row are the attach menu and send; the field is the only other control.
        val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { SemanticsActions.SetText !in it.config }
        assertThat(buttons.map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.single() })
            .containsExactly(app.getString(R.string.composer_attach), app.getString(R.string.common_send))
        compose.onNodeWithContentDescription("Dictation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Finish dictation").assertDoesNotExist()
        // Between the field and send: the field's own 4 dp and the 4 dp spacer, no empty button slot.
        assertThat(bounds(R.string.common_send).left.value - field().right.value).isAtMost(12f)
    }

    @Test
    fun `the empty composer is no taller than a line of text and a button`() {
        show("")

        // The frame around the row: 8 dp outer and 6 dp inner padding on each side, plus a 1 dp border.
        val frame = 2 * (8 + 6 + 1)
        assertThat(compose.onNodeWithTag("composer").getBoundsInRoot().height.value).isAtMost(frame + 22f + 48f)
    }

    @Test
    fun `the placeholder sits in the row between the attach and send buttons`() {
        show("")

        val hint = compose.onNodeWithText(app.getString(R.string.composer_hint)).getBoundsInRoot()
        assertThat(hint.left.value).isAtLeast(bounds(R.string.composer_attach).right.value)
        assertThat(hint.right.value).isAtMost(bounds(R.string.common_send).left.value)
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

    @Test
    fun `send waits for words or a file`() {
        show("")
        compose.onNodeWithContentDescription(app.getString(R.string.common_send)).assertIsNotEnabled()
    }

    @Test
    fun `typed words go with send and with the send key of the keyboard`() {
        show("hello", enterSends = true)

        compose.onNodeWithContentDescription(app.getString(R.string.common_send)).assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction()).performImeAction()

        assertThat(sent).isEqualTo(2)
    }

    @Test
    fun `a file alone can be sent`() {
        show("", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))))

        compose.onNodeWithContentDescription(app.getString(R.string.common_send)).assertIsEnabled().performClick()

        assertThat(sent).isEqualTo(1)
    }

    @Test
    fun `a running turn with an empty field offers stop`() {
        show("", running = true)

        compose.onNodeWithContentDescription(app.getString(R.string.composer_stop)).performClick()

        assertThat(stopped).isEqualTo(1)
        assertThat(sent).isEqualTo(0)
    }

    @Test
    fun `words typed while a turn runs are queued, not sent by the keyboard`() {
        show("next", running = true, enterSends = true)

        compose.onNodeWithContentDescription(app.getString(R.string.composer_stop)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.composer_queue)).performClick()
        // The send key does not send over a running turn: only the queue button does.
        compose.onNode(hasSetTextAction()).performImeAction()

        assertThat(sent).isEqualTo(1)
        assertThat(stopped).isEqualTo(0)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `the row fits a narrow phone`() {
        show("a few words", running = true)

        val composer = compose.onNodeWithTag("composer").getBoundsInRoot()
        val queue = bounds(R.string.composer_queue)
        assertThat(queue.right.value).isAtMost(composer.right.value)
        assertThat(field().right.value).isAtMost(queue.left.value)
        assertThat(bounds(R.string.composer_attach).right.value).isAtMost(field().left.value)
    }

    private val DpRect.centerY get() = (top.value + bottom.value) / 2

    private fun field() = compose.onNode(hasSetTextAction()).getBoundsInRoot()

    private fun bounds(label: Int) = compose.onNodeWithContentDescription(app.getString(label)).getBoundsInRoot()

    private fun show(text: String, attachments: List<LocalAttachment> = emptyList(), running: Boolean = false, enterSends: Boolean = false) {
        compose.setContent {
            HermesTheme {
                Box(Modifier.testTag("composer")) {
                    Composer(
                        text = text, onText = {}, attachments = attachments, onAttachments = {},
                        running = running, enterSends = enterSends, onSend = { sent++ }, onStop = { stopped++ },
                        onError = {}, onOpen = {},
                    )
                }
            }
        }
    }
}
