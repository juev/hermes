package org.evsyukov.hermes.ui.sidebar

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class SidebarUnreadTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val actions = SidebarActions(
        onSearch = {}, onNewChat = {}, onOpen = {}, onRename = { _, _ -> }, onPin = { _, _ -> },
        onArchive = { _, _ -> }, onDelete = {}, onLoadMore = {}, onRefresh = {}, onShowList = {},
        onAdmin = {}, onSettings = {}, onSwitchServer = {}, onEditServer = {},
    )

    private fun chat(unread: Boolean) = ChatSummary(
        id = "s1", title = "Mail", preview = "", source = "telegram", model = null,
        startedAt = 0.0, lastActive = 5.0, messageCount = 2,
        pinned = false, archived = false, active = false, unread = unread,
    )

    @Test
    fun `a chat with unread messages shows the mark`() {
        compose.setContent { HermesTheme { ChatRow(chat(unread = true), selected = false, actions) } }

        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_unread)).assertIsDisplayed()
    }

    @Test
    fun `a read chat shows no mark`() {
        compose.setContent { HermesTheme { ChatRow(chat(unread = false), selected = false, actions) } }

        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_unread)).assertDoesNotExist()
    }
}
