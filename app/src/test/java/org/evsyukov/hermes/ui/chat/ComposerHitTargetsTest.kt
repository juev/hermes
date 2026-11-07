package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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

/**
 * Effective hit testing around the pending attachments. The remove control owns a real 48 dp slot
 * beside the preview, so its taps never compete with opening the file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerHitTargetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val attachments = mutableStateOf<List<LocalAttachment>>(emptyList())
    private val opened = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var submitted = 0

    private val reportName = "a-very-long-attachment-name-that-fills-the-chip.pdf"

    private fun file(name: String) = LocalAttachment(name, "application/pdf", ByteArray(1))
    private fun image(name: String) = LocalAttachment(name, "image/png", ByteArray(1))

    private fun show(files: List<LocalAttachment>) {
        attachments.value = files
        compose.setContent {
            HermesTheme {
                Box(Modifier.testTag("composer")) {
                    Composer(
                        text = "", onText = {},
                        attachments = attachments.value,
                        onAttachments = { next ->
                            removed += attachments.value.filter { old -> next.none { it === old } }.map { it.name }
                            attachments.value = next
                        },
                        running = false, enterSends = false, onSend = { submitted++ }, onStop = {},
                        onError = {}, onOpen = { opened += it.name },
                    )
                }
            }
        }
    }

    private fun preview(name: String) = compose.onNodeWithText(name)

    /** Injects a tap [offsetDp] to the right of the preview's right edge, on its vertical centre. */
    private fun SemanticsNodeInteraction.tapRightOfPreview(offsetDp: Float) = performTouchInput {
        click(Offset(width + offsetDp.dp.toPx(), height / 2f))
    }

    private fun removeTarget() = compose.onNodeWithContentDescription(app.getString(R.string.composer_remove))

    @Test
    fun `normal centre tap removes the file with a named button and effective 48 dp bounds`() {
        show(listOf(file(reportName)))
        val target = removeTarget()
        target.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val node = target.fetchSemanticsNode()
        val touch = node.touchBoundsInRoot
        val density = app.resources.displayMetrics.density
        println("REMOVE layout=${node.boundsInRoot} touch=$touch density=$density")
        assertThat(touch.width / density).isAtLeast(48f)
        assertThat(touch.height / density).isAtLeast(48f)
        target.performTouchInput { click(center) }
        assertThat(removed).containsExactly(reportName)
        assertThat(opened).isEmpty()
        assertThat(submitted).isEqualTo(0)
        assertThat(attachments.value).isEmpty()
    }

    @Test
    fun `a tap in the reserved space beside a file removes it`() {
        show(listOf(file(reportName)))

        preview(reportName).tapRightOfPreview(24f)

        assertThat(removed).containsExactly(reportName)
        assertThat(opened).isEmpty()
        assertThat(submitted).isEqualTo(0)
        assertThat(attachments.value).isEmpty()
    }

    @Test
    fun `the remove target reserves 48 dp of layout`() {
        show(listOf(file(reportName)))

        val bounds = removeTarget().getBoundsInRoot()
        assertThat(bounds.width.value).isAtLeast(48f)
        assertThat(bounds.height.value).isAtLeast(48f)
    }

    @Test
    fun `taps at the near and far edges of the remove target both remove the file`() {
        show(listOf(file(reportName)))
        preview(reportName).tapRightOfPreview(2f)
        assertThat(removed).containsExactly(reportName)

        removed.clear()
        compose.runOnIdle { attachments.value = listOf(file(reportName)) }
        compose.waitForIdle()
        preview(reportName).tapRightOfPreview(46f)
        assertThat(removed).containsExactly(reportName)
    }

    @Test
    fun `a tap on the file itself opens it and removes nothing`() {
        show(listOf(file(reportName)))

        preview(reportName).performTouchInput { click(center) }

        assertThat(opened).containsExactly(reportName)
        assertThat(submitted).isEqualTo(0)
        assertThat(removed).isEmpty()
        assertThat(attachments.value.map { it.name }).containsExactly(reportName)
    }

    @Test
    fun `an image preview also has a 48 dp remove target outside its own tap area`() {
        show(listOf(image("one.png")))

        val bounds = removeTarget().getBoundsInRoot()
        assertThat(bounds.width.value).isAtLeast(48f)
        assertThat(bounds.height.value).isAtLeast(48f)

        compose.onNodeWithContentDescription("one.png").tapRightOfPreview(24f)

        assertThat(removed).containsExactly("one.png")
        assertThat(opened).isEmpty()
        assertThat(submitted).isEqualTo(0)
    }

    @Test
    fun `the second of two attachments is removed without opening either`() {
        show(listOf(image("one.png"), image("two.png")))

        compose.onNodeWithContentDescription("two.png").tapRightOfPreview(24f)

        assertThat(removed).containsExactly("two.png")
        assertThat(opened).isEmpty()
        assertThat(submitted).isEqualTo(0)
        assertThat(attachments.value.map { it.name }).containsExactly("one.png")
    }

    @Test
    fun `the first of two attachments is removed without touching the second`() {
        show(listOf(image("one.png"), image("two.png")))

        compose.onNodeWithContentDescription("one.png").tapRightOfPreview(24f)

        assertThat(removed).containsExactly("one.png")
        assertThat(opened).isEmpty()
        assertThat(submitted).isEqualTo(0)
        assertThat(attachments.value.map { it.name }).containsExactly("two.png")
    }
}
