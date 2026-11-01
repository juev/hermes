package org.evsyukov.hermes.ui.sidebar

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.ChatSummary
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CronRunFoldTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 1)

    private fun chat(id: String, title: String, hour: Int, daysAgo: Long = 0, source: String = "cron") = ChatSummary(
        id = id, title = title, preview = "", source = source, model = null,
        startedAt = 0.0,
        lastActive = today.minusDays(daysAgo).atTime(hour, 0).toEpochSecond(ZoneOffset.UTC).toDouble(),
        messageCount = 2, pinned = false, archived = false, active = false,
    )

    private fun rows(vararg chats: ChatSummary) = groupChats(chats.toList(), today, zone).single().rows

    @Test
    fun `three runs of one job in Today read as one row led by the newest`() {
        val rows = rows(
            chat("r1", "nightly · Nov 01 06:00", hour = 6),
            chat("r3", "nightly · Nov 01 18:00", hour = 18),
            chat("r2", "nightly · Nov 01 12:00", hour = 12),
        )

        val row = rows.single()
        assertThat(row.chat.id).isEqualTo("r3")
        assertThat(row.runs.map { it.id }).containsExactly("r2", "r1").inOrder()
        assertThat(row.folded).isTrue()
        assertThat(row.count).isEqualTo(3)
    }

    @Test
    fun `a single run is a row of its own`() {
        val row = rows(chat("r1", "nightly · Nov 01 06:00", hour = 6)).single()

        assertThat(row.folded).isFalse()
        assertThat(row.count).isEqualTo(1)
    }

    @Test
    fun `runs on either side of the day boundary stay in their own groups`() {
        val groups = groupChats(
            listOf(chat("today", "nightly · Nov 01 06:00", hour = 6), chat("yesterday", "nightly · Oct 31 23:00", hour = 23, daysAgo = 1)),
            today,
            zone,
        )

        assertThat(groups.map { it.key }).containsExactly(GroupKey.Today, GroupKey.Yesterday).inOrder()
        assertThat(groups.map { it.rows.single().chat.id }).containsExactly("today", "yesterday").inOrder()
        assertThat(groups.none { group -> group.rows.any { it.folded } }).isTrue()
    }

    @Test
    fun `a chat of another source is never folded`() {
        val rows = rows(
            chat("t1", "nightly · Nov 01 06:00", hour = 6, source = "telegram"),
            chat("t2", "nightly · Nov 01 12:00", hour = 12, source = "telegram"),
        )

        assertThat(rows.map { it.chat.id }).containsExactly("t2", "t1").inOrder()
        assertThat(rows.none { it.folded }).isTrue()
    }

    @Test
    fun `a run renamed by hand stays alone`() {
        val rows = rows(
            chat("r1", "nightly · Nov 01 06:00", hour = 6),
            chat("r2", "nightly · Nov 01 12:00", hour = 12),
            chat("renamed", "nightly", hour = 18),
        )

        assertThat(rows.map { it.chat.id to it.count }).containsExactly("renamed" to 1, "r2" to 2).inOrder()
    }

    @Test
    fun `fallback titles of one job fold together`() {
        val rows = rows(
            chat("r1", "cron 1a2b3c4d5e6f", hour = 6),
            chat("r2", "cron 1a2b3c4d5e6f 9f8e7d", hour = 12),
            chat("other", "cron 0f0f0f0f0f0f 9f8e7d", hour = 18),
        )

        assertThat(rows.map { it.chat.id to it.count }).containsExactly("other" to 1, "r2" to 2).inOrder()
        assertThat(cronJobKey(chat("r2", "cron 1a2b3c4d5e6f 9f8e7d", hour = 12))).isEqualTo("cron 1a2b3c4d5e6f")
    }

    @Test
    fun `jobs whose names share a prefix stay apart`() {
        val rows = rows(
            chat("a", "nightly · Nov 01 06:00", hour = 6),
            chat("b", "nightly-report · Nov 01 12:00", hour = 12),
        )

        assertThat(rows.map { it.chat.id }).containsExactly("b", "a").inOrder()
        assertThat(rows.none { it.folded }).isTrue()
    }

    @Test
    fun `a month printed in another language still folds`() {
        val rows = rows(
            chat("r1", "nightly · ноя 01 06:00", hour = 6),
            chat("r2", "nightly · ноя 01 12:00", hour = 12),
        )

        assertThat(rows.single().count).isEqualTo(2)
    }

    @Test
    fun `a title that is only a stamp has no key`() {
        assertThat(cronJobKey(chat("r1", " · Nov 01 06:00", hour = 6))).isNull()
    }
}
