package org.evsyukov.hermes.chat

import org.evsyukov.hermes.data.remote.ChatSummary

/**
 * [chats] with the unread mark the sidebar shows. A chat whose activity is newer than the phone's
 * read mark is unread; without a mark the server's own `unread` flag decides. A phone mark that
 * covers the latest activity also clears a server flag the phone could not write back (the
 * dashboard refuses REST mutations for the chats that live in its own process).
 */
fun withUnread(chats: List<ChatSummary>, readAt: Map<String, Double>): List<ChatSummary> =
    chats.map { chat ->
        val seen = readAt[chat.id]
        val unread = if (seen != null) chat.lastActive > seen else chat.unread
        if (unread == chat.unread) chat else chat.copy(unread = unread)
    }
