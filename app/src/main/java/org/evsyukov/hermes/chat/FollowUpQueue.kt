package org.evsyukov.hermes.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Drafts not yet submitted; retained with the chat's composer across screen changes. */
class FollowUpQueue {
    data class Entry(val text: String, val files: List<LocalAttachment>)

    private val pending = MutableStateFlow<List<Entry>>(emptyList())
    val entries = pending.asStateFlow()

    @Synchronized
    internal fun add(text: String, files: List<LocalAttachment>) {
        pending.value = pending.value + Entry(text, files.toList())
    }

    @Synchronized
    internal fun take(): Entry? = pending.value.firstOrNull()?.also { pending.value = pending.value.drop(1) }
}