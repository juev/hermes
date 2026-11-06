package org.evsyukov.hermes.ui.chat

/**
 * The composer drafts of every server profile the app has seen, for the lifetime of the process.
 * A profile is identified by its stable id, not by the connection built for it, so switching the
 * active server, changing its route or rebuilding its session finds the same drafts. Nothing is
 * written to disk; a profile removal drops its store.
 */
class ProfileDraftMemory(slotCapacity: Int = ChatDraftMemory.DEFAULT_CAPACITY) {
    private val lock = Any()
    private val slots = slotCapacity
    private val stores = HashMap<String, ChatDraftMemory>()

    /** How many drafts one profile keeps, the unsaved new chat among them. */
    val capacity: Int = slotCapacity

    fun of(profileId: String): ChatDraftMemory = synchronized(lock) {
        stores.getOrPut(profileId) { ChatDraftMemory(slots) }
    }

    /** Captures an existing target without creating memory for a retired profile. */
    fun existing(profileId: String): ChatDraftMemory? = synchronized(lock) { stores[profileId] }

    /** Called only inside the existing authority/cleanup admission; no Snapshot state is mutated. */
    fun forgetCaptured(profileId: String, memory: ChatDraftMemory?) = synchronized(lock) {
        if (memory != null && stores[profileId] === memory) {
            memory.clear()
            stores.remove(profileId)
        }
    }

    fun forgetCaptured(profileId: String, sessionId: String, memory: ChatDraftMemory?) = synchronized(lock) {
        if (memory != null && stores[profileId] === memory) memory.forget(sessionId)
    }

    /** Drops every draft of a profile, e.g. after an explicit sign-out. */
    fun forget(profileId: String) = synchronized(lock) {
        stores.remove(profileId)
    }

    /** Drops one chat's draft without creating a store for a profile no longer retained. */
    fun forget(profileId: String, sessionId: String) = synchronized(lock) {
        stores[profileId]?.forget(sessionId)
    }

    /** Keeps stores only for profiles that still exist; the rest is gone for good. */
    fun retain(profileIds: Set<String>) = synchronized(lock) {
        stores.keys.retainAll(profileIds)
    }

    /** The profiles whose drafts are still in memory, for tests and diagnostics. */
    fun retainedProfiles(): Set<String> = synchronized(lock) { stores.keys.toSet() }
}
