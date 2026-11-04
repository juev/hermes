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
    fun `a question stands under the answer whose tool asked it, and one no answer holds after the last message`() {
        val run = ToolRun("t1", "clarify", "", "", done = true)
        val a = ChatItem.Assistant("a", listOf(Part.Text("Let me ask."), Part.Tool(run)), streaming = false, time = at(first, 9))
        val b = user("b", at(second, 10))
        val asked = QuestionCard("r1", listOf(Question("q0", "Which file?", emptyList())), toolCallId = "t1")
        val loose = QuestionCard("r2", listOf(Question("q0", "Which branch?", emptyList())), toolCallId = "gone")

        assertThat(transcriptRows(listOf(a, b), zone, questions = listOf(asked, loose))).containsExactly(
            TranscriptRow.Day(first),
            TranscriptRow.Message(a),
            TranscriptRow.Ask(asked),
            TranscriptRow.Day(second),
            TranscriptRow.Message(b),
            TranscriptRow.Ask(loose),
        ).inOrder()
    }

    private fun loose(id: String, time: Double?) = QuestionCard(id, listOf(Question("q0", "Which file?", emptyList())), state = QuestionState.SUBMITTED, toolCallId = null, time = time)

    @Test
    fun `a question no answer holds keeps its place by time as new messages and a new day come`() {
        val a = user("a", at(first, 9))
        val card = loose("r1", at(first, 10))
        val b = ChatItem.Assistant("b", emptyList(), streaming = false, time = at(first, 11))
        val later = user("c", at(second, 8))
        val laterAnswer = ChatItem.Assistant("d", emptyList(), streaming = false, time = at(second, 9))

        assertThat(transcriptRows(listOf(a, b), zone, questions = listOf(card))).containsExactly(
            TranscriptRow.Day(first),
            TranscriptRow.Message(a),
            TranscriptRow.Ask(card),
            TranscriptRow.Message(b),
        ).inOrder()
        assertThat(transcriptRows(listOf(a, b, later, laterAnswer), zone, questions = listOf(card))).containsExactly(
            TranscriptRow.Day(first),
            TranscriptRow.Message(a),
            TranscriptRow.Ask(card),
            TranscriptRow.Message(b),
            TranscriptRow.Day(second),
            TranscriptRow.Message(later),
            TranscriptRow.Message(laterAnswer),
        ).inOrder()
    }

    @Test
    fun `a question whose answer the history no longer holds stays where it was asked, not at the end`() {
        val run = ToolRun("t1", "clarify", "", "", done = true)
        val asked = ChatItem.Assistant("a17", listOf(Part.Text("Let me ask."), Part.Tool(run)), streaming = false, time = at(first, 9))
        val card = QuestionCard("r1", listOf(Question("q0", "Which file?", emptyList())), state = QuestionState.SUBMITTED, toolCallId = "t1", time = at(first, 9) + 30)
        val next = user("n", at(first, 12))
        // The history read again keeps the words of the answer under another key, without the tool of its last round.
        val stored = ChatItem.Assistant("a3", listOf(Part.Text("Let me ask.")), streaming = false, time = at(first, 9))

        assertThat(transcriptRows(listOf(asked, next), zone, questions = listOf(card))).containsExactly(
            TranscriptRow.Day(first), TranscriptRow.Message(asked), TranscriptRow.Ask(card), TranscriptRow.Message(next),
        ).inOrder()
        assertThat(transcriptRows(listOf(stored, next), zone, questions = listOf(card))).containsExactly(
            TranscriptRow.Day(first), TranscriptRow.Message(stored), TranscriptRow.Ask(card), TranscriptRow.Message(next),
        ).inOrder()
    }

    @Test
    fun `a question asked on a day of its own opens that day`() {
        val a = user("a", at(first, 9))
        val card = loose("r1", at(second, 7))
        val b = user("b", at(second, 8))

        assertThat(transcriptRows(listOf(a, b), zone, questions = listOf(card))).containsExactly(
            TranscriptRow.Day(first),
            TranscriptRow.Message(a),
            TranscriptRow.Day(second),
            TranscriptRow.Ask(card),
            TranscriptRow.Message(b),
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
