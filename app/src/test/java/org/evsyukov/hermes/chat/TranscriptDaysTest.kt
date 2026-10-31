package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class TranscriptDaysTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toEpochSecond().toDouble()

    private fun user(key: String, time: Double?) = ChatItem.User(key, key, emptyList(), time = time)

    private val first = LocalDate.of(2026, 10, 30)
    private val second = LocalDate.of(2026, 10, 31)

    @Test
    fun `a day row stands before the first message of each day and only there`() {
        val a = user("a", at(first, 9))
        val b = ChatItem.Assistant("b", emptyList(), streaming = false, time = at(first, 23))
        val c = ChatItem.CronReport("c", "job", "text", time = at(second, 0))
        val d = user("d", at(second, 12))

        assertThat(transcriptRows(listOf(a, b, c, d), zone)).containsExactly(
            TranscriptRow.Day(first),
            TranscriptRow.Message(a),
            TranscriptRow.Message(b),
            TranscriptRow.Day(second),
            TranscriptRow.Message(c),
            TranscriptRow.Message(d),
        ).inOrder()
    }

    @Test
    fun `a message with no time adds no row and does not open a day`() {
        val sending = user("s", null)
        val later = user("l", at(first, 10))

        assertThat(transcriptRows(listOf(sending, later), zone)).containsExactly(
            TranscriptRow.Message(sending),
            TranscriptRow.Day(first),
            TranscriptRow.Message(later),
        ).inOrder()
    }

    @Test
    fun `a message with no time keeps the day before it`() {
        val a = user("a", at(first, 10))
        val sending = user("s", null)
        val b = user("b", at(first, 11))

        assertThat(transcriptRows(listOf(a, sending, b), zone).filterIsInstance<TranscriptRow.Day>()).containsExactly(TranscriptRow.Day(first))
    }

    @Test
    fun `a day opens once even when a clock behind puts a message back in it`() {
        val a = user("a", at(first, 23))
        val b = user("b", at(second, 0))
        val c = user("c", at(first, 23))

        assertThat(transcriptRows(listOf(a, b, c), zone).filterIsInstance<TranscriptRow.Day>()).containsExactly(TranscriptRow.Day(first), TranscriptRow.Day(second)).inOrder()
    }

    @Test
    fun `the label names today and yesterday and gives the year only for another year`() {
        val today = LocalDate.of(2026, 10, 31)

        assertThat(formatDay(today, today, Locale.ENGLISH)).isEqualTo(DayLabel.Today)
        assertThat(formatDay(today.minusDays(1), today, Locale.ENGLISH)).isEqualTo(DayLabel.Yesterday)
        assertThat(formatDay(LocalDate.of(2026, 3, 5), today, Locale.ENGLISH)).isEqualTo(DayLabel.Date("5 March"))
        assertThat(formatDay(LocalDate.of(2025, 12, 31), today, Locale.ENGLISH)).isEqualTo(DayLabel.Date("31 December 2025"))
        assertThat(formatDay(LocalDate.of(2026, 3, 5), today, Locale.forLanguageTag("ru"))).isEqualTo(DayLabel.Date("5 марта"))
    }
}
