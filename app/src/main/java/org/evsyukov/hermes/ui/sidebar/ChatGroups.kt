package org.evsyukov.hermes.ui.sidebar

import org.evsyukov.hermes.data.remote.ChatSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

data class ChatGroup(val label: String, val chats: List<ChatSummary>)

/**
 * Splits the chats the way the ChatGPT sidebar does: pinned first, then today, yesterday, the
 * previous 7 and 30 days, then by month of this year and by year before it. Order inside a group
 * follows the last activity, newest first.
 */
fun groupChats(chats: List<ChatSummary>, today: LocalDate, zone: ZoneId, locale: Locale = Locale.forLanguageTag("ru")): List<ChatGroup> {
    val pinned = chats.filter { it.pinned }.sortedByDescending { it.lastActive }
    val groups = linkedMapOf<String, MutableList<ChatSummary>>()
    for (chat in chats.filterNot { it.pinned }.sortedByDescending { it.lastActive }) {
        val day = Instant.ofEpochMilli((chat.lastActive * 1000).toLong()).atZone(zone).toLocalDate()
        val age = ChronoUnit.DAYS.between(day, today)
        val label = when {
            age <= 0 -> "Сегодня"
            age == 1L -> "Вчера"
            age <= 7 -> "Предыдущие 7 дней"
            age <= 30 -> "Предыдущие 30 дней"
            day.year == today.year -> day.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
            else -> day.year.toString()
        }
        groups.getOrPut(label) { mutableListOf() }.add(chat)
    }
    val result = mutableListOf<ChatGroup>()
    if (pinned.isNotEmpty()) result += ChatGroup("Закреплённые", pinned)
    groups.forEach { (label, list) -> result += ChatGroup(label, list) }
    return result
}
