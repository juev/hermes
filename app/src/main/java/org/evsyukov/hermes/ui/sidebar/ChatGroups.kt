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

/** One line of a sidebar group: a chat, or the runs of one cron job folded into its newest run. */
data class ChatRow(val chat: ChatSummary, val runs: List<ChatSummary> = emptyList()) {
    val count: Int get() = runs.size + 1
    val folded: Boolean get() = runs.isNotEmpty()
}

data class ChatGroup(val key: GroupKey, val rows: List<ChatRow>)

/**
 * Splits the chats the way the ChatGPT sidebar does: pinned first, then today, yesterday, the
 * previous 7 and 30 days, then by month of this year and by year before it. Order inside a group
 * follows the last activity, newest first. The runs of one cron job in a group fold into one row.
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
    if (pinned.isNotEmpty()) result += ChatGroup(GroupKey.Pinned, foldRuns(pinned))
    groups.forEach { (key, list) -> result += ChatGroup(key, foldRuns(list)) }
    return result
}

/** Chats newest first in, rows out: each job's newest run stands where it was, its older runs behind it. */
private fun foldRuns(chats: List<ChatSummary>): List<ChatRow> {
    val jobs = chats.groupBy(::cronJobKey)
    return chats.mapNotNull { chat ->
        val job = cronJobKey(chat)?.let(jobs::getValue) ?: return@mapNotNull ChatRow(chat)
        if (job.first().id == chat.id) ChatRow(chat, job.drop(1)) else null
    }
}

/** The gateway titles a run `<job name> · Nov 01 12:28`; `%b` may come out in another language. */
private val RUN_STAMP = Regex("""^(.*) · \p{L}{3} \d{1,2} \d{2}:\d{2}$""")

/** The fallback title of a run of a nameless job: `cron <job id>`, maybe with the end of the run id. */
private val RUN_FALLBACK = Regex("""^cron ([0-9a-fA-F]{6,})(?: \S+)?$""")

/**
 * Which cron job a chat is a run of, read from the title the gateway wrote — the list carries no job
 * field. Null for any other source and for a run renamed by hand.
 */
internal fun cronJobKey(chat: ChatSummary): String? {
    if (chat.source != "cron") return null
    RUN_STAMP.matchEntire(chat.title)?.let { return it.groupValues[1].trim().ifBlank { null } }
    return RUN_FALLBACK.matchEntire(chat.title)?.let { "cron ${it.groupValues[1]}" }
}
