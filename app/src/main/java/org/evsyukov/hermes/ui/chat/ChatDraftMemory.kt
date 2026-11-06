package org.evsyukov.hermes.ui.chat

import java.util.IdentityHashMap

/**
 * Where a draft belongs: a stored chat of the server, or the one new chat the app has at a time.
 * A profile is one server, so a draft key never meets a chat of another server with the same id.
 */
sealed interface DraftKey {
    data class Chat(val sessionId: String) : DraftKey
    data object NewChat : DraftKey
}

/**
 * The unsent words, files and reading place of the chats of one profile. It outlives the
 * [org.evsyukov.hermes.chat.ChatController] of a chat and the connection built for the profile:
 * opening another chat closes that controller, and coming back finds this draft through the chat's
 * stable id. No controller is kept here, and nothing is written to disk, so a process restart starts
 * without drafts.
 *
 * Memory is bounded by [capacity] slots. A new key takes a free slot, or the least recently used
 * empty draft leaves for it; a draft with words or files is never evicted. When every slot holds
 * content, [obtain] refuses the new key and the screen keeps the current chat and draft, so the user
 * is told to clear or send one of the retained drafts instead of losing one silently.
 */
class ChatDraftMemory(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()

    /** Least recently used first: an empty draft leaves in that order. */
    private val drafts = LinkedHashMap<DraftKey, ChatDraft>(0, 0.75f, true)

    /** How many sends of each new-chat draft still wait for the server to name their chat. */
    private val sending = IdentityHashMap<ChatDraft, Int>()

    /**
     * The draft of [key], the same object on every visit, or null when every slot holds a draft with
     * words or files and [key] is not among them. A refused key is not stored and takes nothing.
     */
    fun obtain(key: DraftKey): ChatDraft? = synchronized(lock) {
        drafts[key]?.let { return@synchronized it }
        if (drafts.size >= capacity) {
            val empty = drafts.entries.firstOrNull { !it.value.hasContent } ?: return@synchronized null
            drafts.remove(empty.key)
        }
        ChatDraft().also { drafts[key] = it }
    }

    /** Drops the draft of a chat the user deleted. */
    fun forget(sessionId: String) = synchronized(lock) { drafts.remove(DraftKey.Chat(sessionId)) }

    /** Drops every draft of the profile, e.g. after a sign-out. */
    fun clear() = synchronized(lock) {
        drafts.clear()
        sending.clear()
    }

    /**
     * The first message of [draft] is on its way and the server has not named its chat yet. Until it
     * does, this draft must not become the next new chat: its words would follow the wrong chat when
     * the late answer arrives.
     */
    fun markSending(draft: ChatDraft) = synchronized(lock) { sending[draft] = (sending[draft] ?: 0) + 1 }

    /** The message of [draft] failed or was cancelled before the server named its chat. */
    fun clearSending(draft: ChatDraft) = synchronized(lock) {
        val left = (sending[draft] ?: return@synchronized) - 1
        if (left > 0) sending[draft] = left else sending.remove(draft)
    }

    /** Whether [draft] is still the one new chat of this profile. */
    fun isNewChat(draft: ChatDraft): Boolean = synchronized(lock) { drafts[DraftKey.NewChat] === draft }

    /** Whether the new chat is waiting for the server to name it; opening another one would share its draft. */
    fun isSendingNewChat(): Boolean = synchronized(lock) {
        val draft = drafts[DraftKey.NewChat] ?: return@synchronized false
        (sending[draft] ?: 0) > 0
    }

    /**
     * Moves [draft] to [to], for the new chat that got its stored id with the first message: the same
     * words and files now belong to that chat, not to the next empty one. The entry count does not
     * change, so this never needs a free slot. The draft no longer owns the new-chat slot, so every
     * send that was waiting for the name stops holding it.
     */
    fun migrate(draft: ChatDraft, to: DraftKey) = synchronized(lock) {
        sending.remove(draft)
        val from = drafts.entries.firstOrNull { it.value === draft }?.key ?: return@synchronized
        if (from == to) return@synchronized
        drafts.remove(from)
        drafts[to] = draft
    }

    /** The chat a screen can open when there is no room for a new chat: the one used last. */
    fun mostRecentChatId(): String? = synchronized(lock) {
        drafts.keys.lastOrNull { it is DraftKey.Chat }?.let { (it as DraftKey.Chat).sessionId }
    }

    companion object {
        /** The drafts the app keeps for one profile, the unsaved new chat among them. */
        const val DEFAULT_CAPACITY = 16
    }
}

/** Whether the draft holds something the user would miss: words, or files picked for the message. */
val ChatDraft.hasContent: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()
