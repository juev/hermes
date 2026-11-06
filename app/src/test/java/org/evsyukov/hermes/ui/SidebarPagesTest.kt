package org.evsyukov.hermes.ui

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test

class SidebarPagesTest {
    private fun chat(id: String, source: String, createdSource: String? = null) =
        ChatSummary(id, id, "", source, null, 0.0, 0.0, 2, pinned = false, archived = false, active = false, createdSource = createdSource)

    @Test
    fun `a later page of the cron runs does not land in the chats shown since`() {
        val chats = SidebarState(chats = listOf(chat("talk", "android")), total = 1, listed = 1, mode = ListMode.CHATS, loading = true)

        val after = chats.withMore(ListMode.CRON, listOf(chat("run", "cron")), total = 80, size = 1)

        assertThat(after).isEqualTo(chats)
    }

    @Test
    fun `a page keeps only the rows of its list, and counts what the server listed`() {
        val runs = SidebarState(chats = listOf(chat("r1", "cron")), total = 3, listed = 1, mode = ListMode.CRON, loading = true)

        // A server that ignores the filter must not put a human chat among the runs.
        val after = runs.withMore(ListMode.CRON, listOf(chat("r2", "cron"), chat("talk", "telegram")), total = 3, size = 2)

        assertThat(after.chats.map { it.id }).containsExactly("r1", "r2").inOrder()
        assertThat(after.listed).isEqualTo(3)
        assertThat(after.loading).isFalse()
    }

    @Test
    fun `a later page keeps a local chat only when its creator is not a service`() {
        val chats = SidebarState(chats = listOf(chat("talk", "android")), total = 4, listed = 1, mode = ListMode.CHATS, loading = true)

        val after = chats.withMore(
            ListMode.CHATS,
            listOf(
                chat("job", "local", createdSource = "tool"),
                chat("desk", "local", createdSource = "desktop"),
                chat("new", "future-client"),
            ),
            total = 4,
            size = 3,
        )

        assertThat(after.chats.map { it.id }).containsExactly("talk", "desk", "new").inOrder()
        assertThat(after.listed).isEqualTo(4)
        assertThat(after.loading).isFalse()
    }
}
