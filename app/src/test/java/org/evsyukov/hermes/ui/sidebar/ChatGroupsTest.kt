package org.evsyukov.hermes.ui.sidebar

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ChatGroupsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 28)

    private fun chat(id: String, daysAgo: Long, pinned: Boolean = false, hour: Int = 12) = ChatSummary(
        id = id, title = id, preview = "", source = "dashboard", model = null,
        startedAt = 0.0,
        lastActive = today.minusDays(daysAgo).atTime(hour, 0).toEpochSecond(ZoneOffset.UTC).toDouble(),
        messageCount = 2, pinned = pinned, archived = false, active = false,
    )

    @Test
    fun `chats fall into the sidebar periods in order`() {
        val groups = groupChats(
            listOf(chat("old", 400), chat("month", 20), chat("week", 5), chat("yesterday", 1), chat("today", 0), chat("march", 230)),
            today,
            zone,
        )
        assertThat(groups.map { it.key })
            .containsExactly(GroupKey.Today, GroupKey.Yesterday, GroupKey.Previous7, GroupKey.Previous30, GroupKey.Month(3), GroupKey.Year(2025))
            .inOrder()
    }

    @Test
    fun `pinned chats come first whatever their age`() {
        val groups = groupChats(listOf(chat("today", 0), chat("pinned-old", 90, pinned = true)), today, zone)
        assertThat(groups.first().key).isEqualTo(GroupKey.Pinned)
        assertThat(groups.first().rows.map { it.chat.id }).containsExactly("pinned-old")
        assertThat(groups.flatMap { it.rows }.count { it.chat.id == "pinned-old" }).isEqualTo(1)
    }

    @Test
    fun `newest chat leads its group`() {
        val groups = groupChats(listOf(chat("morning", 0, hour = 8), chat("evening", 0, hour = 20)), today, zone)
        assertThat(groups.single().rows.map { it.chat.id }).containsExactly("evening", "morning").inOrder()
    }
}
