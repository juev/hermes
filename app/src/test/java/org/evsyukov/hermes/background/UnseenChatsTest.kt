package org.evsyukov.hermes.background

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test

class UnseenChatsTest {
    private fun chat(id: String, lastActive: Double, source: String = "telegram", messages: Int = 2, archived: Boolean = false) =
        ChatSummary(id, id, "", source, null, 0.0, lastActive, messages, pinned = false, archived = archived, active = false)

    @Test
    fun `the first check only sets the mark`() {
        assertThat(unseenChats(listOf(chat("a", 100.0)), mark = null)).isEmpty()
    }

    @Test
    fun `chats newer than the mark are reported`() {
        val result = unseenChats(listOf(chat("old", 90.0), chat("new", 110.0)), mark = 100.0)
        assertThat(result.map { it.id }).containsExactly("new")
    }

    @Test
    fun `chats of this phone are reported too, while empty and archived ones are not`() {
        val result = unseenChats(
            listOf(chat("mine", 110.0, source = "android"), chat("empty", 110.0, messages = 0), chat("archived", 110.0, archived = true), chat("cron", 110.0, source = "cron")),
            mark = 100.0,
        )
        assertThat(result.map { it.id }).containsExactly("mine", "cron")
    }
}
