package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test

/** The rule alone; how the looks and the phone's store feed it is in [LocalReadsTest]. */
class ReadMarksTest {
    private fun chat(id: String, lastActive: Double, unread: Boolean = false, lastReadAt: Double? = null) =
        ChatSummary(id, id, "", "telegram", null, 0.0, lastActive, 2, pinned = false, archived = false, active = false, unread = unread, lastReadAt = lastReadAt)

    @Test
    fun `the server mark shows when nothing says where the chat is read`() {
        val chats = withUnread(listOf(chat("a", 100.0, unread = true), chat("b", 100.0, unread = false)), emptyMap(), emptyMap(), emptyMap())

        assertThat(chats.map { it.unread }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `activity after the read mark without an answer stays read`() {
        val chats = withUnread(listOf(chat("a", 150.0)), mapOf("a" to 100.0), emptyMap(), mapOf("a" to 150.0))

        assertThat(chats.single().unread).isFalse()
    }

    @Test
    fun `an answer after the read mark is unread`() {
        val chats = withUnread(listOf(chat("a", 101.0)), mapOf("a" to 100.0), mapOf("a" to 101.0), emptyMap())

        assertThat(chats.single().unread).isTrue()
    }

    @Test
    fun `a phone mark that covers the answer is read, also over a stale server mark`() {
        val chats = withUnread(
            listOf(chat("a", 100.0, unread = true), chat("b", 100.0, unread = true)),
            mapOf("a" to 100.0, "b" to 120.0),
            mapOf("a" to 100.0, "b" to 100.0),
            emptyMap(),
        )

        assertThat(chats.map { it.unread }).containsExactly(false, false).inOrder()
    }

    @Test
    fun `the server's flag decides a chat without a phone mark until a look through its activity settles it`() {
        val chat = chat("a", 150.0, unread = true, lastReadAt = 100.0)

        assertThat(withUnread(listOf(chat), emptyMap(), emptyMap(), emptyMap()).single().unread).isTrue()
        assertThat(withUnread(listOf(chat), emptyMap(), emptyMap(), mapOf("a" to 150.0)).single().unread).isFalse()
        assertThat(withUnread(listOf(chat), emptyMap(), mapOf("a" to 140.0), mapOf("a" to 150.0)).single().unread).isTrue()
    }

    @Test
    fun `the chat keeps its other fields`() {
        val original = chat("a", 100.0)

        val shown = withUnread(listOf(original), mapOf("a" to 90.0), mapOf("a" to 95.0), emptyMap()).single()

        assertThat(shown).isEqualTo(original.copy(unread = true))
    }
}
