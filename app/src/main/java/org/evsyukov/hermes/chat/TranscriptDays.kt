package org.evsyukov.hermes.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A row of the transcript as the chat lays it out: a day that opens, a message under it, or a question of the agent. */
internal sealed interface TranscriptRow {
    data class Day(val date: LocalDate) : TranscriptRow
    data class Message(val item: ChatItem) : TranscriptRow

    /**
     * A question of the agent: under the answer whose tool asked it, of that answer's day. One whose
     * answer is not in the transcript, or no longer holds the tool, stands where it was asked by its time.
     */
    data class Ask(val card: QuestionCard) : TranscriptRow
}

/**
 * A long chat is read by days: a day row in front of the first message of each day, so the eye
 * finds yesterday without reading every time. A message with no time keeps the day before it, so a
 * message still being sent does not open a new one. A day opens once: a live message takes the
 * clock of the phone, which may be behind the server's around midnight, and a day shown twice would
 * also give the list two rows under one key.
 */
internal fun transcriptRows(items: List<ChatItem>, zone: ZoneId = ZoneId.systemDefault(), questions: List<QuestionCard> = emptyList()): List<TranscriptRow> {
    val owners = items.filterIsInstance<ChatItem.Assistant>().flatMap { item ->
        item.parts.filterIsInstance<Part.Tool>().map { it.run.id to item.key }
    }.toMap()
    val (owned, loose) = questions.partition { card -> card.toolCallId?.let(owners::containsKey) == true }
    val under = owned.groupBy { owners.getValue(it.toolCallId!!) }
    // A stable sort: questions of one moment keep the order they came in, and one with no time goes last.
    val waiting = ArrayDeque(loose.sortedBy { it.time ?: Double.MAX_VALUE })
    val rows = mutableListOf<TranscriptRow>()
    val opened = mutableSetOf<LocalDate>()
    fun open(time: Double?) {
        val day = time?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(zone).toLocalDate() }
        if (day != null && opened.add(day)) rows += TranscriptRow.Day(day)
    }
    fun ask(card: QuestionCard) {
        open(card.time)
        rows += TranscriptRow.Ask(card)
    }
    for (item in items) {
        val at = item.time
        if (at != null) while (waiting.firstOrNull()?.time?.let { it < at } == true) ask(waiting.removeFirst())
        open(at)
        rows += TranscriptRow.Message(item)
        under[item.key]?.forEach { rows += TranscriptRow.Ask(it) }
    }
    waiting.forEach(::ask)
    return rows
}

/** What a day separator says: today and yesterday are words of the app's language, the rest a date. */
internal sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    data class Date(val text: String) : DayLabel
}

/** Today and yesterday by name, anything else by its day and month, with the year when it is not this one. */
internal fun formatDay(day: LocalDate, today: LocalDate, locale: Locale): DayLabel = when (day) {
    today -> DayLabel.Today
    today.minusDays(1) -> DayLabel.Yesterday
    else -> DayLabel.Date(day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", locale)))
}
