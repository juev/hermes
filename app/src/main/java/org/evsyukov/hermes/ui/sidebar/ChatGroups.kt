package org.evsyukov.hermes.ui.sidebar

import org.evsyukov.hermes.data.remote.ChatSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What a sidebar group is called; the sidebar turns it into words in the user's language. */
sealed interface GroupKey {
    data object Pinned : GroupKey
    data object Today : GroupKey
    data object Yesterday : GroupKey
    data object Previous7 : GroupKey
    data object Previous30 : GroupKey

    /** A month of the current year, 1 to 12. */
    data class Month(val month: Int) : GroupKey
    data class Year(val year: Int) : GroupKey
}

data class ChatGroup(val key: GroupKey, val chats: List<ChatSummary>)

/**
 * Splits the chats the way the ChatGPT sidebar does: pinned first, then today, yesterday, the
 * previous 7 and 30 days, then by month of this year and by year before it. Order inside a group
 * follows the last activity, newest first.
 */
fun groupChats(chats: List<ChatSummary>, today: LocalDate, zone: ZoneId): List<ChatGroup> {
    val pinned = chats.filter { it.pinned }.sortedByDescending { it.lastActive }
    val groups = linkedMapOf<GroupKey, MutableList<ChatSummary>>()
    for (chat in chats.filterNot { it.pinned }.sortedByDescending { it.lastActive }) {
        val day = Instant.ofEpochMilli((chat.lastActive * 1000).toLong()).atZone(zone).toLocalDate()
        val age = ChronoUnit.DAYS.between(day, today)
        val key = when {
            age <= 0 -> GroupKey.Today
            age == 1L -> GroupKey.Yesterday
            age <= 7 -> GroupKey.Previous7
            age <= 30 -> GroupKey.Previous30
            day.year == today.year -> GroupKey.Month(day.monthValue)
            else -> GroupKey.Year(day.year)
        }
        groups.getOrPut(key) { mutableListOf() }.add(chat)
    }
    val result = mutableListOf<ChatGroup>()
    if (pinned.isNotEmpty()) result += ChatGroup(GroupKey.Pinned, pinned)
    groups.forEach { (key, list) -> result += ChatGroup(key, list) }
    return result
}
