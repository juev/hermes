package org.evsyukov.hermes.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.data.remote.ChatSummary
import java.util.concurrent.ConcurrentHashMap

private val Context.readStore by preferencesDataStore(name = "read")

/**
 * When this phone last showed the messages of a chat, per server and chat. The dashboard keeps the
 * same watermark on its own (`sessions.last_read_at`, set by `PATCH /api/sessions/{id}` with
 * `unread: false`), but that route refuses the chats that live in the dashboard process, so the
 * phone keeps its own mark and merges the two. The address of a chat is its stored id; a mark
 * survives switching chats, reconnecting and a restart of the app. Beside the marks it keeps the
 * newest answer found in each chat, so an unread answer stays unread when the history cannot be read.
 */
class LocalReads(private val context: Context) {
    /**
     * The activity each chat was looked through to, per server, in this process. It spares a look, and
     * settles a chat the server calls unread; without it the server's word stands.
     */
    private val looked = ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>()

    /** Counts what a server forgot, so a look under way when it forgot does not bring it back. */
    private val epochs = ConcurrentHashMap<String, Long>()

    suspend fun of(server: String): Map<String, Double> = decode(context.readStore.data.first()[key(server)])

    /** When the newest answer found in each chat was written. */
    suspend fun answers(server: String): Map<String, Double> = decode(context.readStore.data.first()[answerKey(server)])

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

    /**
     * [chats] of [server] with the unread mark (see [withUnread]). The chats whose activity went past
     * their mark since the last look are looked through first, a few at a time: [find] gives the time
     * of the newest answer after a mark, null for none. A look that fails changes nothing, and the next
     * one tries again; what the looks found only grows, so a look that ends late takes nothing back.
     */
    suspend fun unread(server: String, chats: List<ChatSummary>, find: suspend (id: String, after: Double) -> Double?): List<ChatSummary> {
        baseline(server, chats)
        val epoch = epochs[server] ?: 0L
        val readAt = of(server)
        val answers = answers(server)
        val gate = Semaphore(LOOKS)
        coroutineScope {
            for (chat in chats) {
                if (!needsLook(chat, readAt, answers, looked[server].orEmpty())) continue
                val mark = readMark(chat, readAt) ?: continue
                launch {
                    gate.withPermit {
                        try {
                            found(server, chat.id, find(chat.id, mark), chat.lastActive, epoch)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Unknown is not read: the chat stays as it was known until a look succeeds.
                        }
                    }
                }
            }
        }
        // Read the marks again: the chat opened while the looks went on is read now.
        return withUnread(chats, of(server), answers(server), looked[server].orEmpty())
    }

    /** A look through [id] up to its activity [upTo] found the answer of [at], or none; dropped when the server forgot since. */
    private suspend fun found(server: String, id: String, at: Double?, upTo: Double, epoch: Long) {
        context.readStore.edit { prefs ->
            if ((epochs[server] ?: 0L) != epoch) return@edit
            if (at != null) {
                val answers = decode(prefs[answerKey(server)]).toMutableMap()
                if ((answers[id] ?: Double.NEGATIVE_INFINITY) < at) {
                    answers[id] = at
                    prefs[answerKey(server)] = encode(answers)
                }
            }
            looked.getOrPut(server) { ConcurrentHashMap() }.merge(id, upTo) { a, b -> maxOf(a, b) }
        }
    }

    suspend fun forget(server: String, id: String) {
        context.readStore.edit { prefs ->
            epochs.merge(server, 1L) { a, b -> a + b }
            looked[server]?.remove(id)
            for (key in listOf(key(server), answerKey(server))) {
                val marks = decode(prefs[key]).toMutableMap()
                if (marks.remove(id) != null) prefs[key] = encode(marks)
            }
        }
    }

    /** Signing out or removing a server drops what the phone remembers of its read state. */
    suspend fun clear(server: String) {
        context.readStore.edit {
            epochs.merge(server, 1L) { a, b -> a + b }
            looked.remove(server)
            it.remove(key(server))
            it.remove(answerKey(server))
        }
    }

    private fun key(server: String) = stringPreferencesKey("read:$server")

    private fun answerKey(server: String) = stringPreferencesKey("answer:$server")

    private fun decode(raw: String?): Map<String, Double> =
        if (raw == null) emptyMap() else runCatching { json.decodeFromString<Map<String, Double>>(raw) }.getOrDefault(emptyMap())

    private fun encode(reads: Map<String, Double>): String = json.encodeToString(reads)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Histories read at once when looking for answers. */
        const val LOOKS = 4
    }
}
