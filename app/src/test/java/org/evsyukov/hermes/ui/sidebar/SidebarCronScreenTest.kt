package org.evsyukov.hermes.ui.sidebar

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class SidebarCronScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val shown = mutableListOf<ListMode>()
    private val opened = mutableListOf<String>()

    private val actions = SidebarActions(
        onSearch = {}, onNewChat = {}, onOpen = { opened += it }, onRename = { _, _ -> }, onPin = { _, _ -> },
        onArchive = { _, _ -> }, onDelete = {}, onLoadMore = {}, onRefresh = {}, onShowList = { shown += it },
        onAdmin = {}, onSettings = {}, onSwitchServer = {}, onEditServer = {},
    )

    private fun run(id: String, title: String, secondsAgo: Int) = ChatSummary(
        id = id, title = title, preview = "", source = "cron", model = null,
        startedAt = 0.0, lastActive = System.currentTimeMillis() / 1000.0 - secondsAgo, messageCount = 2,
        pinned = false, archived = false, active = false,
    )

    private fun show(state: SidebarState) = compose.setContent {
        HermesTheme { Sidebar(state, currentChatId = null, servers = emptyList(), activeServer = null, actions = actions) }
    }

    @Test
    fun `the chats list leads to the cron runs and the archive`() {
        show(SidebarState(mode = ListMode.CHATS))

        compose.onNodeWithText(app.getString(R.string.sidebar_cron)).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.sidebar_archive)).assertIsDisplayed().performClick()

        assertThat(shown).containsExactly(ListMode.CRON, ListMode.ARCHIVE).inOrder()
    }

    @Test
    fun `the cron screen has its title, says when there are no runs and leads back to chats`() {
        show(SidebarState(mode = ListMode.CRON))

        // One node: the screen's title, no row that would open the screen again.
        compose.onAllNodesWithText(app.getString(R.string.sidebar_cron)).assertCountEquals(1)
        compose.onNodeWithText(app.getString(R.string.sidebar_empty_cron)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.sidebar_empty_chats)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.sidebar_archive)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.sidebar_back)).performClick()

        assertThat(shown).containsExactly(ListMode.CHATS)
    }

    @Test
    fun `the runs of a job fold on the cron screen and a run opens`() {
        show(
            SidebarState(
                mode = ListMode.CRON,
                chats = listOf(run("r2", "nightly · Nov 01 12:00", secondsAgo = 1), run("r1", "nightly · Nov 01 06:00", secondsAgo = 2)),
            ),
        )

        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.sidebar_cron_runs, 2, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.sidebar_cron_expand)).performClick()
        compose.onNodeWithText("nightly · Nov 01 06:00").performClick()

        assertThat(opened).containsExactly("r1")
    }
}
