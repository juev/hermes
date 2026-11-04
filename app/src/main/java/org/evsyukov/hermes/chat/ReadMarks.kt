package org.evsyukov.hermes.chat

import org.evsyukov.hermes.data.remote.ChatSummary

/**
 * Where [chat] is read up to: the phone's mark, else the server's watermark. A watermark of 0 is a
 * chat marked unread by hand and gives no time; the server's `unread` flag decides it then.
 */
internal fun readMark(chat: ChatSummary, readAt: Map<String, Double>): Double? = readAt[chat.id] ?: chat.lastReadAt?.takeIf { it > 0 }

/**
 * Whether the history of [chat] must be read for an answer after its mark: its activity went past
 * the mark and past the activity [looked] through, and no answer after the mark is known yet.
 */
internal fun needsLook(chat: ChatSummary, readAt: Map<String, Double>, answers: Map<String, Double>, looked: Map<String, Double>): Boolean {
    val mark = readMark(chat, readAt) ?: return false
    return chat.lastActive > mark && (answers[chat.id] ?: Double.NEGATIVE_INFINITY) <= mark &&
        (looked[chat.id] ?: Double.NEGATIVE_INFINITY) < chat.lastActive
}

/**
 * [chats] with the unread mark the sidebar shows: a chat is unread when an answer was written after
 * its read mark; [answers] holds the newest answer found in each chat. Activity is not an answer —
 * reasoning, tool runs, the user's words and changes of the chat move it too, and the server's own
 * `unread` flag compares that activity. So the flag decides a chat without a phone mark only until a
 * look through its activity ([looked]) settles it. A phone mark also clears a server flag the phone
 * could not write back (the dashboard refuses REST mutations for the chats that live in its own
 * process).
 */
fun withUnread(chats: List<ChatSummary>, readAt: Map<String, Double>, answers: Map<String, Double>, looked: Map<String, Double>): List<ChatSummary> =
    chats.map { chat ->
        val mark = readMark(chat, readAt)
        val unread = when {
            mark == null -> chat.unread
            (answers[chat.id] ?: Double.NEGATIVE_INFINITY) > mark -> true
            chat.id !in readAt && (looked[chat.id] ?: Double.NEGATIVE_INFINITY) < chat.lastActive -> chat.unread
            else -> false
        }
        if (unread == chat.unread) chat else chat.copy(unread = unread)
    }
