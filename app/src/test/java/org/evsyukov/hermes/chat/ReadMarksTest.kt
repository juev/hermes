package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test

class ReadMarksTest {
    private fun chat(id: String, lastActive: Double, unread: Boolean) =
        ChatSummary(id, id, "", "telegram", null, 0.0, lastActive, 2, pinned = false, archived = false, active = false, unread = unread)

    @Test
    fun `the server mark shows when the phone knows nothing of the chat`() {
        val chats = withUnread(listOf(chat("a", 100.0, unread = true), chat("b", 100.0, unread = false)), emptyMap())

        assertThat(chats.map { it.unread }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `activity after the phone's read mark is unread`() {
        val chats = withUnread(listOf(chat("a", 101.0, unread = false)), mapOf("a" to 100.0))

        assertThat(chats.single().unread).isTrue()
    }

    @Test
    fun `a phone mark that covers the activity is read, also over a stale server mark`() {
        val chats = withUnread(listOf(chat("a", 100.0, unread = true), chat("b", 100.0, unread = true)), mapOf("a" to 100.0, "b" to 120.0))

        assertThat(chats.map { it.unread }).containsExactly(false, false).inOrder()
    }

    @Test
    fun `the chat keeps its other fields`() {
        val original = chat("a", 100.0, unread = false)

        val shown = withUnread(listOf(original), mapOf("a" to 90.0)).single()

        assertThat(shown).isEqualTo(original.copy(unread = true))
    }
}
