package org.evsyukov.hermes.ui.search

import android.app.Application
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.SearchHit
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import org.evsyukov.hermes.ui.SearchState
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The search field keeps its name in every state and the clear action is a button. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var state by mutableStateOf(SearchState())

    private fun show() = compose.setContent {
        HermesTheme {
            SearchScreen(
                state = state,
                onQuery = { state = state.copy(query = it) },
                onOpen = { _, _ -> },
                onBack = {},
            )
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    @Test
    fun `the empty search field has a localized name and no duplicate hint node`() {
        show()

        val label = app.getString(R.string.search_placeholder)
        val node = field().fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(label)
        assertThat(node.config.getOrNull(SemanticsProperties.EditableText)?.text).isEmpty()
        assertThat(node.config.contains(SemanticsActions.SetText)).isTrue()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onAllNodesWithText(label).assertCountEquals(0)
    }

    @Test
    fun `the filled search field keeps the name and the typed value`() {
        show()

        field().performTextInput("hermes")
        compose.waitForIdle()

        val label = app.getString(R.string.search_placeholder)
        val node = field().fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly(label)
        assertThat(node.config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("hermes")
        compose.onAllNodesWithText(label).assertCountEquals(0)
    }

    @Test
    fun `the field keeps its place when the placeholder disappears`() {
        show()
        val empty = field().getBoundsInRoot()

        field().performTextInput("hermes")
        compose.waitForIdle()
        val filled = field().getBoundsInRoot()

        assertThat(filled.top.value).isWithin(0.5f).of(empty.top.value)
        assertThat(filled.bottom.value).isWithin(0.5f).of(empty.bottom.value)
        assertThat(filled.left.value).isWithin(0.5f).of(empty.left.value)
        // The right edge gives way to the clear button only after entry; the placeholder itself
        // must not move the field.
    }

    @Test
    fun `clear is a button that empties the query`() {
        show()
        field().performTextInput("hermes")
        compose.waitForIdle()

        val clear = compose.onNodeWithContentDescription(app.getString(R.string.search_clear))
        clear.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        clear.performClick()
        compose.waitForIdle()

        assertThat(state.query).isEmpty()
        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text).isEmpty()
        compose.onNodeWithContentDescription(app.getString(R.string.search_clear)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp")
    fun `in Russian the field name and the clear action are localized`() {
        show()

        val label = app.getString(R.string.search_placeholder)
        assertThat(label).isEqualTo("Поиск по чатам")
        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
            .containsExactly(label)

        field().performTextInput("hermes")
        compose.waitForIdle()

        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
            .containsExactly(label)
        compose.onNodeWithContentDescription(app.getString(R.string.search_clear))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    fun `an enlarged font keeps the field name and a single input row`() {
        show()

        val empty = field().getBoundsInRoot()
        field().performTextInput("hermes")
        compose.waitForIdle()
        val filled = field().getBoundsInRoot()

        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
            .containsExactly(app.getString(R.string.search_placeholder))
        assertThat(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(1)
        assertThat(filled.top.value).isWithin(0.5f).of(empty.top.value)
    }

    private fun result() = SearchHit(
        ChatSummary(id = "s1", title = "Project notes", preview = "", source = "telegram", model = null,
            startedAt = 1.0, lastActive = 2.0, messageCount = 1, pinned = false, archived = false, active = false),
        ">>>hermes<<< context for the result", null,
    )

    @Test
    fun `a result exposes its chat name and readable snippet together and opens that exact hit`() {
        val hit = result()
        var opened: SearchHit? = null
        compose.setContent {
            HermesTheme {
                SearchScreen(SearchState(query = "hermes", hits = listOf(hit)), onQuery = {},
                    onOpen = { h, _ -> opened = h }, onBack = {})
            }
        }
        val row = compose.onNode(hasClickAction() and hasText("Project notes") and hasText("hermes context for the result"))
        row.performClick()
        assertThat(opened).isSameInstanceAs(hit)
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
        state = state.copy(query = "hermes", hits = listOf(result()))
        show()
        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("hermes")
        frame("SearchSemanticsTest-en-1")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    fun `actual 360 dp en scale 2 frame`() {
        state = state.copy(query = "hermes", hits = listOf(result()))
        show()
        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("hermes")
        frame("SearchSemanticsTest-en-2")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp", fontScale = 1.0f)
    fun `actual 360 dp ru scale 1 frame`() {
        state = state.copy(query = "hermes", hits = listOf(result()))
        show()
        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("hermes")
        frame("SearchSemanticsTest-ru-1")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp", fontScale = 2.0f)
    fun `actual 360 dp ru scale 2 frame`() {
        state = state.copy(query = "hermes", hits = listOf(result()))
        show()
        assertThat(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("hermes")
        frame("SearchSemanticsTest-ru-2")
    }
}
