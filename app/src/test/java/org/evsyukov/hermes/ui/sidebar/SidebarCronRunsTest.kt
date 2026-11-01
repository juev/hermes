package org.evsyukov.hermes.ui.sidebar

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.ui.SidebarState
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class SidebarCronRunsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val actions = SidebarActions(
        onSearch = {}, onNewChat = {}, onOpen = {}, onRename = { _, _ -> }, onPin = { _, _ -> },
        onArchive = { _, _ -> }, onDelete = {}, onLoadMore = {}, onRefresh = {}, onShowList = {},
        onAdmin = {}, onSettings = {}, onSwitchServer = {}, onEditServer = {},
    )

    /** The sidebar files chats by the real clock, so the runs happened seconds ago. */
    private fun run(id: String, title: String, secondsAgo: Int) = ChatSummary(
        id = id, title = title, preview = "", source = "cron", model = null,
        startedAt = 0.0, lastActive = System.currentTimeMillis() / 1000.0 - secondsAgo, messageCount = 2,
        pinned = false, archived = false, active = false,
    )

    private fun show(vararg chats: ChatSummary) = compose.setContent {
        HermesTheme { Sidebar(SidebarState(chats = chats.toList()), currentChatId = null, servers = emptyList(), activeServer = null, actions = actions) }
    }

    @Test
    fun `the chevron opens the older runs of a folded row`() {
        show(
            run("r3", "nightly · Nov 01 18:00", secondsAgo = 1),
            run("r2", "nightly · Nov 01 12:00", secondsAgo = 2),
            run("r1", "nightly · Nov 01 06:00", secondsAgo = 3),
        )

        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.sidebar_cron_runs, 3, 3)).assertIsDisplayed()
        compose.onNodeWithText("nightly").assertIsDisplayed()
        compose.onNodeWithText("nightly · Nov 01 18:00").assertDoesNotExist()
        compose.onNodeWithText("nightly · Nov 01 12:00").assertDoesNotExist()
        compose.onNodeWithText("nightly · Nov 01 06:00").assertDoesNotExist()

        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_cron_expand)).performClick()

        compose.onNodeWithText("nightly · Nov 01 12:00").assertIsDisplayed()
        compose.onNodeWithText("nightly · Nov 01 06:00").assertIsDisplayed()

        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_cron_collapse)).performClick()

        compose.onNodeWithText("nightly · Nov 01 12:00").assertDoesNotExist()
    }

    @Test
    fun `an unread older run marks the folded row unread`() {
        val older = run("r1", "nightly · Nov 01 06:00", secondsAgo = 2).copy(unread = true)
        compose.setContent {
            HermesTheme { ChatRow(run("r2", "nightly · Nov 01 12:00", secondsAgo = 1), selected = false, actions, runs = listOf(older), onToggleRuns = {}) }
        }

        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_unread)).assertIsDisplayed()
    }

    @Test
    fun `a job with one run shows no chevron`() {
        show(run("r1", "nightly · Nov 01 06:00", secondsAgo = 1))

        compose.onNodeWithText("nightly · Nov 01 06:00").assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_cron_expand)).assertDoesNotExist()
    }
}
