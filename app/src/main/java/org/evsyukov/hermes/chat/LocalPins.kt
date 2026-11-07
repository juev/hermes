package org.evsyukov.hermes.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.evsyukov.hermes.data.remote.ChatSummary

private val Context.pinStore by preferencesDataStore(name = "pins")

/**
 * Chats pinned on this phone, per server: those the server refuses to pin (see [ChatMutations.pin]).
 * They live apart from the cache of answers, which the user may clear and a new schema empties.
 */
class LocalPins(private val context: Context, backing: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null) {
    private val data = backing ?: context.pinStore
    suspend fun of(server: String): Set<String> = data.data.first()[key(server)].orEmpty()

    @Volatile
    internal var beforeSet: (suspend () -> Unit)? = null

    suspend fun set(server: String, id: String, pinned: Boolean) {
        beforeSet?.invoke()
        setNow(server, id, pinned) { true }
    }

    suspend fun setOwned(owner: org.evsyukov.hermes.data.server.WorkOwner, lifetime: org.evsyukov.hermes.data.server.ProfileLifetime, id: String, pinned: Boolean, wanted: () -> Boolean) {
        beforeSet?.invoke()
        lifetime.withOwner(owner) { setNow(owner.profileId, id, pinned, wanted) }
    }

    private suspend fun setNow(server: String, id: String, pinned: Boolean, wanted: () -> Boolean) {
        data.edit {
            if (!wanted()) return@edit
            val pins = it[key(server)].orEmpty()
            it[key(server)] = if (pinned) pins + id else pins - id
        }
    }

    private fun key(server: String) = stringSetPreferencesKey("pins:$server")
}

/**
 * [page] with the chats in [pins] marked pinned. A pinned chat the page does not reach is added
 * from [fetch] when [admits] takes it, as the server itself adds its pinned chats to the first page.
 */
suspend fun withLocalPins(
    page: List<ChatSummary>,
    pins: Set<String>,
    admits: (ChatSummary) -> Boolean,
    fetch: suspend (String) -> ChatSummary?,
): List<ChatSummary> {
    val listed = page.mapTo(HashSet()) { it.id }
    val beyond = (pins - listed).mapNotNull { fetch(it) }.filter(admits)
    return (page + beyond).map { if (!it.pinned && it.id in pins) it.copy(pinned = true) else it }
}
