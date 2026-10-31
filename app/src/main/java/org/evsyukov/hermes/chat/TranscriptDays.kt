package org.evsyukov.hermes.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A row of the transcript as the chat lays it out: a day that opens, or a message under it. */
internal sealed interface TranscriptRow {
    data class Day(val date: LocalDate) : TranscriptRow
    data class Message(val item: ChatItem) : TranscriptRow
}

/**
 * A long chat is read by days: a day row in front of the first message of each day, so the eye
 * finds yesterday without reading every time. A message with no time keeps the day before it, so a
 * message still being sent does not open a new one. A day opens once: a live message takes the
 * clock of the phone, which may be behind the server's around midnight, and a day shown twice would
 * also give the list two rows under one key.
 */
internal fun transcriptRows(items: List<ChatItem>, zone: ZoneId = ZoneId.systemDefault()): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val opened = mutableSetOf<LocalDate>()
    for (item in items) {
        val day = item.time?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(zone).toLocalDate() }
        if (day != null && opened.add(day)) rows += TranscriptRow.Day(day)
        rows += TranscriptRow.Message(item)
    }
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
