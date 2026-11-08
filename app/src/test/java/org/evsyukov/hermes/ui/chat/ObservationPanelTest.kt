package org.evsyukov.hermes.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.*
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ObservationPanelTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun disclosure(stem: String, scale: Float) {
        var actualScale = 0f
        var refresh = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                actualScale = LocalDensity.current.fontScale
                HermesTheme { Box(Modifier.width(360.dp)) { ObservationDisclosure(true) { refresh++ } } }
            }
        }
        compose.onNodeWithText(app.getString(R.string.chat_local_overlap)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.chat_local_unsaved)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.chat_local_refresh)).performClick()
        compose.runOnIdle { assertThat(refresh).isEqualTo(1); assertThat(actualScale).isEqualTo(scale) }
        System.getenv("HERMES_TEST_CAPTURE_DIR")?.let { folder ->
            val file = File(folder, "$stem.png"); file.parentFile.mkdirs()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test @Config(qualifiers = "en-w360dp-h800dp") fun `English disclosure at normal font is visible and refresh only reads`() = disclosure("en-normal", 1f)
    @Test @Config(qualifiers = "en-w360dp-h800dp") fun `English disclosure at enlarged font is visible and refresh only reads`() = disclosure("en-large", 1.5f)
    @Test @Config(qualifiers = "ru-w360dp-h800dp") fun `Russian disclosure at normal font is visible and refresh only reads`() = disclosure("ru-normal", 1f)
    @Test @Config(qualifiers = "ru-w360dp-h800dp") fun `Russian disclosure at enlarged font is visible and refresh only reads`() = disclosure("ru-large", 1.5f)
    @Test fun `tool frames never appear as raw transcript text`() {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("result_text", kotlinx.serialization.json.JsonPrimitive("RAW_RESULT_SENTINEL"))
        }
        val row = LocalObservation(profile = "p", lineage = "url", session = "s", generation = 1,
            completed = true, frames = listOf(ObservedFrame(ReceiptOrigin("p", 1, "live", 1, null), "tool.complete", payload)),
            items = listOf(ChatItem.Assistant("a", listOf(Part.Tool(ToolRun("t", "terminal", "pwd", "{}", true,
                output = toolOutput("RAW_RESULT_SENTINEL")))), false)))
        val actions = MessageActions({}, {}, {}, {}, {}, {}, { null })
        compose.setContent { HermesTheme { ObservationPanel(row, actions, {}, {}) } }
        compose.onAllNodes(hasText("RAW_RESULT_SENTINEL", substring = true)).assertCountEquals(0)
        assertThat(row.frames.single().payload).isEqualTo(payload)
    }
    @Test fun `retained attachment opens its exact local bytes without a remote path lookup`() {
        val row = LocalObservation(profile = "p", lineage = "url", session = "s", generation = 1,
            files = listOf(ObservationFile("note.txt", "text/plain", "AQID", "local")))
        var opened: LocalAttachment? = null
        compose.setContent { HermesTheme { ObservationFiles(row) { opened = it } } }
        compose.onNodeWithText(app.getString(R.string.chat_local_file, "note.txt")).performClick()
        compose.runOnIdle { assertThat(checkNotNull(opened).bytes.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder(); assertThat(opened?.mime).isEqualTo("text/plain") }
    }
    @Test fun `collapse and reopen are presentation actions with full local content`() {
        val original = LocalObservation(profile = "p", lineage = "url", session = "s", generation = 1, keys = listOf("local"),
            items = listOf(ChatItem.User("local", "local prompt", emptyList()), ChatItem.Assistant("live", listOf(Part.Text("local answer")), false)), completed = true)
        var row by mutableStateOf(original)
        var sent = 0
        var refresh = 0
        val actions = MessageActions({}, {}, {}, { sent++ }, { sent++ }, {}, { null })
        compose.setContent { HermesTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ObservationPanel(row, actions, { refresh++ }, { row = row.copy(collapsed = it) }) } } }
        // Markdown parsing runs outside Compose's idling resource, as in UserBubbleTest.
        // This awaits renderer readiness; visibility and collapse remain separate assertions.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("local answer", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("local answer", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.chat_local_collapse)).performScrollTo().performClick()
        compose.onNodeWithText("local answer", substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.chat_local_reopen)).performScrollTo().performClick()
        // Markdown parsing runs outside Compose's idling resource, as in UserBubbleTest.
        // This awaits renderer readiness; visibility and collapse remain separate assertions.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("local answer", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("local answer", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.chat_local_refresh)).performScrollTo().performClick()
        compose.runOnIdle { assertThat(row.items).isEqualTo(original.items); assertThat(sent).isEqualTo(0); assertThat(refresh).isEqualTo(1) }
    }
}
