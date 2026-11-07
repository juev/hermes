package org.evsyukov.hermes.ui.chat

import android.app.Application
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The names, roles and states that TalkBack reads from the composer controls. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val draftText = mutableStateOf("")
    private val draftAttachments = mutableStateOf<List<LocalAttachment>>(emptyList())

    private fun composerField() = compose.onNode(hasSetTextAction())

    private fun assertButton(label: Int) = compose.onNodeWithContentDescription(app.getString(label))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    @Test
    fun `the empty field keeps a localized name and its editable semantics`() {
        show("")

        val label = app.getString(R.string.composer_hint)
        val field = composerField().fetchSemanticsNode()
        assertThat(field.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(label)
        assertThat(field.config.getOrNull(SemanticsProperties.EditableText)?.text).isEmpty()
        assertThat(field.config.contains(SemanticsActions.SetText)).isTrue()
        // The visible hint is decoration, not a second text node with the same purpose.
        compose.onAllNodesWithText(label).assertCountEquals(0)
    }

    @Test
    fun `a filled field keeps the same name and still carries its value`() {
        show("hello")

        val label = app.getString(R.string.composer_hint)
        val field = composerField().fetchSemanticsNode()
        assertThat(field.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(label)
        assertThat(field.config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("hello")
        assertThat(field.config.contains(SemanticsActions.SetText)).isTrue()
        compose.onAllNodesWithText(label).assertCountEquals(0)
    }

    @Test
    fun `send is a button and stays disabled without words or files`() {
        show("")

        assertButton(R.string.common_send).assertIsNotEnabled()
    }

    @Test
    fun `send is an enabled button when the draft has words`() {
        show("hello")

        assertButton(R.string.common_send).assertIsEnabled()
    }

    @Test
    fun `a running turn exposes stop and queue as distinct buttons`() {
        show("follow-up", running = true)

        assertButton(R.string.composer_stop).assertIsEnabled()
        assertButton(R.string.composer_queue).assertIsEnabled()
        assertThat(app.getString(R.string.composer_stop)).isNotEqualTo(app.getString(R.string.composer_queue))
    }

    @Test
    fun `remove is a button next to every pending file`() {
        show("", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))))

        assertButton(R.string.composer_remove)
    }

    @Test
    fun `the attach control is a button`() {
        show("")

        assertButton(R.string.composer_attach)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp")
    fun `in Russian the field name and the action descriptions are localized`() {
        show("привет", running = true)

        val label = app.getString(R.string.composer_hint)
        assertThat(label).isEqualTo("Спросите Hermes")
        val field = composerField().fetchSemanticsNode()
        assertThat(field.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(label)
        assertThat(field.config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("привет")
        assertThat(app.getString(R.string.composer_stop)).isEqualTo("Остановить")
        assertThat(app.getString(R.string.composer_queue)).isEqualTo("Добавить в очередь")
        assertButton(R.string.composer_stop)
        assertButton(R.string.composer_queue)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    fun `the field name survives an enlarged font`() {
        show("larger text")

        val label = app.getString(R.string.composer_hint)
        val field = composerField().fetchSemanticsNode()
        assertThat(field.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(label)
        assertThat(field.config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("larger text")
        assertButton(R.string.common_send)
    }

    private fun show(text: String, attachments: List<LocalAttachment> = emptyList(), running: Boolean = false) {
        draftText.value = text
        draftAttachments.value = attachments
        compose.setContent {
            HermesTheme {
                Box(Modifier.testTag("composer")) {
                    Composer(
                        text = draftText.value, onText = { draftText.value = it },
                        attachments = draftAttachments.value, onAttachments = { draftAttachments.value = it },
                        running = running, enterSends = false, onSend = {}, onStop = {},
                        onError = {}, onOpen = {},
                    )
                }
            }
        }
    }

    private fun frame(name: String) {
        val directory = System.getenv("HERMES_123_VISUAL_DIR") ?: return
        val output = File(directory).apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            FileOutputStream(File(output, name + ".png")).use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        println("FRAME $name root=${compose.onRoot().fetchSemanticsNode().boundsInRoot}")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 1.0f)
    fun `actual 360 dp en scale 1 frame`() {
        show("follow-up / продолжение", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))), running = true)
        assertButton(R.string.composer_stop)
        assertButton(R.string.composer_queue)
        frame("ComposerSemanticsTest-en-1")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    fun `actual 360 dp en scale 2 frame`() {
        show("follow-up / продолжение", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))), running = true)
        assertButton(R.string.composer_stop)
        assertButton(R.string.composer_queue)
        frame("ComposerSemanticsTest-en-2")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp", fontScale = 1.0f)
    fun `actual 360 dp ru scale 1 frame`() {
        show("follow-up / продолжение", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))), running = true)
        assertButton(R.string.composer_stop)
        assertButton(R.string.composer_queue)
        frame("ComposerSemanticsTest-ru-1")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp", fontScale = 2.0f)
    fun `actual 360 dp ru scale 2 frame`() {
        show("follow-up / продолжение", listOf(LocalAttachment("report.pdf", "application/pdf", ByteArray(1))), running = true)
        assertButton(R.string.composer_stop)
        assertButton(R.string.composer_queue)
        frame("ComposerSemanticsTest-ru-2")
    }
}
