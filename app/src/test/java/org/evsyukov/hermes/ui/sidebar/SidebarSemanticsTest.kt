package org.evsyukov.hermes.ui.sidebar

import android.app.Application
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.ui.ListMode
import org.evsyukov.hermes.ui.SidebarState
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Current, running and unread state of a chat row, and the long-click management action. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidebarSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val opened = mutableListOf<String>()

    private val actions = SidebarActions(
        onSearch = {}, onNewChat = {}, onOpen = { opened += it }, onRename = { _, _ -> }, onPin = { _, _ -> },
        onArchive = { _, _ -> }, onDelete = {}, onLoadMore = {}, onRefresh = {}, onShowList = {},
        onAdmin = {}, onSettings = {}, onSwitchServer = {}, onEditServer = {},
    )

    private fun chat(
        id: String = "s1",
        title: String = "Mail",
        active: Boolean = false,
        unread: Boolean = false,
    ) = ChatSummary(
        id = id, title = title, preview = "", source = "telegram", model = null,
        startedAt = 0.0, lastActive = System.currentTimeMillis() / 1000.0, messageCount = 2,
        pinned = false, archived = false, active = active, unread = unread,
    )

    private fun showSidebar(chats: List<ChatSummary>, currentChatId: String? = null) = compose.setContent {
        HermesTheme {
            Sidebar(
                SidebarState(chats = chats, mode = ListMode.CHATS),
                currentChatId = currentChatId,
                servers = emptyList(),
                activeServer = null,
                actions = actions,
            )
        }
    }

    private fun showRow(chat: ChatSummary, selected: Boolean = false) = compose.setContent {
        HermesTheme { Box { ChatRow(chat, selected = selected, actions = actions) } }
    }

    private fun row(title: String) = compose.onNode(hasClickAction() and hasText(title))

    @Test
    fun `the current chat is selected in the real sidebar wiring`() {
        showSidebar(listOf(chat()), currentChatId = "s1")

        row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun `another chat is not selected`() {
        showSidebar(listOf(chat()), currentChatId = "other")

        row("Mail").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun `a running chat publishes its state and keeps the unread description`() {
        showRow(chat(active = true, unread = true), selected = true)

        val node = row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(app.getString(R.string.sidebar_active))
        assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)).contains(app.getString(R.string.sidebar_unread))
    }

    @Test
    fun `a chat that is not running has no state description`() {
        showRow(chat())

        val node = row("Mail").fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.StateDescription)).isNull()
    }

    @Test
    fun `the management action is labelled and the long click still opens the menu`() {
        showRow(chat())

        val node = row("Mail").fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsActions.OnLongClick)?.label)
            .isEqualTo(app.getString(R.string.sidebar_manage))
        assertThat(opened).isEmpty()

        row("Mail").performTouchInput { longClick() }
        compose.onNodeWithText(app.getString(R.string.sidebar_rename)).assertExists()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `a tap still opens the chat`() {
        showRow(chat())

        row("Mail").performClick()

        assertThat(opened).containsExactly("s1")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp")
    fun `in Russian the active state, unread mark and management action are localized`() {
        showRow(chat(active = true, unread = true))

        val node = row("Mail").fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo("Выполняется")
        assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)).contains("Непрочитано")
        assertThat(node.config.getOrNull(SemanticsActions.OnLongClick)?.label).isEqualTo("Действия с чатом")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    fun `an enlarged font keeps the selected and running states`() {
        showRow(chat(active = true, unread = true), selected = true)

        val node = row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(app.getString(R.string.sidebar_active))
        assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)).contains(app.getString(R.string.sidebar_unread))
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
        showSidebar(listOf(chat(active = true, unread = true)), currentChatId = "s1")
        row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        frame("Sidebar-en-1")
    }
    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    fun `actual 360 dp en scale 2 frame`() {
        showSidebar(listOf(chat(active = true, unread = true)), currentChatId = "s1")
        row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        frame("Sidebar-en-2")
    }
    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp", fontScale = 1.0f)
    fun `actual 360 dp ru scale 1 frame`() {
        showSidebar(listOf(chat(active = true, unread = true)), currentChatId = "s1")
        row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        frame("Sidebar-ru-1")
    }
    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp", fontScale = 2.0f)
    fun `actual 360 dp ru scale 2 frame`() {
        showSidebar(listOf(chat(active = true, unread = true)), currentChatId = "s1")
        row("Mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        frame("Sidebar-ru-2")
    }
}
