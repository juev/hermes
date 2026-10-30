package org.evsyukov.hermes.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.data.remote.ChatSummary

private val Context.readStore by preferencesDataStore(name = "read")

/**
 * When this phone last showed the messages of a chat, per server and chat. The dashboard keeps the
 * same watermark on its own (`sessions.last_read_at`, set by `PATCH /api/sessions/{id}` with
 * `unread: false`), but that route refuses the chats that live in the dashboard process, so the
 * phone keeps its own mark and merges the two. The address of a chat is its stored id; a mark
 * survives switching chats, reconnecting and a restart of the app.
 */
class LocalReads(private val context: Context) {
    suspend fun of(server: String): Map<String, Double> = decode(context.readStore.data.first()[key(server)])

    /**
     * The chat is read up to [at] (the server's `last_active` of what is on the screen). A mark
     * never goes back: a slow answer cannot unsee what a later one showed.
     */
    suspend fun markRead(server: String, id: String, at: Double) {
        context.readStore.edit { prefs ->
            val reads = decode(prefs[key(server)]).toMutableMap()
            if ((reads[id] ?: Double.NEGATIVE_INFINITY) >= at) return@edit
            reads[id] = at
            prefs[key(server)] = encode(reads)
        }
    }

    /**
     * The first sight of a chat counts as read at its current activity, the way the server reads a
     * chat it never tracked. A chat the server itself reports unread is left unmarked: it stays
     * unread until the user opens it.
     */
    suspend fun baseline(server: String, chats: List<ChatSummary>) {
        context.readStore.edit { prefs ->
            val reads = decode(prefs[key(server)]).toMutableMap()
            var changed = false
            for (chat in chats) {
                if (chat.id in reads || chat.unread) continue
                reads[chat.id] = chat.lastActive
                changed = true
            }
            if (changed) prefs[key(server)] = encode(reads)
        }
    }

    suspend fun forget(server: String, id: String) {
        context.readStore.edit { prefs ->
            val reads = decode(prefs[key(server)]).toMutableMap()
            if (reads.remove(id) == null) return@edit
            prefs[key(server)] = encode(reads)
        }
    }

    /** Signing out or removing a server drops what the phone remembers of its read state. */
    suspend fun clear(server: String) {
        context.readStore.edit { it.remove(key(server)) }
    }

    private fun key(server: String) = stringPreferencesKey("read:$server")

    private fun decode(raw: String?): Map<String, Double> =
        if (raw == null) emptyMap() else runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrDefault(emptyMap())

    private fun encode(reads: Map<String, Double>): String = json.encodeToString(reads)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
